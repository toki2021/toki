package com.zhuanz.autoleger.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.zhuanz.autoleger.LedgerAppProvider
import com.zhuanz.autoleger.R
import com.zhuanz.autoleger.data.BackupManager
import com.zhuanz.autoleger.data.CsvImporter
import com.zhuanz.autoleger.data.XlsxImporter
import com.zhuanz.autoleger.backup.BackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiVariantVm: UiVariantViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appContainer = (context.applicationContext as LedgerAppProvider).container
    val uiVariantState by uiVariantVm.uiState.collectAsState()
    var listenerEnabled by remember { mutableStateOf(false) }
    var readerEnabled by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        readerEnabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("com.zhuanz.autoleger/.notify.BillReaderService") == true
        onPauseOrDispose { }
    }

    // —— 导出备份 ——
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = BackupManager.export(appContainer)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray())
                        }
                    }
                    val date = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault())
                        .format(Date())
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_export_success, "autoleger_$date.json"),
                        Toast.LENGTH_SHORT,
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_import_error, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    // —— 恢复备份 ——
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.bufferedReader().readText()
                        } ?: throw Exception("无法读取文件")
                    }
                    val msg = BackupManager.importJson(appContainer, json)
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_import_error, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    // —— 导入账单（CSV / xlsx 按内容魔数自动识别） ——
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw Exception("无法读取文件")
                        // 按 ZIP 魔数 PK\x03\x04 识别 xlsx，不依赖文件名（部分文件选择器拿不到扩展名）
                        val isXlsx = bytes.size >= 4 &&
                            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
                        if (isXlsx) XlsxImporter.parse(appContainer, bytes.inputStream())
                        else CsvImporter.parse(appContainer, bytes.inputStream())
                    }
                    if (result.errors.isNotEmpty()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.backup_import_error, result.errors.first()),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_import_csv_success, result.imported, result.skipped),
                        Toast.LENGTH_SHORT,
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_import_error, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // —— 界面风格：抽拉折叠列表 ——
            var styleExpanded by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { styleExpanded = !styleExpanded }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_ui_style), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        uiVariantState.current.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (styleExpanded) "▴" else "▾",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(visible = styleExpanded) {
                Column {
                    Text(
                        stringResource(R.string.settings_preview_variants),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { uiVariantVm.setPreview(true) }
                            .padding(vertical = 6.dp),
                    )
                    UiVariant.entries.forEach { v ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { uiVariantVm.apply(context, v) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = uiVariantState.current == v,
                                onClick = { uiVariantVm.apply(context, v) },
                            )
                            Text(v.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(stringResource(R.string.settings_notification_permission), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (listenerEnabled) R.string.settings_notification_granted
                    else R.string.settings_notification_denied
                ),
                fontSize = 13.sp,
            )
            if (!listenerEnabled) {
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.settings_grant_notification)) }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            var genericPopup by remember {
                mutableStateOf(prefs.getBoolean("notify_popup_generic", false))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_popup_generic), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.Switch(
                    checked = genericPopup,
                    onCheckedChange = {
                        genericPopup = it
                        prefs.edit().putBoolean("notify_popup_generic", it).apply()
                    },
                )
            }
            Text(
                stringResource(R.string.settings_popup_generic_desc),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(stringResource(R.string.settings_accessibility), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (readerEnabled) R.string.settings_reader_enabled
                    else R.string.settings_reader_disabled
                ),
                fontSize = 13.sp,
            )
            if (!readerEnabled) {
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.settings_grant_accessibility)) }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // —— 数据与备份 ——
            Text(stringResource(R.string.settings_data_backup), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_data_backup_desc),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val date = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault())
                            .format(Date())
                        exportLauncher.launch("autoleger_$date.json")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.backup_export)) }

                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.backup_import)) }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { csvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_import_csv)) }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.backup_csv_guide),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // —— 自动备份 ——
            val backupPrefs = context.getSharedPreferences("auto_backup", android.content.Context.MODE_PRIVATE)
            var autoBackupEnabled by remember { mutableStateOf(backupPrefs.getBoolean("enabled", false)) }
            val lastBackupTime = backupPrefs.getLong("last_backup_time", 0L)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.backup_auto), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.backup_auto_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = autoBackupEnabled,
                    onCheckedChange = {
                        autoBackupEnabled = it
                        BackupWorker.setEnabled(context, it)
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (lastBackupTime > 0) {
                    val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        .format(Date(lastBackupTime))
                    stringResource(R.string.backup_auto_last, date)
                } else {
                    stringResource(R.string.backup_auto_last_none)
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_privacy_desc),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_about_desc), fontSize = 13.sp)
        }
    }
}