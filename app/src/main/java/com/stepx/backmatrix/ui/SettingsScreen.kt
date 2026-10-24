package com.stepx.backmatrix.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 项目主页。装的是自己编的包时，别人问「这从哪来的」也得有据可查。 */
private const val REPO_URL = "https://github.com/oXnMe/StepX-Neo-backmatrix"

/** 设置页：背屏引导、后台放行、设备诊断、项目信息。 */
@Composable
fun SettingsScreen(
    device: DeviceStatus,
    powerOk: Boolean,
    onOpenSettings: () -> Unit,
    onRequestBattery: () -> Unit,
    onOpenAutoStart: () -> Unit
) {
    // 版本号写在设备诊断里：反馈问题时要能确认装的是哪个包，
    // 不然"改了还是老样子"很难判断是新包没装上还是改动没生效
    val ctx = LocalContext.current
    val version = remember {
        runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("—")
    }
    // 设备上没装浏览器时不要崩，失败就算了
    val openUrl: (String) -> Unit = { url ->
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    Column(Modifier.fillMaxSize()) {
        // 标题栏固定在顶部：设置页很长，滚到设备诊断时标题和连接状态
        // 都跑没了，看不出当前连没连上
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 22.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text("设置", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                Spacer(Modifier.height(3.dp))
                Text("背屏开关 · 后台放行 · 设备诊断", color = Palette.dim, fontSize = 12.sp)
            }
            Pill(
                if (device.hasService) "已连接" else "未连接",
                if (device.hasService) Palette.muted else Palette.warn
            )
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

            // 让背屏亮起来
            Panel {
                SectionTitle("让背屏亮起来")
                Spacer(Modifier.height(8.dp))
                GuideStep("1", "系统设置里打开「背屏显示」，需要常亮就选「始终显示」。")
                GuideStep("2", "背屏朝下、双击熄灭或省电/温控策略下，画面已提交也不会亮，这不是应用没在跑。")
                Spacer(Modifier.height(10.dp))
                GhostButton("打开系统设置", modifier = Modifier.fillMaxWidth()) { onOpenSettings() }
            }

            // 后台放行：Android 只有一个正规入口（电池优化白名单），
            // 国产 ROM 的「自启动」「后台运行」没有 API，只能跳过去让用户自己点
            Panel {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("后台运行", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Pill(
                        if (powerOk) "已放行" else "未放行",
                        if (powerOk) Palette.ok else Palette.warn
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (powerOk)
                        "已在电池优化白名单里。国产 ROM 的「允许自启动」「允许后台运行」系统不让应用读取，需要你到设置里核对一次。"
                    else
                        "锁屏后被杀掉，背屏就会停在最后一帧。先关掉电池优化，再进自启动管理把本应用放开。",
                    color = Palette.dim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        if (powerOk) "已关闭电池优化" else "关闭电池优化",
                        modifier = Modifier.weight(1f),
                        enabled = !powerOk
                    ) { onRequestBattery() }
                    GhostButton("自启动 / 后台管理", modifier = Modifier.weight(1f)) { onOpenAutoStart() }
                }
            }

            // 设备诊断
            Panel {
                SectionTitle("设备")
                Spacer(Modifier.height(6.dp))
                KeyValueRow("机型", device.model, if (device.isTarget) Palette.ok else Palette.warn)
            KeyValueRow("版本", version)
                KeyValueRow(
                    "display_ext",
                    if (device.hasService) "可用" else "未找到",
                    if (device.hasService) Palette.ok else Palette.danger
                )
                KeyValueRow("背屏开关", device.backScreenSetting)
                if (device.info != null) KeyValueRow("合成器", device.info)
                if (device.error != null) KeyValueRow("最近错误", device.error, Palette.danger)
            }

            // 版本号上面那栏已经有了，这里补「从哪来的、去哪提问题」
            Panel {
                SectionTitle("项目")
                Spacer(Modifier.height(4.dp))
                LinkRow("源码仓库", "oXnMe/StepX-Neo-backmatrix") { openUrl(REPO_URL) }
                LinkRow("问题反馈", "GitHub Issues") { openUrl("$REPO_URL/issues") }
                LinkRow("第三方出处", "NOTICE.md") { openUrl("$REPO_URL/blob/main/NOTICE.md") }
            }

            Text(
                "非官方项目，调用的是固件自带的 display_ext 接口，OTA 后可能需要重新确认。",
                color = Palette.dim,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun GuideStep(index: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Palette.accentSoft)
                .border(1.dp, Palette.accent.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                index,
                color = Palette.accent,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(text, color = Palette.muted, fontSize = 11.sp, lineHeight = 18.sp)
    }
}
