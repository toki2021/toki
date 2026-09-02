package com.zhuanz.autoleger.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zhuanz.autoleger.LedgerAppProvider
import com.zhuanz.autoleger.MainActivity
import com.zhuanz.autoleger.R
import com.zhuanz.autoleger.data.PendingEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 流动云胶囊前台服务。
 */
class FluidCloudService : Service() {

    companion object {
        private const val TAG = "FluidCloud"
        private const val NOTIFICATION_ID = 10001
        private const val PERSISTENT_NOTIFICATION_ID = 10002
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
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须立即 startForeground，否则 Android 14+ 会崩溃
        val placeholder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_receipt)
            .setContentTitle("加载中...")
            .setContentText("正在获取待处理条目")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, placeholder)
        }

        // 每次启动/刷新时取消旧任务，重新开始
        job?.cancel()
        job = scope.launch {
            try {
                val container = (applicationContext as LedgerAppProvider).container
                val pending = withTimeoutOrNull(8_000L) { container.pendingEntryDao.observeAll().first() }
                val latest = pending?.maxByOrNull { it.time }
                if (latest == null || latest.amountCents == null) {
                    stopSelf()
                    return@launch
                }
                // 用真实数据替换占位通知
                // 同时通过 NotificationManager 以不同 ID 发布，确保即使服务被系统停止后通知仍然可见
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(PERSISTENT_NOTIFICATION_ID, buildNotification(latest))
                // 每 30 秒检查一次，无待处理条目时自动停止
                while (true) {
                    delay(30_000)
                    val current = container.pendingEntryDao.observeAll().first()
                    val hasValid = current.any { it.amountCents != null }
                    if (!hasValid) {
                        stopSelf()
                        return@launch
                    }
                }
            } catch (_: Exception) {
                // 兜底：查询异常或页面切换时立刻停止，绝不永久挂着"加载中"占位通知
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
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
            .setColor(0xFF4C6EF5.toInt())
            .setContentTitle(title)
            .setContentText(text)
            // 默认展开显示，"重新识别"按钮直接可见，无需点展开箭头
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n点下方\"重新识别\"可修正这笔账单"))
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
        // 使用 IMPORTANCE_DEFAULT 使通知在状态栏可见
        val channel = NotificationChannel(
            CHANNEL_ID, "流动云", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "待处理入账的流动云胶囊提示"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}