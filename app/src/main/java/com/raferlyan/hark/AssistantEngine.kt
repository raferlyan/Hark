package com.raferlyan.hark

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 一条麦克风流驱动的状态机：
 *
 *   IDLE ──KWS 命中──▶ LISTENING ──端点/VAD 断句──▶ onCommand ──▶ WAITING
 *                         ▲                                           │
 *                         └────────── listenAgain()（连续对话）────────┤
 *   IDLE ◀──────────── endSession() / 聆听超时 ◀────────────────────────┘
 *
 * 普通话模式：流式 zipformer + modified_beam_search，热词实时生效，边说边出字。
 * 方言/SenseVoice 模式：silero VAD 切出一句话，再交给离线模型整句识别。
 * 讯飞模式：silero VAD 断句，检测到说话才连讯飞、从句首开始上传，断句后让讯飞出最终结果。
 * 本地普通话模型只加载不解码，不占 CPU；讯飞报错或迟迟不出结果时，才把这句录音交给它识别一次。
 *
 * 声纹校验（可选）：唤醒词那段音频提取声纹锁定本次会话，之后每句指令先比声纹，
 * 不是唤醒人说的就丢掉、继续聆听。打断时重新说唤醒词的人会成为新的锁定对象。
 *
 * 启动顺序：唤醒模型很小（0.2 s 左右）先同步加载，加载完立刻开始监听；识别模型（普通话流式要好几秒）
 * 和声纹模型在另一个线程里加载。加载期间被唤醒，指令音频先攒着，模型一好就补进去识别，用户不用等。
 */
