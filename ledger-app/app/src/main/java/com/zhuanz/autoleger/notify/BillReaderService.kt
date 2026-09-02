package com.zhuanz.autoleger.notify

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
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
        private const val COMP_WECHAT = "com.tencent.mm"
        private const val COMP_ALIPAY = "com.eg.android.AlipayGphone"
        @Volatile var instance: BillReaderService? = null
            private set

        /** 本次 OCR 的目标待补全条目 id：由通知监听精确指定，OCR 结果只补这一条，避免错配 */
        @Volatile var targetPendingId: Long? = null
            private set

        /** 收到"重新识别"请求（悬浮球/通知栏）：无条件对当前窗口 OCR 一次 */
        @Volatile var reScanRequested = false
            private set

        /** 通知监听在创建待确认条目后调用，触发一次截屏识别，并精确绑定目标条目 */
        fun requestCapture(pendingId: Long? = null) {
            targetPendingId = pendingId
            instance?.scheduleRead(1500)
        }

        /**
         * 常驻"重新识别当前屏幕"入口：通知栏按钮调用。
         * 不受下拉通知栏造成的瞬时焦点偏移影响——直接对当前屏幕 OCR 一次，
         * 识别成功则记账，识别不到则提示用户。OCR 结果走通用的"匹配/建档"路径。
         * @return 0=已触发识别；-1=识别服务未运行
         */
        fun requestReScan(): Int {
            val svc = instance ?: return -1
            // 以当前/最近的前台支付 App 作为 OCR 目标；即便此刻焦点在通知栏，
            // 也保留 lastPkg（最近一次支付页），OCR 直接拍当前屏。
            val fg = svc.rootInActiveWindow?.packageName?.toString()
            if (fg == COMP_WECHAT || fg == COMP_ALIPAY) svc.lastPkg = fg
            reScanRequested = true
            targetPendingId = null
            svc.scheduleRead(200)
            return 0
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 后台线程外的 UI 线程提示（进程内通用，不依赖 Activity） */
    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                // 读取服务被系统回收时可能拿不到 Context，忽略即可
            }
        }
    }

    private fun formatCents(cents: Long): String {
        val yuan = cents / 100.0
        return if (yuan == yuan.toLong().toDouble()) "%.0f".format(yuan) else "%.2f".format(yuan)
    }

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
        // 手动"重新识别"对准当前前台窗口，不受"仅限支付宝/微信"限制；
        // 自动路径仍用最近一次支付 App。
        val manual = reScanRequested
        reScanRequested = false
        val pkg = (if (manual) rootInActiveWindow?.packageName?.toString() ?: lastPkg else lastPkg)
            ?: return@Runnable
        scope.launch {
            // 通知侧解析出的实付金额是权威值：读屏/OCR 只确认页面并补商户，绝不改写金额
            // （"付14.60优惠0.46"的页面曾把小字号优惠金额当成实付，金额必须以通知为准）
            val container = (applicationContext as LedgerAppProvider).container
            val expected = targetPendingId?.let { container.pendingEntryDao.getById(it)?.amountCents }
            // 引擎一：无障碍树（原生页面）
            val texts = lastSnapshot ?: readWindowFor(pkg)
            lastSnapshot = null
            var bill = if (texts.isEmpty()) null else BillPageParser.parse(texts, expectedAmountCents = expected)
            if (bill != null) {
                retries = 0
                if (BuildConfig.DEBUG) Log.d(TAG, "bill(tree) amount=${bill.amountCents} merchant=${bill.merchant} expected=$expected")
                handleBill(bill, fromOcr = false, targetPendingId = targetPendingId, manual = manual)
                return@launch
            }
            // 引擎二：截屏 OCR。默认只在窗口树几乎为空时（H5 结算页特征）才截屏，
            // 聊天列表等文字丰富的页面自动跳过，隐私上绝不截取聊天内容。
            // 但两种情形会放宽：
            //  - 手动"重新识别"（悬浮球/通知栏触发，用户主动要识别当前页）；
            //  - 自动模式下页面已含"支付成功/已支付 + ¥金额"等强结果特征
            //    （支付宝 NFC 碰一碰等，树字段多但姓名/金额解析不出，靠 OCR 兜底建档）。
            if (texts.size > 8 && !manual && !isStrongResultPage(texts)) {
                retries = 0
                return@launch
            }
            // 当前前台 App 是否还是目标 App？如果用户已离开支付页（回到桌面/打开其他 App），
            // 此时截屏只会拍到别的界面（如 App 自己的首页），白白浪费一次截屏+OCR。
            // 用 rootInActiveWindow 判断：null（无窗口/桌面）或 packageName 不匹配都跳过。
            // 手动"重新识别"除外——主动识别时下拉通知栏会让焦点瞬时切到系统窗口，
            // rootInActiveWindow 拿不到支付宝/微信，故手动模式不做该校验，直接拍屏。
            if (!manual) {
                val currentFg = rootInActiveWindow?.packageName?.toString()
                if (currentFg == null || currentFg != pkg) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "skip ocr: foreground=$currentFg target=$pkg")
                    retries = 0
                    return@launch
                }
            }
            bill = screenshotOcr(expected)
            if (bill != null) {
                retries = 0
                if (BuildConfig.DEBUG) Log.d(TAG, "bill(ocr) amount=${bill.amountCents} merchant=${bill.merchant} strong=${bill.strongPage} expected=$expected")
                // OCR 模式只补全已有记录，不凭空建账（避免把账单列表页等误识别成新账单）
                handleBill(bill, fromOcr = true, targetPendingId = targetPendingId, manual = manual)
                // 手动"重新识别"时，给用户明确的记账反馈
                if (manual) {
                    toast("已识别并记录：¥${formatCents(bill.amountCents)} ${bill.merchant}")
                }
                return@launch
            }
            // 手动"重新识别"只识别一次：无论成功与否都给出结果，不进入后台重试循环，
            // 避免用户对着支付页却反复被"加载中"拖住。
            if (manual) {
                toast("未识别到支付信息，请确认正停留在支付宝/微信的账单页")
                retries = 0
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

    /** 页面文本是否已是"强支付结果特征"：命中结果页标志词，且同屏存在 ¥ 金额 */
    private val strongResultAmount = Regex("[¥￥]\\s*[0-9]")
    private val strongResultMarkers = listOf(
        "支付成功", "付款成功", "成功付款", "支付完成", "已支付", "已付款", "已完成支付",
        "交易成功", "转账成功", "还款成功", "充值成功", "支付凭证", "付款凭证", "已入账",
    )

    private fun isStrongResultPage(lines: List<String>): Boolean {
        val joined = lines.joinToString(" ")
        return strongResultMarkers.any { joined.contains(it) } && strongResultAmount.containsMatchIn(joined)
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
    private suspend fun screenshotOcr(expectedAmountCents: Long? = null): BillPageParser.Bill? =
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
                                    if (cont.isActive) cont.resume(
                                        BillPageParser.parseOcr(lines, expectedAmountCents = expectedAmountCents)
                                    )
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
        manual: Boolean = false,
    ) {
        val container = (applicationContext as LedgerAppProvider).container
        val now = System.currentTimeMillis()

        // 0) 目标待补全条目精确关联：通知监听刚创建的那条。
        //    只补这一条，绝不因为"金额相同"就去覆盖别的记录——这是同金额多笔记账错配的来源。
        if (targetPendingId != null) {
            val target = container.pendingEntryDao.getById(targetPendingId)
            val contentPkg = rootInActiveWindow?.packageName?.toString()
            val matched = target != null && targetMatches(target, bill, contentPkg)
            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "handleBill[0-target] id=$targetPendingId targetAmount=${target?.amountCents} billAmount=${bill.amountCents} matched=$matched"
            )
            if (target != null && matched) {
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
            if (BuildConfig.DEBUG) Log.d(TAG, "handleBill[1-pending] 唯一同金额待确认 id=${pendingMatches.first().id}")
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
            if (BuildConfig.DEBUG) Log.d(TAG, "handleBill[2-tx] 补全已入账 id=${tx.id}")
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
        //    手动"重新识别"除外：用户主动触发，识别到就进"待确认"由用户把关。
        if (fromOcr && !bill.strongPage && !manual) {
            if (BuildConfig.DEBUG) Log.d(TAG, "handleBill[3-new] 拦截：非强页面特征不建账")
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "handleBill[3-new] 新建待确认 amount=${bill.amountCents} merchant=${bill.merchant}")
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
        // 启动流动云胶囊（ColorOS 摄像头位置显示"¥14.14 待确认"）
        FluidCloudService.start(applicationContext)
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
        // 流动云胶囊刷新显示最新商户/金额
        FluidCloudService.start(applicationContext)
    }

    private fun clearTarget(id: Long?) {
        if (targetPendingId == id) targetPendingId = null
    }
}
