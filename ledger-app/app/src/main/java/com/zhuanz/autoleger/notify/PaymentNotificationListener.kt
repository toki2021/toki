package com.zhuanz.autoleger.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.zhuanz.autoleger.BuildConfig
import com.zhuanz.autoleger.LedgerAppProvider
import com.zhuanz.autoleger.data.PENDING_CONFIRM
import com.zhuanz.autoleger.data.PENDING_UNPARSED
import com.zhuanz.autoleger.data.PendingEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 监听微信/支付宝的通知，把疑似支付通知解析后生成"待确认入账"通知。
 * 用户点通知上的【入账】直接入库，点【修改】进编辑页，点【忽略】丢弃；
 * 解析失败的进入 App 内"待处理"列表手动补录。
 */
class PaymentNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        // App 重启后通知监听服务重新绑定，此时系统不会重放已存在的通知。
        // 主动检查微信/支付宝当前活跃的通知，防止重启期间错过的支付通知丢失。
        try {
            for (sbn in activeNotifications) {
                val pkg = sbn.packageName ?: continue
                if (pkg != "com.tencent.mm" && pkg != "com.eg.android.AlipayGphone") continue
                onNotificationPosted(sbn)
            }
        } catch (_: Exception) {
            // 安全兜底：getActiveNotifications 在某些系统上可能抛异常
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // 去重检查与入库必须串行：两条相同通知并发回调时，不加锁会双双通过去重检查
    private val insertMutex = Mutex()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        // 隐私：通知原文（含商户名）只在 debug 包输出，release 一律不打
        if (BuildConfig.DEBUG && (pkg == "com.tencent.mm" || pkg == "com.eg.android.AlipayGphone")) {
            Log.d("PayNotify", "posted pkg=$pkg title=$title text=$text")
        }
        if (!PaymentParser.looksLikePayment(pkg, title, text)) return

        val postedAt = sbn.postTime
        scope.launch {
            val container = (applicationContext as LedgerAppProvider).container
            insertMutex.withLock {
            // 去重：同一通知常被系统多次回调（合并通知/更新），60 秒内同内容的不再重复入列
            val recentPendings = container.pendingEntryDao.observeAll().first()
            if (recentPendings.any {
                    it.packageName == pkg && it.title == title && it.text == text &&
                        postedAt - it.time < 60_000
                }
            ) return@launch
            // 用户可能已通过确认通知入账：2 分钟内同原文的账单存在则不再建待确认
            val rawText = "$title $text"
            val recentTx = container.transactionDao.observeAll().first()
            if (recentTx.any { it.rawText == rawText && postedAt - it.time < 120_000 }) return@launch

            val parsed = PaymentParser.parse(title, text)
            val entry = com.zhuanz.autoleger.data.PendingEntryEntity(
                packageName = pkg,
                title = title,
                text = text,
                amountCents = parsed?.amountCents,
                merchant = parsed?.merchant,
                status = if (parsed != null) com.zhuanz.autoleger.data.PENDING_CONFIRM
                else com.zhuanz.autoleger.data.PENDING_UNPARSED,
                time = postedAt,
            )
            val id = container.pendingEntryDao.insert(entry)
            // 启动流动云胶囊（ColorOS 摄像头位置显示"¥14.14 待确认"）
            FluidCloudService.start(applicationContext)
            // 静默捕获：只记金额时不弹窗（避免骚扰），读到商户时才由读屏侧弹出确认
            val popup = applicationContext.getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("notify_popup_generic", false)
            if (parsed != null && popup) {
                ConfirmNotifier.postConfirmNotification(applicationContext, id, parsed, postedAt)
            }
            // 支付结果页此刻正在弹出，触发截屏 OCR 读取商户并补全。
            // 把"待补全的目标"精确绑定到刚创建的这条 pending，避免连续多条通知时错配到别的账上
            if (parsed != null) {
                // 读屏服务被系统清理时（进程活着但服务已解绑），提醒用户一键恢复
                if (BillReaderService.instance == null) {
                    ReaderOfflineNotifier.postIfNeeded(applicationContext)
                }
                BillReaderService.requestCapture(id)
            }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit
}
