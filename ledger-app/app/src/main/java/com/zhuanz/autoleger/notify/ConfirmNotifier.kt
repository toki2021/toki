package com.zhuanz.autoleger.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.zhuanz.autoleger.MainActivity
import com.zhuanz.autoleger.R

/** 待确认入账通知的构建与发布 */
object ConfirmNotifier {

    const val CHANNEL_ID = "confirm_entry"
    /** 通知 id 与待处理条目 id 绑定，便于撤销 */
    const val ID_OFFSET = 20000

    const val ACTION_CONFIRM = "com.zhuanz.autoleger.action.CONFIRM_ENTRY"
    const val ACTION_DISMISS = "com.zhuanz.autoleger.action.DISMISS_ENTRY"
    const val EXTRA_PENDING_ID = "pending_id"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "待确认入账", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "收到支付通知后请你确认入账" }
        manager.createNotificationChannel(channel)
    }

    fun pendingIdToNotificationId(pendingId: Long) = ID_OFFSET + pendingId.toInt()

    fun postConfirmNotification(
        context: Context,
        pendingId: Long,
        parsed: PaymentParser.Parsed,
        time: Long,
    ) {
        ensureChannel(context)
        val amountText = "¥" + String.format("%.2f", parsed.amountCents / 100.0)
        val titleText = if (parsed.isRefund) "收到退款 $amountText" else "支出 $amountText"

        val confirmIntent = Intent(context, ConfirmActionReceiver::class.java).apply {
            action = ACTION_CONFIRM
            putExtra(EXTRA_PENDING_ID, pendingId)
        }
        val dismissIntent = Intent(context, ConfirmActionReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_PENDING_ID, pendingId)
        }
        val editIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_PENDING_ID, pendingId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_receipt)
            .setContentTitle(titleText)
            .setContentText("${parsed.merchant} · 点「入账」直接记下这笔")
            .setWhen(time)
            .setAutoCancel(true)
            .addAction(0, "入账", PendingIntent.getBroadcast(
                context, pendingId.toInt(), confirmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "修改后入账", PendingIntent.getActivity(
                context, pendingId.toInt(), editIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "忽略", PendingIntent.getBroadcast(
                context, pendingId.toInt(), dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setContentIntent(PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        manager.notify(pendingIdToNotificationId(pendingId), notification)
    }

    fun cancel(context: Context, pendingId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(pendingIdToNotificationId(pendingId))
    }
}
