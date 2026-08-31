package com.zhuanz.autoleger.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.zhuanz.autoleger.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiVariantVm: UiVariantViewModel = viewModel(),
) {
    val context = LocalContext.current
    // 与 AppNav/Theme 共享同一 Activity 级实例，选择实时生效
    val uiVariantState by uiVariantVm.uiState.collectAsState()
    var listenerEnabled by remember { mutableStateOf(false) }
    var readerEnabled by remember { mutableStateOf(false) }
    // 每次回到前台重新读取权限状态，覆盖从系统设置授权/取消后返回的场景
    LifecycleResumeEffect(Unit) {
        listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        readerEnabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("com.zhuanz.autoleger/.notify.BillReaderService") == true
        onPauseOrDispose { }
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
            androidx.compose.animation.AnimatedVisibility(visible = styleExpanded) {
                Column {
                    Text(
                        stringResource(R.string.settings_preview_variants),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
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
            Text(stringResource(R.string.settings_data_backup), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_data_backup_desc), fontSize = 13.sp)
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
