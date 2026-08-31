package com.zhuanz.autoleger.notify

import com.zhuanz.autoleger.data.EntryConfirmer
import com.zhuanz.autoleger.data.PENDING_CONFIRM
import com.zhuanz.autoleger.data.PendingEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 回归：读屏补全的商户不能在确认入库时被通知原文的再解析覆盖 */
class EntryConfirmerTest {

    // 真实泛称商户名单的取样（线上来自 MerchantFilters，单测用固定集）
    private val generic = setOf("微信支付", "支付宝", "微信", "未知商户")

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
        val m = EntryConfirmer.resolveMerchant(entry("tokizero"), "微信支付", generic)
        assertEquals("tokizero", m)
    }

    @Test
    fun 没有补全时_用通知再解析结果() {
        val m = EntryConfirmer.resolveMerchant(entry("微信支付"), "瑞幸咖啡", generic)
        assertEquals("瑞幸咖啡", m)
    }

    @Test
    fun 都没有时_为空() {
        val m = EntryConfirmer.resolveMerchant(entry(null), null, generic)
        assertEquals(null, m)
    }

    @Test
    fun 补全的是泛称时_退回通知再解析结果() {
        val m = EntryConfirmer.resolveMerchant(entry("微信支付"), "瑞幸咖啡", generic)
        assertEquals("瑞幸咖啡", m)
    }
}

/** "重新识别"：解析逻辑升级后，历史识别错的待处理条目点一下即被修正 */
class ReRecognizeTest {

    private val generic = setOf("微信支付", "支付宝", "微信", "未知商户")

    private fun entry(
        amountCents: Long?,
        merchant: String?,
        status: String = PENDING_CONFIRM,
        title: String = "微信支付",
        text: String,
    ) = PendingEntryEntity(
        packageName = "com.tencent.mm",
        title = title,
        text = text,
        amountCents = amountCents,
        merchant = merchant,
        status = status,
        time = 0,
    )

    private fun decide(e: PendingEntryEntity) =
        EntryConfirmer.reRecognizeDecision(e, PaymentParser.parse(e.title, e.text), generic)

    /** 用户实测场景：原文"已优惠¥0.46，实付¥14.14"，旧逻辑存了 46，重识别修正为 1414 */
    @Test
    fun 旧逻辑记错金额_重识别修正() {
        val e = entry(
            amountCents = 46,
            merchant = "微信支付",
            text = "支付成功，已优惠¥0.46，实付¥14.14",
        )
        val r = decide(e)
        assertEquals(1414L, r?.amountCents)
        assertEquals(PENDING_CONFIRM, r?.status)
    }

    /** 未解析条目（旧逻辑没解析出金额）升级为可确认 */
    @Test
    fun 未解析条目_新逻辑解析出金额后升级() {
        val e = entry(
            amountCents = null,
            merchant = null,
            status = "PENDING_UNPARSED",
            text = "支付成功，实付14.14元",
        )
        val r = decide(e)
        assertEquals(1414L, r?.amountCents)
        assertEquals(PENDING_CONFIRM, r?.status)
    }

    /** 泛称商户被新解析出的具体商户替换；已补全的具体商户保留 */
    @Test
    fun 泛称商户被替换_已补全商户保留() {
        val genericOnly = entry(
            amountCents = 46,
            merchant = "微信支付",
            text = "支付成功，已优惠¥0.46，实付¥14.14，瑞幸咖啡-国贸店",
        )
        val enriched = genericOnly.copy(merchant = "瑞幸咖啡")

        assertEquals("瑞幸咖啡-国贸店", decide(genericOnly)?.merchant)
        assertEquals("瑞幸咖啡", decide(enriched)?.merchant)
    }

    /** 原文里没有商户名时保留已存商户（哪怕它是泛称），只修金额 */
    @Test
    fun 原文无商户_保留已存商户只修金额() {
        val e = entry(
            amountCents = 46,
            merchant = "微信支付",
            text = "支付成功，已优惠¥0.46，实付¥14.14",
        )
        val r = decide(e)
        assertEquals(1414L, r?.amountCents)
        assertEquals("微信支付", r?.merchant)
    }

    /** 解析结果与已存值一致时无变化 */
    @Test
    fun 无变化时返回null() {
        val e = entry(amountCents = 10, merchant = "瑞幸咖啡", text = "已支付¥0.10")
        assertNull(decide(e))
    }

    /** OCR 建账条目：原文里就写着错的金额，重解析得到相同值，无变化（只能手动改） */
    @Test
    fun OCR建账条目_重解析与存值相同_无变化() {
        val e = entry(
            amountCents = 46,
            merchant = "蜂鸟准时达",
            text = "读屏补全：蜂鸟准时达 ¥0.46",
        )
        assertNull(decide(e))
    }

    /** 原文里完全没有金额（如纯转账提醒）时无变化 */
    @Test
    fun 原文无金额_返回null() {
        val e = entry(
            amountCents = null,
            merchant = "杨青凤",
            status = "PENDING_UNPARSED",
            text = "杨青凤向你发起了一笔转账",
        )
        assertNull(decide(e))
    }
}
