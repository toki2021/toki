package com.zhuanz.autoleger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.autoleger.data.CategoryEntity
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
fun AddEntrySheet(onDismiss: () -> Unit) {
    val container = rememberContainer()
    val scope = rememberCoroutineScope()
    val categories by container.categoryDao.observeAll().collectAsState(initial = emptyList())

    var amountText by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<CategoryEntity?>(null) }

    val cents = ((amountText.toDoubleOrNull() ?: 0.0) * 100).toLong()

    fun onKey(key: String) {
        when (key) {
            "清空" -> amountText = ""
            "⌫" -> amountText = amountText.dropLast(1)
            "." -> if (!amountText.contains(".")) amountText += if (amountText.isEmpty()) "0." else "."
            else -> {
                // 最多两位小数
                val dot = amountText.indexOf('.')
                if (dot == -1 || amountText.length - dot - 1 < 2) {
                    if (!(amountText.isEmpty() && key == "0")) amountText += key
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("记一笔", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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

            // 分类九宫格
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().height(200.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                userScrollEnabled = false,
            ) {
                items(categories, key = { it.id }) { cat ->
                    val selected = selectedCategory?.id == cat.id
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable {
                                selectedCategory = if (selected) null else cat
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
            Spacer(Modifier.height(14.dp))

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
                                .aspectRatio(if (wide) 4f else 1.35f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSave) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    if (isSave) {
                                        if (cents > 0) {
                                            scope.launch {
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
                                                onDismiss()
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
