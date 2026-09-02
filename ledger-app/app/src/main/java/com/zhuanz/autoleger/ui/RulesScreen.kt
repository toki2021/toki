package com.zhuanz.autoleger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhuanz.autoleger.data.RuleEntity
import kotlinx.coroutines.launch

/** 分类大类分组，用于规则页折叠浏览 */
private val CATEGORY_GROUPS = listOf("饮食", "出行", "购物", "居住", "娱乐", "健康", "通讯", "其他")

private fun categoryGroupOf(name: String): String {
    val n = name.trim()
    return when {
        n.isEmpty() -> "其他"
        listOf("吃", "餐", "食", "饮", "甜", "咖啡", "茶", "外卖", "餐厅", "快餐", "小吃", "生鲜").any { n.contains(it) } -> "饮食"
        listOf("行", "通", "地铁", "公交", "打车", "加油", "停车", "机票", "火车", "出租", "油").any { n.contains(it) } -> "出行"
        listOf("购", "买", "淘", "电商", "京东", "淘宝", "拼", "超市", "商场", "店", "百货", "零售", "装", "数码", "家电").any { n.contains(it) } -> "购物"
        listOf("住", "房", "租", "物业", "水", "电", "燃气", "家居", "维修").any { n.contains(it) } -> "居住"
        listOf("娱乐", "娱", "影", "视", "游戏", "乐", "旅游", "票", "音乐", "健身", "运动", "演出").any { n.contains(it) } -> "娱乐"
        listOf("医", "健", "药", "病", "理疗", "体检", "保健").any { n.contains(it) } -> "健康"
        listOf("讯", "通讯", "话费", "流量", "网络", "宽带", "手机").any { n.contains(it) } -> "通讯"
        else -> "其他"
    }
}

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
            // 按大类分组，组内仍按优先级排序
            val grouped = remember(rules, categories) {
                CATEGORY_GROUPS.mapNotNull { g ->
                    val gRules = rules
                        .filter { r ->
                            val cn = categories.firstOrNull { it.id == r.categoryId }?.name ?: ""
                            categoryGroupOf(cn) == g
                        }
                        .sortedBy { it.priority }
                    if (gRules.isEmpty()) null else g to gRules
                }
            }
            val collapsed = remember { mutableStateMapOf<String, Boolean>() }
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (group, gRules) ->
                    val open = collapsed[group] != true
                    item(key = "head_$group") {
                        GroupHeader(
                            name = group,
                            showCount = gRules.size,
                            open = open,
                            onClick = { collapsed[group] = open },
                        )
                    }
                    if (open) {
                        items(gRules, key = { it.id }) { rule ->
                            val cat = categories.firstOrNull { it.id == rule.categoryId }
                            Row(
                                Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CategoryIcon(cat?.name ?: "?", size = 28.dp)
                                        Spacer(Modifier.width(10.dp))
                                        Text("「${rule.pattern}」→ ${cat?.name ?: "?"}")
                                    }
                                }
                                IconButton(onClick = { scope.launch { container.ruleDao.delete(rule) } }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除规则", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
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

@Composable
private fun GroupHeader(name: String, showCount: Int, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (open) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        Text(
            "$showCount 条",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
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
