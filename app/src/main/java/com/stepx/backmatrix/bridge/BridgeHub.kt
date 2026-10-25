package com.stepx.backmatrix.bridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内的桥单例：点按钮就在当前进程里把服务起来，不等前台服务调度成功——
 * 前台服务只负责保活与状态通知，桥本身立刻可用。
 */
object BridgeHub {

    private var server: BridgeServer? = null
    private var api: BridgeApi? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Synchronized
    fun start(context: Context): Boolean {
        val existing = server
        if (existing != null && existing.isRunning) {
            _running.value = true
            return true
        }
        return try {
            val app = context.applicationContext
            val next = BridgeServer(api ?: BridgeApi(app).also { api = it })
            next.start()
            server = next
            _running.value = true
            _lastError.value = null
            true
        } catch (t: Throwable) {
            _lastError.value = "${t.javaClass.simpleName}: ${t.message}"
            runCatching { server?.stop() }
            server = null
            _running.value = false
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop() }
        server = null
        _running.value = false
    }
}
