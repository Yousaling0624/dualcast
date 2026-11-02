package com.dualcast.live.data.net

import com.dualcast.live.data.model.LiveStatus
import com.dualcast.live.data.model.PlaySource
import com.dualcast.live.data.model.RoomInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * B 站直播接口。参考 web 端 live.bilibili.com 的请求方式，
 * 需要带 Referer 与 buvid，否则弹幕 token 很容易被 -352 风控拦截。
 */
class BiliApi(private val http: LiveHttp) {

    suspend fun fetchRoom(roomId: String): RoomInfo {
        http.ensureBuvid()
        val json = http.getJson(
            "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=$roomId",
            headers(roomId),
        )
        ensureOk(json)
        val data = json.getJSONObject("data")
        val realId = data.optLong("room_id").toString()
        val status = when (data.optInt("live_status")) {
            1 -> LiveStatus.LIVE
            2 -> LiveStatus.ROUND_PLAY
            0 -> LiveStatus.OFFLINE
            else -> LiveStatus.UNKNOWN
        }
        val cover = data.optString("user_cover").ifBlank { data.optString("keyframe") }
        val title = data.optString("title").ifBlank { "房间 $realId" }
        val online = data.optLong("online")
        val anchor = runCatching { fetchAnchorName(realId) }.getOrDefault("")

        return RoomInfo(
            roomId = realId,
            title = title,
            anchor = anchor.ifBlank { "UP ${data.optLong("uid")}" },
            coverUrl = https(cover),
            keyframeUrl = https(data.optString("keyframe")),
            liveStatus = status,
            onlineText = if (online > 0) "${formatCount(online)}人气" else "",
            areaName = data.optString("area_name"),
        )
    }

    private suspend fun fetchAnchorName(roomId: String): String {
        val json = http.getJson(
            "https://api.live.bilibili.com/live_user/v1/UserInfo/get_anchor_in_room?roomid=$roomId",
            headers(roomId),
        )
        return json.optJSONObject("data")
            ?.optJSONObject("info")
            ?.optString("uname")
            .orEmpty()
    }

    suspend fun playSource(room: RoomInfo, qn: Int): PlaySource {
        http.ensureBuvid()
        val url = buildString {
            append("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo")
            append("?room_id=${room.roomId}")
            append("&protocol=0,1&format=0,1,2&codec=0,1")
            append("&qn=$qn&platform=web&ptype=8&dolby=5&panorama=1")
        }
        var json = http.getJson(url, headers(room.roomId))
        // 个别账号/IP 会被要求带 WBI 签名，失败就签一次再试，避免直接卡死
        if (json.optInt("code") == -352) {
            val signed = Wbi.signUrl(http, url)
            json = http.getJson(signed, headers(room.roomId))
        }
        ensureOk(json)
        val data = json.getJSONObject("data")
        if (data.optInt("live_status") == 0) {
            throw ApiException(-1, "直播间当前未开播")
        }
        val playurlInfo = data.optJSONObject("playurl_info")
            ?: throw ApiException(-1, "拿不到播放地址，可能被风控或需要登录")

        val playurl = playurlInfo.getJSONObject("playurl")
        val streams = playurl.optJSONArray("stream") ?: JSONArray()

        val flvUrls = mutableListOf<String>()
        val hlsUrls = mutableListOf<String>()
        var qualityLabel = ""

        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val protocol = stream.optString("protocol_name")
            val formats = stream.optJSONArray("format") ?: continue
            for (j in 0 until formats.length()) {
                val format = formats.optJSONObject(j) ?: continue
                val formatName = format.optString("format_name")
                val codecs = format.optJSONArray("codec") ?: continue
                for (k in 0 until codecs.length()) {
                    val codec = codecs.optJSONObject(k) ?: continue
                    val codecName = codec.optString("codec_name")
                    if (codecName == "hevc") continue
                    val base = codec.optString("base_url")
                    if (base.isBlank()) continue
                    val infos = codec.optJSONArray("url_info") ?: continue
                    for (m in 0 until infos.length()) {
                        val info = infos.optJSONObject(m) ?: continue
                        val candidate = info.optString("host") + base + info.optString("extra")
                        if (candidate.isBlank()) continue
                        if (protocol == "http_stream" && formatName == "flv") {
                            flvUrls.add(candidate)
                        } else if (protocol == "http_hls") {
                            hlsUrls.add(candidate)
                        }
                    }
                    if (qualityLabel.isBlank()) {
                        qualityLabel = qnLabel(codec.optInt("current_qn"))
                    }
                }
            }
        }

