package com.zhuanz.autoleger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhuanz.autoleger.data.*
import com.zhuanz.autoleger.notify.ConfirmNotifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingScreen(onConfirm: (Long) -> Unit) {
    val container = rememberContainer()
    val pending by container.pendingEntryDao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by container.categoryDao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("待确认", fontWeight = FontWeight.Bold) }) },
        contentWindowInsets = WindowInsets(0), snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("核对一下，再安心入账", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (pending.isEmpty()) item { LedgerEmptyState("全部处理好了", "新的自动识别记录会出现在这里") }
            items(pending, key = { it.id }) { entry ->
                val hint = remember(entry) { EntryConfirmer.extractCategoryHint(container, entry) }
                var categoryName by remember(entry, categories) { mutableStateOf("未分类") }
                LaunchedEffect(entry, categories) {
                    val id = EntryConfirmer.categoryFor(container, hint.merchant ?: "")
                    categoryName = categories.firstOrNull { it.id == id }?.name ?: "未分类"
                }
                var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
                var menu by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var discard by remember { mutableStateOf(false) }
                val runAction: (suspend () -> String) -> Unit = { action ->
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try { snackbar.showSnackbar(action()) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { snackbar.showSnackbar("操作失败，请重试") }
                            finally { busy = false }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(hint.merchant ?: entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (hint.amountCents != null) AmountText(hint.amountCents, style = MaterialTheme.typography.headlineSmall)
                                else Text("金额待补充", color = MaterialTheme.colorScheme.error)
                            }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Rounded.MoreVert, "更多操作") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("重新识别") }, onClick = {
                                        menu = false
                                        runAction {
                                            when (EntryConfirmer.reRecognize(container, entry)) {
                                                is EntryConfirmer.ReRecognizeResult.Updated -> "已更新识别结果，请核对"
                                                EntryConfirmer.ReRecognizeResult.NoChange -> "识别结果没有变化"
                                            }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("丢弃", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; discard = true })
                                }
                            }
                        }
                        Text((if (entry.text.contains("退款")) "退款 · " else "") + categoryName + " · " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(entry.time)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry.status != PENDING_CONFIRM) Text("未能完整识别，请补充后入账", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (entry.status == PENDING_CONFIRM) Button(enabled = !busy, onClick = {
                                runAction {
                                    if (EntryConfirmer.confirm(container, entry)) {
                                        ConfirmNotifier.cancel(context, entry.id)
                                        container.pendingEntryDao.deleteById(entry.id)
                                        "已入账"
                                    } else "无法直接入账，请编辑后确认"
                                }
                            }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (busy) "处理中…" else "确认入账") }
                            OutlinedButton(onClick = { onConfirm(entry.id) }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (entry.status == PENDING_CONFIRM) "编辑" else "补充信息") }
                        }
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (expanded) "收起原通知" else "查看原通知")
                        }
                        if (expanded) Text(entry.title + "\n" + entry.text, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("丢弃这条记录？") },
                    text = { Text("这条通知将不再保留，也不会计入账单。") },
                    confirmButton = { TextButton(onClick = {
                        discard = false
                        runAction {
                            ConfirmNotifier.cancel(context, entry.id)
                            container.pendingEntryDao.deleteById(entry.id)
                            "已丢弃"
                        }
                    }) { Text("丢弃") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("取消") } })
            }
        }
    }
}
