package com.stepx.backmatrix.tools

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.GlyphArt
import com.stepx.backmatrix.core.PixelText
import com.stepx.backmatrix.core.Scene
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 倒扣禅定：手机扣在桌上开始倒计时，整屏像素一点点沙化剥落。
 *
 * 反作弊靠加速度计：倒扣时 z 轴朝下（az < -7），一旦翻过来就开始碎心，
 * 并把这次失败记进次数。这个计数器是留在面板上的"耻辱柱"。
 */
class FocusTool(private val context: Context) : ToolInstance, SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs = ToolPrefs(context)
    private val minutes: Int = prefs.int("focus", "minutes", 25).coerceIn(1, 120)
    private val durationMs: Long = minutes * 60_000L

    private enum class Phase { READY, RUNNING, BROKEN, DONE }

    @Volatile private var phase = Phase.READY
    private var startedAt = 0L
    private var brokenAt = 0L
    private var faceDown = false
    @Volatile private var brokenCount: Int = prefs.int("focus", "broken", 0)

    override fun start() {
        val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun stop() {
        sm.unregisterListener(this)
        phase = Phase.READY
    }

    override fun onSensorChanged(e: SensorEvent) {
        val az = e.values[2]
        val now = SystemClock.elapsedRealtime()
        val down = az < -7f
        if (down == faceDown) return
        faceDown = down
        when {
            down && (phase == Phase.READY || phase == Phase.DONE) -> {
                phase = Phase.RUNNING
                startedAt = now
            }
            !down && phase == Phase.RUNNING -> {
                val elapsed = now - startedAt
                if (elapsed < durationMs) {
                    // 提前翻开：破功
                    phase = Phase.BROKEN
                    brokenAt = now
                    brokenCount += 1
                    prefs.put("focus", "broken", brokenCount)
                } else {
                    phase = Phase.DONE
                }
            }
            !down && phase == Phase.BROKEN -> {
                // 已经碎过心，翻回来就重新待机
                phase = Phase.READY
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun frameDelayMs(): Long = 90L

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        Scene.clear(buf)
        val now = SystemClock.elapsedRealtime()
        if (phase == Phase.RUNNING && now - startedAt >= durationMs) phase = Phase.DONE

        when (phase) {
            Phase.READY -> {
                PixelText.drawCentered(buf, "$minutes", 7, 2, 180)
                PixelText.drawCentered(buf, "FACE", 18, 1, 70)
                // 底部用点记录失败次数，最多 8 个
                repeat(brokenCount.coerceAtMost(8)) { i ->
                    Scene.max(buf, 3 + i * 2, Dot.H - 2, 150)
                }
            }
            Phase.RUNNING -> {
                val elapsed = (now - startedAt).coerceAtLeast(0)
                val p = (elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0)
                drawEroding(buf, p.toFloat(), t)
                val remain = ((durationMs - elapsed) / 1000).coerceAtLeast(0)
                val text = if (remain >= 60) "${remain / 60}" else "$remain"
                PixelText.drawCentered(buf, text, 8, 2, 0, erase = true)
            }
            Phase.BROKEN -> {
                val age = now - brokenAt
                // 刚翻开先来一帧冲击波，再把心撕开——只画一颗小心的话根本看不出"碎"
                if (age < 140) Scene.ring(buf, 8f, 10f, 8.2f, 120)
                val gap = (age / 320L).toInt().coerceIn(0, 2)
                GlyphArt.heart(buf, if (gap >= 2) 255 else 235, brokenGap = gap)
                PixelText.drawCentered(buf, "FAIL", 15, 1, 200)
                // 失败次数用小点排在最后一行，最多 8 个
                repeat(brokenCount.coerceAtMost(8)) { i ->
                    Scene.max(buf, 2 + i * 2, Dot.H - 2, 150)
                }
            }
            Phase.DONE -> {
                GlyphArt.check(buf, 8.5f, 10.5f, 10f, 255)
                PixelText.drawCentered(buf, "DONE", 18, 1, 150)
            }
        }
    }

    /**
     * 沙化：从四边往里剥，每格的消失时刻由"离边距离 + 一点随机"决定，
     * 所以看起来是边缘先塌，中间最后剩一小撮。
     */
    private fun drawEroding(buf: ByteArray, progress: Float, t: Double) {
        for (y in 0 until Dot.H) {
            for (x in 0 until Dot.W) {
                val d = min(min(x, Dot.W - 1 - x), min(y, Dot.H - 1 - y)).toFloat()
                val hash = (((x * 73856093) xor (y * 19349663)) and 0xFF) / 255f
                val threshold = d / 8.2f + hash * 0.22f
                if (progress * 1.35f > threshold) continue
                // 没剥落的部分带一点点闪烁，像沙子在动
                val flicker = 0.85 + 0.15 * sin(t * 3.1 + hash * 6.3)
                val v = (200 * flicker).toInt()
                Scene.max(buf, x, y, v)
            }
        }
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        drawEroding(buf, 0.45f, 0.4)
        PixelText.drawCentered(buf, "12", 8, 2, 0, erase = true)
    }

    override fun status(): String = when (phase) {
        Phase.READY -> "倒扣手机开始 · $minutes 分钟" + if (brokenCount > 0) " · 已失败 $brokenCount 次" else ""
        Phase.RUNNING -> {
            val remain = ((durationMs - (SystemClock.elapsedRealtime() - startedAt)) / 1000).coerceAtLeast(0)
            "专注中 · 还剩 ${remain / 60}:${(remain % 60).toString().padStart(2, '0')}"
        }
        Phase.BROKEN -> "破功了 · 累计 $brokenCount 次"
        Phase.DONE -> "完成 · 这次没翻手机"
    }

    fun resetBroken() {
        brokenCount = 0
        prefs.put("focus", "broken", 0)
    }
}

/**
 * 摇一摇骰子 / 硬币 / 转箭头。
 *
 * 面板在背面，所以"正面朝下"是最自然的使用姿势；但没有强制，
 * 摇一下就出结果，省得为了看一眼还得翻过来。
 */
class DiceTool(private val context: Context) : ToolInstance, SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs = ToolPrefs(context)
    private val mode: String = prefs.str("dice", "mode", "dice")

    private val rnd = Random(System.nanoTime())
    @Volatile private var result = 0
    @Volatile private var rolling = false
    @Volatile private var rollStarted = 0L
    @Volatile private var arrowSpeed = 0.0
    @Volatile private var arrowAngle = 0.0
    private var lastShakeAt = 0L
    private var lastMag = 0f

    override fun start() {
        val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        val now = SystemClock.elapsedRealtime()
        val jerk = abs(mag - lastMag)
        lastMag = mag
        // 用"相邻采样差值"判抖动，比绝对值阈值更不容易被静止误触
        if (jerk > 9f && now - lastShakeAt > 700 && !rolling) {
            lastShakeAt = now
            rolling = true
            rollStarted = now
            arrowSpeed = 14.0 + rnd.nextDouble() * 6
            result = if (mode == "coin") rnd.nextInt(2) else rnd.nextInt(6) + 1
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun frameDelayMs(): Long = 55L

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        Scene.clear(buf)
        val now = SystemClock.elapsedRealtime()
        val rollMs = 1100L
        if (rolling) {
            val age = now - rollStarted
            if (age >= rollMs) {
                rolling = false
                result = if (mode == "coin") rnd.nextInt(2) else rnd.nextInt(6) + 1
            } else {
                // 翻滚过程中快速换值，最后一下才定
                if ((age / 90) % 2 == 0L) {
                    result = if (mode == "coin") rnd.nextInt(2) else rnd.nextInt(6) + 1
                }
            }
        }

        when (mode) {
            "arrow" -> {
                if (rolling) {
                    arrowAngle += arrowSpeed * dt
                    arrowSpeed *= 0.985
                }
                Scene.arrow(buf, 8f, 11.5f, arrowAngle.toFloat(), 7.6f, 255, head = 3.6f)
                Scene.ellipse(buf, 8f, 11.5f, 0.9f, 0.9f, 200)
                if (rolling) Scene.ring(buf, 8f, 11.5f, 8.4f, 90)
            }
            "coin" -> {
                if (rolling) {
                    drawCoin(buf, ((now / 90) % 2).toInt() == 0)
                } else {
                    drawCoin(buf, result == 1)
                }
            }
            else -> {
                if (rolling) {
                    // 翻滚时快速换面，方框也跟着轻微抖动，像在桌上滚
                    val jx = rnd.nextInt(-1, 2)
                    val jy = rnd.nextInt(-1, 2)
                    drawDieFace(buf, rnd.nextInt(6) + 1, jx, jy, 200)
                } else {
                    drawDieFace(buf, result.coerceIn(1, 6), 0, 0, 255)
                }
            }
        }
    }

    /**
     * 骰子面：一个方框 + 按点数排的点，而不是把头大一个数字塞进去。
     *
     * 框中 1 点居中、2 点对角、3 点斜线、4 点四角、5 点四角加心、6 点两列三点，
     * 和实物的骰子一致，扫一眼就知道是几。
     */
    private fun drawDieFace(buf: ByteArray, n: Int, dx: Int, dy: Int, v: Int) {
        val x0 = 2 + dx
        val y0 = 5 + dy
        val x1 = 14 + dx
        val y1 = 17 + dy
        // 四个直角不画，看起来像圆角骰子
        Scene.hLine(buf, x0 + 1, x1 - 1, y0, v)
        Scene.hLine(buf, x0 + 1, x1 - 1, y1, v)
        Scene.vLine(buf, x0, y0 + 1, y1 - 1, v)
        Scene.vLine(buf, x1, y0 + 1, y1 - 1, v)

        val cols = intArrayOf(x0 + 3, x0 + 6, x0 + 9)
        val rows = intArrayOf(y0 + 3, y0 + 6, y0 + 9)
        val pips = when (n) {
            1 -> listOf(1 to 1)
            2 -> listOf(0 to 0, 2 to 2)
            3 -> listOf(0 to 0, 1 to 1, 2 to 2)
            4 -> listOf(0 to 0, 2 to 0, 0 to 2, 2 to 2)
            5 -> listOf(0 to 0, 2 to 0, 1 to 1, 0 to 2, 2 to 2)
            else -> listOf(0 to 0, 0 to 1, 0 to 2, 2 to 0, 2 to 1, 2 to 2)
        }
        for ((c, r) in pips) Scene.put(buf, cols[c], rows[r], v)
    }

    /** 硬币：数字面画「1」，花面画五瓣花（不用 H/T，那套说法在中文语境里不直观）。 */
    private fun drawCoin(buf: ByteArray, flower: Boolean) {
        val cx = 8f
        val cy = 11.5f
        Scene.circle(buf, cx, cy, 7.4f, 150)

        if (flower) {
            // 五瓣花：中间一个花心，四周五个花瓣，花瓣小一点才留得出间隙
            Scene.ellipse(buf, cx, cy, 1.2f, 1.2f, 245)
            for (i in 0 until 5) {
                val a = i * 2 * PI / 5 - PI / 2
                val px = cx + sin(a).toFloat() * 3.2f
                val py = cy - cos(a).toFloat() * 3.2f
                Scene.ellipse(buf, px, py, 1.15f, 1.15f, 215)
            }
        } else {
            // scale=2 的"1"是 6x10，四周都留得下白边；scale=3 会顶到币边
            PixelText.drawCentered(buf, "1", 6, 2, 255)
        }
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        when (mode) {
            "coin" -> drawCoin(buf, flower = false)
            "arrow" -> Scene.arrow(buf, 8f, 11.5f, 0.7f, 7.6f, 240, head = 3.6f)
            else -> drawDieFace(buf, 5, 0, 0, 240)
        }
    }

    override fun status(): String = when {
        rolling -> "摇动中…"
        mode == "coin" -> "硬币 · ${if (result == 0) "数字面" else "花面"}（摇一摇再掷）"
        mode == "arrow" -> "箭头 · 摇一摇转一次"
        else -> "骰子 · ${result.coerceAtLeast(1)} 点（摇一摇再掷）"
    }
}
