package com.zhuanz.autoleger.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.autoleger.data.TYPE_REFUND
import java.util.Locale
import androidx.compose.material3.ExperimentalMaterial3Api

/** 统计页：每日消费趋势（30 天柱状）+ 本月分类占比环形图 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: HomeViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val monthTx = state.summary.transactions.filter { it.type != TYPE_REFUND }
    val daily = remember(monthTx, state.month) {
        val sums = monthTx.groupBy { transactionDay(it) }.mapValues { (_, tx) -> tx.sumOf { it.amountCents } }
        (1..state.month.lengthOfMonth()).map { day ->
            val date = state.month.atDay(day)
            DayBar(date.toString(), sums[date] ?: 0)
        }
    }
    val categories = state.categories.associateBy { it.id }
    val categorySums = monthTx.groupBy { categories[it.categoryId]?.name ?: "未分类" }
        .map { (name, tx) -> CategorySum(name, tx.sumOf { it.amountCents }) }.sortedByDescending { it.amountCents }
    RefreshLedgerDate(vm)
    Column(Modifier.fillMaxSize()) {
        MonthBar(state.month, vm::selectMonth)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { if (!state.loading) SummaryCard(state.summary, Modifier.padding(16.dp), month = state.month) }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp)) }
            else if (monthTx.isEmpty()) item { LedgerEmptyState("这个月还没有支出", "账单确认后，趋势和分类统计会在这里显示") }
            else {
                item {
                    ChartCard("每日支出") {
                        TrendBarChart(daily, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(180.dp))
                        Text("点击查看当天金额", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        var showDaily by remember { mutableStateOf(false) }
                        TextButton(onClick = { showDaily = !showDaily }) { Text(if (showDaily) "收起每日明细" else "查看每日明细") }
                        if (showDaily) daily.forEach { d ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(d.day.takeLast(5)); AmountText(d.cents)
                            }
                        }
                    }
                }
                item {
                    ChartCard("支出花在哪里") {
                        CategoryDonut(categorySums, Modifier.fillMaxWidth().height(190.dp))
                        Spacer(Modifier.height(16.dp))
                        val palette = categoryPalette()
                        categorySums.forEachIndexed { i, cs ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                Text(cs.name, style = MaterialTheme.typography.titleSmall)
                                Text("${"%.1f".format(cs.amountCents * 100.0 / state.summary.expense)}% · ${formatCents(cs.amountCents)}",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(progress = { cs.amountCents.toFloat() / state.summary.expense },
                                    modifier = Modifier.fillMaxWidth(), color = palette[i % palette.size])
                            }
                        }
                    }
                }
            }
            if (!state.loading) item {
                ChartCard("长期概览") {
                    Text("今年支出", style = MaterialTheme.typography.labelMedium)
                    AmountText(state.yearExpense, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("累计支出", style = MaterialTheme.typography.labelMedium)
                    AmountText(state.allExpense, style = MaterialTheme.typography.titleLarge)
                }
            }

        }
    }
}

data class DayBar(val day: String, val cents: Long)
data class CategorySum(val name: String, val amountCents: Long)

/** Shared quiet card surface for the warm theme. */
@Composable
fun Modifier.cardSurface(shape: Shape = RoundedCornerShape(16.dp)): Modifier {
    val c = MaterialTheme.colorScheme
    return this
        .background(c.surface, shape)
        .border(1.dp, c.outlineVariant.copy(alpha = 0.45f), shape)
}

