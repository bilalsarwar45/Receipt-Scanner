package com.receiptscan.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.receiptscan.scanner.contracts.ValueSource

val skin: SkinTokens @Composable get() = LocalSkin.current

// ---------------------------------------------------------------- Buttons

enum class ButtonVariant { PRIMARY, TONAL, OUTLINE, GHOST, DANGER, DANGER_TONAL }
enum class ButtonSize(val height: Dp, val textSize: Int) { LARGE(60.dp, 16), NORMAL(56.dp, 15), SMALL(44.dp, 14) }

/**
 * Pill button (handover 4). Disabled keeps its label at 38% opacity; pass [disabledReason] so the
 * reason is shown next to it as the handover requires.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    size: ButtonSize = ButtonSize.NORMAL,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    disabledReason: String? = null,
    fill: Boolean = false,
) {
    val t = skin
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val bg: Brush? = when (variant) {
        ButtonVariant.PRIMARY -> t.primaryFill
        ButtonVariant.TONAL -> SolidColor(t.tonal)
        ButtonVariant.DANGER -> SolidColor(t.err)
        ButtonVariant.DANGER_TONAL -> SolidColor(t.errBg)
        ButtonVariant.OUTLINE, ButtonVariant.GHOST -> null
    }
    val fg = when (variant) {
        ButtonVariant.PRIMARY -> t.onPrimary
        ButtonVariant.TONAL -> t.onTonal
        ButtonVariant.DANGER -> Color.White
        ButtonVariant.DANGER_TONAL -> t.err
        ButtonVariant.OUTLINE -> t.text
        ButtonVariant.GHOST -> t.primary
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = CircleShape
        var m = Modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = size.height)
            .scale(if (pressed && !reduce && enabled) 0.97f else 1f)
            .clip(shape)
        if (bg != null) m = m.background(bg, shape)
        if (variant == ButtonVariant.OUTLINE) m = m.border(1.dp, t.border, shape)
        m = m.alpha(if (enabled) 1f else 0.38f)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (size == ButtonSize.SMALL) 16.dp else 22.dp)
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (size == ButtonSize.SMALL) 18.dp else 20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = fg, style = AppType.lab.copy(fontSize = size.textSize.sp, fontWeight = FontWeight.Bold), maxLines = 2, textAlign = TextAlign.Center)
        }
        if (!enabled && disabledReason != null) {
            Text(disabledReason, style = AppType.cap, color = t.muted, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
        }
    }
}

/** 48 dp icon button. [surface] gives it the card background and border used on tab roots. */
@Composable
fun AppIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, surface: Boolean = false, tint: Color? = null, enabled: Boolean = true) {
    val t = skin
    Box(
        modifier
            .size(Sizes.iconButton)
            .clip(CircleShape)
            .then(if (surface) Modifier.background(t.card, CircleShape).border(1.dp, t.cardBorder, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: t.text, modifier = Modifier.size(22.dp))
    }
}

// ---------------------------------------------------------------- Chips

enum class ChipStyle { DEFAULT, TONAL, OUTLINE }

/** 40 dp chip with a 48 dp hit area. Selected chips use inverse text and background. */
@Composable
fun AppChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, icon: ImageVector? = null, style: ChipStyle = ChipStyle.DEFAULT, enabled: Boolean = true) {
    val t = skin
    val bg by animateColorAsState(
        when {
            selected -> t.text
            style == ChipStyle.TONAL -> t.tonal
            style == ChipStyle.OUTLINE -> Color.Transparent
            else -> t.surface2
        },
        animationSpec = tween(150), label = "chip",
    )
    val fg = when { selected -> t.bg; style == ChipStyle.TONAL -> t.onTonal; else -> t.text }
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(Sizes.chip)
                .clip(CircleShape)
                .background(bg, CircleShape)
                .then(if (style == ChipStyle.OUTLINE && !selected) Modifier.border(1.dp, t.border, CircleShape) else Modifier)
                .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
                .padding(horizontal = 14.dp)
                .alpha(if (enabled) 1f else 0.38f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = AppType.lab, color = fg, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- Badges

enum class BadgeKind { OK, WARN, ERR, INFO, NEUTRAL }

/** 22 dp status badge. Always text plus an icon or dot, never colour alone. */
@Composable
fun StatusBadge(kind: BadgeKind, text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val t = skin
    val (bg, fg) = when (kind) {
        BadgeKind.OK -> t.okBg to t.ok
        BadgeKind.WARN -> t.warnBg to t.warn
        BadgeKind.ERR -> t.errBg to t.err
        BadgeKind.INFO -> t.tonal to t.onTonal
        BadgeKind.NEUTRAL -> t.surface2 to t.muted
    }
    val glyph = icon ?: when (kind) {
        BadgeKind.WARN -> AppIcons.alert
        BadgeKind.ERR -> AppIcons.error
        else -> null
    }
    Row(
        modifier.heightIn(min = 22.dp).clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Icon(glyph, null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        } else if (kind == BadgeKind.OK) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = AppType.cap.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

/** Value source badge (handover 4.4). Tapping a Calculated badge shows its formula via [onClick]. */
@Composable
fun SourceBadge(source: ValueSource, modifier: Modifier = Modifier, enteredLabel: String = "Edited") {
    when (source) {
        ValueSource.FROM_PHOTO -> StatusBadge(BadgeKind.INFO, "From photo", modifier, AppIcons.scan)
        ValueSource.EDITED -> StatusBadge(BadgeKind.NEUTRAL, enteredLabel, modifier, AppIcons.user)
        ValueSource.CALCULATED -> StatusBadge(BadgeKind.NEUTRAL, "Calculated", modifier, AppIcons.math)
        ValueSource.SUGGESTED -> StatusBadge(BadgeKind.INFO, "Suggested", modifier, AppIcons.sparkle)
        ValueSource.ESTIMATE -> StatusBadge(BadgeKind.NEUTRAL, "Estimate", modifier, AppIcons.info)
    }
}

@Composable fun ConfirmedBadge(modifier: Modifier = Modifier) = StatusBadge(BadgeKind.OK, "Confirmed", modifier)
@Composable fun NeedsReviewBadge(modifier: Modifier = Modifier) = StatusBadge(BadgeKind.WARN, "Needs review", modifier)

// ---------------------------------------------------------------- Tiles and cards

/** Module icon container. The skin decides flat or raised 3D. */
@Composable
fun ModuleTile(module: ModuleColor, icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val t = skin
    val radius = when { size >= 72.dp -> Radii.tileXL; size >= 56.dp -> Radii.tileLarge; size >= 44.dp -> Radii.tile; else -> Radii.tileSmall }
    val shape = RoundedCornerShape(radius)
    val c = module.color
    val base = if (t.tileStyle == TileStyle.RAISED_3D) {
        Modifier.shadow(4.dp, shape, ambientColor = c, spotColor = c)
            .background(Brush.verticalGradient(listOf(c.copy(alpha = 0.92f), c)), shape)
    } else {
        Modifier.background(c.copy(alpha = if (t.dark) 0.22f else 0.12f), shape)
    }
    Box(modifier.size(size).clip(shape).then(base), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (t.tileStyle == TileStyle.RAISED_3D) Color.White else (if (t.dark) c.copy(alpha = 1f).lighten() else c), modifier = Modifier.size(size * 0.5f))
    }
}

private fun Color.lighten(): Color = Color(red + (1 - red) * 0.35f, green + (1 - green) * 0.35f, blue + (1 - blue) * 0.35f, alpha)

/** Card: radius 24, 1 dp border, very soft shadow in light themes, border only in dark. */
@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(16.dp), content: @Composable () -> Unit) {
    val t = skin
    val shape = RoundedCornerShape(Radii.card)
    Box(
        modifier
            .then(if (t.dark) Modifier else Modifier.shadow(2.dp, shape, ambientColor = Color(0x14000000), spotColor = Color(0x14000000)))
            .clip(shape)
            .background(t.card, shape)
            .border(1.dp, t.cardBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
    ) { content() }
}

// ---------------------------------------------------------------- Banners

/** Inline banner (radius 20): icon + title + body + optional action. */
@Composable
fun InlineBanner(kind: BadgeKind, title: String, modifier: Modifier = Modifier, body: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null, icon: ImageVector? = null) {
    val t = skin
    val (bg, fg) = when (kind) {
        BadgeKind.OK -> t.okBg to t.ok
        BadgeKind.WARN -> t.warnBg to t.warn
        BadgeKind.ERR -> t.errBg to t.err
        BadgeKind.INFO, BadgeKind.NEUTRAL -> t.tonal to t.onTonal
    }
    val glyph = icon ?: when (kind) { BadgeKind.OK -> AppIcons.checkCircle; BadgeKind.WARN -> AppIcons.alert; BadgeKind.ERR -> AppIcons.error; else -> AppIcons.info }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.banner)).background(bg).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(glyph, null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.lab.copy(fontSize = 14.sp), color = if (kind == BadgeKind.INFO || kind == BadgeKind.NEUTRAL) t.text else fg)
            if (body != null) Text(body, style = AppType.b2, color = t.text.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp))
            if (actionLabel != null && onAction != null) {
                Text(
                    actionLabel, style = AppType.lab, color = t.primary,
                    modifier = Modifier.padding(top = 4.dp).heightIn(min = 40.dp).clickable(role = Role.Button, onClick = onAction).padding(vertical = 10.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Fields

enum class FieldState { DEFAULT, ERROR, WARN, OK }

/** 52 dp field with the label above and helper below (handover 4). */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    state: FieldState = FieldState.DEFAULT,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = AppType.b1,
    minLines: Int = 1,
) {
    val t = skin
    val accent = when (state) { FieldState.ERROR -> t.err; FieldState.WARN -> t.warnLine; FieldState.OK -> t.ok; FieldState.DEFAULT -> t.primary }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = AppType.lab, color = t.text, modifier = Modifier.weight(1f))
            badge?.invoke()
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.field),
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = textStyle.copy(color = t.text),
            placeholder = placeholder?.let { { Text(it, style = textStyle, color = t.muted) } },
            leadingIcon = leading,
            trailingIcon = trailing,
            isError = state == FieldState.ERROR,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onSearch = { onDone?.invoke() }),
            shape = RoundedCornerShape(Radii.field),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = if (state == FieldState.DEFAULT) t.border else accent,
                errorBorderColor = t.err,
                focusedContainerColor = t.card,
                unfocusedContainerColor = t.card,
                disabledContainerColor = t.surface2,
                errorContainerColor = t.card,
                cursorColor = t.primary,
            ),
        )
        if (helper != null) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = when (state) { FieldState.ERROR -> AppIcons.error; FieldState.WARN -> AppIcons.alert; FieldState.OK -> AppIcons.check; else -> null }
                if (icon != null) {
                    Icon(icon, null, tint = if (state == FieldState.WARN) t.warn else accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(helper, style = AppType.cap, color = when (state) { FieldState.ERROR -> t.err; FieldState.WARN -> t.warn; FieldState.OK -> t.ok; else -> t.muted })
            }
        }
    }
}

