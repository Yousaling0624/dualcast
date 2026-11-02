package com.dualcast.live.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dualcast.live.DualCastApp
import com.dualcast.live.data.AppSettings
import com.dualcast.live.data.BiliAccount
import com.dualcast.live.data.DanmakuStyle
import com.dualcast.live.data.MonetMode
import com.dualcast.live.data.RoomProfile
import com.dualcast.live.data.SettingsRepository
import com.dualcast.live.data.SlotSnapshot
import com.dualcast.live.data.ThemeMode
import com.dualcast.live.data.VideoScale
import com.dualcast.live.data.danmaku.BiliDanmakuClient
import com.dualcast.live.data.danmaku.DanmakuClient
import com.dualcast.live.data.danmaku.DanmakuStatus
import com.dualcast.live.data.model.DanmakuMessage
import com.dualcast.live.data.model.DanmakuText
import com.dualcast.live.data.model.PlaySource
import com.dualcast.live.data.model.RecentRoom
import com.dualcast.live.data.model.RoomCombo
import com.dualcast.live.data.model.RoomInfo
import com.dualcast.live.data.net.QrState
import com.dualcast.live.data.net.QrTicket
import com.dualcast.live.data.net.cookieUid
import com.dualcast.live.player.DualPlayer
import com.dualcast.live.player.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Slot { A, B }

sealed interface Screen {
    data object Home : Screen
    data object Player : Screen
    data object Settings : Screen
}

data class SourceState(
    val input: String = "",
    val room: RoomInfo? = null,
    val resolving: Boolean = false,
    val error: String? = null,
) {
    val ready: Boolean get() = room != null && !resolving
}

data class HomeUiState(
    val a: SourceState = SourceState(),
    val b: SourceState = SourceState(),
    val recent: List<RecentRoom> = emptyList(),
    /** 存下来的常看组合，最近的在前。 */
    val combos: List<RoomCombo> = emptyList(),
    val starting: Boolean = false,
    val startError: String? = null,
)

data class SessionState(
    val a: PlaySource,
    val b: PlaySource,
    val danmakuStatus: DanmakuStatus = DanmakuStatus.Connecting,
)

data class PlayerUiState(
    val aPlaying: Boolean = false,
    val bPlaying: Boolean = false,
    val error: String? = null,
    /** 正在重新解析 B 的播放地址（切换清晰度）。 */
    val switchingQuality: Boolean = false,
    /** 正在互换 A / B 的画面。 */
    val swapping: Boolean = false,
    /** 正在手动重连两路。 */
    val reconnecting: Boolean = false,
    /** 手动声音偏移：正值 A 比 B 晚。 */
    val offsetMs: Int = 0,
    /**
     * 本次会话实际生效的音量。可能来自「这个房间组合上次的记忆」，
     * 所以不能再直接读全局设置，否则滑杆和实际声音会对不上。
     */
    val aVolume: Float = 0.85f,
    val bVolume: Float = 1f,
)

data class LoginUiState(
    val ticket: QrTicket? = null,
    val state: QrState? = null,
    val loading: Boolean = false,
    val scanned: Boolean = false,
) {
    val expired: Boolean get() = state is QrState.Expired
    val success: Boolean get() = state is QrState.Success
}

/**
 * 发弹幕的临时状态，和播放状态分开存：
 * 打字、失败、冷却都不该让整个播放页重组。
 */
