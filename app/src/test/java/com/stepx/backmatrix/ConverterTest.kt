package com.stepx.backmatrix

import com.stepx.backmatrix.core.Converter
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.Fit
import com.stepx.backmatrix.core.ToneSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConverterTest {

    private fun solid(color: Int, w: Int, h: Int) = IntArray(w * h) { color }

    @Test
    fun `always produces one 17x23 frame`() {
        val out = Converter.convert(solid(0xFFFFFFFF.toInt(), 40, 30), 40, 30, ToneSettings())
        assertEquals(Dot.PIXELS, out.size)
    }

    @Test
    fun `inverted white becomes empty`() {
        val out = Converter.convert(solid(0xFFFFFFFF.toInt(), 17, 23), 17, 23, ToneSettings.lineArt())
        assertTrue(out.all { (it.toInt() and 0xFF) == 0 })
    }

    @Test
    fun `inverted black becomes full brightness`() {
        val out = Converter.convert(solid(0xFF000000.toInt(), 17, 23), 17, 23, ToneSettings.lineArt())
        assertTrue(out.all { (it.toInt() and 0xFF) == 255 })
    }

    @Test
    fun `transparent pixels count as background`() {
        val out = Converter.convert(solid(0x00000000, 17, 23), 17, 23, ToneSettings.lineArt())
        assertTrue(out.all { (it.toInt() and 0xFF) == 0 })
    }

    @Test
    fun `contain letterboxes a wide source`() {
        val out = Converter.convert(
            solid(0xFF000000.toInt(), 100, 10), 100, 10,
            ToneSettings.lineArt().copy(fit = Fit.CONTAIN)
        )
        // 顶部两行必然是留白
        for (y in 0 until 2) for (x in 0 until Dot.W) {
            assertEquals(0, out[y * Dot.W + x].toInt() and 0xFF)
        }
        // 中间应该有亮起来的部分
        assertTrue(out.any { (it.toInt() and 0xFF) > 100 })
    }

    @Test
    fun `levels quantise the output`() {
        val gradient = IntArray(17 * 23) { i ->
            val v = (i * 255 / (17 * 23 - 1))
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        val out = Converter.convert(gradient, 17, 23, ToneSettings(invert = false, white = 1f, black = 0f, levels = 4))
        val allowed = setOf(0, 85, 170, 255)
        assertTrue(out.all { (it.toInt() and 0xFF) in allowed })
    }
}
