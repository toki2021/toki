package com.zhuanz.autoleger.data

import com.zhuanz.autoleger.notify.PaymentParser
import kotlinx.coroutines.flow.first

/** 把一条待处理通知确认入库的公共逻辑，确认通知按钮和待处理列表共用 */
object EntryConfirmer {

    /** 商户取值：读屏补全过的（非泛称）优先，通知原文的再解析结果只做兜底 */
    private fun resolveMerchant(
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
}