data class SendState(
    val sending: Boolean = false,
    val error: String? = null,
    /** 每成功一次 +1，界面靠它清空输入框。 */
    val sentSeq: Long = 0L,
    /** 冷却截止时间（elapsedRealtime）。B 站大约 1.5 秒才收一条。 */
    val cooldownUntil: Long = 0L,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as DualCastApp).container

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _session = MutableStateFlow<SessionState?>(null)
    val session: StateFlow<SessionState?> = _session.asStateFlow()

    private val _playerUi = MutableStateFlow(PlayerUiState())
    val playerUi: StateFlow<PlayerUiState> = _playerUi.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _login = MutableStateFlow(LoginUiState())
    val login: StateFlow<LoginUiState> = _login.asStateFlow()

    private val _danmaku = MutableSharedFlow<DanmakuMessage>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val danmaku = _danmaku.asSharedFlow()

    /** 最近收到的弹幕，最新的在最前面。弹幕面板里的「+1 / 回复」从这里取。 */
    private val _recentDanmaku = MutableStateFlow<List<DanmakuMessage>>(emptyList())
    val recentDanmaku: StateFlow<List<DanmakuMessage>> = _recentDanmaku.asStateFlow()

    private val _sendState = MutableStateFlow(SendState())
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    /** 自己刚发出去的文案 + 过期时间，用来吞掉 WebSocket 回显，避免同一条出现两次。 */
    private val pendingSelfEcho = ArrayDeque<Pair<String, Long>>()

    private var player: DualPlayer? = null
    private var danmakuClient: DanmakuClient? = null
    private var danmakuJob: Job? = null
    private var statusJob: Job? = null
    private var loginJob: Job? = null
    private var profileSaveJob: Job? = null
    private var lastRefreshAt = 0L
    private var danmakuSeq = 0L

    init {
        viewModelScope.launch {
            container.settings.recentRooms.collect { recent ->
                _home.update { it.copy(recent = recent) }
            }
        }
        viewModelScope.launch {
            container.settings.combos.collect { combos ->
                _home.update { it.copy(combos = combos) }
            }
        }
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                container.biliApi.userCookie = settings.biliCookie
                // 音量按房间组合走 PlayerUiState，这里不能再拿全局值覆盖，
                // 否则改个弹幕设置都会把当前房间调好的音量顶回去。
            }
        }
        restoreLastSession()
    }

    /**
     * 冷启动时把上次的 A/B 组合填回首页并自动解析，
     * 不用每次都重新输入房间号。用户已经手动输入时以手动内容为准。
     */
    private fun restoreLastSession() {
        viewModelScope.launch {
            val saved = container.settings.savedSlots.first()
            var restored = false
            saved.a?.let { snap ->
                if (_home.value.a.input.isBlank()) {
                    updateSlot(Slot.A) { it.copy(input = snap.input) }
                    restored = true
                }
            }
            saved.b?.let { snap ->
                if (_home.value.b.input.isBlank()) {
                    updateSlot(Slot.B) { it.copy(input = snap.input) }
                    restored = true
                }
            }
            if (!restored) return@launch
            if (_home.value.a.input.isNotBlank()) resolve(Slot.A)
            if (_home.value.b.input.isNotBlank()) resolve(Slot.B)
        }
    }

    private fun persistSlots() {
        val state = _home.value
        viewModelScope.launch {
            container.settings.saveSlots(
                SlotSnapshot(state.a.input),
                SlotSnapshot(state.b.input),
            )
        }
    }

    fun setInput(slot: Slot, text: String) = updateSlot(slot) {
        it.copy(input = text, error = null)
    }

    fun clearSlot(slot: Slot) {
        updateSlot(slot) { it.copy(input = "", room = null, error = null, resolving = false) }
        persistSlots()
    }

    fun swapSlots() {
        _home.update { state ->
            state.copy(a = state.b.copy(error = null), b = state.a.copy(error = null), startError = null)
        }
        persistSlots()
    }

    fun resolve(slot: Slot) {
        val source = if (slot == Slot.A) _home.value.a else _home.value.b
        if (source.input.isBlank() || source.resolving) return
        updateSlot(slot) { it.copy(resolving = true, error = null) }
        viewModelScope.launch {
            runCatching { container.resolver.resolve(source.input) }
                .onSuccess { room ->
                    updateSlot(slot) { it.copy(resolving = false, room = room, error = null) }
                    persistSlots()
                    container.settings.addRecent(
                        RecentRoom(
                            input = source.input.trim(),
                            title = room.title,
                            usedAt = System.currentTimeMillis(),
                        )
                    )
                }
                .onFailure { error ->
                    updateSlot(slot) {
                        it.copy(resolving = false, room = null, error = error.message ?: "解析失败")
                    }
                }
        }
    }

    /** 点「最近看过」：优先填空着的那一路，都填了就当 B 的画面源。 */
    fun useRecent(recent: RecentRoom) {
        val slot = when {
            _home.value.a.input.isBlank() -> Slot.A
            _home.value.b.input.isBlank() -> Slot.B
            else -> Slot.B
        }
        updateSlot(slot) {
            it.copy(input = recent.input, room = null, error = null)
        }
        resolve(slot)
    }

    fun startSession() {
        val state = _home.value
        val roomA = state.a.room ?: return
        val roomB = state.b.room ?: return
        if (state.starting) return

        _home.update { it.copy(starting = true, startError = null) }
        viewModelScope.launch {
            try {
                beginSession(roomA, roomB)
            } catch (e: Exception) {
                _home.update { it.copy(startError = e.message ?: "启动失败") }
            } finally {
                _home.update { it.copy(starting = false) }
            }
        }
    }

    /** 点「我的组合」：先把两个房间重新解析一遍，再直接开播。 */
    fun startCombo(combo: RoomCombo) {
        if (_home.value.starting) return
        _home.update { it.copy(starting = true, startError = null) }
        viewModelScope.launch {
            try {
                val roomA = container.resolver.resolve(combo.aInput)
                val roomB = container.resolver.resolve(combo.bInput)
                updateSlot(Slot.A) {
                    it.copy(input = combo.aInput, room = roomA, resolving = false, error = null)
                }
                updateSlot(Slot.B) {
                    it.copy(input = combo.bInput, room = roomB, resolving = false, error = null)
                }
                persistSlots()
                if (!roomA.liveStatus.isPlayable || !roomB.liveStatus.isPlayable) {
                    val offline = if (!roomA.liveStatus.isPlayable) roomA else roomB
                    _home.update { it.copy(startError = "「${offline.title}」当前未开播，开播后再试") }
                    return@launch
                }
                beginSession(roomA, roomB)
            } catch (e: Exception) {
                _home.update { it.copy(startError = e.message ?: "启动失败") }
            } finally {
                _home.update { it.copy(starting = false) }
            }
        }
    }

    fun deleteCombo(combo: RoomCombo) {
        viewModelScope.launch {
            container.settings.deleteCombo(combo.aRoomId, combo.bRoomId)
        }
    }

    /** 真正建立一次播放会话。调用前 [HomeUiState.starting] 已经置位。 */
    private suspend fun beginSession(roomA: RoomInfo, roomB: RoomInfo) {
        val qn = settings.value.qualityQn
        val playA = container.resolver.playSource(roomA, qn)
        val playB = container.resolver.playSource(roomB, qn)

        // 这个组合以前调过就把混音参数带回来；没调过才用全局默认值。
        // 读失败（比如 DataStore 损坏）不能挡住开播，退回默认值即可。
        val profile = runCatching {
            container.settings.roomProfile(roomA.roomId, roomB.roomId)
        }.getOrNull()
        val aVolume = profile?.aVolume ?: settings.value.playback.aVolume
        val bVolume = profile?.bVolume ?: settings.value.playback.bVolume
        val offset = profile?.offsetMs ?: 0

        val dualPlayer = DualPlayer(getApplication(), container.http).apply {
            onError = { slot, message ->
                _playerUi.update { it.copy(error = "${if (slot == DualPlayer.Slot.A) "A" else "B"} 路：$message") }
            }
            onPlayingChanged = { slot, playing ->
                _playerUi.update {
                    if (slot == DualPlayer.Slot.A) it.copy(aPlaying = playing) else it.copy(bPlaying = playing)
                }
            }
            onStalled = { slot -> refreshSource(slot) }
            onCommentaryVideoUnavailable = { message ->
                _playerUi.update { it.copy(error = message) }
                // 别让下次开播又自动打开一个解不了的小窗
                viewModelScope.launch { container.settings.setCommentaryWindow(false) }
            }
            setVolumes(aVolume, bVolume)
        }
        player?.release()
        player = dualPlayer
        _playerUi.value = PlayerUiState(
            aVolume = aVolume,
            bVolume = bVolume,
            offsetMs = offset,
        )
        dualPlayer.play(playA, playB, offset)

        _session.value = SessionState(a = playA, b = playB)
        _screen.value = Screen.Player

        runCatching { buildDanmakuClient(roomA) }
            .onSuccess { attachDanmaku(it) }
            .onFailure { error ->
                _session.update {
                    it?.copy(danmakuStatus = DanmakuStatus.Failed(error.message ?: "弹幕连接失败"))
                }
            }

        // 记进「我的组合」：下次首页一键就能回到同一对房间
        val home = _home.value
        runCatching {
            container.settings.saveCombo(
                RoomCombo(
                    aInput = home.a.input.ifBlank { roomA.roomId },
                    aRoomId = roomA.roomId,
                    aTitle = roomA.title,
                    aAnchor = roomA.anchor,
                    bInput = home.b.input.ifBlank { roomB.roomId },
                    bRoomId = roomB.roomId,
                    bTitle = roomB.title,
                    bAnchor = roomB.anchor,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }

        // 后台播放：把进程挂成前台，锁屏/切后台不会被系统收走
        PlaybackService.stopAction = { stopSession() }
        if (settings.value.playback.backgroundPlay) {
            PlaybackService.start(getApplication(), playB.room.title)
        }
    }

    private fun attachDanmaku(client: DanmakuClient) {
        danmakuJob?.cancel()
        statusJob?.cancel()
        danmakuClient?.stop()
        danmakuClient = client
        client.start()
        danmakuJob = viewModelScope.launch {
            client.messages.collect { message ->
                // 自己发的那条已经本地插进去了，服务端回显再收一次就重复了
                if (message.isSelf && consumeSelfEcho(message.text)) return@collect
                pushRecent(message)
                _danmaku.emit(message)
            }
        }
        statusJob = viewModelScope.launch {
            client.status.collect { status ->
                _session.update { it?.copy(danmakuStatus = status) }
            }
        }
    }

    // ---- 发弹幕 ----

    /** 记进「最近弹幕」，最新在前，只留 200 条。每条分配一个本地序号当列表 key。 */
    private fun pushRecent(message: DanmakuMessage) {
        val entry = message.copy(seq = ++danmakuSeq)
        _recentDanmaku.update { list ->
            val next = ArrayList<DanmakuMessage>(minOf(list.size + 1, MAX_RECENT))
            next.add(entry)
            list.forEach { if (next.size < MAX_RECENT) next.add(it) }
            next
        }
    }

    private fun consumeSelfEcho(text: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (pendingSelfEcho.isNotEmpty() && pendingSelfEcho.first().second < now) {
            pendingSelfEcho.removeFirst()
        }
        val index = pendingSelfEcho.indexOfFirst { it.first == text }
        if (index < 0) return false
        pendingSelfEcho.removeAt(index)
        return true
    }

    /**
     * 发弹幕。目标固定是 A 房间 —— A 是主播那一路，
     * B 只是找来的比赛画面，发到 B 没人看得到。
     *
     * 回复不再走 B 站的 reply 字段：B 站直播没有楼中楼，
     * 直接把「回复内容@对方:对方原话」拼成一条普通弹幕发出去。
     */
    fun sendDanmaku(text: String, replyTo: DanmakuMessage? = null) {
        val raw = text.trim().replace('\n', ' ').replace('\r', ' ')
        if (raw.isBlank()) return
        val body = if (replyTo != null) DanmakuText.formatReply(raw, replyTo) else raw
        if (body.isBlank()) return
        val state = _sendState.value
        if (state.sending) return
        val now = SystemClock.elapsedRealtime()
        if (now < state.cooldownUntil) {
            _sendState.update {
                it.copy(error = "刚发过，等 ${(state.cooldownUntil - now) / 1000 + 1} 秒再发")
            }
            return
        }
        if (body.length > MAX_DANMAKU_LEN) {
            _sendState.update { it.copy(error = "一条弹幕最多 $MAX_DANMAKU_LEN 个字") }
            return
        }
        val room = _session.value?.a?.room ?: return
        val cookie = settings.value.biliCookie
        if (cookie.isBlank()) {
            _sendState.update { it.copy(error = "发弹幕需要先登录 B 站账号") }
            return
        }

        _sendState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            runCatching {
                container.biliApi.sendDanmaku(
                    roomId = room.roomId,
                    text = body,
                    cookie = cookie,
                )
            }.onSuccess {
                val mine = DanmakuMessage(
                    text = body,
                    user = settings.value.biliAccount.name,
                    color = SELF_DANMAKU_COLOR,
                    uid = cookieUid(cookie),
                    isSelf = true,
                )
                pendingSelfEcho.addLast(body to SystemClock.elapsedRealtime() + SELF_ECHO_WINDOW_MS)
                pushRecent(mine)
                _danmaku.emit(mine)
                _sendState.update {
                    it.copy(
                        sending = false,
                        error = null,
                        sentSeq = it.sentSeq + 1,
                        cooldownUntil = SystemClock.elapsedRealtime() + SEND_COOLDOWN_MS,
                    )
                }
            }.onFailure { error ->
                _sendState.update {
                    it.copy(sending = false, error = error.message ?: "发送失败")
                }
            }
        }
    }

    /**
     * 斗鱼那种「+1」：把别人这条原样再发一遍。
     * B 站一条弹幕上限 20 字，原文太长就退化成真的发个「+1」。
     */
    fun plusOne(message: DanmakuMessage) {
        val text = message.text.trim()
        sendDanmaku(if (text.length <= MAX_DANMAKU_LEN) text else "+1")
    }

    fun clearSendError() = _sendState.update { it.copy(error = null) }

    /**
     * 一键互换 A / B：画面、声音、弹幕来源一起换。
     * 播放器实例不重建，只对调两路的媒体源，所以 PlayerView 不用重新绑定。
     */
    fun swapRoles() {
        val current = _session.value ?: return
        val dualPlayer = player ?: return
        if (_playerUi.value.swapping) return
        val before = _playerUi.value
        // 角色互换后，每一路的声音应该继续跟着原来的房间走，所以音量也换边
        val newAVolume = before.bVolume
        val newBVolume = before.aVolume
        _playerUi.update {
            it.copy(
                swapping = true,
                error = null,
                aVolume = newAVolume,
                bVolume = newBVolume,
            )
        }
        dualPlayer.setVolumes(newAVolume, newBVolume)

        dualPlayer.swapSources()

        val newA = current.b
        val newB = current.a
        _session.value = current.copy(a = newA, b = newB, danmakuStatus = DanmakuStatus.Connecting)
        _playerUi.update { it.copy(offsetMs = dualPlayer.currentOffset) }
        saveRoomProfileSoon()
        _home.update { state ->
            state.copy(a = state.b.copy(error = null), b = state.a.copy(error = null))
        }
        persistSlots()

        if (settings.value.playback.backgroundPlay) {
            PlaybackService.start(getApplication(), newB.room.title)
        }

        danmakuJob?.cancel()
        statusJob?.cancel()
        danmakuClient?.stop()
        danmakuClient = null
        _recentDanmaku.value = emptyList()
        viewModelScope.launch {
            runCatching { buildDanmakuClient(newA.room) }
                .onSuccess { attachDanmaku(it) }
                .onFailure { error ->
                    _session.update {
                        it?.copy(danmakuStatus = DanmakuStatus.Failed(error.message ?: "弹幕连接失败"))
                    }
                }
            _playerUi.update { it.copy(swapping = false) }
        }
    }

    /** 看门狗把线路耗尽后，重新解析一次地址塞回播放器。 */
    private fun refreshSource(slot: DualPlayer.Slot) {
        val current = _session.value ?: return
        val dualPlayer = player ?: return
        val room = if (slot == DualPlayer.Slot.A) current.a.room else current.b.room
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshAt < 6_000) return
        lastRefreshAt = now

        viewModelScope.launch {
            // 重新解析必须绕过缓存：播放地址带时效签名，
            // 拿旧签名去重连会被服务器直接踢掉，越是重连越卡。
            runCatching { container.resolver.playSource(room, settings.value.qualityQn) }
                .onSuccess { source ->
                    if (player !== dualPlayer) return@onSuccess
                    dualPlayer.reload(slot, source)
                    // A 重新拉流会回到直播边缘，把用户调好的偏移补回去；
                    // B 重连位置也会跳，但方向不确定，先不动 A，避免越补越偏。
                    if (slot == DualPlayer.Slot.A) dualPlayer.reapplyOffset()
                    _session.update { state ->
                        when {
                            state == null -> null
                            slot == DualPlayer.Slot.A -> state.copy(a = source)
                            else -> state.copy(b = source)
                        }
                    }
                }
                .onFailure { error ->
                    _playerUi.update {
                        it.copy(error = "重新解析播放地址失败：${error.message ?: "网络异常"}")
                    }
                }
        }
    }

    private fun buildDanmakuClient(room: RoomInfo): DanmakuClient = BiliDanmakuClient(
        http = container.http,
        api = container.biliApi,
        roomId = room.roomId,
        userUid = cookieUid(settings.value.biliCookie),
    )

    /** 手动重连 A 房间的弹幕（失败后点重试，或登录后刷新）。 */
    fun retryDanmaku() {
        danmakuClient?.retry()
    }

    fun stopSession() {
        danmakuJob?.cancel()
        danmakuJob = null
        statusJob?.cancel()
        statusJob = null
        danmakuClient?.stop()
        danmakuClient = null
        player?.release()
        player = null
        PlaybackService.stop(getApplication())
        PlaybackService.stopAction = null
        _session.value = null
        _playerUi.value = PlayerUiState()
        _recentDanmaku.value = emptyList()
        _sendState.value = SendState()
        pendingSelfEcho.clear()
        _screen.value = Screen.Home
    }

    fun player(): DualPlayer? = player

    fun openSettings() {
        _screen.value = Screen.Settings
    }

    fun closeSettings() {
        _screen.value = if (_session.value != null) Screen.Player else Screen.Home
    }

    fun dismissError() = _home.update { it.copy(startError = null) }

    fun clearPlayerError() = _playerUi.update { it.copy(error = null) }

    // ---- 设置 ----

    fun setThemeMode(mode: ThemeMode) = repo { setThemeMode(mode) }
    fun setMonetMode(mode: MonetMode) = repo { setMonetMode(mode) }
    fun setSeedColor(color: Long) = repo { setSeedColor(color) }

    fun setQuality(qn: Int) {
        viewModelScope.launch { container.settings.setQuality(qn) }
        applyQuality(qn)
    }

    /** 播放中切清晰度：只重新解析 B 的地址，A 的声音不中断。 */
    private fun applyQuality(qn: Int) {
        val roomB = _home.value.b.room ?: return
        val dualPlayer = player ?: return
        if (_playerUi.value.switchingQuality) return

        _playerUi.update { it.copy(switchingQuality = true) }
        viewModelScope.launch {
            runCatching { container.resolver.playSource(roomB, qn) }
                .onSuccess { source ->
                    dualPlayer.switchSourceB(source)
                    _session.update { it?.copy(b = source) }
                    _playerUi.update { it.copy(switchingQuality = false) }
                }
                .onFailure { error ->
                    _playerUi.update {
                        it.copy(
                            switchingQuality = false,
                            error = "切换清晰度失败：${error.message ?: "未知错误"}",
                        )
                    }
                }
        }
    }
    fun setBiliCookie(value: String) = repo { setBiliCookie(value) }

    fun setDanmakuEnabled(enabled: Boolean) = repo { setDanmakuEnabled(enabled) }
    fun setFontScale(scale: Float) = repo { setFontScale(scale) }
    fun setDanmakuAlpha(alpha: Float) = repo { setDanmakuAlpha(alpha) }
    fun setDanmakuSpeed(speed: Float) = repo { setDanmakuSpeed(speed) }
    fun setDanmakuArea(area: Float) = repo { setDanmakuArea(area) }
    fun setShowUserName(show: Boolean) = repo { setShowUserName(show) }
    fun setBold(bold: Boolean) = repo { setBold(bold) }
    fun setDanmakuStyle(style: DanmakuStyle) = repo { setStyle(style) }
    fun setBlockedWords(words: List<String>) = repo { setBlockedWords(words) }

    fun setAVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        _playerUi.update { it.copy(aVolume = v) }
        player?.setVolumes(v, _playerUi.value.bVolume)
        saveRoomProfileSoon()
    }

    fun setBVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        _playerUi.update { it.copy(bVolume = v) }
        player?.setVolumes(_playerUi.value.aVolume, v)
        saveRoomProfileSoon()
    }

    fun setScale(scale: VideoScale) = repo { setScale(scale) }

    /**
     * 调声音对齐：正数让 A 往后挪（暂停一下），负数让 A 往前赶（1.5 倍速追）。
     * 直接作用在播放器上，按一下就能听出变化，不经过任何音频处理器。
     */
    fun nudgeDelay(deltaMs: Int) {
        val dualPlayer = player ?: return
        dualPlayer.nudgeOffset(deltaMs)
        _playerUi.update { it.copy(offsetMs = dualPlayer.currentOffset) }
        saveRoomProfileSoon()
    }

    /** 清除手动偏移，听感上会有一次明显的快放或停顿。 */
    fun resetDelay() {
        val dualPlayer = player ?: return
        dualPlayer.resetOffset()
        _playerUi.update { it.copy(offsetMs = 0) }
        saveRoomProfileSoon()
    }

    /**
     * 一键重连：两路都重新解析播放地址，弹幕也重新连。
     *
     * 直播地址带时效签名，放久了 / 网络切换后旧地址就拉不动了，
     * 这时重新拿一份新地址比让播放器自己重试可靠得多。
     */
    fun reconnect() {
        val current = _session.value ?: return
        val dualPlayer = player ?: return
        if (_playerUi.value.reconnecting) return
        _playerUi.update { it.copy(reconnecting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val qn = settings.value.qualityQn
                container.resolver.playSource(current.a.room, qn) to
                    container.resolver.playSource(current.b.room, qn)
            }.onSuccess { (sourceA, sourceB) ->
                if (player !== dualPlayer) return@onSuccess
                dualPlayer.reload(DualPlayer.Slot.A, sourceA)
                dualPlayer.reload(DualPlayer.Slot.B, sourceB)
                dualPlayer.reapplyOffset()
                _session.update { it?.copy(a = sourceA, b = sourceB) }
                _playerUi.update { it.copy(reconnecting = false, error = null) }

                danmakuJob?.cancel()
                statusJob?.cancel()
                danmakuClient?.stop()
                danmakuClient = null
                runCatching { buildDanmakuClient(sourceA.room) }
                    .onSuccess { attachDanmaku(it) }
                    .onFailure { error ->
                        _session.update {
                            it?.copy(danmakuStatus = DanmakuStatus.Failed(error.message ?: "弹幕连接失败"))
                        }
                    }
            }.onFailure { error ->
                _playerUi.update {
                    it.copy(reconnecting = false, error = "重连失败：${error.message ?: "网络异常"}")
                }
            }
        }
    }

    fun currentDelay(): Int = player?.currentOffset ?: 0

    /**
     * 记住当前房间组合的音量和偏移。
     *
     * 拖音量滑杆会连续触发，防抖 500ms 再写，避免每移动一像素就落一次盘。
     */
    private fun saveRoomProfileSoon() {
        val current = _session.value ?: return
        val ui = _playerUi.value
        val profile = RoomProfile(
            aRoomId = current.a.room.roomId,
            bRoomId = current.b.room.roomId,
            aVolume = ui.aVolume,
            bVolume = ui.bVolume,
            offsetMs = ui.offsetMs,
            updatedAt = System.currentTimeMillis(),
        )
        profileSaveJob?.cancel()
        profileSaveJob = viewModelScope.launch {
            delay(500)
            runCatching {
                container.settings.saveRoomProfile(profile)
                // 顺手更新全局默认值：下次遇到没见过的房间组合，
                // 起手就是你最常用的音量配比，而不是回到工程默认的 85% / 100%。
                container.settings.setAVolume(profile.aVolume)
                container.settings.setBVolume(profile.bVolume)
            }
        }
    }

    fun setCommentaryWindow(enabled: Boolean) {
        repo { setCommentaryWindow(enabled) }
        // 立刻生效，不用等 DataStore 的流回来
        player?.setCommentaryVideoEnabled(enabled)
    }

    fun setBackgroundPlay(enabled: Boolean) {
        repo { setBackgroundPlay(enabled) }
        val app = getApplication<Application>()
        if (enabled) {
            val title = _session.value?.b?.room?.title.orEmpty()
            if (_session.value != null) PlaybackService.start(app, title)
        } else {
            PlaybackService.stop(app)
        }
    }

    // ---- B 站登录 ----

    fun startLogin() {
        loginJob?.cancel()
        _login.value = LoginUiState(loading = true, state = QrState.Waiting)
        loginJob = viewModelScope.launch {
            val ticket = try {
                container.biliLogin.generate()
            } catch (e: Exception) {
                _login.value = LoginUiState(state = QrState.Failure(e.message ?: "二维码生成失败"))
                return@launch
            }
            _login.update { it.copy(ticket = ticket, loading = false, state = QrState.Waiting) }

            var waited = 0L
            var scanned = false
            var failures = 0
            while (waited < QR_TIMEOUT_MS) {
                delay(POLL_INTERVAL_MS)
                waited += POLL_INTERVAL_MS
                val state = runCatching { container.biliLogin.poll(ticket.key) }
                    .getOrElse { QrState.Failure(it.message ?: "网络异常") }
                when (state) {
                    is QrState.Success -> {
                        _login.update { it.copy(state = state, scanned = true) }
                        completeLogin(state.cookie)
                        return@launch
                    }
                    is QrState.Expired -> {
                        _login.update { it.copy(state = QrState.Expired) }
                        return@launch
                    }
                    is QrState.Scanned -> {
                        scanned = true
                        _login.update { it.copy(state = state, scanned = true) }
                    }
                    is QrState.Waiting -> _login.update { it.copy(state = state) }
                    is QrState.Failure -> {
                        // 偶发网络抖动继续轮询，连续失败三次就放弃
                        failures++
                        _login.update { it.copy(state = state) }
                        if (failures >= 3) return@launch
                    }
                }
            }
            _login.update { it.copy(state = QrState.Expired) }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        loginJob = null
        _login.value = LoginUiState()
    }

    /** 网页登录回来的 Cookie，可能只有 SESSDATA 等字段。 */
    fun submitCookie(cookie: String) {
        if (cookie.isBlank()) return
        viewModelScope.launch { completeLogin(cookie.trim()) }
    }

    private suspend fun completeLogin(cookie: String) {
        _login.update { it.copy(loading = true) }
        val profile = container.biliLogin.profile(cookie)
        val account = profile?.let {
            BiliAccount(mid = it.mid, name = it.name, avatar = it.avatar, level = it.level)
        } ?: BiliAccount()
        container.settings.signIn(cookie, account)
        container.biliApi.userCookie = cookie
        _login.update {
            it.copy(loading = false, state = QrState.Success(cookie), scanned = true)
        }
        refreshProfile()
        // 登录态变化后让弹幕连接用新身份重连
        danmakuClient?.retry()
    }

    fun signOut() {
        val cookie = settings.value.biliCookie
        viewModelScope.launch {
            runCatching { container.biliLogin.logout(cookie) }
            container.settings.signOut()
            container.biliApi.userCookie = ""
            _login.value = LoginUiState()
            danmakuClient?.retry()
        }
    }

    fun refreshProfile() {
        val cookie = settings.value.biliCookie
        if (cookie.isBlank()) return
        viewModelScope.launch {
            val profile = container.biliLogin.profile(cookie) ?: return@launch
            container.settings.setBiliAccount(
                BiliAccount(
                    mid = profile.mid,
                    name = profile.name,
                    avatar = profile.avatar,
                    level = profile.level,
                )
            )
        }
    }

    private fun repo(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { container.settings.block() }
    }

    private fun updateSlot(slot: Slot, block: (SourceState) -> SourceState) {
        _home.update { state ->
            if (slot == Slot.A) state.copy(a = block(state.a)) else state.copy(b = block(state.b))
        }
    }

    override fun onCleared() {
        super.onCleared()
        danmakuJob?.cancel()
        statusJob?.cancel()
        loginJob?.cancel()
        danmakuClient?.stop()
        player?.release()
        PlaybackService.stopAction = null
        PlaybackService.stop(getApplication())
    }

    private companion object {
        const val QR_TIMEOUT_MS = 180_000L
        const val POLL_INTERVAL_MS = 1_500L

        /** 「最近弹幕」保留条数，聊天室往回翻得动。 */
        const val MAX_RECENT = 200

        /** B 站单条弹幕上限。 */
        const val MAX_DANMAKU_LEN = DanmakuText.LIMIT

        /** 发送冷却：连着点 +1 时不至于全被服务端拒掉。 */
        const val SEND_COOLDOWN_MS = 1_500L

        /** 自己发的弹幕，多久之内收到同文的回显就当作重复丢掉。 */
        const val SELF_ECHO_WINDOW_MS = 8_000L

        /** 自己发的弹幕在弹幕流里的高亮色（跟礼物那条区分开）。 */
        val SELF_DANMAKU_COLOR = 0xFFFFD166.toInt()
    }
}
