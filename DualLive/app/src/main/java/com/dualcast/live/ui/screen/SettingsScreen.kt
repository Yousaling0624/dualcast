package com.dualcast.live.ui.screen

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dualcast.live.BuildConfig
import com.dualcast.live.data.AppSettings
import com.dualcast.live.data.BiliAccount
import com.dualcast.live.data.DanmakuStyle
import com.dualcast.live.data.MonetMode
import com.dualcast.live.data.ThemeMode
import com.dualcast.live.ui.LoginUiState
import com.dualcast.live.ui.components.GradientBackground
import com.dualcast.live.ui.components.PillTag
import com.dualcast.live.ui.components.SelectableChip
import com.dualcast.live.ui.components.SettingSwitchRow
import com.dualcast.live.ui.components.pressable
import com.dualcast.live.ui.components.safeBottomPadding
import com.dualcast.live.ui.components.safeTopPadding
import com.dualcast.live.ui.theme.Monet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SEED_COLORS = listOf(
    0xFF6750A4, 0xFF006A6A, 0xFF0061A4, 0xFF3E6837,
    0xFF8B5000, 0xFFB3261E, 0xFF984061, 0xFF4A5C92,
    0xFF7A5900, 0xFF536341,
)

/** 设置页的层级：根页只放入口，具体开关都收进二级页。 */
private enum class SettingsPage {
    ROOT, APPEARANCE, DANMAKU, PLAYBACK, ACCOUNT, ABOUT
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    login: LoginUiState,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onMonetMode: (MonetMode) -> Unit,
    onSeedColor: (Long) -> Unit,
    onQuality: (Int) -> Unit,
    onBiliCookie: (String) -> Unit,
    onDanmakuEnabled: (Boolean) -> Unit,
    onFontScale: (Float) -> Unit,
    onAlpha: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onArea: (Float) -> Unit,
    onShowUserName: (Boolean) -> Unit,
    onStyle: (DanmakuStyle) -> Unit,
    onBackgroundPlay: (Boolean) -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onSignOut: () -> Unit,
    onSubmitCookie: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLogin by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }

    LaunchedEffect(login.success) {
        if (login.success) showLogin = false
    }

    // 二级页优先吃掉返回键，不会再一路退回首页
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val color = withContext(Dispatchers.IO) {
                runCatching {
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }?.let { Monet.seedFromBitmap(it) }
                }.getOrNull()
            }
            color?.let { onSeedColor(it.toArgbLong()) }
        }
    }

    GradientBackground {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) {
                    (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 8 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 8 } + fadeOut())
                }
            },
            label = "settingsPage",
        ) { target ->
            when (target) {
                SettingsPage.ROOT -> SettingsRootPage(
                    settings = settings,
                    account = settings.biliAccount,
                    onBack = onBack,
                    onOpen = { page = it },
                )

                SettingsPage.APPEARANCE -> SettingsPageScaffold(
                    title = "外观",
                    subtitle = "主题模式、取色方式与强调色",
                    onBack = { page = SettingsPage.ROOT },
                ) {
                    SettingsSection("主题模式", "跟随系统会随日夜自动切换") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                ThemeMode.SYSTEM to "跟随系统",
                                ThemeMode.LIGHT to "浅色",
                                ThemeMode.DARK to "深色",
                            ).forEach { (mode, label) ->
                                SelectableChip(
                                    text = label,
                                    selected = settings.themeMode == mode,
                                    onClick = { onThemeMode(mode) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    SettingsSection("取色", "莫奈取色会从壁纸或种子色生成整套 Material You 配色") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectableChip(
                                text = "跟随壁纸",
                                selected = settings.monetMode == MonetMode.WALLPAPER,
                                onClick = { onMonetMode(MonetMode.WALLPAPER) },
                                modifier = Modifier.weight(1f),
                            )
                            SelectableChip(
                                text = "自定义色",
                                selected = settings.monetMode == MonetMode.SEED,
                                onClick = { onMonetMode(MonetMode.SEED) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            "壁纸取色在 Android 12 及以上生效，低版本会回落到下面的种子色。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SEED_COLORS.take(5).forEach { color ->
                                SeedDot(color, settings.seedColor) { onSeedColor(color) }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SEED_COLORS.drop(5).forEach { color ->
                                SeedDot(color, settings.seedColor) { onSeedColor(color) }
                            }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .pressable {
                                    picker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Image, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("从图片提取颜色", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "选一张图，取最鲜艳的颜色作为种子",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                SettingsPage.DANMAKU -> SettingsPageScaffold(
                    title = "弹幕样式",
                    subtitle = "这里改的是默认值，播放页里还能随时调",
                    onBack = { page = SettingsPage.ROOT },
                ) {
                    SettingsSection("显示", "关掉后只保留画面和声音") {
                        SettingSwitchRow(
                            title = "显示弹幕",
                            subtitle = "直播弹幕会盖在 B 画面上",
                            checked = settings.danmaku.enabled,
                            onCheckedChange = onDanmakuEnabled,
                        )
                    }

                    SettingsSection("样式", "字号、透明度、速度和显示区域") {
                        SettingSlider("字号", settings.danmaku.fontScale, 0.6f..1.8f) { onFontScale(it) }
                        SettingSlider("不透明度", settings.danmaku.alpha, 0.2f..1f) { onAlpha(it) }
                        SettingSlider("滚动速度", settings.danmaku.speed, 0.5f..2f) { onSpeed(it) }
                        SettingSlider("显示区域", settings.danmaku.area, 0.2f..1f) { onArea(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DanmakuStyle.entries.forEach { style ->
                                SelectableChip(
                                    text = when (style) {
                                        DanmakuStyle.OUTLINE -> "描边"
                                        DanmakuStyle.SHADOW -> "阴影"
                                        DanmakuStyle.PLAIN -> "纯色"
                                    },
                                    selected = settings.danmaku.style == style,
                                    onClick = { onStyle(style) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        SettingSwitchRow(
                            title = "显示昵称",
                            subtitle = "格式：昵称: 内容",
                            checked = settings.danmaku.showUserName,
                            onCheckedChange = onShowUserName,
                        )
                    }
                }

                SettingsPage.PLAYBACK -> SettingsPageScaffold(
                    title = "播放",
                    subtitle = "清晰度与后台行为",
                    onBack = { page = SettingsPage.ROOT },
                ) {
                    SettingsSection("清晰度", "只影响 B 站的画面，A 的声音不受影响") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppSettings.QUALITY_OPTIONS.forEach { option ->
                                SelectableChip(
                                    text = option.label,
                                    selected = settings.qualityQn == option.qn,
                                    onClick = { onQuality(option.qn) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Text(
                            "登录后更容易拿到原画；网络不稳时会自动降一档。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    SettingsSection("后台", "切到后台或锁屏后声音继续") {
                        SettingSwitchRow(
                            title = "后台播放",
                            subtitle = "通知栏可以随时停止",
                            checked = settings.playback.backgroundPlay,
                            onCheckedChange = { enabled ->
                                onBackgroundPlay(enabled)
                                if (enabled &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                        )
                    }
                }

                SettingsPage.ACCOUNT -> SettingsPageScaffold(
                    title = "账号",
                    subtitle = "登录后可以看到原画清晰度，接口也更稳定",
                    onBack = { page = SettingsPage.ROOT },
                ) {
                    SettingsSection("B 站账号", null) {
                        AccountCard(
                            account = settings.biliAccount,
                            onLogin = {
                                showLogin = true
                                onStartLogin()
                            },
                            onSignOut = onSignOut,
                        )
                        Text(
                            "Cookie 只保存在本机的 DataStore 里，不会上传到任何服务器。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SettingsPage.ABOUT -> SettingsPageScaffold(
                    title = "关于",
                    subtitle = "叠播 ${BuildConfig.VERSION_NAME}",
                    onBack = { page = SettingsPage.ROOT },
                ) {
                    SettingsSection("版本", null) {
                        Text(
                            "构建于 ${BuildConfig.BUILD_STAMP} · versionCode ${BuildConfig.VERSION_CODE}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "双路直播合成：B 的画面与声音，A 的声音与弹幕，两个 B 站直播间自由组合。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    SettingsSection("说明", null) {
                        Text(
                            "虎牙支持已在 1.0.5 移除。当前版本只对接 B 站，弹幕走 WBI 签名，登录后可以拿到原画。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "本项目仅用于个人学习与自用，请遵守各平台的服务条款，不要用于二次分发或商业用途。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (showLogin) {
        BiliLoginSheet(
            login = login,
            onDismiss = {
                showLogin = false
                onCancelLogin()
            },
            onRefresh = onStartLogin,
            onCookie = onSubmitCookie,
        )
    }
}

@Composable
private fun SettingsRootPage(
    settings: AppSettings,
    account: BiliAccount,
    onBack: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .safeTopPadding()
            .safeBottomPadding()
    ) {
        SettingsHeader(
            title = "设置",
            subtitle = "常用的都收在下面，点进去再细调",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AccountSummary(account) { onOpen(SettingsPage.ACCOUNT) }

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(26.dp))
                                .background(scheme.surfaceContainerLow)
                                .border(
                                    1.dp,
                                    scheme.outlineVariant.copy(alpha = 0.32f),
                                    RoundedCornerShape(26.dp),
                                )
                        ) {
                            SettingsNavRow(
                                icon = Icons.Rounded.Palette,
                                title = "外观",
                                summary = "${themeLabel(settings.themeMode)} · ${monetLabel(settings.monetMode)}",
                                onClick = { onOpen(SettingsPage.APPEARANCE) },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp),
                                color = scheme.outlineVariant.copy(alpha = 0.28f),
                            )
                            SettingsNavRow(
                                icon = Icons.Rounded.Subtitles,
                                title = "弹幕样式",
                                summary = if (settings.danmaku.enabled) {
                                    "已开启 · 字号 ${(settings.danmaku.fontScale * 100).toInt()}%"
                                } else {
                                    "已关闭"
                                },
                                onClick = { onOpen(SettingsPage.DANMAKU) },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp),
                                color = scheme.outlineVariant.copy(alpha = 0.28f),
                            )
                            SettingsNavRow(
                                icon = Icons.Rounded.PlayCircle,
                                title = "播放",
                                summary = "${qualityLabel(settings.qualityQn)} · " +
                                    if (settings.playback.backgroundPlay) "允许后台播放" else "仅前台播放",
                                onClick = { onOpen(SettingsPage.PLAYBACK) },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp),
                                color = scheme.outlineVariant.copy(alpha = 0.28f),
                            )
                            SettingsNavRow(
                                icon = Icons.Rounded.Info,
                                title = "关于",
                                summary = "v${BuildConfig.VERSION_NAME}",
                                onClick = { onOpen(SettingsPage.ABOUT) },
                            )
                        }

                        Text(
                            "叠播 v${BuildConfig.VERSION_NAME} · 双路直播合成，仅用于个人学习与自用",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPageScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeTopPadding()
            .safeBottomPadding()
    ) {
        SettingsHeader(title = title, subtitle = subtitle, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 20.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
        }
        Column(
            Modifier.weight(1f).padding(start = 2.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = scheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun AccountSummary(account: BiliAccount, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.32f), RoundedCornerShape(26.dp))
            .pressable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account.loggedIn) {
            AsyncImage(
                model = account.avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Login,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (account.loggedIn) account.name.ifBlank { "已登录" } else "未登录",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (account.loggedIn) "UID ${account.mid} · 点这里管理登录" else "登录后拿原画，弹幕也更稳",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun AccountCard(
    account: BiliAccount,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    if (account.loggedIn) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(scheme.surfaceContainer)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = account.avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    account.name.ifBlank { "已登录" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillTag("UID ${account.mid}")
                    if (account.level > 0) PillTag("LV${account.level}")
                }
            }
            Text(
                "退出",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .pressable(onClick = onSignOut)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(scheme.surfaceContainer)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("未登录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "扫码或网页登录，原画和弹幕都更稳",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onLogin,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Login,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("登录", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = MaterialTheme.shapes.large,
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content()
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SeedDot(color: Long, selected: Long, onClick: () -> Unit) {
    val isSelected = color == selected
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                }
            )
            .padding(5.dp)
            .clip(CircleShape)
            .background(Color(color))
            .pressable(onClick = onClick)
    )
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun monetLabel(mode: MonetMode): String = when (mode) {
    MonetMode.WALLPAPER -> "跟随壁纸"
    MonetMode.SEED -> "自定义色"
}

private fun qualityLabel(qn: Int): String =
    AppSettings.QUALITY_OPTIONS.firstOrNull { it.qn == qn }?.label ?: "自动"

private fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL
