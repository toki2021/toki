package com.zhuanz.autoleger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhuanz.autoleger.data.RuleEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(onManageCategories: () -> Unit) {
    val container = rememberContainer()
    val rules by container.ruleDao.observeAll().collectAsState(initial = emptyList())
    val categories by container.categoryDao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类规则") },
                actions = {
                    TextButton(onClick = onManageCategories) { Text("管理分类") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新增规则")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "商户名包含关键词时，自动归入对应分类。越靠前优先级越高。",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rules.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有规则，点 + 新增\n例如：美团 → 餐饮", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rules, key = { it.id }) { rule ->
                    val catName = categories.firstOrNull { it.id == rule.categoryId }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CategoryIcon(catName?.name ?: "?", size = 32.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("「${rule.pattern}」→ ${catName?.name ?: "?"}")
                            }
                        }
                        IconButton(onClick = { scope.launch { container.ruleDao.delete(rule) } }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除规则")
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddRuleDialog(
            categories = categories,
            onDismiss = { showAdd = false },
            onSave = { pattern, categoryId ->
                scope.launch {
                    container.ruleDao.insert(
                        RuleEntity(
                            pattern = pattern,
                            categoryId = categoryId,
                            priority = (container.ruleDao.maxPriority() ?: 0) + 1,
                        )
                    )
                    showAdd = false
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRuleDialog(
    categories: List<com.zhuanz.autoleger.data.CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
) {
    var pattern by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(categories.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增规则") },
        text = {
            Column {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("商户关键词") },
                    singleLine = true,
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedTextField(
                        value = selected?.name ?: "选择分类",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("目标分类") },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text("${cat.name}") },
                                onClick = {
                                    selected = cat
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onSave(pattern.trim(), it.id) } },
                enabled = pattern.isNotBlank() && selected != null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
