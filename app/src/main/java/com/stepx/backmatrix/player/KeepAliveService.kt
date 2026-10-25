package com.stepx.backmatrix.player

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 保活锚：一个什么都不做的无障碍服务。
 *
 * 系统把无障碍服务当作「用户明确开启的辅助功能」对待，进程优先级比普通后台高得多，
 * 前台服务被回收时更容易被重新拉起。播放是持续推帧，掉一次就是背屏卡在最后一帧，
 * 所以除了电池优化白名单，还给用户留了这一档。
 *
 * 不读取屏幕内容（`canRetrieveWindowContent=false`），不发任何事件，只是占位。
 * 开关状态读系统表，见 [KeepAlive.isAccessibilityEnabled]。
 */
class KeepAliveService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
