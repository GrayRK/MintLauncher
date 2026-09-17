package mint.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Composable
fun Txt(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Кликабельная область с курсором-рукой и состоянием наведения. */
@Composable
fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return source to hovered
}

fun Modifier.clickableNoRipple(source: MutableInteractionSource, enabled: Boolean = true, onClick: () -> Unit) =
    this.hoverable(source)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)

/** Карточка: фон #FBF7F0, граница 7% ink. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    padding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
    spacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .clip(shape)
            .background(MintColors.Surface)
            .border(1.dp, MintColors.ink(0.07f), shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Основная мятная кнопка («Играть», «Войти»). */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
    radius: Dp = 14.dp,
    textStyle: TextStyle = nunito(16f, color = MintColors.MintInk),
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(radius)
    val bg by animateColorAsState(if (hovered && enabled) MintColors.MintHover else MintColors.Mint)
    Row(
        modifier
            .height(height)
            .shadow(if (enabled) 10.dp else 0.dp, shape, ambientColor = MintColors.MintDeep, spotColor = MintColors.MintDeep.copy(alpha = 0.5f))
            .clip(shape)
            .background(if (enabled) bg else MintColors.Mint.copy(alpha = 0.55f))
            .border(1.dp, MintColors.MintBorder, shape)
            .clickableNoRipple(source, enabled, onClick)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Txt(text, textStyle, maxLines = 1)
    }
}

/** Контурная кнопка («Войти через …», «Обзор»). */
@Composable
fun OutlineButton(
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    radius: Dp = 14.dp,
    textStyle: TextStyle = manrope(13.5f, FontWeight.SemiBold),
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(radius)
    val active = hovered && enabled
    Row(
        modifier
            .height(height)
            .clip(shape)
            .background(if (active) MintColors.Mint.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (active) MintColors.MintFocus.copy(alpha = 0.7f) else MintColors.ink(0.16f), shape)
            .clickableNoRipple(source, enabled, onClick)
            .padding(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Txt(text, if (enabled) textStyle else textStyle.copy(color = MintColors.ink(0.4f)), maxLines = 1)
    }
}

@Composable
fun LinkText(text: String, style: TextStyle = manrope(12f, color = MintColors.MintDeep), onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Txt(
        text,
        if (hovered) style.copy(color = MintColors.LinkHover) else style,
        Modifier.clickableNoRipple(source, onClick = onClick),
    )
}

/** Поле ввода 46dp с мятной подсветкой фокуса. */
@Composable
fun MintTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    enabled: Boolean = true,
    onSubmit: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val textStyle = if (password && !reveal) manrope(15f, color = MintColors.ink(0.78f), letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp))
    else manrope(13.5f)
    Box(
        modifier
            .height(46.dp)
            .then(if (focused) Modifier.border(3.dp, MintColors.Mint.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(0.dp) else Modifier)
            .clip(shape)
            .background(MintColors.Surface)
            .border(1.dp, if (focused) MintColors.MintFocus.copy(alpha = 0.75f) else MintColors.ink(0.12f), shape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, manrope(13.5f, color = MintColors.ink(0.4f)), maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MintColors.MintDeep),
                    visualTransformation = if (password && !reveal) PasswordVisualTransformation('•') else VisualTransformation.None,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .then(if (onSubmit != null) Modifier.onEnter(onSubmit) else Modifier),
                )
            }
            if (password) {
                val (source, _) = rememberHover()
                Box(Modifier.padding(start = 8.dp).clickableNoRipple(source) { reveal = !reveal }) {
                    Icon(if (reveal) MintIcon.EyeSlash else MintIcon.Eye, 16.dp, MintColors.ink(0.4f))
                }
            }
        }
    }
}

fun Modifier.onEnter(action: () -> Unit) = this.onPreviewKeyEvent {
    if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
        action(); true
    } else false
}

@Composable
fun Checkbox(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val (source, _) = rememberHover()
    Row(
        Modifier.clickableNoRipple(source) { onChange(!checked) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(5.dp)
        Box(
            Modifier.size(17.dp).clip(shape)
                .background(if (checked) MintColors.Mint else MintColors.Surface)
                .border(1.dp, if (checked) MintColors.MintBorder else MintColors.ink(0.2f), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(MintIcon.Check, 11.dp, MintColors.MintInk)
        }
        Txt(label, manrope(12f, color = MintColors.ink(0.85f)))
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val (source, _) = rememberHover()
    val offset by animateDpAsState(if (checked) 20.dp else 2.dp)
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.width(44.dp).height(26.dp).clip(shape)
            .background(if (checked) MintColors.Mint else MintColors.ink(0.12f))
            .border(1.dp, if (checked) MintColors.MintBorder else Color.Transparent, shape)
            .clickableNoRipple(source) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.offset(x = offset).size(20.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape).background(MintColors.Knob)
        )
    }
}

/** Сегментный переключатель «Автоматически / Вручную». */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(11.dp)).background(MintColors.ink(0.06f)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val (source, hovered) = rememberHover()
            val active = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (active) MintColors.Mint else if (hovered) MintColors.Surface.copy(alpha = 0.6f) else Color.Transparent)
                    .clickableNoRipple(source) { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(label, manrope(12.5f, if (active) FontWeight.SemiBold else FontWeight.Medium, if (active) MintColors.MintInk else MintColors.ink(0.8f)))
            }
        }
    }
}

/** Логотип «M» в мятном квадрате. */
@Composable
fun Logo(size: Dp, radius: Dp, fontSize: Float) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(radius)).background(MintColors.Mint),
        contentAlignment = Alignment.Center,
    ) { Txt("M", nunito(fontSize, color = MintColors.MintDarker)) }
}

@Composable
fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier, height: Dp = 7.dp) {
    val shape = RoundedCornerShape(height / 2 + 0.5.dp)
    Box(modifier.height(height).clip(shape).background(MintColors.ink(0.08f))) {
        if (fraction != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(shape).background(MintColors.Mint))
        } else {
            IndeterminateStripe(Modifier.fillMaxHeight().fillMaxWidth(), shape)
        }
    }
}

@Composable
private fun IndeterminateStripe(modifier: Modifier, shape: RoundedCornerShape) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition()
    val x by transition.animateFloat(
        initialValue = -0.35f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200)),
    )
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        Box(Modifier.offset(x = maxWidth * x).width(maxWidth * 0.35f).fillMaxHeight().clip(shape).background(MintColors.Mint))
    }
}

@Composable
fun RowScope.Spacer() = androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

@Composable
fun ColumnScope.Spacer() = androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
