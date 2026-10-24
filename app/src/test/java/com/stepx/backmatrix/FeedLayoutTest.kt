package com.stepx.backmatrix

import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.PixelText
import com.stepx.backmatrix.core.Scene
import com.stepx.backmatrix.tools.Band
import com.stepx.backmatrix.tools.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指标条的排版测试。
 *
 * 17×23 上排版是像素级的活，看截图像素点分不清哪一行是谁画的，
 * 所以这里直接把 391 个字节渲染成 ASCII 打出来（跑测试时能看到），再对结构做断言。
 */
class FeedLayoutTest {

    private fun render(bands: List<Band>, top: Int, bottom: Int): ByteArray {
        val buf = Dot.blank()
        Feed.drawBands(buf, bands, top, bottom)
        return buf
    }

    private fun ascii(buf: ByteArray): String {
        val sb = StringBuilder()
        for (y in 0 until Dot.H) {
            for (x in 0 until Dot.W) {
                val v = buf[y * Dot.W + x].toInt() and 0xFF
                sb.append(if (v < 20) '.' else if (v < 90) ':' else if (v < 180) '+' else '#')
            }
            sb.append('|').append(y).append('\n')
        }
        return sb.toString()
    }

    private fun lit(buf: ByteArray, x: Int, y: Int, at: Int = 60) =
        (buf[y * Dot.W + x].toInt() and 0xFF) >= at

    /**
     * 在 x=0 那列里找某个字母画在第几行；找不到返回 -1。
     *
     * 参考字形不能画在第 0 行画：那里是圆角，Scene 会把 x=0/1 裁掉，
     * 拿来比对的"标准答案"自己就是残缺的，结果永远对不上。
     */
    private fun glyphRow(buf: ByteArray, c: Char): Int {
        val g = Dot.blank()
        val oy = 6
        PixelText.draw(g, c.toString(), 0, oy, 1, 255)
        for (y in 0..Dot.H - 5) {
            var ok = true
            for (dx in 0 until 3) {
                for (dy in 0 until 5) {
                    if (lit(g, dx, oy + dy) != lit(buf, dx, y + dy)) { ok = false }
                }
            }
            if (ok) return y
        }
        return -1
    }

    @Test
    fun `三条指标都画出来了并且从上往下排`() {
        // 跑满上限时最挤的情况：5..23 一共 18 行分给三个指标
        val bands = listOf(
            Band('D', "0.1", 0.05f),
            Band('U', "0.2", 0.08f),
            Band('C', "8.1", 0.08f)
        )
        val buf = render(bands, 5, Dot.H)
        println(ascii(buf))

        val rows = listOf('D', 'U', 'C').map { glyphRow(buf, it) }
        assertTrue("有标签没画出来：$rows", rows.all { it >= 0 })
        assertEquals("两个标签重叠了：$rows", 3, rows.distinct().size)
        assertEquals("标签顺序反了：$rows", rows.sorted(), rows)
        // 字高 5 行，两个标签至少隔 5 行，否则数字会叠在一起
        rows.zipWithNext().forEach { (a, b) ->
            assertTrue("标签 $a 和 $b 挨太近", b - a >= 5)
        }
        // 不能画到区间外面去
        assertTrue("画到顶部之外了", rows.first() >= 5)
    }

    @Test
    fun `指标少的时候条会加粗`() {
        // 条的行数按剩余高度决定：h>=14 三行，h>=9 两行，h>=6 一行，再窄就不画
        fun barRowsOf(n: Int): Int {
            val bands = List(n) { i -> Band(('A' + i), "5", 0.5f) }
            val buf = render(bands, 5, Dot.H)
            val h = (Dot.H - 5) / n
            // 条的底轨（亮度 18）横贯整行，数字行不会横贯，靠这点认出条占了几行。
            // "横贯"只能要求这一行所有能亮的点都亮——靠近圆角的那行天然少几个点
            return (0 until h).count { dy ->
                val y = 5 + dy
                Dot.usableX(y).all { x -> lit(buf, x, y, 12) }
            }
        }
        assertEquals("单个指标条该是 3 行", 3, barRowsOf(1))
        assertEquals("两个指标条该是 2 行", 2, barRowsOf(2))
        assertEquals("三个指标条该是 1 行", 1, barRowsOf(3))
    }

    @Test
    fun `条的长度跟着百分比走`() {
        val full = render(listOf(Band('D', "9", 1f)), 5, Dot.H)
        val low = render(listOf(Band('D', "9", 0.1f)), 5, Dot.H)
        val usable = Dot.usableX(Dot.H - 1)
        fun barWidth(buf: ByteArray) = usable.count { lit(buf, it, Dot.H - 1, 150) }
        assertTrue("满格应该比 10% 长", barWidth(full) > barWidth(low))
        assertTrue("满格应该铺满整行", barWidth(full) >= usable.count() - 1)
    }

