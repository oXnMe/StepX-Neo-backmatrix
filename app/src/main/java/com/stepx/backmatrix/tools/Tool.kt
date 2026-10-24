package com.stepx.backmatrix.tools

import android.content.Context
import com.stepx.backmatrix.core.Dot

enum class ToolCategory(val label: String) {
    NET("网络与状态"),
    SENSOR("物理与环境"),
    HABIT("行为与专注"),
    ART("生成艺术")
}

/** 工具箱里的一张卡片。 */
data class ToolSpec(
    val id: String,
    val name: String,
    val category: ToolCategory,
    val tagline: String,
    val description: String,
    /** 启动前需要动态申请的权限。 */
    val permissions: List<String> = emptyList(),
    /** 需要硬件，例如麦克风、蓝牙、加速度计。 */
    val hardware: String? = null
)

/**
 * 一个实时帧生成器。
 *
 * 与 .bsa 动画不同，工具没有固定帧数：每帧由传感器/网络/算法现算。
 * [start] 在开始播放时调用一次（此时才可以去开传感器、开线程），
 * [stop] 在暂停或切换工具时释放。
 */
interface ToolInstance {
    fun start() {}
    fun render(buf: ByteArray, t: Double, dt: Double)
    /** 不进实时循环时的一张代表帧，用于工具箱缩略图。 */
    fun still(buf: ByteArray) = render(buf, 1.0, 0.05)
    fun frameDelayMs(): Long = 100L
    fun status(): String = ""
    /** 运行中被要求清零/重置时调用（只有需要这个动作的工具才实现）。 */
    fun reset() {}
    fun stop() {}
}

/** 工具参数统一存这里，键名带工具 id 前缀，避免互相踩。 */
class ToolPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tools", Context.MODE_PRIVATE)

    fun str(id: String, key: String, def: String): String = sp.getString("$id.$key", def) ?: def
    fun int(id: String, key: String, def: Int): Int = sp.getInt("$id.$key", def)
    fun long(id: String, key: String, def: Long): Long = sp.getLong("$id.$key", def)
    fun float(id: String, key: String, def: Float): Float = sp.getFloat("$id.$key", def)
    fun bool(id: String, key: String, def: Boolean): Boolean = sp.getBoolean("$id.$key", def)

    fun put(id: String, key: String, value: Any?) {
        val e = sp.edit()
        when (value) {
            is String -> e.putString("$id.$key", value)
            is Int -> e.putInt("$id.$key", value)
            is Long -> e.putLong("$id.$key", value)
            is Float -> e.putFloat("$id.$key", value)
            is Boolean -> e.putBoolean("$id.$key", value)
            else -> e.remove("$id.$key")
        }
        e.apply()
    }

    fun remove(id: String, key: String) {
        sp.edit().remove("$id.$key").apply()
    }
}

/**
 * 工具注册表。
 *
 * 这里只放元信息；实例由 [create] 按 id 现造，所以前台服务在进程重建后
 * 也能凭 id 把正在跑的工具重新拉起来。
 */
object Tools {

