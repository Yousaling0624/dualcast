package com.dualcast.live.data.danmaku

import android.util.Log
import com.dualcast.live.data.model.DanmakuMessage
import com.dualcast.live.data.model.DanmakuType
import com.dualcast.live.data.net.ApiException
import com.dualcast.live.data.net.BiliApi
import com.dualcast.live.data.net.BiliDanmakuEndpoint
import com.dualcast.live.data.net.HttpHeaders
import com.dualcast.live.data.net.LiveHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.brotli.dec.BrotliInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * B 站直播弹幕：16 字节包头 + 可选 zlib / brotli 压缩。
 */
class BiliDanmakuClient(
    private val http: LiveHttp,
    private val api: BiliApi,
    private val roomId: String,
    userUid: Long = 0L,
) : DanmakuClient {

    /** 登录态失效时自动退回匿名，避免 Cookie 过期导致完全连不上。 */
    @Volatile private var authUid: Long = userUid

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _messages = MutableSharedFlow<DanmakuMessage>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messages: SharedFlow<DanmakuMessage> = _messages

    private val _status = MutableStateFlow<DanmakuStatus>(DanmakuStatus.Idle)
    override val status: StateFlow<DanmakuStatus> = _status

    private var job: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var hostIndex = 0

    override fun start() {
        stop()
        _status.value = DanmakuStatus.Connecting
        job = scope.launch {
            var attempt = 0
            var lastError: String? = null
            while (isActive) {
                if (attempt > MAX_ATTEMPTS) {
                    _status.value = DanmakuStatus.Failed(
                        reason = lastError ?: "连接中断，多次重试失败",
                        retryable = true,
                    )
                    return@launch
                }
                try {
                    val endpoint = api.danmakuEndpoint(roomId)
                    connectOnce(endpoint)
                    attempt = 0
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    Log.w(TAG, "bili danmaku error: ${e.message}")
                    lastError = e.message ?: "网络异常"
                    _status.value = DanmakuStatus.Reconnecting(attempt + 1, lastError)
                }
                attempt++
                delay(min(20_000L, 1_500L * attempt))
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.close(1000, null)
        socket = null
        _status.value = DanmakuStatus.Idle
    }

    private suspend fun connectOnce(endpoint: BiliDanmakuEndpoint) =
        suspendCancellableCoroutine<Unit> { cont ->
            val url = endpoint.urls[hostIndex % endpoint.urls.size]
            hostIndex++
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpHeaders.DESKTOP_UA)
                .header("Origin", "https://live.bilibili.com")
                .build()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                    webSocket.send(authPacket(endpoint.token).toByteString())
                    // 认证结果要等 op=8 的回包，这里不能直接认为已连接。
                    heartbeatJob?.cancel()
                    heartbeatJob = scope.launch {
                        delay(1_500)
                        while (isActive) {
                            runCatching {
                                webSocket.send(packet(OP_HEARTBEAT, HEARTBEAT_BODY).toByteString())
                            }
                            delay(30_000)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    val data = bytes.toByteArray()
                    val reply = authReply(data)
                    if (reply != null) {
                        val (code, message) = reply
                        if (code == 0) {
                            _status.value = DanmakuStatus.Connected
                        } else {
                            heartbeatJob?.cancel()
                            runCatching { webSocket.close(1000, null) }
                            // 带登录态认证失败，先降级成匿名再试一轮
                            val degraded = authUid != 0L
                            if (degraded) authUid = 0L
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    ApiException(
                                        code,
                                        if (degraded) "登录态认证失败，已改用匿名连接"
                                        else message.ifBlank { "弹幕认证失败（$code）" },
                                    )
                                )
                            }
                            return
                        }
                    }
                    runCatching { handleFrame(data) }
                        .onFailure { Log.w(TAG, "decode failed: ${it.message}") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    heartbeatJob?.cancel()
                    if (cont.isActive) cont.resumeWithException(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    heartbeatJob?.cancel()
                    if (cont.isActive) cont.resume(Unit)
                }
            }

            val ws = http.webSocketClient.newWebSocket(request, listener)
            cont.invokeOnCancellation {
                heartbeatJob?.cancel()
                runCatching { ws.cancel() }
            }
        }

    /** 解析 op=8 的认证回包，返回 (code, message)。 */
    private fun authReply(bytes: ByteArray): Pair<Int, String>? {
        if (bytes.size < 16) return null
        val total = readInt(bytes, 0)
        val op = readInt(bytes, 8)
        if (op != OP_AUTH_REPLY || total <= 16 || total > bytes.size) return null
        val text = String(bytes, 16, total - 16, Charsets.UTF_8)
        val code = Regex("\"code\"\\s*:\\s*(-?\\d+)").find(text)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val message = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(text)
            ?.groupValues?.get(1).orEmpty()
        return code to message
    }

    private fun authPacket(token: String): ByteArray {
        val json = JSONObject().apply {
            put("uid", authUid)
            put("roomid", roomId.toLongOrNull() ?: 0L)
            put("protover", 3)
            put("platform", "web")
            put("type", 2)
            put("key", token)
            http.buvid?.let { put("buvid", it) }
        }
        return packet(OP_AUTH, json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun packet(op: Int, body: ByteArray, version: Int = 1): ByteArray {
        val buffer = ByteBuffer.allocate(16 + body.size)
        buffer.putInt(16 + body.size)
        buffer.putShort(16)
        buffer.putShort(version.toShort())
        buffer.putInt(op)
        buffer.putInt(1)
        buffer.put(body)
        return buffer.array()
    }

    private fun handleFrame(bytes: ByteArray) {
        var offset = 0
        while (offset + 16 <= bytes.size) {
            val total = readInt(bytes, offset)
            val headerSize = readShort(bytes, offset + 4)
            val version = readShort(bytes, offset + 6)
            val op = readInt(bytes, offset + 8)
            if (total <= 0 || offset + total > bytes.size) break
            val bodyStart = offset + headerSize.coerceAtLeast(16)
            val bodyEnd = offset + total
            if (bodyStart <= bodyEnd) {
                val body = bytes.copyOfRange(bodyStart, bodyEnd)
                when (version) {
                    2 -> runCatching { handleFrame(inflate(body)) }
                    3 -> runCatching { handleFrame(brotli(body)) }
                    else -> handleBody(op, body)
                }
            }
            offset += total
        }
    }

    private fun handleBody(op: Int, body: ByteArray) {
        if (op == OP_AUTH_REPLY) {
            val text = String(body, Charsets.UTF_8)
            if (text.contains("\"code\":0")) _status.value = DanmakuStatus.Connected
            return
        }
        if (op != OP_MESSAGE) return
        val text = String(body, Charsets.UTF_8)
        parseJsonPayload(text)
    }

    private fun parseJsonPayload(text: String) {
        val cleaned = text.trim('\u0000', ' ', '\n', '\r', '\t')
        if (cleaned.isEmpty()) return
        val chunks = if (cleaned.startsWith("{")) {
            listOf(cleaned)
        } else {
            cleaned.split('\u0000', '\n').filter { it.trimStart().startsWith("{") }
        }
        chunks.forEach { chunk ->
            runCatching { JSONObject(chunk) }.getOrNull()?.let { parseCommand(it) }
        }
    }

    private fun parseCommand(json: JSONObject) {
        val cmd = json.optString("cmd").substringBefore(":")
        when (cmd) {
            "DANMU_MSG" -> parseDanmaku(json)
            "SUPER_CHAT_MESSAGE" -> parseSuperChat(json)
            "SEND_GIFT" -> parseGift(json)
        }
    }

    private fun parseDanmaku(json: JSONObject) {
        val info = json.optJSONArray("info") ?: return
        val text = info.optString(1)
        if (text.isBlank()) return
        val userInfo = info.optJSONArray(2)
        val user = userInfo?.optString(1).orEmpty()
        val uid = userInfo?.optLong(0) ?: 0L
        // info[0][7] 是这条弹幕的 dmid，回复时要用；老版本服务端可能不带，留空即可。
        val dmid = info.optJSONArray(0)?.optString(7).orEmpty()
        val colorInt = info.optJSONArray(0)?.optInt(3, 0xFFFFFF) ?: 0xFFFFFF
        val color = if (colorInt <= 0) {
            0xFFFFFFFF.toInt()
        } else {
            (0xFF000000.toInt()) or (colorInt and 0xFFFFFF)
        }
        emit(
            DanmakuMessage(
                text = text.trim(),
                user = user,
                color = color,
                uid = uid,
                dmid = dmid,
                isSelf = authUid != 0L && uid == authUid,
            )
        )
    }

    private fun parseSuperChat(json: JSONObject) {
        val data = json.optJSONObject("data") ?: return
        val message = data.optString("message")
        if (message.isBlank()) return
        val user = data.optJSONObject("user_info")?.optString("uname").orEmpty()
        val price = data.optInt("price")
        emit(
            DanmakuMessage(
                text = message,
                user = if (price > 0) "$user · ¥$price" else user,
                color = 0xFFFFD166.toInt(),
                type = DanmakuType.SUPER_CHAT,
            )
        )
    }

    private fun parseGift(json: JSONObject) {
        val data = json.optJSONObject("data") ?: return
        val totalCoin = data.optInt("total_coin")
        if (totalCoin < 1000) return
        val name = data.optString("giftName")
        val num = data.optInt("num", 1)
        if (name.isBlank()) return
        emit(
            DanmakuMessage(
                text = "送出 $name ×$num",
                user = data.optString("uname"),
                color = 0xFFFFB4AB.toInt(),
                type = DanmakuType.GIFT,
            )
        )
    }

    private fun emit(message: DanmakuMessage) {
        _messages.tryEmit(message)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 3 + 64)
        val buffer = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buffer, 0, count)
                }
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun brotli(data: ByteArray): ByteArray =
        BrotliInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

    companion object {
        private const val TAG = "BiliDanmaku"
        private const val MAX_ATTEMPTS = 6
        private const val OP_HEARTBEAT = 2
        private const val OP_MESSAGE = 5
        private const val OP_AUTH = 7
        private const val OP_AUTH_REPLY = 8
        private val HEARTBEAT_BODY = "[object Object]".toByteArray(Charsets.UTF_8)
    }
}
