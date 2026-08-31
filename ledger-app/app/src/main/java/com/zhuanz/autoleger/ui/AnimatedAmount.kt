package com.zhuanz.autoleger.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import java.math.RoundingMode
import java.text.DecimalFormat
import kotlin.math.abs

/**
 * 复用同一 [DecimalFormat] 实例，避免金额滚动动画期间每帧
 * 都重新解析格式串、查询默认 Locale（String.format 的固有开销）。
 * HALF_UP 与 String.format("%.2f") 的默认舍入保持一致。
 */
@Composable
private fun rememberAmountFormat(): DecimalFormat =
    remember { (DecimalFormat("#,##0.00")).apply { roundingMode = RoundingMode.HALF_UP } }

private fun formatYuan(formatter: DecimalFormat, amountYuan: Double): String {
    val negative = amountYuan < -0.005
    val abs = abs(amountYuan)
    return (if (negative) "-¥" else "¥") + formatter.format(abs)
}

/**
 * 金额数字滚动动画文本（等宽数字，避免跳动）
 */
@Composable
fun AnimatedAmountText(
    cents: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayMedium,
    color: Color = Color.Unspecified,
    animate: Boolean = true,
    align: TextAlign? = null,
) {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(cents) {
        if (animate) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    val formatter = rememberAmountFormat()
    val value = (cents / 100.0) * progress.value
    Text(
        text = formatYuan(formatter, value),
        modifier = modifier,
        style = style.copy(fontFeatureSettings = "tnum"),
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        textAlign = align,
    )
}

/** 静态金额文本（等宽数字） */
@Composable
fun AmountText(
    cents: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    val formatter = rememberAmountFormat()
    Text(
        text = formatYuan(formatter, cents / 100.0),
        modifier = modifier,
        style = style.copy(fontFeatureSettings = "tnum"),
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
    )
}