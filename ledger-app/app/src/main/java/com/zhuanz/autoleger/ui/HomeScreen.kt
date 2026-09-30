package com.zhuanz.autoleger.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(vm: HomeViewModel, onEdit: (Long) -> Unit, onStats: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val periodKey = "${state.month}/${state.selectedCategory}"
    var previousKey by rememberSaveable { mutableStateOf(periodKey) }
    LaunchedEffect(periodKey) {
        if (previousKey != periodKey) { listState.scrollToItem(0); previousKey = periodKey }
    }
    RefreshLedgerDate(vm)
    Column(Modifier.fillMaxSize()) {
        MonthBar(state.month, vm::selectMonth)
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.weight(1f)) {
            item("summary") {
                if (!state.loading) SummaryCard(state.summary, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), onStats, state.month)
            }
            item("filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                        selected = state.selectedCategory == null, onClick = { vm.selectCategory(null) },
                        label = { Text("全部") }, modifier = Modifier.heightIn(min = 48.dp))
                    state.categories.forEach { category ->
                        FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                        selected = state.selectedCategory == category.id,
                            onClick = { vm.selectCategory(category.id) }, label = { Text(category.name) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                    FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                        selected = state.selectedCategory == UNCATEGORIZED_ID,
                        onClick = { vm.selectCategory(UNCATEGORIZED_ID) }, label = { Text("未分类") },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            item("list-title") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("账单明细 · ${state.visible.size}笔", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.Add, null); Text("记一笔") }
                }
            }
            if (state.loading) item("loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
                LedgerEmptyState("正在读取账单", "稍等一下，你的记录马上就来")
            } else if (state.visible.isEmpty()) item("empty") {
                if (state.selectedCategory != null) LedgerEmptyState("这个分类还没有记录", "试试查看本月的全部账单", "清除筛选") {
                    vm.selectCategory(null)
                } else LedgerEmptyState("这个月还没有账单", "自动识别的账单确认后会出现在这里", "记一笔") { showAdd = true }
            }
            val categories = state.categories.associateBy { it.id }
            state.visible.groupBy { transactionDay(it) }.forEach { (day, entries) ->
                item("day-$day") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        val today = state.today
                        val label = when (day) {
                            today -> "今天"
                            today.minusDays(1) -> "昨天"
                            else -> day.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA))
                        }
                        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("支出 ${formatCents(entries.filter { it.type != "REFUND" }.sumOf { it.amountCents })}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(entries, key = { "tx-${it.id}" }, contentType = { "transaction" }) { tx ->
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        LedgerTransactionRow(tx, categories[tx.categoryId]?.name ?: "未分类") { onEdit(tx.id) }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
        }
    }
    if (showAdd) AddEntrySheet(onDismiss = { showAdd = false }, onSaved = {
        vm.selectMonth(java.time.YearMonth.now())
        vm.selectCategory(null)
    })
}
