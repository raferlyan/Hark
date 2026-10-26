package com.raferlyan.hark

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class EngineState(val label: String) {
    STOPPED("已停止"),
    LOADING("加载模型中…"),
    IDLE("等待唤醒：说唤醒词，如「小晏小晏」"),
    LISTENING("正在聆听…"),
    RECOGNIZING("识别中…"),
    WAITING("处理/播报回复中…"),
    ERROR("出错"),
}

/**
 * 一次聆听录下的音频和识别结果，调试面板用来回放、标注。
 * [note] 说明这一句怎么结束的：超时、声纹不符、声纹相似度等。
 */
class Utterance(
    val audio: FloatArray,
    val text: String,
    val raw: String,
    val note: String,
    val mode: AsrMode,
    val time: Long = System.currentTimeMillis(),
) {
    val durationMs get() = audio.size * 1000L / AssistantEngine.SAMPLE_RATE
}

/** 引擎线程 → UI 的事件通道。所有回调都在主线程触发。 */
object AssistantBus {
    const val TAG = "XiaoYan"

    interface Listener {
        fun onState(state: EngineState) {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        fun onReply(text: String) {}
        fun onLevel(db: Float) {}
        fun onLog(line: String) {}
        fun onStats(wakeCount: Int) {}
        fun onUtterance(u: Utterance) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var state = EngineState.STOPPED; private set
    @Volatile var wakeCount = 0; private set
    /** 最近一次聆听的录音，调试面板打开时用它恢复「上一句」。 */
    @Volatile var lastUtterance: Utterance? = null; private set
    /** TTS 播报（或调试面板回放录音）期间为 true，引擎丢弃麦克风数据，避免自己唤醒自己。 */
    @Volatile var speaking = false

    val logs = ArrayDeque<String>()

    fun add(l: Listener) = listeners.add(l)
    fun remove(l: Listener) = listeners.remove(l)

    fun state(s: EngineState) {
        state = s
        main.post { listeners.forEach { it.onState(s) } }
    }

    fun partial(t: String) = main.post { listeners.forEach { it.onPartial(t) } }
    fun final(t: String) = main.post { listeners.forEach { it.onFinal(t) } }
    fun level(db: Float) = main.post { listeners.forEach { it.onLevel(db) } }
    fun reply(t: String) = main.post { listeners.forEach { it.onReply(t) } }

    fun utterance(u: Utterance) {
        lastUtterance = u
        main.post { listeners.forEach { it.onUtterance(u) } }
    }

    fun wake() {
        wakeCount++
        val n = wakeCount
        main.post { listeners.forEach { it.onStats(n) } }
    }

    fun resetStats() {
        wakeCount = 0
        main.post { listeners.forEach { it.onStats(0) } }
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        val line = "${fmt.format(Date())}  $msg"
        main.post {
            logs.addLast(line)
            while (logs.size > 300) logs.removeFirst()
            listeners.forEach { it.onLog(line) }
        }
    }
}
