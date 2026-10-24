package com.stepx.backmatrix

import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.GlyphArt
import com.stepx.backmatrix.core.PixelText
import com.stepx.backmatrix.core.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 绘图原语是 10 个工具的共同底座，越界和叠加语义必须先钉死。 */
class SceneTest {

    private fun blank() = ByteArray(Dot.PIXELS)

    private fun at(b: ByteArray, x: Int, y: Int) = b[y * Dot.W + x].toInt() and 0xFF

    @Test
    fun `put 会忽略越界坐标而不是抛异常`() {
        val b = blank()
        Scene.put(b, -1, 0, 200)
        Scene.put(b, 0, -1, 200)
        Scene.put(b, Dot.W, 0, 200)
        Scene.put(b, 0, Dot.H, 200)
        assertTrue(b.all { it == 0.toByte() })
    }

    @Test
    fun `put 会把写入值夹到 0 到 255`() {
        val b = blank()
        Scene.put(b, 3, 3, 999)
        assertEquals(255, at(b, 3, 3))
        Scene.put(b, 4, 3, -5)
        assertEquals(0, at(b, 4, 3))
    }

    @Test
    fun `max 只提亮不压暗`() {
        val b = blank()
        Scene.put(b, 2, 2, 180)
        Scene.max(b, 2, 2, 90)
        assertEquals(180, at(b, 2, 2))
        Scene.max(b, 2, 2, 240)
        assertEquals(240, at(b, 2, 2))
    }

    @Test
    fun `add 会饱和到 255`() {
        val b = blank()
        Scene.add(b, 5, 5, 200)
        Scene.add(b, 5, 5, 200)
        assertEquals(255, at(b, 5, 5))
    }

    @Test
    fun `hLine 与 vLine 按端点顺序无关`() {
        val a = blank()
        Scene.hLine(a, 12, 4, 7, 255)
        val c = blank()
        Scene.hLine(c, 4, 12, 7, 255)
        assertTrue(a.contentEquals(c))
        assertEquals(255, at(a, 4, 7))
        assertEquals(255, at(a, 12, 7))
        assertEquals(0, at(a, 3, 7))
    }

    @Test
    fun `实心椭圆会填满内部而空心只留边缘`() {
        val fill = blank()
        Scene.ellipse(fill, 8f, 11f, 3f, 3f, 255, fill = true)
        assertEquals(255, at(fill, 8, 11))

        val ring = blank()
        Scene.ring(ring, 8f, 11f, 3f, 255)
        assertEquals(0, at(ring, 8, 11))
        assertTrue(ring.any { (it.toInt() and 0xFF) > 0 })
    }

    @Test
    fun `箭头 0 度指向正上方`() {
        val b = blank()
        Scene.arrow(b, 8f, 11.5f, 0f, 7f, 255)
        // 箭尖落在中心正上方
        assertTrue(at(b, 8, 11 - 7) > 0 || at(b, 8, 11 - 6) > 0)
        // 中心上方而不是下方
        assertTrue(at(b, 8, 11) + at(b, 8, 12) > 0)
        assertEquals(0, at(b, 8, 20))
    }

    @Test
    fun `箭头 90 度指向右侧`() {
        val b = blank()
        Scene.arrow(b, 8f, 11f, (Math.PI / 2).toFloat(), 6f, 255)
        assertTrue((9..16).any { at(b, it, 11) > 0 })
    }

    @Test
    fun `字体宽度按 3 加 1 间隔累加`() {
        assertEquals(3, PixelText.width("A"))
        assertEquals(7, PixelText.width("AB"))
        assertEquals(11, PixelText.width("ABC"))
        assertEquals(0, PixelText.width(""))
        assertEquals(6, PixelText.width("A", 2))
        assertEquals(14, PixelText.width("AB", 2))
    }

    @Test
    fun `所有字形都能画出像素且不越界`() {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ .!:-/?+%>"
        val b = blank()
        for (c in chars) {
            PixelText.draw(b, c.toString(), 0, 0, 1, 255)
            if (c != ' ') {
                assertTrue("字形 $c 没有画出任何像素", b.any { it != 0.toByte() })
            }
            java.util.Arrays.fill(b, 0)
        }
    }

    @Test
    fun `文字可以挖洞`() {
        val b = ByteArray(Dot.PIXELS) { 200.toByte() }
        PixelText.draw(b, "8", 2, 2, 1, 0, erase = true)
        assertTrue(b.any { it == 0.toByte() })
    }

    @Test
    fun `居中绘制左右留白相差不超过 1 像素`() {
        val b = blank()
        PixelText.drawCentered(b, "12", 5, 2, 255)
        var minX = Dot.W
        var maxX = -1
        for (y in 0 until Dot.H) for (x in 0 until Dot.W) {
            if (at(b, x, y) > 0) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
            }
        }
        val left = minX
        val right = Dot.W - 1 - maxX
        assertTrue("左右留白差太多：$left / $right", kotlin.math.abs(left - right) <= 1)
    }

    @Test
    fun `心形有裂口时中心会断开`() {
        val solid = blank()
        GlyphArt.heart(solid, 255, brokenGap = 0)
        val broken = blank()
        GlyphArt.heart(broken, 255, brokenGap = 4)
        val solidCount = solid.count { it != 0.toByte() }
        val brokenCount = broken.count { it != 0.toByte() }
        assertTrue("裂开后像素应该更少：$brokenCount vs $solidCount", brokenCount < solidCount)
    }

    @Test
    fun `M N W 三个字母画出来互不相同`() {
        // N 和 W 曾经是同一个字形，背屏上分不出，这里钉死它们必须有区别
        val glyphs = listOf("M", "N", "W").map { ch ->
            blank().also { PixelText.draw(it, ch, 2, 2, 1, 255) }
        }
        assertTrue("M 和 N 画出来一样", !glyphs[0].contentEquals(glyphs[1]))
        assertTrue("N 和 W 画出来一样", !glyphs[1].contentEquals(glyphs[2]))
        assertTrue("M 和 W 画出来一样", !glyphs[0].contentEquals(glyphs[2]))
    }
}
