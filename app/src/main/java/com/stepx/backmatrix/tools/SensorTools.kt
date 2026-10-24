package com.stepx.backmatrix.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.PixelText
import com.stepx.backmatrix.core.Scene
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 数字水准泡：加速度矢量决定气泡往哪边跑。 */
class BubbleLevelTool(private val context: Context) : ToolInstance, SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs = ToolPrefs(context)
    private val tolerance: Float = prefs.float("level", "tol", 1.2f)

    @Volatile private var ax = 0f
    @Volatile private var ay = 0f
    @Volatile private var az = 9.81f
    @Volatile private var alive = false

    override fun start() {
        val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        // 低通：手上抖动不该让气泡乱跳
        ax = ax * 0.75f + e.values[0] * 0.25f
        ay = ay * 0.75f + e.values[1] * 0.25f
        az = az * 0.75f + e.values[2] * 0.25f
        alive = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun frameDelayMs(): Long = 60L

    private fun tiltDeg(): Float {
        val flat = sqrt(ax * ax + ay * ay)
        return Math.toDegrees(atan2(flat.toDouble(), abs(az).toDouble())).toFloat()
    }

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        Scene.clear(buf)
        val cx = 8f
        val cy = 11.5f

        // 十字准星，越靠中心越亮
        for (i in 0 until 7) {
            val v = (70 - i * 8).coerceAtLeast(24)
            Scene.max(buf, (cx.roundToInt() - 1 - i), cy.roundToInt(), v)
            Scene.max(buf, (cx.roundToInt() + 1 + i), cy.roundToInt(), v)
            Scene.max(buf, cx.roundToInt(), (cy.roundToInt() - 2 - i), v)
            Scene.max(buf, cx.roundToInt(), (cy.roundToInt() + 2 + i), v)
        }

        val deg = tiltDeg()
        val level = deg <= tolerance

        if (level && alive) {
            // 达标：向外扩散一圈光晕
            val period = 1.2
            val p = (t % period) / period
            val r = 2.5 + p * 5.5
            Scene.ring(buf, cx, cy, r.toFloat(), (190 * (1 - p)).toInt())
            Scene.ring(buf, cx, cy, 7.2f, 60)
        }

        // 气泡：倾角换算成点阵偏移，3x3 方块
        val bx = (ax / 9.81f * 22f).coerceIn(-6f, 6f)
        val by = (ay / 9.81f * 22f).coerceIn(-8f, 8f)
        val px = (cx + bx).roundToInt()
        val py = (cy + by).roundToInt()
        Scene.rect(buf, px - 1, py - 1, px + 1, py + 1, if (level) 255 else 210, fill = true)

        if (!alive) {
            PixelText.drawCentered(buf, "NO ACC", 20, 1, 80)
        }
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        val cx = 8
        val cy = 12
        for (i in 0 until 6) {
            val v = (60 - i * 9).coerceAtLeast(20)
            Scene.max(buf, cx - 1 - i, cy, v); Scene.max(buf, cx + 1 + i, cy, v)
            Scene.max(buf, cx, cy - 2 - i, v); Scene.max(buf, cx, cy + 2 + i, v)
        }
        Scene.rect(buf, cx - 1, cy - 1, cx + 1, cy + 1, 240, fill = true)
        Scene.ring(buf, 8f, 11.5f, 4.5f, 120)
        Scene.ring(buf, 8f, 11.5f, 7.2f, 60)
    }

    override fun status(): String =
        if (!alive) "等待加速度计数据" else "倾角 %.1f° · %s".format(degOrZero(), if (tiltDeg() <= tolerance) "水平" else "倾斜")

    private fun degOrZero(): Float = if (alive) tiltDeg() else 0f
}

/**
 * 水平线水准仪：一根参考线加一个气泡，判断边缘有没有放平。
 *
 * 竖着拿量顶边（x 轴）、横着拿量长边（y 轴），方向自动切换；
 * 气泡沿被测的那条边滑动，重力在该边的分量为零即水平。
 *
 * 与数字水准泡的分工：那个测手机背面贴着的面，这个测竖直面上的边。
 */
