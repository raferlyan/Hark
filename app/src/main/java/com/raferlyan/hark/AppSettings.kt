package com.raferlyan.hark

import android.content.Context

/** 识别模式：普通话流式（支持热词）/ 方言（TeleSpeech）/ 粤语及多语种（SenseVoice）/ 讯飞云端听写。 */
enum class AsrMode(val label: String) {
    MANDARIN_STREAMING("普通话·流式（支持热词）"),
    DIALECT_TELESPEECH("方言·TeleSpeech（川/粤/闽/吴/湘等）"),
    SENSEVOICE("粤语/多语种·SenseVoice"),
    IFLYTEK("讯飞云端听写（联网，上传录音；本地普通话兜底）"),
}

/** 设备形态：决定回复话术和浮窗位置。 */
enum class DeviceMode(val label: String) {
    CAR("车机（平板车机）"),
    PHONE("手机"),
}

/**
 * 所有可调参数。保存在 SharedPreferences，修改后由 Service 重新加载引擎。
 */
data class AppSettings(
    /** 每行一个唤醒词：`小晏小晏 :2.0 #0.20`，也可以直接写 token：`x iǎo y àn x iǎo y àn @小晏小晏`。 */
    val keywords: String = DEFAULT_KEYWORDS,
    /** 全局唤醒阈值，越小越容易唤醒（误唤醒也越多）。 */
    val kwsThreshold: Float = 0.25f,
    /** 全局唤醒词增强分数，越大越容易唤醒。 */
    val kwsScore: Float = 1.5f,
    /** 唤醒后跟随多少个 blank 帧才确认触发，越大越不容易被前缀误触发。 */
    val kwsTrailingBlanks: Int = 1,
    /** 只做唤醒统计、不进入识别，用于调唤醒率/误唤醒。 */
    val kwsTestMode: Boolean = false,

    val asrMode: AsrMode = AsrMode.MANDARIN_STREAMING,
    /** 每行一个热词，可带分数：`手电筒 :2.5`。仅普通话流式模式生效。 */
    val hotwords: String = DEFAULT_HOTWORDS,
    val hotwordsScore: Float = 1.5f,
    /** 识别结果纠错，每行 `错词=>正确词`，所有模式都生效（方言模型不支持热词时靠它兜底）。 */
    val corrections: String = DEFAULT_CORRECTIONS,
    /** SenseVoice 语种：auto / zh / yue / en / ja / ko */
    val senseVoiceLang: String = "auto",

    /** 方言/SenseVoice 模式的 VAD 阈值，越小越灵敏。远场、小声、鼻音开头（如粤语「唔该」）需要调低。 */
    val vadThreshold: Float = 0.3f,
    /** 唤醒后多久没开口就退出（秒）。 */
    val listenTimeoutSec: Float = 6f,
    /** 说完后静音多久判定一句话结束（秒）。 */
    val endSilenceSec: Float = 0.8f,
    val numThreads: Int = 2,
    val beepOnWake: Boolean = false,
    val speakReply: Boolean = true,
    val saveAudio: Boolean = true,

    val deviceMode: DeviceMode = DeviceMode.PHONE,
    /** 唤醒后显示胶囊浮窗（需要悬浮窗权限）。 */
    val showOverlay: Boolean = true,
    /** 回复播完后继续聆听多久（秒），期间不用再喊唤醒词；0 表示每次都要唤醒。 */
    val followUpSec: Float = 6f,
    /** 播报回复或等待大模型时，说唤醒词可以打断并直接下达新指令。 */
    val bargeIn: Boolean = true,
    /**
     * 录音走 VOICE_COMMUNICATION + 平台回声消除。不开的话，播报时手机自己的声音会盖住唤醒词，打断基本听不到；
     * 代价是系统会额外做降噪/增益，远场唤醒率可能有变化，需要按设备实测。
     */
    val echoCancel: Boolean = true,
    /** 声纹校验：只接受说唤醒词那个人的指令，其他人说话直接忽略。需要声纹模型。 */
    val speakerVerify: Boolean = false,
    /** 声纹相似度阈值，低于它就当作不是唤醒人。越大越严格（本人也更容易被拒）。 */
    val speakerThreshold: Float = 0.45f,

    /** 讯飞听写密钥，在讯飞开放平台控制台创建应用并开通「语音听写（流式版）」后获得；留空用 local.properties 里的。 */
    val xfAppId: String = "",
    val xfApiKey: String = "",
    val xfApiSecret: String = "",
    /** 讯飞方言参数：mandarin、cantonese、lmz（四川话）等，方言要先在控制台开通。 */
    val xfAccent: String = "mandarin",
    /** 讯飞方言免切：普通话和 23 种方言混说，要先在控制台开通。 */
    val xfDialectFree: Boolean = false,
    /** 讯飞结果加标点。指令识别一般不需要。 */
    val xfPunctuation: Boolean = false,
    /** 讯飞动态修正：边说边出字时会回头改字。 */
    val xfDynamicCorrection: Boolean = true,

    /** 本地规则匹配不上时调用在线大模型。 */
    val llmEnabled: Boolean = true,
    val llmBaseUrl: String = LlmPreset.DEEPSEEK.baseUrl,
    val llmModel: String = LlmPreset.DEEPSEEK.model,
    /** 留空时使用 local.properties 里的 llm.apiKey（只用于调试，不入库）。 */
    val llmApiKey: String = "",
) {
    val effectiveLlmKey get() = llmApiKey.trim().ifEmpty { BuildConfig.LLM_DEBUG_KEY }

    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putString("keywords", keywords)
            .putFloat("kwsThreshold", kwsThreshold)
            .putFloat("kwsScore", kwsScore)
            .putInt("kwsTrailingBlanks", kwsTrailingBlanks)
            .putBoolean("kwsTestMode", kwsTestMode)
            .putString("asrMode", asrMode.name)
            .putString("hotwords", hotwords)
            .putFloat("hotwordsScore", hotwordsScore)
            .putString("corrections", corrections)
            .putString("senseVoiceLang", senseVoiceLang)
            .putFloat("vadThreshold", vadThreshold)
            .putFloat("listenTimeoutSec", listenTimeoutSec)
            .putFloat("endSilenceSec", endSilenceSec)
            .putInt("numThreads", numThreads)
            .putBoolean("beepOnWake", beepOnWake)
            .putBoolean("speakReply", speakReply)
            .putBoolean("saveAudio", saveAudio)
            .putString("deviceMode", deviceMode.name)
            .putBoolean("showOverlay", showOverlay)
            .putFloat("followUpSec", followUpSec)
            .putBoolean("bargeIn", bargeIn)
            .putBoolean("echoCancel", echoCancel)
            .putBoolean("speakerVerify", speakerVerify)
            .putFloat("speakerThreshold", speakerThreshold)
            .putString("xfAppId", xfAppId)
            .putString("xfApiKey", xfApiKey)
            .putString("xfApiSecret", xfApiSecret)
            .putString("xfAccent", xfAccent)
            .putBoolean("xfDialectFree", xfDialectFree)
            .putBoolean("xfPunctuation", xfPunctuation)
            .putBoolean("xfDynamicCorrection", xfDynamicCorrection)
            .putBoolean("llmEnabled", llmEnabled)
            .putString("llmBaseUrl", llmBaseUrl)
            .putString("llmModel", llmModel)
            .putString("llmApiKey", llmApiKey)
            .apply()
    }

    companion object {
        const val DEFAULT_KEYWORDS = "小晏小晏 :2.0 #0.20\n小海小海 :2.0 #0.20\n小章小章 :2.0 #0.20"
        const val DEFAULT_HOTWORDS = "打开手电 :2.0\n关闭手电 :2.0\n音量调大\n音量调小\n现在几点"
        const val DEFAULT_CORRECTIONS = "小燕=>小晏\n小雁=>小晏\n小艳=>小晏\n小严=>小晏\n大开=>打开"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun load(ctx: Context): AppSettings {
            val p = prefs(ctx)
            val d = AppSettings()
            return AppSettings(
                keywords = p.getString("keywords", d.keywords)!!,
                kwsThreshold = p.getFloat("kwsThreshold", d.kwsThreshold),
                kwsScore = p.getFloat("kwsScore", d.kwsScore),
                kwsTrailingBlanks = p.getInt("kwsTrailingBlanks", d.kwsTrailingBlanks),
                kwsTestMode = p.getBoolean("kwsTestMode", d.kwsTestMode),
                asrMode = runCatching { AsrMode.valueOf(p.getString("asrMode", d.asrMode.name)!!) }
                    .getOrDefault(d.asrMode),
                hotwords = p.getString("hotwords", d.hotwords)!!,
                hotwordsScore = p.getFloat("hotwordsScore", d.hotwordsScore),
                corrections = p.getString("corrections", d.corrections)!!,
                senseVoiceLang = p.getString("senseVoiceLang", d.senseVoiceLang)!!,
                vadThreshold = p.getFloat("vadThreshold", d.vadThreshold),
                listenTimeoutSec = p.getFloat("listenTimeoutSec", d.listenTimeoutSec),
                endSilenceSec = p.getFloat("endSilenceSec", d.endSilenceSec),
                numThreads = p.getInt("numThreads", d.numThreads),
                beepOnWake = p.getBoolean("beepOnWake", d.beepOnWake),
                speakReply = p.getBoolean("speakReply", d.speakReply),
                saveAudio = p.getBoolean("saveAudio", d.saveAudio),
                deviceMode = runCatching { DeviceMode.valueOf(p.getString("deviceMode", d.deviceMode.name)!!) }
                    .getOrDefault(d.deviceMode),
                showOverlay = p.getBoolean("showOverlay", d.showOverlay),
                followUpSec = p.getFloat("followUpSec", d.followUpSec),
                bargeIn = p.getBoolean("bargeIn", d.bargeIn),
                echoCancel = p.getBoolean("echoCancel", d.echoCancel),
                speakerVerify = p.getBoolean("speakerVerify", d.speakerVerify),
                speakerThreshold = p.getFloat("speakerThreshold", d.speakerThreshold),
                xfAppId = p.getString("xfAppId", d.xfAppId)!!,
                xfApiKey = p.getString("xfApiKey", d.xfApiKey)!!,
                xfApiSecret = p.getString("xfApiSecret", d.xfApiSecret)!!,
                xfAccent = p.getString("xfAccent", d.xfAccent)!!,
                xfDialectFree = p.getBoolean("xfDialectFree", d.xfDialectFree),
                xfPunctuation = p.getBoolean("xfPunctuation", d.xfPunctuation),
                xfDynamicCorrection = p.getBoolean("xfDynamicCorrection", d.xfDynamicCorrection),
                llmEnabled = p.getBoolean("llmEnabled", d.llmEnabled),
                llmBaseUrl = p.getString("llmBaseUrl", d.llmBaseUrl)!!,
                llmModel = p.getString("llmModel", d.llmModel)!!,
                llmApiKey = p.getString("llmApiKey", d.llmApiKey)!!,
            )
        }
    }
}
