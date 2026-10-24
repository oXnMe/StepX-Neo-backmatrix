package com.stepx.backmatrix.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.InputStream

/** GIF / MP4 → 点阵动画。转换在主线程之外执行，进度通过回调上报。 */
object Importer {

    const val MAX_FRAMES = 600

    class Result(val animation: DotAnimation, val sourceWidth: Int, val sourceHeight: Int, val note: String?)

    /**
     * @param onProgress 0..1 的进度
     */
    fun fromGif(
        input: InputStream,
        settings: ToneSettings,
        name: String,
        onProgress: (Float, String) -> Unit
    ): Result {
        val interval = 1000f / settings.fps.coerceIn(1, 30)
        val frames = ArrayList<ByteArray>()
        var timeMs = 0f
        var nextDue = 0f
        var sw = 0
        var sh = 0
        var truncated = false

        GifDecoder.decode(input) { pixels, w, h, delay ->
            sw = w; sh = h
            timeMs += delay
            if (timeMs >= nextDue) {
                frames.add(Converter.convert(pixels, w, h, settings))
                nextDue += interval
                onProgress((frames.size / 400f).coerceIn(0f, 0.99f), "已转换 ${frames.size} 帧")
                if (frames.size >= MAX_FRAMES) { truncated = true; return@decode false }
            }
            true
        }

        require(frames.isNotEmpty()) { "这个 GIF 里没有可用的画面" }
        var anim = DotAnimation(name, settings.fps, settings.loop, frames)
        if (settings.pingPong) anim = anim.pingPong()
        return Result(
            anim, sw, sh,
            if (truncated) "超过 $MAX_FRAMES 帧，只保留了前 ${MAX_FRAMES} 帧" else null
        )
    }

    fun fromVideo(
        context: Context,
        uri: Uri,
        settings: ToneSettings,
        name: String,
        onProgress: (Float, String) -> Unit
    ): Result {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            require(durationMs > 0) { "读不出视频时长，可能不是受支持的视频文件" }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            val interval = 1000f / settings.fps.coerceIn(1, 30)
            val count = ((durationMs / interval).toInt() + 1).coerceIn(1, MAX_FRAMES)
            val frames = ArrayList<ByteArray>(count)
            var sw = 0
            var sh = 0

            for (i in 0 until count) {
                val tUs = (i * interval * 1000f).toLong()
                val raw = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val bitmap = rotate(raw, rotation)
                if (sw == 0) { sw = bitmap.width; sh = bitmap.height }
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                frames.add(Converter.convert(pixels, bitmap.width, bitmap.height, settings))
                if (bitmap !== raw) bitmap.recycle()
                raw.recycle()
                onProgress((i + 1).toFloat() / count, "已采样 ${i + 1}/$count 帧")
            }

            require(frames.isNotEmpty()) { "没能从视频里取到画面" }
            var anim = DotAnimation(name, settings.fps, settings.loop, frames)
            if (settings.pingPong) anim = anim.pingPong()
            val note = when {
                durationMs / interval > MAX_FRAMES -> "视频较长，只取了前 ${count} 帧（约 ${count * 1000 / settings.fps / 1000} 秒）"
                else -> null
            }
            return Result(anim, sw, sh, note)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
