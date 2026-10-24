package com.stepx.backmatrix.tools

import android.content.Context
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.PixelText
import com.stepx.backmatrix.core.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * 一行指标：左边一个字母当标签，右边三格以内的数字，底下一条进度条。
 *
 * 17 点是横着放不下中文的，所以每个指标只用一个大写字母代表，
 * 完整的名字交给详情页的 status()（那行有空间写全）。
 */
internal data class Band(val label: Char, val text: String, val pct: Float, val warn: Boolean = false)

/**
 * 可选指标的一条。面板靠它画勾选列表，工具靠 id 决定怎么取值。
 *
 * [label] 是画在背屏上的那个字母——17 点宽塞不下中文，只能一个字母。
 */
data class MetricOption(val id: String, val label: Char, val name: String)

/** 遥测类工具（路由器 / 主机状态）共用的小东西：HTTP、数字格式、指标条排版。 */
internal object Feed {

    /** 一遍能画几个指标。三个是 17×23 上还能看清数字的上限，再多只能并成一条线。 */
    const val MAX_METRICS = 3

    fun normalizeBase(raw: String): String {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return ""
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
    }

    /** 只认成功状态码；失败返回 null，由调用方决定怎么显示（不能假装还活着）。 */
    fun getText(url: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "pz-backmatrix")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** "8.1 %" / "360 MHz" / "1.2 GB" 这类格式化好的字符串，取开头那个数。 */
    fun firstNumber(s: String): Double? {
        val m = Regex("-?\\d+(?:\\.\\d+)?").find(s) ?: return null
        return m.value.toDoubleOrNull()
    }

    /**
     * 三格以内的数字：够宽就带一位小数，超过三位就只能取整。
     *
     * 面板只有 17 点宽，`[字母][数字]` 加起来最多 14 点，所以数字必须是 1~3 个字符。
     */
    fun compact(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "--"
        val a = abs(v)
        val s = when {
            a >= 1000 -> "999"
            a >= 100 -> v.roundToInt().toString()
            a >= 10 -> v.roundToInt().toString()
            a >= 0.05 -> String.format(Locale.US, "%.1f", v)
            else -> "0"
        }
        return if (s.length > 3) v.roundToInt().toString() else s
    }

    /** 百分比：能塞下百分号就塞，塞不下就只留数字。 */
    fun percent(p: Double): String {
        val s = compact(p)
        return if (s.length <= 2) "$s%" else s
    }

    /**
     * 画一组指标。区间 [top, bottom) 平均分给每个指标，条越少单条越高。
     *
     * 数字始终用 scale=1（5 行高），多出来的高度给进度条加粗——
     * 放大字号会让数字跑到 22 点宽，17 点的面板装不下。
     * 只有 5 行时（刚好只够一个字高）就不画条了：条会跟字的最后一行叠在一起。
     */
    fun drawBands(buf: ByteArray, bands: List<Band>, top: Int, bottom: Int) {
        if (bands.isEmpty() || bottom - top < 5) return
        val h = (bottom - top) / bands.size
        if (h < 5) return
        bands.forEachIndexed { i, b ->
            val y0 = top + i * h
            val barH = when {
                h >= 14 -> 3
                h >= 9 -> 2
                h >= 6 -> 1
                else -> 0
            }
            val textY = y0 + ((h - barH - 5) / 2).coerceAtLeast(0)
            val v = if (b.warn) 255 else 225
            PixelText.draw(buf, b.label.toString(), 0, textY, 1, if (b.warn) 255 else 130)
            PixelText.draw(buf, b.text, 4, textY, 1, v)
            if (barH == 0) return@forEachIndexed
            val barY = y0 + h - barH
            val fill = (b.pct.coerceIn(0f, 1f) * (Dot.W - 1)).roundToInt()
            for (k in 0 until barH) {
                Scene.hLine(buf, 0, Dot.W - 1, barY + k, 18)
                if (fill > 0) Scene.hLine(buf, 0, fill, barY + k, if (b.warn) 255 else 200)
            }
        }
    }

    /** base + 路径，去掉多余斜杠。 */
    fun url(base: String, path: String): String = base + "/" + path.trimStart('/')

