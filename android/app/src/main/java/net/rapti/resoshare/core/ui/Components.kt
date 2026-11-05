package net.rapti.resoshare.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Gradient
import net.rapti.resoshare.core.ui.theme.Kalpurush
import net.rapti.resoshare.core.ui.theme.Reso

// ---------------------------------------------------------------- text helpers

@Composable
fun T(
    text: String,
    size: TextUnit = 15.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Reso.colors.text,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(fontFamily = Kalpurush, fontSize = size, fontWeight = weight, color = color,
            lineHeight = if (lineHeight == TextUnit.Unspecified) (size.value * 1.45f).sp else lineHeight),
        textAlign = align,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------- interaction

/** Subtle press-down scale used on all tappable surfaces for a tactile feel. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit, role: Role = Role.Button): Modifier {
    val source = remember { MutableInteractionSource() }
    val scale = remember { Animatable(1f) }
    // Press state is collected in a coroutine (not as composition state), and the scale is
    // read only in the layer block: pressing or starting a scroll on a card never recomposes it.
    LaunchedEffect(source, enabled) {
        source.interactions.collect { i ->
            val target = when (i) {
                is PressInteraction.Press -> if (enabled) 0.965f else 1f
                is PressInteraction.Release, is PressInteraction.Cancel -> 1f
                else -> return@collect
            }
            launch { scale.animateTo(target, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) }
        }
    }
    return this
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, role = role, onClick = onClick)
}

// ---------------------------------------------------------------- header

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Reso.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.header)
            .statusBarsPadding()
            .headerBubble()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBack != null) {
                CircleIconButton(ResoIcons.Back, t("পেছনে যান", "Back"), onBack)
            }
            Column(Modifier.weight(1f)) {
                T(title, 22.sp, FontWeight.Bold, Color.White, maxLines = 2)
                if (subtitle != null) T(subtitle, 14.sp, color = Brand.HeaderMuted)
            }
            actions()
        }
        if (below != null) below()
    }
}

/** Large translucent circle in the header's top-right corner (v2 design). */
fun Modifier.headerBubble(): Modifier = this.drawBehind {
    val r = size.width * 0.32f
    drawCircle(Color.White.copy(alpha = 0.06f), radius = r, center = androidx.compose.ui.geometry.Offset(size.width - r * 0.35f, r * 0.15f))
    val gr = size.width * 0.42f
    val gc = androidx.compose.ui.geometry.Offset(size.width * 0.08f, size.height + gr * 0.25f)
    drawCircle(Brush.radialGradient(listOf(Brand.Amber.copy(alpha = 0.10f), Color.Transparent), center = gc, radius = gr), radius = gr, center = gc)
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    bg: Color = Reso.colors.headerChip,
    fg: Color = Color.White,
    size: Dp = 44.dp,
    badge: Int = 0,
) {
    Box {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(bg)
                .pressable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        }
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .heightIn(min = 18.dp)
                    .widthIn(min = 18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Brand.Amber)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { T(if (badge > 99) Bn.digits("99+") else Bn.num(badge), 11.sp, FontWeight.Bold, Brand.Navy, lineHeight = 14.sp) }
        }
    }
}

@Composable
fun HeaderPill(text: String, bg: Color = Reso.colors.headerChip, fg: Color = Brand.Gold) {
    Box(Modifier.background(bg, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 5.dp)) {
        T(text, 13.sp, FontWeight.Bold, fg, maxLines = 1)
    }
}

/** Two stat tiles inside the navy header (transactions / dividend screens). */
@Composable
fun HeaderStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Reso.colors.headerChip, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        T(label, 13.sp, color = Brand.HeaderMuted, maxLines = 1)
        T(value, 18.sp, FontWeight.Bold, Brand.Gold, maxLines = 1)
    }
}

// ---------------------------------------------------------------- containers

@Composable
fun ResoCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    radius: Dp = 14.dp,
    bg: Color = Reso.colors.surface,
    border: Color = Reso.colors.border,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(shape) else Modifier)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun GradientCard(
    g: Gradient,
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    padding: PaddingValues = PaddingValues(14.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .then(if (onClick != null) Modifier.clip(shape) else Modifier)
            .background(Brush.linearGradient(listOf(g.start, g.end)), shape)
            .border(1.dp, g.border, shape)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Decorative translucent circle used on hero cards. */
@Composable
fun Bubble(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Reso.colors.border))
}