    private const val ID_PING = "ping"

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            id = ID_PING,
            name = "节点心跳矩阵",
            category = ToolCategory.NET,
            tagline = "391 个点盯着你的 VPS / NAS",
            description = "把面板切成小格，每格一台机器。存活的主机按各自的节奏呼吸，宕机的那格整块爆闪；" +
                "全部挂掉时满屏显示一个大感叹号。探测走 TCP 握手，只要求端口能连上，不关心对方说什么协议：" +
                "SSH 的 22、MySQL 的 3306、Redis 的 6379、RDP 的 3389 都能探，不限于 HTTP/HTTPS。"
        ),
        ToolSpec(
            id = "flow",
            name = "流量水流计",
            category = ToolCategory.NET,
            tagline = "下载往下淌，上传往上逆",
            description = "读取系统网卡计数器，把实时速率换算成雨量：越快，像素雨落得越密越急。" +
                "下载往下掉，上传从底部往上顶。面板是灰度的，绿色雨在这里表现为更亮的雨滴。"
        ),
        ToolSpec(
            id = OpenWrtTool.ID,
            name = "OpenWrt 状态",
            category = ToolCategory.NET,
            tagline = "路由器上的 netdata，搬到背屏",
            description = "填路由器上 netdata 的地址（默认端口 19999），直接读 netdata 的 v1 接口，" +
                "不装任何插件。默认显示 WAN 的下行和上行，也可以换成 CPU、内存、磁盘、负载、连接数。" +
                "WAN 网卡名字不用你填：名字里带 wan/pppoe 的自动优先，没有就退回 eth0。" +
                "面板一次最多放三个指标——每个指标要占一整行才看得清数字。"
        ),
        ToolSpec(
            id = RigTool.ID,
            name = "PC 游戏状态",
            category = ToolCategory.NET,
            tagline = "FPS 和显卡，抬眼看背屏",
            description = "填 PC 上 rig-exporter 的地址（默认端口 8787），读 /api/status。" +
                "FPS 用大号字显示，右上角的箭头亮着就表示正在游戏里，桌面上是暗的。" +
                "下面最多再挂三个指标：CPU、显卡占用、显存、内存、温度、功耗、风扇、磁盘、网速。"
        ),
        ToolSpec(
            id = "level",
            name = "数字水准泡",
            category = ToolCategory.SENSOR,
            tagline = "把桌面调成真正的水平",
            description = "加速度计算出倾角，气泡随重力滑动。落在中心容差内时会亮一圈光晕，" +
                "相当于一个随身携带的水准仪。测的是「手机背面贴着的面」平不平，适合放在桌面、地板这些水平面上。"
        ),
        ToolSpec(
            id = "line",
            name = "水平线水准仪",
            category = ToolCategory.SENSOR,
            tagline = "贴边比一比，看它正不正",
            description = "一条参考线加一个气泡，专门判断边缘有没有放平：竖着拿手机量顶边，" +
                "横着拿就自动改成量长边。气泡压在中间两条刻度之间就是正的，竖着用时偏差角度直接写在面板上方。" +
                "和数字水准泡互补——那个测水平面，这个测竖直面上的边。"
        ),
        ToolSpec(
            id = "noise",
            name = "环境分贝热力",
            category = ToolCategory.SENSOR,
            tagline = "会议室的无声静音提醒",
            description = "麦克风采低频环境声，算 RMS 当分贝。安静时只有底层波纹在慢慢晃，" +
                "有人提高嗓门，能量柱会直接顶到最上面一行。",
            permissions = listOf(android.Manifest.permission.RECORD_AUDIO),
            hardware = "麦克风"
        ),
        ToolSpec(
            id = "compass",
            name = "地磁航向针",
            category = ToolCategory.SENSOR,
            tagline = "针头朝前，北绕着圈走",
            description = "中间那根针固定朝屏幕上方，代表你正对的方向；北由表圈上最亮的一点表示，" +
                "它绕着你转，转到头顶就是正朝北。底部写出航向角。手机转一圈，亮点也跟着跑一圈。"
        ),
        ToolSpec(
            id = "focus",
            name = "倒扣禅定",
            category = ToolCategory.HABIT,
            tagline = "把手机倒扣，看着它慢慢沙化",
            description = "倒扣开始计时，整屏像素像沙堡一样从边缘剥落。中途翻开就算破功，" +
                "背屏立刻碎心并记上一笔；下次倒扣时提醒你已经失败过几回。"
        ),
        ToolSpec(
            id = "dice",
            name = "摇一摇骰子",
            category = ToolCategory.HABIT,
            tagline = "聚会时决定谁去买单",
            description = "手机正面朝下放桌上，摇一下：大号骰子点数、硬币的数字面与花面、或者一根转完停下的箭头。" +
                "不用掏手机看屏幕，答案就在背面。",
            hardware = "加速度计"
        ),
        ToolSpec(
            id = "knock",
            name = "敲击计数",
            category = ToolCategory.HABIT,
            tagline = "敲一下背面，记一次功德",
            description = "用手指敲手机背面，敲一下就加一。判据是几毫秒的高频冲击：把加速度里" +
                "拿起手机、走路这类慢动作先滤掉，剩下的尖峰超过门槛才算一下。" +
                "两次敲击之间留了 140ms 不应期，一次敲击不会被算成好几下。" +
                "按下开始、把手机放下的头一秒多不计数：放下这个动作本身在转，陀螺仪测到持续转动之后" +
                "要静置 1.2 秒才恢复；落定时磕的那几下会把冷却继续往后推，所以摆放手机怎么磕都不加分。" +
                "敲击本身那点抖动攒不够 0.15 秒，不会被当成\"拿起来了\"，所以放稳之后可以连续敲。" +
                "详情页实时显示当前冲击、门槛和还要等几秒，敲不出来就照着数字调灵敏度。木鱼动画可以关掉，只留数字当计数器。",
            hardware = "加速度计"
        ),
        ToolSpec(
            id = "sand",
            name = "落沙沙盒",
            category = ToolCategory.ART,
            tagline = "倾斜手机，看沙子堆成山丘",
            description = "元胞自动机模拟落沙：沙粒按重力方向滚动、堆叠，斜着拿手机沙丘就会塌到另一边。" +
                "纯装饰，但很好玩。"
        ),
        ToolSpec(
            id = "ble",
            name = "蓝牙雷达",
            category = ToolCategory.ART,
            tagline = "扫到附近谁在广播",
            description = "扫线顺时针转，扫到的蓝牙设备会在对应方位激起一圈扩散的涟漪，" +
                "信号越强涟漪越大。方位由设备地址散列而来——手机没有测向天线，方向是装饰性的。",
            permissions = buildList {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    add(android.Manifest.permission.BLUETOOTH_SCAN)
                } else {
                    add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            hardware = "蓝牙"
        )
    )

    fun spec(id: String): ToolSpec? = all.firstOrNull { it.id == id }

    fun byCategory(): List<Pair<ToolCategory, List<ToolSpec>>> =
        ToolCategory.entries.map { c -> c to all.filter { it.category == c } }

    /** 按 id 现造实例。调用方负责 start/stop。 */
    fun create(context: Context, id: String): ToolInstance? = when (id) {
        ID_PING -> PingMatrixTool(context)
        "flow" -> FlowMeterTool(context)
        OpenWrtTool.ID -> OpenWrtTool(context)
        RigTool.ID -> RigTool(context)
        "level" -> BubbleLevelTool(context)
        "line" -> LineLevelTool(context)
        "noise" -> NoiseMeterTool(context)
        "compass" -> CompassTool(context)
        "focus" -> FocusTool(context)
        "knock" -> KnockTool(context)
        "dice" -> DiceTool(context)
        "sand" -> SandTool(context)
        "ble" -> BleRadarTool(context)
        else -> null
    }

    /** 工具箱列表用的静态缩略帧。 */
    fun preview(context: Context, id: String): ByteArray {
        val buf = ByteArray(Dot.PIXELS)
        val tool = create(context, id) ?: return buf
        return try {
            tool.still(buf)
            buf
        } catch (t: Throwable) {
            ByteArray(Dot.PIXELS)
        }
    }
}
