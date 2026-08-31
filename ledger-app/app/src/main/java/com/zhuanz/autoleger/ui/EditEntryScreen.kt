package com.zhuanz.autoleger.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhuanz.autoleger.data.EntryConfirmer
import com.zhuanz.autoleger.data.MerchantFilters
import com.zhuanz.autoleger.data.SOURCE_MANUAL
import com.zhuanz.autoleger.data.TransactionEntity
import com.zhuanz.autoleger.data.TYPE_EXPENSE
import com.zhuanz.autoleger.data.TYPE_REFUND
import com.zhuanz.autoleger.data.toCents
import com.zhuanz.autoleger.notify.ConfirmNotifier
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 单笔账单新增/编辑页。
 * txId > 0：编辑已有账单；pendingId > 0：确认一条通知入账；两者皆 -1：手动新记。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntryScreen(txId: Long, pendingId: Long, onDone: () -> Unit) {
    val container = rememberContainer()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val categories by container.categoryDao.observeAll().collectAsState(initial = emptyList())

    // 历史常用商户（DAO 层去重+排序+限量），排除泛称/占位商户名
    val genericMerchants = remember { MerchantFilters.genericMerchants(context).toList() }
    val recentMerchants by container.transactionDao.observeRecentMerchants(8, genericMerchants)
        .collectAsState(initial = emptyList())

    var type by remember { mutableStateOf(TYPE_EXPENSE) }
    var amountText by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var loaded by remember { mutableStateOf(false) }

    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 载入：已有账单 or 待确认通知
    LaunchedEffect(txId, pendingId) {
        if (loaded) return@LaunchedEffect
        when {
            txId > 0 -> container.transactionDao.getById(txId)?.let { tx ->
                type = tx.type
                amountText = String.format("%.2f", tx.amountCents / 100.0)
                merchant = tx.merchant
                categoryId = tx.categoryId
                time = tx.time
                loaded = true
            }
            pendingId > 0 -> container.pendingEntryDao.getById(pendingId)?.let { entry ->
                val hint = EntryConfirmer.extractCategoryHint(container, entry)
                hint.amountCents?.let { amountText = String.format("%.2f", it / 100.0) }
                merchant = hint.merchant ?: ""
                time = entry.time
                type = if (entry.text.contains("退款")) TYPE_REFUND else TYPE_EXPENSE
                loaded = true
            }
            else -> loaded = true
        }
    }
    LaunchedEffect(categories, merchant, loaded) {
        if (loaded && categoryId == null && merchant.isNotBlank()) {
            categoryId = EntryConfirmer.categoryFor(container, merchant)
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = {
            Text(
                when {
                    txId > 0 -> "编辑账单"
                    pendingId > 0 -> "确认入账"
                    else -> "记一笔"
                }
            )
        })
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = type == TYPE_EXPENSE,
                    onClick = { type = TYPE_EXPENSE },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("支出") }
                SegmentedButton(
                    selected = type == TYPE_REFUND,
                    onClick = { type = TYPE_REFUND },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("退款") }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("金额（元）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = merchant,
                onValueChange = {
                    merchant = it
                    categoryId = null
                },
                label = { Text("商户 / 备注") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (merchant.isBlank() && recentMerchants.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recentMerchants.forEach { m ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                merchant = m
                                categoryId = null
                            },
                            label = { Text(m) },
                        )
                    }
                }
            }

            Text("分类", style = MaterialTheme.typography.titleSmall)
            FlowChips(
                items = categories,
                selectedId = categoryId,
                onSelect = { categoryId = it.id },
                label = { it.name },
            )

            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("时间：${dateFmt.format(Date(time))}")
            }

            Button(
                onClick = {
                    // BigDecimal 精确换算，避免 Math.round(amount * 100) 的 double 精度损失
                    val cents = amountText.toCents() ?: 0L
                    if (cents <= 0) return@Button
                    scope.launch {
                        when {
                            txId > 0 -> container.transactionDao.getById(txId)?.let { old ->
                                container.transactionDao.update(
                                    old.copy(
                                        type = type, amountCents = cents, merchant = merchant
                                            .ifBlank { "未知商户" },
                                        categoryId = categoryId,
                                        time = time,
                                    )
                                )
                            }
                            pendingId > 0 -> {
                                container.transactionDao.insert(
                                    TransactionEntity(
                                        type = type,
                                        amountCents = cents,
                                        merchant = merchant.ifBlank { "未知商户" },
                                        categoryId = categoryId,
                                        time = time,
                                        source = SOURCE_MANUAL,
                                        rawText = container.pendingEntryDao.getById(pendingId)
                                            ?.let { "${it.title} ${it.text}" },
                                    )
                                )
                                ConfirmNotifier.cancel(context, pendingId)
                                container.pendingEntryDao.deleteById(pendingId)
                            }
                            else -> container.transactionDao.insert(
                                TransactionEntity(
                                    type = type,
                                    amountCents = cents,
                                    merchant = merchant.ifBlank { "未知商户" },
                                    categoryId = categoryId,
                                    time = time,
                                    source = SOURCE_MANUAL,
                                )
                            )
                        }
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = time)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        val cal = Calendar.getInstance().apply {
                            this.timeInMillis = picked
                            val orig = Calendar.getInstance().apply { this.timeInMillis = time }
                            set(Calendar.HOUR_OF_DAY, orig.get(Calendar.HOUR_OF_DAY))
                            set(Calendar.MINUTE, orig.get(Calendar.MINUTE))
                        }
                        time = cal.timeInMillis
                    }
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("下一步") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }

    if (showTimePicker) {
        val cal = Calendar.getInstance().apply { this.timeInMillis = time }
        val state = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val newCal = Calendar.getInstance().apply {
                        this.timeInMillis = time
                        set(Calendar.HOUR_OF_DAY, state.hour)
                        set(Calendar.MINUTE, state.minute)
                    }
                    time = newCal.timeInMillis
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
private fun <T> FlowChips(
    items: List<T>,
    selectedId: Long?,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val id = when (item) {
                is com.zhuanz.autoleger.data.CategoryEntity -> item.id
                else -> null
            }
            FilterChip(
                selected = id != null && id == selectedId,
                onClick = { onSelect(item) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryIcon(label(item).toString(), size = 22.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(label(item))
                    }
                },
            )
        }
    }
}