@Composable
fun IconDisc(icon: ImageVector, bg: Color, fg: Color, size: Dp = 32.dp, iconSize: Dp = 18.dp) {
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun TextDisc(text: String, bg: Color, fg: Color, size: Dp = 32.dp, textSize: TextUnit = 16.sp) {
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        T(text, textSize, FontWeight.Bold, fg, lineHeight = textSize * 1.2f)
    }
}

@Composable
fun Avatar(name: String, size: Dp = 44.dp, bg: Color = Reso.colors.tagBlueBg, fg: Color = Reso.colors.tagBlueText) {
    TextDisc(Bn.initial(name), bg, fg, size, (size.value * 0.42f).sp)
}

// ---------------------------------------------------------------- buttons

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = 54.dp,
) {
    val active = enabled && !loading
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(if (enabled) Brand.Amber else Reso.colors.track)
            .pressable(enabled = active, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Brand.Navy, strokeWidth = 2.5.dp)
        } else {
            if (icon != null) {
                Icon(icon, null, tint = Brand.Navy, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
            }
            T(text, 17.sp, FontWeight.Bold, if (enabled) Brand.Navy else Reso.colors.muted, maxLines = 1)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    color: Color = Reso.colors.ink,
    height: Dp = 54.dp,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .border(1.5.dp, color.copy(alpha = if (enabled) 1f else 0.4f), shape)
            .pressable(enabled = enabled && !loading, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
        else {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            T(text, 16.sp, FontWeight.Bold, color.copy(alpha = if (enabled) 1f else 0.5f), maxLines = 1, align = TextAlign.Center)
        }
    }
}

@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 50.dp,
) {
    val c = Reso.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(c.ink.copy(alpha = if (enabled) 1f else 0.4f))
            .pressable(enabled = enabled && !loading, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = c.onInk, strokeWidth = 2.dp)
        else T(text, 16.sp, FontWeight.Bold, c.onInk, maxLines = 1)
    }
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, height: Dp = 50.dp) =
    SecondaryButton(text, onClick, modifier, enabled, loading, color = Reso.colors.red, height = height)

@Composable
fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: TextUnit = 14.sp, color: Color = Reso.colors.ink) {
    Box(modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = TextStyle(fontFamily = Kalpurush, fontSize = size, fontWeight = FontWeight.Bold, color = color,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        )
    }
}

/** Square ± button used by steppers. */
@Composable
fun StepButton(plus: Boolean, onClick: () -> Unit, size: Dp = 56.dp, enabled: Boolean = true) {
    val c = Reso.colors
    val shape = RoundedCornerShape(if (size < 50.dp) 12.dp else 14.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(if (plus) c.ink else c.surface)
            .border(1.dp, if (plus) c.ink else c.inputBorder, shape)
            .pressable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (plus) t("বাড়ান", "Increase") else t("কমান", "Decrease") },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (plus) ResoIcons.Plus else ResoIcons.Minus, null, tint = if (plus) c.onInk else c.ink,
            modifier = Modifier.size(if (size < 50.dp) 20.dp else 24.dp))
    }
}

// ---------------------------------------------------------------- inputs

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun Field(
    label: String?,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Next,
    password: Boolean = false,
    error: String? = null,
    enabled: Boolean = true,
    maxLength: Int = 120,
    singleLine: Boolean = true,
    minHeight: Dp = 52.dp,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    onDone: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    hint: String? = null,
    leading: ImageVector? = null,
) {
    val c = Reso.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    var reveal by rememberSaveable { mutableStateOf(false) }
    // Keep the focused field visible above the keyboard: once the IME has finished opening
    // (the scroll container has resized), scroll this field into view.
    val bringIntoView = remember { BringIntoViewRequester() }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(focused, imeVisible) {
        if (focused) {
            kotlinx.coroutines.delay(280)
            bringIntoView.bringIntoView()
        }
    }
    val borderColor by animateColorAsState(
        when { error != null -> c.red; focused -> c.ink; else -> c.inputBorder }, tween(180), label = "border",
    )
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) T(label, 14.sp, FontWeight.Bold, c.text)
        val shape = RoundedCornerShape(12.dp)
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= maxLength) onChange(it) },
            enabled = enabled,
            singleLine = singleLine,
            interactionSource = source,
            textStyle = TextStyle(fontFamily = Kalpurush, fontSize = 16.sp, color = if (enabled) c.text else c.muted, lineHeight = 24.sp),
            cursorBrush = SolidColor(c.ink),
            visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboard,
                imeAction = ime,
                autoCorrectEnabled = false,
                capitalization = capitalization,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
                onDone = { focus.clearFocus(); onDone?.invoke() },
                onGo = { focus.clearFocus(); onDone?.invoke() },
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .clip(shape)
                        .background(if (enabled) c.surfaceAlt else c.bg)
                        .border(if (focused || error != null) 1.5.dp else 1.dp, borderColor, shape)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    if (leading != null) {
                        Icon(leading, null, tint = if (focused) c.ink else c.muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) T(placeholder, 16.sp, color = c.muted.copy(alpha = 0.8f), maxLines = if (singleLine) 1 else 4)
                        inner()
                    }
                    if (password) {
                        Icon(
                            if (reveal) ResoIcons.EyeOff else ResoIcons.Eye,
                            contentDescription = if (reveal) t("পাসওয়ার্ড লুকান", "Hide password") else t("পাসওয়ার্ড দেখুন", "Show password"),
                            tint = c.muted,
                            modifier = Modifier.size(22.dp).clip(CircleShape).clickable { reveal = !reveal },
                        )
                    } else trailing?.invoke()
                }
            },
        )
        AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            T(error ?: "", 13.sp, color = c.red)
        }
        if (hint != null && error == null) T(hint, 12.sp, color = c.muted)
    }
}

