package com.stepx.backmatrix.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.stepx.backmatrix.core.BackScreen
import com.stepx.backmatrix.core.Converter
import com.stepx.backmatrix.core.DotAnimation
import com.stepx.backmatrix.core.Fit
import com.stepx.backmatrix.core.Importer
import com.stepx.backmatrix.core.Library
import com.stepx.backmatrix.core.SourceLoader
import com.stepx.backmatrix.core.SourcePreview
import com.stepx.backmatrix.core.ToneSettings
import com.stepx.backmatrix.player.KeepAlive
import com.stepx.backmatrix.player.MatrixPlayer
import com.stepx.backmatrix.player.PlaybackService
import com.stepx.backmatrix.tools.ToolPrefs
import com.stepx.backmatrix.tools.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 应用内的四块屏幕。转换页是叠加态，不在这里。 */
enum class Screen { HOME, TOOLS, TOOL_DETAIL, SETTINGS }

/** 首启后台放行只主动弹一次，标记存在 "app" 这份 SharedPreferences 里。 */
private const val KEY_POWER_ASKED = "power_asked"

/** 通知权限同理：拒掉之后就别再弹，想开可以去系统设置里给。 */
private const val KEY_NOTIF_ASKED = "notif_asked"

class MainActivity : ComponentActivity() {

    private lateinit var app: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = AppState(this)
        setContent {
            PzTheme {
                AppRoot(app)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::app.isInitialized) app.refreshDevice()
    }
}

/** 极简的状态容器：几个屏幕 + 一个播放器，没必要上完整的 ViewModel 体系。 */
class AppState(val activity: ComponentActivity) {

    private val prefs = activity.getSharedPreferences("app", Context.MODE_PRIVATE)
    val toolPrefs = ToolPrefs(activity)

    var entries by mutableStateOf<List<Library.Entry>>(emptyList())
    var thumbs by mutableStateOf<Map<String, ByteArray>>(emptyMap())
    var idleFrame by mutableStateOf<ByteArray?>(null)
    var device by mutableStateOf(
        DeviceStatus(Build.MODEL ?: "未知", BackScreen.isTargetModel, false, null, null, "—")
    )
    /** 电池优化白名单状态。原厂的自启动开关系统不给读，只有这个读得到。 */
    var powerOk by mutableStateOf(PowerGuide.isIgnoringBatteryOptimizations(activity))
    /** 通知权限与无障碍保活：两个都读得到状态，设置页要显示真实开关。 */
    var notifOk by mutableStateOf(KeepAlive.isNotificationGranted(activity))
    var accessibilityOk by mutableStateOf(KeepAlive.isAccessibilityEnabled(activity))
    var convertMode by mutableStateOf(false)
    var source by mutableStateOf<SourcePreview?>(null)
    var settings by mutableStateOf(ToneSettings())
    var previewIndex by mutableStateOf(0)
    var previewFrame by mutableStateOf<ByteArray?>(null)
    var busy by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var progressText by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Library.Entry?>(null)
    private var currentAnim: DotAnimation? = null

    // ── 工具箱相关 ─────────────────────────────────────────────
    var screen by mutableStateOf(Screen.HOME)
    var activeToolId by mutableStateOf<String?>(null)
    var toolPreviews by mutableStateOf<Map<String, ByteArray>>(emptyMap())
    /** 参数写入后自增，用来让详情页重新读取 SharedPreferences。 */
    var paramVersion by mutableStateOf(0)
    var pendingStartId by mutableStateOf<String?>(null)
    var toolError by mutableStateOf<String?>(null)

    /**
     * 这次通知权限弹框是不是首启流程发起的。
     * 是：回调里接着问后台放行（两个对话框串起来）；否：只是用户在设置页点了一下。
     */
    var notifAskFromFirstRun = false

    init {
        MatrixPlayer.setRotation(activity, prefs.getInt("rotation", 0))
        MatrixPlayer.setBrightness(activity, prefs.getFloat("brightness", 1f))
        refreshLibrary()
        refreshDevice()
        loadToolPreviews()
    }

