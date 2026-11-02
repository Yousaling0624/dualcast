package com.dualcast.live.data.net

import com.dualcast.live.data.model.PlaySource
import com.dualcast.live.data.model.RoomInfo

/**
 * 输入解析与统一门面：无论用户粘的是链接还是房间号，都收敛成 B 站 API 需要的 id。
 */
class RoomResolver(private val bili: BiliApi) {

    fun parseInput(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) throw ApiException(-400, "请先填写直播间链接或房间号")
        val fromUrl = Regex("live\\.bilibili\\.com/(?:blanc/)?(\\d+)")
            .find(input)?.groupValues?.getOrNull(1)
        val digits = Regex("^\\d{1,12}$").find(input)?.value
        return fromUrl ?: digits
        ?: throw ApiException(-400, "没认出 B 站房间号，试试 live.bilibili.com/12345")
    }

    suspend fun resolve(raw: String): RoomInfo = bili.fetchRoom(parseInput(raw))

    suspend fun playSource(room: RoomInfo, qn: Int): PlaySource = bili.playSource(room, qn)
}
