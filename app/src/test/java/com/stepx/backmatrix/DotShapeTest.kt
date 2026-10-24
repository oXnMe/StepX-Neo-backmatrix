package com.stepx.backmatrix

import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 面板形状的测试。
 *
 * PZ261A 的四个角不是直角：顶行和底行各少左右两个点，第 1 行和倒数第 2 行各少一个点。
 * 这是照着实物照片数出来的，一改就会连着影响所有工具的排版，所以用测试钉死。
 */
class DotShapeTest {

    @Test
    fun `四角是圆角，每行少几个点是固定的`() {
        // 顶行 / 底行：13 点
        assertEquals("顶行可用点数", 13, (0 until Dot.W).count { Dot.visible(it, 0) })
        assertEquals("底行可用点数", 13, (0 until Dot.W).count { Dot.visible(it, Dot.H - 1) })
        // 次行 / 倒数第二行：15 点
        assertEquals("第 1 行可用点数", 15, (0 until Dot.W).count { Dot.visible(it, 1) })
        assertEquals("倒数第 2 行可用点数", 15, (0 until Dot.W).count { Dot.visible(it, Dot.H - 2) })
        // 中间 19 行是完整的
        for (y in 2 until Dot.H - 2) {
            assertEquals("第 $y 行应该满格", Dot.W, (0 until Dot.W).count { Dot.visible(it, y) })
        }
    }

    @Test
    fun `越界一律不可见`() {
        assertFalse(Dot.visible(-1, 5))
        assertFalse(Dot.visible(Dot.W, 5))
        assertFalse(Dot.visible(5, -1))
        assertFalse(Dot.visible(5, Dot.H))
    }

    @Test
    fun `usableX 和 visible 说的是同一件事`() {
        for (y in 0 until Dot.H) {
            val r = Dot.usableX(y)
            for (x in 0 until Dot.W) {
                assertEquals("y=$y x=$x 两处判断不一致", Dot.visible(x, y), x in r)
            }
        }
    }

    @Test
    fun `画到角上的像素会被自动裁掉`() {
        // 不做任何避让，直接把整块面板点满
        val buf = Dot.blank()
        for (y in 0 until Dot.H) {
            for (x in 0 until Dot.W) Scene.put(buf, x, y, 255)
        }
        fun lit(x: Int, y: Int) = (buf[y * Dot.W + x].toInt() and 0xFF) > 0

        // 四个角各该有一个"缺角"的三角
        assertFalse("左上角该是空的", lit(0, 0))
        assertFalse("左上角该是空的", lit(1, 0))
        assertTrue("顶行第 2 列应该能亮", lit(2, 0))
        assertFalse("右上角该是空的", lit(Dot.W - 1, 0))
        assertFalse("左下角该是空的", lit(0, Dot.H - 1))
        assertFalse("右下角该是空的", lit(Dot.W - 1, Dot.H - 1))
        // 第 1 行只少一列
        assertFalse("第 1 行左端该空一格", lit(0, 1))
        assertTrue("第 1 行第 1 列应该能亮", lit(1, 1))
        // 角外其余全部点亮
        for (y in 0 until Dot.H) {
            for (x in 0 until Dot.W) {
                assertEquals("y=$y x=$x", Dot.visible(x, y), lit(x, y))
            }
        }
    }

    @Test
    fun `hLine 这种跨整行的原语也不会溢出到圆角`() {
        val buf = Dot.blank()
        Scene.hLine(buf, 0, Dot.W - 1, 0, 255)
        // 顶行横贯一整条线，只有中间 13 个点亮得起来
        val on = (0 until Dot.W).count { (buf[it].toInt() and 0xFF) > 0 }
        assertEquals(13, on)
    }
}
