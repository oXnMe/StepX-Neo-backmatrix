package com.stepx.backmatrix.bridge

/**
 * 本地桥的地址常量。
 *
 * 端口和 Token 与独立的「背屏桥」应用保持一致（39871 / 同名 Token），
 * 已经按 `docs/STUDIO_PROMPT.md` 写好的 Studio 页面不用改就能连到这里。
 */
object BridgeConfig {

    const val PORT = 39871

    const val TOKEN = "backscreen-bridge"

    const val TOKEN_HEADER = "X-Bridge-Token"

    const val HEALTH_PATH = "/health"

    const val CALL_PATH = "/api/call"

    /** `/health` 里报的名字，用来确认连的是哪一个 App。 */
    const val SERVICE = "stepx-backmatrix"

    /** 用户上次把桥留成开着还是关着，重启应用后按这个恢复。 */
    const val PREF_FILE = "app"
    const val KEY_ON = "bridge_on"

    val baseUrl: String get() = "http://127.0.0.1:$PORT"
}
