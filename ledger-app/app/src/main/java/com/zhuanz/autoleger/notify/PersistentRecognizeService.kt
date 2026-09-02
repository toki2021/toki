package com.zhuanz.autoleger.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zhuanz.autoleger.MainActivity
import com.zhuanz.autoleger.R

/**
 * 常驻通知栏的"记账助手"。
 * 任何时候下拉通知栏都能找到它，并点"重新识别当前"对当前支付页重新 OCR，
 * 作为自动识别失败（如 NFC 碰一碰支付）时的兜底入口。
 */
class PersistentRecognizeService : Service() {

    companion object {
        const val CHANNEL_ID = "persistent_recognize"
        const val RESCAN_ACTION = "com.zhuanz.autoleger.action.RESCAN_CURRENT"
        private const val NOTIFICATION_ID = 10004

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PersistentRecognizeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PersistentRecognizeService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(
            CHANNEL_ID, "记账助手", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "常驻通知栏，用于对支付页手动重新识别"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        // onTaskRemoved 之外的正常停止无需清理
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉时保持常驻通知，避免误杀导致漏识别
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundCompat() {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // 通知栏上的"重新识别当前"按钮
        val rescanTap = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val rescanAction = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, PersistentRescanReceiver::class.java)
                .setAction(PersistentRecognizeService.RESCAN_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_receipt)
            .setColor(0xFF4C6EF5.toInt())
            .setContentTitle(getString(R.string.persist_notice_title))
            .setContentText(getString(R.string.persist_notice_desc))
            // 收起态点整张卡片即触发重扫（部分 ROM 即使 BigTextStyle 也默认收起，
            // 只有 action 才能保证"不用点箭头"就触发）。展开态额外提供"打开明细"。
            .setContentIntent(rescanAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, "打开明细", rescanTap)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }
}

/** 常驻通知上"重新识别当前"的点击接收器 */
class PersistentRescanReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PersistentRecognizeService.RESCAN_ACTION) return
        val pending = goAsync()
        val main = android.os.Handler(android.os.Looper.getMainLooper())

        // 第 1 步：先收起通知栏（否则截屏会把"运营商/网速"等通知栏内容一并拍到）
        main.post {
            try {
                PaymentNotificationListener.instance?.collapseShade()
            } catch (_: Exception) {
            }
            try {
                context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            } catch (_: Exception) {
            }
        }

        // 第 2 步：稍等通知栏彻底收起后再触发识别
        main.postDelayed({
            val msg = when (BillReaderService.requestReScan()) {
                0 -> "正在识别当前页面…"
                else -> "识别服务未运行，请在设置→辅助功能里开启自动记账"
            }
            try {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
            try {
                pending.finish()
            } catch (_: Exception) {
            }
        }, BILL_RE_SCAN_DELAY_MS)
    }

    companion object {
        private const val BILL_RE_SCAN_DELAY_MS = 900L
    }
}