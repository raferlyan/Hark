package com.raferlyan.hark

import android.content.Context
import java.io.File

/**
 * 模型不打进 APK，用 adb push 到 /sdcard/Android/data/com.raferlyan.hark/files/models/，
 * 换模型、调参都不用重新安装。见 tools/push_models.sh。
 */
class ModelPaths(ctx: Context, settings: AppSettings) {
    val root: File = File(ctx.getExternalFilesDir(null), "models")

    private fun f(dir: String, name: String) = File(root, "$dir/$name").absolutePath

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

    val vad = File(root, "silero_vad.onnx").absolutePath

    // 声纹：3D-Speaker CAM++ 中文，可选，缺了只是不做声纹校验
    val speaker = File(root, "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx").absolutePath

    fun requiredFor(mode: AsrMode): List<String> {
        val kws = listOf(kwsEncoder, kwsDecoder, kwsJoiner, kwsTokens)
        return kws + when (mode) {
            // 讯飞模式同时跑本地普通话模型：断句靠它，断网或讯飞报错时也用它的结果
            AsrMode.MANDARIN_STREAMING, AsrMode.IFLYTEK -> streamFiles
            AsrMode.DIALECT_TELESPEECH -> listOf(teleModel, teleTokens, vad)
            AsrMode.SENSEVOICE -> listOf(svModel, svTokens, vad)
        }
    }

    fun missingFor(mode: AsrMode): List<String> = requiredFor(mode).filterNot { File(it).isFile }
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