/** 54 dp search pill. */
@Composable
fun SearchPill(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val t = skin
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = Sizes.search),
        singleLine = true,
        placeholder = { Text(placeholder, style = AppType.b1, color = t.muted) },
        leadingIcon = { Icon(AppIcons.search, null, tint = t.muted) },
        trailingIcon = {
            if (trailing != null) trailing()
            else if (value.isNotEmpty()) AppIconButton(AppIcons.close, "Clear search", { onValueChange("") })
        },
        shape = CircleShape,
        textStyle = AppType.b1.copy(color = t.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = t.primary, unfocusedBorderColor = t.cardBorder,
            focusedContainerColor = t.card, unfocusedContainerColor = t.card, cursorColor = t.primary,
        ),
    )
}

// ---------------------------------------------------------------- Text helpers

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val t = skin
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = AppType.h2, color = t.text, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null && onAction != null) {
            Text(action, style = AppType.lab, color = t.primary, modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 4.dp, vertical = 14.dp))
        }
    }
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = AppType.ov, color = skin.muted, modifier = modifier)
}

/** Amount text with tabular numerals. Honors Bigger amounts. */
@Composable
fun AmountText(text: String, modifier: Modifier = Modifier, size: Int = 16, color: Color = Color.Unspecified, approx: Boolean = false) {
    val big = LocalBiggerAmounts.current
    Text(
        (if (approx) "≈ " else "") + text,
        style = AppType.num.copy(fontSize = (if (big) size * 1.2f else size.toFloat()).sp),
        color = if (color == Color.Unspecified) skin.text else color,
        modifier = modifier,
        maxLines = 2,
    )
}

