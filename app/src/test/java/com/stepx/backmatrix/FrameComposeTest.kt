package com.stepx.backmatrix

import com.stepx.backmatrix.core.Dot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * 帧合成的别名契约。
 *
 * 实时工具每帧复用同一块 391 字节缓冲，如果合成这一层直通返回入参，
 * 播放器保存的"上一帧"就会指向正在被改写的同一块内存，内容比较永远相等，
 * 表现为背屏停在第一帧、界面预览却正常在动。这组测试是那个问题的护栏。
 */
class FrameComposeTest {

    @Test
    fun `直通缩放与不旋转时也交出独立数组`() {
        val buffer = ByteArray(Dot.PIXELS) { 200.toByte() }
        val out = Dot.compose(buffer, 1f, 0)
        assertNotSame(buffer, out)
        assertArrayEquals(buffer, out)
    }

    @Test
    fun `源缓冲被覆写后已合成的帧不受影响`() {
        val buffer = ByteArray(Dot.PIXELS) { 120.toByte() }
        val first = Dot.compose(buffer, 1f, 0)
        buffer.fill(0)
        assertArrayEquals(ByteArray(Dot.PIXELS) { 120.toByte() }, first)
    }

    @Test
    fun `复用缓冲画出的两帧仍然互不相同`() {
        // 直接复现线上现象：修复前这里是同一块内存，contentEquals 恒为 true
        val buffer = ByteArray(Dot.PIXELS)
        buffer[0] = 10
        val first = Dot.compose(buffer, 1f, 0)
        buffer[0] = 90
        val second = Dot.compose(buffer, 1f, 0)
        assertFalse(second.contentEquals(first))
    }

    @Test
    fun `合成等价于先缩放再旋转`() {
        val raw = ByteArray(Dot.PIXELS) { ((it * 7) % 256).toByte() }
        for (turns in 0..3) {
            assertArrayEquals(
                Dot.rotate(Dot.scale(raw, 0.5f), turns),
                Dot.compose(raw, 0.5f, turns)
            )
            assertArrayEquals(
                Dot.rotate(raw, turns),
                Dot.compose(raw, 1f, turns)
            )
        }
    }

    @Test
    fun `旋转结果不与入参共享内存`() {
        val raw = ByteArray(Dot.PIXELS) { 64.toByte() }
        for (turns in 0..3) {
            assertNotSame(raw, Dot.compose(raw, 1f, turns))
        }
    }
}
