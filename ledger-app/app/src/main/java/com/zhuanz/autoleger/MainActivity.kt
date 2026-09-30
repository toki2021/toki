package com.zhuanz.autoleger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.zhuanz.autoleger.notify.ConfirmNotifier
import com.zhuanz.autoleger.ui.AutoLedgerApp
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Channel buffers cold-start notifications until Compose starts collecting.
    private val pendingDeepLinks = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPostNotificationsIfNeeded()
        ConfirmNotifier.ensureChannel(this)
        // Retire only the old appearance preferences. Ledger and other settings are untouched.
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .remove("ui_variant").remove("ui_variant_v2").apply()

        setContent {
            com.zhuanz.autoleger.ui.AutoLedgerTheme {
                AutoLedgerApp(deepLinkFlow = pendingDeepLinks.receiveAsFlow())
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
        if (pendingId > 0) {
            pendingDeepLinks.trySend(pendingId)
            intent?.removeExtra(ConfirmNotifier.EXTRA_PENDING_ID)
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
