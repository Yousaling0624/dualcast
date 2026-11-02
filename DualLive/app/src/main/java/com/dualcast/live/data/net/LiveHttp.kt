package com.dualcast.live.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object HttpHeaders {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
}

class ApiException(val code: Int, override val message: String) : Exception(message)

/**
 * 所有 HTTP 请求的入口。B 站的接口对 buvid 比较敏感，
 * 这里会在第一次请求前先取一次指纹 cookie，并在后续请求里带上。
 */
class LiveHttp {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    val webSocketClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    /**
     * 拉流专用的客户端。
     *
     * 直播是长连接，[client] 上的 `callTimeout(25s)` 会把整个请求（含读 body）
     * 在 25 秒后直接取消——表现就是「播一会儿卡住」，然后切线路、再卡。
     * 这里必须把 callTimeout 关掉，只保留读超时：服务端真的不发数据了，
     * 12 秒后抛 IO 异常，交给播放器切下一条线路，而不是无限干等。
     *
     * 另外强制走 HTTP/1.1：OkHttp 的 h2 流控窗口对长视频流很不友好，
     * 几个 CDN 的 h2 实现也参差不齐，直播流用 h1 明显更稳。
     */
    val mediaClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val buvidMutex = Mutex()
    @Volatile private var buvid3: String? = null
    @Volatile private var buvid4: String? = null

    val buvid: String? get() = buvid3

    suspend fun ensureBuvid(force: Boolean = false) {
        if (!force && buvid3 != null) return
        buvidMutex.withLock {
            if (!force && buvid3 != null) return@withLock
            runCatching {
                val json = getJson(
                    "https://api.bilibili.com/x/frontend/finger/spi",
                    mapOf("User-Agent" to HttpHeaders.DESKTOP_UA),
                )
                val data = json.optJSONObject("data") ?: return@runCatching
                buvid3 = data.optString("b_3").takeIf { it.isNotBlank() }
                buvid4 = data.optString("b_4").takeIf { it.isNotBlank() }
            }
        }
    }

    fun biliCookie(userCookie: String): String {
        val parts = buildList {
            buvid3?.let { add("buvid3=$it") }
            buvid4?.let { add("buvid4=$it") }
            if (userCookie.isNotBlank()) add(userCookie.trim().trimEnd(';'))
        }
        return parts.joinToString("; ")
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            headers.forEach { (key, value) -> builder.header(key, value) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(response.code, "HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val text = getText(url, headers)
            runCatching { JSONObject(text) }
                .getOrElse { throw ApiException(-1, "返回内容不是合法 JSON") }
        }

    /** 需要读 Set-Cookie 的场景（扫码登录轮询）用这个。 */
    suspend fun getWithHeaders(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<String, okhttp3.Headers> = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        headers.forEach { (key, value) -> builder.header(key, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "HTTP ${response.code}")
            response.body?.string().orEmpty() to response.headers
        }
    }

    /**
     * 表单 POST。B 站的写操作（发弹幕等）都是 form + csrf 校验，
     * Content-Type 由 [okhttp3.FormBody] 自己带，不要再手动加，否则会重复。
     */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = okhttp3.FormBody.Builder()
            .apply { form.forEach { (key, value) -> add(key, value) } }
            .build()
        val builder = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        headers.forEach { (key, value) -> builder.header(key, value) }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, "HTTP ${response.code}")
            runCatching { JSONObject(text) }
                .getOrElse { throw ApiException(-1, "返回内容不是合法 JSON") }
        }
    }

    suspend fun head(url: String, headers: Map<String, String> = emptyMap()): Int =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).head()
            headers.forEach { (key, value) -> builder.header(key, value) }
            client.newCall(builder.build()).execute().use { it.code }
        }
}
