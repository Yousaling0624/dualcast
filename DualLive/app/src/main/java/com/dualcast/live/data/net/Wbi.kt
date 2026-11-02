package com.dualcast.live.data.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TreeMap

/**
 * B 站的 WBI 签名。
 *
 * 2023 年之后，`getDanmuInfo`、`getRoomPlayInfo` 等直播间接口在服务端加了
 * WBI 校验：请求必须带 `wts` 与 `w_rid`，否则一律返回 `-352`。
 * `w_rid = md5(按 key 排序后的 query + mixinKey)`，mixinKey 由 nav 接口下发的
 * 两个图片名按固定表重排后取前 32 位得到。mixinKey 每天会换，这里缓存一小时。
 */
object Wbi {

    /** B 站前端写死的重排表。 */
    private val MIXIN_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    private const val TTL_MS = 3_600_000L

    private val mutex = Mutex()

    @Volatile private var cachedKey: String? = null
    @Volatile private var cachedAt: Long = 0L

    /** 取（并缓存）mixinKey，拿不到时返回空串，调用方降级为不签名。 */
    private suspend fun mixinKey(http: LiveHttp): String {
        val now = System.currentTimeMillis()
        cachedKey?.let { if (now - cachedAt < TTL_MS) return it }
        return mutex.withLock {
            val again = cachedKey
            if (again != null && System.currentTimeMillis() - cachedAt < TTL_MS) return@withLock again
            val key = runCatching {
                val json = http.getJson(
                    "https://api.bilibili.com/x/web-interface/nav",
                    mapOf(
                        "User-Agent" to HttpHeaders.DESKTOP_UA,
                        "Referer" to "https://www.bilibili.com",
                    ),
                )
                val wbi = json.optJSONObject("data")?.optJSONObject("wbi_img") ?: return@runCatching ""
                val img = wbi.optString("img_url").substringAfterLast('/').substringBefore('.')
                val sub = wbi.optString("sub_url").substringAfterLast('/').substringBefore('.')
                val raw = img + sub
                if (raw.length < 64) return@runCatching ""
                buildString { MIXIN_TAB.forEach { append(raw[it]) } }.take(32)
            }.getOrDefault("")
            if (key.isNotBlank()) {
                cachedKey = key
                cachedAt = System.currentTimeMillis()
            }
            key
        }
    }

    /** 把参数签名后拼成可直接拼到 URL 后面的 query string。 */
    suspend fun sign(http: LiveHttp, params: Map<String, String>): String {
        val key = mixinKey(http)
        val sorted = TreeMap<String, String>().apply {
            putAll(params)
            put("wts", (System.currentTimeMillis() / 1000).toString())
        }
        val query = sorted.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(scrub(v))}"
        }
        if (key.isBlank()) return query
        return "$query&w_rid=${md5(query + key)}"
    }

    /** 给已存在的 query 补签名，用于参数已经在字符串里拼好的场景。 */
    suspend fun signUrl(http: LiveHttp, url: String): String {
        val base = url.substringBefore('?')
        val raw = url.substringAfter('?', "")
        if (raw.isBlank()) return url
        val params = LinkedHashMap<String, String>()
        raw.split('&').filter { it.isNotBlank() }.forEach { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            params[decode(k)] = decode(v)
        }
        return "$base?${sign(http, params)}"
    }

    /** B 站会过滤掉这几个字符再算签名。 */
    private fun scrub(value: String): String =
        value.filterNot { it == '!' || it == '\'' || it == '(' || it == ')' || it == '*' }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val hex = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            hex[index * 2] = digits[value ushr 4]
            hex[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(hex)
    }
}
