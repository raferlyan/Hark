package com.raferlyan.hark

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 声纹：用 3D-Speaker CAM++ 把一段语音变成说话人向量，两段语音的余弦相似度越高越像同一个人。
 *
 * 用法是「谁唤醒就只听谁」：唤醒词那一段音频提取声纹锁定本次会话，之后每句指令都和它比一下，
 * 不像的（比如车里其他人在聊天）直接丢掉、继续等唤醒人说话。不需要提前录入。
 */
class VoicePrint(modelPath: String, numThreads: Int) {

    private val extractor = SpeakerEmbeddingExtractor(
        assetManager = null,
        config = SpeakerEmbeddingExtractorConfig(model = modelPath, numThreads = numThreads),
    )

    /** 去掉静音后提取声纹；有效语音太短（不到 [MIN_VOICED_SEC]）返回 null，这时不做判断。 */
    fun embed(audio: FloatArray): Embedding? {
        val voiced = voicedOnly(audio)
        if (voiced.size < MIN_VOICED_SEC * SAMPLE_RATE) return null
        val s = extractor.createStream()
        try {
            s.acceptWaveform(voiced, SAMPLE_RATE)
            s.inputFinished()
            if (!extractor.isReady(s)) return null
            return Embedding(extractor.compute(s), voiced.size * 1000 / SAMPLE_RATE)
        } finally {
            s.release()
        }
    }

    fun release() = extractor.release()

    class Embedding(val vector: FloatArray, val voicedMs: Int)

    companion object {
        private const val SAMPLE_RATE = AssistantEngine.SAMPLE_RATE
        private const val FRAME = SAMPLE_RATE * 30 / 1000
        const val MIN_VOICED_SEC = 0.5f

        fun similarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
            }
            return (dot / (sqrt(na * nb) + 1e-9)).toFloat()
        }

        /**
         * 只保留比最响的一帧低 30 dB 以内的帧。聆听录音里常有一两秒没说话，
         * 静音和底噪直接送进去会把不同人的声纹拉得很像。
         */
        fun voicedOnly(audio: FloatArray): FloatArray {
            val n = audio.size / FRAME
            if (n == 0) return FloatArray(0)
            val db = FloatArray(n) { f ->
                var s = 0.0
                for (i in f * FRAME until (f + 1) * FRAME) s += audio[i] * audio[i]
                (20 * log10(sqrt(s / FRAME) + 1e-9)).toFloat()
            }
            val floor = max(db.max() - 30f, -60f)
            val keep = (0 until n).filter { db[it] > floor }
            val out = FloatArray(keep.size * FRAME)
            keep.forEachIndexed { k, f -> System.arraycopy(audio, f * FRAME, out, k * FRAME, FRAME) }
            return out
        }
    }
}
