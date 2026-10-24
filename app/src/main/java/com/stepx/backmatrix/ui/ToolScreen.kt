package com.stepx.backmatrix.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stepx.backmatrix.player.MatrixPlayer
import com.stepx.backmatrix.tools.Feed
import com.stepx.backmatrix.tools.FlowMeterTool
import com.stepx.backmatrix.tools.KnockTool
import com.stepx.backmatrix.tools.MetricOption
import com.stepx.backmatrix.tools.OpenWrtTool
import com.stepx.backmatrix.tools.PingMatrixTool
import com.stepx.backmatrix.tools.RigTool
import com.stepx.backmatrix.tools.ToolPrefs
import com.stepx.backmatrix.tools.ToolSpec
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 工具详情页：预览、启停、参数、说明。
 *
 * 参数只写进 SharedPreferences，不热更新正在运行的实例，改完需重启。
 */
@Composable
fun ToolScreen(
    context: Context,
    spec: ToolSpec,
    state: MatrixPlayer.State,
    frame: ByteArray?,
    fallback: ByteArray?,
    paramVersion: Int,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onParam: (String, Any?) -> Unit,
    onReset: () -> Unit
) {
    val prefs = remember(paramVersion) { ToolPrefs(context) }
    val running = state.playing && state.live && state.toolId == spec.id
    var dirty by remember(spec.id) { mutableStateOf(false) }

    fun set(key: String, value: Any?) {
        onParam(key, value)
        if (running) dirty = true
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏固定在原地。详情页内容很长，返回按钮要是跟着一起滚走，
        // 想回上一页还得先把页面一路拉回顶部
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackChip { onBack() }
            Spacer(Modifier.width(10.dp))
            Text(
                spec.name,
                color = Palette.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (running) Pill("运行中", Palette.accent)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Panel {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(width = 128.dp, height = 173.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0C0C10))
                            .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
                    ) {
                        DotGrid(
                            if (running) frame else fallback,
                            Modifier.fillMaxWidth().height(173.dp),
                            padding = 8f
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) state.title else "未运行",
                            color = Palette.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (running) state.status.ifEmpty { "启动中…" } else spec.tagline,
                            color = if (running) Palette.muted else Palette.dim,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        if (running && state.renderError != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(state.renderError, color = Palette.danger, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                        if (running && state.error != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(state.error, color = Palette.danger, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                        if (dirty) {
                            Spacer(Modifier.height(6.dp))
                            Text("参数已改 · 重启后生效", color = Palette.warn, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        if (dirty && running) "重启" else "启动",
                        modifier = Modifier.weight(1f),
                        enabled = true,
                        glyph = Glyph.PLAY
                    ) {
                        dirty = false
                        onStart()
                    }
                    GhostButton("停止", modifier = Modifier.weight(1f), enabled = running, glyph = Glyph.STOP) {
                        dirty = false
                        onStop()
                    }
                }
            }

            ParamPanel(
                spec = spec,
                prefs = prefs,
                running = running,
                onReset = { onReset() },
                set = { key, value -> set(key, value) }
            )

            if (spec.id == "focus") {
                Panel {
                    SectionTitle("纪律记录")
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("提前翻面次数", "${prefs.int("focus", "broken", 0)} 次", Palette.warn)
                    Spacer(Modifier.height(8.dp))
                    GhostButton("清零", modifier = Modifier.fillMaxWidth()) { set("broken", 0) }
                }
            }

            Panel {
                SectionTitle("说明")
                Spacer(Modifier.height(6.dp))
                Text(spec.description, color = Palette.muted, fontSize = 11.sp, lineHeight = 18.sp)
                if (spec.hardware != null) {
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("依赖硬件", spec.hardware)
                }
                if (spec.permissions.isNotEmpty()) {
                    KeyValueRow("需要权限", "启动时会向你申请", Palette.warn)
                }
            }
        }
    }
}

@Composable
private fun ParamPanel(
    spec: ToolSpec,
    prefs: ToolPrefs,
    running: Boolean,
    onReset: () -> Unit,
    set: (String, Any?) -> Unit
) {
    val id = spec.id
    if (id == "compass" || id == "ble") return

    Panel {
        SectionTitle("参数")
        Spacer(Modifier.height(2.dp))
        when (id) {
            "ping" -> {
                Spacer(Modifier.height(6.dp))
                Text("监控目标", color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                TextFieldBox(
                    value = prefs.str("ping", "hosts", PingMatrixTool.DEFAULT_HOSTS),
                    placeholder = "每行一个 host:port",
                    singleLine = false,
                    minHeight = 76.dp,
                    onValueChange = { set("hosts", it) }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "只做 TCP 连接，不看对方是什么协议：SSH 22、MySQL 3306、Redis 6379、RDP 3389 都行。" +
                        "端口不写默认按 443 试。",
                    color = Palette.dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
                val interval = prefs.int("ping", "interval", 5)
                LabeledSlider("探测间隔", interval.toFloat(), "${interval}s", 2f..30f, 27) {
                    set("interval", it.roundToInt())
                }
                val timeout = prefs.int("ping", "timeout", 1200)
                LabeledSlider("单次超时", timeout.toFloat(), "${timeout}ms", 300f..3000f, 8) {
                    set("timeout", ((it / 100).roundToInt() * 100))
                }
            }

            "flow" -> {
                val scale = prefs.float("flow", "scale", 4f)
                val full = FlowMeterTool.fullScaleBytes(scale)
                val label = if (full >= 1024 * 1024) {
                    String.format(Locale.US, "满格 %.1f MB/s", full / 1048576)
                } else {
                    String.format(Locale.US, "满格 %.0f KB/s", full / 1024)
                }
                LabeledSlider(label, scale, String.format(Locale.US, "%.0f 级", scale), 1f..8f, 6) {
                    set("scale", it)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "对数刻度：量程越小，小流量越敏感。手机待机时的背景流量只有几百 B/s，" +
                        "调小一档就能看见它在动。",
                    color = Palette.dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }

            "knock" -> {
                val sens = prefs.float("knock", "sens", 1.0f)
                val gate = KnockTool.threshold(sens) / KnockTool.SENSOR_G
                // 门槛本来就小（0.1~0.4g），一位小数会显示成"0.1g"，两位才看得出差别
                val gateText = String.format(Locale.US, if (gate < 0.3) "%.2fg" else "%.1fg", gate)
                LabeledSlider(
                    "灵敏度",
                    sens,
                    String.format(Locale.US, "%.1fx · 门槛 %s", sens, gateText),
                    0.4f..3f,
                    26
                ) { set("sens", (it * 10).roundToInt() / 10f) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "往右更灵敏。敲不响就往右调一格，调到上面那行的「当前」读数能超过「门槛」为止——" +
                        "敲的时候那个数会跳一下。判据看的是几毫秒的高频冲击，" +
                        "拿起手机、走路这类慢动作会被滤掉。" +
                        "动过手机之后要静置一秒多才恢复计数：放下、倒扣、拿起都算动，" +
                        "所以摆放手机时磕到桌面不会被记成敲击，敲击那点抖动也不会让你等。" +
                        "上面那个读数会直接显示还要放稳几秒。",
                    color = Palette.dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    "显示木鱼动画",
                    "关掉就只留数字，当纯计数器用",
                    prefs.bool("knock", "fish", true)
                ) { set("fish", it) }
                Spacer(Modifier.height(8.dp))
                GhostButton(
                    "计数清零",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = running
                ) { onReset() }
            }

            "level" -> {
                val tol = prefs.float("level", "tol", 1.2f)
                LabeledSlider(
                    "水平容差", tol, String.format(Locale.US, "%.1f°", tol), 0.3f..4f, 36
                ) { set("tol", (it * 10).roundToInt() / 10f) }
            }

            "line" -> {
                val tol = prefs.float("line", "tol", 0.8f)
                LabeledSlider(
                    "水平容差", tol, String.format(Locale.US, "%.1f°", tol), 0.2f..3f, 28
                ) { set("tol", (it * 10).roundToInt() / 10f) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "容差越小越严格。手举着会有微抖，0.5° 以下容易一直闪，一般 0.6~1.0° 最顺手。",
                    color = Palette.dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }

            "noise" -> {
                val gain = prefs.float("noise", "gain", 1f)
                LabeledSlider(
                    "灵敏度", gain, String.format(Locale.US, "%.1fx", gain), 0.4f..3f, 26
                ) { set("gain", (it * 10).roundToInt() / 10f) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "以 -55dBFS 为安静基线、-8dBFS 为满格换算。会议室里太容易顶满就把灵敏度往左调。",
                    color = Palette.dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }

            "focus" -> {
                val m = prefs.int("focus", "minutes", 25)
                LabeledSlider("时长", m.toFloat(), "$m 分钟", 5f..60f, 54) {
                    set("minutes", it.roundToInt())
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "倒扣手机开始计时；中途翻过来就会碎心，并记一次失败。",
                    color = Palette.dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }

            "dice" -> {
                val mode = prefs.str("dice", "mode", "dice")
                val idx = when (mode) {
                    "coin" -> 1
                    "arrow" -> 2
                    else -> 0
                }
                SegRow(listOf("骰子", "硬币", "箭头"), idx) {
                    set("mode", when (it) {
                        1 -> "coin"
                        2 -> "arrow"
                        else -> "dice"
                    })
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "硬币一面是数字「1」、一面是五瓣花，国内习惯的「数字面 / 花面」。",
                    color = Palette.dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }

            "sand" -> {
                val rate = prefs.int("sand", "rate", 1)
                LabeledSlider("撒沙速率", rate.toFloat(), "$rate 颗/帧", 1f..8f, 6) {
                    set("rate", it.roundToInt())
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "重力跟着手机姿态走：竖着拿往底部塌，横过来往侧边堆，平放按默认向下。",
                    color = Palette.dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }

            "openwrt" -> {
                Spacer(Modifier.height(6.dp))
                Text("netdata 地址", color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                TextFieldBox(
                    value = prefs.str(OpenWrtTool.ID, "base", ""),
                    placeholder = "http://192.168.1.1:19999",
                    onValueChange = { set("base", it) }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "路由器上装了 netdata 直接就能读，不用装插件、不用建只读账号。" +
                        "地址只写 IP 也行，会自动补 http://。",
                    color = Palette.dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )

                MetricPicker(
                    toolId = OpenWrtTool.ID,
                    options = OpenWrtTool.OPTIONS,
                    default = OpenWrtTool.DEFAULT_METRICS,
                    prefs = prefs,
                    set = set
                )

                // 满格带宽决定流量条画多长。做成几档而不是滑块，省得拖半天对不准。
                val presets = listOf(100f, 300f, 500f, 1000f, 2000f)
                val labels = listOf("100M", "300M", "500M", "1G", "2G")
                val cur = prefs.float(OpenWrtTool.ID, "full", 1000f)
                val idx = presets.indexOfFirst { it == cur }.let { if (it >= 0) it else 3 }
                Spacer(Modifier.height(10.dp))
                Text("WAN 满格带宽", color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                SegRow(labels, idx) { set("full", presets[it]) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "按你家宽带的签约速率选。流量条是对数刻度，选大了小流量也看得见，只是不够满。",
                    color = Palette.dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )

                val interval = prefs.int(OpenWrtTool.ID, "interval", 2)
                LabeledSlider("刷新间隔", interval.toFloat(), "${interval}s", 1f..30f, 28) {
                    set("interval", it.roundToInt())
                }
            }

            "rig" -> {
                Spacer(Modifier.height(6.dp))
                Text("rig-exporter 地址", color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                TextFieldBox(
                    value = prefs.str(RigTool.ID, "base", ""),
                    placeholder = "http://192.168.1.100:8787",
                    onValueChange = { set("base", it) }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "PC 上跑着 rig-exporter 就行，它读 RTSS 的帧率。游戏没在跑的时候 FPS 位置显示 --，" +
                        "右上角的箭头是暗的。",
                    color = Palette.dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )

                MetricPicker(
                    toolId = RigTool.ID,
                    options = RigTool.OPTIONS,
                    default = RigTool.DEFAULT_METRICS,
                    prefs = prefs,
                    set = set
                )

                val interval = prefs.int(RigTool.ID, "interval", 2)
                LabeledSlider("刷新间隔", interval.toFloat(), "${interval}s", 1f..30f, 28) {
                    set("interval", it.roundToInt())
                }
            }
        }
    }
}

/**
 * 指标多选。到上限后其余项置灰但仍列出，避免看起来「只支持这几项」。
 *
 * 存的是逗号分隔的 id，按 [options] 的顺序重排：勾选先后不影响背屏上的顺序。
 */
@Composable
private fun MetricPicker(
    toolId: String,
    options: List<MetricOption>,
    default: String,
    prefs: ToolPrefs,
    set: (String, Any?) -> Unit
) {
    val current = Feed.sanitize(prefs.str(toolId, "metrics", ""), options.map { it.id }, default)
    val full = current.size >= Feed.MAX_METRICS

    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("展示指标", color = Palette.muted, fontSize = 12.sp)
        Text(
            "${current.size} / ${Feed.MAX_METRICS}",
            color = if (full) Palette.accent else Palette.dim,
            fontSize = 11.sp
        )
    }
    options.forEach { o ->
        val checked = o.id in current
        CheckRow(
            title = o.name,
            checked = checked,
            // 已勾上的永远能取消；没勾的在满了以后就点不动了
            enabled = checked || !full,
            note = if (checked) o.label.toString() else null
        ) { on ->
            val next = if (on) current + o.id else current - o.id
            set("metrics", options.map { it.id }.filter { it in next }.joinToString(","))
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        if (full) "已经满 ${Feed.MAX_METRICS} 个了。屏幕只有 17 点宽，每个指标至少要一整行才放得下数字，" +
            "想换就先取消一个。"
        else "最多 ${Feed.MAX_METRICS} 个。勾得少，剩下的指标会自动分到更多行，更好读。",
        color = Palette.dim,
        fontSize = 10.sp,
        lineHeight = 15.sp
    )
}
