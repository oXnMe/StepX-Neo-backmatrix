package com.stepx.backmatrix.tools

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.PixelText
import com.stepx.backmatrix.core.Scene
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 敲击计数：敲一下手机背面加一。
 *
 * 判据是**高频冲击**：一阶低通估计出重力慢分量，原始值减去它，剩下的尖峰超过阈值才算。
 * 拿起手机是几百毫秒的位移，滤完几乎不剩什么；敲击是几毫秒的尖峰，频谱在几十上百 Hz。
 *
 * 把手机放到桌上磕的那几下是真实冲击，频谱和敲击无法区分，只能用「手机在不在动」兜底：
 *
 * 1. 转动要持续 [MOVE_HOLD_S] 才算动过——敲击只让机身抖几十毫秒，不会把自己踢进冷却；
 * 2. 动过之后要静置 [SETTLE_MS] 才恢复计数，放下手机的全过程都落在窗口里；
 * 3. 冷却期内再挨一下，窗口继续顺延 [HIT_EXTEND_MS]。
 *
 * 采样率必须用 FASTEST：冲击只有几毫秒，GAME（约 50Hz）会跨过去采不到峰值。
 * 木鱼动画可以关掉，关掉后只留数字。
 */
class KnockTool(private val context: Context) : ToolInstance, SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs = ToolPrefs(context)

    /** 越大越灵敏（阈值取倒数）。 */
    private val sensitivity: Float = prefs.float("knock", "sens", 1.0f).coerceIn(0.4f, 3f)
    private val showFish: Boolean = prefs.bool("knock", "fish", true)

    /** 低通估计出来的慢分量（重力 + 缓慢移动），三轴各一个。 */
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0

    /** 第一帧直接当初始慢分量，否则会从 0 跳到 9.8，刚启动就白记一下。 */
    private var primed = false
    private var lastSampleAt = 0L

    /** 最后一次"手机在动"的时刻（陀螺仪测到的持续转动）。 */
    @Volatile private var lastMoveAt = 0L

    /** 陀螺仪上一次采样的时刻，用来算角速度积分。 */
    private var lastGyroAt = 0L

    /** 无单位的转动积分（秒）。超过 [MOVE_HOLD_S] 才算手机真的在动。 */
    private var moveScore = 0.0

    private var count = 0
    private var lastKnockAt = 0L

    /**
     * 上膛状态。冲击一出现就卸膛，等幅值回落到阈值以下才重新上膛，
     * 保证一次冲击只算一下（只看时间间隔会一次记成好几下）。
     */
    private var armed = true

    /** 实时冲击幅值（m/s²），详情页拿它当调灵敏度的参照。 */
    @Volatile private var liveShock = 0.0

    /** 传感器没打开时的原因。非空就别再假装在计数了。 */
    @Volatile private var sensorError: String? = null

    /** 最近一次被计数的冲击峰值，保持几秒方便看。 */
    @Volatile private var lastPeak = 0.0
    @Volatile private var lastPeakAt = 0L

    /**
     * 只用来看"手机在不在动"的陀螺仪。
     *
     * 拿起、翻转、放下都是持续几百毫秒的转动；敲击只有几十毫秒，攒不够 [MOVE_HOLD_S]。
     * 冲击期间不累计积分，避免一次重敲把自己推进冷却。
     */
    private val gyroWatcher = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            val w = sqrt(
                (e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()
            )
            val dt = if (lastGyroAt == 0L) 0.0 else ((now - lastGyroAt) / 1000.0).coerceIn(0.0, 0.25)
            lastGyroAt = now
            if (liveShock >= threshold(sensitivity)) return

            moveScore = moveScoreNext(moveScore, dt, w)
            if (moveScore >= MOVE_HOLD_S) lastMoveAt = now
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun start() {
        val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s == null) {
            sensorError = "这台设备没有加速度计"
            return
        }
        // 启动时刻当成"刚动过"：冷却一秒多，用户这时通常还在手里端着找地方放
        lastMoveAt = SystemClock.elapsedRealtime()
        moveScore = 0.0
        lastGyroAt = 0L
        // FASTEST 是必须的：冲击只有几毫秒，GAME（50Hz）经常整段跨过去采不到峰值。
        // 但它要求 HIGH_SAMPLING_RATE_SENSORS 这个普通权限（API 31 起），没有就抛
        // SecurityException——那样工具看着在跑，其实一个数据都收不到，怎么敲都不加一。
        // 所以逐档降级试一遍，全都失败就明说，不要留一个"运行中"的空壳。
        for (rate in intArrayOf(SensorManager.SENSOR_DELAY_FASTEST, SensorManager.SENSOR_DELAY_GAME)) {
            val ok = runCatching { sm.registerListener(this, s, rate) }.getOrDefault(false)
            if (ok) {
                sensorError = null
                // 陀螺仪只是辅助判断"手机在动"，没有它照样能敲，所以失败不管
                sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { g ->
                    runCatching { sm.registerListener(gyroWatcher, g, SensorManager.SENSOR_DELAY_GAME) }
                }
                return
            }
        }
        sensorError = "加速度计打不开：系统拒绝了传感器注册"
    }

    override fun stop() {
        sm.unregisterListener(this)
        sm.unregisterListener(gyroWatcher)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val ax = e.values[0].toDouble()
        val ay = e.values[1].toDouble()
        val az = e.values[2].toDouble()
        val now = SystemClock.elapsedRealtime()

        if (!primed) {
            gx = ax; gy = ay; gz = az
            primed = true
            lastSampleAt = now
            return
        }

        val dt = ((now - lastSampleAt) / 1000.0).coerceIn(0.001, 0.5)
        lastSampleAt = now

        // 这一帧之前的基线有多接近 1g。要用"更新之前"的值：
        // 冲击会把基线本身拉偏，拿拉偏后的值判断，等于自己把自己挡在门外。
        val baseBefore = sqrt(gx * gx + gy * gy + gz * gz)

        // 时间归一化的低通：采样率变了时间常数也不变（固定系数在 FASTEST 下会把基线追得太快）
        val a = gravityAlpha(dt)
        gx = a * gx + (1 - a) * ax
        gy = a * gy + (1 - a) * ay
        gz = a * gz + (1 - a) * az

        val shock = shockOf(ax, ay, az, gx, gy, gz)
        liveShock = shock
        val th = threshold(sensitivity)

        if (armed) {
            if (shock > th) {
                // 冷却期里还在磕：这一下不算，而且把冷却再往后顺延一点。
                // 落桌是一串（弹一下又一下），140ms 的不应期只能把一记敲击算成一下，
                // 拆不开这一串，只能让整串一直待在冷却里。
                if (now - lastMoveAt < SETTLE_MS) lastMoveAt = settleAfterHit(now, lastMoveAt)
                // 刚启动（启动时刻当作刚动过）、手机还在动、或者基线根本不在 1g 附近
                // （自由落体、被持续加速）——都不算
                if (settledAt(now, lastMoveAt, baseBefore) && now - lastKnockAt >= MIN_GAP_MS) {
                    lastKnockAt = now
                    lastPeak = shock
                    lastPeakAt = now
                    count += 1
                }
                // 冲着呢，先卸膛；哪怕还在不应期里，也别让同一次冲击过会儿再算一遍
                armed = false
            }
        } else if (shock < th * REARM_RATIO) {
            armed = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun frameDelayMs(): Long = 50L

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        Scene.clear(buf)

        // 敲完 0.45 秒内的余韵：1 表示刚敲下去，0 表示已经静止。
        // 这里必须用同一个时钟（elapsedRealtime），render 的 t 是"工具启动至今"，两者不能混
        val age = (SystemClock.elapsedRealtime() - lastKnockAt) / 1000.0
        val hit = (1.0 - age / 0.45).coerceIn(0.0, 1.0).toFloat()

        if (showFish) {
            drawFish(buf, hit)
            drawCount(buf, COUNT_Y_WITH_FISH)
        } else {
            drawCount(buf, COUNT_Y_ALONE)
        }
    }

    /**
     * 木鱼：占满下半屏的圆鼓，顶部窄、左边一道斜边到底、左下伸出一截柄。
     *
     * 圆内上亮下暗做出体积，中间一道暗缝当鱼嘴；敲下去时两侧各亮一颗星，0.45 秒内淡出。
     */
    private fun drawFish(buf: ByteArray, hit: Float) {
        val glow = (60 * hit).toInt()
        BODY.forEachIndexed { i, span ->
            val y = BODY_TOP + i
            val v = (238 - i * 6 + glow).coerceIn(150, 255)
            for (x in span[0]..span[1]) Scene.max(buf, x, y, v)
        }
        // 鱼嘴：中上偏右的一道暗缝。用 put 覆盖，加亮的时候也不会漏光
        for (i in SLIT.indices step 2) Scene.put(buf, SLIT[i], SLIT[i + 1], 70)

        if (hit > 0.03f) {
            val v = (255 * hit).toInt().coerceIn(0, 255)
            sparkle(buf, STAR_LEFT, STAR_Y, v)
            sparkle(buf, STAR_RIGHT, STAR_Y, v)
        }
    }

    /** 一颗四角星：中心一点加上下左右各一点。 */
    private fun sparkle(buf: ByteArray, x: Int, y: Int, v: Int) {
        Scene.max(buf, x, y, v)
        Scene.max(buf, x - 1, y, v)
        Scene.max(buf, x + 1, y, v)
        Scene.max(buf, x, y - 1, v)
        Scene.max(buf, x, y + 1, v)
    }

    private fun drawCount(buf: ByteArray, y: Int) {
        val text = count.toString()
        // 三位以上就缩小一号，四位以上只显示后三位（木鱼敲到一千下已经够本了）
        val shown = if (text.length > 3) text.takeLast(3) else text
        val scale = if (shown.length <= 2) 2 else 1
        // 小号字只有 5 行，往上提一点才是视觉居中（大字从第 0 行起笔刚好贴顶）
        PixelText.drawCentered(buf, shown, if (scale == 2) y else y + 3, scale, 255)
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        if (showFish) {
            drawFish(buf, 0.35f)
            drawCount(buf, COUNT_Y_WITH_FISH)
        } else {
            drawCount(buf, COUNT_Y_ALONE)
        }
    }

    override fun status(): String {
        sensorError?.let { return it }
        val th = threshold(sensitivity) / SENSOR_G
        // 刚敲过就把峰值亮出来，方便对着数字调灵敏度
        val peakAge = SystemClock.elapsedRealtime() - lastPeakAt
        val show = if (peakAge < 3000) lastPeak else liveShock
        val now = String.format(Locale.US, "%.2fg", show / SENSOR_G)
        // 门槛本来就小，一位小数会显示成"0.1g"，两位才看得出灵敏度调了几档
        val gate = String.format(Locale.US, if (th < 0.3) "%.2fg" else "%.1fg", th)
        val clock = SystemClock.elapsedRealtime()
        // 不计数的时候要说清楚为什么、还要等多久，否则用户只会觉得"坏了"
        val cooling = SETTLE_MS - (clock - lastMoveAt)
        val head = when {
            cooling > 0 -> String.format(Locale.US, "放稳 %.1fs 后开始", cooling / 1000.0)
            count == 0 -> "敲一下手机背面"
            else -> "已敲 $count 下"
        }
        return "$head · 当前 $now / 门槛 $gate"
    }

    /** 详情页的「清零」按钮走这里。 */
    override fun reset() {
        count = 0
        armed = true
    }

    companion object {
        const val SENSOR_G = 9.81

        /**
         * 慢分量的时间常数，对应截止频率约 8 Hz。
         *
         * 要滤的是拿起手机这类 1~2 Hz 的位移；取 0.3 秒那种重力估计值会把它们放过去，
         * 拿起手机照样被判成敲击。
         */
        const val GRAVITY_TAU_S = 0.02

        /**
         * 默认灵敏度下的判定阈值，单位 m/s²（约 0.14g 的冲击）。
         *
         * 轻敲的峰值本来就只有几个 m/s²，门槛高了会变成「必须使劲砸」。
         * 误触由时间闸负责，不靠调高门槛。
         */
        const val BASE_SHOCK = 1.4

        /** 两次敲击之间至少隔这么久，否则一次敲击会被采样成好几下。 */
        const val MIN_GAP_MS = 140L

        /** 冲击回落到阈值的这个比例以内，才重新上膛。 */
        const val REARM_RATIO = 0.35

        /**
         * 测到转动之后要静止这么久才恢复计数。
         *
         * 必须长于「把手机放下的全过程」：放下本身在转，落定后还会磕两三下，
         * 这串冲击靠 [MIN_GAP_MS] 拆不开，只能整体挡在冷却里。
         */
        const val SETTLE_MS = 1200L

        /** 冷却期里又磕了一下：冷却至少再续这么久，免得慢慢放下、磕了半秒的漏过去。 */
        const val HIT_EXTEND_MS = 500L

        /** 超过这个角速度（rad/s，约 10°/s）就算"手机在转"。 */
        const val MOVE_RAD_S = 0.18

        /**
         * 转动要累计这么久（秒，带衰减）才算手机真的在动。
         *
         * 用瞬时角速度判断会把每一次敲击都当成「拿起」，那样每敲一下都要等一秒多。
         */
        const val MOVE_HOLD_S = 0.15

        /**
         * 基线离 1g 超过这么多就认为手机不在静止状态（自由落体、被持续加速）。
         *
         * 这种输入回落的边沿和敲击长得一样，靠这一条挡掉；真实敲击的基线一直在 1g 附近。
         */
        const val BASE_TOLERANCE = 3.0

        /** 有木鱼时数字从第 0 行起笔：scale=2 高 10 行，正好占住上半屏，木鱼从第 11 行开始。 */
        internal const val COUNT_Y_WITH_FISH = 0

        /** 只当计数器时数字居中。 */
        private const val COUNT_Y_ALONE = 7

        /** 木鱼鼓身从第 11 行开始，一直画到面板底边被切掉为止。 */
        internal const val BODY_TOP = 11

        /**
         * 鼓身每行的左右端点（闭区间），行号 = [BODY_TOP] + 下标。
         * 照实物轮廓描的，改动前先按 17×23 画一遍。
         */
        internal val BODY = arrayOf(
            intArrayOf(9, 12),
            intArrayOf(7, 13),
            intArrayOf(5, 14),
            intArrayOf(4, 15),
            intArrayOf(3, 15),
            intArrayOf(3, 15),
            intArrayOf(2, 15),
            intArrayOf(2, 15),
            intArrayOf(1, 15),
            intArrayOf(0, 15),
            intArrayOf(1, 14),
            intArrayOf(2, 13)
        )

        /** 鱼嘴那道暗缝的坐标，x,y 成对排列。 */
        internal val SLIT = intArrayOf(10, 16, 11, 16, 12, 17)

        /** 敲击时两侧星光的中心：贴着鼓身上沿的两边，不会压到数字。 */
        private const val STAR_LEFT = 0
        private const val STAR_RIGHT = 16
        private const val STAR_Y = 13

        /** 低通系数：dt 秒后旧值还剩多少。纯函数，方便单测。 */
        fun gravityAlpha(dtSec: Double): Double = exp(-dtSec.coerceIn(0.0, 1.0) / GRAVITY_TAU_S)

        /** 去掉慢分量之后剩下的冲击幅值。 */
        fun shockOf(ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double): Double {
            val dx = ax - gx
            val dy = ay - gy
            val dz = az - gz
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        /** 判定阈值（m/s²）。灵敏度越高阈值越低。 */
        fun threshold(sensitivity: Float): Double = BASE_SHOCK / sensitivity.coerceIn(0.4f, 3f)

        /**
         * 现在允许计数吗。纯函数，方便单测。
         *
         * [now] 当前时刻、[lastMoveAt] 最后一次测到持续转动的时刻，都是 elapsedRealtime；
         * [baselineG] 是这一帧之前的基线模长（静止时约 9.81）。
         */
        fun settledAt(now: Long, lastMoveAt: Long, baselineG: Double): Boolean =
            now - lastMoveAt >= SETTLE_MS &&
                abs(baselineG - SENSOR_G) < BASE_TOLERANCE

        /**
         * 冷却期里又挨了一下，冷却该推到哪儿。纯函数，方便单测。
         *
         * 只往后推、不往前拉：刚放下的头几百毫秒本来还剩很久，别被这一下缩短了。
         */
        fun settleAfterHit(now: Long, lastMoveAt: Long): Long =
            maxOf(lastMoveAt, now - SETTLE_MS + HIT_EXTEND_MS)

        /**
         * 转动积分的下一步。纯函数，方便单测。
         *
         * 超阈值的角速度按 dt 加分，没超按两倍 dt 扣分（转完要很快回落，
         * 不然缓过来之后还会被当成"还在动"）。上限 1，够触发 [MOVE_HOLD_S] 就行。
         */
        fun moveScoreNext(prev: Double, dtSec: Double, gyroRad: Double): Double {
            val step = if (gyroRad > MOVE_RAD_S) dtSec else -dtSec * 2
            return (prev + step).coerceIn(0.0, 1.0)
        }
    }
}
