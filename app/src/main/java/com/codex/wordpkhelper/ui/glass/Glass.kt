package com.codex.wordpkhelper.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codex.wordpkhelper.ui.theme.GlassColors
import com.codex.wordpkhelper.ui.theme.GlassDimens
import com.codex.wordpkhelper.ui.theme.GlassType
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * 当前页面可用来折射的背景层。
 *
 * 由根布局通过 `rememberLayerBackdrop` 建立；悬浮窗里没有这个层，
 * [GlassSurface] 会自动退化成手绘玻璃（半透明底 + 高光描边），保证同一套组件两处都能用。
 */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
fun ProvideGlassBackdrop(backdrop: LayerBackdrop, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop, content = content)
}

/** 去掉水波纹的点击：玻璃组件自己用缩放做按压反馈。 */
fun Modifier.glassClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    onClick = onClick,
)

/**
 * 液态玻璃表面。
 *
 * 有 backdrop 时用 `drawBackdrop` 做真实折射（vibrancy + blur + lens）；
 * 没有时退化成半透明底 + 上亮下暗的高光描边。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassDimens.CardRadius),
    tint: Color = GlassColors.GlassTint,
    refraction: Dp = 18.dp,
    blurRadius: Dp = 6.dp,
    rim: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val backdrop = LocalGlassBackdrop.current
    val decorated = if (backdrop != null) {
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                if (blurRadius > 0.dp) blur(blurRadius.toPx())
                if (refraction > 0.dp) lens(refraction.toPx(), refraction.toPx() * 1.8f)
            },
            highlight = if (rim) ({ Highlight.Default }) else null,
            shadow = { Shadow.Default },
            onDrawSurface = { drawRect(tint) },
        )
    } else {
        modifier
            .shadow(16.dp, shape, clip = false)
            .clip(shape)
            .background(tint, shape)
            .border(
                BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(GlassColors.GlassStrokeTop, GlassColors.GlassStrokeBottom)
                    ),
                ),
                shape,
            )
    }
    Box(decorated, content = content)
}

/** 永远走手绘分支的玻璃表面：列表里成百上千行时用它，避免每行挂一个折射节点。 */
@Composable
fun GlassFlatSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassDimens.ControlRadius),
    tint: Color = GlassColors.GlassTint,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier
            .clip(shape)
            .background(tint, shape)
            .border(
                BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(GlassColors.GlassStrokeTop, GlassColors.GlassStrokeBottom)
                    ),
                ),
                shape,
            ),
        content = content,
    )
}

@Composable
fun GlassText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = GlassType.Body,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(textAlign = align),
        overflow = overflow,
        maxLines = maxLines,
    )
}

/** 主按钮：玻璃底 + 轻微缩放反馈。 */
@Composable
fun GlassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    accent: Color = GlassColors.Accent,
    shape: Shape = RoundedCornerShape(GlassDimens.ControlRadius),
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, label = "buttonScale")
    GlassSurface(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glassClickable(interaction, enabled, onClick),
        shape = shape,
        tint = if (primary) accent.copy(alpha = 0.42f) else GlassColors.GlassTintStrong,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlassText(
                text = label,
                style = GlassType.Headline.copy(
                    color = if (enabled) GlassColors.TextPrimary else GlassColors.TextDisabled
                ),
                align = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** 自定义开关，避免为了一个 Switch 引入 material3。 */
@Composable
fun GlassToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val knob by animateFloatAsState(if (checked) 1f else 0f, label = "toggleKnob")
    Box(
        modifier = modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(CircleShape)
            .background(
                if (checked) GlassColors.Accent.copy(alpha = 0.78f)
                else Color.White.copy(alpha = 0.13f)
            )
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                CircleShape,
            )
            .glassClickable(interaction, enabled) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer { translationX = knob * 20.dp.toPx() }
                .padding(start = 5.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) Color.White.copy(alpha = 0.95f)
                    else Color.White.copy(alpha = 0.45f)
                ),
        )
    }
}

