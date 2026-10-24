package com.stepx.backmatrix.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.stepx.backmatrix.core.BackScreen
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.DotAnimation
import com.stepx.backmatrix.tools.ToolInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放器：把画面按节奏推给背屏，并对外暴露当前帧供界面预览。
 *
 * 两种驱动源共用同一条提交管线（亮度 → 旋转 → 去重 → 反射调用）：
 *  - 动画：.bsa 逐帧循环，帧内容固定
 *  - 实时工具：每帧由传感器/网络现算，帧数无穷
 *
 * 只有一个实例，前台服务与界面共用，避免两处同时写背屏。
 */
object MatrixPlayer {

    data class State(
        val playing: Boolean = false,
        val title: String = "",
        val fps: Int = 12,
        val frameIndex: Int = 0,
        val frameCount: Int = 0,
        val rotationTurns: Int = 0,
        val brightness: Float = 1f,
        val error: String? = null,
        /** 工具渲染自身抛出的异常，与背屏接口错误分开报 */
        val renderError: String? = null,
        /** true 表示当前跑的是实时工具，界面不显示帧号 */
        val live: Boolean = false,
        val toolId: String = "",
        val status: String = ""
    )

    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _frame = MutableStateFlow<ByteArray?>(null)
    val frame: StateFlow<ByteArray?> = _frame.asStateFlow()

    private var animation: DotAnimation? = null
    private var tool: ToolInstance? = null
    private var lastSubmitted: ByteArray? = null
    /** 上一次推给界面的帧；用内容比较避免每帧都触发重组 */
    private var lastEmitted: ByteArray? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nextFrameAt = 0L
    private var appContext: Context? = null

    /** 工具用的帧缓冲，复用一块内存，避免每秒分配几十个 ByteArray。 */
    private val buffer = ByteArray(Dot.PIXELS)
    private var toolStartedAt = 0L
    private var lastTickAt = 0L
    private var lastStatusAt = 0L
    private var debugTicks = 0

    fun isPlaying(): Boolean = _state.value.playing && (animation != null || tool != null)

