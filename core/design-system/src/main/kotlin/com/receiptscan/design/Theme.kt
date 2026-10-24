package com.receiptscan.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)
val InstrumentSerifItalic = FontFamily(Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic))

/** Type scale (handover 3.4). Amounts use tabular numerals via [AppType.num]. */
object AppType {
    private fun s(size: Int, weight: FontWeight, trackingPx: Float = 0f, lh: Float? = null) = TextStyle(
        fontFamily = PlusJakartaSans, fontSize = size.sp, fontWeight = weight,
        letterSpacing = if (trackingPx == 0f) 0.sp else (trackingPx / size).em,
        lineHeight = lh?.let { (size * it).sp } ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    )
    val d1 = s(30, FontWeight.Bold, -0.9f).copy(fontFeatureSettings = "tnum")
    val h1 = s(24, FontWeight.Bold, -0.5f)
    val appBar = s(24, FontWeight.Bold, -0.6f)
    val h2 = s(18, FontWeight.Bold, -0.3f)
    val t1 = s(16, FontWeight.SemiBold)
    val b1 = s(15, FontWeight.Normal, lh = 1.5f)
    val b2 = s(14, FontWeight.Normal, lh = 1.45f)
    val lab = s(13, FontWeight.SemiBold)
    val cap = s(12, FontWeight.Normal)
    val ov = s(12, FontWeight.Bold, 0.8f)
    val num = TextStyle(fontFamily = PlusJakartaSans, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
    val brand = TextStyle(fontFamily = InstrumentSerifItalic, fontSize = 26.sp)
}

val LocalSkin = staticCompositionLocalOf { Palette.tokens(Skin.SIMPLE, false) }
/** True when the in-app Reduce motion switch is on or the system animator scale is 0. */
val LocalReduceMotion = staticCompositionLocalOf { false }
/** Settings → Appearance → Bigger amounts. */
val LocalBiggerAmounts = staticCompositionLocalOf { false }

private fun scheme(t: SkinTokens): ColorScheme = if (t.dark) {
    darkColorScheme(
        primary = t.primary, onPrimary = t.onPrimary, primaryContainer = t.tonal, onPrimaryContainer = t.onTonal,
        secondary = t.primary, onSecondary = t.onPrimary, secondaryContainer = t.tonal, onSecondaryContainer = t.onTonal,
        background = t.bg, onBackground = t.text, surface = t.card, onSurface = t.text, surfaceVariant = t.surface2, onSurfaceVariant = t.muted,
        surfaceContainer = t.card, surfaceContainerHigh = t.card, surfaceContainerHighest = t.surface2, surfaceContainerLow = t.card, surfaceContainerLowest = t.bg,
        outline = t.border, outlineVariant = t.border, error = t.err, onError = t.bg, errorContainer = t.errBg, onErrorContainer = t.err, scrim = t.scrim,
        inverseSurface = t.snack, inverseOnSurface = t.snackText, inversePrimary = t.snackAction,
    )
} else {
    lightColorScheme(
        primary = t.primary, onPrimary = t.onPrimary, primaryContainer = t.tonal, onPrimaryContainer = t.onTonal,
        secondary = t.primary, onSecondary = t.onPrimary, secondaryContainer = t.tonal, onSecondaryContainer = t.onTonal,
        background = t.bg, onBackground = t.text, surface = t.card, onSurface = t.text, surfaceVariant = t.surface2, onSurfaceVariant = t.muted,
        surfaceContainer = t.card, surfaceContainerHigh = t.card, surfaceContainerHighest = t.surface2, surfaceContainerLow = t.card, surfaceContainerLowest = t.bg,
        outline = t.border, outlineVariant = t.border, error = t.err, onError = androidx.compose.ui.graphics.Color.White, errorContainer = t.errBg, onErrorContainer = t.err, scrim = t.scrim,
        inverseSurface = t.snack, inverseOnSurface = t.snackText, inversePrimary = t.snackAction,
    )
}

private val typography = Typography(
    displaySmall = AppType.d1, headlineMedium = AppType.h1, headlineSmall = AppType.appBar, titleLarge = AppType.h2, titleMedium = AppType.t1,
    titleSmall = AppType.lab, bodyLarge = AppType.b1, bodyMedium = AppType.b2, bodySmall = AppType.cap, labelLarge = AppType.lab.copy(fontSize = 15.sp),
    labelMedium = AppType.lab, labelSmall = AppType.cap,
)

/**
 * App theme: an M3 ColorScheme derived from the skin tokens, plus [LocalSkin] for skin-only rendering
 * (gradient fills, wash, tile style). Hosts embedding scanner modules wrap them in this theme.
 */
@Composable
fun ReceiptScanTheme(
    skin: Skin = Skin.SIMPLE,
    mode: ThemeMode = ThemeMode.SYSTEM,
    reduceMotion: Boolean = false,
    biggerAmounts: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val tokens = Palette.tokens(skin, dark)
    CompositionLocalProvider(LocalSkin provides tokens, LocalReduceMotion provides reduceMotion, LocalBiggerAmounts provides biggerAmounts) {
        MaterialTheme(colorScheme = scheme(tokens), typography = typography, content = content)
    }
}
