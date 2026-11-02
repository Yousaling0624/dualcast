package com.dualcast.live.data.net

import org.json.JSONObject

data class QrTicket(
    val key: String,
    val url: String,
)

data class BiliProfile(
    val mid: Long,
    val name: String,
    val avatar: String,
    val level: Int,
)

sealed interface QrState {
    /** 二维码已生成，等待扫码。 */
    data object Waiting : QrState

    /** 手机上已经扫了，等待确认。 */
    data object Scanned : QrState

    /** 二维码超时，需要重新生成。 */
    data object Expired : QrState

    data class Success(val cookie: String) : QrState

    data class Failure(val message: String) : QrState
}

/**
 * B 站扫码登录。
 *
 * 走的是网页版 passport 的公开接口：
 * 1. `qrcode/generate` 拿 qrcode_key 和一个 h5 链接，把链接画成二维码；
 * 2. App 扫码确认后，`qrcode/poll` 返回 code=0，同时响应头里有 Set-Cookie，
 *    里面的 SESSDATA / bili_jct / DedeUserID 就是我们要的登录态；
 * 3. Cookie 存到 DataStore，之后所有直播接口都会带上。
 */
class BiliLogin(private val http: LiveHttp) {

    private val headers = mapOf(
        "User-Agent" to HttpHeaders.DESKTOP_UA,
        "Referer" to "https://www.bilibili.com/",
        "Origin" to "https://www.bilibili.com",
    )

    suspend fun generate(): QrTicket {
        val json = http.getJson(
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header",
            headers,
        )
        if (json.optInt("code") != 0) {
            throw ApiException(json.optInt("code"), "二维码生成失败，请稍后重试")
        }
        val data = json.optJSONObject("data") ?: throw ApiException(-1, "二维码数据为空")
        val key = data.optString("qrcode_key")
        val url = data.optString("url")
        if (key.isBlank() || url.isBlank()) throw ApiException(-1, "二维码数据不完整")
        return QrTicket(key = key, url = url)
    }

    suspend fun poll(key: String): QrState {
        val (body, responseHeaders) = http.getWithHeaders(
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$key&source=main-fe-header",
            headers,
        )
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return QrState.Failure("返回内容异常")

        val outer = json.optInt("code", -1)
        if (outer != 0) {
            return QrState.Failure(
                json.optString("message").ifBlank { "登录接口返回 $outer" }
            )
        }

        // 注意：这个接口外层 code 恒为 0，真正的扫码状态在内层 data.code 里。
        val data = json.optJSONObject("data")
        val state = data?.optInt("code") ?: 0
        return when (state) {
            0 -> {
                val cookie = pickCookie(responseHeaders, data)
                if (cookie.isBlank()) {
                    QrState.Failure("登录成功但没拿到 Cookie，请重试")
                } else {
                    QrState.Success(cookie)
                }
            }
            86101 -> QrState.Waiting
            86090 -> QrState.Scanned
            86038 -> QrState.Expired
            else -> QrState.Failure(
                data?.optString("message").orEmpty().ifBlank { "登录失败（$state）" }
            )
        }
    }

    /** 用已登录的 Cookie 查昵称和头像，失败返回 null（比如 Cookie 过期）。 */
    suspend fun profile(cookie: String): BiliProfile? = runCatching {
        val json = http.getJson(
            "https://api.bilibili.com/x/web-interface/nav",
            mapOf(
                "User-Agent" to HttpHeaders.DESKTOP_UA,
                "Referer" to "https://www.bilibili.com/",
                "Cookie" to cookie,
            ),
        )
        val data = json.optJSONObject("data") ?: return@runCatching null
        if (!data.optBoolean("isLogin")) return@runCatching null
        BiliProfile(
            mid = data.optLong("mid"),
            name = data.optString("uname"),
            avatar = data.optString("face"),
            level = data.optJSONObject("level_info")?.optInt("current_level") ?: 0,
        )
    }.getOrNull()

    /** 主动注销，让服务端的登录态失效（可选，失败不影响本地清理）。 */
    suspend fun logout(cookie: String) {
        if (cookie.isBlank()) return
        runCatching {
            val biliJct = cookieValue(cookie, "bili_jct")
            http.getText(
                "https://passport.bilibili.com/login/exit/v2?biliCSRF=$biliJct",
                mapOf(
                    "User-Agent" to HttpHeaders.DESKTOP_UA,
                    "Referer" to "https://www.bilibili.com/",
                    "Cookie" to cookie,
                ),
            )
        }
    }

    private fun pickCookie(headers: okhttp3.Headers, data: JSONObject?): String {
        val wanted = listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid")
        val collected = LinkedHashMap<String, String>()

        headers.values("Set-Cookie").forEach { raw ->
            val pair = raw.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name in wanted && value.isNotEmpty()) collected[name] = value
        }

        // 少数情况下响应头缺失，退回到 data.url 里带的参数。
        if (collected["SESSDATA"].isNullOrBlank()) {
            val url = data?.optString("url").orEmpty()
            if (url.contains("SESSDATA=")) {
                val query = url.substringAfter('?', "")
                query.split('&').forEach { pair ->
                    val name = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    if (name in wanted && value.isNotEmpty()) collected[name] = value
                }
            }
        }

        if (collected["SESSDATA"].isNullOrBlank()) return ""
        return collected.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}

/** 从 `k=v; k2=v2` 形式的 Cookie 串里取某一项。 */
fun cookieValue(cookie: String, name: String): String {
    if (cookie.isBlank()) return ""
    return cookie.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        .orEmpty()
}

/** 登录态下弹幕认证要用真实 uid，否则部分房间会被降级。 */
fun cookieUid(cookie: String): Long = cookieValue(cookie, "DedeUserID").toLongOrNull() ?: 0L
