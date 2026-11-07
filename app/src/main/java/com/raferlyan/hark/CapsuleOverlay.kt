package com.raferlyan.hark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 唤醒后弹出的胶囊浮窗。上下两层：
 *
 * - 用户区：识别出的文字居中显示，背后铺一层随声音起伏的波浪。波浪只属于「用户在说话」，
 *   聆听时跟随麦克风电平，思考时缓慢流动；
 * - 助手区：助手的回复，纯文字，不带波浪。出现回复时，用户区收成一行小字放在上方。
 *
 * 流程：我在 → 聆听 → 思考 → 回复 → 继续聆听或收起。车机放在屏幕顶部，手机放在底部。
 * 所有方法都要在主线程调用。
 */
class CapsuleOverlay(private val ctx: Context, private val mode: DeviceMode, private val onTap: () -> Unit) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun dp(v: Int) = (v * density).toInt()
    private val car = mode == DeviceMode.CAR

    private val wave = WaveView(ctx).apply { visibility = View.INVISIBLE }
    private val userView = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        maxLines = 1
        // 边说边出字时句子越来越长，保留最新的部分
        ellipsize = TextUtils.TruncateAt.START
        setShadowLayer(dp(6f), 0f, 0f, 0x99000000.toInt())
    }
    private val userFrame = FrameLayout(ctx).apply {
        minimumHeight = dp(if (car) 64 else 58)
        addView(wave, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(userView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL,
        ))
    }
    private val replyView = TextView(ctx).apply {
        textSize = if (car) 18f else 16f
        setTextColor(Color.WHITE)
        // 车机上司机只扫一眼，少显示几行；完整内容靠播报
        maxLines = if (car) 3 else 6
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.15f)
        setPadding(dp(22), 0, dp(22), dp(14))
        visibility = View.GONE
    }
    private val capsule = CapsuleView(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        addView(userFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(replyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener { onTap() }
    }

    private var attached = false
    private val removeWindow = Runnable { runCatching { wm.removeView(capsule) } }

    fun canShow() = Settings.canDrawOverlays(ctx)

    fun show() {
        if (attached) return
        // 退场动画还没走完又被唤醒：窗口还在，直接反向进场
        if (capsule.isAttachedToWindow) {
            capsule.removeCallbacks(removeWindow)
            attached = true
            animateIn(fresh = false)
            return
        }
        if (!canShow()) {
            AssistantBus.log("没有悬浮窗权限，浮窗不显示（在调试面板里点「授予悬浮窗权限」）")
            return
        }
        val screenW = ctx.resources.displayMetrics.widthPixels
        val w = if (car) min(dp(560), screenW - dp(48)) else min(dp(440), screenW - dp(24))
        val lp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不加 FLAG_LAYOUT_IN_SCREEN：让系统把浮窗摆在状态栏下方 / 导航栏上方，不盖住时间和信号
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or if (car) Gravity.TOP else Gravity.BOTTOM
            y = dp(if (car) 20 else 48)
            title = "XiaoYanCapsule"
            // 进出场动画全部由 View 自己做，不叠加系统的窗口动画
            windowAnimations = 0
        }
        try {
            wm.addView(capsule, lp)
            attached = true
        } catch (e: Exception) {
            AssistantBus.log("浮窗显示失败：${e.message}")
            return
        }
        animateIn(fresh = true)
    }

    /** [fresh] 为 false 时从退场动画当前的样子接着往回走，不跳帧。 */
    private fun animateIn(fresh: Boolean) {
        capsule.animate().cancel()
        capsule.visibility = View.VISIBLE
        if (fresh) {
            capsule.alpha = 0f
            capsule.scaleX = 0.8f
            capsule.scaleY = 0.8f
            capsule.translationY = edgeOffset()
        }
        // 注意：ViewPropertyAnimator 会沿用上一次设置的插值器，所以进出场每次都显式指定
        capsule.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(280).setInterpolator(ENTER).withLayer().start()
        capsule.startGlow()
    }

    fun hide() {
        if (!attached) return
        attached = false
        capsule.animate().cancel()
        capsule.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).translationY(edgeOffset())
            .setDuration(180).setInterpolator(EXIT).withLayer().withEndAction {
                // 先画一帧完全透明的内容，下一帧再移除窗口，避免系统拿最后一帧旧画面再闪一下
                capsule.visibility = View.INVISIBLE
                capsule.stopGlow()
                capsule.post(removeWindow)
            }.start()
    }

    /** 进出场时往屏幕边缘方向偏一点：车机往上，手机往下。 */
    private fun edgeOffset() = dp(if (car) -10f else 10f)

    fun level(db: Float) = wave.setLevel(db)

    /** 「我在」：助手说的话，不带波浪。 */
    fun greeting(text: String) {
        showWave(false)
        userLine(text, big = true, dim = false)
        reply(null)
    }

    fun listening(hint: String) {
        wave.mode = WaveView.Mode.LISTEN
        showWave(true)
        userLine(hint, big = true, dim = true)
        reply(null)
    }

    /** 边说边出字；空串保持提示语。 */
    fun partial(text: String) {
        if (text.isNotEmpty() && wave.mode == WaveView.Mode.LISTEN && wave.visibility == View.VISIBLE) {
            userLine(text, big = true, dim = false)
        }
    }

    /** 识别完成，等结果：用户的话还在波浪上，波浪转为缓慢流动；下方提示「思考中」。 */
    fun thinking(query: String, hint: String) {
        wave.mode = WaveView.Mode.THINK
        showWave(true)
        userLine(query, big = true, dim = false)
        reply(hint, dim = true)
    }

    /** 助手回复：波浪收起，用户的话缩成上方一行小字。 */
    fun reply(query: String?, text: String) {
        showWave(false)
        if (query.isNullOrEmpty()) userLine("", big = false, dim = true) else userLine(query, big = false, dim = true)
        reply(text, dim = false)
    }

    private fun userLine(text: String, big: Boolean, dim: Boolean) {
        userView.text = text
        userView.textSize = if (big) (if (car) 19f else 17f) else 13f
        userView.gravity = if (big) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        userView.setTextColor(if (dim) 0x99FFFFFF.toInt() else Color.WHITE)
        userView.setPadding(dp(22), if (big) 0 else dp(10), dp(22), if (big) 0 else dp(2))
        userFrame.minimumHeight = if (big) dp(if (car) 64 else 58) else 0
        userView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun reply(text: String?, dim: Boolean = false) {
        if (text.isNullOrEmpty()) {
            replyView.visibility = View.GONE
            return
        }
        replyView.text = text
        replyView.setTextColor(if (dim) 0x99FFFFFF.toInt() else Color.WHITE)
        replyView.visibility = View.VISIBLE
    }

    private fun showWave(on: Boolean) {
        wave.animate().cancel()
        if (on) {
            if (wave.visibility != View.VISIBLE) {
                wave.alpha = 0f
                wave.visibility = View.VISIBLE
                wave.invalidate()
            }
            wave.animate().alpha(1f).setDuration(220).start()
        } else if (wave.visibility == View.VISIBLE) {
            wave.animate().alpha(0f).setDuration(160).withEndAction { wave.visibility = View.INVISIBLE }.start()
        }
    }

    // ---------------------------------------------------------------- 胶囊背景

    /** 深色半透明底 + 缓慢旋转的渐变描边；内容按圆角裁剪，波浪不会溢出。 */
    private inner class CapsuleView(ctx: Context) : LinearLayout(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEB12151C.toInt() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.6f)
        }
        private val rect = RectF()
        private val rot = Matrix()
        private var angle = 0f
        private var glowing = false
        private var glowStart = 0L
        // 描边和波浪共用这一个节拍。跟着屏幕刷新率画太费：RK3562 这类 A53 上，光动画就能吃掉一个核
        private val tick = object : Runnable {
            override fun run() {
                if (!glowing) return
                angle = (SystemClock.uptimeMillis() - glowStart) % GLOW_PERIOD_MS * 360f / GLOW_PERIOD_MS
                invalidate()
                if (wave.visibility == View.VISIBLE) wave.invalidate()
                postDelayed(this, FRAME_MS)
            }
        }

        init {
            setWillNotDraw(false)
            elevation = dp(10f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius())
            }
            clipToOutline = true
        }

        private fun radius() = min(height / 2f, dp(30f))

        fun startGlow() {
            removeCallbacks(tick)
            glowing = true
            glowStart = SystemClock.uptimeMillis()
            post(tick)
        }

        fun stopGlow() {
            glowing = false
            removeCallbacks(tick)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            stroke.shader = SweepGradient(w / 2f, h / 2f, GLOW, null)
            invalidateOutline()
        }

        override fun onDraw(c: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            val r = radius()
            c.drawRoundRect(rect, r, r, fill)
        }

        // 描边画在子 View 之上，波浪不会盖住它
        override fun dispatchDraw(c: Canvas) {
            super.dispatchDraw(c)
            val inset = stroke.strokeWidth / 2
            rect.set(inset, inset, width - inset, height - inset)
            val r = radius() - inset
            rot.setRotate(angle, width / 2f, height / 2f)
            stroke.shader?.setLocalMatrix(rot)
            c.drawRoundRect(rect, r, r, stroke)
        }
    }

    companion object {
        private val ENTER = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val EXIT = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        private const val FRAME_MS = 40L
        private const val GLOW_PERIOD_MS = 4000L
        private val GLOW = intArrayOf(
            0xFF4F8BFF.toInt(), 0xFF9B5CFF.toInt(), 0xFFFF5CA8.toInt(), 0xFF3DDCFF.toInt(), 0xFF4F8BFF.toInt(),
        )
    }
}

