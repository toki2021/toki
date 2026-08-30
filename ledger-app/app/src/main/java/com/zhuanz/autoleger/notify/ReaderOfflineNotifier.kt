package com.zhuanz.autoleger.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * 读屏服务被系统清理后的恢复提醒。
 * ColorOS 等系统会杀后台无障碍服务；此通知让用户一键回到开关页重新启用。
 */
object ReaderOfflineNotifier {

    private const val ID = 50001
    private var lastPostedAt = 0L

    fun postIfNeeded(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPostedAt < 10 * 60_000L) return  // 10 分钟内只提醒一次
        lastPostedAt = now

        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            context, 50001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, ConfirmNotifier.CHANNEL_ID)
            .setSmallIcon(com.zhuanz.autoleger.R.drawable.ic_receipt)
            .setContentTitle("自动记账：读屏服务已掉线")
            .setContentText("点按重新开启，否则无法自动读取商户名")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("系统清理了后台服务。点按这条通知，在列表里重新勾选「账单读屏补全」即可恢复。"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(ID, n)
        } catch (_: SecurityException) {
        }
    }
}
