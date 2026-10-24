package com.stepx.backmatrix.core

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * 打开一个 GIF/MP4，抽出若干"取景采样帧"供转换界面实时预览。
 *
 * 采样帧会被缩到 320px 宽以内：预览只有 391 个点，用原图纯属浪费内存。
 */
data class SourcePreview(
    val uri: Uri,
    val isGif: Boolean,
    val displayName: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val durationMs: Long,
    val samples: List<IntArray>,
    val sampleWidth: Int,
    val sampleHeight: Int
) {
    fun infoText(): String {
        val megapixel = width.toLong() * height
        val res = if (megapixel >= 1_000_000) String.format("%.1fMP", megapixel / 1_000_000.0) else "${width}×$height"
        val dur = if (durationMs > 0) String.format("%.1fs", durationMs / 1000.0) else "—"
        return "$res · $frameCount 帧 · $dur"
    }
}

object SourceLoader {

    private const val MAX_SAMPLE_WIDTH = 320
    private const val SAMPLES = 8

    fun isGif(context: Context, uri: Uri, name: String): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime != null) return mime == "image/gif"
        return name.endsWith(".gif", ignoreCase = true)
    }

    fun load(context: Context, uri: Uri, name: String): SourcePreview {
        return if (isGif(context, uri, name)) loadGif(context, uri, name) else loadVideo(context, uri, name)
    }

    private fun open(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: error("无法读取这个文件")

    private fun loadGif(context: Context, uri: Uri, name: String): SourcePreview {
        // 第一遍：只数帧与总时长
        var count = 0
        var totalMs = 0L
        var w = 0
        var h = 0
        open(context, uri).use { stream ->
            GifDecoder.decode(stream) { _, width, height, delay ->
                w = width; h = height; count++; totalMs += delay
                count < 5000
            }
        }
        require(count > 0) { "这个 GIF 没有可用的帧" }

        // 第二遍：按时间均匀取 8 帧
        val targets = (0 until SAMPLES).map { it * (totalMs - 1).coerceAtLeast(0) / (SAMPLES - 1) }
        val samples = ArrayList<IntArray>()
        var sw = 0
        var sh = 0
        var time = 0L
        var next = 0
        open(context, uri).use { stream ->
            GifDecoder.decode(stream) { pixels, width, height, delay ->
                time += delay
                while (next < targets.size && time >= targets[next]) {
                    val scaled = downscale(pixels, width, height)
                    if (samples.isEmpty()) { sw = scaled.first; sh = scaled.second }
                    samples.add(scaled.third)
                    next++
                }
                next < targets.size
            }
        }
        return SourcePreview(uri, true, name, w, h, count, totalMs, samples, sw, sh)
    }

    private fun loadVideo(context: Context, uri: Uri, name: String): SourcePreview {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            require(duration > 0) { "读不出视频时长，可能不是受支持的视频" }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val samples = ArrayList<IntArray>()
            var sw = 0
            var sh = 0
            var width = 0
            var height = 0
            for (i in 0 until SAMPLES) {
                val tUs = duration * 1000L * i / (SAMPLES - 1).coerceAtLeast(1)
                val frame = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val rotated = rotate(frame, rotation)
                if (width == 0) { width = rotated.width; height = rotated.height }
                val pixels = IntArray(rotated.width * rotated.height)
                rotated.getPixels(pixels, 0, rotated.width, 0, 0, rotated.width, rotated.height)
                val scaled = downscale(pixels, rotated.width, rotated.height)
                if (samples.isEmpty()) { sw = scaled.first; sh = scaled.second }
                samples.add(scaled.third)
                if (rotated !== frame) rotated.recycle()
                frame.recycle()
            }
            // 视频帧数按目标 12fps 估算，只作展示
            val estimatedFrames = (duration / 1000f * 12).toInt().coerceAtLeast(1)
            return SourcePreview(uri, false, name, width, height, estimatedFrames, duration, samples, sw, sh)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** 简单的整数倍面积平均缩放，返回 (宽, 高, 像素)。 */
    private fun downscale(src: IntArray, w: Int, h: Int): Triple<Int, Int, IntArray> {
        if (w <= MAX_SAMPLE_WIDTH) return Triple(w, h, src)
        val factor = (w + MAX_SAMPLE_WIDTH - 1) / MAX_SAMPLE_WIDTH
        val nw = w / factor
        val nh = h / factor
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            for (x in 0 until nw) {
                var r = 0; var g = 0; var b = 0; var a = 0; var n = 0
                for (dy in 0 until factor) {
                    val sy = y * factor + dy
                    if (sy >= h) continue
                    val row = sy * w
                    for (dx in 0 until factor) {
                        val sx = x * factor + dx
                        if (sx >= w) continue
                        val p = src[row + sx]
                        a += (p ushr 24) and 0xFF
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
                if (n == 0) continue
                out[y * nw + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return Triple(nw, nh, out)
    }
}
