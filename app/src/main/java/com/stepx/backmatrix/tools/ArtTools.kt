package com.stepx.backmatrix.tools

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.PixelText
import com.stepx.backmatrix.core.Scene
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 落沙沙盒：元胞自动机，重力来自加速度计。
 *
 * 每次 tick 里沙粒先沿重力方向掉，掉不动就试着往两侧斜滑，
 * 这样堆起来才会形成自然的沙丘坡度而不是垂直的柱子。
 */
class SandTool(private val context: Context) : ToolInstance, SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs = ToolPrefs(context)
    private val rate: Int = prefs.int("sand", "rate", 1).coerceIn(1, 8)

    /** 0 表示空，其余是这颗沙子的亮度，用来做颗粒质感。 */
    private val grid = ByteArray(Dot.PIXELS)
    private val rnd = Random(System.nanoTime())

    @Volatile private var gx = 0f
    @Volatile private var gy = 0f

    private var acc = 0f
    private var dirX = 0
    private var dirY = 1

    override fun start() {
        val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        gx = gx * 0.8f + e.values[0] * 0.2f
        gy = gy * 0.8f + e.values[1] * 0.2f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun frameDelayMs(): Long = 55L

    /**
     * 重力方向 = 加速度计读数的符号，两轴都不取反。
     *
     * 与「比力方向与重力相反」的推导结论相反，是因为背屏面板的 x 轴朝向与主板坐标系相反。
     * 以真机实测为准，改这里之前先跑 [SandToolTest]。
     */
    private fun updateGravity() {
        val (dx, dy) = gravityDir(gx, gy)
        dirX = dx
        dirY = dy
    }

    companion object {
        /** 纯函数，方便单测：返回沙子的流动方向 (dirX, dirY)，只会是四个正交方向之一。 */
        fun gravityDir(ax: Float, ay: Float): Pair<Int, Int> =
            if (abs(ax) > abs(ay)) {
                (if (ax > 0) 1 else -1) to 0
            } else {
                0 to (if (ay >= 0) 1 else -1)
            }
    }

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        updateGravity()
        // 按固定节奏推进，和渲染帧率解耦，掉帧时沙子不会突然变慢
        acc += dt.toFloat()
        var steps = 0
        while (acc >= 0.05f && steps < 3) {
            acc -= 0.05f
            spawn()
            step()
            steps++
        }
        draw(buf)
    }

    private fun spawn() {
        repeat(rate) {
            val x: Int
            val y: Int
            when {
                dirX == 0 && dirY > 0 -> { x = rnd.nextInt(Dot.W); y = 0 }
                dirX == 0 && dirY < 0 -> { x = rnd.nextInt(Dot.W); y = Dot.H - 1 }
                dirY == 0 && dirX > 0 -> { x = 0; y = rnd.nextInt(Dot.H) }
                else -> { x = Dot.W - 1; y = rnd.nextInt(Dot.H) }
            }
            val i = y * Dot.W + x
            if (grid[i] == 0.toByte()) {
                grid[i] = (120 + rnd.nextInt(120)).toByte()
            }
        }
    }

    private fun step() {
        // 逆着重力方向遍历，避免一颗沙子在同一 tick 里被推两次
        val xs = if (dirX > 0) (Dot.W - 1 downTo 0) else (0 until Dot.W)
        val ys = if (dirY > 0) (Dot.H - 1 downTo 0) else (0 until Dot.H)
        for (y in ys) {
            for (x in xs) {
                val i = y * Dot.W + x
                val v = grid[i]
                if (v == 0.toByte()) continue
                val nx = x + dirX
                val ny = y + dirY
                if (nx in 0 until Dot.W && ny in 0 until Dot.H && grid[ny * Dot.W + nx] == 0.toByte()) {
                    grid[ny * Dot.W + nx] = v
                    grid[i] = 0
                    continue
                }
                // 正下方被占：试着沿垂直方向斜滑
                if (dirY != 0) {
                    val side = if (rnd.nextBoolean()) 1 else -1
                    if (trySlide(x, y, side, 0, v) || trySlide(x, y, -side, 0, v)) continue
                } else {
                    val side = if (rnd.nextBoolean()) 1 else -1
                    if (trySlide(x, y, 0, side, v) || trySlide(x, y, 0, -side, v)) continue
                }
            }
        }
        // 出口：贴着"底面"的沙子慢慢漏掉，免得整屏填满变成一块白板
        val edgeY = if (dirY > 0) Dot.H - 1 else 0
        val edgeX = if (dirX > 0) Dot.W - 1 else 0
        if (dirY != 0) {
            for (x in 0 until Dot.W) {
                val i = edgeY * Dot.W + x
                if (grid[i] != 0.toByte() && rnd.nextFloat() < 0.035f) grid[i] = 0
            }
        } else {
            for (y in 0 until Dot.H) {
                val i = y * Dot.W + edgeX
                if (grid[i] != 0.toByte() && rnd.nextFloat() < 0.035f) grid[i] = 0
            }
        }
    }

    private fun trySlide(x: Int, y: Int, sx: Int, sy: Int, v: Byte): Boolean {
        val tx = x + dirX + sx
        val ty = y + dirY + sy
        if (tx !in 0 until Dot.W || ty !in 0 until Dot.H) return false
        val ti = ty * Dot.W + tx
        if (grid[ti] != 0.toByte()) return false
        grid[ti] = v
        grid[y * Dot.W + x] = 0
        return true
    }

    private fun draw(buf: ByteArray) {
        Scene.clear(buf)
        for (i in 0 until Dot.PIXELS) {
            val v = grid[i].toInt() and 0xFF
            if (v > 0) buf[i] = v.toByte()
        }
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        // 一堆静止的沙丘剖面
        val heights = intArrayOf(3, 5, 8, 11, 13, 14, 13, 11, 8, 6, 7, 9, 12, 14, 13, 10, 7)
        for (x in 0 until Dot.W) {
            for (k in 0 until heights[x]) {
                val y = Dot.H - 1 - k
                val shade = (110 + ((x * 37 + k * 91) % 130))
                Scene.max(buf, x, y, shade)
            }
        }
    }

    override fun status(): String =
        "重力方向 " + when {
            dirX == 0 && dirY > 0 -> "下"
            dirX == 0 && dirY < 0 -> "上"
            dirX > 0 -> "右"
            else -> "左"
        }
}