// ---------------------------------------------------------------- Rows

/** List row: leading tile, title + meta, trailing amount + badge. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    compact: Boolean = false,
    selected: Boolean = false,
) {
    val t = skin
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) Sizes.rowCompact else Sizes.row)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) t.tonal else Color.Transparent)
            .then(
                if (onClick != null || onLongClick != null) Modifier.combinedClickableCompat(onClick ?: {}, onLongClick) else Modifier,
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.t1, color = t.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta != null) Text(meta, style = AppType.cap, color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) { trailing() }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

// ---------------------------------------------------------------- Empty and error states

/** Every state has a title, one sentence saying what still works, and one primary action (handover 8.4). */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null, secondaryLabel: String? = null, onSecondary: (() -> Unit)? = null) {
    val t = skin
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(Radii.tileXL)).background(t.tonal), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = t.onTonal, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = AppType.h2, color = t.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = AppType.b2, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            AppButton(actionLabel, onAction)
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(8.dp))
            AppButton(secondaryLabel, onSecondary, variant = ButtonVariant.GHOST)
        }
    }
}

// ---------------------------------------------------------------- Navigation

data class NavItem(val label: String, val icon: ImageVector)

/** Floating pill nav (68 dp). The active item expands into a filled pill with its label. */
@Composable
fun FloatingNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val t = skin
    Surface(
        modifier = modifier.padding(start = 20.dp, end = 20.dp, bottom = 26.dp).fillMaxWidth().height(Sizes.nav),
        shape = CircleShape,
        color = t.nav,
        border = BorderStroke(1.dp, t.cardBorder),
        shadowElevation = if (t.dark) 0.dp else 8.dp,
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEachIndexed { i, item ->
                val active = i == selected
                val shape = CircleShape
                Row(
                    Modifier
                        .weight(if (active) 1.5f else 1f)
                        .height(52.dp)
                        .clip(shape)
                        .then(if (active) Modifier.background(t.primaryFill, shape) else Modifier)
                        .clickable(role = Role.Tab, onClick = { onSelect(i) })
                        .semantics { contentDescription = item.label },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (active) {
                        Icon(item.icon, null, tint = t.onPrimary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(item.label, style = AppType.lab, color = t.onPrimary, maxLines = 1)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(item.icon, null, tint = t.muted, modifier = Modifier.size(22.dp))
                            Text(item.label, style = AppType.cap.copy(fontSize = 11.sp), color = t.muted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** App bar used on tab roots and pushed screens. */
@Composable
fun AppTopBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, brand: Boolean = false) {
    val t = skin
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = if (onBack != null) 8.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            AppIconButton(AppIcons.back, "Back", onBack)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title,
            style = if (brand) AppType.brand.copy(fontSize = 28.sp) else if (onBack != null) AppType.h2 else AppType.appBar,
            color = t.text,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CompositionLocalProvider(LocalContentColor provides t.text) { actions() }
    }
}

// ---------------------------------------------------------------- Wash and skeleton

/** Top 440 dp wash with two radial gradients, drawn behind the content (handover 12). */
fun Modifier.wash(tokens: SkinTokens): Modifier = drawBehind {
    val h = 440.dp.toPx().coerceAtMost(size.height)
    drawRect(Brush.radialGradient(listOf(tokens.wash1, Color.Transparent), center = Offset(size.width * 0.15f, 0f), radius = size.width * 0.9f), size = androidx.compose.ui.geometry.Size(size.width, h))
    drawRect(Brush.radialGradient(listOf(tokens.wash2, Color.Transparent), center = Offset(size.width * 0.95f, h * 0.2f), radius = size.width * 0.8f), size = androidx.compose.ui.geometry.Size(size.width, h))
}

/** Shimmer block. With Reduce motion the block is static. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 16.dp, radius: Dp = 8.dp) {
    val t = skin
    val reduce = LocalReduceMotion.current
    val alpha = if (reduce) 0.6f else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        transition.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a").value
    }
    Box(modifier.height(height).clip(RoundedCornerShape(radius)).background(t.surface2.copy(alpha = alpha)))
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(skin.border))
}

/** Key/value line used on result screens: label, value, source badge. */
@Composable
fun ValueLine(label: String, value: String, source: ValueSource?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, emphasize: Boolean = false, warn: String? = null) {
    val t = skin
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (warn != null) t.warnBg else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = if (warn != null) 10.dp else 0.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = AppType.cap, color = t.muted)
                Text(value, style = if (emphasize) AppType.num.copy(fontSize = 18.sp) else AppType.t1, color = t.text)
            }
            if (source != null) SourceBadge(source)
        }
        if (warn != null) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.alert, null, tint = t.warn, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(warn, style = AppType.cap, color = t.warn)
            }
        }
    }
}

/** Screen background with the skin wash. */
@Composable
fun ScreenSurface(modifier: Modifier = Modifier, withWash: Boolean = true, content: @Composable () -> Unit) {
    val t = skin
    Box(modifier.background(t.bg).then(if (withWash) Modifier.wash(t) else Modifier)) { content() }
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, muted: Boolean = false, style: TextStyle = AppType.b2) {
    Text(text, style = style, color = if (muted) skin.muted else skin.text, modifier = modifier)
}

/** Determinate or indeterminate linear progress, 8 dp. */
@Composable
fun AppProgress(progress: Float?, modifier: Modifier = Modifier) {
    val t = skin
    if (progress == null) {
        androidx.compose.material3.LinearProgressIndicator(modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = t.primary, trackColor = t.surface2)
    } else {
        androidx.compose.material3.LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = t.primary, trackColor = t.surface2)
    }
}

/** Material typography and colors shortcut for code that needs them. */
object DS {
    val colors @Composable get() = MaterialTheme.colorScheme
}
