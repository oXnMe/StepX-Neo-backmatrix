package com.stepx.backmatrix.bridge

import android.util.Log
import com.stepx.backmatrix.core.BackScreen
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 极简本地 HTTP 服务：JDK 的 ServerSocket + 平台自带 org.json，只绑 127.0.0.1。
 *
 * 两个端点：`GET /health` 探活（不校验 Token）与 `POST /api/call`（必须带 Token）。
 * 业务失败也返回 HTTP 200，body 里 `ok:false` —— H5 侧只能靠 body 判断。
 *
 * 为什么不用 NanoHTTPD：这块只需要一个固定端点的回环服务，多一个依赖不值得。
 */
class BridgeServer(
    private val api: BridgeApi,
    private val port: Int = BridgeConfig.PORT,
    private val token: String = BridgeConfig.TOKEN
) {

    private var socket: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running && socket?.isClosed == false

    @Throws(IOException::class)
    fun start() {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", port))
        socket = server
        running = true
        pool.execute { acceptLoop(server) }
        Log.i(TAG, "bridge listening on 127.0.0.1:$port")
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        pool.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            try {
                val client = server.accept()
                pool.execute { handle(client) }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept error", e)
                break
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { s ->
            s.soTimeout = 8000
            val input = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            val out = s.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            var auth: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (key) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    BridgeConfig.TOKEN_HEADER.lowercase() -> auth = value
                }
            }

            val body = CharArray(contentLength.coerceAtLeast(0))
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }

            if (method == "OPTIONS") {
                respond(out, 204, "")
                return
            }

            // /health 是探活口，不校验 Token：Studio 侧的健康检查按契约不带请求头，
            // 这里要是要求 Token，页面会一直显示「未连接」。它只暴露机型与 display_ext 可用性。
            val isHealth = method == "GET" && (path == BridgeConfig.HEALTH_PATH || path == "/")
            if (!isHealth && token.isNotEmpty() && token != auth) {
                respond(out, 401, error("unauthorized").toString())
                return
            }

            if (isHealth) {
                val health = JSONObject()
                    .put("ok", true)
                    .put("service", BridgeConfig.SERVICE)
                    .put("displayExtAvailable", BackScreen.hasService(api.context))
                    .put("modelSupported", BackScreen.isTargetModel)
                respond(out, 200, health.toString())
                return
            }

            if (method == "POST" && path == BridgeConfig.CALL_PATH) {
                val payload = try {
                    val request = JSONObject(String(body))
                    val name = request.optString("name", "")
                    // 入参根的名字是 arguments：写成 args 会被当成空对象，
                    // 表现为「能力像是拒绝了这个参数」，很难查
                    val args = request.optJSONObject("arguments") ?: JSONObject()
                    api.call(name, args).toString()
                } catch (e: BridgeException) {
                    error(e.message ?: "error").toString()
                } catch (e: Exception) {
                    Log.w(TAG, "call failed: ${e.message}")
                    error(e.message ?: "error").toString()
                }
                respond(out, 200, payload)
                return
            }

            respond(out, 404, error("not found").toString())
        }
    }

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, ").append(BridgeConfig.TOKEN_HEADER).append("\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        204 -> "No Content"
        401 -> "Unauthorized"
        else -> "Error"
    }

    private companion object {
        const val TAG = "BridgeServer"
    }
}