/**
 * 蓝牙雷达：扫线转圈，扫到设备就在对应方位冒一圈涟漪。
 *
 * 手机没有测向天线（没有 AoA），所以方位是用设备地址散列出来的固定值——
 * 它能让你分辨"附近有几个、信号强弱"，但别拿它当真正的方向。
 */
class BleRadarTool(private val context: Context) : ToolInstance {

    private class Device(val address: String, val name: String, val bearing: Float) {
        @Volatile var rssi = -100
        @Volatile var lastSeen = 0L
    }

    private val bm = context.getSystemService(BluetoothManager::class.java)
    private val devices = HashMap<String, Device>()
    private val rnd = Random(1)

    @Volatile private var scanning = false
    @Volatile private var error: String? = null
    @Volatile private var scanCount = 0

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device.address ?: return
            val d = devices.getOrPut(addr) {
                Device(
                    address = addr,
                    name = runCatching { result.device.name }.getOrNull() ?: "",
                    // 方位由地址决定：同一台设备每次都在同一个方向，涟漪不会乱跳
                    bearing = ((addr.hashCode() and 0x7FFFFFFF) % 360).toFloat()
                )
            }
            d.rssi = result.rssi
            d.lastSeen = SystemClock.elapsedRealtime()
            scanCount++
        }

        override fun onScanFailed(errorCode: Int) {
            error = "扫描失败 $errorCode"
            scanning = false
        }
    }

    private fun hasPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    override fun start() {
        if (!hasPermission()) {
            error = "没有蓝牙扫描权限"
            return
        }
        val scanner = bm?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            error = "蓝牙未开启"
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        runCatching { scanner.startScan(null, settings, callback) }
            .onFailure { error = it.message ?: "无法开始扫描" }
        scanning = true
    }

    override fun stop() {
        runCatching { bm?.adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning = false
        devices.clear()
    }

    override fun frameDelayMs(): Long = 50L

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        Scene.clear(buf)
        val cx = 8f
        val cy = 11.5f

        // 底圈
        Scene.ring(buf, cx, cy, 9.2f, 42)
        Scene.ring(buf, cx, cy, 5.6f, 26)
        Scene.max(buf, 8, 11, 60)

        // 扫线 + 拖尾
        val sweep = (t * 1.5) % (2 * PI)
        for (k in 0 until 6) {
            val a = sweep - k * 0.12
            val v = (200 * (1 - k / 6f)).toInt()
            Scene.line(
                buf, cx, cy,
                cx + sin(a).toFloat() * 9.2f,
                cy - cos(a).toFloat() * 9.2f, v
            )
        }

        val now = SystemClock.elapsedRealtime()
        devices.values.forEach { d ->
            val age = now - d.lastSeen
            if (age > 8000) return@forEach
            // 信号越强越靠近中心
            val near = ((d.rssi + 95f) / 60f).coerceIn(0f, 1f)
            val r0 = 8.6f - near * 6.4f
            val pulse = ((now % 900) / 900f)
            val r = r0 + pulse * (2.2f + near * 2f)
            val v = (230 * (1 - pulse)).toInt()
            val rad = Math.toRadians(d.bearing.toDouble()).toFloat()
            val x = cx + sin(rad) * r
            val y = cy - cos(rad) * r
            if (v > 30) Scene.ellipse(buf, x, y, 0.9f, 0.9f, v)
            val bx = cx + sin(rad) * r0
            val by = cy - cos(rad) * r0
            if (age < 2500) Scene.max(buf, bx.roundToInt(), by.roundToInt(), (150 * (1 - age / 2500f)).toInt())
        }

        if (!scanning) {
            PixelText.drawCentered(buf, if (error != null) "NO BT" else "SCAN", 19, 1, 110)
        }
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        val cx = 8f
        val cy = 11.5f
        Scene.ring(buf, cx, cy, 9.2f, 50)
        Scene.ring(buf, cx, cy, 5.6f, 30)
        for (k in 0 until 5) {
            val a = 1.1 - k * 0.14
            Scene.line(buf, cx, cy, cx + sin(a).toFloat() * 9.2f, cy - cos(a).toFloat() * 9.2f, 200 - k * 34)
        }
        for ((bearing, r) in listOf(0.6f to 6.5f, 2.4f to 5.0f, 4.1f to 7.5f)) {
            val rad = Math.toRadians(bearing.toDouble()).toFloat()
            Scene.ellipse(buf, cx + sin(rad) * r, cy - cos(rad) * r, 0.9f, 0.9f, 210)
        }
    }

    override fun status(): String = when {
        error != null -> error!!
        !scanning -> "未在扫描"
        else -> {
            val alive = devices.values.count { SystemClock.elapsedRealtime() - it.lastSeen < 8000 }
            "附近 $alive 台 · 累计 $scanCount 次广播"
        }
    }
}