    /**
     * 画主角数字（FPS），返回后续内容可以从哪一行开始。
     *
     * [big] 为 true 用 4×9 大数字（三位数只要 14 点），否则用 3×5 字体。
     * 居中在扣掉右侧 [reserveRight] 列之后的宽度里做；大号字从第 2 行起笔以避开圆角。
     */
    fun drawHero(buf: ByteArray, text: String, big: Boolean, v: Int, reserveRight: Int = 3): Int {
        val room = Dot.W - reserveRight
        return if (big) {
            val w = PixelText.bigNumWidth(text)
            PixelText.drawBigNum(buf, text, ((room - w) / 2).coerceAtLeast(0), HERO_TOP, 1, v)
            HERO_TOP + PixelText.bigNumHeight()
        } else {
            val w = PixelText.width(text, 1)
            // 第 0 行只有 x=2 起始的 13 列可用，小字从这里起笔就绝不会被圆角碰到
            val x = ((room - w) / 2).coerceIn(2, (room - w).coerceAtLeast(2))
            PixelText.draw(buf, text, x, 0, 1, v)
            PixelText.height(1)
        }
    }

    /** 大号数字从第 2 行起笔，避开顶部两行的圆角。 */
    private const val HERO_TOP = 2

    /** 从 "a,b,c" 里取选择结果，未知 id 丢掉，超过上限截断。 */
    fun sanitize(raw: String, known: List<String>, def: String): List<String> {
        val ids = raw.split(',').map { it.trim() }.filter { it in known }.distinct()
        val out = if (ids.isEmpty()) def.split(',').filter { it in known } else ids
        return out.take(MAX_METRICS)
    }
}

/**
 * OpenWrt 状态：直接读路由器上 netdata 的 v1 接口。
 *
 * netdata 每张图一次只取"最近 1 个聚合点"就够用了（`after=-1&points=1&group=average`），
 * 一次几十字节，比拉 allmetrics（这台机器上 80KB）便宜得多。
 */
class OpenWrtTool(private val context: Context) : ToolInstance {

    private val prefs = ToolPrefs(context)
    private val base = Feed.normalizeBase(prefs.str(ID, "base", ""))
    private val ifacePref = prefs.str(ID, "iface", "").trim()
    private val fullMbit = prefs.float(ID, "full", 1000f).coerceIn(10f, 2500f)
    private val intervalMs = prefs.int(ID, "interval", 2).coerceIn(1, 30) * 1000L
    private val selected = Feed.sanitize(
        prefs.str(ID, "metrics", ""), OPTIONS.map { it.id }, DEFAULT_METRICS
    )

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** WAN 网卡的图表名（net.pppoe-WAN 这种），第一次拉 /api/v1/charts 时定下来。 */
    @Volatile private var wanChart: String? = if (ifacePref.isEmpty()) null else "net.$ifacePref"
    @Volatile private var cores = 4.0

    private class Snap(
        val bands: List<Band>,
        val title: String,
        val ok: Boolean,
        val at: Long,
        val err: String?
    )

    @Volatile private var snap = Snap(emptyList(), "", false, 0L, null)

    override fun start() {
        if (job != null || base.isEmpty()) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch {
            while (isActive) {
                tick()
                delay(intervalMs)
            }
        }
    }

    private fun tick() {
        val errs = mutableListOf<String>()
        if (wanChart == null) resolveWan(errs)

        // 同一个图表只查一次：WAN 上下行共用 net.xxx，没必要请求两遍
        val values = HashMap<String, Map<String, Double>>()
        for (id in selected) {
            val chart = (if (id == "wan_down" || id == "wan_up") wanChart else chartOf(id)) ?: continue
            if (chart.isEmpty()) continue
            if (values.containsKey(chart)) continue
            val dims = queryChart(chart)
            if (dims == null) errs += "$chart 取不到" else values[chart] = dims
        }
        if (values.isEmpty()) {
            snap = Snap(emptyList(), "", false, System.currentTimeMillis(), errs.firstOrNull())
            return
        }

        val bands = selected.mapNotNull { id -> band(id, values) }
        val title = wanChart?.substringAfter("net.") ?: "WAN"
        snap = Snap(bands, title, bands.isNotEmpty(), System.currentTimeMillis(), errs.firstOrNull())
    }