class LineLevelTool(private val context: Context) : ToolInstance, SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs = ToolPrefs(context)
    private val tolerance: Float = prefs.float("line", "tol", 0.8f).coerceIn(0.2f, 3f)

    @Volatile private var ax = 0f
    @Volatile private var ay = 0f
    @Volatile private var alive = false

    override fun start() {
        val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        ax = ax * 0.7f + e.values[0] * 0.3f
        ay = ay * 0.7f + e.values[1] * 0.3f
        alive = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun frameDelayMs(): Long = 60L

    /** 屏幕坐标里的重力：x 与设备 x 同向，y 与设备 y 反向（屏幕 y 朝下）。 */
    private fun gravX() = -ax
    private fun gravY() = ay

    /** 重力主要压在屏幕竖直方向 → 竖着拿。 */
    private fun upright(): Boolean = abs(ay) >= abs(ax)

    /** 重力几乎全压在 z 轴上 → 手机平放，此时两条边都算水平。 */
    private fun flat(): Boolean = sqrt(gravX() * gravX() + gravY() * gravY()) < 1.5f

    /** 被测那条边相对水平面的夹角，正负表示往哪一头倒。 */
    fun tiltDeg(): Float {
        if (flat()) return 0f
        val mag = sqrt(gravX() * gravX() + gravY() * gravY())
        val along = if (upright()) gravX() else gravY()
        return Math.toDegrees(asin((along / mag).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    private fun isLevel(): Boolean = alive && abs(tiltDeg()) <= tolerance

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        Scene.clear(buf)
        val deg = tiltDeg()
        val level = isLevel()
        // ±12° 走完全程，再大就贴着端点
        val slide = (deg / 12f).coerceIn(-1f, 1f)
        val pulse = 0.5 + 0.5 * sin(t * 4.5)

        if (upright()) {
            // 竖放：量的是顶边。一条横贯的轨道，中段是"窗口"
            Scene.hLine(buf, 0, Dot.W - 1, 11, 36)
            Scene.hLine(buf, 6, 10, 11, if (level) (150 + 90 * pulse).toInt() else 100)
            Scene.vLine(buf, 0, 9, 13, 66)
            Scene.vLine(buf, Dot.W - 1, 9, 13, 66)
            // 命中时窗口框整体点亮，比只靠气泡位置更容易一眼确认
            val frame = if (level) (170 + 80 * pulse).toInt() else 95
            Scene.vLine(buf, 6, 8, 14, frame)
            Scene.vLine(buf, 10, 8, 14, frame)
            val px = (8f + slide * 6f).roundToInt().coerceIn(1, Dot.W - 2)
            Scene.rect(buf, px - 1, 10, px + 1, 12, 255, fill = true)
            // 数字放得下就写，比两像素的偏移直观
            val text = String.format(java.util.Locale.US, "%.1f", abs(deg))
            if (text.length <= 4) PixelText.drawCentered(buf, text, 2, 1, if (level) 205 else 110)
        } else {
            // 横放：量的是长边。轨道竖贯面板，用户把头转 90° 看它就是一条水平线
            Scene.vLine(buf, 8, 0, Dot.H - 1, 36)
            Scene.vLine(buf, 8, 9, 13, if (level) (150 + 90 * pulse).toInt() else 100)
            Scene.hLine(buf, 6, 10, 0, 66)
            Scene.hLine(buf, 6, 10, Dot.H - 1, 66)
            val frame = if (level) (170 + 80 * pulse).toInt() else 95
            Scene.hLine(buf, 5, 11, 9, frame)
            Scene.hLine(buf, 5, 11, 13, frame)
            val py = (11f + slide * 8f).roundToInt().coerceIn(1, Dot.H - 2)
            Scene.rect(buf, 7, py - 1, 9, py + 1, 255, fill = true)
        }

        if (!alive) PixelText.drawCentered(buf, "ACC", 2, 1, 70)
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        PixelText.drawCentered(buf, "0.0", 2, 1, 150)
        Scene.hLine(buf, 0, Dot.W - 1, 11, 36)
        Scene.hLine(buf, 6, 10, 11, 170)
        Scene.vLine(buf, 0, 9, 13, 66)
        Scene.vLine(buf, Dot.W - 1, 9, 13, 66)
        Scene.vLine(buf, 6, 8, 14, 95)
        Scene.vLine(buf, 10, 8, 14, 95)
        Scene.rect(buf, 7, 10, 9, 12, 245, fill = true)
    }

    override fun status(): String {
        if (!alive) return "等待加速度计"
        val deg = tiltDeg()
        // -0.04° 会被格式化成 "-0.0°"，正负号在零附近只会添乱
        val shown = if (abs(deg) < 0.05f) "0.0" else String.format(java.util.Locale.US, "%+.1f", deg)
        val pose = when {
            flat() -> "平放"
            upright() -> "竖放"
            else -> "横放"
        }
        return "%s · %s° · %s".format(pose, shown, if (abs(deg) <= tolerance) "水平" else "倾斜")
    }
}

/**
 * 环境分贝热力：麦克风 RMS → 能量柱。
 * 安静时只有底部波纹，有人提高嗓门整根柱子顶到顶。
 */
class NoiseMeterTool(private val context: Context) : ToolInstance {

    private val prefs = ToolPrefs(context)
    private val gain: Float = prefs.float("noise", "gain", 1f).coerceIn(0.4f, 3f)

    @Volatile private var level = 0f
    @Volatile private var peak = 0f
    @Volatile private var alive = false
    @Volatile private var error: String? = null

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private val sampleRate = 16000

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun start() {
        if (!hasPermission()) {
            error = "没有录音权限"
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            error = "麦克风不可用"
            return
        }
        val bufSize = maxOf(minBuf, sampleRate / 4 * 2)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            error = "麦克风打不开"
            runCatching { rec?.release() }
            return
        }
        record = rec
        running = true
        rec.startRecording()
        val th = Thread({
            val buf = ShortArray(1024)
            while (running) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(0)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768.0
                    sum += v * v
                }
                val rms = sqrt(sum / n)
                val dbfs = if (rms < 1e-7) -90.0 else 20 * log10(rms)
                // -55dBFS 以下当作安静，-8dBFS 当满格
                val norm = ((-55.0 - dbfs) * -1 / 47.0 * gain).toFloat().coerceIn(0f, 1f)
                level = if (norm > level) level * 0.35f + norm * 0.65f else level * 0.90f + norm * 0.10f
                if (level > peak) peak = level
                alive = true
            }
        }, "noise-meter")
        th.isDaemon = true
        th.start()
        thread = th
    }

    override fun stop() {
        running = false
        thread?.let { runCatching { it.join(300) } }
        thread = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        level = 0f
    }

    override fun frameDelayMs(): Long = 55L

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        Scene.clear(buf)
        peak = (peak - 0.008f).coerceAtLeast(level)

        val top = (Dot.H * level).roundToInt().coerceIn(0, Dot.H)
        for (y in (Dot.H - top) until Dot.H) {
            // 能量柱用横向摆动的水纹填充，越高越亮
            val yFromBottom = Dot.H - 1 - y
            val wave = sin(t * 4.0 + yFromBottom * 0.8) * 0.5 + 0.5
            val base = (70 + 150 * (yFromBottom.toFloat() / Dot.H))
            val v = (base * (0.55 + 0.45 * wave)).toInt()
            for (x in 0 until Dot.W) Scene.max(buf, x, y, v.coerceIn(0, 235))
        }

        // 峰值保持线
        if (peak > 0.04f) {
            val py = (Dot.H - 1 - (Dot.H * peak).roundToInt()).coerceIn(0, Dot.H - 1)
            for (x in 0 until Dot.W) Scene.max(buf, x, py, 255)
        }

        if (level < 0.04f) {
            // 安静到没有能量柱时也要有东西，否则面板看起来像是挂了
            for (x in 0 until Dot.W) {
                val v1 = (38 + 34 * (sin(t * 1.6 + x * 0.6) * 0.5 + 0.5)).toInt()
                Scene.max(buf, x, Dot.H - 1, v1)
                if (x % 2 == 0) {
                    val v2 = (26 + 26 * (sin(t * 1.2 + x * 0.4 + 1.4) * 0.5 + 0.5)).toInt()
                    Scene.max(buf, x, Dot.H - 2, v2)
                }
            }
        }

        if (error != null && !alive) {
            PixelText.drawCentered(buf, "MIC", 4, 1, 90)
        }
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        for (y in 0 until Dot.H) {
            val yFromBottom = Dot.H - 1 - y
            val frac = yFromBottom.toFloat() / Dot.H
            if (frac > 0.55f) continue
            val v = (60 + 170 * frac).toInt()
            for (x in 0 until Dot.W) Scene.max(buf, x, y, (v * (0.6 + 0.4 * sin(x * 0.9))).toInt().coerceAtLeast(30))
        }
        for (x in 0 until Dot.W) Scene.max(buf, x, 9, 240)
    }

    override fun status(): String = when {
        error != null -> error!!
        !alive -> "等待声音…"
        else -> "电平 ${(level * 100).toInt()}%"
    }
}

