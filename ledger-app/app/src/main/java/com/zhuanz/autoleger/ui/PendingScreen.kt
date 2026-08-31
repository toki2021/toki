package com.zhuanz.autoleger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.autoleger.data.EntryConfirmer
import com.zhuanz.autoleger.data.PENDING_CONFIRM
import com.zhuanz.autoleger.notify.ConfirmNotifier
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingScreen(onConfirm: (Long) -> Unit) {
    val container = rememberContainer()
    val context = LocalContext.current
    val pending by container.pendingEntryDao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(topBar = { TopAppBar(title = { Text("待处理通知") }) }) { padding ->
        if (pending.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                Text("没有待处理的通知")
                Text(
                    "收到微信/支付宝支付通知后会出现在这里\n解析成功的还会弹确认通知，点「入账」即可",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(pending, key = { it.id }) { entry ->
                    val hint = EntryConfirmer.extractCategoryHint(container, entry)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (entry.status == PENDING_CONFIRM)
                                        (hint.merchant ?: entry.title) + "  ¥" +
                                            String.format("%.2f", (hint.amountCents ?: 0) / 100.0)
                                    else "未解析 · ${entry.title}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    entry.text + " · " + fmt.format(Date(entry.time)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (entry.status == PENDING_CONFIRM) {
                                Button(onClick = {
                                    scope.launch {
                                        if (EntryConfirmer.confirm(container, entry)) {
                                            ConfirmNotifier.cancel(context, entry.id)
                                            container.pendingEntryDao.deleteById(entry.id)
                                        }
                                    }
                                }) { Text("入账") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { onConfirm(entry.id) }) { Text("改") }
                            } else {
                                TextButton(onClick = { onConfirm(entry.id) }) { Text("补录") }
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    ConfirmNotifier.cancel(context, entry.id)
                                    container.pendingEntryDao.deleteById(entry.id)
                                }
                            }) { Text("删") }
                        }
                    }
                }
            }
        }
    }
}
