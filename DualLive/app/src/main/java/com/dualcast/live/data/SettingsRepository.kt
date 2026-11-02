package com.dualcast.live.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dualcast.live.data.model.RecentRoom
import com.dualcast.live.data.model.RoomCombo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dualcast")

/** 首页某一路的持久化快照，下次打开始播页时直接恢复。 */
data class SlotSnapshot(val input: String)

data class SavedSlots(val a: SlotSnapshot? = null, val b: SlotSnapshot? = null)

class SettingsRepository(private val context: Context) {

    /** 「读-改-写」房间配置 / 组合时串行化，避免快速拖动音量时互相覆盖。 */
    private val mutex = Mutex()


    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val monetMode = stringPreferencesKey("monet_mode")
        val seedColor = longPreferencesKey("seed_color")
        val qualityQn = intPreferencesKey("quality_qn")
        val biliCookie = stringPreferencesKey("bili_cookie")

        val dmEnabled = booleanPreferencesKey("dm_enabled")
        val dmFontScale = floatPreferencesKey("dm_font_scale")
        val dmAlpha = floatPreferencesKey("dm_alpha")
        val dmSpeed = floatPreferencesKey("dm_speed")
        val dmArea = floatPreferencesKey("dm_area")
        val dmShowName = booleanPreferencesKey("dm_show_name")
        val dmBold = booleanPreferencesKey("dm_bold")
        val dmStyle = stringPreferencesKey("dm_style")
        val dmBlocked = stringPreferencesKey("dm_blocked")

        val pbAVolume = floatPreferencesKey("pb_a_volume")
        val pbBVolume = floatPreferencesKey("pb_b_volume")
        val pbScale = stringPreferencesKey("pb_scale")
        val pbBackground = booleanPreferencesKey("pb_background_play")
        val pbCommentaryWindow = booleanPreferencesKey("pb_commentary_window")

        val roomProfiles = stringPreferencesKey("room_profiles")
        val roomCombos = stringPreferencesKey("room_combos")

        val accMid = longPreferencesKey("bili_mid")
        val accName = stringPreferencesKey("bili_name")
        val accAvatar = stringPreferencesKey("bili_avatar")
        val accLevel = intPreferencesKey("bili_level")

