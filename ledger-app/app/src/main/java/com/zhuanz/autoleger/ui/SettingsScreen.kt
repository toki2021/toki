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

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
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
                Text("界面风格", style = MaterialTheme.typography.titleMedium)
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
                        "预览界面方案",
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
            Text("通知监听权限", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (listenerEnabled) "✅ 已授权，可以监听微信/支付宝的支付通知"
                else "❌ 未授权。授权后 App 才能收到支付通知并生成待确认账单。",
                fontSize = 13.sp,
            )
            if (!listenerEnabled) {
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("去系统设置授权") }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            var genericPopup by remember {
                mutableStateOf(prefs.getBoolean("notify_popup_generic", false))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("金额通知也弹确认窗", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.Switch(
                    checked = genericPopup,
                    onCheckedChange = {
                        genericPopup = it
                        prefs.edit().putBoolean("notify_popup_generic", it).apply()
                    },
                )
            }
            Text(
                "关闭（推荐）：支付通知只悄悄记下金额不弹窗，等读屏读到商户后才弹确认通知。\n开启：收到支付通知立刻弹确认（只有金额，商户需要手动补）。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("读屏补全商户（无障碍）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (readerEnabled) "✅ 已开启。打开微信/支付宝账单详情页时自动读取收款方，补全商户和分类。"
                else "❌ 未开启。开启后，打开账单详情页会自动读取收款方商户（通知里通常只有金额）。仅读取账单页，不收集其他内容。",
                fontSize = 13.sp,
            )
            if (!readerEnabled) {
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("去系统设置开启") }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("数据与备份", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("账目数据仅保存在本机。WebDAV 云备份将在 V1.3 提供。", fontSize = 13.sp)
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("隐私说明", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "本 App 仅在你授权后，于本机解析微信/支付宝的支付通知与账单详情页：\n" +
                    "· 通知监听只处理支付相关通知，不读取其他内容；\n" +
                    "· 读屏/截屏只在你打开账单详情页且存在待补全账单时短暂触发，识别结果（商户/金额）即用即删，截图不落盘；\n" +
                    "· 所有账目与识别数据仅保存在本机，不上传任何服务器。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("自动记账 V1.0 · 通知监听 → 确认入账 → 自动分类", fontSize = 13.sp)
        }
    }
}