class AssistantEngine(
    private val ctx: Context,
    private val settings: AppSettings,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** 唤醒词命中，引擎已进入聆听。在引擎线程调用，可以直接在这里开始播报「我在」。 */
        fun onWake(keyword: String)
        /** 识别出一句话。引擎随即进入 WAITING，直到 [listenAgain] 或 [endSession]。 */
        fun onCommand(text: String)
        /** 聆听超时没听到话（或识别为空），已回到待唤醒。 */
        fun onSessionEnd()
        /**
         * 播报回复或等大模型时又听到了唤醒词（打断）。在引擎线程调用，必须在这里同步停掉 TTS；
         * 返回后引擎直接进入聆听，不再播「我在」。
         */
        fun onBargeIn(keyword: String)
    }

    private sealed interface Request
    private data class Listen(val timeoutSec: Float) : Request
    private object End : Request
    @Volatile private var request: Request? = null

    /** 回复播完后继续听下一句，不用再喊唤醒词。任意线程可调用。 */
    fun listenAgain(timeoutSec: Float) { request = Listen(timeoutSec) }

    /** 结束本次会话，回到待唤醒。任意线程可调用。 */
    fun endSession() { request = End }

    private val paths = ModelPaths(ctx, settings)
    @Volatile private var running = false
    private var thread: Thread? = null

    private var kws: KeywordSpotter? = null
    private var kwsStream: OnlineStream? = null
    // 下面这些由加载线程赋值、引擎线程使用；引擎线程只在 asrReady == true 之后才碰它们
    @Volatile private var online: OnlineRecognizer? = null
    @Volatile private var offline: OfflineRecognizer? = null
    @Volatile private var vad: Vad? = null
    /** 识别模型加载状态：null 加载中，true 可用，false 加载失败。 */
    @Volatile private var asrReady: Boolean? = null
    @Volatile private var voicePrint: VoicePrint? = null
    /** 本次会话锁定的唤醒人声纹；null 表示不校验（没开、模型缺失或唤醒词太短）。 */
    private var sessionVoice: VoicePrint.Embedding? = null
    /** 唤醒模型最近吃进去的音频，命中时从这里取唤醒词那一段提取声纹。 */
    private val recent = RingBuffer((WAKE_AUDIO_SEC * SAMPLE_RATE).toInt())
    private var corrections: List<Pair<String, String>> = emptyList()
    /** 讯飞模式且填了密钥时非 null。 */
    private var xfConfig: XfIat.Config? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "hark-engine").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(3000)
        thread = null
    }

    private fun run() {
        var loader: Thread? = null
        try {
            AssistantBus.state(EngineState.LOADING)
            if (!loadWake()) {
                AssistantBus.state(EngineState.ERROR)
                return
            }
            if (settings.kwsTestMode) {
                AssistantBus.log("唤醒测试模式：只统计唤醒，不加载识别模型")
            } else {
                loader = Thread(::loadAsrInBackground, "hark-load").apply { start() }
            }
            loop()
            AssistantBus.state(EngineState.STOPPED)
        } catch (t: Throwable) {
            AssistantBus.log("引擎异常：${t.javaClass.simpleName}: ${t.message}")
            AssistantBus.state(EngineState.ERROR)
        } finally {
            // 模型加载到一半没法打断，等它加载完再一起释放
            loader?.join()
            release()
        }
    }

    // ---------------------------------------------------------------- 模型加载

    private val prep by lazy { TextPrep(ctx) }
    private val feat = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)

    /** 检查模型文件、加载唤醒模型。返回 false 表示引擎起不来。 */
    private fun loadWake(): Boolean {
        val missing = paths.missingFor(settings.asrMode)
        if (missing.isNotEmpty()) {
            AssistantBus.log("缺少模型文件（请先运行 tools/push_models.sh）：")
            missing.forEach { AssistantBus.log("  ✗ $it") }
            if (settings.asrMode == AsrMode.MANDARIN_STREAMING || settings.asrMode == AsrMode.IFLYTEK) {
                AssistantBus.log("当前流式模型：${settings.streamModel.label}，推送：tools/push_models.sh ${settings.streamModel.group}")
            }
            return false
        }

        // 1) 唤醒词
        val kw = prep.prepareKeywords(settings.keywords, paths.kwsTokens)
        kw.warnings.forEach { AssistantBus.log("⚠ $it") }
        if (kw.count == 0) {
            AssistantBus.log("没有可用的唤醒词")
            return false
        }
        val kwFile = File(ctx.filesDir, "keywords.txt").apply { writeText(kw.content + "\n") }
        AssistantBus.log("唤醒词：\n${kw.content}")
        timed("加载唤醒模型") {
            kws = KeywordSpotter(
                assetManager = null,
                config = KeywordSpotterConfig(
                    featConfig = feat,
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = paths.kwsEncoder,
                            decoder = paths.kwsDecoder,
                            joiner = paths.kwsJoiner,
                        ),
                        tokens = paths.kwsTokens,
                        modelType = "zipformer2",
                        numThreads = 1,
                    ),
                    keywordsFile = kwFile.absolutePath,
                    keywordsScore = settings.kwsScore,
                    keywordsThreshold = settings.kwsThreshold,
                    numTrailingBlanks = settings.kwsTrailingBlanks,
                ),
            )
            kwsStream = kws!!.createStream()
        }
        corrections = TextPrep.parseCorrections(settings.corrections)
        return true
    }

    private fun loadAsrInBackground() {
        val t0 = SystemClock.elapsedRealtime()
        asrReady = try {
            loadAsr()
            AssistantBus.log("识别模型就绪，启动后 ${SystemClock.elapsedRealtime() - t0} ms")
            true
        } catch (t: Throwable) {
            AssistantBus.log("识别模型加载失败：${t.javaClass.simpleName}: ${t.message}")
            // 讯飞模式丢的只是兜底，还能继续用讯飞
            xfConfig != null && vad != null
        }
    }

    /** 识别模型和声纹模型，在 hark-load 线程里跑。 */
    private fun loadAsr() {
        when (settings.asrMode) {
            AsrMode.MANDARIN_STREAMING, AsrMode.IFLYTEK -> {
                if (settings.asrMode == AsrMode.IFLYTEK) {
                    xfConfig = XfIat.Config.from(settings)
                    AssistantBus.log(
                        xfConfig?.let {
                            "讯飞听写：accent=${it.accent}，方言免切=${it.dialectFree}，标点=${it.punctuation}，" +
                                "动态修正=${it.dynamicCorrection}，后端断句 ${it.eosMs}ms；VAD 断句，本地普通话模型只在讯飞失败时兜底"
                        } ?: "⚠ 讯飞 APPID / APIKey / APISecret 没填全，只用本地普通话识别",
                    )
                    if (xfConfig != null) {
                        loadVad()
                        // 讯飞有 VAD 就能用，不用等本地模型：弱 CPU 上它要加载很久
                        asrReady = true
                    }
                }
                val sm = settings.streamModel
                // 热词靠 modified_beam_search，只有 transducer 模型能用；CTC 模型只能靠纠错表兜底
                val hw = if (sm.ctc) {
                    if (settings.hotwords.isNotBlank()) AssistantBus.log("⚠ ${sm.label} 是 CTC 模型，不支持热词，热词不生效")
                    TextPrep.Prepared("", 0, emptyList())
                } else {
                    prep.prepareHotwords(settings.hotwords, paths.streamTokens)
                }
                hw.warnings.forEach { AssistantBus.log("⚠ $it") }
                val hwFile = File(ctx.filesDir, "hotwords.txt").apply { writeText(hw.content + "\n") }
                if (!sm.ctc) AssistantBus.log("热词 ${hw.count} 个，hotwordsScore=${settings.hotwordsScore}")
                timed("加载普通话流式模型（${sm.label}）") {
                    online = OnlineRecognizer(
                        assetManager = null,
                        config = OnlineRecognizerConfig(
                            featConfig = feat,
                            modelConfig = if (sm.ctc) {
                                OnlineModelConfig(
                                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = paths.streamCtc),
                                    tokens = paths.streamTokens,
                                    numThreads = settings.numThreads,
                                )
                            } else {
                                OnlineModelConfig(
                                    transducer = OnlineTransducerModelConfig(
                                        encoder = paths.streamEncoder,
                                        decoder = paths.streamDecoder,
                                        joiner = paths.streamJoiner,
                                    ),
                                    tokens = paths.streamTokens,
                                    numThreads = settings.numThreads,
                                    modelingUnit = "cjkchar",
                                )
                            },
                            endpointConfig = EndpointConfig(
                                // 「一直不说话」的超时由主循环自己计时（播报期间不算），这里设成很长
                                rule1 = EndpointRule(false, MAX_UTTERANCE_SEC * 2, 0f),
                                // 说完一句后的静音
                                rule2 = EndpointRule(true, settings.endSilenceSec, 0f),
                                rule3 = EndpointRule(false, 0f, MAX_UTTERANCE_SEC),
                            ),
                            enableEndpoint = true,
                            // 热词只在 modified_beam_search 下生效
                            decodingMethod = if (hw.count > 0) "modified_beam_search" else "greedy_search",
                            maxActivePaths = 4,
                            hotwordsFile = if (hw.count > 0) hwFile.absolutePath else "",
                            hotwordsScore = settings.hotwordsScore,
                        ),
                    )
                }
            }

            AsrMode.DIALECT_TELESPEECH, AsrMode.SENSEVOICE, AsrMode.RKNN_SENSEVOICE -> {
                val model = when (settings.asrMode) {
                    AsrMode.DIALECT_TELESPEECH -> OfflineModelConfig(
                        teleSpeech = paths.teleModel,
                        tokens = paths.teleTokens,
                        modelType = "telespeech_ctc",
                        numThreads = settings.numThreads,
                    )
                    AsrMode.RKNN_SENSEVOICE -> {
                        // NPU 初始化或运行出错时 sherpa-onnx 会直接退出进程，这里接不住，先留下线索
                        AssistantBus.log("使用 RK NPU（provider=rknn）。如果应用闪退，看 logcat 里的 sherpa-onnx 和 RKNN 日志")
                        OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = paths.rknnSvModel,
                                language = settings.senseVoiceLang.takeIf { it != "auto" } ?: "",
                                useInverseTextNormalization = true,
                            ),
                            tokens = paths.rknnSvTokens,
                            // rknn 下这个参数是 NPU 核掩码，1 表示自动选核，不是 CPU 线程数
                            numThreads = 1,
                            provider = "rknn",
                        )
                    }
                    else -> OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = paths.svModel,
                            language = settings.senseVoiceLang.takeIf { it != "auto" } ?: "",
                            useInverseTextNormalization = true,
                        ),
                        tokens = paths.svTokens,
                        numThreads = settings.numThreads,
                    )
                }
                timed("加载${settings.asrMode.label}") {
                    offline = OfflineRecognizer(
                        assetManager = null,
                        config = OfflineRecognizerConfig(featConfig = feat, modelConfig = model),
                    )
                }
                loadVad()
            }
        }

        // 声纹放在识别后面：识别是刚需，声纹缺了只是本次不校验
        if (settings.speakerVerify) {
            if (File(paths.speaker).isFile) {
                timed("加载声纹模型") { voicePrint = VoicePrint(paths.speaker, 1) }
                AssistantBus.log("声纹校验已开启，阈值 ${settings.speakerThreshold}")
            } else {
                AssistantBus.log("⚠ 缺少声纹模型，本次不做声纹校验（tools/push_models.sh speaker）：\n  ✗ ${paths.speaker}")
            }
        }
    }

    private fun loadVad() {
        timed("加载 VAD") {
            vad = Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = paths.vad,
                        threshold = settings.vadThreshold,
                        minSilenceDuration = settings.endSilenceSec,
                        minSpeechDuration = 0.25f,
                        windowSize = VAD_WINDOW,
                        maxSpeechDuration = MAX_UTTERANCE_SEC,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ),
            )
        }
    }

    private inline fun timed(what: String, block: () -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
        block()
        AssistantBus.log("$what 完成，用时 ${SystemClock.elapsedRealtime() - t0} ms")
    }

    private fun release() {
        kwsStream?.release(); kwsStream = null
        kws?.release(); kws = null
        online?.release(); online = null
        offline?.release(); offline = null
        vad?.release(); vad = null
        voicePrint?.release(); voicePrint = null
    }

    // ---------------------------------------------------------------- 主循环

    private enum class Phase { IDLE, LISTENING, WAITING }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // VOICE_COMMUNICATION 会走平台的回声消除通路，播报时说唤醒词更容易被听到
        val record = AudioRecord(
            if (settings.echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, SAMPLE_RATE * 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            AssistantBus.log("AudioRecord 初始化失败（检查录音权限 / 是否被其他应用占用）")
            AssistantBus.state(EngineState.ERROR)
            return
        }
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        val aec = if (settings.echoCancel && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        } else null
        AssistantBus.log("录音源 ${if (settings.echoCancel) "VOICE_COMMUNICATION" else "VOICE_RECOGNITION"}，" +
            "回声消除 ${if (aec?.enabled == true) "已开启" else if (AcousticEchoCanceler.isAvailable()) "未开启" else "设备不支持"}")

        var phase = Phase.IDLE
        var asrStream: OnlineStream? = null
        var listenStart = 0L
        var listenTimeoutMs = 0L
        // 聆听超时按实际处理到的音频算，不按墙上时间：识别跟不上实时、音频还在队列里排着时，不能先判超时
        var listenSamples = 0L
        // 诊断：本次聆听处理了多少音频、花了多少 CPU 时间（算实时率）
        var listenBusyNs = 0L
        var listenAudioSamples = 0L
        // 诊断：播报回复期间麦克风的峰值，用来判断打断时唤醒词是不是被回声盖住了
        var echoPeakDb = -100f
        var speechSeen = false
        var speechStart = 0
        var lastPartial = ""
        val utterance = FloatBuffer()
        var levelTick = 0
        var wasSpeaking = false
        // 诊断：本次聆听期间的最大电平、因 TTS 播报被丢弃的帧数
        var listenPeakDb = -100f
        var listenMuted = 0
        // 本次聆听里因为声纹不符被丢掉的句数
        var rejected = 0
        // 本次聆听是回复之后的连续对话（不是刚唤醒）
        var followUp = false
        // 讯飞会话，检测到说话才建；cloudEndAt > 0 表示 VAD 已断句、已发结束帧，在等讯飞最终结果
        var cloud: XfIat? = null
        var cloudEndAt = 0L
        var cloudFailLogged = false
        // 唤醒时识别模型还在后台加载：这段指令音频先攒着，加载完一次性补上
        val pending = ArrayList<FloatArray>()

        fun dropCloud() {
            cloud?.cancel()
            cloud = null
            cloudEndAt = 0L
            cloudFailLogged = false
        }

        // 录音单独一个线程，只管读麦克风往队列里放。识别模型在弱 CPU（如 RK3562）上跟不上实时的时候，
        // 音频在队列里排队而不是在 AudioRecord 缓冲区里溢出丢掉——以前就是这样，说了五六秒只录进去一秒多。
        val queue = LinkedBlockingQueue<Chunk>(MAX_BACKLOG_CHUNKS)
        var overflowed = 0
        record.startRecording()
        val mic = Thread({
            val pcm = ShortArray(VAD_WINDOW)
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                // 播报状态在录下这一帧时就记下来，排队晚处理也不会把「我在」算进指令、或把指令当回声丢掉
                val c = Chunk(FloatArray(n) { pcm[it] / 32768f }, AssistantBus.speaking)
                if (!queue.offer(c)) {
                    queue.poll()
                    queue.offer(c)
                    overflowed++
                }
            }
        }, "hark-mic").apply { start() }
        AssistantBus.state(EngineState.IDLE)
        AssistantBus.log("开始监听麦克风（${SAMPLE_RATE}Hz）")

        /** 本次聆听的处理速度，写进日志用来判断设备是不是算不过来。 */
        fun perf(): String {
            val audioMs = listenAudioSamples * 1000 / SAMPLE_RATE
            val rtf = if (listenAudioSamples > 0) listenBusyNs / 1e6 / audioMs else 0.0
            val backlogMs = queue.size.toLong() * VAD_WINDOW * 1000 / SAMPLE_RATE
            return "处理音频 ${audioMs}ms，实时率 %.2f，积压 ${backlogMs}ms".format(rtf) +
                (if (overflowed > 0) "，积压过多丢弃 $overflowed 帧" else "") +
                (if (rtf > 1.0) "（>1 表示识别跟不上说话，可调大线程数或换小模型）" else "")
        }

        fun backToIdle() {
            pending.clear()
            asrStream?.release(); asrStream = null
            dropCloud()
            vad?.reset()
            utterance.clear()
            kws!!.reset(kwsStream!!)
            phase = Phase.IDLE
            AssistantBus.state(EngineState.IDLE)
        }

        fun startListening(timeoutSec: Float) {
            if (echoPeakDb > -100f) {
                AssistantBus.log("上一段播报期间麦克风峰值 %.0f dB".format(echoPeakDb))
                echoPeakDb = -100f
            }
            phase = Phase.LISTENING
            listenStart = SystemClock.elapsedRealtime()
            listenTimeoutMs = (timeoutSec * 1000).toLong()
            listenSamples = 0L
            listenBusyNs = 0L
            listenAudioSamples = 0L
            speechSeen = false
            listenPeakDb = -100f
            listenMuted = 0
            rejected = 0
            followUp = false
            lastPartial = ""
            utterance.clear()
            pending.clear()
            asrStream?.release()
            asrStream = if (xfConfig == null) online?.createStream() else null
            dropCloud()
            vad?.reset()
            AssistantBus.state(EngineState.LISTENING)
            AssistantBus.partial("")
        }

        /** 唤醒/打断时调用：用唤醒词那段音频锁定本次会话的说话人。 */
        fun lockSpeaker() {
            val vp = voicePrint ?: return
            val t0 = SystemClock.elapsedRealtime()
            sessionVoice = vp.embed(recent.toArray())
            val ms = SystemClock.elapsedRealtime() - t0
            AssistantBus.log(
                sessionVoice?.let { "声纹已锁定（有效语音 ${it.voicedMs}ms，用时 ${ms}ms）" }
                    ?: "唤醒词有效语音太短，本次会话不校验声纹",
            )
        }

        /**
         * 声纹不是唤醒人就返回 null，丢掉这句、接着听；是唤醒人（或不校验）返回写进日志的说明。
         */
        fun checkSpeaker(audio: FloatArray, text: String): String? {
            val locked = sessionVoice ?: return ""
            val e = voicePrint!!.embed(audio) ?: return "声纹：有效语音太短，未校验"
            val sim = VoicePrint.similarity(locked.vector, e.vector)
            if (sim >= settings.speakerThreshold) return "声纹 %.2f".format(sim)

            val note = "声纹不符 %.2f < %.2f".format(sim, settings.speakerThreshold)
            rejected++
            AssistantBus.log("✗ $note，忽略「${text.trim()}」（本次第 $rejected 句）")
            if (settings.saveAudio) saveWav(audio, "rejected")
            AssistantBus.utterance(Utterance(audio, "", text.trim(), note, settings.asrMode))
            AssistantBus.partial("")
            if (rejected >= MAX_REJECTED) {
                AssistantBus.log("连续 $rejected 句都不是唤醒人，结束会话")
                AssistantBus.final("")
                backToIdle()
                callbacks.onSessionEnd()
                return null
            }
            // 接着等唤醒人说话，超时重新算，别让别人的长句把唤醒人的时间耗光
            listenStart = SystemClock.elapsedRealtime()
            listenSamples = 0L
            speechSeen = false
            lastPartial = ""
            utterance.clear()
            asrStream?.release()
            asrStream = if (xfConfig == null) online?.createStream() else null
            dropCloud()
            vad?.reset()
            AssistantBus.state(EngineState.LISTENING)
            return null
        }

        fun noCommand() {
            AssistantBus.log("没有听到指令，回到待唤醒（峰值 %.0f dB，静音丢弃 %d 帧，%s）".format(listenPeakDb, listenMuted, perf()))
            val audio = utterance.toArray()
            // 超时的录音也留着，方便排查“明明说了却没听到”
            if (settings.saveAudio) saveWav(audio, "timeout")
            // 连续对话里没人接话是常态，别让那几秒底噪顶掉调试面板上真正有用的上一句
            if (!followUp && audio.isNotEmpty()) AssistantBus.utterance(Utterance(audio, "", "", "超时，没识别到指令", settings.asrMode))
            AssistantBus.final("")
            backToIdle()
            callbacks.onSessionEnd()
        }

        fun finish(rawText: String, audio: FloatArray, extra: String = "") {
            val text = TextPrep.applyCorrections(rawText.trim(), corrections)
            val cost = SystemClock.elapsedRealtime() - listenStart
            AssistantBus.log(
                "识别结果：「$text」" + (if (text != rawText.trim()) "（原始「${rawText.trim()}」）" else "") +
                    " $extra 唤醒后 ${cost}ms，${perf()}",
            )
            if (settings.saveAudio) saveWav(utterance.toArray(), text)
            AssistantBus.utterance(Utterance(audio, text, rawText.trim(), extra, settings.asrMode))
            AssistantBus.final(text)
            if (text.isEmpty()) {
                backToIdle()
                callbacks.onSessionEnd()
                return
            }
            asrStream?.release(); asrStream = null
            dropCloud()
            vad?.reset()
            utterance.clear()
            kws!!.reset(kwsStream!!) // WAITING 期间要靠它听打断
            phase = Phase.WAITING
            AssistantBus.state(EngineState.WAITING)
            callbacks.onCommand(text)
        }

        fun accept(result: String, audio: FloatArray, note: String) {
            checkSpeaker(audio, result)?.let { v ->
                finish(result, audio, listOf(note, v).filter { it.isNotEmpty() }.joinToString("，"))
            }
        }

        /** 讯飞没给出结果时，用本地模型把这句录音识别一次。只有这时才占 CPU。 */
        fun decodeLocally(audio: FloatArray, why: String) {
            dropCloud()
            val rec = online
            if (rec == null) {
                AssistantBus.log("$why，本地模型还没加载好，这句没法识别")
                noCommand()
                return
            }
            AssistantBus.state(EngineState.RECOGNIZING)
            val t0 = SystemClock.elapsedRealtime()
            val s = rec.createStream()
            s.acceptWaveform(audio, SAMPLE_RATE)
            // 流式模型有前瞻，补一段静音才能把句尾的字解出来
            s.acceptWaveform(FloatArray(LOCAL_TAIL_SAMPLES), SAMPLE_RATE)
            s.inputFinished()
            while (rec.isReady(s)) rec.decode(s)
            val text = rec.getResult(s).text
            s.release()
            val note = "$why，用本地结果（音频 ${audio.size * 1000L / SAMPLE_RATE}ms，解码 ${SystemClock.elapsedRealtime() - t0}ms）"
            if (text.isBlank()) {
                AssistantBus.log("$note，本地也没识别出文字")
                noCommand()
            } else {
                accept(text, audio, note)
            }
        }

        /** 讯飞模式的一帧：VAD 判断说没说话，音频只在说话时上传。 */
        fun listenCloud(chunk: FloatArray, cfg: XfIat.Config) {
            val v = vad!!
            var ended = false
            if (cloudEndAt == 0L) {
                v.acceptWaveform(chunk)
                val detected = v.isSpeechDetected()
                if (!v.empty()) {
                    v.pop()
                    ended = true
                }
                if (!speechSeen && (detected || ended)) {
                    speechSeen = true
                    // VAD 确认说话时已经晚了几百毫秒，句首从缓存里补上；这一帧已经在 utterance 里了
                    speechStart = max(0, utterance.size - (VAD_PREROLL_SEC * SAMPLE_RATE).toInt())
                    AssistantBus.partial("（检测到说话…）")
                    cloud = XfIat(cfg).apply {
                        start()
                        feed(utterance.slice(speechStart))
                    }
                } else if (speechSeen) {
                    cloud?.feed(chunk)
                }
            }
            val c = cloud
            if (c == null) {
                if (!speechSeen && listenSamples * 1000 / SAMPLE_RATE > listenTimeoutMs) noCommand()
                return
            }
            val failed = c.error
            if (failed != null && !cloudFailLogged) {
                cloudFailLogged = true
                AssistantBus.log("讯飞不可用：$failed")
            }
            if (failed == null && c.text.isNotEmpty() && c.text != lastPartial) {
                lastPartial = c.text
                AssistantBus.partial(c.text)
            }
            if (ended && failed == null && !c.finished) {
                c.end()
                cloudEndAt = SystemClock.elapsedRealtime()
                AssistantBus.state(EngineState.RECOGNIZING)
            }
            fun speech() = utterance.slice(speechStart)
            when {
                failed != null -> if (ended || cloudEndAt > 0L) decodeLocally(speech(), "讯飞失败")
                c.finished -> {
                    if (c.text.isNotBlank()) {
                        accept(c.text, speech(), "讯飞 ${c.timing()}")
                    } else {
                        // VAD 把噪声当成说话时讯飞会返回空结果，别结束会话，接着等用户开口；超时还按原来算
                        AssistantBus.log("讯飞没识别出文字（可能是噪声），继续听")
                        dropCloud()
                        speechSeen = false
                        lastPartial = ""
                        v.reset()
                        AssistantBus.state(EngineState.LISTENING)
                        AssistantBus.partial("")
                    }
                }
                cloudEndAt > 0L && SystemClock.elapsedRealtime() - cloudEndAt > XF_FINAL_WAIT_MS -> {
                    AssistantBus.log("讯飞 ${XF_FINAL_WAIT_MS}ms 内没出最终结果（${c.timing()}）")
                    if (c.text.isNotBlank()) accept(c.text, speech(), "讯飞超时，用中间结果")
                    else decodeLocally(speech(), "讯飞超时")
                }
            }
        }

        /** 聆听阶段处理一帧音频（识别模型已就绪）。 */
        fun listen(chunk: FloatArray) {
            val t0 = System.nanoTime()
            try {
                utterance.add(chunk)
                listenSamples += chunk.size
                listenAudioSamples += chunk.size
                val xf = xfConfig
                val rec = online
                if (xf != null) {
                    listenCloud(chunk, xf)
                } else if (rec != null) {
                    // 唤醒时模型还没加载好，startListening 没建成流，这里补建
                    val s = asrStream ?: rec.createStream().also { asrStream = it }
                    s.acceptWaveform(chunk, SAMPLE_RATE)
                    while (rec.isReady(s)) rec.decode(s)
                    val text = rec.getResult(s).text
                    if (text != lastPartial) {
                        lastPartial = text
                        AssistantBus.partial(text)
                    }
                    when {
                        rec.isEndpoint(s) && text.isNotBlank() -> accept(text, utterance.toArray(), "")
                        text.isBlank() && listenSamples * 1000 / SAMPLE_RATE > listenTimeoutMs -> noCommand()
                    }
                } else {
                    val v = vad!!
                    v.acceptWaveform(chunk)
                    if (!speechSeen && v.isSpeechDetected()) {
                        speechSeen = true
                        // VAD 确认说话时已经晚了几百毫秒，往前多留一段，避免吞掉句首
                        speechStart = max(0, utterance.size - (VAD_PREROLL_SEC * SAMPLE_RATE).toInt())
                        AssistantBus.partial("（检测到说话…）")
                    }
                    if (!v.empty()) {
                        v.pop()
                        val audio = utterance.slice(speechStart)
                        // 不是唤醒人就不用解码了
                        val voiceNote = checkSpeaker(audio, "（未解码）") ?: return
                        AssistantBus.state(EngineState.RECOGNIZING)
                        val t0 = SystemClock.elapsedRealtime()
                        val st = offline!!.createStream()
                        st.acceptWaveform(audio, SAMPLE_RATE)
                        offline!!.decode(st)
                        val r = offline!!.getResult(st)
                        st.release()
                        val dur = audio.size * 1000 / SAMPLE_RATE
                        val extra = buildString {
                            append("音频 ${dur}ms，解码 ${SystemClock.elapsedRealtime() - t0}ms")
                            if (r.lang.isNotEmpty()) append("，语种 ${r.lang}")
                            if (voiceNote.isNotEmpty()) append("，$voiceNote")
                        }
                        finish(r.text, audio, extra)
                    } else if (!speechSeen &&
                        listenSamples * 1000 / SAMPLE_RATE > listenTimeoutMs
                    ) {
                        noCommand()
                    }
                }
            } finally {
                listenBusyNs += System.nanoTime() - t0
            }
        }

        try {
            while (running) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val chunk = item.pcm
                val muted = item.muted

                // 会话控制（来自 VoiceSession，在引擎线程里统一生效）
                request?.let { req ->
                    request = null
                    when (req) {
                        is Listen -> if (phase == Phase.WAITING) {
                            startListening(req.timeoutSec)
                            followUp = true
                        }
                        End -> if (phase != Phase.IDLE) backToIdle()
                    }
                }

                val db = rmsDb(chunk)
                // 浮窗波浪靠它驱动，64ms 一次
                if (++levelTick % 2 == 0) AssistantBus.level(db)
                if (phase == Phase.LISTENING) listenPeakDb = max(listenPeakDb, db)

                // 打断：播报回复 / 等大模型的时候只跑唤醒模型，听到唤醒词就停掉播报重新聆听。
                // 识别模型在播报时不开：没有回声消除，会把自己的声音识别成指令；唤醒词则很难被回复内容误触发。
                if (phase == Phase.WAITING && settings.bargeIn) {
                    if (muted) echoPeakDb = max(echoPeakDb, db)
                    val kw = spot(chunk)
                    if (kw != null) {
                        AssistantBus.log("★ 打断：$kw（播报中=${AssistantBus.speaking}）")
                        callbacks.onBargeIn(kw)
                        AssistantBus.speaking = false
                        wasSpeaking = false
                        startListening(settings.listenTimeoutSec)
                        lockSpeaker()
                    }
                    wasSpeaking = muted
                    continue
                }

                // 自己播报时不听，播完重置唤醒流，避免把 TTS 的尾音当作唤醒词
                if (muted) {
                    wasSpeaking = true
                    if (phase == Phase.LISTENING) listenMuted++
                    continue
                }
                if (wasSpeaking) {
                    wasSpeaking = false
                    kws!!.reset(kwsStream!!)
                    // 「我在」播完才开始算聆听超时
                    if (phase == Phase.LISTENING) {
                        listenStart = SystemClock.elapsedRealtime()
                        listenSamples = 0L
                    }
                }

                when (phase) {
                    Phase.IDLE -> {
                        val kw = spot(chunk) ?: continue
                        AssistantBus.wake()
                        AssistantBus.log("★ 唤醒：$kw  第 ${AssistantBus.wakeCount} 次")
                        if (settings.beepOnWake) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                        if (settings.kwsTestMode) continue

                        startListening(settings.listenTimeoutSec)
                        callbacks.onWake(kw)
                        // 「我在」已经开始播了，这时麦克风反正是静音的，提取声纹不耽误事
                        lockSpeaker()
                    }

                    // 等回复播完；这段时间的音频不用
                    Phase.WAITING -> {}

                    Phase.LISTENING -> when (asrReady) {
                        true -> {
                            // 先补上模型加载期间攒下的音频；中途断句进了 WAITING 就不用再喂了
                            if (pending.isNotEmpty()) {
                                AssistantBus.log("识别模型就绪，补识别加载期间的 ${pending.sumOf { it.size } * 1000L / SAMPLE_RATE}ms 音频")
                                val backlog = pending.toList()
                                pending.clear()
                                for (c in backlog) if (phase == Phase.LISTENING) listen(c)
                            }
                            if (phase == Phase.LISTENING) listen(chunk)
                        }
                        false -> {
                            AssistantBus.log("识别模型加载失败，这次没法识别指令")
                            AssistantBus.final("")
                            backToIdle()
                            callbacks.onSessionEnd()
                        }
                        null -> {
                            if (pending.isEmpty()) AssistantBus.partial("（识别模型加载中，请说…）")
                            pending.add(chunk)
                        }
                    }
                }
            }
        } finally {
            running = false
            mic.join(1000)
            asrStream?.release()
            cloud?.cancel()
            aec?.release()
            record.stop()
            record.release()
            tone?.release()
        }
    }

    /** 喂一帧给唤醒模型，命中返回唤醒词并重置流。 */
    private fun spot(chunk: FloatArray): String? {
        if (voicePrint != null) recent.add(chunk)
        val s = kwsStream!!
        s.acceptWaveform(chunk, SAMPLE_RATE)
        while (kws!!.isReady(s)) kws!!.decode(s)
        val r = kws!!.getResult(s)
        if (r.keyword.isEmpty()) return null
        kws!!.reset(s)
        return r.keyword
    }

    // ---------------------------------------------------------------- 调试辅助

    private fun rmsDb(x: FloatArray): Float {
        var s = 0.0
        for (v in x) s += v * v
        val rms = sqrt(s / max(1, x.size))
        return (20 * log10(rms + 1e-9)).toFloat()
    }

    /** 每次指令的录音存到 files/recordings，`adb pull` 下来就能在电脑上复现识别问题。 */
    private fun saveWav(data: FloatArray, text: String) {
        if (data.isEmpty()) return
        // 开机早期外部存储可能还没挂载，这时不存，别因为调试录音把引擎搞崩
        val ext = ctx.getExternalFilesDir(null) ?: return
        val dir = File(ext, "recordings").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            "_" + text.take(12).replace(Regex("[^\\p{L}\\p{N}]"), "") + ".wav"
        WavWriter.write(File(dir, name), data, SAMPLE_RATE)
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(MAX_RECORDINGS)?.forEach { it.delete() }
    }

    /** 麦克风线程读到的一帧，[muted] 是录下这帧时是否正在播报。 */
    private class Chunk(val pcm: FloatArray, val muted: Boolean)

    private class FloatBuffer {
        private var buf = FloatArray(SAMPLE_RATE * 4)
        private var n = 0
        fun add(x: FloatArray) {
            if (n + x.size > MAX_UTTERANCE_SEC.toInt() * SAMPLE_RATE + SAMPLE_RATE) return
            if (n + x.size > buf.size) buf = buf.copyOf(max(buf.size * 2, n + x.size))
            System.arraycopy(x, 0, buf, n, x.size)
            n += x.size
        }
        fun clear() { n = 0 }
        val size get() = this.n
        fun toArray() = buf.copyOf(n)
        fun slice(from: Int) = buf.copyOfRange(from.coerceIn(0, n), n)
    }

    /** 只保留最近 N 个采样。 */
    private class RingBuffer(private val cap: Int) {
        private val buf = FloatArray(cap)
        private var pos = 0
        private var full = false
        fun add(x: FloatArray) {
            for (v in x) {
                buf[pos] = v
                if (++pos == cap) { pos = 0; full = true }
            }
        }
        fun toArray(): FloatArray =
            if (full) buf.copyOfRange(pos, cap) + buf.copyOfRange(0, pos) else buf.copyOf(pos)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512
        const val MAX_UTTERANCE_SEC = 15f
        const val MAX_RECORDINGS = 30
        /** 录音队列最多攒多少帧（每帧 32ms，约 30 秒），再多就丢最老的。 */
        const val MAX_BACKLOG_CHUNKS = 1000
        const val VAD_PREROLL_SEC = 1.0f
        /** 唤醒命中时往回取多少秒当作唤醒词音频（四字唤醒词约 1.2s，多留一点给尾随 blank）。 */
        const val WAKE_AUDIO_SEC = 2.0f
        /** 一次聆听里连续几句声纹不符就结束会话。 */
        const val MAX_REJECTED = 3
        /** VAD 断句后最多等讯飞多久出最终结果。短句可能还在建连，弱 CPU 上 TLS 握手也慢，别等得太短。 */
        const val XF_FINAL_WAIT_MS = 3000L
        /** 本地整句识别时补在句尾的静音。 */
        const val LOCAL_TAIL_SAMPLES = SAMPLE_RATE * 2 / 3
    }
}