    /** 第一次要认出哪张网图是 WAN：名字里带 wan 的优先，其次 eth0。 */
    private fun resolveWan(errs: MutableList<String>) {
        val text = Feed.getText(Feed.url(base, "/api/v1/charts"), 4000)
        if (text == null) { errs += "路由器连不上"; return }
        val ids = runCatching {
            val charts = JSONObject(text).getJSONObject("charts")
            charts.keys().asSequence().toList()
        }.getOrNull()
        if (ids.isNullOrEmpty()) { errs += "图表列表读不出来"; return }
        wanChart = pickWan(ids) ?: run { errs += "找不到 WAN 网卡"; null }
        // 顺手把核数拿来做负载的百分比
        Feed.getText(Feed.url(base, "/api/v1/info"), 3000)?.let { info ->
            runCatching { JSONObject(info).optString("cores_total").toDoubleOrNull() }
                .getOrNull()?.let { if (it > 0) cores = it }
        }
    }

    private fun queryChart(chart: String): Map<String, Double>? {
        val url = Feed.url(
            base,
            "/api/v1/data?chart=" + java.net.URLEncoder.encode(chart, "UTF-8") +
                "&after=-1&points=1&group=average&format=json"
        )
        val text = Feed.getText(url, 4000) ?: return null
        return runCatching {
            val obj = JSONObject(text)
            val labels = obj.getJSONArray("labels")
            val row = obj.getJSONArray("data").getJSONArray(0)
            buildMap {
                for (i in 1 until labels.length()) {
                    val v = row.optDouble(i, Double.NaN)
                    if (!v.isNaN()) put(labels.getString(i), v)
                }
            }
        }.getOrNull()
    }

    private fun band(id: String, values: Map<String, Map<String, Double>>): Band? {
        when (id) {
            "wan_down", "wan_up" -> {
                val chart = wanChart ?: return null
                val dims = values[chart] ?: return null
                // netdata 的 sent 是负数（它按"进出方向"记录），取绝对值才是速率
                val kbit = abs(if (id == "wan_down") dims["received"] ?: 0.0 else dims["sent"] ?: 0.0)
                return Band(
                    if (id == "wan_down") 'D' else 'U',
                    Feed.compact(kbit / 1000.0),
                    flowPct(kbit, fullMbit)
                )
            }
            "cpu" -> {
                val dims = values["system.cpu"] ?: return null
                // 有的版本带 idle，有的不带：带就 100-idle，不带就把各状态加起来
                val used = if (dims.containsKey("idle")) {
                    (100.0 - (dims["idle"] ?: 0.0)).coerceIn(0.0, 100.0)
                } else {
                    dims.filterKeys { it != "idle" }.values.sum().coerceIn(0.0, 100.0)
                }
                return Band('C', Feed.percent(used), (used / 100).toFloat(), used >= 85)
            }
            "ram" -> {
                val dims = values["system.ram"] ?: return null
                val total = dims.values.sum()
                if (total <= 0) return null
                val used = dims["used"] ?: 0.0
                val pct = used / total * 100
                return Band('M', Feed.percent(pct), (pct / 100).toFloat(), pct >= 90)
            }
            "disk" -> {
                val dims = values["disk_space._"] ?: return null
                val used = dims["used"] ?: return null
                val avail = dims["avail"] ?: 0.0
                val pct = if (used + avail <= 0) 0.0 else used / (used + avail) * 100
                return Band('S', Feed.percent(pct), (pct / 100).toFloat(), pct >= 90)
            }
            "load" -> {
                val dims = values["system.load"] ?: return null
                val l1 = dims["load1"] ?: return null
                val pct = (l1 / cores).coerceIn(0.0, 1.0)
                return Band('L', Feed.compact(l1), pct.toFloat(), pct >= 0.9)
            }
            "conntrack" -> {
                val dims = values["netfilter.conntrack_sockets"] ?: return null
                val n = dims["connections"] ?: return null
                // 常见家用固件 conntrack 上限是 16384，按这个画条
                return Band('N', Feed.compact(n), (n / 16384).toFloat().coerceIn(0f, 1f), n >= 14000)
            }
        }
        return null
    }