        val urls = (flvUrls + hlsUrls).distinct().take(6)
        if (urls.isEmpty()) throw ApiException(-1, "播放地址为空，可能需要登录或已下播")

        return PlaySource(
            room = room,
            urls = urls,
            headers = playbackHeaders(room.roomId),
            qualityLabel = qualityLabel,
        )
    }

    /**
     * 发一条弹幕。
     *
     * 参数照抄 PiliPlus 的 `LiveHttp.sendLiveMsg`（第三方客户端里维护得最勤的一个）：
     * - 接口带 WBI 签名的 `web_location`，不签名偶尔会被 -352 挡回来；
     * - `csrf` 与 `csrf_token` 都必须等于 Cookie 里的 `bili_jct`；
     * - 除了正文，还带一组 `reply_*` 字段。B 站直播的「回复某条弹幕」就是靠
     *   `reply_mid` + `replay_dmid` 实现的（官方字段拼写确实是 replay，不是 reply）；
     * - `rnd` 是秒级时间戳，服务端拿它做重放校验，每次都必须新生成。
     */
    /**
     * 发一条直播弹幕。
     *
     * 只发纯文本。B 站直播没有「楼中楼」，回复在客户端拼成
     * `{回复}@{昵称}:{原弹幕}` 一次性发出去，不再带 reply_mid / replay_dmid，
     * 免得服务端把它当成一条格式不完整的回复。
     */
    suspend fun sendDanmaku(
        roomId: String,
        text: String,
        cookie: String,
    ): String {
        val csrf = cookieValue(cookie, "bili_jct")
        if (cookie.isBlank() || csrf.isBlank()) {
            throw ApiException(-101, "发弹幕需要先登录 B 站账号")
        }
        http.ensureBuvid()

        val signed = runCatching {
            Wbi.sign(http, mapOf("web_location" to "444.8"))
        }.getOrElse { "web_location=444.8" }

        val form = mapOf(
            "bubble" to "0",
            "msg" to text,
            "color" to "16777215",
            "mode" to "1",
            "room_type" to "0",
            "jumpfrom" to "0",
            "fontsize" to "25",
            "rnd" to (System.currentTimeMillis() / 1000).toString(),
            "roomid" to roomId,
            "csrf" to csrf,
            "csrf_token" to csrf,
        )
        val headers = mapOf(
            "User-Agent" to HttpHeaders.DESKTOP_UA,
            "Referer" to "https://live.bilibili.com/$roomId",
            "Origin" to "https://live.bilibili.com",
            "Cookie" to http.biliCookie(cookie),
        )

        val base = "https://api.live.bilibili.com/msg/send"
        var json = http.postForm("$base?$signed", form, headers)
        if (json.optInt("code", 0) == -352) {
            // 本机时间偏差会让 w_rid 永远对不上，退回不签名再试一次
            json = http.postForm(base, form, headers)
        }

        val code = json.optInt("code", 0)
        val message = json.optString("message").ifBlank { json.optString("msg") }
        // code=0 但 message 是 "f" 时，服务端其实把这条当敏感词吃掉了
        if (code == 0 && message != "f") return message
        if (code == 0) throw ApiException(10030, "这条被内容过滤拦下了，换个说法")
        throw ApiException(code, sendErrorText(code, message))
    }

    private fun sendErrorText(code: Int, raw: String): String = when (code) {
        -101 -> "登录状态失效了，去设置里重新登录"
        -111 -> "登录校验失败（csrf 不匹配），请重新登录"
        -403 -> "当前账号没有发弹幕权限"
        10030 -> "弹幕内容不合法，可能有敏感词"
        10031 -> "发得太快了，等几秒再发"
        10032 -> "你在这个直播间被禁言了"
        10033 -> "账号等级不够，发不了弹幕"
        10034 -> "粉丝勋章等级不够，发不了弹幕"
        10035 -> "弹幕包含敏感内容"
        1003212 -> "相同内容重复发送，换一句试试"
        else -> raw.ifBlank { "发送失败（$code）" }
    }

    suspend fun danmakuEndpoint(roomId: String): BiliDanmakuEndpoint {
        http.ensureBuvid()
        // 这个接口 2023 年后强制校验 WBI 签名，不签名一律 -352，与 Cookie 无关。
        val query = Wbi.sign(http, mapOf("id" to roomId, "type" to "0"))
        val json = http.getJson(
            "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?$query",
            headers(roomId),
        )
        if (json.optInt("code") == -352) {
            throw ApiException(
                -352,
                "B站接口校验失败（-352）：请确认手机时间准确、网络正常，然后再重试",
            )
        }
        ensureOk(json)
        val data = json.getJSONObject("data")
        val token = data.optString("token")
        val hosts = data.optJSONArray("host_list") ?: JSONArray()
        val urls = buildList {
            for (i in 0 until hosts.length()) {
                val host = hosts.optJSONObject(i) ?: continue
                val name = host.optString("host")
                if (name.isBlank()) continue
                val port = host.optInt("wss_port").takeIf { it > 0 } ?: 443
                add("wss://$name:$port/sub")
            }
        }
        if (urls.isEmpty()) throw ApiException(-1, "弹幕服务器列表为空")
        return BiliDanmakuEndpoint(token = token, urls = urls)
    }

    fun headers(roomId: String): Map<String, String> = mapOf(
        "User-Agent" to HttpHeaders.DESKTOP_UA,
        "Referer" to "https://live.bilibili.com/$roomId",
        "Origin" to "https://live.bilibili.com",
        "Cookie" to http.biliCookie(userCookie),
    )

    private fun playbackHeaders(roomId: String): Map<String, String> = mapOf(
        "User-Agent" to HttpHeaders.DESKTOP_UA,
        "Referer" to "https://live.bilibili.com/$roomId",
        "Origin" to "https://live.bilibili.com",
    )

    /** 由 AppContainer 在构造后注入，避免接口层直接依赖 DataStore。 */
    var userCookie: String = ""

    private fun ensureOk(json: JSONObject) {
        val code = json.optInt("code", 0)
        if (code == 0) return
        val message = when (code) {
            -352 -> "B站接口校验失败（-352），请稍后重试或检查网络"
            -400 -> "房间号格式不正确"
            60004 -> "直播间不存在或已关闭"
            else -> json.optString("message").ifBlank { "请求失败（$code）" }
        }
        throw ApiException(code, message)
    }

    private fun qnLabel(qn: Int): String = when {
        qn >= 10000 -> "原画"
        qn >= 400 -> "蓝光"
        qn >= 250 -> "超清"
        qn >= 150 -> "高清"
        qn > 0 -> "流畅"
        else -> ""
    }

    private fun https(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> url.replaceFirst("http://", "https://")
        else -> url
    }

    private fun formatCount(value: Long): String = when {
        value >= 100_000_000 -> String.format("%.1f亿", value / 100_000_000.0)
        value >= 10_000 -> String.format("%.1f万", value / 10_000.0)
        else -> value.toString()
    }
}

data class BiliDanmakuEndpoint(
    val token: String,
    val urls: List<String>,
)
