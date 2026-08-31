package com.zhuanz.autoleger.data

import com.zhuanz.autoleger.notify.ConfirmNotifier
import com.zhuanz.autoleger.notify.PaymentParser
import kotlinx.coroutines.flow.first

/** 把一条待处理通知确认入库的公共逻辑，确认通知按钮和待处理列表共用 */
object EntryConfirmer {

    /** 商户取值：读屏补全过的（非泛称）优先，通知原文的再解析结果只做兜底 */
    internal fun resolveMerchant(
        entry: PendingEntryEntity,
        freshMerchant: String?,
        generic: Set<String>,
    ): String? =
        entry.merchant?.takeIf { it !in generic }
            ?: freshMerchant?.takeIf { it !in generic }
            ?: entry.merchant

    suspend fun confirm(container: AppContainer, entry: PendingEntryEntity): Boolean {
        val generic = MerchantFilters.genericMerchants(container.appContext)
        // 入库时以通知原文重新解析金额；商户则优先用读屏已补全的结果
        val fresh = PaymentParser.parse(entry.title, entry.text)
        val amount = fresh?.amountCents ?: entry.amountCents ?: return false
        val merchant = resolveMerchant(entry, fresh?.merchant, generic) ?: "未知商户"
        val isRefund = entry.text.contains("退款")
        val category = container.matchCategory(merchant)
        val fallback = container.categoryDao.observeAll().first().lastOrNull()
        container.transactionDao.insert(
            TransactionEntity(
                type = if (isRefund) TYPE_REFUND else TYPE_EXPENSE,
                amountCents = amount,
                merchant = merchant,
                categoryId = category?.id ?: fallback?.id,
                time = entry.time,
                source = SOURCE_NOTIFICATION,
                rawText = "${entry.title} ${entry.text}",
            )
        )
        return true
    }

    /** 手动/编辑页保存时按规则匹配分类的兜底处理 */
    suspend fun categoryFor(container: AppContainer, merchant: String): Long? =
        container.matchCategory(merchant)?.id
            ?: container.categoryDao.observeAll().first().lastOrNull()?.id

    fun extractCategoryHint(container: AppContainer, entry: PendingEntryEntity): ParsedHint {
        val generic = MerchantFilters.genericMerchants(container.appContext)
        val parsed = PaymentParser.parse(entry.title, entry.text)
        return ParsedHint(
            parsed?.amountCents ?: entry.amountCents,
            resolveMerchant(entry, parsed?.merchant, generic),
        )
    }

    data class ParsedHint(val amountCents: Long?, val merchant: String?)

    sealed interface ReRecognizeResult {
        /** 已按新解析逻辑修正（金额/商户/状态），返回修正后的条目 */
        data class Updated(val entry: PendingEntryEntity) : ReRecognizeResult

        /** 原文解析不出比已存值更多的信息，条目未变 */
        object NoChange : ReRecognizeResult
    }

    /**
     * "重新识别"：用当前解析逻辑重解析该条通知原文，修正库里存的金额/商户并重发确认通知。
     *
     * 使用场景：解析逻辑升级后修复历史识别错误（如"已优惠¥0.46，实付¥14.14"曾被记成 0.46）。
     * 库里存错金额不只是显示问题——OCR 补全按存库金额匹配，存错会连带导致商户补不上。
     * 读屏已补全的非泛称商户保留不动，金额以新解析为准。
     */
    suspend fun reRecognize(container: AppContainer, entry: PendingEntryEntity): ReRecognizeResult {
        val generic = MerchantFilters.genericMerchants(container.appContext)
        val fresh = PaymentParser.parse(entry.title, entry.text)
        val updated = reRecognizeDecision(entry, fresh, generic)
            ?: return ReRecognizeResult.NoChange
        container.pendingEntryDao.insert(updated)
        ConfirmNotifier.postConfirmNotification(
            container.appContext,
            updated.id,
            PaymentParser.Parsed(
                updated.amountCents ?: 0L,
                updated.merchant ?: updated.title,
                isRefund = updated.text.contains("退款"),
            ),
            updated.time,
        )
        return ReRecognizeResult.Updated(updated)
    }

    /** 纯决策：新解析结果与已存值合并。返回 null 表示无变化（OCR 建账条目的原文就是错的，也在此返回 null） */
    internal fun reRecognizeDecision(
        entry: PendingEntryEntity,
        fresh: PaymentParser.Parsed?,
        generic: Set<String>,
    ): PendingEntryEntity? {
        val amount = fresh?.amountCents?.takeIf { it > 0 } ?: return null
        val merchant = resolveMerchant(entry, fresh.merchant, generic)
        val amountChanged = entry.amountCents != amount
        val merchantChanged = merchant != entry.merchant
        val statusUpgraded = entry.status != PENDING_CONFIRM
        if (!amountChanged && !merchantChanged && !statusUpgraded) return null
        return entry.copy(
            amountCents = amount,
            merchant = merchant,
            status = PENDING_CONFIRM,
        )
    }
}
