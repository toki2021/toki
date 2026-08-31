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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhuanz.autoleger.data.AppContainer
import com.zhuanz.autoleger.data.TransactionEntity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import com.zhuanz.autoleger.R
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

/** 用 AppContainer 构造 HomeViewModel 的工厂（Activity 级共享） */
@Composable
private fun homeViewModel(container: AppContainer): HomeViewModel {
    val factory = remember(container) {
        viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
    return viewModel(factory = factory)
}

@Composable
fun HomeScreen(
    onEdit: (Long) -> Unit,
    onStats: () -> Unit,
    vm: UiVariantViewModel,
) {
    val uiState by vm.uiState.collectAsState()
    val container = rememberContainer()
    val context = LocalContext.current
    val homeVm = homeViewModel(container)
    val transactions by container.transactionDao.observeAll().collectAsState(initial = emptyList())
    val categories by container.categoryDao.observeAll().collectAsState(initial = emptyList())
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    // 删除确认 Dialog 的显示状态由 homeVm.pendingDeleteTx 驱动
    var showDeleteDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 收集删除事件：弹出 Undo Snackbar
    LaunchedEffect(Unit) {
        homeVm.undoEvents.collect { deletedTx ->
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.home_delete_snackbar, deletedTx.merchant),
                actionLabel = context.getString(R.string.common_undo),
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                homeVm.undo(deletedTx)
            }
        }
    }

    // 日期边界只随进入页面计算一次，避免每次重组都 new Calendar + 改字段
    val (todayStart, monthStart, dayOfMonth) = remember {
        val cal = Calendar.getInstance()
        // 先读"今天几号"再改动日历字段，否则恒为 1 导致日均错误
        val dom = cal.get(Calendar.DAY_OF_MONTH)
        val ts = cal.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val ms = cal.apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        Triple(ts, ms, dom)
    }

    val (todayExpense, monthExpense, monthDailyAvg) =
        remember(transactions, todayStart, monthStart, dayOfMonth) {
            val te = transactions.filter { it.time >= todayStart && it.type != "REFUND" }.sumOf { it.amountCents }
            val me = transactions.filter { it.time >= monthStart && it.type != "REFUND" }.sumOf { it.amountCents }
            Triple(te, me, me / dayOfMonth)
        }

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
    if (uiState.effective == UiVariant.F) {
        Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            MonoHome(
                transactions = transactions,
                categories = categories,
                monthExpense = monthExpense,
                todayExpense = todayExpense,
                avgExpense = monthDailyAvg,
                onEdit = onEdit,
                onStats = onStats,
                onDelete = {
                    homeVm.requestDelete(it)
                    showDeleteDialog = true
                },
                onAdd = { showAddSheet = true },
            )
        }
        if (showAddSheet) {
            AddEntrySheet(onDismiss = { showAddSheet = false })
        }
        DeleteConfirmDialog(
            visible = showDeleteDialog,
            onDismiss = {
                showDeleteDialog = false
                homeVm.clearPendingDelete()
            },
            onConfirm = {
                showDeleteDialog = false
                homeVm.confirmDelete()
            },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.title_add_entry), fontWeight = FontWeight.Medium)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // —— 摘要区（三套方案三种头部，点击 A/C 进入统计）——
            when (uiState.effective) {
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
                        stringResource(R.string.home_empty),
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
                                categoryName = categories.firstOrNull { it.id == tx.categoryId }?.name ?: stringResource(R.string.category_uncategorized),
                                dateText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tx.time)),
                                onClick = { onEdit(tx.id) },
                                onDelete = {
                                    homeVm.requestDelete(tx)
                                    showDeleteDialog = true
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
    DeleteConfirmDialog(
        visible = showDeleteDialog,
        onDismiss = {
            showDeleteDialog = false
            homeVm.clearPendingDelete()
        },
        onConfirm = {
            showDeleteDialog = false
            homeVm.confirmDelete()
        },
    )
}

/** 删除前的确认对话框（visible 时显示） */
@Composable
private fun DeleteConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_delete_title)) },
        text = { Text(stringResource(R.string.home_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
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
    val deleteLabel = stringResource(R.string.common_delete)
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
                categoryName + " · " + dateText + if (tx.source == "CSV") stringResource(R.string.common_imported) else "",
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
                .clickable(onClick = onDelete)
                .semantics { contentDescription = deleteLabel },
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
                    stringResource(R.string.stats_month_expense),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Rounded.Insights, contentDescription = stringResource(R.string.home_view_stats_cd),
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
                stringResource(
                    R.string.home_today_avg,
                    "%,.2f".format(todayCents / 100.0),
                    "%,.2f".format(avgCents / 100.0),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.home_view_stats),
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
            stringResource(R.string.app_name),
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.stats_month_expense), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedAmountText(
                    cents = monthCents,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.home_today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountText(cents = todayCents, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.home_daily_avg), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoChip(stringResource(R.string.home_today), todayCents, Modifier.weight(1f))
            InfoChip(stringResource(R.string.home_month), monthCents, Modifier.weight(1f))
            InfoChip(stringResource(R.string.home_daily_avg), avgCents, Modifier.weight(1f))
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
