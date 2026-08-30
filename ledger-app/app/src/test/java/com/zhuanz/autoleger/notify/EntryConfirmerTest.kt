package com.zhuanz.autoleger.notify

import com.zhuanz.autoleger.data.EntryConfirmer
import com.zhuanz.autoleger.data.PendingEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 回归：读屏补全的商户不能在确认入库时被通知原文的再解析覆盖 */
class EntryConfirmerTest {

    private fun entry(merchant: String?) = PendingEntryEntity(
        packageName = "com.tencent.mm",
        title = "微信支付",
        text = "[2条]微信支付: 已支付¥0.10",
        amountCents = 10,
        merchant = merchant,
        status = "PENDING_CONFIRM",
        time = 0,
    )

    @Test
    fun 读屏补全的商户_优先于通知再解析() {
        val m = EntryConfirmer.resolveMerchant(entry("tokizero"), freshMerchant = "微信支付")
        assertEquals("tokizero", m)
    }

    @Test
    fun 没有补全时_用通知再解析结果() {
        val m = EntryConfirmer.resolveMerchant(entry("微信支付"), freshMerchant = "瑞幸咖啡")
        assertEquals("瑞幸咖啡", m)
    }

    @Test
    fun 都没有时_为空() {
        val m = EntryConfirmer.resolveMerchant(entry(null), freshMerchant = null)
        assertEquals(null, m)
    }

    @Test
    fun 编辑页预填_同样优先补全商户() {
        val hint = EntryConfirmer.extractCategoryHint(entry("tokizero"))
        assertEquals("tokizero", hint.merchant)
    }
}
