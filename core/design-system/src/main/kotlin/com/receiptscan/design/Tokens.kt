package com.receiptscan.design

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/** Selectable skin (Settings → Appearance). Skins change fills, card, border, background, wash and tiles only. */
enum class Skin(val label: String) { SIMPLE("Simple"), LAVENDER_MIST("Lavender Mist"), AQUA_PEARL("Aqua Pearl") }

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class TileStyle { FLAT, RAISED_3D }

/** Colour tokens from docs/design/receipt-scan-handover.md section 3.1 (Simple) plus skin overrides (3.2). */
data class SkinTokens(
    val skin: Skin,
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val cardBorder: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val primaryFill: Brush,
    val onPrimary: Color,
    val tonal: Color,
    val onTonal: Color,
    val ok: Color, val okBg: Color,
    val warn: Color, val warnBg: Color, val warnLine: Color,
    val err: Color, val errBg: Color,
    val nav: Color,
    val scrim: Color,
    val snack: Color, val snackText: Color, val snackAction: Color,
    val wash1: Color, val wash2: Color,
    val tileStyle: TileStyle,
    val cameraBg: Color = Color(0xFF07080D),
    val edgeSearching: Color = Color(0xFF8F98FF),
    val edgeLocked: Color = Color(0xFF7BE0A8),
)

object Palette {
    private fun simpleLight() = SkinTokens(
        skin = Skin.SIMPLE, dark = false,
        bg = Color(0xFFF5F5FB), card = Color.White, cardBorder = Color(0xFFE7E8F2), surface2 = Color(0xFFF0F1F8), border = Color(0xFFE7E8F2),
        text = Color(0xFF15172B), muted = Color(0xFF5E6278), primary = Color(0xFF4A56E8), primaryFill = Brush.linearGradient(listOf(Color(0xFF4A56E8), Color(0xFF4A56E8))),
        onPrimary = Color.White, tonal = Color(0xFFECEEFF), onTonal = Color(0xFF3E47C9),
        ok = Color(0xFF1F7A55), okBg = Color(0xFFE3F6EC), warn = Color(0xFF8A4B00), warnBg = Color(0xFFFFF1DF), warnLine = Color(0xFFE0A458),
        err = Color(0xFFB42318), errBg = Color(0xFFFDECEA), nav = Color.White, scrim = Color(0x7A0D0E1A),
        snack = Color(0xFF1E2033), snackText = Color(0xFFF2F2FA), snackAction = Color(0xFFB4B9FF),
        wash1 = Color(0x33AA9CFF), wash2 = Color(0x248CBAFF), tileStyle = TileStyle.FLAT,
    )

    private fun simpleDark() = SkinTokens(
        skin = Skin.SIMPLE, dark = true,
        bg = Color(0xFF0D0E1A), card = Color(0xFF181A2A), cardBorder = Color(0xFF272A3F), surface2 = Color(0xFF222438), border = Color(0xFF272A3F),
        text = Color(0xFFF2F2FA), muted = Color(0xFFA9ABC6), primary = Color(0xFF4A56E8), primaryFill = Brush.linearGradient(listOf(Color(0xFF4A56E8), Color(0xFF4A56E8))),
        onPrimary = Color.White, tonal = Color(0x24B4B9FF), onTonal = Color(0xFFB4B9FF),
        ok = Color(0xFF86D9AE), okBg = Color(0x2486D9AE), warn = Color(0xFFF2B96B), warnBg = Color(0x24F2B96B), warnLine = Color(0xFFB98236),
        err = Color(0xFFFF8A80), errBg = Color(0x24FF8A80), nav = Color(0xFF1C1E31), scrim = Color(0x99000000),
        snack = Color(0xFFE9E9F5), snackText = Color(0xFF15172B), snackAction = Color(0xFF3E47C9),
        wash1 = Color(0x296E5CFF), wash2 = Color(0x1A3876FF), tileStyle = TileStyle.FLAT,
    )

    fun tokens(skin: Skin, dark: Boolean): SkinTokens {
        val base = if (dark) simpleDark() else simpleLight()
        return when (skin) {
            Skin.SIMPLE -> base
            Skin.LAVENDER_MIST -> base.copy(
                skin = skin,
                primaryFill = Brush.linearGradient(listOf(Color(0xFF4A56E8), Color(0xFF6A4FE8)), start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)),
                card = if (dark) Color(0xD9181A2A) else Color(0xBDFFFFFF),
                cardBorder = if (dark) Color(0xFF2E3150) else Color(0xF2FFFFFF),
                wash1 = if (dark) Color(0x4D6E5CFF) else Color(0x99AA9CFF),
                wash2 = if (dark) Color(0x403876FF) else Color(0x998CBAFF),
                tileStyle = TileStyle.RAISED_3D,
            )
            Skin.AQUA_PEARL -> base.copy(
                skin = skin,
                primary = Color(0xFF0F7C86),
                primaryFill = Brush.linearGradient(listOf(Color(0xFF0F7C86), Color(0xFF2C6BC9)), start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)),
                bg = if (dark) base.bg else Color(0xFFF2F7F7),
                tonal = if (dark) Color(0x2449C2CC) else Color(0x1A0F7C86),
                onTonal = if (dark) Color(0xFF7FD6DD) else Color(0xFF0E6E78),
                wash1 = if (dark) Color(0x2914B8A6) else Color(0x5514B8A6),
                wash2 = if (dark) Color(0x1A2C6BC9) else Color(0x402C6BC9),
                tileStyle = TileStyle.RAISED_3D,
            )
        }
    }
}

/** Module colours (handover 3.3). Used for tiles and headers, never for status. */
enum class ModuleColor(val color: Color) {
    RECEIPT(Color(0xFF4A56E8)), BILLS(Color(0xFF12998C)), DOCUMENT(Color(0xFF3B6CF5)), TEXT(Color(0xFF64748B)),
    PRODUCT(Color(0xFF8B5CF6)), NUTRITION(Color(0xFF16A34A)), EXPIRY(Color(0xFFE11D48)), FOOD(Color(0xFFF97316)),
    QR(Color(0xFFF59E0B)), TRANSLATE(Color(0xFF0891B2)), MATH(Color(0xFFDB2777)), COUNTER(Color(0xFFA16207)),
    PLANT(Color(0xFF65A30D)), WIFI(Color(0xFF0284C7)), VOICE(Color(0xFF4A56E8)),
}

object Space {
    val s2 = 2.dp; val s4 = 4.dp; val s6 = 6.dp; val s8 = 8.dp; val s10 = 10.dp; val s12 = 12.dp
    val s16 = 16.dp; val s20 = 20.dp; val s24 = 24.dp; val s32 = 32.dp
    val gutter = 20.dp
}

object Radii {
    val tileSmall = 12.dp; val tile = 15.dp; val tileLarge = 18.dp; val tileXL = 24.dp
    val field = 16.dp; val card = 24.dp; val sheet = 28.dp; val banner = 20.dp
}

object Sizes {
    val button = 56.dp; val buttonLarge = 60.dp; val buttonSmall = 44.dp; val iconButton = 48.dp; val chip = 40.dp
    val row = 64.dp; val rowCompact = 56.dp; val field = 52.dp; val search = 54.dp; val nav = 68.dp; val minTouch = 48.dp
}