    override fun frameDelayMs(): Long = 120L

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        Scene.clear(buf)
        val s = snap
        if (base.isEmpty()) {
            // 三字母在 scale=2 下是 22 点宽，面板只有 17 点，只能用一号字
            PixelText.drawCentered(buf, "SET", 6, 1, 200)
            PixelText.drawCentered(buf, "URL", 13, 1, 110)
            return
        }
        if (!s.ok) {
            // 连不上就明说，别把最后一帧冻在屏幕上装成实时
            val blink = (t * 1.6).toInt() % 2 == 0
            PixelText.drawCentered(buf, "OFF", 6, 1, if (blink) 255 else 70)
            PixelText.drawCentered(buf, "NET", 14, 1, 90)
            return
        }
        PixelText.draw(buf, TITLE, Dot.usableX(0).first, 0, 1, 120)
        // 右上角一颗"心跳"点，闪就说明还在轮询。放在第 3 行：第 0/1 行的最右两列是圆角
        val pulse = 0.5 + 0.5 * kotlin.math.sin(t * 3.0)
        Scene.max(buf, Dot.W - 1, 3, (70 + 150 * pulse).toInt())
        Feed.drawBands(buf, s.bands, 5, Dot.H)
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        val demo = listOf(Band('D', "12", 0.35f), Band('U', "1.2", 0.12f))
        PixelText.draw(buf, TITLE, Dot.usableX(0).first, 0, 1, 120)
        Feed.drawBands(buf, demo, 5, Dot.H)
    }

    override fun status(): String {
        val s = snap
        if (base.isEmpty()) return "还没有填 netdata 地址"
        if (!s.ok) return "连不上：${s.err ?: base}"
        val parts = s.bands.map { b -> "${nameOf(b.label)} ${b.text}" }
        return (listOf("${s.title}") + parts).joinToString(" · ")
    }

    override fun stop() {
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
    }

    private fun nameOf(label: Char): String =
        OPTIONS.firstOrNull { it.label == label }?.name ?: label.toString()

    companion object {
        const val ID = "openwrt"
        const val DEFAULT_METRICS = "wan_down,wan_up"

        /**
         * 背屏上的标题。
         *
         * 不写网卡真名（`pppoe-WAN` 这种）：17 点宽只能截到 4 个字符 `PPPO`，既读不出是什么，
         * 又正好顶到右上角的圆角和心跳点上。统一写 WAN，具体是哪张网卡交给详情页的 status()。
         */
        const val TITLE = "WAN"

        /** 面板上的勾选表。顺序就是默认顺序，WAN 进出排最前（用户明确要求默认展示它）。 */
        val OPTIONS: List<MetricOption> = listOf(
            MetricOption("wan_down", 'D', "WAN 下行"),
            MetricOption("wan_up", 'U', "WAN 上行"),
            MetricOption("cpu", 'C', "CPU 占用"),
            MetricOption("ram", 'M', "内存占用"),
            MetricOption("disk", 'S', "磁盘占用"),
            MetricOption("load", 'L', "系统负载"),
            MetricOption("conntrack", 'N', "连接数")
        )

        /** 指标 → netdata 图表名。WAN 那两项不在这张表里，它们的图表名要运行时才认出来。 */
        private val CHART = mapOf(
            "cpu" to "system.cpu",
            "ram" to "system.ram",
            "disk" to "disk_space._",
            "load" to "system.load",
            "conntrack" to "netfilter.conntrack_sockets"
        )

        fun chartOf(id: String): String? = CHART[id]

        /** 从图表列表里挑 WAN：名字带 wan/pppoe 的优先，否则退回 eth0。 */
        fun pickWan(charts: List<String>): String? {
            val nets = charts.filter { it.startsWith("net.") }
            return nets.firstOrNull { it.substringAfter("net.").lowercase().let { n -> n.contains("wan") || n.contains("pppoe") } }
                ?: nets.firstOrNull { it.substringAfter("net.").lowercase() == "eth0" }
                ?: nets.firstOrNull()
        }

        /**
         * 流量条用对数刻度：低于 8 kbit/s 算没流量，满格是配置的带宽。
         * 线性刻度下 100M 的线永远看不出中间档位。
         */
        fun flowPct(kbit: Double, fullMbit: Float): Float {
            val floor = 8.0
            val max = fullMbit.toDouble().coerceAtLeast(1.0) * 1000.0
            if (kbit <= floor || max <= floor) return 0f
            return (log10(kbit / floor) / log10(max / floor)).toFloat().coerceIn(0f, 1f)
        }
    }
}