/** 分段选择器：运行模式这类二选一/三选一用它，比一排按钮清晰。 */
@Composable
fun GlassSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(GlassDimens.ControlRadius),
        tint = GlassColors.GlassTint,
        refraction = 14.dp,
        blurRadius = 5.dp,
    ) {
        Row(
            Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(GlassDimens.ChipRadius))
                        .background(
                            if (selected) Color.White.copy(alpha = 0.20f) else Color.Transparent
                        )
                        .glassClickable(interaction) { onSelect(index) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    GlassText(
                        text = option,
                        style = GlassType.Label.copy(
                            color = if (selected) GlassColors.TextPrimary
                            else GlassColors.TextTertiary
                        ),
                        align = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 只用基础手势实现的滑杆。 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val knobPx = with(LocalDensity.current) { 20.dp.toPx() }
    // 手势回调要保持最新，否则每帧新生成的 lambda 会让 pointerInput 不断重启、拖动会断。
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestRange by rememberUpdatedState(valueRange)

    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    val emit: (Float) -> Unit = { x ->
        val usable = widthPx - knobPx
        if (usable > 0f) {
            val f = ((x - knobPx / 2f) / usable).coerceIn(0f, 1f)
            latestOnValueChange(latestRange.start + span * f)
        }
    }

    Box(
        modifier = modifier
            .height(34.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                detectTapGestures { offset -> emit(offset.x) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> emit(change.position.x) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f)),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(listOf(GlassColors.Cyan, GlassColors.Accent))
                ),
        )
        Box(
            Modifier
                .graphicsLayer { translationX = fraction * (widthPx - knobPx) }
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.95f))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)), CircleShape),
        )
    }
}

/** 设置页的一行：左边标题/说明，右边控件。 */
@Composable
fun GlassRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GlassText(title, style = GlassType.Headline)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                GlassText(subtitle, style = GlassType.Caption)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(14.dp))
            trailing()
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(GlassDimens.CardPadding),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    GlassText(
        text = text.uppercase(),
        modifier = modifier.padding(start = 6.dp, bottom = 8.dp),
        style = GlassType.Caption,
    )
}

@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GlassColors.Hairline)
    )
}

/** 状态圆点：运行中亮起，空闲时压暗。 */
@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier, color: Color = GlassColors.Success) {
    val alpha by animateFloatAsState(if (active) 1f else 0.35f, label = "dotAlpha")
    Box(
        modifier
            .size(9.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(if (active) color else GlassColors.TextTertiary),
    )
}

/** 导航图标：不引入 material-icons，直接画几何形状。 */
enum class NavGlyph { Home, Vocab, Settings }

@Composable
fun Glyph(glyph: NavGlyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
        when (glyph) {
            NavGlyph.Home -> {
                val roof = Path().apply {
                    moveTo(w * 0.14f, h * 0.46f)
                    lineTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.86f, h * 0.46f)
                }
                drawPath(roof, color, style = stroke)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.24f, h * 0.44f),
                    size = Size(w * 0.52f, h * 0.40f),
                    cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
                    style = stroke,
                )
            }

            NavGlyph.Vocab -> {
                for (i in 0 until 3) {
                    val y = h * (0.30f + i * 0.20f)
                    drawLine(
                        color = color,
                        start = Offset(w * 0.20f, y),
                        end = Offset(w * 0.80f, y),
                        strokeWidth = w * 0.11f,
                        cap = StrokeCap.Round,
                    )
                }
            }

            NavGlyph.Settings -> {
                drawCircle(
                    color = color,
                    radius = w * 0.33f,
                    center = Offset(w / 2f, h / 2f),
                    style = stroke,
                )
                drawCircle(
                    color = color,
                    radius = w * 0.10f,
                    center = Offset(w / 2f, h / 2f),
                )
            }
        }
    }
}
