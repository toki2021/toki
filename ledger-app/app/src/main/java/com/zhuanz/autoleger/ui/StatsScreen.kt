package com.zhuanz.autoleger.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import com.zhuanz.autoleger.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Alignment.Companion.CenterVertically
import com.zhuanz.autoleger.data.TransactionEntity
import com.zhuanz.autoleger.data.TYPE_REFUND
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.material3.ExperimentalMaterial3Api

/** 统计页：每日消费趋势（30 天柱状）+ 本月分类占比环形图 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val container = rememberContainer()
    val transactions by container.transactionDao.observeAll().collectAsState(initial = emptyList())
    val categories by container.categoryDao.observeAll().collectAsState(initial = emptyList())
    val uncategorizedLabel = stringResource(R.string.category_uncategorized)

    val cal = Calendar.getInstance()
    val monthStart = cal.apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yearStart = (cal.clone() as Calendar).apply {
        set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val expenseTx = transactions.filter { it.type != TYPE_REFUND }
    val monthTotal = expenseTx.filter { it.time >= monthStart }.sumOf { it.amountCents }
    val yearTotal = expenseTx.filter { it.time >= yearStart }.sumOf { it.amountCents }
    val allTotal = expenseTx.sumOf { it.amountCents }

    // 最近 30 天每日支出
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val daily = remember(transactions) {
        val cal2 = Calendar.getInstance()
        val map = transactions.filter { it.type != TYPE_REFUND }
            .groupBy { dayFmt.format(Date(it.time)) }
            .mapValues { it.value.sumOf { t -> t.amountCents } }
        (0 until 30).map { back ->
            val c = (cal2.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -back) }
            DayBar(dayFmt.format(c.time), map[dayFmt.format(c.time)] ?: 0L)
        }.asReversed()
    }

    // 本月分类支出
    val monthTx = expenseTx.filter { it.time >= monthStart }
    val categorySums = remember(monthTx, categories) {
        monthTx.groupBy { it.categoryId }
            .map { (catId, items) ->
                val cat = categories.firstOrNull { it.id == catId }
                CategorySum(cat?.name ?: uncategorizedLabel, items.sumOf { it.amountCents })
            }
            .sortedByDescending { it.amountCents }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title), fontWeight = FontWeight.Bold) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                OverviewCard(
                    monthCents = monthTotal,
                    yearCents = yearTotal,
                    totalCents = allTotal,
                )
                Spacer(Modifier.height(12.dp))
            }

            // —— 每日趋势（30 天柱状图，Canvas 自绘 + 生长动画）——
            item {
                ChartCard(title = stringResource(R.string.stats_daily_trend)) {
                    TrendBarChart(
                        daily = daily,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                }
            }

            // —— 分类占比环形图 ——
            item {
                ChartCard(title = stringResource(R.string.stats_category_share)) {
                    if (categorySums.isEmpty()) {
                        Text(
                            stringResource(R.string.stats_month_empty),
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        CategoryDonut(
                            data = categorySums,
                            modifier = Modifier.fillMaxWidth().height(170.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                        val palette = categoryPalette()
                        val total = categorySums.sumOf { it.amountCents }.coerceAtLeast(1)
                        categorySums.forEachIndexed { i, cs ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = CenterVertically,
                            ) {
                                Box(Modifier.size(10.dp).background(palette[i % palette.size], CircleShape))
                                Spacer(Modifier.width(10.dp))
                                Text(cs.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${"%1.0f".format(cs.amountCents * 100.0 / total)}%  " +
                                        "¥${"%.2f".format(cs.amountCents / 100.0)}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

data class DayBar(val day: String, val cents: Long)
data class CategorySum(val name: String, val amountCents: Long)

/** 分层卡片样式：底色浮起 + 细边框 + 微阴影，提升层次与质感 */
@Composable
fun Modifier.cardSurface(shape: Shape = RoundedCornerShape(20.dp)): Modifier {
    val c = MaterialTheme.colorScheme
    return this
        .shadow(3.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
        .background(c.surfaceContainerLow, shape)
        .border(1.dp, c.outlineVariant.copy(alpha = 0.45f), shape)
}

/** 顶部总览卡：本月支出为主视觉，下方两列展示今年 / 累计 */
@Composable
private fun OverviewCard(monthCents: Long, yearCents: Long, totalCents: Long) {
    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .cardSurface()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.stats_month_expense),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            formatCents(monthCents),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            StatItem(
                label = stringResource(R.string.stats_year_expense),
                cents = yearCents,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.width(1.dp).height(34.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
            StatItem(
                label = stringResource(R.string.stats_total_expense),
                cents = totalCents,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 总览卡内的小指标：标签在上、金额在下，居中单行 */
@Composable
private fun StatItem(label: String, cents: Long, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            formatCents(cents),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/** 金额分 -> "¥1,234.56"，用于统计卡与柱状图标签 */
@Composable
fun formatCents(cents: Long): String {
    val fmt = remember { java.text.DecimalFormat("#,##0.00") }
    return "¥${fmt.format(cents / 100.0)}"
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

/** 柱状趋势图（Canvas 自绘 + 生长动画），按压柱子显示当日金额 */
@Composable
fun TrendBarChart(daily: List<DayBar>, color: Color, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
    }
    var selected by remember { mutableStateOf(-1) }

    val maxCents = (daily.maxOfOrNull { it.cents } ?: 0L).coerceAtLeast(1)
    val labelColorArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    Canvas(
        modifier
            .pointerInput(daily) {
                fun hitIndex(x: Float): Int {
                    val n = daily.size
                    if (n == 0) return -1
                    val gap = 6.dp.toPx()
                    val barW = (this.size.width - gap * (n - 1)) / n
                    return (x / (barW + gap)).toInt().coerceIn(0, n - 1)
                }
                // 按压滑动跟随显示当前柱金额，松手隐藏
                awaitEachGesture {
                    val down = awaitFirstDown()
                    selected = hitIndex(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        if (change.positionChanged()) {
                            selected = hitIndex(change.position.x)
                        }
                        change.consume()
                    }
                    selected = -1
                }
            },
    ) {
        val n = daily.size
        if (n == 0) return@Canvas
        val gap = 6.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        val baseY = size.height - 4.dp.toPx()
        daily.forEachIndexed { i, d ->
            val h = (d.cents.toFloat() / maxCents) * (size.height - 20.dp.toPx()) * progress.value
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
            val topY = baseY - (d.cents.toFloat() / maxCents) * (size.height - 20.dp.toPx()) * progress.value
            val cx = selected * (barW + gap) + barW / 2
            val text = "¥${"%,.2f".format(Locale.US, d.cents / 100.0)}"
            val labelY = 10.dp.toPx()
            val paintTx = android.graphics.Paint().apply {
                this.color = android.graphics.Color.WHITE
                textSize = 9.sp.toPx()
                isAntiAlias = true
                isFakeBoldText = true
            }
            val textW = paintTx.measureText(text)
            val pad = 8.dp.toPx()
            val labelW = textW + pad * 2
            val left = (cx - labelW / 2).coerceIn(0f, size.width - labelW).coerceAtLeast(0f)
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
    val total = data.sumOf { it.amountCents }.coerceAtLeast(1)
    val progress = remember { Animatable(0f) }
    val labelColorArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val amountColorArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val monthLabel = stringResource(R.string.stats_month_expense)
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
            val sweep = (cs.amountCents.toFloat() / total) * 360f * progress.value
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
            paint.textSize = 20.sp.toPx()
            paint.isFakeBoldText = true
            drawText(
                "¥${"%.2f".format(total / 100.0)}",
                size.width / 2,
                size.height / 2 + 16.dp.toPx(),
                paint,
            )
        }
    }
}
