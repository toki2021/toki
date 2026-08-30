package com.zhuanz.autoleger.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
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

    val cal = Calendar.getInstance()
    val monthStart = cal.apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val monthTx = transactions.filter { it.time >= monthStart && it.type != TYPE_REFUND }
    val monthTotal = monthTx.sumOf { it.amountCents }

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
    val categorySums = remember(monthTx, categories) {
        monthTx.groupBy { it.categoryId }
            .map { (catId, items) ->
                val cat = categories.firstOrNull { it.id == catId }
                CategorySum(cat?.name ?: "未分类", items.sumOf { it.amountCents })
            }
            .sortedByDescending { it.amountCents }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("统计", fontWeight = FontWeight.Bold) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    "本月支出",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedAmountText(
                    cents = monthTotal,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(20.dp))
            }

            // —— 每日趋势（30 天柱状图，Canvas 自绘 + 生长动画）——
            item {
                ChartCard(title = "每日趋势 · 近 30 天") {
                    TrendBarChart(
                        daily = daily,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                }
            }

            // —— 分类占比环形图 ——
            item {
                ChartCard(title = "本月分类占比") {
                    if (categorySums.isEmpty()) {
                        Text(
                            "本月暂无支出",
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

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** 柱状趋势图（Canvas 自绘 + 生长动画） */
@Composable
fun TrendBarChart(daily: List<DayBar>, color: Color, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
    }
    val maxCents = (daily.maxOfOrNull { it.cents } ?: 0L).coerceAtLeast(1)
    val labelFmt = SimpleDateFormat("d", Locale.getDefault())
    Canvas(modifier) {
        val n = daily.size
        if (n == 0) return@Canvas
        val gap = 6.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        val baseY = size.height - 4.dp.toPx()
        daily.forEachIndexed { i, d ->
            val h = (d.cents.toFloat() / maxCents) * (size.height - 20.dp.toPx()) * progress.value
            drawRoundRect(
                color = if (i == n - 1) color else color.copy(alpha = 0.35f + 0.4f * (d.cents.toFloat() / maxCents)),
                topLeft = Offset(i * (barW + gap), baseY - h),
                size = Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 3),
            )
        }
        // 日期标签：只画 1 号、15 号、今天附近
        val labelColor = androidx.compose.ui.graphics.Color.Gray
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.GRAY
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
                color = android.graphics.Color.GRAY
                textSize = 12.sp.toPx()
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            drawText("本月支出", size.width / 2, size.height / 2 - 6.dp.toPx(), paint)
            paint.color = android.graphics.Color.WHITE
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
