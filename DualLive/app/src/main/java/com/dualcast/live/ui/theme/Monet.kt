package com.dualcast.live.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.pow

/**
 * 一个轻量的莫奈（Material You）配色生成器。
 *
 * Android 12+ 走系统动态取色；其余情况（或用户自定义种子色）用这里的实现：
 * 把种子色转到 HSL，按 Material 的 L* 色调阶梯重新生成一整套 tonal palette，
 * 再用 CIE 相对亮度做一次校正，保证 40 / 80 这类关键色调的明度接近规范。
 */
object Monet {

    fun schemeFor(seed: Color, dark: Boolean): ColorScheme = buildScheme(seed, dark)

    fun dynamicScheme(context: Context, dark: Boolean): ColorScheme? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }.getOrNull()
    }

    fun seedFromBitmap(bitmap: Bitmap): Color? {
        val palette = Palette.from(bitmap)
            .maximumColorCount(32)
            .clearFilters()
            .generate()
        val swatch = palette.vibrantSwatch
            ?: palette.dominantSwatch
            ?: palette.mutedSwatch
            ?: palette.swatches.maxByOrNull { it.population }
        return swatch?.let { Color(it.rgb) }
    }

    private fun buildScheme(seed: Color, dark: Boolean): ColorScheme {
        val hsl = FloatArray(3)
        val argb = seed.toArgb()
        ColorUtils.RGBToHSL(
            android.graphics.Color.red(argb),
            android.graphics.Color.green(argb),
            android.graphics.Color.blue(argb),
            hsl,
        )
        val hue = hsl[0]
        val saturation = hsl[1].coerceIn(0.24f, 0.95f)

        val primarySat = saturation
        val secondarySat = (saturation * 0.42f).coerceAtLeast(0.12f)
        val tertiarySat = (saturation * 0.78f).coerceAtLeast(0.18f)
        val neutralSat = (saturation * 0.055f)
        val neutralVariantSat = (saturation * 0.12f)

        fun p(t: Int) = tone(hue, primarySat, t)
        fun s(t: Int) = tone(hue, secondarySat, t)
        fun te(t: Int) = tone(hue + 60f, tertiarySat, t)
        fun n(t: Int) = tone(hue, neutralSat, t)
        fun nv(t: Int) = tone(hue, neutralVariantSat, t)

        return if (dark) {
            darkColorScheme(
                primary = p(80),
                onPrimary = p(20),
                primaryContainer = p(32),
                onPrimaryContainer = p(92),
                inversePrimary = p(40),
                secondary = s(82),
                onSecondary = s(20),
                secondaryContainer = s(32),
                onSecondaryContainer = s(92),
                tertiary = te(82),
                onTertiary = te(20),
                tertiaryContainer = te(32),
                onTertiaryContainer = te(92),
                background = n(10),
                onBackground = n(92),
                surface = n(10),
                onSurface = n(92),
                surfaceVariant = nv(32),
                // 原来 80 在低亮度背景上偏灰，提到 90 让辅助文案更清楚
                onSurfaceVariant = nv(90),
                surfaceTint = p(80),
                inverseSurface = n(92),
                inverseOnSurface = n(20),
                error = Color(0xFFF2B8B5),
                onError = Color(0xFF601410),
                errorContainer = Color(0xFF8C1D18),
                onErrorContainer = Color(0xFFF9DEDC),
                outline = nv(70),
                outlineVariant = nv(42),
                scrim = Color(0xFF000000),
                surfaceBright = n(26),
                surfaceDim = n(8),
                // 卡片之间拉开层级，否则深色下整页糊成一片
                surfaceContainer = n(17),
                surfaceContainerHigh = n(22),
                surfaceContainerHighest = n(26),
                surfaceContainerLow = n(14),
                surfaceContainerLowest = n(8),
            )
        } else {
            lightColorScheme(
                primary = p(40),
                onPrimary = p(100),
                primaryContainer = p(90),
                onPrimaryContainer = p(10),
                inversePrimary = p(80),
                secondary = s(40),
                onSecondary = s(100),
                secondaryContainer = s(90),
                onSecondaryContainer = s(10),
                tertiary = te(40),
                onTertiary = te(100),
                tertiaryContainer = te(90),
                onTertiaryContainer = te(10),
                background = n(98),
                onBackground = n(10),
                surface = n(98),
                onSurface = n(10),
                surfaceVariant = nv(90),
                onSurfaceVariant = nv(30),
                surfaceTint = p(40),
                inverseSurface = n(20),
                inverseOnSurface = n(95),
                error = Color(0xFFB3261E),
                onError = Color(0xFFFFFFFF),
                errorContainer = Color(0xFFF9DEDC),
                onErrorContainer = Color(0xFF410E0B),
                outline = nv(50),
                outlineVariant = nv(80),
                scrim = Color(0xFF000000),
                surfaceBright = n(98),
                surfaceDim = n(87),
                surfaceContainer = n(94),
                surfaceContainerHigh = n(92),
                surfaceContainerHighest = n(90),
                surfaceContainerLow = n(96),
                surfaceContainerLowest = n(100),
            )
        }
    }

    /**
     * 生成指定 L* 明度、色相与饱和度的颜色，并把实际亮度校正到 L* 对应的位置。
     */
    private fun tone(hue: Float, saturation: Float, lstar: Int): Color {
        if (lstar <= 0) return Color.Black
        if (lstar >= 100) return Color.White
        val normalizedHue = ((hue % 360f) + 360f) % 360f
        val argb = ColorUtils.HSLToColor(
            floatArrayOf(normalizedHue, saturation.coerceIn(0f, 1f), lstar / 100f)
        )
        val r = android.graphics.Color.red(argb) / 255f
        val g = android.graphics.Color.green(argb) / 255f
        val b = android.graphics.Color.blue(argb) / 255f

        val targetY = lstarToY(lstar.toDouble())
        val currentY = relativeLuminance(r, g, b)
        if (currentY < 0.0001f) return Color(argb)

        val factor = (targetY / currentY).toFloat()
        val lr = (linearize(r) * factor).coerceIn(0f, 1f)
        val lg = (linearize(g) * factor).coerceIn(0f, 1f)
        val lb = (linearize(b) * factor).coerceIn(0f, 1f)

        return Color(
            red = delinearize(lr),
            green = delinearize(lg),
            blue = delinearize(lb),
            alpha = 1f,
        )
    }

    private fun lstarToY(lstar: Double): Double {
        val y = if (lstar > 8.0) ((lstar + 16.0) / 116.0).pow(3) else lstar / 903.3
        return y.coerceIn(0.0, 1.0)
    }

    private fun linearize(channel: Float): Float =
        if (channel <= 0.04045f) channel / 12.92f
        else ((channel + 0.055f) / 1.055f).pow(2.4f)

    private fun delinearize(channel: Float): Float =
        if (channel <= 0.0031308f) channel * 12.92f
        else 1.055f * channel.pow(1f / 2.4f) - 0.055f

    private fun relativeLuminance(r: Float, g: Float, b: Float): Float =
        0.2126f * linearize(r) + 0.7152f * linearize(g) + 0.0722f * linearize(b)

    /** 让两个色相之间的差异更直观，用于「随机取色」时避免撞色。 */
    fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}
