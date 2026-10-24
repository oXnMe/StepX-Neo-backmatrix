package com.stepx.backmatrix.tools

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.GlyphArt
import com.stepx.backmatrix.core.PixelText
import com.stepx.backmatrix.core.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 节点心跳矩阵：每台机器一格，活着就慢慢呼吸，挂了整块爆闪。
 *
 * 探测用 TCP connect（端口可配，默认 443），比 InetAddress.isReachable 稳，
 * 也不需要 root 或原始套接字权限。
 */
class PingMatrixTool(private val context: Context) : ToolInstance {

    private class Node(val host: String, val port: Int, val period: Double, val phase: Double) {
        @Volatile var up = false
        @Volatile var known = false
        @Volatile var rtt = -1L
    }

    private val prefs = ToolPrefs(context)
    private val nodes: List<Node> = parse(prefs.str("ping", "hosts", DEFAULT_HOSTS))
    private val intervalMs: Long = prefs.int("ping", "interval", 5).coerceIn(2, 60) * 1000L
    private val timeoutMs: Int = prefs.int("ping", "timeout", 1200).coerceIn(300, 5000)

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** 每台机器呼吸周期错开，避免 10 个点同时亮成一片。 */
    private val cols: Int
    private val rows: Int

    init {
        val n = nodes.size.coerceAtLeast(1)
        val g = gridFor(n)
        cols = g.first
        rows = g.second
    }

    override fun start() {
        if (job != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch {
            while (isActive) {
                probeAll()
                delay(intervalMs)
            }
        }
    }

    private suspend fun probeAll() = coroutineScope {
        for (n in nodes) {
            launch { probe(n) }
        }
    }

    private fun probe(node: Node) {
        val t0 = SystemClock.elapsedRealtime()
        val ok = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(node.host, node.port), timeoutMs)
            }
        }.isSuccess
        node.rtt = if (ok) SystemClock.elapsedRealtime() - t0 else -1L
        node.up = ok
        node.known = true
    }

    override fun frameDelayMs(): Long = 90L

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        Scene.clear(buf)
        if (nodes.isEmpty()) {
            PixelText.drawCentered(buf, "?", 8, 3, 200)
            PixelText.drawCentered(buf, "SET", 19, 1, 90)
            return
        }
        val allDown = nodes.all { it.known && !it.up }
        if (allDown) {
            // 全挂了就别客气：满屏闪感叹号
            val on = (t * 5.0).toInt() % 2 == 0
            if (on) GlyphArt.bang(buf, 255) else GlyphArt.bang(buf, 60)
            return
        }

        val cw = Dot.W.toFloat() / cols
        val chh = Dot.H.toFloat() / rows
        nodes.forEachIndexed { i, node ->
            val cx = (i % cols) * cw + cw / 2f
            val cy = (i / cols) * chh + chh / 2f
            if (!node.known) {
                Scene.max(buf, cx.roundToInt(), cy.roundToInt(), 40)
                return@forEachIndexed
            }
            if (node.up) {
                // 心跳：中心常亮 + 一圈按周期向外扩散的光环
                val beat = 0.5 + 0.5 * sin(2 * PI * t / node.period + node.phase)
                val core = (110 + 145 * beat).toInt()
                Scene.ellipse(buf, cx, cy, 1.3f, 1.3f, core)
                val ringT = ((t + node.phase) % node.period) / node.period
                val r = 1.2 + ringT * 3.4
                val rv = (230 * (1 - ringT)).toInt()
                if (rv > 24) Scene.ring(buf, cx, cy, r.toFloat(), rv)
            } else {
                // 宕机：整块格子按 4Hz 爆闪
                val on = (t * 8.0).toInt() % 2 == 0
                val x0 = (i % cols) * cw + 0.6f
                val y0 = (i / cols) * chh + 0.6f
                Scene.rect(
                    buf,
                    x0.roundToInt(), y0.roundToInt(),
                    (x0 + cw - 1.2f).roundToInt(), (y0 + chh - 1.2f).roundToInt(),
                    if (on) 255 else 55, fill = true
                )
            }
        }
    }

    override fun still(buf: ByteArray) {
        // 缩略图：画一半在线一半掉线，比全亮更有信息量
        Scene.clear(buf)
        val cw = Dot.W.toFloat() / cols
        val chh = Dot.H.toFloat() / rows
        nodes.forEachIndexed { i, _ ->
            val cx = (i % cols) * cw + cw / 2f
            val cy = (i / cols) * chh + chh / 2f
            if (i % 3 == 2) {
                Scene.rect(
                    buf,
                    ((i % cols) * cw + 0.6f).roundToInt(), ((i / cols) * chh + 0.6f).roundToInt(),
                    ((i % cols) * cw + cw - 1.2f).roundToInt(), ((i / cols) * chh + chh - 1.2f).roundToInt(),
                    210, fill = true
                )
            } else {
                Scene.ellipse(buf, cx, cy, 1.2f, 1.2f, 220)
                Scene.ring(buf, cx, cy, 2.4f, 90)
            }
        }
    }

    override fun status(): String {
        val up = nodes.count { it.known && it.up }
        val fastest = nodes.filter { it.known && it.up && it.rtt >= 0 }.minOfOrNull { it.rtt } ?: -1
        return if (nodes.isEmpty()) "还没有监控目标" else "$up/${nodes.size} 在线 · 最快 ${fastest}ms"
    }

    override fun stop() {
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
    }

    companion object {
        const val DEFAULT_HOSTS = "www.baidu.com:443\nwww.qq.com:443"

        /** 主机数量 → 网格。格子越少单格越大；面板偏高，宁可少几行也不要空一行。 */
        fun gridFor(n: Int): Pair<Int, Int> = when {
            n <= 1 -> 1 to 1
            n <= 3 -> n to 1
            n <= 4 -> 2 to 2
            n <= 6 -> 3 to 2
            n <= 9 -> 3 to 3
            n <= 12 -> 4 to 3
            else -> 4 to 4
        }

        private fun parse(raw: String): List<Node> = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(16)
            .mapIndexed { i, line ->
                val host = line.substringBeforeLast(':', line)
                val port = line.substringAfterLast(':', "443").toIntOrNull() ?: 443
                Node(
                    host = host.trim(),
                    port = port,
                    period = 1.6 + (i % 5) * 0.28,
                    phase = i * 1.1
                )
            }.toList()
    }
}