    @Test
    fun `数字再长也不会顶出右边`() {
        // 数字从 x=4 起画，最长的三字符宽度必须留在 17 点以内
        listOf("0", "9.9", "999", "32%", "100").forEach { s ->
            assertTrue("$s 太宽了", 4 + PixelText.width(s, 1) <= Dot.W)
        }
    }

    @Test
    fun `区间窄到只剩一行字，再窄就整个跳过`() {
        // 4 行连一行 5 点高的字都放不下，宁可空着也不要糊成一团
        val tiny = render(listOf(Band('D', "1", 0.5f)), 10, 14)
        assertTrue("4 行不该画任何东西", tiny.all { (it.toInt() and 0xFF) == 0 })

        // 5 行刚好一个字高：数字照画，但不画条——条会压在这个字的最后一行上
        val tight = render(listOf(Band('D', "1", 0.5f)), 10, 15)
        assertEquals("区间够画一个字时就该画出来", 10, glyphRow(tight, 'D'))
        for (y in 15 until Dot.H) {
            assertTrue("画到区间外面了：第 $y 行", (0 until Dot.W).none { x -> lit(tight, x, y) })
        }
    }

    @Test
    fun `空列表不改变画面`() {
        val buf = Dot.blank()
        Scene.clear(buf, 3)
        Feed.drawBands(buf, emptyList(), 0, Dot.H)
        assertTrue(buf.all { (it.toInt() and 0xFF) == 3 })
    }

    /** 画面里亮起来的列的左右边界。 */
    private fun litSpan(buf: ByteArray): IntRange? {
        val cols = (0 until Dot.W).filter { x -> (0 until Dot.H).any { y -> lit(buf, x, y) } }
        return if (cols.isEmpty()) null else cols.first()..cols.last()
    }

    @Test
    fun `FPS 三位数用大号字也放得下`() {
        // 高刷屏上 165 是常态。3×5 字体放大两倍时三位要 22 点，比整块面板还宽，
        // 所以大号字单独做了一套 4×9：三位连字距 14 点，和两位的 scale=2 一样宽
        val buf = Dot.blank()
        val h = Feed.drawHero(buf, "165", true, 255)
        println(ascii(buf))

        assertEquals("三位大号字应该正好 14 点", 14, PixelText.bigNumWidth("165"))
        // 返回值是"下一块内容从哪行开始"：起笔第 2 行 + 字高 9 行
        assertEquals(11, h)
        val span = litSpan(buf)!!
        assertTrue("数字侵入了箭头的位置：$span", span.last <= Dot.W - 4)
        // 第 0/1 行有四角圆角，大号数字从第 2 行起笔，顶上两行必须全空
        for (y in 0 until 2) {
            assertTrue("大号数字画到圆角里了：第 $y 行", (0 until Dot.W).none { x -> lit(buf, x, y) })
        }
    }

    @Test
    fun `FPS 两位大号字不碰箭头`() {
        val buf = Dot.blank()
        val h = Feed.drawHero(buf, "60", true, 255)
        println(ascii(buf))
        assertEquals(11, h)
        val span = litSpan(buf)!!
        assertEquals("两位大号字应该占 9 点", 9, PixelText.bigNumWidth("60"))
        // 居中是在"扣掉右边三列"之后的 14 点里做的，所以起笔在 x=2
        assertEquals("起笔位置不对", 2, span.first)
        assertTrue("数字侵入了箭头的位置：$span", span.last <= Dot.W - 4)
    }

    @Test
    fun `指标多到三个时 FPS 退回小号一行`() {
        // 三个指标时给 FPS 只剩 11 行，4×9 大字加不了指标条，退回 3×5 一行
        val buf = Dot.blank()
        val h = Feed.drawHero(buf, "165", false, 255)
        println(ascii(buf))
        assertEquals(5, h)
        // 小字从 y=0 起笔，但横向被夹到 x>=2，正好避开顶行的圆角
        assertTrue("小字顶到圆角了", (0 until Dot.W).none { x -> x < 2 && lit(buf, x, 0) })
        assertTrue("小字侵入了箭头的位置", litSpan(buf)!!.last <= Dot.W - 4)
    }

    @Test
    fun `没有游戏时画两个短横也放得下`() {
        val buf = Dot.blank()
        val h = Feed.drawHero(buf, "--", true, 150)
        assertEquals(11, h)
        assertTrue(litSpan(buf)!!.last <= Dot.W - 4)
        // 两个短横也不能画到圆角那两行里
        for (y in 0 until 2) {
            assertTrue("短横画到圆角里了：第 $y 行", (0 until Dot.W).none { x -> lit(buf, x, y) })
        }
    }
}
