package com.dualcast.live.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dualcast.live.R
import com.dualcast.live.data.AppSettings
import com.dualcast.live.data.BiliAccount
import com.dualcast.live.data.DanmakuStyle
import com.dualcast.live.data.VideoScale
import com.dualcast.live.data.danmaku.DanmakuStatus
import com.dualcast.live.data.model.DanmakuMessage
import com.dualcast.live.data.model.DanmakuText
import com.dualcast.live.data.model.DanmakuType
import com.dualcast.live.player.DualPlayer
import com.dualcast.live.ui.PlayerUiState
import com.dualcast.live.ui.SendState
import com.dualcast.live.ui.SessionState
import com.dualcast.live.ui.components.DanmakuOverlayView
import com.dualcast.live.ui.components.pressable
import com.dualcast.live.ui.components.SelectableChip
import com.dualcast.live.ui.components.SoundBar
import com.dualcast.live.ui.components.safeBottomPadding
import com.dualcast.live.ui.components.safeTopPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放页：上面画面，下面聊天室（横屏改左右分栏）。
 *
 * 交互对齐斗鱼：
 * - 聊天室里的弹幕点一下就能「+1 / 回复」；
 * - B 站没有楼中楼，回复直接拼成「回复内容@对方:对方原话」发出去。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    session: SessionState,
    settings: AppSettings,
    playerUi: PlayerUiState,
    danmakuFlow: SharedFlow<DanmakuMessage>,
    recentDanmaku: StateFlow<List<DanmakuMessage>>,
    sendState: SendState,
    account: BiliAccount,
    playerProvider: () -> DualPlayer?,
    pipMode: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleDanmaku: (Boolean) -> Unit,
    onFontScale: (Float) -> Unit,
    onAlpha: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onArea: (Float) -> Unit,
    onShowUserName: (Boolean) -> Unit,
    onStyle: (DanmakuStyle) -> Unit,
    onBlockedWords: (List<String>) -> Unit,
    onAVolume: (Float) -> Unit,
    onBVolume: (Float) -> Unit,
    onNudgeDelay: (Int) -> Unit,
    onResetDelay: () -> Unit,
    onScale: (VideoScale) -> Unit,
    onQuality: (Int) -> Unit,
    onRetryDanmaku: () -> Unit,
    onSwapRoles: () -> Unit,
    onToggleCommentaryWindow: (Boolean) -> Unit,
    onSendDanmaku: (String, DanmakuMessage?) -> Unit,
    onPlusOne: (DanmakuMessage) -> Unit,
    onClearSendError: () -> Unit,
    onReconnect: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    var overlay by remember { mutableStateOf<DanmakuOverlayView?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var sheetVisible by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    // 全屏 = 收起聊天室 + 锁横屏（系统栏在播放页本来就是隐藏的）
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var chatCollapsed by rememberSaveable { mutableStateOf(false) }
    var collapsedBeforeFullscreen by rememberSaveable { mutableStateOf(false) }
    // 全屏时点中一条飘过的弹幕，浮出 +1 / 回复
    var floatingTarget by remember { mutableStateOf<DanmakuMessage?>(null) }
    // A 解说小窗全屏：铺满整个画面区（横屏时聊天栏也让位）
    var commentaryFullscreen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    fun setFullscreen(on: Boolean) {
        if (on == fullscreen) return
        if (on) {
            collapsedBeforeFullscreen = chatCollapsed
            chatCollapsed = true
            floatingTarget = null
            fullscreen = true
        } else {
            fullscreen = false
            chatCollapsed = collapsedBeforeFullscreen
            floatingTarget = null
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(danmakuFlow) {
        danmakuFlow.collect { overlay?.submit(it) }
    }

    LaunchedEffect(settings.danmaku, overlay) {
        overlay?.apply {
            danmakuEnabled = settings.danmaku.enabled
            textScale = settings.danmaku.fontScale
            alphaScale = settings.danmaku.alpha
            speedScale = settings.danmaku.speed
            displayArea = settings.danmaku.area
            showUserName = settings.danmaku.showUserName
            bold = settings.danmaku.bold
            style = settings.danmaku.style
            blockedWords = settings.danmaku.blockedWords
        }
    }

    // 小窗被关掉后，别把「小窗全屏」的状态留在播放页上
    LaunchedEffect(settings.playback.commentaryWindow) {
        if (!settings.playback.commentaryWindow) commentaryFullscreen = false
    }

    LaunchedEffect(controlsVisible, sheetVisible) {
        if (controlsVisible && !sheetVisible) {
            delay(4_200)
            controlsVisible = false
        }
    }

    LaunchedEffect(pipMode) {
        if (pipMode) controlsVisible = false
        // 画中画只有 B 的画面，A 的视频没必要继续解
        playerProvider()?.setCommentaryVideoEnabled(!pipMode && settings.playback.commentaryWindow)
    }

    // 小窗开着才让 A 解视频；关掉 / 离开播放页立刻释放 A 的视频解码器
    DisposableEffect(settings.playback.commentaryWindow) {
        playerProvider()?.setCommentaryVideoEnabled(settings.playback.commentaryWindow)
        onDispose { playerProvider()?.setCommentaryVideoEnabled(false) }
    }

    DisposableEffect(Unit) {
        playerProvider()?.resume()
        onDispose {
            overlay?.clear()
            playerProvider()?.pause()
        }
    }

    // 全屏时锁横屏；退出 / 离开播放页还原，避免把竖屏用户卡在横屏里
    DisposableEffect(fullscreen) {
        val act = activity
        if (fullscreen) {
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (fullscreen) act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 只有全屏 / 收起聊天室时才让飘过的弹幕可点，平时不抢「点画面开关控制条」的手势
    val danmakuTappable = fullscreen || chatCollapsed

    BackHandler(enabled = fullscreen || commentaryFullscreen || floatingTarget != null) {
        when {
            floatingTarget != null -> floatingTarget = null
            commentaryFullscreen -> commentaryFullscreen = false
            else -> setFullscreen(false)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(scheme.surface)) {
        // 竖屏、聊天室还在下面时，给挖孔 / 状态栏留一条空间；
        // 聊天室收起后画面自己铺满，顶部避让交给顶栏
        val safeTopDp = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
        val topReserved = if (!pipMode && !fullscreen && !landscape && !chatCollapsed) safeTopDp else 0.dp

        val video: @Composable (Modifier) -> Unit = { modifier ->
            VideoPane(
                modifier = modifier,
                session = session,
                settings = settings,
                playerUi = playerUi,
                playerProvider = playerProvider,
                pipMode = pipMode,
                landscape = landscape || fullscreen,
                fullscreen = fullscreen,
                reservedTop = topReserved,
                playing = playing,
                controlsVisible = controlsVisible,
                onToggleControls = {
                    floatingTarget = null
                    controlsVisible = !controlsVisible
                },
                danmakuTappable = danmakuTappable,
                onDanmakuTap = { message ->
                    controlsVisible = false
                    floatingTarget = message
                },
                onOverlayReady = { overlay = it },
                onBack = { if (fullscreen) setFullscreen(false) else onBack() },
                onToggleFullscreen = { setFullscreen(!fullscreen) },
                onToggleDanmaku = {
                    onToggleDanmaku(!settings.danmaku.enabled)
                    overlay?.danmakuEnabled = !settings.danmaku.enabled
                },
                onSwapRoles = onSwapRoles,
                onPlayPause = {
                    playing = !playing
                    val player = playerProvider()
                    if (playing) player?.resume() else player?.pause()
                },
                onOpenMixer = {
                    tab = 1
                    sheetVisible = true
                },
                onOpenPicture = {
                    tab = 3
                    sheetVisible = true
                },
                onOpenSettings = onOpenSettings,
                onTapStatus = {
                    tab = 0
                    sheetVisible = true
                },
                onTapQuality = {
                    tab = 2
                    sheetVisible = true
                },
                onToggleCommentary = {
                    onToggleCommentaryWindow(!settings.playback.commentaryWindow)
                },
                onEnterPip = onEnterPip,
                onReconnect = onReconnect,
            )
        }

        if (pipMode) {
            video(Modifier.fillMaxSize())
        } else {
            if (topReserved > 0.dp) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(topReserved)
                        .background(Color.Black)
                )
            }

            // 画面和聊天室都只保留一个调用点：收起 / 展开、横竖屏切换只改尺寸，
            // 不重建 PlayerView，画面不会闪，聊天室草稿也不会丢。
            val chatWidth = (maxWidth * 0.38f).coerceIn(280.dp, 360.dp)
            val showChat = !fullscreen && !chatCollapsed
            val videoModifier = when {
                !showChat -> Modifier.fillMaxSize()
                landscape -> Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .padding(end = chatWidth)
                else -> Modifier
                    .fillMaxWidth()
                    .padding(top = topReserved)
                    .aspectRatio(16f / 9f)
            }
            val chatModifier = if (landscape) {
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(chatWidth)
                    .fillMaxHeight()
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    // 只在 16:9 画面下方画聊天室，别用背景把视频盖住
                    .padding(top = maxWidth * 9f / 16f + topReserved)
            }

            video(videoModifier)

            if (showChat) {
                ChatPanel(
                    modifier = chatModifier,
                    session = session,
                    account = account,
                    messagesFlow = recentDanmaku,
                    sendState = sendState,
                    reconnecting = playerUi.reconnecting,
                    onSend = onSendDanmaku,
                    onPlusOne = onPlusOne,
                    onClearSendError = onClearSendError,
                    onCollapse = { chatCollapsed = true },
                    onOpenDanmakuSettings = {
                        tab = 0
                        sheetVisible = true
                    },
                    onReconnect = onReconnect,
                    onOpenLogin = onOpenSettings,
                )
            }

            if (!showChat && !fullscreen) {
                // 跟控制条一起出现 / 自动消失，别一直挂在画面上挡摄像头
                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    ChatExpandChip(
                        onExpand = { chatCollapsed = false },
                        modifier = Modifier
                            .safeBottomPadding()
                            .padding(end = 12.dp, bottom = 74.dp),
                    )
                }
            }
        }

        // 全屏时点中飘过的弹幕，底部浮出 +1 / 回复
        floatingTarget?.let { target ->
            if (!pipMode) {
                DanmakuQuickBar(
                    target = target,
                    account = account,
                    sendState = sendState,
                    liftedByControls = controlsVisible,
                    onPlusOne = { onPlusOne(target) },
                    onSend = { text -> onSendDanmaku(text, target) },
                    onDismiss = { floatingTarget = null },
                    onOpenLogin = onOpenSettings,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (sheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { sheetVisible = false },
            sheetState = sheetState,
            containerColor = scheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        ) {
            // 横屏时抽屉会拉满整块屏幕，内容限宽居中更好读
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .heightIn(max = if (landscape) 440.dp else 1_000.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("弹幕", "混音", "清晰度", "画面").forEachIndexed { index, label ->
                        SelectableChip(
                            text = label,
                            selected = tab == index,
                            onClick = { tab = index },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "sheetTab",
                ) { index ->
                when (index) {
                    0 -> DanmakuPanel(
                        session = session,
                        settings = settings,
                        onToggleDanmaku = onToggleDanmaku,
                        onFontScale = onFontScale,
                        onAlpha = onAlpha,
                        onSpeed = onSpeed,
                        onArea = onArea,
                        onShowUserName = onShowUserName,
                        onStyle = onStyle,
                        onBlockedWords = onBlockedWords,
                        onRetry = onRetryDanmaku,
                    )
                    1 -> MixerPanel(
                        playerUi = playerUi,
                        session = session,
                        onAVolume = onAVolume,
                        onBVolume = onBVolume,
                        onNudgeDelay = onNudgeDelay,
                        onResetDelay = onResetDelay,
                    )
                    2 -> QualityPanel(
                        session = session,
                        settings = settings,
                        switching = playerUi.switchingQuality,
                        onQuality = onQuality,
                    )
                    else -> PicturePanel(
                        settings = settings,
                        onScale = onScale,
                        onEnterPip = onEnterPip,
                        onToggleCommentaryWindow = onToggleCommentaryWindow,
                    )
                }
                }
            }
            }
        }
    }
}

/**
 * 画面区：B 的画面 + 弹幕浮层 + 控制条 + A 解说小窗。
 * 控制条只覆盖画面，不再压到下面的聊天室。
 */
@Composable
private fun VideoPane(
    modifier: Modifier,
    session: SessionState,
    settings: AppSettings,
    playerUi: PlayerUiState,
    playerProvider: () -> DualPlayer?,
    pipMode: Boolean,
    landscape: Boolean,
    fullscreen: Boolean,
    reservedTop: Dp,
    playing: Boolean,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    danmakuTappable: Boolean,
    onDanmakuTap: (DanmakuMessage) -> Unit,
    onOverlayReady: (DanmakuOverlayView) -> Unit,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onSwapRoles: () -> Unit,
    onPlayPause: () -> Unit,
    onOpenMixer: () -> Unit,
    onOpenPicture: () -> Unit,
    onOpenSettings: () -> Unit,
    onTapStatus: () -> Unit,
    onTapQuality: () -> Unit,
    onToggleCommentary: () -> Unit,
    onEnterPip: () -> Unit,
    onReconnect: () -> Unit,
) {
    BoxWithConstraints(modifier.background(Color.Black)) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        val compact = landscape || maxHeight < 260.dp
        // 小窗全屏时把播放页控制条藏起来，避免两套按钮叠在右上角打架
        var commentaryExpanded by remember { mutableStateOf(false) }
        LaunchedEffect(settings.playback.commentaryWindow) {
            if (!settings.playback.commentaryWindow) commentaryExpanded = false
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // 切线路 / 切画面时保留上一帧，避免整屏闪黑
                    setKeepContentOnPlayerReset(true)
                    resizeMode = settings.playback.scale.toResizeMode()
                    player = playerProvider()?.videoPlayer
                }
            },
            update = { view ->
                view.player = playerProvider()?.videoPlayer
                view.resizeMode = settings.playback.scale.toResizeMode()
            },
        )

        // 透明点击层放在弹幕层上面：先问弹幕层「点到哪条了」，没点中才开关控制条
        var overlayView by remember { mutableStateOf<DanmakuOverlayView?>(null) }
        var overlayWindow by remember { mutableStateOf(Offset.Zero) }
        var tapWindow by remember { mutableStateOf(Offset.Zero) }

        AndroidView(
            // 弹幕避开状态栏和挖孔；外层已经预留过顶部空间时只让左右
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    if (reservedTop > 0.dp) {
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    } else {
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                    }
                )
                .onGloballyPositioned { overlayWindow = it.positionInWindow() },
            factory = { ctx ->
                DanmakuOverlayView(ctx).also {
                    overlayView = it
                    onOverlayReady(it)
                }
            },
        )

        if (!pipMode) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { tapWindow = it.positionInWindow() }
                    .pointerInput(danmakuTappable, overlayView) {
                        detectTapGestures { point ->
                            val hit = if (danmakuTappable) {
                                val dx = overlayWindow.x - tapWindow.x
                                val dy = overlayWindow.y - tapWindow.y
                                overlayView?.findMessageAt(point.x - dx, point.y - dy)
                            } else {
                                null
                            }
                            if (hit != null) onDanmakuTap(hit) else onToggleControls()
                        }
                    }
            )

            if (settings.playback.commentaryWindow) {
                CommentaryWindow(
                    player = playerProvider()?.audioPlayer,
                    containerW = containerW,
                    containerH = containerH,
                    landscape = landscape,
                    onClose = { onToggleCommentary() },
                    onExpandedChange = { commentaryExpanded = it },
                )
            }

            AnimatedVisibility(
                visible = controlsVisible && !commentaryExpanded,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopBar(
                    title = session.b.room.title,
                    subtitle = "A 弹幕 · ${session.danmakuStatus.label()}",
                    live = session.b.room.liveStatus.isPlayable,
                    landscape = landscape,
                    qualityLabel = session.b.qualityLabel.ifBlank { "清晰度" },
                    switchingQuality = playerUi.switchingQuality,
                    reconnecting = playerUi.reconnecting,
                    commentaryWindow = settings.playback.commentaryWindow,
                    fullscreen = fullscreen,
                    onBack = onBack,
                    onReconnect = onReconnect,
                    onToggleCommentary = onToggleCommentary,
                    onEnterPip = onEnterPip,
                    onToggleFullscreen = onToggleFullscreen,
                    onTapStatus = onTapStatus,
                    onTapQuality = onTapQuality,
                    topInsetHandled = reservedTop > 0.dp,
                )
            }

            AnimatedVisibility(
                visible = controlsVisible && !commentaryExpanded,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                BottomControls(
                    playing = playing,
                    danmakuEnabled = settings.danmaku.enabled,
                    landscape = landscape,
                    compact = compact,
                    swapping = playerUi.swapping,
                    onSwapRoles = onSwapRoles,
                    onPlayPause = onPlayPause,
                    onToggleDanmaku = onToggleDanmaku,
                    onOpenMixer = onOpenMixer,
                    onOpenPicture = onOpenPicture,
                    onOpenSettings = onOpenSettings,
                )
            }

            val error = playerUi.error
            if (error != null) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .safeTopPadding()
                        .padding(
                            top = if (landscape) 52.dp else 58.dp,
                            start = 12.dp,
                            end = 12.dp,
                        )
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "重连",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !playerUi.reconnecting) { onReconnect() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            if (playerUi.reconnecting) {
                Row(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Text("正在重连两路…", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * 聊天室：弹幕列表 + 发送框。
 *
 * 单击一条弹幕会选中它，底部出现「+1 / 回复」；
 * 回复会拼成 `回复内容@对方:对方原话`，这是 B 站目前唯一能表达「回复」的方式。
 */
@Composable
private fun ChatPanel(
    modifier: Modifier,
    session: SessionState,
    account: BiliAccount,
    messagesFlow: StateFlow<List<DanmakuMessage>>,
    sendState: SendState,
    reconnecting: Boolean,
    onSend: (String, DanmakuMessage?) -> Unit,
    onPlusOne: (DanmakuMessage) -> Unit,
    onClearSendError: () -> Unit,
    onCollapse: () -> Unit,
    onOpenDanmakuSettings: () -> Unit,
    onReconnect: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 只让聊天室订阅弹幕列表：新弹幕不会把上面的播放器和控制条一起重组
    val messages by messagesFlow.collectAsStateWithLifecycle()
    val ordered = remember(messages) { messages.asReversed() }
    val density = LocalDensity.current
    // 键盘弹起时聊天栏会被压得很矮（横屏尤其明显），把非必要行收起来，先保住输入框
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<DanmakuMessage?>(null) }
    var replyTo by remember { mutableStateOf<DanmakuMessage?>(null) }
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var handledSeq by remember { mutableStateOf(sendState.sentSeq) }
    // 只有「发自己输入框里的内容」才清空草稿；+1 不该把正在打的字吃掉
    var clearOnSuccess by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(session.a.room.roomId) {
        // 切了 A 房间（包括 A/B 互换）之后，之前选中的弹幕和回复对象都失效了
        selected = null
        replyTo = null
    }

    val lastSeq = ordered.lastOrNull()?.seq
    LaunchedEffect(lastSeq) {
        if (follow && ordered.isNotEmpty()) {
            listState.scrollToItem(ordered.lastIndex)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                follow = last >= listState.layoutInfo.totalItemsCount - 2
            }
        }
    }
    LaunchedEffect(sendState.sentSeq) {
        if (sendState.sentSeq != handledSeq) {
            handledSeq = sendState.sentSeq
            if (clearOnSuccess) {
                input = ""
                replyTo = null
                focusManager.clearFocus()
                keyboard?.hide()
            }
            clearOnSuccess = false
            hint = null
        }
    }
    LaunchedEffect(sendState.error) {
        if (sendState.error != null) {
            hint = null
            clearOnSuccess = false
        }
    }

    val inputLimit = replyTo?.let { DanmakuText.replyInputLimit(it) } ?: DanmakuText.LIMIT
    val preview = replyTo?.let { DanmakuText.formatReply(input, it) }.orEmpty()
    val canSend = account.loggedIn && input.isNotBlank() && !sendState.sending

    Column(
        modifier
            .background(scheme.surface)
            .then(if (imeVisible) Modifier else Modifier.navigationBarsPadding())
            // 横屏时挖孔可能落在聊天栏这一侧（右侧），内容让开摄像头
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
            .imePadding()
    ) {
        // 顶栏：键盘弹起时先让位给输入框
        if (!imeVisible) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(session.danmakuStatus)
                Column(Modifier.weight(1f)) {
                    Text("聊天室", style = MaterialTheme.typography.titleSmall)
                    Text(
                        session.a.room.anchor.ifBlank { session.a.room.title },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onOpenDanmakuSettings, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = "弹幕设置",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onReconnect,
                    enabled = !reconnecting,
                    modifier = Modifier.size(36.dp),
                ) {
                    if (reconnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = scheme.primary,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "重连直播",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                // 收起聊天室：画面占满整屏，右下角留一个「聊天室」小胶囊随时叫回来
                IconButton(onClick = onCollapse, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "收起聊天室",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ordered.isEmpty()) {
                Text(
                    "弹幕还没来，等一下下",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    items(ordered, key = { it.seq }) { message ->
                        ChatMessageRow(
                            message = message,
                            selected = selected?.seq == message.seq,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(180),
                                placementSpec = null,
                                fadeOutSpec = null,
                            ),
                            onTap = {
                                selected = if (selected?.seq == message.seq) null else message
                            },
                        )
                    }
                }
            }
            if (!follow && ordered.isNotEmpty()) {
                Text(
                    "有新弹幕 ↓",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(scheme.primary.copy(alpha = 0.9f))
                        .pressable {
                            follow = true
                            scope.launch { listState.animateScrollToItem(ordered.lastIndex) }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // 选中弹幕后浮出的快捷操作
        if (!imeVisible) selected?.let { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .background(scheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        message.user.ifBlank { "匿名" },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ChatAction(
                    label = "+1",
                    enabled = !message.isSelf,
                ) {
                    if (!account.loggedIn) {
                        hint = "先登录 B 站账号才能 +1"
                    } else {
                        onPlusOne(message)
                        selected = null
                    }
                }
                ChatAction(
                    label = "回复",
                    enabled = message.canReply,
                ) {
                    if (!account.loggedIn) {
                        hint = "先登录 B 站账号才能回复"
                    } else {
                        replyTo = message
                        selected = null
                        input = input.take(DanmakuText.replyInputLimit(message))
                        runCatching { focusRequester.requestFocus() }
                        keyboard?.show()
                    }
                }
                IconButton(
                    onClick = { selected = null },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "取消",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        // 输入区
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (!account.loggedIn && !imeVisible) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(scheme.secondaryContainer, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "登录 B 站账号后才能发弹幕",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "去登录",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onOpenLogin)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            if (replyTo != null) {
                if (imeVisible) {
                    // 键盘占掉大半屏幕时只留一行：还能看见回的是谁、发出去长什么样
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(scheme.primaryContainer.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "回复 @${replyTo?.user?.ifBlank { "匿名" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onPrimaryContainer,
                        )
                        Text(
                            if (input.isNotBlank()) "· $preview" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "×",
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onPrimaryContainer,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { replyTo = null }
                                .padding(horizontal = 5.dp),
                        )
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(scheme.primaryContainer.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Reply,
                            contentDescription = null,
                            tint = scheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "回复 @${replyTo?.user?.ifBlank { "匿名" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onPrimaryContainer,
                            )
                            if (input.isNotBlank()) {
                                Text(
                                    "将发送：$preview",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            "×",
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onPrimaryContainer,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { replyTo = null }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            hint?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.error)
            }
            sendState.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.error)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = {
                        if (it.length <= inputLimit) {
                            input = it
                            hint = null
                            if (sendState.error != null) onClearSendError()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(scheme.surfaceContainerHighest)
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (canSend) {
                            clearOnSuccess = true
                            onSend(input, replyTo)
                        }
                    }),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (input.isEmpty()) {
                                Text(
                                    if (replyTo != null) "回复 @${replyTo?.user}…" else "发弹幕…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (canSend) scheme.primary else scheme.surfaceContainerHighest)
                        .clickable(enabled = canSend) {
                            clearOnSuccess = true
                            onSend(input, replyTo)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (sendState.sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = scheme.onPrimary,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "发送",
                            tint = if (canSend) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (!imeVisible) {
                Text(
                    // 回复时按最终拼出来的文案算字数，跟真正发出去的内容一致
                    "${if (replyTo != null && input.isNotBlank()) preview.length else input.length} / ${DanmakuText.LIMIT}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }
        }
    }
}

/**
 * 收起聊天室后，右下角留的小胶囊。点一下把聊天室叫回来。
 */
@Composable
private fun ChatExpandChip(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.62f))
            .pressable(onClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Rounded.ChatBubbleOutline,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Text("聊天室", color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 全屏时点中飘过的弹幕后浮出的操作条。
 *
 * 全屏没有聊天室和输入框，所以 +1 / 回复都在这里完成；
 * 回复同样拼成 `回复内容@对方:对方原话`，发完自动收起。
 */
@Composable
private fun DanmakuQuickBar(
    target: DanmakuMessage,
    account: BiliAccount,
    sendState: SendState,
    liftedByControls: Boolean,
    onPlusOne: () -> Unit,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // 换了一条弹幕就重置输入和回复态，别把上一条的草稿带过来
    var replying by remember(target) { mutableStateOf(false) }
    var input by remember(target) { mutableStateOf("") }
    var hint by remember(target) { mutableStateOf<String?>(null) }
    // 只认打开之后新产生的那次成功，别被上一次发送的 sentSeq 直接关掉
    var handledSeq by remember { mutableLongStateOf(sendState.sentSeq) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val limit = DanmakuText.replyInputLimit(target)
    val preview = if (input.isNotBlank()) DanmakuText.formatReply(input, target) else ""
    val canSend = account.loggedIn && input.isNotBlank() && !sendState.sending

    LaunchedEffect(sendState.sentSeq) {
        if (sendState.sentSeq != handledSeq) {
            handledSeq = sendState.sentSeq
            onDismiss()
        }
    }
    LaunchedEffect(replying) {
        if (replying) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .imePadding()
            .padding(
                start = 12.dp,
                end = 12.dp,
                // 控制条露出来时往上让一让，别叠在一起
                bottom = if (liftedByControls) 86.dp else 14.dp,
            )
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                target.user.ifBlank { "匿名" },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            target.text,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (replying) {
            BasicTextField(
                value = input,
                onValueChange = {
                    if (it.length <= limit) {
                        input = it
                        hint = null
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(scheme.surfaceContainerHighest)
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (canSend) onSend(input)
                }),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                "回复 @${DanmakuText.replyName(target)}…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (preview.isNotBlank()) {
                Text(
                    "将发送：$preview",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            sendState.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.error)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${input.length} / $limit",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                ChatAction(label = "取消", enabled = true) {
                    replying = false
                    input = ""
                    keyboard?.hide()
                }
                ChatAction(label = if (sendState.sending) "发送中…" else "发送", enabled = canSend) {
                    onSend(input)
                }
            }
        } else {
            sendState.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.error)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!account.loggedIn) {
                    Text(
                        "登录后才能互动",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    ChatAction(label = "去登录", enabled = true) {
                        onDismiss()
                        onOpenLogin()
                    }
                } else {
                    hint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    } ?: Spacer(Modifier.weight(1f))
                    ChatAction(label = "+1", enabled = !target.isSelf) {
                        onPlusOne()
                    }
                    ChatAction(label = "回复", enabled = target.canReply) {
                        if (!account.loggedIn) {
                            hint = "先登录 B 站账号才能回复"
                        } else {
                            replying = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) scheme.onSecondaryContainer else scheme.onSurfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (enabled) scheme.secondaryContainer else scheme.surfaceContainerHighest
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun ChatMessageRow(
    message: DanmakuMessage,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val actionable = message.type == DanmakuType.CHAT
    val background = when {
        selected -> scheme.primaryContainer.copy(alpha = 0.55f)
        message.isSelf -> Color(0x33FFD166)
        message.type == DanmakuType.GIFT -> scheme.tertiaryContainer.copy(alpha = 0.4f)
        message.type == DanmakuType.SUPER_CHAT -> scheme.secondaryContainer.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    val textColor = when {
        message.isSelf -> Color(0xFFFFD166)
        message.type == DanmakuType.GIFT -> scheme.onTertiaryContainer
        message.type == DanmakuType.SUPER_CHAT -> scheme.onSecondaryContainer
        else -> scheme.onSurface
    }
    val nameColor = if (message.isSelf) Color(0xFFE0A93B) else scheme.onSurfaceVariant

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(enabled = actionable, onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            buildAnnotatedString {
                if (message.user.isNotBlank()) {
                    withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Medium)) {
                        append(message.user)
                        append("：")
                    }
                }
                withStyle(SpanStyle(color = textColor)) {
                    append(message.text)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    live: Boolean,
    landscape: Boolean,
    qualityLabel: String,
    switchingQuality: Boolean,
    reconnecting: Boolean,
    commentaryWindow: Boolean,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onToggleCommentary: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onTapStatus: () -> Unit,
    onTapQuality: () -> Unit,
    topInsetHandled: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xCC000000), Color(0x66000000), Color.Transparent)
                )
            )
            // 竖屏时外层已经给挖孔 / 状态栏留了位置，这里不再重复避让
            .then(if (topInsetHandled) Modifier else Modifier.safeTopPadding())
            .padding(
                horizontal = if (landscape) 4.dp else 6.dp,
                vertical = if (landscape) 2.dp else 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = if (landscape) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.titleSmall
                },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onTapStatus)
                    .padding(vertical = 2.dp, horizontal = 2.dp),
            ) {
                if (live) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(Color(0xFFFF5A5F), CircleShape)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 一键重连：两路地址重新解析，弹幕也重连
        Box(
            Modifier
                .padding(end = 2.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .pressable(enabled = !reconnecting, onClick = onReconnect),
            contentAlignment = Alignment.Center,
        ) {
            if (reconnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.6.dp,
                    color = Color.White,
                )
            } else {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "重连",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        // A 解说小窗开关
        Row(
            Modifier
                .padding(end = 2.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (commentaryWindow) Color.White.copy(alpha = 0.34f)
                    else Color.White.copy(alpha = 0.14f)
                )
                .pressable(onClick = onToggleCommentary)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Rounded.RecordVoiceOver,
                contentDescription = null,
                tint = if (commentaryWindow) Color(0xFFFFD166) else Color.White,
                modifier = Modifier.size(13.dp),
            )
            Text(
                "小窗",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // 画中画：系统级小窗，切出去干别的也能挂着看
        Box(
            Modifier
                .padding(end = 2.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .pressable(onClick = onEnterPip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PictureInPictureAlt,
                contentDescription = "画中画",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        // 清晰度快捷入口
        Row(
            Modifier
                .padding(end = 2.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.16f))
                .pressable(onClick = onTapQuality)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (switchingQuality) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = Color.White,
                )
            } else {
                Text(
                    qualityLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        // 一键全屏：聊天室让位 + 锁横屏，退出时回到原来的布局
        Box(
            Modifier
                .padding(start = 2.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .pressable(onClick = onToggleFullscreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                contentDescription = if (fullscreen) "退出全屏" else "全屏",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun BottomControls(
    playing: Boolean,
    danmakuEnabled: Boolean,
    landscape: Boolean,
    compact: Boolean,
    swapping: Boolean,
    onSwapRoles: () -> Unit,
    onPlayPause: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onOpenMixer: () -> Unit,
    onOpenPicture: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0x99000000), Color(0xDD000000))
                )
            )
            .safeBottomPadding()
            .padding(
                horizontal = if (landscape) 20.dp else 10.dp,
                vertical = if (landscape) 8.dp else 10.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(if (landscape) 8.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (compact) 42.dp else 48.dp)
                .background(Color.White.copy(alpha = 0.16f), CircleShape)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (compact) 19.dp else 22.dp),
            )
        }
        ControlPill(
            icon = Icons.Rounded.SwapHoriz,
            label = if (landscape) "互换" else "切画面",
            compact = compact,
            busy = swapping,
            highlighted = true,
            onClick = onSwapRoles,
            modifier = Modifier.weight(1f),
        )
        ControlPill(
            icon = if (danmakuEnabled) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            label = "弹幕",
            active = danmakuEnabled,
            compact = compact,
            onClick = onToggleDanmaku,
            modifier = Modifier.weight(1f),
        )
        ControlPill(
            icon = Icons.Rounded.GraphicEq,
            label = "混音",
            compact = compact,
            onClick = onOpenMixer,
            modifier = Modifier.weight(1f),
        )
        ControlPill(
            icon = Icons.Rounded.AspectRatio,
            label = "画面",
            compact = compact,
            onClick = onOpenPicture,
            modifier = Modifier.weight(1f),
        )
        ControlPill(
            icon = Icons.Rounded.Settings,
            label = "设置",
            compact = compact,
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ControlPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    compact: Boolean = false,
    busy: Boolean = false,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        active -> Color.White.copy(alpha = 0.24f)
        highlighted -> Color.White.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    Column(
        modifier = modifier
            .height(if (compact) 42.dp else 52.dp)
            .background(background, RoundedCornerShape(14.dp))
            .clickable(enabled = !busy, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(if (compact) 14.dp else 16.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (compact) 17.dp else 20.dp),
            )
        }
        Text(
            label,
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun DanmakuPanel(
    session: SessionState,
    settings: AppSettings,
    onToggleDanmaku: (Boolean) -> Unit,
    onFontScale: (Float) -> Unit,
    onAlpha: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onArea: (Float) -> Unit,
    onShowUserName: (Boolean) -> Unit,
    onStyle: (DanmakuStyle) -> Unit,
    onBlockedWords: (List<String>) -> Unit,
    onRetry: () -> Unit,
) {
    val danmaku = settings.danmaku
    var blockedText by remember { mutableStateOf(danmaku.blockedWords.joinToString(" ")) }
    val status = session.danmakuStatus
    val needRetry = status is DanmakuStatus.Failed || status is DanmakuStatus.Reconnecting

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "弹幕来源 · B 站",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = session.a.room.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusDot(status)
        }

        if (needRetry) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = status.hint(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "重试",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("显示弹幕", style = MaterialTheme.typography.bodyLarge)
                Text(
                    status.hint(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = danmaku.enabled, onCheckedChange = onToggleDanmaku)
        }

        SliderRow("字号", danmaku.fontScale, 0.6f..1.8f, "${(danmaku.fontScale * 100).toInt()}%") {
            onFontScale(it)
        }
        SliderRow("不透明度", danmaku.alpha, 0.2f..1f, "${(danmaku.alpha * 100).toInt()}%") {
            onAlpha(it)
        }
        SliderRow("速度", danmaku.speed, 0.5f..2f, "×${String.format("%.1f", danmaku.speed)}") {
            onSpeed(it)
        }
        SliderRow("显示区域", danmaku.area, 0.2f..1f, "${(danmaku.area * 100).toInt()}%") {
            onArea(it)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DanmakuStyle.entries.forEach { style ->
                SelectableChip(
                    text = style.label(),
                    selected = danmaku.style == style,
                    onClick = { onStyle(style) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("显示昵称", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "把发送者昵称一起盖上去",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = danmaku.showUserName, onCheckedChange = onShowUserName)
        }

        OutlinedTextField(
            value = blockedText,
            onValueChange = { blockedText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("屏蔽词（空格分隔）") },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            trailingIcon = {
                Text(
                    "应用",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            onBlockedWords(
                                blockedText.split(' ', '，', ',').map { it.trim() }.filter { it.isNotBlank() }
                            )
                        }
                        .padding(8.dp),
                )
            },
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/**
 * A 解说小窗。
 *
 * - 单指拖动：挪窗口位置；
 * - 双指：缩放 / 平移画面内容（用来放大主播摄像头那一块）；
 * - 双击：1 倍 / 2 倍快速切换；
 * - 拖四角：窗口保持 16:9 等比缩放；
 * - 拖四边：改窗口比例，画面按铺满裁切，不留黑边。
 */
@Composable
private fun CommentaryWindow(
    player: ExoPlayer?,
    containerW: Float,
    containerH: Float,
    landscape: Boolean,
    onClose: () -> Unit,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val edgePx = with(density) { 12.dp.toPx() }
    val minWidthPx = with(density) { 108.dp.toPx() }
    val minHeightPx = with(density) { 62.dp.toPx() }
    val maxWidthPx = (containerW - edgePx * 2).coerceAtLeast(minWidthPx)
    val maxHeightPx = (containerH - with(density) { 24.dp.toPx() }).coerceAtLeast(minHeightPx)
    val initialWidthPx = with(density) { (if (landscape) 216.dp else 150.dp).toPx() }
        .coerceIn(minWidthPx, maxWidthPx)
    val initialHeightPx = (initialWidthPx * 9f / 16f).coerceIn(minHeightPx, maxHeightPx)

    var widthPx by remember(containerW, containerH) { mutableFloatStateOf(initialWidthPx) }
    var heightPx by remember(containerW, containerH) { mutableFloatStateOf(initialHeightPx) }
    var x by remember(containerW, containerH) {
        mutableFloatStateOf((containerW - initialWidthPx - edgePx).coerceAtLeast(0f))
    }
    var y by remember(containerW, containerH) {
        mutableFloatStateOf(
            with(density) { 62.dp.toPx() }
                .coerceAtMost((containerH - initialHeightPx).coerceAtLeast(0f))
        )
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    // 小窗全屏：铺满画面区，方便双指放大裁切取景，退出后还是同一个小窗
    var expanded by remember { mutableStateOf(false) }

    // 平时不显示任何按钮，点一下才浮出来；停手 3.2 秒自动收起，别挡住摄像头
    var showControls by remember { mutableStateOf(false) }
    var lastControlsAt by remember { mutableLongStateOf(0L) }

    fun revealControls() {
        showControls = true
        lastControlsAt = System.currentTimeMillis()
    }

    // 只要还在拖 / 缩，就一直续命；松手 3.2 秒后才淡出
    LaunchedEffect(showControls) {
        while (showControls) {
            delay(400)
            if (System.currentTimeMillis() - lastControlsAt > 3200) {
                showControls = false
            }
        }
    }

    // 进出全屏时把按钮亮一下——用户刚点的就是它，别立刻消失
    LaunchedEffect(expanded) {
        revealControls()
    }

    BackHandler(enabled = expanded) {
        expanded = false
        onExpandedChange(false)
    }

    fun clampPan(w: Float, h: Float) {
        val maxX = ((zoom - 1f) * w / 2f).coerceAtLeast(0f)
        val maxY = ((zoom - 1f) * h / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    fun clampWindow() {
        widthPx = widthPx.coerceIn(minWidthPx, maxWidthPx)
        heightPx = heightPx.coerceIn(minHeightPx, maxHeightPx)
        x = x.coerceIn(0f, (containerW - widthPx).coerceAtLeast(0f))
        y = y.coerceIn(0f, (containerH - heightPx).coerceAtLeast(0f))
    }

    /**
     * 拖四角：窗口按 16:9 等比缩放，比例始终不变。
     * right / bottom 表示这个角在窗口的右侧 / 下侧，用来决定缩放方向和固定哪个对角。
     */
    fun resizeCorner(right: Boolean, bottom: Boolean, dx: Float, dy: Float) {
        val ratio = 9f / 16f
        val fromX = if (right) dx else -dx
        val fromY = (if (bottom) dy else -dy) / ratio
        val delta = if (abs(fromX) >= abs(fromY)) fromX else fromY
        if (delta == 0f) return
        val nextW = (widthPx + delta).coerceIn(minWidthPx, maxWidthPx)
        val nextH = (nextW * ratio).coerceIn(minHeightPx, maxHeightPx)
        if (!right) x += widthPx - nextW
        if (!bottom) y += heightPx - nextH
        widthPx = nextW
        heightPx = nextH
        clampWindow()
    }

    /**
     * 拖边框：只改一个方向，窗口比例会变。
     * 小窗里的画面是 cover（ZOOM）铺满的，比例一变就自然裁掉多出来的部分，不会留黑边。
     */
    fun resizeEdge(edge: Int, dx: Float, dy: Float) {
        when (edge) {
            EDGE_LEFT -> {
                val next = (widthPx - dx).coerceIn(minWidthPx, maxWidthPx)
                x += widthPx - next
                widthPx = next
            }
            EDGE_RIGHT -> widthPx = (widthPx + dx).coerceIn(minWidthPx, maxWidthPx)
            EDGE_TOP -> {
                val next = (heightPx - dy).coerceIn(minHeightPx, maxHeightPx)
                y += heightPx - next
                heightPx = next
            }
            EDGE_BOTTOM -> heightPx = (heightPx + dy).coerceIn(minHeightPx, maxHeightPx)
        }
        clampWindow()
    }

    LaunchedEffect(widthPx, heightPx, containerW, containerH) {
        clampWindow()
    }

    // 全屏和小窗的画面尺寸不同，切换后把平移量收进新边界，别让画面跑没
    LaunchedEffect(expanded, widthPx, heightPx, containerW, containerH) {
        if (expanded) {
            clampPan(containerW, containerH)
        } else {
            clampPan(widthPx, heightPx)
        }
    }

    Box(
        Modifier
            .then(
                if (expanded) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                        .shadow(10.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.34f), RoundedCornerShape(14.dp))
                }
            )
            .background(Color.Black)
            .pointerInput(containerW, containerH, expanded) {
                if (!expanded) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        x = (x + dragAmount.x).coerceIn(0f, (containerW - widthPx).coerceAtLeast(0f))
                        y = (y + dragAmount.y).coerceIn(0f, (containerH - heightPx).coerceAtLeast(0f))
                        revealControls()
                    }
                }
            },
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        // 单击小窗：按钮浮出来 / 再点一下收回去
                        onTap = { showControls = !showControls },
                        onDoubleTap = { tap ->
                            if (zoom > 1.05f) {
                                zoom = 1f
                                panX = 0f
                                panY = 0f
                            } else {
                                val target = 2f
                                val cx = tap.x - size.width / 2f
                                val cy = tap.y - size.height / 2f
                                zoom = target
                                val maxX = (target - 1f) * size.width / 2f
                                val maxY = (target - 1f) * size.height / 2f
                                panX = (-cx * (target - 1f)).coerceIn(-maxX, maxX)
                                panY = (-cy * (target - 1f)).coerceIn(-maxY, maxY)
                            }
                            revealControls()
                        }
                    )
                }
                .pointerInput(expanded) {
                    // 全屏取景时单指拖画面：放大后把主播的脸挪到中间
                    if (expanded) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val maxX = ((zoom - 1f) * size.width / 2f).coerceAtLeast(0f)
                            val maxY = ((zoom - 1f) * size.height / 2f).coerceAtLeast(0f)
                            panX = (panX + drag.x).coerceIn(-maxX, maxX)
                            panY = (panY + drag.y).coerceIn(-maxY, maxY)
                            revealControls()
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var transforming = false
                        var event: PointerEvent
                        do {
                            event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                transforming = true
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val next = (zoom * zoomChange).coerceIn(1f, 4f)
                                zoom = next
                                panX += panChange.x
                                panY += panChange.y
                                val maxX = ((next - 1f) * size.width / 2f).coerceAtLeast(0f)
                                val maxY = ((next - 1f) * size.height / 2f).coerceAtLeast(0f)
                                panX = panX.coerceIn(-maxX, maxX)
                                panY = panY.coerceIn(-maxY, maxY)
                                event.changes.forEach { it.consume() }
                                // 缩放时把倍率提示亮出来，手停下 3 秒再收
                                revealControls()
                            } else if (pressed == 1 && transforming) {
                                // 松开一根手指后不再把窗口拖动接回来
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            factory = { ctx ->
                LayoutInflater.from(ctx)
                    .inflate(R.layout.view_commentary_player, null) as PlayerView
            },
            update = { view ->
                view.player = player
                // 小窗比例是随便拖的，画面一律铺满裁切，不留黑边
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            },
            // 会话结束时播放器可能已经 release()，清 surface 时不要再碰它
            onRelease = { view -> runCatching { view.player = null } },
        )

        // 小窗全屏时按钮要避开状态栏 / 挖孔，别被摄像头咬掉
        val safeTop = if (expanded) {
            Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
        } else {
            Modifier
        }

        // 「A · 解说」角标：点一下才出现，平时把画面让出来
        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopStart),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                Modifier
                    .then(safeTop)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Rounded.RecordVoiceOver,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
                // 小窗窄到放不下时只留图标
                if (expanded || widthPx >= with(density) { 170.dp.toPx() }) {
                    Text(
                        "A · 解说",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                Modifier
                    .then(safeTop)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 小窗全屏：铺满画面区，双指放大裁切后再退出，尺寸和取景都保留
                Box(
                    Modifier
                        .size(30.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .clickable {
                            expanded = !expanded
                            onExpandedChange(expanded)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (expanded) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = if (expanded) "退出小窗全屏" else "小窗全屏",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Box(
                    Modifier
                        .size(30.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .clickable {
                            if (expanded) onExpandedChange(false)
                            onClose()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭解说小窗",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.BottomStart),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (zoom > 1.02f) {
                Text(
                    "×${String.format("%.1f", zoom)} · 复位",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .clickable {
                            zoom = 1f
                            panX = 0f
                            panY = 0f
                        }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            } else if (expanded) {
                Text(
                    "双指放大 · 单指拖动取景",
                    color = Color.White.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (!expanded && showControls) {
            // 四角：拖拽保持 16:9 等比缩放窗口
            listOf(
                Triple(Alignment.TopStart, false, false),
                Triple(Alignment.TopEnd, true, false),
                Triple(Alignment.BottomStart, false, true),
                Triple(Alignment.BottomEnd, true, true),
            ).forEach { (align, right, bottom) ->
                Box(
                    Modifier
                        .align(align)
                        .size(36.dp)
                        .pointerInput(containerW, containerH) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                resizeCorner(right, bottom, drag.x, drag.y)
                                revealControls()
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .align(align)
                            .padding(5.dp)
                            .size(13.dp)
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape),
                    )
                }
            }

            // 四边：拖拽改窗口比例，画面按铺满裁切，不会留黑边
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(20.dp)
                    .height(64.dp)
                    .pointerInput(containerW, containerH) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            resizeEdge(EDGE_LEFT, drag.x, drag.y)
                            revealControls()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(24.dp)
                        .background(Color.White.copy(alpha = 0.65f), RoundedCornerShape(50)),
                )
            }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(20.dp)
                    .height(64.dp)
                    .pointerInput(containerW, containerH) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            resizeEdge(EDGE_RIGHT, drag.x, drag.y)
                            revealControls()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(24.dp)
                        .background(Color.White.copy(alpha = 0.65f), RoundedCornerShape(50)),
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(64.dp)
                    .height(20.dp)
                    .pointerInput(containerW, containerH) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            resizeEdge(EDGE_TOP, drag.x, drag.y)
                            revealControls()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(24.dp)
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.65f), RoundedCornerShape(50)),
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(64.dp)
                    .height(20.dp)
                    .pointerInput(containerW, containerH) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            resizeEdge(EDGE_BOTTOM, drag.x, drag.y)
                            revealControls()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(24.dp)
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.65f), RoundedCornerShape(50)),
                )
            }
        }
    }
}

@Composable
private fun MixerPanel(
    playerUi: PlayerUiState,
    session: SessionState,
    onAVolume: (Float) -> Unit,
    onBVolume: (Float) -> Unit,
    onNudgeDelay: (Int) -> Unit,
    onResetDelay: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val aVolume = playerUi.aVolume
    val bVolume = playerUi.bVolume
    val offsetMs = playerUi.offsetMs
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "两路声音同时播放，可以分别调音量。先点「只听 A」「只听 B」来回切两次，能听出谁快谁慢。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip(
                text = "双声道",
                selected = aVolume > 0f && bVolume > 0f,
                onClick = {
                    onAVolume(0.85f)
                    onBVolume(1f)
                },
                modifier = Modifier.weight(1f),
            )
            SelectableChip(
                text = "只听 A",
                selected = bVolume == 0f,
                onClick = {
                    onAVolume(1f)
                    onBVolume(0f)
                },
                modifier = Modifier.weight(1f),
            )
            SelectableChip(
                text = "只听 B",
                selected = aVolume == 0f,
                onClick = {
                    onAVolume(0f)
                    onBVolume(1f)
                },
                modifier = Modifier.weight(1f),
            )
        }
        SoundBar(
            label = "A · 弹幕",
            hint = session.a.room.title,
            value = aVolume,
            accent = scheme.secondary,
            onValueChange = onAVolume,
        )
        SoundBar(
            label = "B · 画面",
            hint = session.b.room.title,
            value = bVolume,
            accent = scheme.primary,
            onValueChange = onBVolume,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .background(scheme.secondaryContainer.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = scheme.onSecondaryContainer,
                modifier = Modifier.size(15.dp),
            )
            Text(
                "这个组合的音量和声音偏移会自动记住，下次开播直接还原",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSecondaryContainer,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainer, RoundedCornerShape(22.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("声音对齐", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            offsetMs > 0 -> "A 比 B 晚 ${secondsLabel(offsetMs)} 秒"
                            offsetMs < 0 -> "A 比 B 早 ${secondsLabel(offsetMs)} 秒"
                            else -> "没有手动偏移"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Text(
                    "归零",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (offsetMs == 0) {
                        scheme.onSurfaceVariant.copy(alpha = 0.45f)
                    } else {
                        scheme.primary
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = offsetMs != 0) { onResetDelay() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            Text(
                text = if (offsetMs == 0) "0.0 秒" else "${if (offsetMs > 0) "+" else "−"}${secondsLabel(offsetMs)} 秒",
                style = MaterialTheme.typography.headlineMedium,
                color = if (offsetMs == 0) scheme.onSurfaceVariant else scheme.primary,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("← 让 A 提前", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text("让 A 延后 →", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-500, -100, 100, 500).forEach { delta ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(scheme.surfaceContainerHighest)
                            .clickable { onNudgeDelay(delta) }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = (if (delta > 0) "+" else "−") + (kotlin.math.abs(delta) / 1000f).toString().removeSuffix(".0") + "s",
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                        )
                    }
                }
            }

            Text(
                "点「+」，A 会停一下再继续，声音就往后挪了一点；点「−」，A 会用 1.5 倍速追一下，声音提前。" +
                    "每次都有明显的听感变化，听到两路说话不打架就停手。这个偏移会跟房间组合一起记住。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QualityPanel(
    session: SessionState,
    settings: AppSettings,
    switching: Boolean,
    onQuality: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val current = settings.qualityQn

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("B 画面清晰度", style = MaterialTheme.typography.titleSmall)
            Text(
                "当前实际播放：${session.b.qualityLabel.ifBlank { "未知" }}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }

        AppSettings.QUALITY_OPTIONS.forEach { option ->
            val selected = current == option.qn
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) scheme.primaryContainer else scheme.surfaceContainer)
                    .clickable { if (!selected) onQuality(option.qn) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                    )
                    Text(
                        qualityHint(option.qn),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            scheme.onPrimaryContainer.copy(alpha = 0.8f)
                        } else {
                            scheme.onSurfaceVariant
                        },
                    )
                }
                if (selected) {
                    if (switching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = scheme.onPrimaryContainer,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = scheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        Text(
            "清晰度受账号权限影响：未登录通常只能拿到高清及以下，登录后原画才会出现。切换只重载 B 的画面，A 的声音不中断。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

private fun qualityHint(qn: Int): String = when (qn) {
    10000 -> "最高画质，通常需要登录"
    400 -> "蓝光，画质与带宽的平衡"
    250 -> "超清，网络一般时更稳"
    150 -> "高清，最省流量"
    else -> ""
}

private fun secondsLabel(ms: Int): String =
    String.format(java.util.Locale.CHINA, "%.1f", kotlin.math.abs(ms) / 1000f)

@Composable
private fun PicturePanel(
    settings: AppSettings,
    onScale: (VideoScale) -> Unit,
    onEnterPip: () -> Unit,
    onToggleCommentaryWindow: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("画面缩放", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoScale.entries.forEach { scale ->
                SelectableChip(
                    text = scale.label(),
                    selected = settings.playback.scale == scale,
                    onClick = { onScale(scale) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("解说小窗", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "把 A 主播的画面浮在 B 画面上。可拖动；双指缩放画面、拖右下角改大小，" +
                        "放大后挪到主播摄像头那块再缩小，就能一直盯着人脸。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.playback.commentaryWindow,
                onCheckedChange = onToggleCommentaryWindow,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
                .clickable(onClick = onEnterPip)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("画中画", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "切到其他 App 时继续看 B 的画面和弹幕",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: DanmakuStatus) {
    val color = when (status) {
        is DanmakuStatus.Connected -> Color(0xFF4CAF50)
        is DanmakuStatus.Connecting, is DanmakuStatus.Reconnecting -> Color(0xFFFFB300)
        is DanmakuStatus.Failed -> MaterialTheme.colorScheme.error
        DanmakuStatus.Idle -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            status.shortLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun DanmakuStatus.label(): String = when (this) {
    DanmakuStatus.Connected -> "弹幕已连接"
    is DanmakuStatus.Connecting -> "弹幕连接中"
    is DanmakuStatus.Reconnecting -> "弹幕重连中"
    is DanmakuStatus.Failed -> "弹幕不可用"
    DanmakuStatus.Idle -> "弹幕未启动"
}

private fun DanmakuStatus.shortLabel(): String = when (this) {
    DanmakuStatus.Connected -> "已连接"
    is DanmakuStatus.Connecting -> "连接中"
    is DanmakuStatus.Reconnecting -> "重连中"
    is DanmakuStatus.Failed -> "不可用"
    DanmakuStatus.Idle -> "未启动"
}

private fun DanmakuStatus.hint(): String = when (this) {
    DanmakuStatus.Connected -> "弹幕正在叠加到 B 的画面上"
    is DanmakuStatus.Connecting -> "正在连接 A 房间的弹幕服务器"
    is DanmakuStatus.Reconnecting -> reason?.let { "第 $attempt 次重连：$it" }
        ?: "连接断开，正在自动重连"
    is DanmakuStatus.Failed -> reason
    DanmakuStatus.Idle -> "尚未开始"
}

private fun DanmakuStyle.label(): String = when (this) {
    DanmakuStyle.OUTLINE -> "描边"
    DanmakuStyle.SHADOW -> "阴影"
    DanmakuStyle.PLAIN -> "纯色"
}

private fun VideoScale.label(): String = when (this) {
    VideoScale.FIT -> "适应"
    VideoScale.CROP -> "裁剪"
    VideoScale.FILL -> "铺满"
}

private fun VideoScale.toResizeMode(): Int = when (this) {
    VideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoScale.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

/** Compose 的 Context 可能被主题/包装器套了几层，这里一路往上找到 Activity。 */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 解说小窗四边手柄的方向编号。 */
private const val EDGE_LEFT = 0
private const val EDGE_RIGHT = 1
private const val EDGE_TOP = 2
private const val EDGE_BOTTOM = 3
