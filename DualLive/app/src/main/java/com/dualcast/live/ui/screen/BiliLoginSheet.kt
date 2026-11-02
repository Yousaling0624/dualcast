package com.dualcast.live.ui.screen

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dualcast.live.data.net.QrState
import com.dualcast.live.data.net.cookieValue
import com.dualcast.live.ui.LoginUiState
import com.dualcast.live.ui.components.SelectableChip
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlinx.coroutines.delay

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliLoginSheet(
    login: LoginUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onCookie: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) }
    val landscape = LocalConfiguration.current.screenWidthDp >
        LocalConfiguration.current.screenHeightDp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        // 横屏时底部面板很宽，内容限宽居中，二维码不会飘到屏幕边上
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .heightIn(max = if (landscape) 380.dp else 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "登录 B 站",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "登录后可以看原画，弹幕连接也更稳定。不登录也能看，只是清晰度可能受限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectableChip(
                    text = "扫码登录",
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    modifier = Modifier.weight(1f),
                )
                SelectableChip(
                    text = "网页登录",
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    modifier = Modifier.weight(1f),
                )
            }

            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "loginTab",
            ) { index ->
                if (index == 0) {
                    QrLoginPanel(login = login, onRefresh = onRefresh)
                } else {
                    WebLoginPanel(onCookie = onCookie)
                }
            }
        }
        }
    }
}

@Composable
private fun QrLoginPanel(
    login: LoginUiState,
    onRefresh: () -> Unit,
) {
    val state = login.state
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(228.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                login.ticket != null && !login.expired && !login.success -> {
                    QrCanvas(content = login.ticket.url)
                    if (state is QrState.Scanned) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.92f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(44.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("已扫码，请在手机上确认", color = Color.Black)
                            }
                        }
                    }
                }

                login.success -> {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(64.dp),
                    )
                }

                login.loading || (login.ticket == null && login.state !is QrState.Failure) -> {
                    CircularProgressIndicator(color = Color(0xFF6750A4))
                }

                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (login.expired) "二维码已过期" else "二维码生成失败",
                            color = Color.Black,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF6750A4))
                                .clickable(onClick = onRefresh)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("刷新", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        Text(
            text = when {
                login.success -> "登录成功"
                state is QrState.Scanned -> "已扫码，请在手机上点确认"
                login.expired -> "二维码三分钟有效，点上面按钮重新获取"
                state is QrState.Failure -> state.message
                else -> "用 B 站手机客户端扫一扫"
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = if (state is QrState.Failure) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Text(
            "扫码只用于获取登录态，账号密码不会经过本应用。Cookie 只保存在本机。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 用 ZXing 的编码器拿到原始模块矩阵，再按模块逐格画，避免生成大 Bitmap。 */
@Composable
private fun QrCanvas(content: String) {
    val matrix = remember(content) {
        runCatching {
            Encoder.encode(
                content,
                ErrorCorrectionLevel.M,
                mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 0),
            ).matrix
        }.getOrNull()
    }
    val color = Color(0xFF111111)

    if (matrix == null) {
        Text("二维码内容无效", color = Color.Black)
        return
    }

    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val quiet = 2
        val total = matrix.width + quiet * 2
        val cell = size.width / total
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y).toInt() == 1) {
                    drawRect(
                        color = color,
                        topLeft = Offset((x + quiet) * cell, (y + quiet) * cell),
                        size = Size(cell + 0.6f, cell + 0.6f),
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebLoginPanel(onCookie: (String) -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var found by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.runCatching {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }

    LaunchedEffect(webView) {
        if (webView == null) return@LaunchedEffect
        while (!found) {
            delay(1_200)
            val cookie = runCatching {
                CookieManager.getInstance().getCookie("https://www.bilibili.com")
            }.getOrNull().orEmpty()
            if (cookieValue(cookie, "SESSDATA").isNotBlank()) {
                found = true
                onCookie(cookie)
                return@LaunchedEffect
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(430.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = MOBILE_UA
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        loadUrl("https://passport.bilibili.com/h5-app/passport/login")
                        webView = this
                    }
                },
            )
        }
        Text(
            text = if (found) "已获取到登录态" else "在下面用手机号或账号密码登录，完成后会自动读取登录态",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "网页登录用的是 B 站官方页面，验证码、短信校验都在页面内完成。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