    private fun loadToolPreviews() {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val map = HashMap<String, ByteArray>()
            for (spec in Tools.all) {
                map[spec.id] = runCatching { Tools.preview(activity, spec.id) }.getOrDefault(ByteArray(391))
            }
            activity.runOnUiThread { toolPreviews = map }
        }
    }

    fun openTools() {
        screen = Screen.TOOLS
        activeToolId = null
    }

    fun openTool(id: String) {
        activeToolId = id
        paramVersion++
        toolError = null
        screen = Screen.TOOL_DETAIL
    }

    fun backToTools() {
        activeToolId = null
        screen = Screen.TOOLS
    }

    fun startTool(id: String) {
        val spec = Tools.spec(id) ?: return
        val instance = runCatching { Tools.create(activity, id) }.getOrNull()
        if (instance == null) {
            toolError = "这个工具起不来"
            return
        }
        toolError = null
        MatrixPlayer.startTool(activity, id, spec.name, instance)
        PlaybackService.startTool(activity, id, spec.name)
    }

    /** 详情页的「清零」：只对正在跑的工具生效。 */
    fun resetTool() = MatrixPlayer.resetTool()

    fun updateToolParam(id: String, key: String, value: Any?) {
        toolPrefs.put(id, key, value)
        paramVersion++
    }

    fun refreshDevice() {
        val has = BackScreen.hasService(activity)
        val info = if (has) BackScreen.animationInfo(activity) else null
        val settingValue = runCatching {
            Settings.System.getInt(activity.contentResolver, "stepos_back_screen_display", 1)
        }.getOrNull()
        val label = when (settingValue) {
            null -> "读不到（系统版本差异）"
            0 -> "已关闭"
            else -> "已开启"
        }
        device = DeviceStatus(
            model = Build.MODEL ?: "未知",
            isTarget = BackScreen.isTargetModel,
            hasService = has,
            info = info,
            error = BackScreen.lastError,
            backScreenSetting = label
        )
        refreshPower()
    }

    /** 从系统设置页（电池 / 通知 / 无障碍）回来时，重新读一遍这三项状态。 */
    fun refreshPower() {
        powerOk = PowerGuide.isIgnoringBatteryOptimizations(activity)
        notifOk = KeepAlive.isNotificationGranted(activity)
        accessibilityOk = KeepAlive.isAccessibilityEnabled(activity)
    }

    /** 弹系统的「忽略电池优化」确认框。 */
    fun requestBatteryExemption() {
        if (!PowerGuide.requestIgnoreBatteryOptimizations(activity)) {
            Toast.makeText(activity, "这台手机没有电池优化设置页，请手动到应用信息里放行", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 首启引导：只主动弹一次，之后从「设置 → 后台运行」手动点。
     *
     * 先落标记再弹框，避免对话框被系统吞掉后每次开应用都再弹一遍。
     */
    fun requestBatteryOnFirstRun() {
        if (powerOk) return
        if (prefs.getBoolean(KEY_POWER_ASKED, false)) return
        prefs.edit().putBoolean(KEY_POWER_ASKED, true).apply()
        requestBatteryExemption()
    }

    /**
     * 通知权限要不要主动问。
     *
     * 和后台放行一样只问一次：先落标记再返回 true，用户拒了就不会每次开应用都被弹一遍。
     * 之后想开只能去系统设置里给，这比反复弹框体面。
     */
    fun shouldAskNotifications(): Boolean {
        if (prefs.getBoolean(KEY_NOTIF_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        return true
    }

    /** 跳厂商的自启动 / 后台管理页，跳不到就退到应用详情页。 */
    fun openAutoStart() {
        if (!PowerGuide.openAutoStartSettings(activity)) {
            Toast.makeText(activity, "打不开自启动管理，请手动到应用信息 → 耗电和后台里设置", Toast.LENGTH_LONG).show()
        }
    }

    /** 无障碍保活：系统只给列表页，勾选得用户自己来。 */
    fun openAccessibility() {
        if (!KeepAlive.openAccessibilitySettings(activity)) {
            Toast.makeText(activity, "打不开无障碍设置，请手动到 设置 → 无障碍 里开启", Toast.LENGTH_LONG).show()
        }
    }

    /** 通知被拒过之后系统不再弹框，只能去应用信息里手动开。 */
    fun openAppDetails() {
        if (!PowerGuide.openAppDetails(activity)) {
            Toast.makeText(activity, "打不开应用信息页", Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshLibrary() {
        entries = runCatching { Library.list(activity) }.getOrDefault(emptyList())
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val map = HashMap<String, ByteArray>()
            for (e in entries) {
                val anim = runCatching { Library.load(activity, e) }.getOrNull() ?: continue
                map[e.id] = anim.frames.first()
            }
            activity.runOnUiThread {
                thumbs = map
                if (idleFrame == null) idleFrame = map[Library.BUILTIN_ID] ?: map.values.firstOrNull()
            }
        }
    }

    fun onFilePicked(uri: Uri) {
        busy = true
        progress = 0f
        progressText = "正在读取文件…"
        error = null
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = displayName(uri)
                val preview = SourceLoader.load(activity, uri, name)
                activity.runOnUiThread {
                    source = preview
                    // GIF 基本是线稿；视频按画面平均亮度判断，白底的就按线稿处理
                    val bright = preview.samples.firstOrNull()?.let { Converter.meanLuma(it) } ?: 0f
                    val preset = if (preview.isGif || bright > 0.62f) ToneSettings.lineArt() else ToneSettings.photo()
                    settings = preset.copy(
                        fps = settings.fps,
                        loop = settings.loop,
                        fit = Fit.COVER
                    )
                    previewIndex = 0
                    recomputePreview()
                    convertMode = true
                    busy = false
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    busy = false
                    error = "读取失败：${t.message ?: t.javaClass.simpleName}"
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        return runCatching {
            activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "动画"
    }

    fun updateSettings(next: ToneSettings) {
        settings = next
        recomputePreview()
    }

    fun selectPreviewIndex(index: Int) {
        previewIndex = index
        recomputePreview()
    }

    private fun recomputePreview() {
        val s = source ?: return
        if (s.samples.isEmpty()) { previewFrame = null; return }
        val idx = previewIndex.coerceIn(0, s.samples.size - 1)
        previewFrame = runCatching {
            Converter.convert(s.samples[idx], s.sampleWidth, s.sampleHeight, settings)
        }.getOrNull()
    }

    fun cancelConvert() {
        convertMode = false
        source = null
        previewFrame = null
        busy = false
        progress = 0f
        error = null
    }

    fun confirmConvert() {
        val s = source ?: return
        busy = true
        progress = 0f
        error = null
        progressText = "开始转换…"
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val onProgress: (Float, String) -> Unit = { p, text ->
                    activity.runOnUiThread {
                        progress = p.coerceIn(0f, 1f)
                        progressText = text
                    }
                }
                val result = if (s.isGif) {
                    activity.contentResolver.openInputStream(s.uri)?.use { input ->
                        Importer.fromGif(input, settings, s.displayName, onProgress)
                    } ?: error("无法重新打开这个文件")
                } else {
                    Importer.fromVideo(activity, s.uri, settings, s.displayName, onProgress)
                }
                val entry = Library.save(activity, result.animation)
                activity.runOnUiThread {
                    busy = false
                    progress = 1f
                    convertMode = false
                    source = null
                    refreshLibrary()
                    if (result.note != null) error = result.note
                    playEntry(entry)
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    busy = false
                    progress = 0f
                    error = "转换失败：${t.message ?: t.javaClass.simpleName}"
                }
            }
        }
    }

    fun togglePlay() {
        val state = MatrixPlayer.state.value
        if (state.playing) {
            MatrixPlayer.pause(activity)
            PlaybackService.stop(activity)
            return
        }
        if (state.live && state.toolId.isNotEmpty()) {
            // 暂停中的工具可以原地续跑
            startTool(state.toolId)
            return
        }
        val anim = currentAnim
        val entry = selected
        if (anim != null && entry != null) {
            MatrixPlayer.start(activity, anim, fromStart = false)
            PlaybackService.start(activity, entry.id)
        } else {
            entries.firstOrNull()?.let { playEntry(it) }
        }
    }

    fun stopPlayback() {
        MatrixPlayer.stop(activity)
        PlaybackService.stop(activity)
    }

    fun playEntry(entry: Library.Entry) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val anim = runCatching { Library.load(activity, entry) }.getOrNull()
            if (anim == null) {
                activity.runOnUiThread { error = "读取动画失败" }
                return@launch
            }
            activity.runOnUiThread {
                currentAnim = anim
                selected = entry
                error = null
                MatrixPlayer.start(activity, anim)
                PlaybackService.start(activity, entry.id)
            }
        }
    }

    fun deleteEntry(entry: Library.Entry) {
        if (entry.builtin) return
        Library.delete(activity, entry)
        if (selected?.id == entry.id) {
            stopPlayback()
            selected = null
            currentAnim = null
        }
        refreshLibrary()
    }

    fun rotate() {
        val turns = (MatrixPlayer.state.value.rotationTurns + 1) % 4
        MatrixPlayer.setRotation(activity, turns)
        prefs.edit().putInt("rotation", turns).apply()
    }

    fun setBrightness(value: Float) {
        MatrixPlayer.setBrightness(activity, value)
        prefs.edit().putFloat("brightness", value).apply()
    }

    /** Step AOS 的「背屏」设置页是系统设置里的一个独立 Activity，直接跳过去。 */
    fun openSystemSettings() {
        val backScreen = Intent().setComponent(
            ComponentName(
                "com.android.settings",
                "com.stepos.settings.StepOSSettingsActivity\$StepOSBackScreenActivity"
            )
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { activity.startActivity(backScreen) }.isSuccess) return

        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { activity.startActivity(fallback) }.isSuccess) {
            Toast.makeText(activity, "没找到「背屏」设置页，已打开系统设置", Toast.LENGTH_LONG).show()
            return
        }
        error = "打不开系统设置，请手动进入 设置 → 显示 → 背屏"
    }
}

@Composable
fun AppRoot(app: AppState) {
    val state by MatrixPlayer.state.collectAsState()
    val frame by MatrixPlayer.frame.collectAsState()

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // 首启：通知权限落地后再问后台放行，两个对话框才不会打架。
        // 设置页手动点的这一次只是刷新状态，别顺手再弹一个框。
        if (app.notifAskFromFirstRun) {
            app.notifAskFromFirstRun = false
            app.requestBatteryOnFirstRun()
        } else {
            app.refreshPower()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.onFilePicked(uri)
    }
    // 工具按需申请权限：授权成功才真的启动，避免了"先崩一次再要权限"
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val pending = app.pendingStartId
        app.pendingStartId = null
        if (pending == null) return@rememberLauncherForActivityResult
        if (result.values.all { it }) {
            app.startTool(pending)
        } else {
            app.toolError = "权限没给全，这个工具跑不起来"
        }
    }

    fun startWithPermission(id: String) {
        val spec = Tools.spec(id) ?: return
        val missing = spec.permissions.filter {
            ContextCompat.checkSelfPermission(app.activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            app.startTool(id)
        } else {
            app.pendingStartId = id
            permLauncher.launch(missing.toTypedArray())
        }
    }

    // 系统返回键按层级退：转换页先取消、详情页回列表、其他非首页回动画页，
    // 只有已经在首页时才交还给系统（退出）。不加这个，按返回会一步退回桌面。
    BackHandler(enabled = app.convertMode || app.screen != Screen.HOME) {
        when {
            app.convertMode -> app.cancelConvert()
            app.screen == Screen.TOOL_DETAIL -> app.backToTools()
            else -> app.screen = Screen.HOME
        }
    }

    LaunchedEffect(Unit) {
        var waitingNotif = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                app.activity, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            // 同电池放行：只主动问一次，被拒了就不要再每次开应用都弹一遍
            if (!granted && app.shouldAskNotifications()) {
                // 弹了通知权限就等回调里接着问，别一次丢两个对话框出来
                waitingNotif = true
                app.notifAskFromFirstRun = true
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // 通知权限已经给过或系统版本不需要：这里直接问后台放行
        if (!waitingNotif) app.requestBatteryOnFirstRun()
    }

    Surface(color = Palette.bg, modifier = Modifier.fillMaxSize()) {
        // targetSdk 35 在 Android 15 上强制边到边，必须自己让开状态栏与手势条
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    val src = app.source
                    when {
                        app.convertMode && src != null -> ConvertScreen(
                            sourceName = src.displayName,
                            sourceInfo = src.infoText(),
                            settings = app.settings,
                            previewFrame = app.previewFrame,
                            previewIndex = app.previewIndex,
                            previewCount = src.samples.size.coerceAtLeast(1),
                            busy = app.busy,
                            progress = app.progress,
                            progressText = app.progressText,
                            error = app.error,
                            onSettings = { app.updateSettings(it) },
                            onPreviewIndex = { app.selectPreviewIndex(it) },
                            onCancel = { app.cancelConvert() },
                            onConfirm = { app.confirmConvert() }
                        )
                        app.screen == Screen.TOOL_DETAIL -> {
                            val spec = app.activeToolId?.let { Tools.spec(it) }
                            if (spec == null) {
                                app.backToTools()
                            } else {
                                // 按 id 分槽：不同工具之间不能复用 Compose 的 remember 槽位
                                androidx.compose.runtime.key(spec.id) {
                                    ToolScreen(
                                        context = app.activity,
                                        spec = spec,
                                        state = state,
                                        frame = frame,
                                        fallback = app.toolPreviews[spec.id],
                                        paramVersion = app.paramVersion,
                                        onBack = { app.backToTools() },
                                        onStart = { startWithPermission(spec.id) },
                                        onStop = { app.stopPlayback() },
                                        onParam = { k, v -> app.updateToolParam(spec.id, k, v) },
                                        onReset = { app.resetTool() },
                                    )
                                }
                            }
                        }
                        app.screen == Screen.TOOLS -> ToolsScreen(
                            ready = app.device.hasService,
                            runningToolId = if (state.live) state.toolId else "",
                            previews = app.toolPreviews,
                            onOpen = { app.openTool(it) }
                        )
                        app.screen == Screen.SETTINGS -> SettingsScreen(
                            device = app.device,
                            powerOk = app.powerOk,
                            notifOk = app.notifOk,
                            accessibilityOk = app.accessibilityOk,
                            onOpenSettings = { app.openSystemSettings() },
                            onRequestBattery = { app.requestBatteryExemption() },
                            onOpenAutoStart = { app.openAutoStart() },
                            onRequestNotification = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    app.notifAskFromFirstRun = false
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    app.openAppDetails()
                                }
                            },
                            onOpenAccessibility = { app.openAccessibility() },
                            onOpenAppDetails = { app.openAppDetails() }
                        )
                        else -> HomeScreen(
                            state = state,
                            frame = frame,
                            entries = app.entries,
                            thumbs = app.thumbs,
                            ready = app.device.hasService,
                            idleFrame = app.idleFrame,
                            converting = app.busy,
                            onPlayPause = { app.togglePlay() },
                            onStop = { app.stopPlayback() },
                            onRotate = { app.rotate() },
                            onBrightness = { app.setBrightness(it) },
                            onPickFile = { picker.launch(arrayOf("image/gif", "image/*", "video/mp4", "video/*")) },
                            onPlayEntry = { app.playEntry(it) },
                            onDeleteEntry = { app.deleteEntry(it) }
                        )
                    }
                }
                if (!app.convertMode && app.screen != Screen.TOOL_DETAIL) {
                    BottomBar(app.screen) { target ->
                        if (target == Screen.TOOLS) app.openTools() else app.screen = target
                    }
                }
            }
        }
    }
}

/** 底部三格导航：动画 / 工具 / 设置。 */
@Composable
private fun BottomBar(current: Screen, onSelect: (Screen) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(Palette.surface)
        ) {
            BarItem("动画", Glyph.FILM, current == Screen.HOME, Modifier.weight(1f)) {
                onSelect(Screen.HOME)
            }
            BarItem("工具", Glyph.GRID, current == Screen.TOOLS, Modifier.weight(1f)) {
                onSelect(Screen.TOOLS)
            }
            BarItem("设置", Glyph.SLIDERS, current == Screen.SETTINGS, Modifier.weight(1f)) {
                onSelect(Screen.SETTINGS)
            }
        }
    }
}

@Composable
private fun BarItem(
    label: String,
    glyph: Glyph,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tint = if (active) Palette.accent else Palette.dim
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlyphIcon(glyph, tint, 16.dp)
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