/** 金额分 -> "¥1,234.56"，用于统计卡与柱状图标签 */
@Composable
fun formatCents(cents: Long): String {
    val fmt = remember { java.text.DecimalFormat("#,##0.00") }
    return "¥${fmt.format(java.math.BigDecimal.valueOf(cents, 2))}"
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .cardSurface()
            .padding(18.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** Tap a bar for its date and amount; textual detail is available below. */
@Composable
fun TrendBarChart(daily: List<DayBar>, color: Color, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(daily) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
    }
    var selected by remember(daily) { mutableStateOf(-1) }

    val maxCents = (daily.maxOfOrNull { it.cents } ?: 0L).coerceAtLeast(1)
    val labelColorArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val tooltipColorArgb = MaterialTheme.colorScheme.onPrimary.toArgb()

    Canvas(
        modifier
            .pointerInput(daily) {
                fun hitIndex(x: Float): Int {
                    val n = daily.size
                    if (n == 0) return -1
                    val gap = (size.width / daily.size.coerceAtLeast(1)) * 0.28f
                    val barW = (this.size.width - gap * (n - 1)) / n
                    return (x / (barW + gap)).toInt().coerceIn(0, n - 1)
                }
                detectTapGestures { position -> selected = hitIndex(position.x) }

            },
    ) {
        val n = daily.size
        if (n == 0) return@Canvas
        val gap = (size.width / daily.size.coerceAtLeast(1)) * 0.28f
        val barW = (size.width - gap * (n - 1)) / n
        val baseY = size.height - 24.dp.toPx()
        daily.forEachIndexed { i, d ->
            val h = (d.cents.toFloat() / maxCents) * (size.height - 70.dp.toPx()).coerceAtLeast(0f) * progress.value
            drawRoundRect(
                color = if (i == selected) color
                else color.copy(alpha = 0.35f + 0.4f * (d.cents.toFloat() / maxCents)),
                topLeft = Offset(i * (barW + gap), baseY - h),
                size = Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 3),
            )
        }

        // 选中柱：顶部金额标签 + 引导线
        if (selected in daily.indices) {
            val d = daily[selected]
            val topY = baseY - (d.cents.toFloat() / maxCents) * (size.height - 70.dp.toPx()).coerceAtLeast(0f) * progress.value
            val cx = selected * (barW + gap) + barW / 2
            val text = d.day.takeLast(5) + " · ¥${"%,.2f".format(Locale.US, d.cents / 100.0)}"
            val labelY = 10.dp.toPx()
            val paintTx = android.graphics.Paint().apply {
                this.color = tooltipColorArgb
                textSize = 11.sp.toPx()
                isAntiAlias = true
                isFakeBoldText = true
            }
            val textW = paintTx.measureText(text)
            val pad = 8.dp.toPx()
            val labelW = textW + pad * 2
            val left = (cx - labelW / 2).coerceIn(0f, (size.width - labelW).coerceAtLeast(0f))
            // 引导线：标签底部到柱顶
            val lineTop = labelY + paintTx.textSize + 4.dp.toPx()
            drawLine(
                color = color,
                start = Offset(cx, lineTop),
                end = Offset(cx, (topY + 2.dp.toPx()).coerceAtLeast(lineTop + 1.dp.toPx())),
                strokeWidth = 1.5.dp.toPx(),
            )
            // 标签背景
            drawRoundRect(
                color = color,
                topLeft = Offset(left, labelY),
                size = Size(labelW, paintTx.textSize + 8.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            )
            drawContext.canvas.nativeCanvas.drawText(
                text,
                left + (labelW - textW) / 2,
                labelY + paintTx.textSize + 4.dp.toPx(),
                paintTx,
            )
            // 柱顶金额已选中，文字颜色覆盖白色可直接读
        }

        // 日期标签：只画 1 号、15 号、今天附近
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                this.color = labelColorArgb
                textSize = 10.sp.toPx()
                isAntiAlias = true
            }
            daily.forEachIndexed { i, d ->
                val dayNum = d.day.substringAfterLast('-').toIntOrNull() ?: return@forEachIndexed
                if (dayNum == 1 || dayNum == 15 || i == n - 1) {
                    drawContext.canvas.nativeCanvas.drawText(
                        dayNum.toString(),
                        i * (barW + gap) + barW / 2 - paint.measureText(dayNum.toString()) / 2,
                        size.height,
                        paint,
                    )
                }
            }
        }
    }
}

/** 分类占比环形图（Canvas drawArc + 生长动画） */
@Composable
fun CategoryDonut(data: List<CategorySum>, modifier: Modifier = Modifier) {
    val palette = categoryPalette()
    val total = data.sumOf { it.amountCents }
    val progress = remember { Animatable(0f) }
    val labelColorArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val amountColorArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val monthLabel = "支出分类"
    LaunchedEffect(data) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val stroke = 26.dp.toPx()
        val diameter = minOf(size.width, size.height) - stroke
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        var startAngle = -90f
        data.forEachIndexed { i, cs ->
            val sweep = (cs.amountCents.toFloat() / total.coerceAtLeast(1)) * 360f * progress.value
            drawArc(
                color = palette[i % palette.size],
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = stroke),
            )
            startAngle += sweep
        }
        // 中心总额
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = labelColorArgb
                textSize = 12.sp.toPx()
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            drawText(monthLabel, size.width / 2, size.height / 2 - 6.dp.toPx(), paint)
            paint.color = amountColorArgb
            paint.textSize = 16.sp.toPx()
            paint.isFakeBoldText = true
            drawText(
                "${data.size}个分类",
                size.width / 2,
                size.height / 2 + 16.dp.toPx(),
                paint,
            )
        }
    }
}