@Composable
fun CheckBox(checked: Boolean, onChange: (Boolean) -> Unit, size: Dp = 24.dp, enabled: Boolean = true) {
    val c = Reso.colors
    val bg by animateColorAsState(if (checked) c.ink else Color.Transparent, tween(150), label = "cb")
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(bg)
            .border(2.dp, if (checked) c.ink else c.inputBorder, shape)
            .clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(ResoIcons.Check, null, tint = c.onInk, modifier = Modifier.size(size * 0.75f))
    }
}

@Composable
fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CheckBox(checked, onChange, enabled = enabled)
        content()
    }
}

/** Setting row with title/subtitle and a checkbox toggle on the right (as in the design). */
@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(title, 16.sp, FontWeight.Bold, Reso.colors.text.copy(alpha = if (enabled) 1f else 0.5f))
            if (subtitle != null) T(subtitle, 13.sp, color = Reso.colors.muted)
        }
        Spacer(Modifier.width(12.dp))
        CheckBox(checked, onChange, size = 26.dp, enabled = enabled)
    }
}

@Composable
fun RadioCard(selected: Boolean, title: String, note: String?, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    val c = Reso.colors
    val shape = RoundedCornerShape(12.dp)
    val border by animateColorAsState(if (selected) c.ink else c.inputBorder, tween(180), label = "rb")
    val bg by animateColorAsState(if (selected) c.infoBg else c.surface, tween(180), label = "rbg")
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(bg).border(2.dp, border, shape)
            .pressable(onClick = onClick, role = Role.RadioButton).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, c.ink, CircleShape), contentAlignment = Alignment.Center) {
            val dot by animateDpAsState(if (selected) 11.dp else 0.dp, label = "dot")
            Box(Modifier.size(dot).clip(CircleShape).background(c.ink))
        }
        Column(Modifier.weight(1f)) {
            T(title, 16.sp, FontWeight.Bold)
            if (subtitle != null) T(subtitle, 13.sp, color = c.muted)
        }
        if (note != null) T(note, 13.sp, color = c.muted)
    }
}

/** Pill tab switcher (দৈনিক / সাপ্তাহিক / মাসিক / বার্ষিক …) with a sliding indicator. */
@Composable
fun SegmentedTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Reso.colors
    BoxWithConstraints(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.segmentBg).padding(4.dp),
    ) {
        val w = maxWidth / items.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "seg")
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }.width(w).height(44.dp).clip(RoundedCornerShape(11.dp)).background(c.ink))
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, label ->
                val fg by animateColorAsState(if (i == selected) c.onInk else c.text, tween(200), label = "segfg")
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(11.dp))
                        .clickable(role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { T(label, 15.sp, FontWeight.Bold, fg, maxLines = 1) }
            }
        }
    }
}