/**
 * 铺在用户文字背后的声波：三条半透明的「透镜」状波带叠在一起，横跨整个胶囊，两端收拢。
 * LISTEN 时振幅跟随麦克风电平；THINK 时小幅缓慢流动。
 */
class WaveView(ctx: Context) : View(ctx) {
    enum class Mode { LISTEN, THINK }

    var mode = Mode.LISTEN

    private val density = ctx.resources.displayMetrics.density
    private val paints = WAVES.map { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val path = Path()
    private val ys = FloatArray(512)
    private var target = 0f
    private var amp = 0f
    private var phase = 0f
    private var lastFrame = 0L

    /** 电平 dBFS → 0..1。手机自带麦克风说话大约 -45 ~ -20 dB。 */
    fun setLevel(db: Float) {
        target = ((db + 60f) / 34f).coerceIn(0f, 1f)
    }

    /** 只做背景：高度跟着文字区走，自己不要求高度（否则 WRAP_CONTENT 的浮窗会被撑满全屏）。 */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        WAVES.forEachIndexed { i, wv ->
            paints[i].shader = LinearGradient(0f, 0f, w.toFloat(), 0f, wv.colors, null, Shader.TileMode.CLAMP)
            paints[i].alpha = wv.alpha
        }
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceAtMost(0.05f)
        lastFrame = now
        val t = now / 1000f

        val goal = when (mode) {
            Mode.LISTEN -> 0.25f + 0.75f * target
            Mode.THINK -> 0.45f + 0.12f * sin(t * 2.5f)
        }
        // 起得快、落得慢，看起来更跟手（系数按 25fps 取）
        amp += (goal - amp) * (if (goal > amp) 0.6f else 0.22f)
        phase += dt * (if (mode == Mode.THINK) 2.5f else 5f)

        val w = width.toFloat()
        val mid = height / 2f
        val maxH = height / 2f - density
        val step = 6 * density
        val n = min((w / step).toInt() + 1, ys.size)
        WAVES.forEachIndexed { i, wv ->
            // 上沿和下沿关于中线对称，围出一串透镜形的波带
            for (k in 0 until n) {
                val u = k * step / w
                // 两端收拢；sqrt 让中间段更饱满，不至于只剩中间一个尖
                val env = kotlin.math.sqrt(sin(PI * u.coerceAtMost(1f)).toFloat())
                ys[k] = amp * wv.scale * maxH * env *
                    abs(sin(2 * PI * wv.freq * u + phase * wv.speed + wv.offset).toFloat())
            }
            path.reset()
            path.moveTo(0f, mid)
            for (k in 0 until n) path.lineTo(k * step, mid - ys[k])
            for (k in n - 1 downTo 0) path.lineTo(k * step, mid + ys[k])
            path.close()
            c.drawPath(path, paints[i])
        }
    }

    private class Wave(
        val freq: Float, val speed: Float, val offset: Float, val scale: Float, val alpha: Int, val colors: IntArray,
    )

    companion object {
        private val WAVES = listOf(
            Wave(1.3f, 1.0f, 0f, 1.0f, 120, intArrayOf(0xFF3DDCFF.toInt(), 0xFF4F8BFF.toInt(), 0xFF9B5CFF.toInt())),
            Wave(1.9f, -1.4f, 1.2f, 0.8f, 105, intArrayOf(0xFF9B5CFF.toInt(), 0xFFFF5CA8.toInt(), 0xFF4F8BFF.toInt())),
            Wave(1.0f, 0.7f, 2.4f, 0.65f, 90, intArrayOf(0xFF4F8BFF.toInt(), 0xFF3DDCFF.toInt(), 0xFFFF5CA8.toInt())),
        )
    }
}
