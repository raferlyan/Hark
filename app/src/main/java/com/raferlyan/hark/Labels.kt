package com.raferlyan.hark

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试面板里人工标注的识别结果，存在 files/labeled/：每条一个 wav，外加一份 labels.tsv。
 * 和 recordings/ 分开放，不会被「只留最近 30 条」清掉，攒够了可以 `adb pull` 下来批量评测模型和参数。
 *
 * labels.tsv 每行：时间 wav 识别模式 识别结果 原始结果 判定(correct/wrong) 正确文本 备注
 */
object Labels {
    private const val TSV = "labels.tsv"

    fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "labeled").apply { mkdirs() }

    /** 同一句重复标注时覆盖之前那条。[expected] 为空表示只知道错了、没填正确文本。 */
    fun save(ctx: Context, u: Utterance, correct: Boolean, expected: String) {
        val d = dir(ctx)
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(u.time)) + ".wav"
        val wav = File(d, name)
        if (!wav.exists()) WavWriter.write(wav, u.audio, AssistantEngine.SAMPLE_RATE)

        val row = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(u.time)),
            name, u.mode.name, u.text, u.raw,
            if (correct) "correct" else "wrong",
            if (correct) u.text else expected,
            u.note,
        ).joinToString("\t") { it.replace(Regex("[\t\n]"), " ") }
        val tsv = File(d, TSV)
        val others = if (tsv.exists()) tsv.readLines().filter { it.split("\t").getOrNull(1) != name } else emptyList()
        tsv.writeText((others + row).joinToString("\n", postfix = "\n"))
    }

    /** 已标注条数和其中识别正确的条数。 */
    fun stats(ctx: Context): Pair<Int, Int> {
        val tsv = File(dir(ctx), TSV)
        if (!tsv.exists()) return 0 to 0
        val rows = tsv.readLines().filter { it.isNotBlank() }
        return rows.size to rows.count { it.split("\t").getOrNull(5) == "correct" }
    }
}