/** Horizontally scrolling filter pills (সব / যাচাইকৃত / অপেক্ষমাণ …). */
@Composable
fun FilterPills(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Reso.colors
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, label ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) c.ink else c.surface, tween(180), label = "pill")
            val shape = RoundedCornerShape(24.dp)
            Box(
                Modifier.heightIn(min = 44.dp).clip(shape).background(bg).border(1.5.dp, c.ink, shape)
                    .clickable(role = Role.Tab) { onSelect(i) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { T(label, 15.sp, FontWeight.Bold, if (sel) c.onInk else c.text, maxLines = 1) }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Field(null, value, onChange, placeholder = placeholder, ime = ImeAction.Search, maxLength = 60,
        trailing = { androidx.compose.material3.Icon(ResoIcons.Search, null, tint = Reso.colors.muted, modifier = Modifier.size(20.dp)) })
}

// ---------------------------------------------------------------- display

@Composable
fun Tag(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, size: TextUnit = 12.sp) {
    Box(modifier.background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        T(text, size, FontWeight.Bold, fg, maxLines = 1)
    }
}

/** Order status label in the design colors. */
@Composable
fun StatusText(status: String, size: TextUnit = 12.sp) {
    val c = Reso.colors
    val (text, color) = when (status) {
        "confirmed" -> t("নিশ্চিত", "Confirmed") to c.green
        "pending" -> t("যাচাই চলছে", "Verifying") to c.brown
        "cancelled" -> t("বাতিল", "Cancelled") to c.red
        else -> status to c.muted
    }
    T(text, size, FontWeight.Bold, color)
}

@Composable
fun KycText(status: String, accountStatus: String = "active", size: TextUnit = 13.sp) {
    val c = Reso.colors
    val (text, color) = when {
        accountStatus == "suspended" -> t("স্থগিত", "Suspended") to c.red
        status == "verified" -> t("যাচাইকৃত", "Verified") to c.green
        status == "rejected" -> t("বাতিল", "Rejected") to c.red
        else -> t("অপেক্ষমাণ", "Pending") to c.brown
    }
    T(text, size, FontWeight.Bold, color)
}

fun methodLabel(m: String) = if (m == "cash") t("ক্যাশ", "Cash") else t("ব্যাংক ট্রান্সফার", "Bank transfer")
fun methodShort(m: String) = if (m == "cash") t("ক্যাশ", "Cash") else t("ব্যাংক", "Bank")

/** Status as a soft pill (v2 design): নিশ্চিত green, যাচাই চলছে amber, বাতিল red. */
@Composable
fun StatusPill(status: String) {
    val c = Reso.colors
    val (text, bg, fg) = when (status) {
        "confirmed", "verified" -> Triple(if (status == "verified") t("যাচাইকৃত", "Verified") else t("নিশ্চিত", "Confirmed"), c.successBg, c.green)
        "pending" -> Triple(t("যাচাই চলছে", "Verifying"), c.warnBg, c.brown)
        "kyc_pending" -> Triple(t("অপেক্ষমাণ", "Pending"), c.warnBg, c.brown)
        "suspended" -> Triple(t("স্থগিত", "Suspended"), c.dangerBg, c.red)
        "cancelled", "rejected" -> Triple(if (status == "rejected") t("বাতিল", "Rejected") else t("বাতিল", "Cancelled"), c.dangerBg, c.red)
        else -> Triple(status, c.tagBlueBg, c.tagBlueText)
    }
    Tag(text, bg, fg)
}

@Composable
fun ProgressTrack(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp, color: Color = Reso.colors.ink) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "track")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Reso.colors.track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(height / 2)).background(color))
    }
}

/** Two-part split bar (nominee shares). */
@Composable
fun SplitBar(first: Float, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val f by animateFloatAsState(first.coerceIn(0f, 1f), tween(500), label = "split")
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Reso.colors.track)) {
        if (f > 0f) Box(Modifier.weight(f).fillMaxHeight().background(Reso.colors.ink))
        if (f < 1f) Box(Modifier.weight(1f - f).fillMaxHeight().background(Brand.Amber))
    }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(title, 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) LinkText(action, onAction)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueWeight: FontWeight = FontWeight.Bold, valueColor: Color = Reso.colors.text, size: TextUnit = 15.sp) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        T(key, size, color = Reso.colors.muted, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        T(value, size, valueWeight, valueColor, align = TextAlign.End, modifier = Modifier.weight(1.4f))
    }
}

enum class Tone { INFO, CASH, WARN, DANGER, SUCCESS }

@Composable
fun InfoBox(text: String, modifier: Modifier = Modifier, title: String? = null, icon: ImageVector? = ResoIcons.Info, tone: Tone = Tone.INFO) {
    val c = Reso.colors
    val (bg, border) = when (tone) {
        Tone.INFO -> c.infoBg to c.infoBorder
        Tone.CASH, Tone.WARN -> c.cashBg to c.cashBorder
        Tone.DANGER -> c.dangerBg to c.red.copy(alpha = 0.4f)
        Tone.SUCCESS -> c.successBg to c.green.copy(alpha = 0.4f)
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, border, shape).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = c.text, modifier = Modifier.size(22.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) T(title, 15.sp, FontWeight.Bold)
            T(text, 14.sp, color = c.textSoft, lineHeight = 22.sp)
        }
    }
}

