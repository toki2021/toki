package com.zhuanz.autoleger.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.sp
import com.zhuanz.autoleger.data.CategoryEntity
import androidx.compose.ui.res.stringResource
import com.zhuanz.autoleger.R
import kotlinx.coroutines.launch

/** 按 Unicode 码点截断，避免把多字符 emoji（国旗/肤色/ZWJ 组合）截成乱码 */
private fun String.takeCodePoints(maxCodePoints: Int): String {
    var index = 0
    var count = 0
    while (index < length) {
        if (count == maxCodePoints) return substring(0, index)
        index += Character.charCount(codePointAt(index))
        count++
    }
    return this
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val container = rememberContainer()
    val categories by container.categoryDao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.category_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.category_add))
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(categories, key = { it.id }) { cat ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryIcon(cat.name, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(cat.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { scope.launch { container.categoryDao.delete(cat) } }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.category_delete))
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var emoji by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.category_add)) },
            text = {
                Column {
                    Text(stringResource(R.string.category_add_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.category_name)) }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(4.dp))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            container.categoryDao.insert(
                                CategoryEntity(name = name.trim(), emoji = "🏷️")
                            )
                            showAdd = false
                        }
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
