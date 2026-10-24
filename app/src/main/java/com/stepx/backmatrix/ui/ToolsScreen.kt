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
import com.stepx.backmatrix.tools.ToolSpec
import com.stepx.backmatrix.tools.Tools

/**
 * 工具箱列表。按四类分组，每张卡片左边是工具真实渲染的一帧缩略图——
 * 不是图标，是它自己画出来的画面，一眼能看出这工具长什么样。
 */
@Composable
fun ToolsScreen(
    ready: Boolean,
    runningToolId: String,
    previews: Map<String, ByteArray>,
    onOpen: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // 标题栏固定在顶部：整页一起滚的话，翻到下面几类工具时，标题和
        // 「就绪 / 未连接」状态胶囊都跟着跑没了，想确认状态还得先拉回顶部
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 22.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text("工具箱", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${Tools.all.size} 个实时工具 · 背屏当仪表用",
                    color = Palette.dim,
                    fontSize = 12.sp
                )
            }
            if (runningToolId.isNotEmpty()) Pill("运行中", Palette.accent)
            else Pill(if (ready) "就绪" else "未连接", if (ready) Palette.muted else Palette.warn)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            Tools.byCategory().forEach { (category, specs) ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(category.label, "${specs.size} 个")
                    specs.forEach { spec ->
                        ToolCard(
                            spec = spec,
                            thumb = previews[spec.id],
                            running = spec.id == runningToolId,
                            onClick = { onOpen(spec.id) }
                        )
                    }
                }
            }

            Text(
                "所有工具都是现算的，不吃网络也不上传任何数据。传感器类工具只在运行期间持有传感器，停下就释放。",
                color = Palette.dim,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun ToolCard(spec: ToolSpec, thumb: ByteArray?, running: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.surface)
            .border(
                1.dp,
                if (running) Palette.accent.copy(alpha = 0.55f) else Palette.border,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0C0C10))
                .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
        ) {
            DotGrid(
                thumb,
                Modifier.fillMaxWidth().height(54.dp),
                padding = 3f
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    spec.name,
                    color = Palette.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (running) {
                    Spacer(Modifier.width(6.dp))
                    Pill("运行中", Palette.accent)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                spec.tagline,
                color = Palette.dim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("›", color = Palette.dim, fontSize = 18.sp)
    }
}
