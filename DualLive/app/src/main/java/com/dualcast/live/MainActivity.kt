package com.dualcast.live

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualcast.live.ui.AppViewModel
import com.dualcast.live.ui.Screen
import com.dualcast.live.ui.screen.HomeScreen
import com.dualcast.live.ui.screen.PlayerScreen
import com.dualcast.live.ui.screen.SettingsScreen
import com.dualcast.live.ui.theme.DualCastTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()
    private val inPip = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val screen by viewModel.screen.collectAsStateWithLifecycle()
            val home by viewModel.home.collectAsStateWithLifecycle()
            val session by viewModel.session.collectAsStateWithLifecycle()
            val playerUi by viewModel.playerUi.collectAsStateWithLifecycle()
            val login by viewModel.login.collectAsStateWithLifecycle()
            val sendState by viewModel.sendState.collectAsStateWithLifecycle()

            DualCastTheme(settings) {
                // 用 Box 而不是 Surface：Surface 会把 LocalContentColor 覆盖成 Unspecified，
                // 深色主题下没写 color 的文字又会退回黑色。
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            // 首页 → 设置 / 播放页走「前进」，返回走「后退」，方向感更明确
                            fun depth(s: Screen) = when (s) {
                                Screen.Home -> 0
                                Screen.Settings -> 1
                                Screen.Player -> 2
                            }
                            if (depth(targetState) >= depth(initialState)) {
                                (slideInHorizontally { it / 8 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it / 10 } + fadeOut())
                            } else {
                                (slideInHorizontally { -it / 8 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { it / 10 } + fadeOut())
                            }
                        },
                        label = "screen",
                    ) { target ->
                        when (target) {
                            Screen.Home -> {
                                HomeScreen(
                                    state = home,
                                    onInputChange = viewModel::setInput,
                                    onResolve = viewModel::resolve,
                                    onClear = viewModel::clearSlot,
                                    onSwap = viewModel::swapSlots,
                                    onStart = viewModel::startSession,
                                    onOpenSettings = viewModel::openSettings,
                                    onUseRecent = viewModel::useRecent,
                                    onStartCombo = viewModel::startCombo,
                                    onDeleteCombo = viewModel::deleteCombo,
                                )
                            }

                            Screen.Settings -> {
                                BackHandler { viewModel.closeSettings() }
                                SettingsScreen(
                                    settings = settings,
                                    login = login,
                                    onBack = viewModel::closeSettings,
                                    onThemeMode = viewModel::setThemeMode,
                                    onMonetMode = viewModel::setMonetMode,
                                    onSeedColor = viewModel::setSeedColor,
                                    onQuality = viewModel::setQuality,
                                    onBiliCookie = viewModel::setBiliCookie,
                                    onDanmakuEnabled = viewModel::setDanmakuEnabled,
                                    onFontScale = viewModel::setFontScale,
                                    onAlpha = viewModel::setDanmakuAlpha,
                                    onSpeed = viewModel::setDanmakuSpeed,
                                    onArea = viewModel::setDanmakuArea,
                                    onShowUserName = viewModel::setShowUserName,
                                    onStyle = viewModel::setDanmakuStyle,
                                    onBackgroundPlay = viewModel::setBackgroundPlay,
                                    onStartLogin = viewModel::startLogin,
                                    onCancelLogin = viewModel::cancelLogin,
                                    onSignOut = viewModel::signOut,
                                    onSubmitCookie = viewModel::submitCookie,
                                )
                            }

                            Screen.Player -> {
                                BackHandler { viewModel.stopSession() }
                                val current = session
                                if (current == null) {
                                    androidx.compose.runtime.LaunchedEffect(Unit) {
                                        viewModel.stopSession()
                                    }
                                } else {
                                    PlayerScreen(
                                        session = current,
                                        settings = settings,
                                        playerUi = playerUi,
                                        danmakuFlow = viewModel.danmaku,
                                        recentDanmaku = viewModel.recentDanmaku,
                                        sendState = sendState,
                                        account = settings.biliAccount,
                                        playerProvider = viewModel::player,
                                        pipMode = inPip.value,
                                        onBack = viewModel::stopSession,
                                        onOpenSettings = viewModel::openSettings,
                                        onEnterPip = ::enterPip,
                                        onToggleDanmaku = viewModel::setDanmakuEnabled,
                                        onFontScale = viewModel::setFontScale,
                                        onAlpha = viewModel::setDanmakuAlpha,
                                        onSpeed = viewModel::setDanmakuSpeed,
                                        onArea = viewModel::setDanmakuArea,
                                        onShowUserName = viewModel::setShowUserName,
                                        onStyle = viewModel::setDanmakuStyle,
                                        onBlockedWords = viewModel::setBlockedWords,
                                        onAVolume = viewModel::setAVolume,
                                        onBVolume = viewModel::setBVolume,
                                        onNudgeDelay = viewModel::nudgeDelay,
                                        onResetDelay = viewModel::resetDelay,
                                        onScale = viewModel::setScale,
                                            onQuality = viewModel::setQuality,
                                        onRetryDanmaku = viewModel::retryDanmaku,
                                        onSwapRoles = viewModel::swapRoles,
                                        onToggleCommentaryWindow = viewModel::setCommentaryWindow,
                                        onSendDanmaku = viewModel::sendDanmaku,
                                        onPlusOne = viewModel::plusOne,
                                        onClearSendError = viewModel::clearSendError,
                                        onReconnect = viewModel::reconnect,
                                    )
                                }
                            }
                        }
                    }
                }

                DisposableEffect(session != null) {
                    val active = session != null
                    if (active) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                DisposableEffect(screen) {
                    // 所有页面都让背景铺进挖孔区，内容再靠 safeDrawing 避让，
                    // 横屏时不会因为摄像头留出一条黑边。
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        window.attributes = window.attributes.apply {
                            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            } else {
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            }
                        }
                    }
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    if (screen == Screen.Player) {
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        controller.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                    onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
                }
            }
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) viewModel.player()?.resume()
    }

    override fun onStop() {
        super.onStop()
        // 开了后台播放就交给前台服务继续放，否则切后台立刻静音
        if (inPip.value) return
        if (viewModel.settings.value.playback.backgroundPlay) {
            // 后台只留声音，关掉视频解码（包括 A 的解说小窗）
            viewModel.player()?.setVideoEnabled(false)
            viewModel.player()?.setCommentaryVideoEnabled(false)
            return
        }
        viewModel.player()?.pause()
    }

    override fun onStart() {
        super.onStart()
        val player = viewModel.player() ?: return
        player.setVideoEnabled(true)
        // 只有停在播放页时才恢复 A 的视频解码；在设置页没必要偷偷解码
        val onPlayer = viewModel.screen.value == Screen.Player && !inPip.value
        player.setCommentaryVideoEnabled(
            onPlayer && viewModel.settings.value.playback.commentaryWindow
        )
        if (viewModel.session.value != null) player.resume()
    }
}
