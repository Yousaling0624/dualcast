package com.dualcast.live.data

import com.dualcast.live.data.model.QualityOption

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 莫奈取色来源：跟随系统壁纸（Android 12+）或自定义种子色。 */
enum class MonetMode { WALLPAPER, SEED }

enum class DanmakuStyle { OUTLINE, SHADOW, PLAIN }

enum class VideoScale { FIT, CROP, FILL }

data class DanmakuSettings(
    val enabled: Boolean = true,
    val fontScale: Float = 1f,
    val alpha: Float = 0.94f,
    val speed: Float = 1f,
    val area: Float = 0.62f,
    val showUserName: Boolean = false,
    val bold: Boolean = true,
    val style: DanmakuStyle = DanmakuStyle.OUTLINE,
    val blockedWords: List<String> = emptyList(),
)

data class PlaybackSettings(
    val aVolume: Float = 0.85f,
    val bVolume: Float = 1f,
    val scale: VideoScale = VideoScale.FIT,
    /** 切到后台 / 锁屏后继续播放声音。 */
    val backgroundPlay: Boolean = false,
    /** 播放页是否显示 A 解说小窗（会给 A 打开视频解码）。 */
    val commentaryWindow: Boolean = false,
)

/**
 * 某个「A 房间 + B 房间」组合记住的混音参数。
 *
 * 音量配比和声音偏移都跟具体两个流有关：换个比赛画面，B 的音量、
 * 以及 B 相对 A 的延迟就全变了。所以按房间组合存，而不是全局存。
 */
data class RoomProfile(
    val aRoomId: String,
    val bRoomId: String,
    val aVolume: Float,
    val bVolume: Float,
    val offsetMs: Int,
    val updatedAt: Long = 0L,
)

data class BiliAccount(
    val mid: Long = 0L,
    val name: String = "",
    val avatar: String = "",
    val level: Int = 0,
) {
    val loggedIn: Boolean get() = mid > 0L
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val monetMode: MonetMode = MonetMode.WALLPAPER,
    val seedColor: Long = 0xFF6750A4,
    val danmaku: DanmakuSettings = DanmakuSettings(),
    val playback: PlaybackSettings = PlaybackSettings(),
    val biliCookie: String = "",
    /**
     * 清晰度。默认「超清」而不是「原画」：原画档码率高，移动网络喂不饱缓冲，
     * 表现就是播一秒卡十几秒。
     */
    val qualityQn: Int = 250,
    val biliAccount: BiliAccount = BiliAccount(),
) {
    companion object {
        val QUALITY_OPTIONS = listOf(
            QualityOption("原画", 10000),
            QualityOption("蓝光", 400),
            QualityOption("超清", 250),
            QualityOption("高清", 150),
        )
    }
}
