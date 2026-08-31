package com.zhuanz.autoleger.notify

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.zhuanz.autoleger.BuildConfig
import com.zhuanz.autoleger.LedgerAppProvider
import com.zhuanz.autoleger.data.AppContainer
import com.zhuanz.autoleger.data.PENDING_CONFIRM
import com.zhuanz.autoleger.data.PendingEntryEntity
import com.zhuanz.autoleger.data.SOURCE_NOTIFICATION
import com.zhuanz.autoleger.data.TransactionEntity
import com.zhuanz.autoleger.data.MerchantFilters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 读屏 + 截屏 OCR 双引擎：
 * 1. 无障碍树读取（原生页面有效，成本低）
 * 2. 树里读不到时（微信 H5 页面不对无障碍暴露内容），自动截屏 + 端上 OCR 识别文字
 *
 * 截屏只在"支付后几分钟内存在可补全的账单"时才触发，平时浏览微信不会截屏；
 * 截图即用即删，识别结果（商户/金额）入库，图片本身不落盘。
 */
class BillReaderService : AccessibilityService() {

    companion object {
        private const val TAG = "BillReader"
        @Volatile var instance: BillReaderService? = null
            private set

        /** 本次 OCR 的目标待补全条目 id：由通知监听精确指定，OCR 结果只补这一条，避免错配 */
        @Volatile var targetPendingId: Long? = null
            private set

        /** 通知监听在创建待确认条目后调用，触发一次截屏识别，并精确绑定目标条目 */
        fun requestCapture(pendingId: Long? = null) {
            targetPendingId = pendingId
            instance?.scheduleRead(1500)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 这些词出现在"商户名"里说明它其实是通知句式/按钮词（如"你有一笔 的支出"、"完成"），
    // 同样视为可补全对象
    private val garbageWords = listOf(
        "支付", "付款", "收款", "支出", "收入", "一笔", "成功", "完成",
        "账单", "提醒", "领取", "积分", "未知商户",
    )

    private fun isEnrichableMerchant(m: String?): Boolean =
        m == null || m in MerchantFilters.genericMerchants(applicationContext) || garbageWords.any { it in m }
    private val handler = Handler(Looper.getMainLooper())
    private val textRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    @Volatile private var lastPkg: String? = null
    @Volatile private var lastSnapshot: List<String>? = null
    @Volatile private var retries = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (BuildConfig.DEBUG) Log.d(TAG, "connected, capabilities=${serviceInfo.capabilities}")
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacks(readRunnable)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    fun scheduleRead(delayMs: Long) {
        handler.removeCallbacks(readRunnable)
        handler.postDelayed(readRunnable, delayMs)
    }

    // 防抖 + 重试：结果页可能先显示"正在加载"数秒，失败后继续重试约 12 秒
    private val readRunnable: Runnable = Runnable {
        val pkg = lastPkg ?: return@Runnable
        scope.launch {
            // 引擎一：无障碍树（原生页面）
            val texts = lastSnapshot ?: readWindowFor(pkg)
            lastSnapshot = null
            var bill = if (texts.isEmpty()) null else BillPageParser.parse(texts)
            if (bill != null) {
                retries = 0
                handleBill(bill, fromOcr = false, targetPendingId)
                return@launch
            }
            // 引擎二：截屏 OCR。仅当窗口树几乎为空时（H5 页面的特征，
            // 如微信/支付宝结算页）才截屏；聊天列表等文字丰富的页面自动跳过，
            // 隐私上绝不截取聊天内容，电量上也只产生偶尔一次截屏。
            if (texts.size > 8) {
                retries = 0
                return@launch
            }
            bill = screenshotOcr()
            if (bill != null) {
                retries = 0
                // OCR 模式只补全已有记录，不凭空建账（避免把账单列表页等误识别成新账单）
                handleBill(bill, fromOcr = true, targetPendingId)
                return@launch
            }
            if (retries < 8) {
                retries++
                scheduleRead(1500)
            } else {
                retries = 0
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != "com.tencent.mm" && pkg != "com.eg.android.AlipayGphone") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        // 窗口切换事件：同步快照新窗口的文本（原生页面有效；事件对象会被回收不能留引用）
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.source?.let { src ->
                val texts = mutableListOf<String>()
                walkNode(src, texts, 0)
                if (texts.isNotEmpty()) lastSnapshot = texts
            }
        }
        lastPkg = pkg
        scheduleRead(350)
    }

    override fun onInterrupt() = Unit

    private fun walkNode(node: AccessibilityNodeInfo?, texts: MutableList<String>, depth: Int) {
        if (node == null || depth > 30 || texts.size > 300) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
        for (i in 0 until node.childCount) walkNode(node.getChild(i), texts, depth + 1)
    }

    /** 只读微信/支付宝自己的窗口，绝不读桌面/通知横幅/其他 App */
    private fun readWindowFor(targetPkg: String): List<String> {
        rootInActiveWindow?.takeIf { it.packageName == targetPkg }?.let { root ->
            val texts = mutableListOf<String>()
            walkNode(root, texts, 0)
            if (texts.isNotEmpty()) return texts
        }
        for (w in windows) {
            val r = try { w.root } catch (e: Exception) { null } ?: continue
            if (r.packageName != targetPkg) continue
            val texts = mutableListOf<String>()
            walkNode(r, texts, 0)
            if (texts.isNotEmpty()) return texts
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "tree: no window for $targetPkg")
        return emptyList()
    }

    /** 截屏 → OCR → 文本行。截图不落盘，位图识别后立即回收 */
    private suspend fun screenshotOcr(): BillPageParser.Bill? =
        suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            // 注意：必须先把 hardware bitmap 复制成普通位图，再关闭底层缓冲
                            val wrapped = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer, screenshot.colorSpace
                            )
                            val soft = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            screenshot.hardwareBuffer.close()
                            if (soft == null) {
                                cont.resume(null); return
                            }
                            textRecognizer.process(InputImage.fromBitmap(soft, 0))
                                .addOnSuccessListener { visionText: Text ->
                                    val lines = visionText.textBlocks
                                        .flatMap { it.lines }
                                        .map { it.text.trim() }
                                        .filter { it.isNotBlank() }
                                    // 隐私：release 下绝不输出 OCR 文本内容（含商户名等敏感信息）
                                    if (BuildConfig.DEBUG) Log.d(TAG, "ocr lines=${lines.size}: ${lines.joinToString("|").take(300)}")
                                    soft.recycle()
                                    if (cont.isActive) cont.resume(BillPageParser.parseOcr(lines))
                                }
                                .addOnFailureListener { e ->
                                    if (BuildConfig.DEBUG) Log.d(TAG, "ocr fail: ${e.message}")
                                    soft.recycle()
                                    if (cont.isActive) cont.resume(null)
                                }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "screenshot fail: $errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    })
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.d(TAG, "screenshot error: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    /** 是否存在值得截屏补全的近期记录（支付后几分钟内的待确认/泛称商户账单） */
    private suspend fun hasEnrichableRecent(): Boolean {
        val container = (applicationContext as LedgerAppProvider).container
        val now = System.currentTimeMillis()
        val hasPending = container.pendingEntryDao.observeAll().first().any {
            now - it.time < 10 * 60_000L && isEnrichableMerchant(it.merchant)
        }
        if (hasPending) return true
        return container.transactionDao.observeAll().first().any {
            now - it.time < 30 * 60_000L &&
                it.source == SOURCE_NOTIFICATION &&
                isEnrichableMerchant(it.merchant)
        }
    }

    private suspend fun handleBill(
        bill: BillPageParser.Bill,
        fromOcr: Boolean = false,
        targetPendingId: Long? = null,
    ) {
        val container = (applicationContext as LedgerAppProvider).container
        val now = System.currentTimeMillis()

        // 0) 目标待补全条目精确关联：通知监听刚创建的那条。
        //    只补这一条，绝不因为"金额相同"就去覆盖别的记录——这是同金额多笔记账错配的来源。
        if (targetPendingId != null) {
            val target = container.pendingEntryDao.getById(targetPendingId)
            val contentPkg = rootInActiveWindow?.packageName?.toString()
            if (target != null && targetMatches(target, bill, contentPkg)) {
                enrichPending(container, target, bill, now)
            }
            clearTarget(targetPendingId)
            return
        }

        // 1) 10 分钟内同金额且金额唯一的待确认条目 → 补全商户。
        //    金额相同但有多条待确认时无法确定是哪笔，宁可不补（宁可少记）
        val pendingMatches = container.pendingEntryDao.observeAll().first().filter {
            it.amountCents == bill.amountCents && now - it.time < 10 * 60_000L
        }
        if (pendingMatches.size == 1) {
            enrichPending(container, pendingMatches.first(), bill, now)
            return
        }

        // 2) 30 分钟内同金额、商户是泛称、来源为通知的已入账 → 直接补全。
        //    金额必须唯一：同金额多笔无法确定是哪笔，宁可不改也不能错配/重复
        val candidates = container.transactionDao.observeAll().first().filter {
            it.amountCents == bill.amountCents &&
                now - it.time < 30 * 60_000L &&
                it.source == SOURCE_NOTIFICATION &&
                isEnrichableMerchant(it.merchant)
        }
        val tx = candidates.singleOrNull()
        if (tx != null) {
            val merchant = captureMerchant(bill) ?: return
            val category = container.matchCategory(merchant)
            container.transactionDao.update(
                tx.copy(
                    merchant = merchant,
                    categoryId = category?.id ?: tx.categoryId,
                )
            )
            return
        }

        // 3) 都没有 → 新建待确认条目并弹确认通知。
        //    OCR 模式必须命中强页面特征（支付成功/凭证页）才允许建账，
        //    账单列表页等只有弱特征的页面会被拦截，绝不凭空创建。
        if (fromOcr && !bill.strongPage) return
        val id = container.pendingEntryDao.insert(
            PendingEntryEntity(
                packageName = rootInActiveWindow?.packageName?.toString() ?: "unknown",
                title = "账单读取",
                text = "读屏补全：${bill.merchant} ¥${"%.2f".format(bill.amountCents / 100.0)}",
                amountCents = bill.amountCents,
                merchant = bill.merchant,
                status = PENDING_CONFIRM,
                time = now,
            )
        )
        ConfirmNotifier.postConfirmNotification(
            applicationContext, id,
            PaymentParser.Parsed(bill.amountCents, bill.merchant, isRefund = false),
            now,
        )
    }

    /** 目标条目是否匹配本次 OCR：金额未解析的可由 OCR 补全，已解析的须金额一致、来源一致 */
    private fun targetMatches(
        target: PendingEntryEntity,
        bill: BillPageParser.Bill,
        contentPkg: String?,
    ): Boolean =
        (target.amountCents == null || target.amountCents == bill.amountCents) &&
            (contentPkg == null || contentPkg == "unknown" || target.packageName == contentPkg)

    /** OCR 读回我们自己的确认通知横幅（含"入账/确认/点按"等按钮词）属于反向污染，丢弃 */
    private fun captureMerchant(bill: BillPageParser.Bill): String? {
        val m = bill.merchant
        if (m.isNullOrBlank()) return null
        if (listOf("入账", "入帐", "确认", "点按", "撤销", "忽略").any { it in m }) return null
        return m
    }

    /** 用 OCR 结果补全一条待确认条目（商户 + 未解析时的金额），并重发确认通知 */
    private suspend fun enrichPending(
        container: AppContainer,
        pending: PendingEntryEntity,
        bill: BillPageParser.Bill,
        now: Long,
    ) {
        val merchant = captureMerchant(bill) ?: return
        val amount = pending.amountCents ?: bill.amountCents
        if (pending.merchant == merchant && pending.amountCents == amount) return
        container.pendingEntryDao.insert(
            pending.copy(
                merchant = merchant,
                amountCents = amount,
                status = PENDING_CONFIRM,
            )
        )
        ConfirmNotifier.postConfirmNotification(
            applicationContext, pending.id,
            PaymentParser.Parsed(amount, merchant, isRefund = pending.text.contains("退款")),
            now,
        )
    }

    private fun clearTarget(id: Long?) {
        if (targetPendingId == id) targetPendingId = null
    }
}