/** 地磁航向针：箭头始终指地理北。 */
class CompassTool(private val context: Context) : ToolInstance, SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile private var azimuthDeg = 0f
    @Volatile private var alive = false

    override fun start() {
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, e.values)
        SensorManager.getOrientation(rotation, orientation)
        var deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (deg < 0) deg += 360f
        // 角度做圆周平滑，避免 359→0 跳变时箭头抽一下
        val diff = ((deg - azimuthDeg + 540f) % 360f) - 180f
        azimuthDeg = (azimuthDeg + diff * 0.25f + 360f) % 360f
        alive = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun frameDelayMs(): Long = 60L

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        Scene.clear(buf)

        // 底部：航向角三位数。背屏上唯一能读到精确值的入口
        val deg = ((azimuthDeg.roundToInt() % 360) + 360) % 360
        PixelText.drawCentered(
            buf,
            if (alive) deg.toString().padStart(3, '0') else "---",
            18, 1,
            if (alive) 255 else 70
        )

        drawDial(buf, if (alive) azimuthDeg else 0f, if (alive) 255 else 90)
    }

    /**
     * 罗盘：固定朝上的针 + 绕圈转的北点。
     *
     * 针永远朝屏幕上方，代表你正对的方向；北由表圈上最亮的一点表示，绕着你转。
     * 不写方位缩写，3×5 字体里 N 和 W 难以分辨。
     */
    private fun drawDial(buf: ByteArray, azimuthDeg: Float, markV: Int) {
        val cx = 8
        val cy = 12

        Scene.circle(buf, cx.toFloat(), cy.toFloat(), 5.0f, 40)

        // 正前方：一根固定朝上的针（屏幕上方就是手机顶边），不随航向变化。
        // 针头必须逐行加宽成三角：直接一行 5 格宽再配针杆，在这个尺寸上会读成"蘑菇"或"十字"
        Scene.put(buf, cx, 3, 235)
        Scene.hLine(buf, cx - 1, cx + 1, 4, 220)
        Scene.hLine(buf, cx - 2, cx + 2, 5, 205)
        Scene.vLine(buf, cx, 6, cy, 170)

        // 北：航向角是"手机顶边指向哪里"，屏幕上的北在反方向，所以取负角
        val (nx, ny) = northScreenDir(azimuthDeg)
        Scene.line(buf, cx + nx * 3.5f, cy + ny * 3.5f, cx + nx * 5.0f, cy + ny * 5.0f, markV)
        Scene.ellipse(buf, cx + nx * 5.0f, cy + ny * 5.0f, 1.0f, 1.0f, markV)
    }

    companion object {
        /**
         * 北在屏幕上的单位方向（x 向右、y 向下）。
         *
         * 航向角是"手机顶边指向哪里"，屏幕上的北在反方向，所以取负角。
         * 手机朝北（0°）北点在头顶，朝东（90°）北在左边，朝南（180°）北在脚下。
         */
        fun northScreenDir(azimuthDeg: Float): Pair<Float, Float> {
            val a = -Math.toRadians(azimuthDeg.toDouble()).toFloat()
            return sin(a) to -cos(a)
        }
    }

    /** 0° 为北，顺时针 45° 一档。 */
    private fun compassPoint(deg: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = ((((deg + 22.5f) % 360f + 360f) % 360f) / 45f).toInt().coerceIn(0, 7)
        return dirs[idx]
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        PixelText.drawCentered(buf, "045", 18, 1, 255)
        // 缩略图用同样是 NE 的航向来演示：北点在左上，针朝上
        drawDial(buf, 45f, 245)
    }

    override fun status(): String {
        if (!alive) return "等待磁力计"
        val deg = ((azimuthDeg.roundToInt() % 360) + 360) % 360
        return "航向 $deg° ${compassPoint(azimuthDeg)}"
    }
}
