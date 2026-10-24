package com.stepx.backmatrix

import android.Manifest
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.tools.CompassTool
import com.stepx.backmatrix.tools.Feed
import com.stepx.backmatrix.tools.FlowMeterTool
import com.stepx.backmatrix.tools.KnockTool
import com.stepx.backmatrix.tools.OpenWrtTool
import com.stepx.backmatrix.tools.PingMatrixTool
import com.stepx.backmatrix.tools.SandTool
import com.stepx.backmatrix.tools.ToolCategory
import com.stepx.backmatrix.tools.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 注册表是工具箱的入口，元信息错了界面就会露馅。 */
class ToolsTest {

    @Test
    fun `十三个工具都在注册表里`() {
        assertEquals(13, Tools.all.size)
        val expected = setOf(
            "ping", "flow", "openwrt", "rig", "level", "line", "noise", "compass",
            "focus", "dice", "sand", "ble", "knock"
        )
        assertEquals(expected, Tools.all.map { it.id }.toSet())
    }

    @Test
    fun `遥测工具都不需要运行时权限`() {
        // 只是发 HTTP 到局域网，用不着 INTERNET 之外的任何东西，更不该弹授权框
        assertTrue(Tools.spec("openwrt")!!.permissions.isEmpty())
        assertTrue(Tools.spec("rig")!!.permissions.isEmpty())
        assertEquals(ToolCategory.NET, Tools.spec("openwrt")!!.category)
        assertEquals(ToolCategory.NET, Tools.spec("rig")!!.category)
    }

    @Test
    fun `敲击计数归在行为与专注`() {
        assertEquals(ToolCategory.HABIT, Tools.spec("knock")!!.category)
        assertEquals("加速度计", Tools.spec("knock")!!.hardware)
        // 加速度计不是运行时权限，不该让用户去点授权框
        assertTrue(Tools.spec("knock")!!.permissions.isEmpty())
    }

    @Test
    fun `id 不重复且名字与说明都不为空`() {
        assertEquals(Tools.all.size, Tools.all.map { it.id }.toSet().size)
        Tools.all.forEach {
            assertTrue("${it.id} 名字为空", it.name.isNotBlank())
            assertTrue("${it.id} 描述为空", it.description.isNotBlank())
            assertTrue("${it.id} 标语为空", it.tagline.isNotBlank())
        }
    }

    @Test
    fun `四个分类都有工具`() {
        val grouped = Tools.byCategory()
        assertEquals(ToolCategory.entries.size, grouped.size)
        grouped.forEach { (category, specs) ->
            assertTrue("${category.label} 没有工具", specs.isNotEmpty())
            assertTrue(specs.all { it.category == category })
        }
        assertEquals(Tools.all.size, grouped.sumOf { it.second.size })
    }

    @Test
    fun `未知 id 返回 null 而不是抛异常`() {
        assertNull(Tools.spec("nope"))
        assertNotNull(Tools.spec("ping"))
    }

