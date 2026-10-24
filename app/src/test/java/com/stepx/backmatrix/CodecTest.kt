package com.stepx.backmatrix

import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.DotAnimation
import com.stepx.backmatrix.core.GifDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

class CodecTest {

    private val onePixelGif = Base64.getDecoder().decode(
        "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
    )

    @Test
    fun `gif decoder reads a minimal gif`() {
        var frames = 0
        var w = 0
        var h = 0
        GifDecoder.decode(ByteArrayInputStream(onePixelGif)) { _, width, height, _ ->
            frames++; w = width; h = height; true
        }
        assertEquals(1, frames)
        assertEquals(1, w)
        assertEquals(1, h)
    }

    @Test
    fun `gif decoder rejects non gif data`() {
        var failed = false
        try {
            GifDecoder.decode(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))) { _, _, _, _ -> true }
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `bsa round trip keeps frames and metadata`() {
        val frames = listOf(ByteArray(Dot.PIXELS) { 1.toByte() }, ByteArray(Dot.PIXELS) { 255.toByte() })
        val anim = DotAnimation("测试", 12, true, frames)
        val bytes = DotAnimation.toBytes(anim)
        val back = DotAnimation.decode(ByteArrayInputStream(bytes))
        assertEquals("测试", back.name)
        assertEquals(12, back.fps)
        assertTrue(back.loop)
        assertEquals(2, back.frameCount)
        assertEquals(255, back.frames[1][0].toInt() and 0xFF)
    }

    @Test
    fun `ping pong doubles the loop without repeating the seam`() {
        val frames = (0 until 3).map { i -> ByteArray(Dot.PIXELS) { i.toByte() } }
        val anim = DotAnimation("x", 8, true, frames).pingPong()
        assertEquals(4, anim.frameCount)
        assertEquals(1, anim.frames[3][0].toInt() and 0xFF)
    }

    @Test
    fun `four quarter turns return the original inside the safe area`() {
        // 旋转只在中央 17x17 安全区内进行，所以测试图案也只画在安全区里
        val scene = ByteArray(Dot.PIXELS)
        for (y in Dot.SAFE_TOP until Dot.SAFE_TOP + Dot.SAFE_ROWS) {
            for (x in 0 until Dot.W) scene[y * Dot.W + x] = ((x * 7 + y * 13) % 251).toByte()
        }
        var rotated = scene
        repeat(4) { rotated = Dot.rotate(rotated, 1) }
        assertTrue(scene.contentEquals(rotated))
    }
}
