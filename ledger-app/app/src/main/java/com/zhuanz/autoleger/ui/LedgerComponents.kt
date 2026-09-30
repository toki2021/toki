package com.zhuanz.autoleger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhuanz.autoleger.data.TransactionEntity
import com.zhuanz.autoleger.data.TYPE_REFUND
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MonthBar(month: YearMonth, onSelect: (YearMonth) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelect(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上个月")
            }
            Text("${month.year}年${month.monthValue}月", Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (month != YearMonth.now()) {
                TextButton(onClick = { onSelect(YearMonth.now()) }) { Text("本月") }
            }
            IconButton(onClick = { onSelect(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下个月")
            }
        }
    }
}

@Composable
fun SummaryCard(summary: MonthSummary, modifier: Modifier = Modifier, onStats: (() -> Unit)? = null, month: YearMonth = YearMonth.now()) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (month == YearMonth.now()) "本月支出" else "当月支出", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                if (onStats != null) TextButton(onClick = onStats) { Text("查看统计") }
            }
            AmountText(summary.expense, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
            if (LocalDensity.current.fontScale > 1.3f) {
                Metric("退款", summary.refund)
                Metric("日均支出", summary.dailyAverage)
            } else Row(Modifier.fillMaxWidth()) {
                Metric("退款", summary.refund, Modifier.weight(1f))
                Metric("日均支出", summary.dailyAverage, Modifier.weight(1f))
            }
            Text("支出未扣除退款 · ${summary.transactions.size}笔记录", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun Metric(label: String, cents: Long, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        AmountText(cents, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun LedgerEmptyState(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
fun LedgerTransactionRow(tx: TransactionEntity, category: String, onClick: () -> Unit) {
    val refund = tx.type == TYPE_REFUND
    val time = remember(tx.time) {
        Instant.ofEpochMilli(tx.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    val detail = (if (refund) "退款 · " else "") + "$category · $time" +
        if (tx.source == "CSV") " · 已导入" else ""
    BoxWithConstraints(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp)) {
        val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f || tx.amountCents >= 100_000_000L
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(category, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tx.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (stacked) SignedAmount(tx.amountCents, refund)
            }
            if (!stacked) {
                Spacer(Modifier.width(12.dp))
                SignedAmount(tx.amountCents, refund)
            }
        }
    }
}

@Composable
private fun SignedAmount(cents: Long, refund: Boolean) {
    Text((if (refund) "+" else "−") + formatCents(cents),
        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
        color = if (refund) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
}

@Composable
fun RefreshLedgerDate(vm: HomeViewModel) {
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refreshDate()
        onPauseOrDispose { }
    }
    LaunchedEffect(vm) {
        while (true) {
            val now = java.time.ZonedDateTime.now()
            val next = now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault())
            kotlinx.coroutines.delay(java.time.Duration.between(now, next).toMillis().coerceAtLeast(1_000))
            vm.refreshDate()
        }
    }
}

@Composable
fun LedgerSection(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