    fun start(context: Context, anim: DotAnimation, fromStart: Boolean = true) {
        val ctx = context.applicationContext
        appContext = ctx
        releaseTool()
        animation = anim
        invalidate()
        _state.value = _state.value.copy(
            playing = true,
            title = anim.name,
            fps = anim.fps,
            frameCount = anim.frameCount,
            frameIndex = if (fromStart) 0 else _state.value.frameIndex,
            error = null,
            renderError = null,
            live = false,
            toolId = "",
            status = ""
        )
        acquireWakeLock(ctx)
        nextFrameAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** 启动一个实时工具。切换动画/工具时旧的会被停掉。 */
    fun startTool(context: Context, toolId: String, title: String, instance: ToolInstance) {
        val ctx = context.applicationContext
        appContext = ctx
        releaseTool()
        animation = null
        tool = instance
        invalidate()
        // 清掉上一个工具留下的画面：否则渲染万一失败，面板会继续显示旧帧，
        // 看起来"在运行"，实际什么都没画
        java.util.Arrays.fill(buffer, 0)
        _state.value = _state.value.copy(
            playing = true,
            title = title,
            fps = 0,
            frameCount = 0,
            frameIndex = 0,
            error = null,
            renderError = null,
            live = true,
            toolId = toolId,
            status = ""
        )
        acquireWakeLock(ctx)
        runCatching { instance.start() }
        toolStartedAt = SystemClock.uptimeMillis()
        lastTickAt = 0L
        lastStatusAt = 0L
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** 让正在跑的工具做一次运行时动作，例如计数器清零。没在跑就什么都不做。 */
    fun resetTool() {
        tool?.let { runCatching { it.reset() } }
    }

    fun pause(context: Context) {
        handler.removeCallbacks(tick)
        _state.value = _state.value.copy(playing = false)
        releaseWakeLock()
        // 保留最后一帧在背屏上，不主动清除；工具的传感器/线程要停下省电
        tool?.let { runCatching { it.stop() } }
    }

    /** 完全停止并交还背屏。 */
    fun stop(context: Context) {
        handler.removeCallbacks(tick)
        val ctx = appContext ?: context.applicationContext
        if (lastSubmitted != null) {
            BackScreen.show(ctx, null)
        }
        releaseTool()
        animation = null
        invalidate()
        releaseWakeLock()
        _state.value = _state.value.copy(
            playing = false, frameIndex = 0, title = "", frameCount = 0,
            error = null, renderError = null, live = false, toolId = "", status = ""
        )
        _frame.value = null
    }

    fun setRotation(context: Context, turns: Int) {
        _state.value = _state.value.copy(rotationTurns = ((turns % 4) + 4) % 4)
        invalidate()
        if (!_state.value.playing) {
            // 暂停状态也要让画面跟着转，方便摆姿势
            handler.post(tick)
        }
    }

    fun setBrightness(context: Context, value: Float) {
        _state.value = _state.value.copy(brightness = value.coerceIn(0.15f, 1f))
        invalidate()
        if (!_state.value.playing) handler.post(tick)
    }

    fun step(context: Context, delta: Int) {
        val anim = animation ?: return
        val next = ((_state.value.frameIndex + delta) % anim.frameCount + anim.frameCount) % anim.frameCount
        _state.value = _state.value.copy(frameIndex = next)
        invalidate()
        handler.post(tick)
    }

    /** 让下一帧强制重新提交与重新发射（换动画、改旋转/亮度、清屏时用）。 */
    private fun invalidate() {
        lastSubmitted = null
        lastEmitted = null
    }

    private fun releaseTool() {
        tool?.let { runCatching { it.stop() } }
        tool = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            val s = _state.value

            tool?.let { inst ->
                val now = SystemClock.uptimeMillis()
                val dt = if (lastTickAt == 0L) 0.05 else ((now - lastTickAt) / 1000.0).coerceIn(0.0, 0.4)
                lastTickAt = now
                val t = (now - toolStartedAt) / 1000.0
                // 渲染异常必须冒出来：静默吞掉的话面板会停在上一个工具的画面上，
                // 看起来"在跑"，实际什么都没画
                val renderError = try {
                    inst.render(buffer, t, dt)
                    null
                } catch (e: Throwable) {
                    java.util.Arrays.fill(buffer, 0)
                    "工具渲染异常：${e.javaClass.simpleName} ${e.message ?: ""}".trim()
                }
                if (renderError != _state.value.renderError) {
                    _state.value = _state.value.copy(renderError = renderError)
                }
                submit(ctx, buffer, s)

                if (now - lastStatusAt > 320) {
                    lastStatusAt = now
                    val st = runCatching { inst.status() }.getOrDefault("")
                    if (st != _state.value.status) _state.value = _state.value.copy(status = st)
                }
                if (!_state.value.playing) return
                handler.postDelayed(this, inst.frameDelayMs().coerceIn(20L, 1000L))
                return
            }

            val anim = animation ?: return
            val index = s.frameIndex.coerceIn(0, (anim.frameCount - 1).coerceAtLeast(0))
            val raw = anim.frameAt(index)
            submit(ctx, raw, s)

            if (!s.playing) return

            var nextIndex = index + 1
            if (nextIndex >= anim.frameCount) {
                if (!anim.loop) {
                    handler.removeCallbacks(this)
                    _state.value = _state.value.copy(playing = false, frameIndex = anim.frameCount - 1)
                    releaseWakeLock()
                    return
                }
                nextIndex = 0
            }
            _state.value = _state.value.copy(frameIndex = nextIndex)

            nextFrameAt += (1000L / anim.fps.coerceIn(1, 60))
            val delay = (nextFrameAt - SystemClock.uptimeMillis()).coerceAtLeast(1L)
            if (delay > 500) nextFrameAt = SystemClock.uptimeMillis() + 1000L / anim.fps
            handler.postDelayed(this, delay)
        }
    }

    /** 亮度、旋转、去重、提交。两条驱动源都走这里，行为才一致。 */
    private fun submit(ctx: Context, raw: ByteArray, s: State) {
        // 必须用 compose 的独立快照，不能直接用 raw：实时工具每帧复用同一块 buffer，
        // lastSubmitted 存下这块内存后，下一帧的 contentEquals 就是自己跟自己比，
        // 背屏会停在第一帧，而界面拿的是拷贝，看起来照旧在动。
        val out = Dot.compose(raw, s.brightness, s.rotationTurns)
        if (lastSubmitted == null || !out.contentEquals(lastSubmitted)) {
            val ok = BackScreen.show(ctx, out)
            lastSubmitted = if (ok) out else null
            if (!ok) {
                _state.value = _state.value.copy(error = BackScreen.lastError ?: "背屏接口调用失败")
            } else if (_state.value.error != null) {
                _state.value = _state.value.copy(error = null)
            }
        }
        if (lastEmitted == null || !out.contentEquals(lastEmitted)) {
            lastEmitted = out
            _frame.value = out
        }
    }

    private fun acquireWakeLock(context: Context) {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PZBackMatrix:playback").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
