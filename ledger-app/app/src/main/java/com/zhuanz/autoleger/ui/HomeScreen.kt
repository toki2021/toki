package com.zhuanz.autoleger.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.autoleger.data.TransactionEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DayGroup(
    val key: String,
    val header: String,
    val totalCents: Long,
    val items: List<TransactionEntity>,
)

@Composable
fun HomeScreen(
    onEdit: (Long) -> Unit,
    onStats: () -> Unit,
) {
    val container = rememberContainer()
    val transactions by container.transactionDao.observeAll().collectAsState(initial = emptyList())
    val categories by container.categoryDao.observeAll().collectAsState(initial = emptyList())
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    val cal = Calendar.getInstance()
    // 先读"今天几号"再改动日历字段，否则恒为 1 导致日均错误
    val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
    val todayStart = cal.apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val monthStart = cal.apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

    val todayExpense = transactions.filter { it.time >= todayStart && it.type != "REFUND" }.sumOf { it.amountCents }
    val monthExpense = transactions.filter { it.time >= monthStart && it.type != "REFUND" }.sumOf { it.amountCents }
    val monthDailyAvg = monthExpense / dayOfMonth

    // 按日分组（保持时间倒序）
    val dayKeyFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val dayHeaderFmt = remember { SimpleDateFormat("M月d日 EEEE", Locale.CHINA) }
    val groups = remember(transactions, dayKeyFmt) {
        transactions
            .groupBy { dayKeyFmt.format(Date(it.time)) }
            .map { (key, items) ->
                DayGroup(
                    key = key,
                    header = dayHeaderFmt.format(Date(items.first().time)),
                    totalCents = items.filter { it.type != "REFUND" }.sumOf { it.amountCents },
                    items = items,
                )
            }
    }

    // 方案 F · 简约卡片风：独立布局
    if (UiVariantState.effective == UiVariant.F) {
        Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            MonoHome(
                transactions = transactions,
                categories = categories,
                monthExpense = monthExpense,
                todayExpense = todayExpense,
                avgExpense = monthDailyAvg,
                onEdit = onEdit,
                onStats = onStats,
                onDelete = { scope_delete(it, container) },
                onAdd = { showAddSheet = true },
            )
        }
        if (showAddSheet) {
            AddEntrySheet(onDismiss = { showAddSheet = false })
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("记一笔", fontWeight = FontWeight.Medium)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // —— 摘要区（三套方案三种头部，点击 A/C 进入统计）——
            when (UiVariantState.effective) {
                UiVariant.A -> GradientHeroHeader(monthExpense, todayExpense, monthDailyAvg, onStats)
                UiVariant.B -> LargeTitleHeader(monthExpense, todayExpense, monthDailyAvg)
                UiVariant.C -> CompactHeader(monthExpense, todayExpense, monthDailyAvg)
                UiVariant.D -> GradientHeroHeader(monthExpense, todayExpense, monthDailyAvg, onStats)
                UiVariant.E -> CompactHeader(monthExpense, todayExpense, monthDailyAvg)
                UiVariant.F -> CompactHeader(monthExpense, todayExpense, monthDailyAvg)
            }

            // —— 账单流水（按日分组）——
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有账单\n收到支付通知后点「入账」即可记录",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    groups.forEach { group ->
                        item(key = "h_${group.key}") {
                            DayHeader(group.header, group.totalCents)
                        }
                        items(group.items, key = { it.id }) { tx ->
                            TransactionRow(
                                tx = tx,
                                categoryName = categories.firstOrNull { it.id == tx.categoryId }?.name ?: "未分类",
                                dateText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tx.time)),
                                onClick = { onEdit(tx.id) },
                                onDelete = {
                                    scope_delete(tx.id, container)
                                },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    if (showAddSheet) {
        AddEntrySheet(onDismiss = { showAddSheet = false })
    }
}

// 事务删除：投递到 IO 协程执行
private val deleteScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
)

private fun scope_delete(txId: Long, container: com.zhuanz.autoleger.data.AppContainer) {
    deleteScope.launch { container.transactionDao.deleteById(txId) }
}

@Composable
private fun DayHeader(header: String, totalCents: Long) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            header,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AmountText(
            cents = totalCents,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 账单行：渐入动画 + 图标 + 商户/分类 + 金额 */
@Composable
private fun TransactionRow(
    tx: TransactionEntity,
    categoryName: String,
    dateText: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val appear = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
    }
    val isRefund = tx.type == "REFUND"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .graphicsLayer {
                alpha = appear.value
                translationX = 40f * (1 - appear.value)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(categoryName, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tx.merchant, style = MaterialTheme.typography.bodyLarge)
            Text(
                categoryName + " · " + dateText + if (tx.source == "CSV") " · 导入" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        AmountText(
            cents = if (isRefund) tx.amountCents else -tx.amountCents,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            "✕",
            modifier = Modifier
                .size(24.dp)
                .clickable(onClick = onDelete),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}


/** 方案 A：渐变英雄卡 */
@Composable
fun GradientHeroHeader(monthCents: Long, todayCents: Long, avgCents: Long, onStats: () -> Unit) {
    Box(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        Color(0xFF00382F),
                    )
                )
            )
            .clickable(onClick = onStats)
            .padding(24.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本月支出",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Rounded.Insights, contentDescription = "查看统计",
                    tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(16.dp),
                )
            }
            AnimatedAmountText(
                cents = monthCents,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "今日 ¥${"%,.2f".format(todayCents / 100.0)}   ·   日均 ¥${"%,.2f".format(avgCents / 100.0)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "查看统计 ›",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 方案 B：沉浸大标题 + 三列摘要 */
@Composable
fun LargeTitleHeader(monthCents: Long, todayCents: Long, avgCents: Long) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(
            "自动记账",
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("本月支出", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedAmountText(
                    cents = monthCents,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("今日", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountText(cents = todayCents, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("日均", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountText(cents = avgCents, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 方案 C：紧凑顶栏 + 信息胶囊 */
@Composable
fun CompactHeader(monthCents: Long, todayCents: Long, avgCents: Long) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "自动记账",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoChip("今日", todayCents, Modifier.weight(1f))
            InfoChip("本月", monthCents, Modifier.weight(1f))
            InfoChip("日均", avgCents, Modifier.weight(1f))
        }
    }
}

@Composable
fun InfoChip(label: String, cents: Long, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.width(6.dp))
        AmountText(
            cents = cents,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
