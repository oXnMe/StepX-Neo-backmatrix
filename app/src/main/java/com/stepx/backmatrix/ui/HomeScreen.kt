package com.stepx.backmatrix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.core.Library
import com.stepx.backmatrix.player.MatrixPlayer

data class DeviceStatus(
    val model: String,
    val isTarget: Boolean,
    val hasService: Boolean,
    val info: String?,
    val error: String?,
    val backScreenSetting: String
)

@Composable
fun HomeScreen(
    state: MatrixPlayer.State,
    frame: ByteArray?,
    entries: List<Library.Entry>,
    thumbs: Map<String, ByteArray>,
    ready: Boolean,
    idleFrame: ByteArray?,
    converting: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onRotate: () -> Unit,
    onBrightness: (Float) -> Unit,
    onPickFile: () -> Unit,
    onPlayEntry: (Library.Entry) -> Unit,
    onDeleteEntry: (Library.Entry) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // 标题固定在顶部：翻动画库时「播放中 / 就绪」这个胶囊要一直看得见
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 22.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text("点阵背屏", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                Spacer(Modifier.height(3.dp))
                Text("PZ261A · 17×23 灰度点阵", color = Palette.dim, fontSize = 12.sp)
            }
            if (state.playing) Pill("播放中", Palette.ok) else Pill(if (ready) "就绪" else "未连接", if (ready) Palette.muted else Palette.warn)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // 预览 + 播放控制
            Panel {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(width = 128.dp, height = 173.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0C0C10))
                            .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
                    ) {
                        DotGrid(frame ?: idleFrame, Modifier.fillMaxWidth().height(173.dp), padding = 8f)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.title.ifEmpty { "还没有在播放" },
                            color = Palette.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        if (state.live) {
                            Text(
                                "实时工具 · 数据驱动",
                                color = Palette.dim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                            Text(
                                state.status.ifEmpty { "等待数据…" },
                                color = Palette.dim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        } else if (state.frameCount > 0) {
                            Text(
                                Dot.describe(state.fps, state.frameCount),
                                color = Palette.dim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                            Text(
                                "第 ${state.frameIndex + 1} 帧 · ${if (state.rotationTurns == 0) "竖屏" else "旋转 ${state.rotationTurns * 90}°"}",
                                color = Palette.dim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        } else {
                            Text(
                                "从下面的动画库选一个，或者自己导入 GIF / MP4。",
                                color = Palette.dim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        if (state.playing) "暂停" else "播放",
                        modifier = Modifier.weight(1f),
                        enabled = state.frameCount > 0 || entries.isNotEmpty(),
                        glyph = if (state.playing) Glyph.PAUSE else Glyph.PLAY
                    ) { onPlayPause() }
                    GhostButton(
                        "停止",
                        modifier = Modifier.weight(1f),
                        enabled = state.frameCount > 0,
                        glyph = Glyph.STOP
                    ) { onStop() }
                }
                Spacer(Modifier.height(8.dp))
                GhostButton("旋转 90°", modifier = Modifier.fillMaxWidth(), glyph = Glyph.ROTATE) { onRotate() }
                Spacer(Modifier.height(2.dp))
                LabeledSlider(
                    "亮度",
                    state.brightness,
                    "${(state.brightness * 100).toInt()}%",
                    0.2f..1f
                ) { onBrightness(it) }
                if (state.error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(state.error, color = Palette.danger, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }

            // 导入
            Panel {
                PrimaryButton(
                    if (converting) "正在转换…" else "导入 GIF / MP4",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !converting,
                    glyph = Glyph.UPLOAD
                ) { onPickFile() }
                Spacer(Modifier.height(8.dp))
                Text(
                    "选好文件后可以调裁切、反相、黑场/白场、伽马、色阶和抖动，实时看效果再保存。",
                    color = Palette.dim,
                    fontSize = 11.sp
                )
            }

            // 动画库
            SectionTitle("动画库", "${entries.size} 个")
            entries.forEach { entry ->
                LibraryRow(
                    entry = entry,
                    thumb = thumbs[entry.id],
                    playing = state.playing && state.title == entry.name,
                    onPlay = { onPlayEntry(entry) },
                    onDelete = { onDeleteEntry(entry) }
                )
            }

            // 设置引导不在这里了，见底部「设置」页
            Text(
                "背屏开关、后台放行和设备诊断都在底部的「设置」里。",
                color = Palette.dim,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun LibraryRow(
    entry: Library.Entry,
    thumb: ByteArray?,
    playing: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.surface)
            .border(1.dp, if (playing) Palette.accent.copy(alpha = 0.5f) else Palette.border, RoundedCornerShape(14.dp))
            .clickable { onPlay() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0C0C10))
        ) {
            DotGrid(thumb, Modifier.fillMaxWidth().height(54.dp), padding = 3f)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    color = Palette.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.builtin) {
                    Spacer(Modifier.width(6.dp))
                    Pill("内置", Palette.accent)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(Dot.describe(entry.fps, entry.frameCount), color = Palette.dim, fontSize = 11.sp)
        }
        if (!entry.builtin) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.surfaceAlt)
                    .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) { GlyphIcon(Glyph.TRASH, Palette.muted, 15.dp) }
        }
    }
}
