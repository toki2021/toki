package com.zhuanz.autoleger.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.zhuanz.autoleger.data.toCents
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.autoleger.data.SOURCE_MANUAL
import com.zhuanz.autoleger.data.TransactionEntity
import com.zhuanz.autoleger.data.TYPE_EXPENSE
import com.zhuanz.autoleger.data.EntryConfirmer
import kotlinx.coroutines.launch

/**
 * 记一笔：底部弹出半屏浮层 + 大按钮数字键盘 + 分类九宫格。
 * 记完自动收起，首页流水实时刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntrySheet(onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val container = rememberContainer()
    val scope = rememberCoroutineScope()
    val categories by container.categoryDao.observeAll().collectAsState(initial = emptyList())

    var amountText by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }
    var selectedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val cents = amountText.toCents() ?: 0L

    fun onKey(key: String) {
        when (key) {
            "清空" -> amountText = ""
            "⌫" -> amountText = amountText.dropLast(1)
            "." -> if (!amountText.contains(".")) amountText += if (amountText.isEmpty()) "0." else "."
            else -> {
                // 最多两位小数
                val dot = amountText.indexOf('.')
                if (amountText.length < 12 && (dot == -1 || amountText.length - dot - 1 < 2)) {
                    if (!(amountText.isEmpty() && key == "0")) amountText += key
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.imePadding().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("记一笔", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("今天 · 支出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            // 金额显示屏
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 20.dp, horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = if (amountText.isEmpty()) "¥0.00" else "¥$amountText",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                    color = if (amountText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(14.dp))

            // 分类九宫格：随整页滚动，图标再多也能滑到位
            categories.chunked(4).forEach { rowCats ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowCats.forEach { cat ->
                        val selected = selectedCategory?.id == cat.id
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    selectedCategoryId = if (selected) null else cat.id
                                    if (!selected) {
                                        // 选中分类自动带上商户联想：留空则用分类名
                                        if (merchant.isBlank()) merchant = cat.name
                                    }
                                }
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CategoryIcon(cat.name, size = 34.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                cat.name,
                                fontSize = 12.sp,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 商户输入
            androidx.compose.material3.OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("商户 / 备注（可选）") },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))

            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            // 数字键盘
            val keys = listOf(
                listOf("1", "2", "3", "⌫"),
                listOf("4", "5", "6", "清空"),
                listOf("7", "8", "9", "."),
                listOf("0", "保存"),
            )
            keys.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { key ->
                        val isSave = key == "保存"
                        val wide = row.size == 2
                        Box(
                            Modifier
                                .weight(if (wide) 2f else 1f)
                                .heightIn(min = 56.dp)
                                .semantics { contentDescription = if (isSave) "保存账单" else "输入$key" }
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSave) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable(enabled = !saving && (!isSave || cents > 0)) {
                                    if (isSave) {
                                        if (cents > 0) {
                                            saving = true
                                            scope.launch {
                                                try {
                                                val merchantFinal = merchant.ifBlank {
                                                    selectedCategory?.name ?: "未知商户"
                                                }
                                                val categoryId = selectedCategory?.id
                                                    ?: EntryConfirmer.categoryFor(container, merchantFinal)
                                                container.transactionDao.insert(
                                                    TransactionEntity(
                                                        type = TYPE_EXPENSE,
                                                        amountCents = cents,
                                                        merchant = merchantFinal,
                                                        categoryId = categoryId,
                                                        time = System.currentTimeMillis(),
                                                        source = SOURCE_MANUAL,
                                                    )
                                                )
                                                onSaved()
                                                onDismiss()
                                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                                catch (_: Exception) { error = "保存失败，请重试" }
                                                finally { saving = false }
                                            }
                                        }
                                    } else {
                                        onKey(key)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                key,
                                fontSize = if (isSave) 16.sp else 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isSave) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