/**
 * 流量水流计：下载往下淌，上传往上顶。
 * 数据来自 TrafficStats 的网卡累计计数，差值除以时间就是瞬时速率。
 */
class FlowMeterTool(private val context: Context) : ToolInstance {

    private val prefs = ToolPrefs(context)
    private val scaleMax: Float = prefs.float("flow", "scale", 4f).coerceIn(1f, 8f)

    private var lastAt = 0L
    private var lastRx = -1L
    private var lastTx = -1L
    private var rxBps = 0.0
    private var txBps = 0.0

    private val downHead = FloatArray(Dot.W) { -1f }
    private val upHead = FloatArray(Dot.W) { -1f }
    private val rnd = Random(System.nanoTime())

    private fun sample(now: Long) {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx < 0 || tx < 0) return
        if (lastAt == 0L) {
            lastAt = now; lastRx = rx; lastTx = tx
            return
        }
        val dtMs = now - lastAt
        if (dtMs < 120) return
        val dr = (rx - lastRx).coerceAtLeast(0)
        val dt = (tx - lastTx).coerceAtLeast(0)
        // 指数平滑，避免瞬时抖动让雨忽大忽小
        rxBps = if (rxBps == 0.0) dr * 1000.0 / dtMs else rxBps * 0.6 + dr * 1000.0 / dtMs * 0.4
        txBps = if (txBps == 0.0) dt * 1000.0 / dtMs else txBps * 0.6 + dt * 1000.0 / dtMs * 0.4
        lastAt = now; lastRx = rx; lastTx = tx
    }

    /** 1 KB/s 以下几乎不动，1 MB/s 已经下暴雨；对数刻度才不会要么不动要么糊屏。 */
    private fun level(bytesPerSec: Double): Float = levelFor(bytesPerSec, scaleMax)

    companion object {
        /** 低于这个速率就当没流量。手机待机时的背景流量大概几百 B/s，取 64 才不至于一片死寂。 */
        private const val FLOOR = 64.0

        fun levelFor(bytesPerSec: Double, scale: Float): Float {
            if (bytesPerSec <= FLOOR) return 0f
            val v = (log10(bytesPerSec / FLOOR) / (scale - 0.5)).toFloat()
            return v.coerceIn(0f, 1f)
        }

        /** 当前量程下"满格"对应的字节速率，给界面显示用。 */
        fun fullScaleBytes(scale: Float): Double = FLOOR * Math.pow(10.0, (scale - 0.5).toDouble())
    }

    override fun frameDelayMs(): Long = 60L

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        sample(SystemClock.elapsedRealtime())
        Scene.clear(buf)
        val dl = level(rxBps)
        val ul = level(txBps)

        // 速率只靠雨本身表达：落得越密、越快、尾巴越长。
        // 不画基准线——17 点上会变成一条横线，反而看不清水流。

        // 下落雨：列之间独立，快的列一条接一条
        val spawnDown = 0.02f + 0.50f * dl
        val fallDown = 0.9f + 7.0f * dl
        val tailDown = 2 + (4 * dl).roundToInt()
        val headDown = (130 + 125 * dl).toInt()
        for (c in 0 until Dot.W) {
            if (downHead[c] < 0f) {
                // 起点必须是 0（屏幕顶端），不能是负数：
                // 判空用的就是 < 0，写成 -0.5 会让这一列永远卡在"待发射"，
                // 既不前进也不画，下载方向的雨等于从来没有
                if (rnd.nextFloat() < spawnDown) downHead[c] = 0f
            } else {
                downHead[c] += fallDown
                if (downHead[c] > Dot.H + 1) downHead[c] = -1f
            }
            val h = downHead[c]
            if (h >= 0f) drawDrop(buf, c, h.toInt(), 1, tailDown, headDown)
        }

        // 上行逆流
        val spawnUp = 0.015f + 0.42f * ul
        val riseUp = 0.8f + 6.0f * ul
        val tailUp = 2 + (3 * ul).roundToInt()
        val headUp = (120 + 130 * ul).toInt()
        for (c in 0 until Dot.W) {
            if (upHead[c] < 0f) {
                if (rnd.nextFloat() < spawnUp) upHead[c] = (Dot.H - 0.5f)
            } else {
                upHead[c] -= riseUp
                if (upHead[c] < -1) upHead[c] = -1f
            }
            val h = upHead[c]
            if (h >= 0f) drawDrop(buf, c, h.toInt(), -1, tailUp, headUp)
        }

        if (dl < 0.02f && ul < 0.02f) {
            // 真的一点流量都没有：中间一个慢慢呼吸的点，表示还活着。
            // 这里绝不能画"一排暗点"——那就是用户说的"只显示一条横线"
            val p = 0.5 + 0.5 * sin(t * 1.8)
            Scene.max(buf, 8, 11, (40 + 120 * p).toInt())
        }
    }

    /** 往运动反方向拖一条渐隐尾巴，看起来才有速度。 */
    private fun drawDrop(buf: ByteArray, x: Int, y: Int, dir: Int, tail: Int, head: Int) {
        for (k in 0 until tail) {
            val yy = y - dir * k
            val v = (head * (1f - 0.78f * k / tail)).toInt()
            if (v > 18) Scene.add(buf, x, yy, v)
        }
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        val rnd = Random(7)
        for (c in 0 until Dot.W) {
            if (rnd.nextFloat() < 0.55f) {
                val y = rnd.nextInt(2, Dot.H - 2)
                for (k in 0 until 4) Scene.add(buf, c, y - k, (220 - k * 45).coerceAtLeast(30))
            }
        }
    }

    override fun status(): String =
        "↓ ${fmt(rxBps)}   ↑ ${fmt(txBps)}"

    private fun fmt(bps: Double): String = when {
        bps < 1024 -> "${bps.toInt()} B/s"
        bps < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB/s", bps / 1024)
        else -> String.format(java.util.Locale.US, "%.1f MB/s", bps / 1024 / 1024)
    }
}
