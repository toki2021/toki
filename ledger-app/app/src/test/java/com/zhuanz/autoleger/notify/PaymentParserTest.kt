package com.zhuanz.autoleger.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentParserTest {

    private fun parse(title: String, text: String) = PaymentParser.parse(title, text)

    @Test
    fun 真实样本_微信单行只有金额_商户不应误取描述词() {
        val r = parse("微信支付", "已支付¥1.00")
        println("实际 merchant = ${r?.merchant}, amount = ${r?.amountCents}")
        println("变体 无标题   = " + parse("X", "已支付¥1.00")?.merchant)
        println("变体 无已支付 = " + parse("微信支付", "¥1.00")?.merchant)
        println("变体 有空格   = " + parse("微信支付", "已支付 ¥1.00")?.merchant)
        println("变体 有商户   = " + parse("微信支付", "已支付¥1.00 瑞幸咖啡")?.merchant)
        assertEquals(100L, r?.amountCents)
        assertEquals(false, r?.merchant == "已支付")
    }

    @Test
    fun 真实样本_微信凭证三行() {
        val r = parse("微信支付", "微信支付凭证\n¥25.00\n-美团外卖-国贸店")
        assertEquals(2500L, r?.amountCents)
        assertEquals("美团外卖-国贸店", r?.merchant)
    }

    @Test
    fun 商户带标签() {
        val r = parse("微信支付", "¥300.00\n商户：山姆会员店")
        assertEquals("山姆会员店", r?.merchant)
    }

    @Test
    fun 单行带商户() {
        val r = parse("微信支付", "支付成功 ¥45.00 Starbucks 国贸店")
        assertEquals("Starbucks 国贸店", r?.merchant)
    }

    @Test
    fun 退款通知识别() {
        val r = parse("微信支付", "退款¥12.00\n瑞幸咖啡")
        assertEquals(1200L, r?.amountCents)
        assertEquals(true, r?.isRefund)
    }

    @Test
    fun 真实样本_系统合并通知_前缀不应成为商户() {
        val r = parse("微信支付", "[2条] 微信支付: 已支付¥8.00")
        println("合并通知 merchant = ${r?.merchant}, amount = ${r?.amountCents}")
        assertEquals(800L, r?.amountCents)
        assertEquals(false, r?.merchant?.contains("条") == true)
        assertEquals(false, r?.merchant?.contains(":") == true)
    }

    // ===== 优惠场景：取实付金额，不取优惠金额（用户实测 付14.60 优惠0.46 实付14.14）=====

    @Test
    fun 优惠通知_优先取实付标签金额() {
        val r = parse("微信支付", "支付成功，已优惠¥0.46，实付¥14.14")
        assertEquals(1414L, r?.amountCents)
    }

    @Test
    fun 优惠通知_无标签时剔除优惠金额取唯一主金额() {
        val r = parse("微信支付", "已支付¥14.14，已优惠¥0.46")
        assertEquals(1414L, r?.amountCents)
    }

    @Test
    fun 优惠通知_元计价() {
        val r = parse("支付宝", "支付成功 14.14元（已优惠0.46元）")
        assertEquals(1414L, r?.amountCents)
    }

    @Test
    fun 优惠通知_多个主金额无法确定时不猜() {
        // 没有实付标签、两个都是主金额语境 → 宁可不解析（进待处理），也不能猜错
        val r = parse("微信支付", "支付成功 14.60 已优惠0.46 实际14.14")
        assertEquals(null, r)
    }
}

/** 通知粗过滤：营销/事件拦截，真实支付放行 */
class LooksLikePaymentTest {

    private fun looks(title: String, text: String, pkg: String = "com.tencent.mm") =
        PaymentParser.looksLikePayment(pkg, title, text)

    @Test
    fun 营销与事件通知_拦截() {
        assertEquals(false, looks("微信支付", "多笔立减优惠等你使用"))
        assertEquals(false, looks("淘宝闪购", "红包今晚失效，速来领取"))
        assertEquals(false, looks("蜂鸟准时达", "您的订单已准时送达，感谢惠顾"))
        assertEquals(false, looks("微信支付", "验证码123456，请勿泄露"))
        assertEquals(false, looks("支付宝", "还款日提醒：本月账单已出"))
    }

    @Test
    fun 真实支付通知_放行() {
        assertEquals(true, looks("微信支付", "已支付¥14.14"))
        assertEquals(true, looks("微信支付", "支付成功，已优惠0.46，实付14.14元"))
        assertEquals(true, looks("微信支付", "向杨青凤转账¥1000.00"))
        assertEquals(true, looks("微信支付", "退款成功¥12.00"))
    }

    /** 真机样本：支付宝标准通知带"点击领取积分"，不能被词表误杀 */
    @Test
    fun 支付宝实付句式_放行() {
        assertEquals(true, PaymentParser.looksLikePayment(
            "com.eg.android.AlipayGphone", "交易提醒",
            "你有一笔1.87元的支出，点击领取10个支付宝积分。",
        ))
    }
}

/** 用户真机实测的支付宝通知样本（2026-08-30 ¥1.87 那笔） */
class 支付宝样本Test {
    @Test
    fun 支付宝交易提醒_不产生垃圾商户() {
        val r = PaymentParser.parse(
            "交易提醒",
            "你有一笔1.87元的支出，点击领取10个支付宝积分。",
        )
        println("实际 merchant = ${r?.merchant}, amount = ${r?.amountCents}")
        assertEquals(187L, r?.amountCents)
        val m = r?.merchant ?: ""
        // 垃圾商户（通知句式）不允许出现
        assertEquals(false, m.contains("支出"))
        assertEquals(false, m.contains("一笔"))
        assertEquals(false, m.contains("你有一笔"))
    }

    @Test
    fun 支付宝合并通知_金额正确() {
        val r = PaymentParser.parse("支付宝", "你有一笔41.00元的支出")
        assertEquals(4100L, r?.amountCents)
    }
}
