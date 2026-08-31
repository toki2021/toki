package com.zhuanz.autoleger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.autoleger.data.AppContainer
import com.zhuanz.autoleger.data.CategoryEntity
import com.zhuanz.autoleger.data.TransactionEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 方案 F · 简约卡片风首页：细描边卡片、胶囊筛选、按日分组 */
@Composable
fun MonoHome(
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    monthExpense: Long,
    todayExpense: Long,
    avgExpense: Long,
    onEdit: (Long) -> Unit,
    onStats: () -> Unit,
    onDelete: (TransactionEntity) -> Unit,
    onAdd: () -> Unit,
) {
    var filter by remember { mutableStateOf<String?>(null) }
    val catNameOf: (TransactionEntity) -> String = { tx ->
        categories.firstOrNull { it.id == tx.categoryId }?.name ?: "未分类"
    }

    val cal = Calendar.getInstance()
    val greeting = when (cal.get(Calendar.HOUR_OF_DAY)) {
        in 0..11 -> "早上好"
        in 12..13 -> "中午好"
        in 14..17 -> "下午好"
        else -> "晚上好"
    }

    val dayKeyFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val dayHeaderFmt = remember { SimpleDateFormat("M月d日 EEEE", Locale.CHINA) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val visible = transactions.filter { filter == null || catNameOf(it) == filter }
    val groups = remember(visible, dayKeyFmt) {
        visible
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(
                "Hi，$greeting",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(18.dp))

            // —— 本月支出摘要卡（细描边，点击进统计）——
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onStats)
                    .padding(20.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "本月支出",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "统计 ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                AnimatedAmountText(
                    cents = monthExpense,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "今日 ¥${"%,.2f".format(todayExpense / 100.0)}   ·   日均 ¥${"%,.2f".format(avgExpense / 100.0)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(18.dp))

            // —— 分类筛选胶囊 ——
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill("全部", filter == null) { filter = null }
                categories.forEach { c ->
                    FilterPill(c.name, filter == c.name) { filter = c.name }
                }
            }
            Spacer(Modifier.height(18.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("账单明细", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${visible.size} 笔",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = onAdd)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "＋ 记一笔",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (groups.isEmpty()) {
                item {
                    Text(
                        "没有符合条件的账单",
                        Modifier.padding(vertical = 40.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            groups.forEach { group ->
                item(key = "d_${group.key}") {
                    // 每日一张描边卡片
                    Column(
                        Modifier
                            .padding(horizontal = 24.dp, vertical = 6.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                group.header,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AmountText(
                                cents = group.totalCents,
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        group.items.forEach { tx ->
                            MonoRow(
                                tx = tx,
                                categoryName = catNameOf(tx),
                                dateText = timeFmt.format(Date(tx.time)),
                                onClick = { onEdit(tx.id) },
                                onDelete = { onDelete(tx) },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(100.dp)) }
        }
    }
}

@Composable
private fun MonoRow(
    tx: TransactionEntity,
    categoryName: String,
    dateText: String,
    onClick: () -> Unit,
    onDelete: (TransactionEntity) -> Unit,
) {
    val isRefund = tx.type == "REFUND"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(categoryName, size = 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tx.merchant, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                categoryName + " · " + dateText + if (tx.source == "CSV") " · 导入" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AmountText(
            cents = if (isRefund) tx.amountCents else -tx.amountCents,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "✕",
            modifier = Modifier.clickable { onDelete(tx) },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

/** 筛选胶囊：选中为实心黑（暗色为白），未选中细描边 */
@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Row(
        Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.background
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** 供 AppNav 底栏使用的待处理徽标 */
@Composable
fun MonoBadge(count: Int) {
    if (count <= 0) return
    Box(
        Modifier
            .size(16.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}