/**
 * PC 状态：读 rig-exporter 的 /api/status。
 *
 * FPS 和"是否在游戏中"是主角，用大字号；
 * 剩下的 CPU / 显卡 / 显存这些可选指标排在下面，最多三个。
 */
class RigTool(private val context: Context) : ToolInstance {

    private val prefs = ToolPrefs(context)
    private val base = Feed.normalizeBase(prefs.str(ID, "base", ""))
    private val intervalMs = prefs.int(ID, "interval", 2).coerceIn(1, 30) * 1000L
    private val selected = Feed.sanitize(
        prefs.str(ID, "metrics", ""), OPTIONS.map { it.id }, DEFAULT_METRICS
    )

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    private class Snap(
        val fps: Double,
        val inGame: Boolean,
        val game: String,
        val bands: List<Band>,
        val ok: Boolean,
        val err: String?
    )

    @Volatile private var snap = Snap(0.0, false, "", emptyList(), false, null)

    override fun start() {
        if (job != null || base.isEmpty()) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch {
            while (isActive) {
                tick()
                delay(intervalMs)
            }
        }
    }

    private fun tick() {
        val text = Feed.getText(Feed.url(base, "/api/status"), 4000)
        if (text == null) {
            snap = Snap(snap.fps, false, "", emptyList(), false, "连不上 $base")
            return
        }
        val parsed = runCatching { parse(text) }.getOrNull()
        if (parsed == null) {
            snap = Snap(snap.fps, false, "", emptyList(), false, "返回的不是 rig-exporter 的 JSON")
            return
        }
        snap = parsed
    }

    private fun parse(text: String): Snap {
        val obj = JSONObject(text)
        val fps = obj.optDouble("fps", 0.0)
        val fpsOk = obj.optBoolean("fps_available", false)
        val game = obj.optString("game", "none")
        val rendering = obj.optBoolean("rendering", false)
        val inGame = (game.isNotEmpty() && game != "none") || rendering
        val rows = rowsOf(obj)

        val bands = selected.mapNotNull { id ->
            val m = OPTIONS.first { it.id == id }
            when (m.id) {
                "cpu" -> pctBand(m, obj.optDouble("cpu", 0.0), 85.0)
                "ram" -> pctBand(m, obj.optDouble("ram", 0.0), 90.0)
                "disk" -> pctBand(m, rows["磁盘 总体占用率"] ?: return@mapNotNull null, 90.0)
                "gpu" -> pctBand(m, rows["GPU 0 占用率"] ?: return@mapNotNull null, 95.0)
                "vram" -> pctBand(m, rows["GPU 0 显存占用率"] ?: return@mapNotNull null, 95.0)
                "temp" -> {
                    val t = rows["GPU 0 温度"] ?: return@mapNotNull null
                    Band(m.label, Feed.compact(t), (t / 100.0).toFloat().coerceIn(0f, 1f), t >= 80)
                }
                "power" -> {
                    val w = rows["GPU 0 功耗"] ?: return@mapNotNull null
                    val cap = rows["GPU 0 功耗上限"] ?: 200.0
                    Band(m.label, Feed.compact(w), (w / cap).toFloat().coerceIn(0f, 1f))
                }
                "fan" -> {
                    val rpm = rows["GPU 0 风扇转速"] ?: return@mapNotNull null
                    Band(m.label, Feed.compact(rpm), (rpm / 3000.0).toFloat().coerceIn(0f, 1f), rpm >= 2500)
                }
                "net" -> {
                    val mb = rows["以太网 下载"] ?: return@mapNotNull null
                    Band(m.label, Feed.compact(mb), (mb / 2500.0).toFloat().coerceIn(0f, 1f))
                }
                else -> null
            }
        }
        return Snap(if (fpsOk) fps else 0.0, inGame, game, bands, true, null)
    }

    private fun pctBand(m: MetricOption, v: Double, warnAt: Double) =
        Band(m.label, Feed.percent(v), (v / 100).toFloat().coerceIn(0f, 1f), v >= warnAt)