@Composable
fun MenuRow(icon: ImageVector, title: String, subtitle: String? = null, badge: String? = null, onClick: () -> Unit, tint: Color = Reso.colors.text) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            T(title, 17.sp, FontWeight.Bold, tint)
            if (subtitle != null) T(subtitle, 13.sp, color = Reso.colors.muted)
        }
        if (badge != null) Tag(badge, Brand.Amber, Brand.Navy)
        Icon(ResoIcons.Chevron, null, tint = Reso.colors.muted, modifier = Modifier.size(18.dp))
    }
}

// ---------------------------------------------------------------- states

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

val <T> Load<T>.dataOrNull: T? get() = (this as? Load.Ready<T>)?.data

@Composable
fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Reso.colors.ink, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    }
}

@Composable
fun ErrorBlock(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconDisc(ResoIcons.Info, Reso.colors.dangerBg, Reso.colors.red, 56.dp, 28.dp)
        T(message, 15.sp, color = Reso.colors.textSoft, align = TextAlign.Center)
        if (onRetry != null) SecondaryButton(t("আবার চেষ্টা করুন", "Try again"), onRetry, Modifier.widthIn(max = 260.dp), height = 48.dp, icon = ResoIcons.Refresh)
    }
}

@Composable
fun EmptyBlock(message: String, icon: ImageVector = ResoIcons.Document) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconDisc(icon, Reso.colors.tagBlueBg, Reso.colors.tagBlueText, 56.dp, 26.dp)
        T(message, 15.sp, color = Reso.colors.muted, align = TextAlign.Center)
    }
}

/** Standard pull-to-refresh wrapper. */
@Composable
fun Refreshable(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) { content() }
}

/** Smoothly counts a Long value (money/shares) from its previous value to the new one. */
@Composable
fun animatedLong(target: Long, durationMs: Int = 900): Long {
    val start = remember { mutableLongStateOf(target) }
    val anim = remember { Animatable(1f) }
    var shown by remember { mutableLongStateOf(target) }
    LaunchedEffect(target) {
        if (shown == target) return@LaunchedEffect
        start.longValue = shown
        anim.snapTo(0f)
        anim.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) {
            shown = start.longValue + ((target - start.longValue) * value.toDouble()).toLong()
        }
        shown = target
    }
    return shown
}

// ---------------------------------------------------------------- dialogs

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    val c = Reso.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { T(title, 19.sp, FontWeight.Bold) },
        text = { T(text, 15.sp, color = c.textSoft, lineHeight = 23.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) { T(confirm, 16.sp, FontWeight.Bold, if (danger) c.red else c.ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { T(t("বাতিল", "Cancel"), 16.sp, color = c.muted) } },
    )
}

/** Dialog asking for a short reason (reject / suspend). */
@Composable
fun ReasonDialog(title: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit, placeholder: String = t("কারণ লিখুন", "Write the reason")) {
    val c = Reso.colors
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        properties = DialogProperties(usePlatformDefaultWidth = true),
        title = { T(title, 19.sp, FontWeight.Bold) },
        text = {
            Field(null, reason, { reason = it }, placeholder = placeholder, singleLine = false, minHeight = 96.dp, maxLength = 300, ime = ImeAction.Default)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.trim()) }, enabled = reason.trim().length >= 3) {
                T(confirm, 16.sp, FontWeight.Bold, if (reason.trim().length >= 3) c.red else c.muted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { T(t("বাতিল", "Cancel"), 16.sp, color = c.muted) } },
    )
}

/** Shows a secret exactly once (temporary passwords). */
@Composable
fun SecretDialog(title: String, secret: String, note: String, onDismiss: () -> Unit) {
    val c = Reso.colors
    AlertDialog(
        onDismissRequest = {},
        containerColor = c.surface,
        title = { T(title, 19.sp, FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.infoBg).padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(secret, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text, letterSpacing = 1.sp))
                }
                T(note, 14.sp, color = c.textSoft)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { T(t("লিখে রেখেছি", "I have noted it"), 16.sp, FontWeight.Bold, c.ink) } },
    )
}

// ---------------------------------------------------------------- bottom navigation