    @Test
    fun `需要权限的工具都标了对应权限`() {
        assertTrue(Tools.spec("noise")!!.permissions.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(Tools.spec("ble")!!.permissions.isNotEmpty())
        assertTrue(Tools.spec("ping")!!.permissions.isEmpty())
        assertTrue(Tools.spec("sand")!!.permissions.isEmpty())
    }

    @Test
    fun `主机数量映射到网格`() {
        assertEquals(1 to 1, PingMatrixTool.gridFor(1))
        assertEquals(2 to 1, PingMatrixTool.gridFor(2))
        assertEquals(3 to 1, PingMatrixTool.gridFor(3))
        assertEquals(2 to 2, PingMatrixTool.gridFor(4))
        assertEquals(3 to 2, PingMatrixTool.gridFor(5))
        assertEquals(3 to 3, PingMatrixTool.gridFor(9))
        assertEquals(4 to 3, PingMatrixTool.gridFor(12))
        assertEquals(4 to 4, PingMatrixTool.gridFor(16))
    }

    @Test
    fun `网格永远放得下所有主机`() {
        for (n in 1..16) {
            val (c, r) = PingMatrixTool.gridFor(n)
            assertTrue("$n 台主机放不进 ${c}x$r", c * r >= n)
        }
    }

    @Test
    fun `流量映射单调且量程越小越敏感`() {
        assertEquals(0f, FlowMeterTool.levelFor(32.0, 4f), 0.0001f)
        assertTrue(FlowMeterTool.levelFor(2048.0, 4f) > 0f)
        assertTrue(FlowMeterTool.levelFor(1_000_000.0, 4f) > FlowMeterTool.levelFor(100_000.0, 4f))
        assertEquals(1f, FlowMeterTool.levelFor(1e9, 4f), 0.0001f)
        assertTrue(
            "小量程应该更敏感",
            FlowMeterTool.levelFor(80_000.0, 2f) > FlowMeterTool.levelFor(80_000.0, 6f)
        )
        // 满格点正好对应 1.0
        val full = FlowMeterTool.fullScaleBytes(4f)
        assertEquals(1f, FlowMeterTool.levelFor(full, 4f), 0.001f)
    }

    @Test
    fun `待机背景流量也要看得见`() {
        // 以前门槛是 512 B/s，手机闲着时读数常年为 0，面板只剩一条不动的横线
        assertTrue(FlowMeterTool.levelFor(300.0, 4f) > 0f)
        assertTrue(FlowMeterTool.levelFor(300.0, 4f) < 0.2f)
    }

    @Test
    fun `落沙的重力方向四个姿态都对`() {
        // 这两行在真机上错了两次：先两轴都取反、再只反 x。锁死成回归测试。
        assertEquals(0 to 1, SandTool.gravityDir(0f, 9.8f))
        assertEquals(0 to -1, SandTool.gravityDir(0f, -9.8f))
        assertEquals(1 to 0, SandTool.gravityDir(9.8f, 0f))
        assertEquals(-1 to 0, SandTool.gravityDir(-9.8f, 0f))
        // 几乎平放时不能出现两个分量都为 0 的情况，否则沙子不动
        assertEquals(0 to 1, SandTool.gravityDir(0f, 0f))
    }

    @Test
    fun `罗盘的北点绕着你转`() {
        fun close(actual: Pair<Float, Float>, ex: Float, ey: Float) {
            assertEquals("x", ex, actual.first, 0.001f)
            assertEquals("y", ey, actual.second, 0.001f)
        }
        // 朝北时北点在头顶，朝东时在左边，朝南时在脚下，朝西时在右边
        close(CompassTool.northScreenDir(0f), 0f, -1f)
        close(CompassTool.northScreenDir(90f), -1f, 0f)
        close(CompassTool.northScreenDir(180f), 0f, 1f)
        close(CompassTool.northScreenDir(270f), 1f, 0f)
        // 朝东北：北在左上
        val (nx, ny) = CompassTool.northScreenDir(45f)
        assertTrue("朝东北时北应该偏左上", nx < 0f && ny < 0f)
    }

    /** 按 200Hz 跑一段只沿 z 轴的加速度信号，返回过程中最大的冲击幅值。 */
    private fun maxShock(durationSec: Double = 1.0, signal: (Double) -> Double): Double {
        val dt = 0.005
        var g = signal(0.0)
        var peak = 0.0
        var t = dt
        while (t <= durationSec) {
            val a = signal(t)
            val alpha = KnockTool.gravityAlpha(dt)
            g = alpha * g + (1 - alpha) * a
            val s = KnockTool.shockOf(0.0, 0.0, a, 0.0, 0.0, g)
            if (s > peak) peak = s
            t += dt
        }
        return peak
    }

    @Test
    fun `拿起手机不该被当成敲击`() {
        // 1.4.0 的判据是"模长比基线大多少"，拿起手机和敲击都是模长大涨，只能靠阈值硬切，
        // 结果就是敲不响、拿起却记了一下。现在看的是高频冲击，两者能分开。
        val picked = maxShock { t -> 9.81 + 4.0 * kotlin.math.sin(2 * Math.PI * t) }
        val knock = maxShock { t ->
            val d = (t - 0.5) / 0.006
            9.81 + 22.0 * kotlin.math.exp(-d * d)
        }
        val gate = KnockTool.threshold(1f)
        assertTrue("拿起手机的冲击 $picked 不该过门槛 $gate", picked < gate * 0.5)
        assertTrue("敲一下的冲击 $knock 应该远高于门槛 $gate", knock > gate * 2)
    }

    @Test
    fun `刚启动和手机在动的时候不计数`() {
        // 放到桌上磕的那几下与敲击同谱，只能靠时间闸挡：启动时当作"刚动过"，
        // 测到持续转动后再静置一秒多才恢复计数。
        val g = KnockTool.SENSOR_G
        val start = 100_000L
        // 刚点开始：用户手上多半还端着手机，先给一段冷却
        assertFalse(KnockTool.settledAt(start + 100, start, g))
        assertTrue(
            "放稳之后就该允许计数",
            KnockTool.settledAt(start + KnockTool.SETTLE_MS, start, g)
        )
        // 刚刚还在转（放下、倒扣、拿起）
        assertFalse(
            "刚测到转动，落桌那一下不该算",
            KnockTool.settledAt(start + 5_000, start + 5_000 - 50, g)
        )
        assertTrue(
            "静止够久了就该恢复",
            KnockTool.settledAt(start + 5_000, start + 5_000 - KnockTool.SETTLE_MS, g)
        )
        // 基线被拉离 1g（自由落体、被持续加速）：这时候的"回落边沿"不是敲击
        assertFalse("基线 30g 时不该计数", KnockTool.settledAt(start + 5_000, start, g * 3))
        assertFalse("基线接近 0 时不该计数", KnockTool.settledAt(start + 5_000, start, 0.0))
    }

    @Test
    fun `放下手机时磕的那一串都不计数`() {
        // 落桌是一串（弹一下再落一下，间隔一两百毫秒），140ms 不应期拆不开。
        // 用「动过之后静置一秒多」整串挡住，冷却里还在磕则继续顺延
        val g = KnockTool.SENSOR_G
        val moveAt = 100_000L          // 放下手机的过程测到持续转动
        var lastMove = moveAt
        var counted = 0
        // 落定后 0.6 秒之内磕三下
        for (hit in longArrayOf(moveAt + 100, moveAt + 350, moveAt + 600)) {
            if (hit - lastMove < KnockTool.SETTLE_MS) lastMove = KnockTool.settleAfterHit(hit, lastMove)
            if (KnockTool.settledAt(hit, lastMove, g)) counted += 1
        }
        assertEquals("落桌的三下都该被冷却挡住", 0, counted)
        // 放稳之后照常计数：冷却从最后一次磕碰算起，不再是 1.2 秒以前那次转动
        val later = moveAt + KnockTool.SETTLE_MS
        assertTrue("放稳之后要能敲", KnockTool.settledAt(later, lastMove, g))
    }

    @Test
    fun `冷却只会被延后，不会被缩短`() {
        val t0 = 0L
        // 刚转完就磕一下：本来还剩一秒多，别被这一下缩短
        assertEquals(t0, KnockTool.settleAfterHit(t0 + 100, t0))
        // 冷却快到期时又磕一下：至少再续 HIT_EXTEND_MS
        val nearEnd = t0 + KnockTool.SETTLE_MS - 50
        val pushed = KnockTool.settleAfterHit(nearEnd, t0)
        assertEquals(nearEnd - KnockTool.SETTLE_MS + KnockTool.HIT_EXTEND_MS, pushed)
        assertFalse(
            "延后之后还是不许计数",
            KnockTool.settledAt(nearEnd, pushed, KnockTool.SENSOR_G)
        )
    }

    @Test
    fun `敲击的抖动不算手机在动，拿起来才算`() {
        // 敲一下机身抖 40ms：攒不到 MOVE_HOLD_S，否则每敲一下都要等一秒多
        var score = 0.0
        repeat(4) { score = KnockTool.moveScoreNext(score, 0.01, 0.9) }
        assertTrue("敲击抖动 $score 不该算在动", score < KnockTool.MOVE_HOLD_S)
        // 敲完震一下，然后安静，分数要很快归零
        score = KnockTool.moveScoreNext(score, 0.05, 0.0)
        assertEquals(0.0, score, 1e-9)
        // 拿起来：持续 0.3 秒的转动，一定要过线
        repeat(30) { score = KnockTool.moveScoreNext(score, 0.01, 0.6) }
        assertTrue("拿起手机的转动 $score 应该算在动", score >= KnockTool.MOVE_HOLD_S)
        // 低于角速度阈值的小晃动（放在桌上被人碰一下）不算
        var slow = 0.0
        repeat(50) { slow = KnockTool.moveScoreNext(slow, 0.01, KnockTool.MOVE_RAD_S * 0.5) }
        assertTrue("低于阈值的晃动不该积累", slow < KnockTool.MOVE_HOLD_S)
    }

    @Test
    fun `默认灵敏度下轻敲就够，不用使劲砸`() {
        // 手指轻敲的冲击峰值大约 5~10 m/s²，经过高通还会打折；默认门槛不能把这些挡在外面
        val light = maxShock { t ->
            val d = (t - 0.5) / 0.006
            9.81 + 7.0 * kotlin.math.exp(-d * d)
        }
        val gate = KnockTool.threshold(1f)
        assertTrue("轻敲 $light 应该过得了默认门槛 $gate", light > gate)
        // 最灵敏那一档要更松
        assertTrue(KnockTool.threshold(3f) < gate)
    }

    @Test
    fun `灵敏度越高门槛越低`() {
        assertTrue(KnockTool.threshold(2f) < KnockTool.threshold(1f))
        assertTrue(KnockTool.threshold(0.5f) > KnockTool.threshold(1f))
        // 越界的灵敏度被夹到范围内，不会把阈值算成 0 或负数
        assertEquals(KnockTool.threshold(3f), KnockTool.threshold(1000f), 0.0001)
        assertEquals(KnockTool.threshold(0.4f), KnockTool.threshold(0.01f), 0.0001)
        assertTrue(KnockTool.threshold(3f) > 0)
    }

    @Test
    fun `低通系数随时间间隔衰减`() {
        // 采样率越低，单步 dt 越大，旧值保留得越少——时间常数才和采样率无关
        assertTrue(KnockTool.gravityAlpha(0.001) > KnockTool.gravityAlpha(0.01))
        assertTrue(KnockTool.gravityAlpha(0.01) > KnockTool.gravityAlpha(0.05))
        assertEquals(1.0, KnockTool.gravityAlpha(0.0), 1e-9)
    }

    @Test
    fun `灵敏度调到最低，真敲一下也要过门槛`() {
        // 门槛必须留在「轻敲也能过」的范围内：
        // 手指敲背面的冲击峰值大约 15~40 m/s²，最不灵敏那档不能高过它太多
        val gate = KnockTool.threshold(0.4f)
        assertTrue("最不灵敏时门槛 $gate 太高了", gate <= 10.0)
        val knock = maxShock { t ->
            val d = (t - 0.5) / 0.006
            9.81 + 22.0 * kotlin.math.exp(-d * d)
        }
        assertTrue("真敲一下的冲击 $knock 应该过得了最低灵敏度的门槛 $gate", knock > gate)
    }

    @Test
    fun `一次冲击只记一下，两下就是两下`() {
        // 跑完整的"上膛 / 卸膛"状态机。冲击是单个高斯脉冲（真实敲击就是几毫秒的尖峰），
        // 不是方波——方波在"松开"时还有一个反向跳变，那是模型不真实，不是算法的问题
        fun run(pulses: List<Double>): Int {
            val dt = 0.005
            val gate = KnockTool.threshold(1f)
            var g = 9.81
            var armed = true
            var count = 0
            var lastKnock = -10_000L
            var t = 0.0
            var first = true
            while (t <= 1.2) {
                var a = 9.81
                for (p in pulses) {
                    val d = (t - p) / 0.008
                    a += 20.0 * kotlin.math.exp(-d * d)
                }
                if (first) {
                    g = a
                    first = false
                } else {
                    val alpha = KnockTool.gravityAlpha(dt)
                    g = alpha * g + (1 - alpha) * a
                    val s = KnockTool.shockOf(0.0, 0.0, a, 0.0, 0.0, g)
                    val nowMs = (t * 1000).toLong()
                    if (armed) {
                        if (s > gate) {
                            if (nowMs - lastKnock >= KnockTool.MIN_GAP_MS) {
                                lastKnock = nowMs
                                count += 1
                            }
                            armed = false
                        }
                    } else if (s < gate * KnockTool.REARM_RATIO) {
                        armed = true
                    }
                }
                t += dt
            }
            return count
        }

        assertEquals("一记敲击被记成了多次", 1, run(listOf(0.3)))
        assertEquals("连着两下应该记两下", 2, run(listOf(0.3, 0.6)))
        // 40ms 内的两次抖动算同一下（不应期 140ms）
        assertEquals("挨太近的两下应该只算一下", 1, run(listOf(0.3, 0.34)))
    }

    @Test
    fun `木鱼只占下半屏，不会压住计数`() {
        // 计数是 scale=2 的两位数字，高 10 行；鼓身必须整块落在它下面
        val countBottom = KnockTool.COUNT_Y_WITH_FISH + 10 - 1
        assertTrue(
            "鼓身从第 ${KnockTool.BODY_TOP} 行开始，会压住第 $countBottom 行之前的数字",
            KnockTool.BODY_TOP > countBottom
        )
        assertEquals("鼓身要一直画到面板底边", Dot.H - 1, KnockTool.BODY_TOP + KnockTool.BODY.size - 1)
    }

    @Test
    fun `鼓身每一行都在能亮的点里`() {
        KnockTool.BODY.forEachIndexed { i, span ->
            val y = KnockTool.BODY_TOP + i
            val usable = Dot.usableX(y)
            assertTrue("第 $y 行左端 ${span[0]} 越界", span[0] >= usable.first)
            assertTrue("第 $y 行右端 ${span[1]} 越界", span[1] <= usable.last)
            assertTrue("第 $y 行左右反了", span[0] <= span[1])
        }
        // 顶部要比底部窄，否则画出来是个方块不是鼓
        assertTrue(
            "顶部该比底部窄",
            KnockTool.BODY.first().let { it[1] - it[0] } < KnockTool.BODY.last().let { it[1] - it[0] }
        )
        // 左边那道斜边必须真的斜到最左列，否则左下角那截柄就没了
        assertTrue("左边没斜到最左列", KnockTool.BODY.any { it[0] == 0 })
    }

    @Test
    fun `鱼嘴的暗缝落在鼓身里面`() {
        val slit = KnockTool.SLIT
        for (i in slit.indices step 2) {
            val x = slit[i]
            val y = slit[i + 1]
            val span = KnockTool.BODY[y - KnockTool.BODY_TOP]
            assertTrue("缝 ($x,$y) 掉到鼓身外面了", x in span[0]..span[1])
        }
    }

    @Test
    fun `WAN 网卡优先认名字带 wan 或 pppoe 的`() {
        // 真机上的图表名长这样：路由器会把逻辑接口名当成图表名
        val charts = listOf(
            "net.eth0", "net.br-lan", "net.pppoe-wan", "net.lo",
            "system.cpu", "disk_space._"
        )
        assertEquals("net.pppoe-wan", OpenWrtTool.pickWan(charts))
        // 没有 pppoe 时退回 eth0
        assertEquals("net.eth0", OpenWrtTool.pickWan(listOf("net.br-lan", "net.eth0")))
        // 什么都没有就返回 null，别硬塞一张图进去
        assertNull(OpenWrtTool.pickWan(listOf("system.cpu", "system.ram")))
    }

    @Test
    fun `流量条是对数刻度`() {
        // 空流量压到 0；满格是 1
        assertEquals(0f, OpenWrtTool.flowPct(0.0, 1000f))
        assertEquals(0f, OpenWrtTool.flowPct(5.0, 1000f))
        assertEquals(1f, OpenWrtTool.flowPct(1000.0 * 1000, 1000f))
        // 对数刻度的意义：1/1000 的流量也能占到两成高度，线性刻度下只有 0.1%
        val mid = OpenWrtTool.flowPct(1000.0, 1000f)
        assertTrue("1 Mbps 应该画到 $mid", mid > 0.15f && mid < 0.45f)
        // 超出量程不越界
        assertEquals(1f, OpenWrtTool.flowPct(1e9, 1000f))
    }

    @Test
    fun `数字压进三格`() {
        assertEquals("0", Feed.compact(0.0))
        assertEquals("0.3", Feed.compact(0.3))
        assertEquals("9.5", Feed.compact(9.5))
        assertEquals("42", Feed.compact(42.4))
        // 三位以上只能取整，不能撑爆 17 点宽
        assertEquals("999", Feed.compact(4321.0))
        assertTrue(Feed.compact(12345.0).length <= 3)
        // 异常值不能画成数字
        assertEquals("--", Feed.compact(Double.NaN))
        // 百分比：两位以内带 %
        assertEquals("32%", Feed.percent(32.0))
        assertTrue(Feed.percent(100.0).length <= 3)
    }

    @Test
    fun `从带单位的字符串里抠出数值`() {
        // rig-exporter 的 rows 全是这种格式
        assertEquals(25.0, Feed.firstNumber("25.0 %")!!, 0.001)
        assertEquals(360.0, Feed.firstNumber("360 MHz")!!, 0.001)
        assertEquals(1.2, Feed.firstNumber("1.2 GB")!!, 0.001)
        assertEquals(-3.0, Feed.firstNumber("-3.0")!!, 0.001)
        assertNull(Feed.firstNumber("--"))
        assertNull(Feed.firstNumber(""))
    }

    @Test
    fun `指标选择会去重并截到上限`() {
        val known = listOf("a", "b", "c", "d")
        // 空串走默认值
        assertEquals(listOf("a", "b"), Feed.sanitize("", known, "a,b"))
        // 未知 id 丢掉，重复的只留一个
        assertEquals(listOf("a", "c"), Feed.sanitize("a,zz,c,a", known, "b"))
        // 超过上限的部分截断，顺序按用户给的来
        assertEquals(listOf("d", "c", "b"), Feed.sanitize("d,c,b,a", known, "a"))
        // 一个合法值都没有时退回默认
        assertEquals(listOf("a"), Feed.sanitize("zz,qq", known, "a"))
        // 默认值本身也会被筛
        assertEquals(emptyList<String>(), Feed.sanitize("", known, "zz"))
    }

    @Test
    fun `地址会补协议头并去尾斜杠`() {
        assertEquals("http://192.168.1.1:19999", Feed.normalizeBase("192.168.1.1:19999"))
        assertEquals("http://192.168.1.1:19999", Feed.normalizeBase("  http://192.168.1.1:19999/  "))
        assertEquals("https://a.b", Feed.normalizeBase("https://a.b/"))
        assertEquals("", Feed.normalizeBase("   "))
    }
}
