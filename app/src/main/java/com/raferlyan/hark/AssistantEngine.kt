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
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * 讯飞模式：音频同时送给讯飞听写和本地普通话模型。本地模型负责断句，断句后让讯飞出最终结果；
 * 讯飞连不上、报错或迟迟不出结果，就用本地结果。
 *
 * 声纹校验（可选）：唤醒词那段音频提取声纹锁定本次会话，之后每句指令先比声纹，
 * 不是唤醒人说的就丢掉、继续聆听。打断时重新说唤醒词的人会成为新的锁定对象。
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

    private val paths = ModelPaths(ctx)
    @Volatile private var running = false
    private var thread: Thread? = null

    private var kws: KeywordSpotter? = null
    private var kwsStream: OnlineStream? = null
    private var online: OnlineRecognizer? = null
    private var offline: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var voicePrint: VoicePrint? = null
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
        try {
            AssistantBus.state(EngineState.LOADING)
            if (!load()) {
                AssistantBus.state(EngineState.ERROR)
                return
            }
            loop()
            AssistantBus.state(EngineState.STOPPED)
        } catch (t: Throwable) {
            AssistantBus.log("引擎异常：${t.javaClass.simpleName}: ${t.message}")
            AssistantBus.state(EngineState.ERROR)
        } finally {
            release()
        }
    }

    // ---------------------------------------------------------------- 模型加载

    private fun load(): Boolean {
        val missing = paths.missingFor(settings.asrMode)
        if (missing.isNotEmpty()) {
            AssistantBus.log("缺少模型文件（请先运行 tools/push_models.sh）：")
            missing.forEach { AssistantBus.log("  ✗ $it") }
            return false
        }
        val prep = TextPrep(ctx)
        val feat = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)

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
        if (settings.kwsTestMode) {
            AssistantBus.log("唤醒测试模式：只统计唤醒，不加载识别模型")
            return true
        }

        corrections = TextPrep.parseCorrections(settings.corrections)

        if (settings.speakerVerify) {
            if (File(paths.speaker).isFile) {
                timed("加载声纹模型") { voicePrint = VoicePrint(paths.speaker, 1) }
                AssistantBus.log("声纹校验已开启，阈值 ${settings.speakerThreshold}")
            } else {
                AssistantBus.log("⚠ 缺少声纹模型，本次不做声纹校验（tools/push_models.sh speaker）：\n  ✗ ${paths.speaker}")
            }
        }

        // 2) 识别
        when (settings.asrMode) {
            AsrMode.MANDARIN_STREAMING, AsrMode.IFLYTEK -> {
                if (settings.asrMode == AsrMode.IFLYTEK) {
                    xfConfig = XfIat.Config.from(settings)
                    AssistantBus.log(
                        xfConfig?.let {
                            "讯飞听写：accent=${it.accent}，方言免切=${it.dialectFree}，标点=${it.punctuation}，" +
                                "动态修正=${it.dynamicCorrection}，后端断句 ${it.eosMs}ms；本地普通话模型兜底"
                        } ?: "⚠ 讯飞 APPID / APIKey / APISecret 没填全，只用本地普通话识别",
                    )
                }
                val hw = prep.prepareHotwords(settings.hotwords, paths.streamTokens)
                hw.warnings.forEach { AssistantBus.log("⚠ $it") }
                val hwFile = File(ctx.filesDir, "hotwords.txt").apply { writeText(hw.content + "\n") }
                AssistantBus.log("热词 ${hw.count} 个，hotwordsScore=${settings.hotwordsScore}")
                timed("加载普通话流式模型") {
                    online = OnlineRecognizer(
                        assetManager = null,
                        config = OnlineRecognizerConfig(
                            featConfig = feat,
                            modelConfig = OnlineModelConfig(
                                transducer = OnlineTransducerModelConfig(
                                    encoder = paths.streamEncoder,
                                    decoder = paths.streamDecoder,
                                    joiner = paths.streamJoiner,
                                ),
                                tokens = paths.streamTokens,
                                numThreads = settings.numThreads,
                                modelingUnit = "cjkchar",
                            ),
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

            AsrMode.DIALECT_TELESPEECH, AsrMode.SENSEVOICE -> {
                val model = if (settings.asrMode == AsrMode.DIALECT_TELESPEECH) {
                    OfflineModelConfig(
                        teleSpeech = paths.teleModel,
                        tokens = paths.teleTokens,
                        modelType = "telespeech_ctc",
                        numThreads = settings.numThreads,
                    )
                } else {
                    OfflineModelConfig(
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
        }
        return true
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

        val pcm = ShortArray(VAD_WINDOW)
        val samples = FloatArray(VAD_WINDOW)
        var phase = Phase.IDLE
        var asrStream: OnlineStream? = null
        var listenStart = 0L
        var listenTimeoutMs = 0L
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
        // 讯飞会话；cloudEndAt > 0 表示本地已断句、已发结束帧，在等讯飞最终结果
        var cloud: XfIat? = null
        var cloudEndAt = 0L
        var cloudFailLogged = false

        fun newCloud() {
            cloud?.cancel()
            cloud = xfConfig?.let { XfIat(it).apply { start() } }
            cloudEndAt = 0L
            cloudFailLogged = false
        }

        fun dropCloud() {
            cloud?.cancel()
            cloud = null
        }

        record.startRecording()
        AssistantBus.state(EngineState.IDLE)
        AssistantBus.log("开始监听麦克风（${SAMPLE_RATE}Hz）")

        fun backToIdle() {
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
            speechSeen = false
            listenPeakDb = -100f
            listenMuted = 0
            rejected = 0
            followUp = false
            lastPartial = ""
            utterance.clear()
            asrStream?.release()
            asrStream = online?.createStream()
            newCloud()
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
            speechSeen = false
            lastPartial = ""
            utterance.clear()
            asrStream?.release()
            asrStream = online?.createStream()
            newCloud()
            vad?.reset()
            AssistantBus.state(EngineState.LISTENING)
            return null
        }

        fun noCommand() {
            AssistantBus.log("没有听到指令，回到待唤醒（峰值 %.0f dB，静音丢弃 %d 帧）".format(listenPeakDb, listenMuted))
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
                    " $extra 唤醒后 ${cost}ms",
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

        try {
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                for (i in 0 until n) samples[i] = pcm[i] / 32768f
                val chunk = if (n == samples.size) samples else samples.copyOf(n)

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
                    if (AssistantBus.speaking) echoPeakDb = max(echoPeakDb, db)
                    val kw = spot(chunk)
                    if (kw != null) {
                        AssistantBus.log("★ 打断：$kw（播报中=${AssistantBus.speaking}）")
                        callbacks.onBargeIn(kw)
                        AssistantBus.speaking = false
                        wasSpeaking = false
                        startListening(settings.listenTimeoutSec)
                        lockSpeaker()
                    }
                    wasSpeaking = AssistantBus.speaking
                    continue
                }

                // 自己播报时不听，播完重置唤醒流，避免把 TTS 的尾音当作唤醒词
                if (AssistantBus.speaking) {
                    wasSpeaking = true
                    if (phase == Phase.LISTENING) listenMuted++
                    continue
                }
                if (wasSpeaking) {
                    wasSpeaking = false
                    kws!!.reset(kwsStream!!)
                    // 「我在」播完才开始算聆听超时
                    if (phase == Phase.LISTENING) listenStart = SystemClock.elapsedRealtime()
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

                    Phase.LISTENING -> {
                        utterance.add(chunk)
                        val rec = online
                        if (rec != null) {
                            val s = asrStream!!
                            // 已经断句、在等讯飞结果时不再送音频
                            if (cloudEndAt == 0L) {
                                s.acceptWaveform(chunk, SAMPLE_RATE)
                                while (rec.isReady(s)) rec.decode(s)
                                cloud?.feed(chunk)
                            }
                            val text = rec.getResult(s).text
                            val c = cloud
                            if (c != null && c.error != null && !cloudFailLogged) {
                                cloudFailLogged = true
                                AssistantBus.log("讯飞不可用，这句改用本地识别：${c.error}")
                            }
                            val cloudOk = c != null && c.error == null
                            val shown = if (cloudOk && c!!.text.isNotEmpty()) c.text else text
                            if (shown != lastPartial) {
                                lastPartial = shown
                                AssistantBus.partial(shown)
                            }
                            val now = SystemClock.elapsedRealtime()
                            fun accept(result: String, note: String) {
                                val audio = utterance.toArray()
                                checkSpeaker(audio, result)?.let { v ->
                                    finish(result, audio, listOf(note, v).filter { it.isNotEmpty() }.joinToString("，"))
                                }
                            }
                            when {
                                // 讯飞出了最终结果（本地断句后，或者讯飞自己先判定说完了）
                                cloudOk && c!!.finished -> when {
                                    c.text.isNotBlank() -> accept(
                                        c.text,
                                        "讯飞" + (if (c.finalLatencyMs > 0) " 收尾 ${c.finalLatencyMs}ms" else "") +
                                            "，本地「${text.trim()}」",
                                    )
                                    text.isNotBlank() -> accept(text, "讯飞结果为空，用本地结果")
                                    else -> noCommand()
                                }
                                // 本地断句：让讯飞收尾，最多等 XF_FINAL_WAIT_MS
                                cloudOk && cloudEndAt == 0L && rec.isEndpoint(s) && text.isNotBlank() -> {
                                    c!!.end()
                                    cloudEndAt = now
                                    AssistantBus.state(EngineState.RECOGNIZING)
                                }
                                cloudOk && cloudEndAt > 0L -> if (now - cloudEndAt > XF_FINAL_WAIT_MS) {
                                    AssistantBus.log("讯飞 ${XF_FINAL_WAIT_MS}ms 内没出最终结果，用本地结果")
                                    accept(text, "讯飞超时，用本地结果")
                                }
                                // 纯本地，或讯飞这句已经失败
                                rec.isEndpoint(s) && text.isNotBlank() ->
                                    accept(text, if (c != null) "讯飞失败，用本地结果" else "")
                                text.isBlank() && (!cloudOk || c!!.text.isBlank()) &&
                                    now - listenStart > listenTimeoutMs -> noCommand()
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
                                val voiceNote = checkSpeaker(audio, "（未解码）") ?: continue
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
                                SystemClock.elapsedRealtime() - listenStart > listenTimeoutMs
                            ) {
                                noCommand()
                            }
                        }
                    }
                }
            }
        } finally {
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
        val dir = File(ctx.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            "_" + text.take(12).replace(Regex("[^\\p{L}\\p{N}]"), "") + ".wav"
        WavWriter.write(File(dir, name), data, SAMPLE_RATE)
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(MAX_RECORDINGS)?.forEach { it.delete() }
    }

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
        const val VAD_PREROLL_SEC = 1.0f
        /** 唤醒命中时往回取多少秒当作唤醒词音频（四字唤醒词约 1.2s，多留一点给尾随 blank）。 */
        const val WAKE_AUDIO_SEC = 2.0f
        /** 一次聆听里连续几句声纹不符就结束会话。 */
        const val MAX_REJECTED = 3
        /** 本地断句后最多等讯飞多久出最终结果。 */
        const val XF_FINAL_WAIT_MS = 2000L
    }
}
