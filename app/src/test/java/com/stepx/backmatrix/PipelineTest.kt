package com.stepx.backmatrix

import com.stepx.backmatrix.core.Converter
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.DotAnimation
import com.stepx.backmatrix.core.GifDecoder
import com.stepx.backmatrix.core.ToneSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 端到端验证：用真实的示例 GIF 跑一遍 Kotlin 的解码 + 转换管线，
 * 并把结果画成 PNG，方便和 tools/convert_demo.py 的产物肉眼对照。
 *
 * 示例素材不入库，本地没有这个 GIF 时这条用例直接跳过（CI 上就是这样）。
 */
class PipelineTest {

    private val gif = File("../assets-src/demo-basketball.gif")
    private val asset = File("src/main/assets/demo_basketball.bsa")
    private val outDir = File("build/preview").apply { mkdirs() }

    @Test
    fun `decodes the bundled gif and converts real frames`() {
        if (!gif.exists()) return
        var frames = 0
        var totalMs = 0
        val converted = ArrayList<ByteArray>()
        gif.inputStream().use { stream ->
            GifDecoder.decode(stream) { pixels, w, h, delay ->
                frames++
                totalMs += delay
                if (frames % 26 == 1) {
                    converted.add(Converter.convert(pixels, w, h, ToneSettings.lineArt()))
                }
                true
            }
        }
        println("decoded frames=$frames totalMs=$totalMs sampled=${converted.size}")
        assertTrue("应该解出几百帧", frames > 300)
        assertTrue("总时长应该超过 10 秒", totalMs > 10_000)
        assertTrue("转换后的帧必须有点亮起来", converted.any { f -> f.any { (it.toInt() and 0xFF) > 60 } })
        writeSheet(converted, File(outDir, "kotlin-preview.ppm"))
    }

    @Test
    fun `bundled asset decodes to the expected animation`() {
        if (!asset.exists()) return
        val anim = asset.inputStream().use { DotAnimation.decode(it) }
        assertEquals(12, anim.fps)
        assertEquals(334, anim.frameCount)
        assertTrue(anim.name.contains("内置"))
        assertTrue(anim.frames.all { it.size == Dot.PIXELS })
        writeSheet(anim.frames.filterIndexed { i, _ -> i % 28 == 0 }, File(outDir, "asset-preview.ppm"))
    }

    /** 写成 PPM：单元测试跑在 android.jar 上，用不了 javax.imageio。 */
    private fun writeSheet(frames: List<ByteArray>, file: File, scale: Int = 10) {
        if (frames.isEmpty()) return
        val w = Dot.W * scale * frames.size
        val h = Dot.H * scale
        val pixels = ByteArray(w * h * 3)
        frames.forEachIndexed { index, frame ->
            for (y in 0 until Dot.H) for (x in 0 until Dot.W) {
                val v = frame[y * Dot.W + x].toInt() and 0xFF
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    val px = ((index * Dot.W * scale) + x * scale + dx)
                    val py = y * scale + dy
                    val off = (py * w + px) * 3
                    pixels[off] = v.toByte()
                    pixels[off + 1] = v.toByte()
                    pixels[off + 2] = v.toByte()
                }
            }
        }
        file.outputStream().use { out ->
            out.write("P6\n$w $h\n255\n".toByteArray(Charsets.US_ASCII))
            out.write(pixels)
        }
        println("wrote ${file.absolutePath}")
    }
}
