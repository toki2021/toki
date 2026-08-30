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

private fun fmt(amountYuan: Double): String {
    val negative = amountYuan < -0.005
    val abs = kotlin.math.abs(amountYuan)
    val s = String.format("%,.2f", abs)
    return (if (negative) "-¥" else "¥") + s
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
    val value = (cents / 100.0) * progress.value
    Text(
        text = fmt(value),
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
    Text(
        text = fmt(cents / 100.0),
        modifier = modifier,
        style = style.copy(fontFeatureSettings = "tnum"),
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
    )
}
