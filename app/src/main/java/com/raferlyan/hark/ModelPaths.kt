package com.raferlyan.hark

import android.content.Context
import java.io.File

/**
 * 模型不打进 APK。调试时用 adb push 到 /sdcard/Android/data/com.raferlyan.hark/files/models/，
 * 换模型、调参都不用重新安装，见 tools/push_models.sh；随系统预置时放在 /product/etc/hark/models 等只读分区，
 * 见 aosp/README.md。每个文件按 [roots] 的顺序找，先找到哪个用哪个，所以 push 到 sdcard 的可以覆盖预置的。
 */
class ModelPaths(ctx: Context, settings: AppSettings) {
    /** 开机早期外部存储可能还没挂载，getExternalFilesDir 会返回 null。 */
    val root: File? = ctx.getExternalFilesDir(null)?.let { File(it, "models") }

    private val roots: List<File> = listOfNotNull(root) + SYSTEM_ROOTS.map(::File)

    /** 都找不到时返回第一个位置的路径，缺文件提示里就是让用户 push 的那个位置。 */
    private fun f(dir: String, name: String): String {
        val rel = if (dir.isEmpty()) name else "$dir/$name"
        return (roots.map { File(it, rel) }.firstOrNull { it.isFile } ?: File(roots.first(), rel)).absolutePath
    }

    // 唤醒：wenetspeech 3.3M（拼音声韵母 token）
    private val kwsDir = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile"
    val kwsEncoder = f(kwsDir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
    val kwsDecoder = f(kwsDir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx")
    val kwsJoiner = f(kwsDir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
    val kwsTokens = f(kwsDir, "tokens.txt")

    // 普通话流式：设置里选哪个就用哪个，见 [StreamModel]
    private val stream = settings.streamModel
    val streamEncoder = f(stream.dir, stream.encoder)
    val streamDecoder = f(stream.dir, stream.decoder)
    val streamJoiner = f(stream.dir, stream.joiner)
    /** CTC 模型只有这一个文件。 */
    val streamCtc = f(stream.dir, stream.encoder)
    val streamTokens = f(stream.dir, "tokens.txt")
    private val streamFiles =
        if (stream.ctc) listOf(streamCtc, streamTokens) else listOf(streamEncoder, streamDecoder, streamJoiner, streamTokens)

    // 方言：TeleSpeech CTC，覆盖 30 多种方言
    private val teleDir = "sherpa-onnx-telespeech-ctc-int8-zh-2024-06-04"
    val teleModel = f(teleDir, "model.int8.onnx")
    val teleTokens = f(teleDir, "tokens.txt")

    // 粤语/多语种：SenseVoice
    private val svDir = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"
    val svModel = f(svDir, "model.int8.onnx")
    val svTokens = f(svDir, "tokens.txt")

    // 同一个 SenseVoice，转换成 RK3562 NPU 能跑的版本。输入长度固定为 10 秒，更长的句子会被截断
    private val rknnSvDir = "sherpa-onnx-rk3562-10-seconds-sense-voice-zh-en-ja-ko-yue-2025-09-09"
    val rknnSvModel = f(rknnSvDir, "model.rknn")
    val rknnSvTokens = f(rknnSvDir, "tokens.txt")

    val vad = f("", "silero_vad.onnx")

    // 声纹：3D-Speaker CAM++ 中文，可选，缺了只是不做声纹校验
    val speaker = f("", "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx")

    fun requiredFor(mode: AsrMode): List<String> {
        val kws = listOf(kwsEncoder, kwsDecoder, kwsJoiner, kwsTokens)
        return kws + when (mode) {
            AsrMode.MANDARIN_STREAMING -> streamFiles
            // 讯飞模式靠 VAD 断句；本地普通话模型平时不解码，断网或讯飞报错时才用
            AsrMode.IFLYTEK -> streamFiles + vad
            AsrMode.DIALECT_TELESPEECH -> listOf(teleModel, teleTokens, vad)
            AsrMode.SENSEVOICE -> listOf(svModel, svTokens, vad)
            AsrMode.RKNN_SENSEVOICE -> listOf(rknnSvModel, rknnSvTokens, vad)
        }
    }

    fun missingFor(mode: AsrMode): List<String> = requiredFor(mode).filterNot { File(it).isFile }

    companion object {
        /** 预置模型的位置，对应 aosp/hark.mk 里 PRODUCT_COPY_FILES 的目标目录。 */
        val SYSTEM_ROOTS = listOf(
            "/product/etc/hark/models",
            "/system_ext/etc/hark/models",
            "/system/etc/hark/models",
            "/vendor/etc/hark/models",
        )
    }
}

/**
 * 普通话流式识别模型。大模型准确率高但吃 CPU，RK3562 这类 A53 小核跟不上实时，就换小一号的。
 * 模型用 `tools/push_models.sh <group>` 推到设备上。CTC 模型只有一个文件（放在 [encoder]），不支持热词。
 */
enum class StreamModel(
    val label: String,
    val group: String,
    val dir: String,
    val encoder: String,
    val decoder: String = "",
    val joiner: String = "",
    val ctc: Boolean = false,
) {
    ZH_2025(
        "大·zipformer-zh 2025（默认，最准）", "asr",
        "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
        "encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx",
    ),
    MULTI_ZH_HANS(
        "中·multi-zh-hans 2023（稍快，支持热词）", "asr-small",
        "sherpa-onnx-streaming-zipformer-multi-zh-hans-int8-2023-12-13",
        "encoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx",
        "decoder-epoch-20-avg-1-chunk-16-left-128.onnx",
        "joiner-epoch-20-avg-1-chunk-16-left-128.int8.onnx",
    ),
    ZH_14M(
        "小·zh-14M（较快，准确率明显差）", "asr-tiny",
        "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile",
        "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx",
    ),
    SMALL_CTC(
        "小·small-ctc 2025（最快，CTC 不支持热词）", "asr-ctc",
        "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01",
        "model.int8.onnx", ctc = true,
    ),
}
