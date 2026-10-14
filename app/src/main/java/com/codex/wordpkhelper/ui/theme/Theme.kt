package com.codex.wordpkhelper.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 深色极光配色。玻璃要好看，前提是背后有东西可折射 —— 这些颜色就是被折射的内容。
 */
object GlassColors {
    val Deep = Color(0xFF070B14)
    val DeepAlt = Color(0xFF0C1424)
    val Ink = Color(0xFF04070D)

    val Blue = Color(0xFF3B82F6)
    val Violet = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)
    val Pink = Color(0xFFF472B6)
    val Mint = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Rose = Color(0xFFFB7185)

    val TextPrimary = Color(0xFFF2F6FF)
    val TextSecondary = Color(0xB3F2F6FF)
    val TextTertiary = Color(0x80F2F6FF)
    val TextDisabled = Color(0x4DF2F6FF)

    /** 玻璃表面底色。 */
    val GlassTint = Color(0x1AFFFFFF)
    val GlassTintStrong = Color(0x2BFFFFFF)

    /** 玻璃边缘的高光渐变：上亮下暗，这是「有厚度」的关键。 */
    val GlassStrokeTop = Color(0x80FFFFFF)
    val GlassStrokeBottom = Color(0x0FFFFFFF)

    val Hairline = Color(0x1FFFFFFF)
    val Scrim = Color(0x66000000)

    /** 按用量把强调色排好，页面里按语义取。 */
    val Accent = Blue
    val Success = Mint
    val Warning = Amber
    val Danger = Rose
}

/** 字号阶梯。只保留这六档，避免页面各写各的。 */
object GlassType {
    val Display = TextStyle(
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        color = GlassColors.TextPrimary,
        letterSpacing = (-0.6).sp,
    )
    val Title = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        color = GlassColors.TextPrimary,
        letterSpacing = (-0.2).sp,
    )
    val Headline = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = GlassColors.TextPrimary,
    )
    val Body = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        color = GlassColors.TextSecondary,
        lineHeight = 22.sp,
    )
    val Label = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = GlassColors.TextSecondary,
    )
    val Caption = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = GlassColors.TextTertiary,
        letterSpacing = 0.7.sp,
    )
    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = GlassColors.TextSecondary,
    )
}

/** 圆角与间距阶梯。 */
object GlassDimens {
    val ScreenPadding = 18.dp
    val BarRadius = 30.dp
    val CardRadius = 28.dp
    val ControlRadius = 20.dp
    val ChipRadius = 14.dp
    val CardPadding = 18.dp
    val ItemGap = 12.dp
    val SectionGap = 22.dp
    /** 底部玻璃导航栏占掉的高度，页面内容要留出这么多，否则最后一项会被挡住。 */
    val BottomBarSpace = 108.dp
    /** 顶部玻璃栏本身的高度；状态栏高度由内容容器的 statusBarsPadding 另外处理。 */
    val TopBarSpace = 96.dp
}

/**
 * 极光背景：几个大半径径向渐变缓慢漂移。
 *
 * 作为 Composable 而不是纯绘制函数，是为了让无限动画本身参与重组 ——
 * `layerBackdrop` 只在子树重绘时重新录制，如果动画只发生在一个普通 lambda 里，
 * 录制层不会失效，背景会僵在第一帧。
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "auroraPhase",
    )
    Canvas(modifier.fillMaxSize()) { drawAurora(phase) }
}

/** 实际的光斑绘制，供 [AuroraBackground] 与测试直接调用。 */
fun DrawScope.drawAurora(phase: Float) {
    drawRect(
        Brush.verticalGradient(
            listOf(GlassColors.Deep, GlassColors.DeepAlt, GlassColors.Ink)
        )
    )
    val t = phase * 2f * PI.toFloat()
    val base = minOf(size.width, size.height)

    blob(
        Offset(size.width * (0.20f + 0.09f * sin(t)),
            size.height * (0.16f + 0.06f * cos(t))),
        base * 0.74f, GlassColors.Blue, 0.42f,
    )
    blob(
        Offset(size.width * (0.88f + 0.07f * cos(t * 0.8f)),
            size.height * (0.28f + 0.07f * sin(t * 0.9f))),
        base * 0.64f, GlassColors.Violet, 0.40f,
    )
    blob(
        Offset(size.width * (0.44f + 0.11f * sin(t * 0.7f + 1.6f)),
            size.height * (0.80f + 0.07f * cos(t * 0.6f))),
        base * 0.82f, GlassColors.Cyan, 0.24f,
    )
    blob(
        Offset(size.width * (0.94f + 0.06f * sin(t * 1.1f + 2.4f)),
            size.height * (0.88f + 0.05f * cos(t * 1.2f))),
        base * 0.56f, GlassColors.Pink, 0.20f,
    )
    // 底部压暗，保证玻璃条与导航栏上的文字始终有足够对比度
    drawRect(
        Brush.verticalGradient(
            0.55f to Color.Transparent,
            1f to GlassColors.Ink.copy(alpha = 0.78f),
        )
    )
}

private fun DrawScope.blob(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
