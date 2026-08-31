package com.zhuanz.autoleger.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zhuanz.autoleger.LedgerAppProvider
import com.zhuanz.autoleger.MainActivity
import com.zhuanz.autoleger.R
import com.zhuanz.autoleger.data.PendingEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 流动云胶囊前台服务。
 *
 * 当有待处理条目时启动，在 ColorOS 流动云区域显示胶囊：
 *   "¥14.14 待确认" +  ⟳ 图标
 * 点胶囊展开后可见"重新识别"按钮。
 *
 * 无需接入 Realme 闭源 SDK——ColorOS 14+ 会自动将前台服务的持续通知
 * 渲染为摄像头位置的胶囊形态。
 */
class FluidCloudService : Service() {

    companion object {
        private const val TAG = "FluidCloud"
        private const val NOTIFICATION_ID = 10001
        const val CHANNEL_ID = "fluid_cloud"
        const val ACTION_RECOGNIZE = "com.zhuanz.autoleger.action.FLUID_RECOGNIZE"
        const val EXTRA_PENDING_ID = "pending_id"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FluidCloudService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FluidCloudService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val container = (applicationContext as LedgerAppProvider).container
            val pending = container.pendingEntryDao.observeAll().first()
            val latest = pending.maxByOrNull { it.time }
            if (latest == null || latest.amountCents == null) {
                stopSelf()
                return@launch
            }
            startForeground(NOTIFICATION_ID, buildNotification(latest))
            // 每 30 秒检查一次，无待处理条目（金额不为 null 的）时自动停止
            while (true) {
                delay(30_000)
                val current = container.pendingEntryDao.observeAll().first()
                val hasValid = current.any { it.amountCents != null }
                if (!hasValid) {
                    stopSelf()
                    return@launch
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(pending: PendingEntryEntity): Notification {
        val amountText = "¥%.2f".format(pending.amountCents!! / 100.0)
        val merchant = pending.merchant ?: pending.title
        val title = "$amountText 待确认"
        val text = "$merchant · 点按查看详情"

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val recognizeIntent = Intent(this, FluidCloudReceiver::class.java).apply {
            action = ACTION_RECOGNIZE
            putExtra(EXTRA_PENDING_ID, pending.id)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_receipt)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                0, "重新识别",
                PendingIntent.getBroadcast(
                    this, pending.id.toInt(), recognizeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "流动云", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "待处理入账的流动云胶囊提示"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}