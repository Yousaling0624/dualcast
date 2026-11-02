package com.dualcast.live.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.dualcast.live.data.AppSettings
import com.dualcast.live.data.MonetMode
import com.dualcast.live.data.ThemeMode

@Composable
fun DualCastTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val scheme = remember(settings.monetMode, settings.seedColor, dark, context) {
        val fromWallpaper = if (settings.monetMode == MonetMode.WALLPAPER) {
            Monet.dynamicScheme(context, dark)
        } else {
            null
        }
        fromWallpaper ?: Monet.schemeFor(Color(settings.seedColor), dark)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        shapes = DualCastShapes,
        typography = DualCastTypography,
    ) {
        // MaterialTheme 本身不提供 LocalContentColor，缺省值是纯黑。
        // 深色主题下所有没写 color 的 Text 都会变成黑字，这里统一兜底。
        CompositionLocalProvider(
            LocalContentColor provides scheme.onBackground,
            content = content,
        )
    }
}