    /** 把 groups[].rows[] 摊平成 标签→数值。值都是 "25.0 %" 这种带单位的字符串，取开头的数。 */
    private fun rowsOf(obj: JSONObject): Map<String, Double> {
        val out = HashMap<String, Double>()
        val groups = obj.optJSONArray("groups") ?: return out
        for (i in 0 until groups.length()) {
            val rows = groups.optJSONObject(i)?.optJSONArray("rows") ?: continue
            for (j in 0 until rows.length()) {
                val r = rows.optJSONObject(j) ?: continue
                val label = r.optString("label")
                val value = Feed.firstNumber(r.optString("value")) ?: continue
                // 同名的行（多显卡时会出现）先到先得，别把第二张卡的数盖上去
                if (!out.containsKey(label)) out[label] = value
            }
        }
        return out
    }

    override fun frameDelayMs(): Long = 120L

    override fun render(buf: ByteArray, t: Double, dt: Double) {
        Scene.clear(buf)
        val s = snap
        if (base.isEmpty()) {
            PixelText.drawCentered(buf, "SET", 6, 1, 200)
            PixelText.drawCentered(buf, "URL", 13, 1, 110)
            return
        }
        if (!s.ok) {
            val blink = (t * 1.6).toInt() % 2 == 0
            PixelText.drawCentered(buf, "OFF", 6, 1, if (blink) 255 else 70)
            PixelText.drawCentered(buf, "PC", 14, 1, 90)
            return
        }

        val n = s.bands.size
        // FPS 只取整数：带一位小数要多占一格，而"60"和"59.8"对读数没区别
        val fpsText = if (s.fps > 0.5) s.fps.roundToInt().toString() else "--"
        // 指标少的时候 FPS 用 4×9 的大号字（三位数也放得下）；三个指标时高度不够，退回 3×5
        val nextY = Feed.drawHero(buf, fpsText, n <= 2, if (s.inGame) 255 else 150)

        // 右上角"在游戏中"的标记：朝右的小三角，游戏中亮、桌面上暗。
        // 固定在第 2~4 行最右三列——这几行没有圆角，也不会跟左边的大字撞上
        val arrowV = if (s.inGame) 240 else 45
        for (r in 0 until 3) {
            for (k in 0..r) Scene.max(buf, Dot.W - 3 + k, ARROW_TOP + r, arrowV)
        }

        if (s.bands.isNotEmpty()) Feed.drawBands(buf, s.bands, nextY, Dot.H)
    }

    override fun still(buf: ByteArray) {
        Scene.clear(buf)
        // 静态图直接用三位数，一眼就能看出高刷屏的数字放得下
        val nextY = Feed.drawHero(buf, "165", true, 235)
        for (r in 0 until 3) {
            for (k in 0..r) Scene.max(buf, Dot.W - 3 + k, ARROW_TOP + r, 240)
        }
        Feed.drawBands(
            buf,
            listOf(Band('C', "32%", 0.32f), Band('G', "76%", 0.76f)),
            nextY, Dot.H
        )
    }

    override fun status(): String {
        val s = snap
        if (base.isEmpty()) return "还没有填 rig-exporter 地址"
        if (!s.ok) return "连不上：${s.err ?: base}"
        val head = if (s.inGame) "游戏中 · ${s.game} · ${Feed.compact(s.fps)} FPS" else "桌面待机"
        val rest = s.bands.map { b -> "${nameOf(b.label)} ${b.text}" }
        return (listOf(head) + rest).joinToString(" · ")
    }

    override fun stop() {
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
    }

    private fun nameOf(label: Char): String =
        OPTIONS.firstOrNull { it.label == label }?.name ?: label.toString()

    companion object {
        const val ID = "rig"
        const val DEFAULT_METRICS = "cpu,gpu"

        /** "在游戏中"标记的起始行：第 2 行起就绕开了四角圆角。 */
        private const val ARROW_TOP = 2

        val OPTIONS: List<MetricOption> = listOf(
            MetricOption("cpu", 'C', "CPU 占用"),
            MetricOption("gpu", 'G', "显卡占用"),
            MetricOption("vram", 'V', "显存占用"),
            MetricOption("ram", 'M', "内存占用"),
            MetricOption("temp", 'T', "显卡温度"),
            MetricOption("power", 'P', "显卡功耗"),
            MetricOption("fan", 'F', "风扇转速"),
            MetricOption("disk", 'S', "磁盘占用"),
            MetricOption("net", 'N', "网络下载")
        )
    }
}
