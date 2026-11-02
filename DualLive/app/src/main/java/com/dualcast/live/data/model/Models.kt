package com.dualcast.live.data.model

enum class LiveStatus {
    LIVE,
    ROUND_PLAY,
    OFFLINE,
    UNKNOWN;

    val isPlayable: Boolean get() = this == LIVE || this == ROUND_PLAY
}

data class RoomInfo(
    val roomId: String,
    val title: String,
    val anchor: String,
    val coverUrl: String,
    val liveStatus: LiveStatus,
    val onlineText: String = "",
    val areaName: String = "",
    val keyframeUrl: String = "",
)

data class PlaySource(
    val room: RoomInfo,
    val urls: List<String>,
    val headers: Map<String, String>,
    val qualityLabel: String = "",
) {
    val primaryUrl: String get() = urls.first()
}

data class QualityOption(val label: String, val qn: Int)

enum class DanmakuType { CHAT, SUPER_CHAT, GIFT, MEMBER, SYSTEM }

data class DanmakuMessage(
    val text: String,
    val user: String = "",
    val color: Int = 0xFFFFFFFF.toInt(),
    val type: DanmakuType = DanmakuType.CHAT,
    val timestamp: Long = System.currentTimeMillis(),
    /** 发送者 uid，回复弹幕时要带给服务端。 */
    val uid: Long = 0L,
    /** 这条弹幕的 dmid，回复时用来定位被回复的那一条。 */
    val dmid: String = "",
    /** 是不是我自己刚发的，用来在弹幕流里高亮。 */
    val isSelf: Boolean = false,
    /**
     * 进聊天室时分配的本地序号，保证 LazyColumn 的 key 唯一。
     * 弹幕流里的消息是 0，只有聊天室日志会填。
     */
    val seq: Long = 0L,
) {
    val isHighlight: Boolean get() = type != DanmakuType.CHAT
    val canReply: Boolean get() = uid > 0L && type == DanmakuType.CHAT
}

data class RecentRoom(
    val input: String,
    val title: String,
    val usedAt: Long,
)

/**
 * 用户存下来的「主播 + 比赛」组合。
 *
 * 同时存 input 和解析后的标题：首页不用再请求一次接口就能显示卡片，
 * 点开时再用 input 重新解析（直播间地址可能变，房间号不会）。
 */
data class RoomCombo(
    val aInput: String,
    val aRoomId: String,
    val aTitle: String,
    val aAnchor: String,
    val bInput: String,
    val bRoomId: String,
    val bTitle: String,
    val bAnchor: String,
    val updatedAt: Long = 0L,
)