        val recent = stringPreferencesKey("recent_rooms")
        val slotA = stringPreferencesKey("home_slot_a")
        val slotB = stringPreferencesKey("home_slot_b")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            themeMode = prefs[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: defaults.themeMode,
            monetMode = prefs[Keys.monetMode]?.let { runCatching { MonetMode.valueOf(it) }.getOrNull() }
                ?: defaults.monetMode,
            seedColor = prefs[Keys.seedColor] ?: defaults.seedColor,
            qualityQn = prefs[Keys.qualityQn] ?: defaults.qualityQn,
            biliCookie = prefs[Keys.biliCookie].orEmpty(),
            danmaku = DanmakuSettings(
                enabled = prefs[Keys.dmEnabled] ?: defaults.danmaku.enabled,
                fontScale = prefs[Keys.dmFontScale] ?: defaults.danmaku.fontScale,
                alpha = prefs[Keys.dmAlpha] ?: defaults.danmaku.alpha,
                speed = prefs[Keys.dmSpeed] ?: defaults.danmaku.speed,
                area = prefs[Keys.dmArea] ?: defaults.danmaku.area,
                showUserName = prefs[Keys.dmShowName] ?: defaults.danmaku.showUserName,
                bold = prefs[Keys.dmBold] ?: defaults.danmaku.bold,
                style = prefs[Keys.dmStyle]?.let { runCatching { DanmakuStyle.valueOf(it) }.getOrNull() }
                    ?: defaults.danmaku.style,
                blockedWords = prefs[Keys.dmBlocked]?.split('\n')?.filter { it.isNotBlank() }
                    ?: defaults.danmaku.blockedWords,
            ),
            playback = PlaybackSettings(
                aVolume = prefs[Keys.pbAVolume] ?: defaults.playback.aVolume,
                bVolume = prefs[Keys.pbBVolume] ?: defaults.playback.bVolume,
                scale = prefs[Keys.pbScale]?.let { runCatching { VideoScale.valueOf(it) }.getOrNull() }
                    ?: defaults.playback.scale,
                backgroundPlay = prefs[Keys.pbBackground] ?: defaults.playback.backgroundPlay,
                commentaryWindow = prefs[Keys.pbCommentaryWindow] ?: defaults.playback.commentaryWindow,
            ),
            biliAccount = BiliAccount(
                mid = prefs[Keys.accMid] ?: 0L,
                name = prefs[Keys.accName].orEmpty(),
                avatar = prefs[Keys.accAvatar].orEmpty(),
                level = prefs[Keys.accLevel] ?: 0,
            ),
        )
    }

    val recentRooms: Flow<List<RecentRoom>> = context.dataStore.data.map { prefs ->
        decodeRecent(prefs[Keys.recent].orEmpty())
    }

    /** 上次在首页填的 A/B 两路，用于冷启动后自动恢复。 */
    val savedSlots: Flow<SavedSlots> = context.dataStore.data.map { prefs ->
        SavedSlots(
            a = decodeSlot(prefs[Keys.slotA]),
            b = decodeSlot(prefs[Keys.slotB]),
        )
    }

    /** 「我的组合」：常看的「主播 + 比赛」组合，最近的在前。 */
    val combos: Flow<List<RoomCombo>> = context.dataStore.data.map { prefs ->
        decodeCombos(prefs[Keys.roomCombos].orEmpty())
    }

    suspend fun snapshot(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = put { it[Keys.themeMode] = mode.name }
    suspend fun setMonetMode(mode: MonetMode) = put { it[Keys.monetMode] = mode.name }
    suspend fun setSeedColor(color: Long) = put { it[Keys.seedColor] = color }
    suspend fun setQuality(qn: Int) = put { it[Keys.qualityQn] = qn }
    suspend fun setBiliCookie(value: String) = put { it[Keys.biliCookie] = value.trim() }

    suspend fun setDanmakuEnabled(enabled: Boolean) = put { it[Keys.dmEnabled] = enabled }
    suspend fun setFontScale(scale: Float) = put { it[Keys.dmFontScale] = scale.coerceIn(0.6f, 2f) }
    suspend fun setDanmakuAlpha(alpha: Float) = put { it[Keys.dmAlpha] = alpha.coerceIn(0.15f, 1f) }
    suspend fun setDanmakuSpeed(speed: Float) = put { it[Keys.dmSpeed] = speed.coerceIn(0.4f, 2.5f) }
    suspend fun setDanmakuArea(area: Float) = put { it[Keys.dmArea] = area.coerceIn(0.2f, 1f) }
    suspend fun setShowUserName(show: Boolean) = put { it[Keys.dmShowName] = show }
    suspend fun setBold(bold: Boolean) = put { it[Keys.dmBold] = bold }
    suspend fun setStyle(style: DanmakuStyle) = put { it[Keys.dmStyle] = style.name }
    suspend fun setBlockedWords(words: List<String>) =
        put { it[Keys.dmBlocked] = words.joinToString("\n") { w -> w.trim() }.trim() }

    suspend fun setAVolume(volume: Float) = put { it[Keys.pbAVolume] = volume.coerceIn(0f, 1f) }
    suspend fun setBVolume(volume: Float) = put { it[Keys.pbBVolume] = volume.coerceIn(0f, 1f) }
    suspend fun setScale(scale: VideoScale) = put { it[Keys.pbScale] = scale.name }
    suspend fun setBackgroundPlay(enabled: Boolean) = put { it[Keys.pbBackground] = enabled }
    suspend fun setCommentaryWindow(enabled: Boolean) = put { it[Keys.pbCommentaryWindow] = enabled }

    /**
     * 取某个房间组合的混音配置。
     *
     * 找不到正序的，就反过来找一次：用户把 A/B 对调过之后，
     * 同一对房间只是角色互换，音量和偏移镜像一下就能直接用。
     */
    suspend fun roomProfile(aRoomId: String, bRoomId: String): RoomProfile? {
        val all = decodeProfiles(context.dataStore.data.first()[Keys.roomProfiles].orEmpty())
        all.firstOrNull { it.aRoomId == aRoomId && it.bRoomId == bRoomId }?.let { return it }
        val reversed = all.firstOrNull { it.aRoomId == bRoomId && it.bRoomId == aRoomId }
            ?: return null
        return reversed.copy(
            aRoomId = aRoomId,
            bRoomId = bRoomId,
            aVolume = reversed.bVolume,
            bVolume = reversed.aVolume,
            offsetMs = -reversed.offsetMs,
        )
    }

    /** 记住一个房间组合的音量 / 偏移，最多保留最近 40 组。 */
    suspend fun saveRoomProfile(profile: RoomProfile) = mutex.withLock {
        val current = decodeProfiles(context.dataStore.data.first()[Keys.roomProfiles].orEmpty())
        val next = buildList {
            add(profile)
            current.forEach {
                if (it.aRoomId != profile.aRoomId || it.bRoomId != profile.bRoomId) add(it)
            }
        }.take(MAX_ROOM_PROFILES)
        put { it[Keys.roomProfiles] = encodeProfiles(next) }
    }

    suspend fun setBiliAccount(account: BiliAccount) = put {
        it[Keys.accMid] = account.mid
        it[Keys.accName] = account.name
        it[Keys.accAvatar] = account.avatar
        it[Keys.accLevel] = account.level
    }

    /** 扫码登录成功后一次性写入 Cookie 与账号信息。 */
    suspend fun signIn(cookie: String, account: BiliAccount) = put {
        it[Keys.biliCookie] = cookie.trim()
        it[Keys.accMid] = account.mid
        it[Keys.accName] = account.name
        it[Keys.accAvatar] = account.avatar
        it[Keys.accLevel] = account.level
    }

    suspend fun signOut() = put {
        it.remove(Keys.biliCookie)
        it.remove(Keys.accMid)
        it.remove(Keys.accName)
        it.remove(Keys.accAvatar)
        it.remove(Keys.accLevel)
    }

    suspend fun addRecent(room: RecentRoom) {
        val current = recentRooms.first().filterNot {
            it.input.equals(room.input, ignoreCase = true)
        }
        val next = (listOf(room) + current).take(8)
        put { it[Keys.recent] = encodeRecent(next) }
    }

    suspend fun clearRecent() = put { it.remove(Keys.recent) }

    suspend fun saveSlots(a: SlotSnapshot, b: SlotSnapshot) = put {
        it[Keys.slotA] = encodeSlot(a)
        it[Keys.slotB] = encodeSlot(b)
    }

    /** 存一个组合。同一对房间只保留一条，最多 12 条。 */
    suspend fun saveCombo(combo: RoomCombo) = mutex.withLock {
        val current = decodeCombos(context.dataStore.data.first()[Keys.roomCombos].orEmpty())
        val next = buildList {
            add(combo)
            current.forEach {
                if (it.aRoomId != combo.aRoomId || it.bRoomId != combo.bRoomId) add(it)
            }
        }.take(MAX_COMBOS)
        put { it[Keys.roomCombos] = encodeCombos(next) }
    }

    suspend fun deleteCombo(aRoomId: String, bRoomId: String) = mutex.withLock {
        val current = decodeCombos(context.dataStore.data.first()[Keys.roomCombos].orEmpty())
        val next = current.filterNot { it.aRoomId == aRoomId && it.bRoomId == bRoomId }
        put { it[Keys.roomCombos] = encodeCombos(next) }
    }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    private fun encodeSlot(slot: SlotSnapshot): String = Uri.encode(slot.input.trim())

    /**
     * 兼容 v1.0.4 及之前写下的 `平台名|房间号` 格式。
     * 虎牙那一路直接丢弃，只把 B 站的房间号带过来。
     */
    private fun decodeSlot(raw: String?): SlotSnapshot? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|', limit = 2)
        if (parts.size == 2 && isLegacyPlatform(parts[0])) {
            if (parts[0] != LEGACY_BILI) return null
            val legacy = Uri.decode(parts[1])
            return if (legacy.isBlank()) null else SlotSnapshot(legacy)
        }
        val input = Uri.decode(raw)
        return if (input.isBlank()) null else SlotSnapshot(input)
    }

    private fun encodeRecent(list: List<RecentRoom>): String = list.joinToString("\n") { room ->
        listOf(
            Uri.encode(room.input),
            Uri.encode(room.title),
            room.usedAt.toString(),
        ).joinToString("|")
    }

    private fun decodeRecent(raw: String): List<RecentRoom> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            // 旧格式多一段平台名，跳过它；虎牙的记录不再恢复
            val old = parts.size >= 4 && isLegacyPlatform(parts[0])
            if (old && parts[0] != LEGACY_BILI) return@mapNotNull null
            val body = if (old) parts.drop(1) else parts
            if (body.size < 3) return@mapNotNull null
            RecentRoom(
                input = Uri.decode(body[0]),
                title = Uri.decode(body[1]),
                usedAt = body[2].toLongOrNull() ?: 0L,
            )
        }.toList()
    }

    private fun isLegacyPlatform(value: String): Boolean =
        value == LEGACY_BILI || value == "HUYA"

    private fun encodeCombos(list: List<RoomCombo>): String = list.joinToString("\n") { c ->
        listOf(
            Uri.encode(c.aInput),
            Uri.encode(c.aRoomId),
            Uri.encode(c.aTitle),
            Uri.encode(c.aAnchor),
            Uri.encode(c.bInput),
            Uri.encode(c.bRoomId),
            Uri.encode(c.bTitle),
            Uri.encode(c.bAnchor),
            c.updatedAt.toString(),
        ).joinToString("|")
    }

    private fun decodeCombos(raw: String): List<RoomCombo> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 9) return@mapNotNull null
            if (parts[1].isBlank() || parts[5].isBlank()) return@mapNotNull null
            RoomCombo(
                aInput = Uri.decode(parts[0]),
                aRoomId = parts[1],
                aTitle = Uri.decode(parts[2]),
                aAnchor = Uri.decode(parts[3]),
                bInput = Uri.decode(parts[4]),
                bRoomId = parts[5],
                bTitle = Uri.decode(parts[6]),
                bAnchor = Uri.decode(parts[7]),
                updatedAt = parts[8].toLongOrNull() ?: 0L,
            )
        }.toList()
    }

    private fun encodeProfiles(list: List<RoomProfile>): String = list.joinToString("\n") { p ->
        listOf(
            p.aRoomId,
            p.bRoomId,
            p.aVolume.toString(),
            p.bVolume.toString(),
            p.offsetMs.toString(),
            p.updatedAt.toString(),
        ).joinToString("|")
    }

    private fun decodeProfiles(raw: String): List<RoomProfile> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 6) return@mapNotNull null
            val a = parts[2].toFloatOrNull() ?: return@mapNotNull null
            val b = parts[3].toFloatOrNull() ?: return@mapNotNull null
            val offset = parts[4].toIntOrNull() ?: return@mapNotNull null
            if (parts[0].isBlank() || parts[1].isBlank()) return@mapNotNull null
            RoomProfile(
                aRoomId = parts[0],
                bRoomId = parts[1],
                aVolume = a.coerceIn(0f, 1f),
                bVolume = b.coerceIn(0f, 1f),
                offsetMs = offset.coerceIn(-6_000, 6_000),
                updatedAt = parts[5].toLongOrNull() ?: 0L,
            )
        }.toList()
    }

    private companion object {
        const val LEGACY_BILI = "BILIBILI"
        const val MAX_ROOM_PROFILES = 40
        const val MAX_COMBOS = 12
    }
}
