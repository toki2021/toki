package com.zhuanz.autoleger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.zhuanz.autoleger.notify.ConfirmNotifier
import com.zhuanz.autoleger.ui.AutoLedgerApp
import com.zhuanz.autoleger.ui.UiVariant
import com.zhuanz.autoleger.ui.UiVariantViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Activity 级 ViewModel：与 Compose 树中 viewModel() 获取的是同一实例，
     * 因此这里对状态的修改会实时反映到 AutoLedgerTheme / AppNav / SettingsScreen。
     */
    private val uiVariantVm: UiVariantViewModel by viewModels()

    /** 待确认 id 深链事件：onCreate / onNewIntent 转发给 Compose 导航 */
    private val pendingDeepLinks = MutableSharedFlow<Long>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPostNotificationsIfNeeded()
        ConfirmNotifier.ensureChannel(this)
        uiVariantVm.load(this)
        // 调试/预览：am start --ez preview true --ei ui_variant N 直接进入预览并切换方案
        if (intent?.getBooleanExtra("preview", false) == true) {
            uiVariantVm.setPreview(true)
        }
        intent?.getIntExtra("ui_variant", -1)?.takeIf { it >= 0 }?.let { idx ->
            val v = UiVariant.fromIndex(idx)
            if (uiVariantVm.uiState.value.previewing) {
                uiVariantVm.setPreviewVariant(v)
            } else {
                uiVariantVm.apply(this, v)
            }
        }

        setContent {
            com.zhuanz.autoleger.ui.AutoLedgerTheme {
                AutoLedgerApp(deepLinkFlow = pendingDeepLinks)
            }
        }
        handlePendingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePendingIntent(intent)
    }

    private fun handlePendingIntent(intent: Intent?) {
        val pendingId = intent?.getLongExtra(ConfirmNotifier.EXTRA_PENDING_ID, -1L) ?: -1L
        if (pendingId > 0) pendingDeepLinks.tryEmit(pendingId)
    }

    private fun requestPostNotificationsIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
