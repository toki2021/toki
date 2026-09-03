package com.zhuanz.autoleger.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillPageParserTest {

    /** 图1真实页面：支付宝账单详情页（无¥、带负号金额、无商户标签、带券） */
    private val alipayDetailTree = listOf(
        "账单详情",
        "淘宝闪购",
        "-16.60",
        "支付成功",
        "管理极速付款", "极速付款",
        "2026-09-02 18:02:28",
        "中国银行储蓄卡(0982)",
        "柒小螺·螺蛳粉(尚城融悦汇店)外卖订单",
        "立即领取2积分",
        "淘宝闪购极速版 / 13元新人优惠券",
        "2026090223001199631453776750",
    )

    @Test
    fun 支付宝账单详情_无障碍树_识别金额与商户() {
        val bill = BillPageParser.parse(alipayDetailTree)
        assertEquals(1660L, bill?.amountCents)
        assertEquals("淘宝闪购", bill?.merchant)
    }

    @Test
    fun 支付宝账单详情_OCR行_识别金额与商户() {
        val bill = BillPageParser.parseOcr(alipayDetailTree)
        assertEquals(1660L, bill?.amountCents)
        assertEquals("淘宝闪购", bill?.merchant)
        assertEquals(true, bill?.strongPage)
    }

    @Test
    fun 券行金额_不作为支出() {
        // 仅有"13元新人优惠券"这类行时不能识别为支出
        assertNull(BillPageParser.parseOcr(listOf("淘宝闪购", "13元新人优惠券", "立即领取")))
    }

    @Test
    fun 日期负号_不误当金额() {
        // "-09" 这种日期片段不是金额
        assertNull(BillPageParser.parseOcr(listOf("2026-09-02", "正常支付")))
    }

    /** 真机样本（2026-09-03）：支付宝支付结果页 OCR 原始 16 行。
     *  曾把 "-¥0.04"（百次立减优惠额）记成实付、营销卡片当商户。 */
    private val alipayResultOcr = listOf(
        "百次立减", "北京荒野探索科技有限公司", "付款方式", "¥10.31",
        "支付宝积分+2", "支付有礼", "。支付成功", "18VIP)", "充值金+15",
        "回首页", "中国银行储蓄卡(0982)", "本月这标后笔笔95折 去看看",
        "Y 10.35", "-¥0.04", "18笔支付立减机会待解锁", "一键领",
    )

    @Test
    fun 支付宝结果页_优惠负号金额_不覆盖实付() {
        val bill = BillPageParser.parseOcr(alipayResultOcr)
        assertEquals(1031L, bill?.amountCents)
        assertEquals("北京荒野探索科技有限公司", bill?.merchant)
        assertEquals(true, bill?.strongPage)
    }

    @Test
    fun 支付宝结果页_期望金额锚定_优先于页面金额() {
        val bill = BillPageParser.parseOcr(alipayResultOcr, expectedAmountCents = 1031L)
        assertEquals(1031L, bill?.amountCents)
    }

    @Test
    fun 营销文案_不当商户() {
        // 营销卡片行不能成为商户候选——识别不到干净商户时整个识别放弃
        assertNull(
            BillPageParser.parseOcr(
                listOf("支付成功", "¥25.00", "去看看", "本月达标后笔笔95折", "一键领")
            )
        )
    }
}