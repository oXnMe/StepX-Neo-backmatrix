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
import androidx.compose.material3.LinearProgressIndicator
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
import com.stepx.backmatrix.core.Fit
import com.stepx.backmatrix.core.ToneSettings

@Composable
fun ConvertScreen(
    sourceName: String,
    sourceInfo: String,
    settings: ToneSettings,
    previewFrame: ByteArray?,
    previewIndex: Int,
    previewCount: Int,
    busy: Boolean,
    progress: Float,
    progressText: String,
    error: String?,
    onSettings: (ToneSettings) -> Unit,
    onPreviewIndex: (Int) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // 顶栏固定在顶部：调参数时页面上半截一直在滚，返回按钮不该跟着走
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 20.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.surfaceAlt)
                    .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                    .clickable { onCancel() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("返回", color = Palette.muted, fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("转换", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(sourceName, color = Palette.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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

            // 取景预览
            Panel(padding = 14.dp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0A0A0D)),
                    contentAlignment = Alignment.Center
                ) {
                    DotGrid(previewFrame, Modifier.size(width = 200.dp, height = 271.dp), padding = 10f)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(sourceInfo, color = Palette.dim, fontSize = 11.sp)
                    Text("预览 ${previewIndex + 1}/$previewCount", color = Palette.dim, fontSize = 11.sp)
                }
                if (previewCount > 1) {
                    LabeledSlider(
                        "取景帧",
                        previewIndex.toFloat(),
                        "${previewIndex + 1}/$previewCount",
                        range = 0f..(previewCount - 1).toFloat(),
                        steps = (previewCount - 2).coerceAtLeast(0)
                    ) { v -> onPreviewIndex(v.toInt()) }
                }
            }

            // 取景
            Panel {
                SectionTitle("取景")
                Spacer(Modifier.height(10.dp))
                SegRow(
                    listOf("填满裁切", "完整留边", "拉伸"),
                    when (settings.fit) {
                        Fit.COVER -> 0
                        Fit.CONTAIN -> 1
                        Fit.STRETCH -> 2
                    }
                ) { index ->
                    onSettings(
                        settings.copy(
                            fit = when (index) {
                                0 -> Fit.COVER
                                1 -> Fit.CONTAIN
                                else -> Fit.STRETCH
                            }
                        )
                    )
                }
                if (settings.fit == Fit.COVER) {
                    LabeledSlider("缩放", settings.zoom, String.format("%.2fx", settings.zoom), 1f..2.5f) {
                        onSettings(settings.copy(zoom = it))
                    }
                    LabeledSlider("水平位置", settings.offsetX, pct(settings.offsetX), -1f..1f) {
                        onSettings(settings.copy(offsetX = it))
                    }
                    LabeledSlider("垂直位置", settings.offsetY, pct(settings.offsetY), -1f..1f) {
                        onSettings(settings.copy(offsetY = it))
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (settings.fit == Fit.CONTAIN) "完整保留画面，上下或左右留黑边。" else "强行拉成 17:23，人物会变形。",
                        color = Palette.dim,
                        fontSize = 11.sp
                    )
                }
            }

            // 明暗
            Panel {
                SectionTitle("明暗与层次")
                Spacer(Modifier.height(6.dp))
                SwitchRow("反相", "线稿、白底黑线的图打开；照片关掉", settings.invert) {
                    onSettings(settings.copy(invert = it))
                }
                LabeledSlider("黑场", settings.black, String.format("%.02f", settings.black), 0f..0.4f) {
                    onSettings(settings.copy(black = it.coerceAtMost(settings.white - 0.05f)))
                }
                LabeledSlider("白场", settings.white, String.format("%.02f", settings.white), 0.2f..1f) {
                    onSettings(settings.copy(white = it.coerceAtLeast(settings.black + 0.05f)))
                }
                LabeledSlider("伽马", settings.gamma, String.format("%.2f", settings.gamma), 0.4f..2.6f) {
                    onSettings(settings.copy(gamma = it))
                }
                Spacer(Modifier.height(6.dp))
                Text("色阶", color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                val levelOptions = listOf(2, 4, 8, 16, 256)
                val levelLabels = listOf("2 级", "4 级", "8 级", "16 级", "连续")
                val levelIndex = levelOptions.indexOfFirst { it >= settings.levels }.let { if (it < 0) 4 else it }
                SegRow(levelLabels, levelIndex) { index ->
                    onSettings(settings.copy(levels = levelOptions[index]))
                }
                Spacer(Modifier.height(4.dp))
                SwitchRow("抖动", "低色阶时用棋盘格补过渡，会牺牲一点干净度", settings.dither) {
                    onSettings(settings.copy(dither = it))
                }
            }

            // 输出
            Panel {
                SectionTitle("输出")
                LabeledSlider("帧率", settings.fps.toFloat(), "${settings.fps} fps", 6f..24f, steps = 17) {
                    onSettings(settings.copy(fps = it.toInt().coerceIn(6, 24)))
                }
                SwitchRow("循环播放", null, settings.loop) { onSettings(settings.copy(loop = it)) }
                SwitchRow("乒乓循环", "正放再倒放，短动画看起来更自然", settings.pingPong) {
                    onSettings(settings.copy(pingPong = it))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "最多保留 ${com.stepx.backmatrix.core.Importer.MAX_FRAMES} 帧，超出会截断。",
                    color = Palette.dim,
                    fontSize = 11.sp
                )
            }

            if (busy) {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progressText, color = Palette.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${(progress * 100).toInt()}%", color = Palette.muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Palette.accent,
                        trackColor = Palette.borderStrong
                    )
                }
            }

            if (error != null) {
                Text(error, color = Palette.danger, fontSize = 12.sp)
            }

            PrimaryButton(
                if (busy) "正在转换…" else "保存并播放",
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                glyph = Glyph.CHECK
            ) { onConfirm() }
            GhostButton("取消", modifier = Modifier.fillMaxWidth(), enabled = !busy) { onCancel() }

            Text(
                "转换结果会存进动画库，随时可以再播。原始 GIF/MP4 不会被修改。",
                color = Palette.dim,
                fontSize = 10.sp
            )
        }
    }
}

private fun pct(v: Float): String = when {
    kotlin.math.abs(v) < 0.02f -> "居中"
    v > 0 -> "右 ${(v * 100).toInt()}%"
    else -> "左 ${(-v * 100).toInt()}%"
}