data class NavItem(val route: String, val label: String, val icon: ImageVector, val badge: Int = 0)

/**
 * v2 bottom bar. The selected item gets a soft pill behind its icon (amber for the shareholder
 * portal, navy for admin). With [fab], a raised round "+" button sits in the middle.
 */
@Composable
fun BottomBar(
    items: List<NavItem>,
    selected: String?,
    onSelect: (String) -> Unit,
    adminStyle: Boolean = false,
    fab: NavItem? = null,
    onFab: () -> Unit = {},
) {
    val c = Reso.colors
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(top = if (fab != null) 14.dp else 0.dp).background(c.surface)) {
            RowDivider()
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp)) {
                val half = items.size / 2
                items.forEachIndexed { i, item ->
                    if (fab != null && i == half) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(40.dp))
                            T(fab.label, 13.sp, FontWeight.Bold, if (fab.route == selected) c.ink else c.text, maxLines = 1)
                        }
                    }
                    BarItem(item, item.route == selected, adminStyle, Modifier.weight(1f)) { onSelect(item.route) }
                }
            }
        }
        if (fab != null) {
            Box(
                Modifier.align(Alignment.TopCenter).size(58.dp).clip(CircleShape).background(c.surface).padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(Brand.Amber).pressable(onClick = onFab)
                        .semantics { contentDescription = fab.label },
                    contentAlignment = Alignment.Center,
                ) { Icon(ResoIcons.Plus, null, tint = Brand.Navy, modifier = Modifier.size(28.dp)) }
            }
        }
    }
}

@Composable
private fun BarItem(item: NavItem, sel: Boolean, adminStyle: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Reso.colors
    val pillBg by animateColorAsState(
        when {
            !sel -> Color.Transparent
            adminStyle -> c.ink
            else -> c.warnBg
        }, tween(220), label = "pill",
    )
    val pop = remember { Animatable(1f) }
    var firstSel by remember { mutableStateOf(true) }
    LaunchedEffect(sel) {
        if (firstSel) { firstSel = false; return@LaunchedEffect }
        if (sel) { pop.snapTo(0.78f); pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f)) }
    }
    val iconColor = when {
        sel && adminStyle -> c.onInk
        sel -> c.ink
        else -> c.muted
    }
    Column(
        modifier.heightIn(min = 54.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Tab, onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Box {
            Box(
                Modifier
                    .drawBehind { drawRoundRect(pillBg, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Icon(item.icon, item.label, tint = iconColor, modifier = Modifier.size(24.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
            }
            if (item.badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-4).dp).size(18.dp).clip(CircleShape).background(Brand.Amber),
                    contentAlignment = Alignment.Center,
                ) { T(if (item.badge > 9) Bn.digits("9+") else Bn.num(item.badge), 10.sp, FontWeight.Bold, Brand.Navy, lineHeight = 12.sp) }
            }
        }
        T(item.label, 13.sp, if (sel) FontWeight.Bold else FontWeight.Normal, if (sel) c.text else c.muted, maxLines = 1)
    }
}

// ---------------------------------------------------------------- brand

@Composable
fun BrandLogo(size: Dp = 44.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val s = this.size.width / 40f
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFFFFC24D), Brand.Amber), end = androidx.compose.ui.geometry.Offset(this.size.width, this.size.height)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(11f * s, 11f * s),
        )
        drawPath(brandWave(s, 0f, 0f), Brand.Navy, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.6f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** The Resonance wave in a 40×40 box (1.5 smooth periods), scaled by [s] and offset by [dx]/[dy]. */
fun brandWave(s: Float, dx: Float, dy: Float) = androidx.compose.ui.graphics.Path().apply {
    fun x(v: Float) = dx + v * s
    fun y(v: Float) = dy + v * s
    moveTo(x(8f), y(20f))
    cubicTo(x(10.88f), y(13.35f), x(13.12f), y(13.35f), x(16f), y(20f))
    cubicTo(x(18.88f), y(26.65f), x(21.12f), y(26.65f), x(24f), y(20f))
    cubicTo(x(26.88f), y(13.35f), x(29.12f), y(13.35f), x(32f), y(20f))
}

@Composable
fun DemoBadge(modifier: Modifier = Modifier) {
    Tag(t("ডেমো সংস্করণ · সব তথ্য নমুনা", "Demo version · sample data"), Brand.Amber, Brand.Navy, modifier, 12.sp)
}

/** Shape helper for top-rounded sheets (login card). */
val SheetShape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
