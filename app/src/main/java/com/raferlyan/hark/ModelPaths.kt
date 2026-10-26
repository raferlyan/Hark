package com.raferlyan.hark

import android.content.Context
import java.io.File

/**
 * 模型不打进 APK，用 adb push 到 /sdcard/Android/data/com.raferlyan.hark/files/models/，
 * 换模型、调参都不用重新安装。见 tools/push_models.sh。
 */
class ModelPaths(ctx: Context) {
    val root: File = File(ctx.getExternalFilesDir(null), "models")

    private fun f(dir: String, name: String) = File(root, "$dir/$name").absolutePath

    // 唤醒：wenetspeech 3.3M（拼音声韵母 token）
    private val kwsDir = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile"
    val kwsEncoder = f(kwsDir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
    val kwsDecoder = f(kwsDir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx")
    val kwsJoiner = f(kwsDir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
    val kwsTokens = f(kwsDir, "tokens.txt")

    // 普通话流式：multi-zh-hans zipformer large，按字建模，热词用 cjkchar
    private val streamDir = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30"
    val streamEncoder = f(streamDir, "encoder.int8.onnx")
    val streamDecoder = f(streamDir, "decoder.onnx")
    val streamJoiner = f(streamDir, "joiner.int8.onnx")
    val streamTokens = f(streamDir, "tokens.txt")

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
            AsrMode.MANDARIN_STREAMING, AsrMode.IFLYTEK -> listOf(streamEncoder, streamDecoder, streamJoiner, streamTokens)
            AsrMode.DIALECT_TELESPEECH -> listOf(teleModel, teleTokens, vad)
            AsrMode.SENSEVOICE -> listOf(svModel, svTokens, vad)
        }
    }

    fun missingFor(mode: AsrMode): List<String> = requiredFor(mode).filterNot { File(it).isFile }
}
