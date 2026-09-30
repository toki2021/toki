package com.zhuanz.autoleger.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.stringResource
import com.zhuanz.autoleger.R
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
fun EditEntryScreen(txId: Long, pendingId: Long, onDone: () -> Unit, onDelete: (TransactionEntity) -> Unit) {
    val container = rememberContainer()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val categories by container.categoryDao.observeAll().collectAsState(initial = emptyList())

    // 历史常用商户（DAO 层去重+排序+限量），排除泛称/占位商户名
    val genericMerchants = remember { MerchantFilters.genericMerchants(context).toList() }
    val recentMerchants by container.transactionDao.observeRecentMerchants(8, genericMerchants)
        .collectAsState(initial = emptyList())

    var type by rememberSaveable { mutableStateOf(TYPE_EXPENSE) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var time by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var formInitialized by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf<TransactionEntity?>(null) }
    var deleteDialog by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 载入：已有账单 or 待确认通知
    LaunchedEffect(txId, pendingId) {
        if (loaded) return@LaunchedEffect
        when {
            txId > 0 -> container.transactionDao.getById(txId)?.let { tx ->
                original = tx
                if (!formInitialized) {
                type = tx.type
                amountText = java.math.BigDecimal.valueOf(tx.amountCents, 2).toPlainString()
                merchant = tx.merchant
                categoryId = tx.categoryId
                time = tx.time
                }
                formInitialized = true
                loaded = true
            }
            pendingId > 0 -> container.pendingEntryDao.getById(pendingId)?.let { entry ->
                if (!formInitialized) {
                val hint = EntryConfirmer.extractCategoryHint(container, entry)
                hint.amountCents?.let { amountText = java.math.BigDecimal.valueOf(it, 2).toPlainString() }
                merchant = hint.merchant ?: ""
                time = entry.time
                type = if (entry.text.contains("退款")) TYPE_REFUND else TYPE_EXPENSE
                }
                formInitialized = true
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

    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0), topBar = {
        TopAppBar(navigationIcon = { androidx.compose.material3.IconButton(onClick = onDone) { androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }, title = {
            Text(
                stringResource(
                    when {
                        txId > 0 -> R.string.title_edit_entry
                        pendingId > 0 -> R.string.title_confirm_entry
                        else -> R.string.title_add_entry
                    }
                )
            )
        })
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp)
                .imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = type == TYPE_EXPENSE,
                    onClick = { type = TYPE_EXPENSE },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.type_expense)) }
                SegmentedButton(
                    selected = type == TYPE_REFUND,
                    onClick = { type = TYPE_REFUND },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.type_refund)) }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(stringResource(R.string.field_amount)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            )

            OutlinedTextField(
                value = merchant,
                onValueChange = {
                    merchant = it
                    categoryId = null
                },
                label = { Text(stringResource(R.string.field_merchant)) },
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

            Text(stringResource(R.string.field_category), style = MaterialTheme.typography.titleSmall)
            FlowChips(
                items = categories,
                selectedId = categoryId,
                onSelect = { categoryId = it.id },
                label = { it.name },
            )

            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("时间：${dateFmt.format(Date(time))}")
            }

            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            Button(
                enabled = loaded && !saving && (amountText.toCents() ?: 0L) > 0,
                onClick = {
                    saving = true
                    // BigDecimal 精确换算，避免 Math.round(amount * 100) 的 double 精度损失
                    val cents = amountText.toCents() ?: 0L
                    if (cents <= 0) return@Button
                    scope.launch {
                        try {
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
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { error = "保存失败，请重试" }
                        finally { saving = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "保存中…" else stringResource(R.string.common_save)) }
            if (txId > 0 && original != null) {
                TextButton(onClick = { deleteDialog = true }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("删除这笔账单", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (deleteDialog) AlertDialog(
        onDismissRequest = { deleteDialog = false },
        title = { Text("删除这笔账单？") },
        text = { Text("删除后可通过底部提示撤销。") },
        confirmButton = { TextButton(onClick = {
            deleteDialog = false
            original?.let { onDelete(it) }
        }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } },
    )

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
                }) { Text(stringResource(R.string.common_next)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.common_cancel)) } },
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
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.common_cancel)) } },
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
