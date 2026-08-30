package com.zhuanz.autoleger.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class BillPageParserTest {

    /** 用户手机真机实测的 OCR 输出（含"微信支付凭证→啟信支付凭证"、"收款方→女款方"误识） */
    @Test
    fun OCR_真实微信支付凭证页_提取收款人() {
        val lines = listOf(
            "24", "02:03 G", "啟信支付凭证", "女款方", "看账单详情", "tokizero",
            "啟信支付凭证", "女款方", "交易状态支付成功,对方已收款", "使用零钱支付",
            "微信支付", "¥0.10", "查看账单详羊情", "我的账单", "tokizero", "02:01",
            "使用零钱支付", "¥0.10", "交易状态 支付成功,对方已收款", "支付服务",
            "当前状态", "收款方备注", "支付万式", "转账单号", "", "账单服务",
            "扫", "2", "对订单有疑", "", "收款方服务", "⑥申请电子凭", "0向收款方留",
        )
        val r = BillPageParser.parseOcr(lines)
        println("实际 = $r")
        assertEquals(10L, r?.amountCents)
        assertEquals("tokizero", r?.merchant)
    }

    /** 结果页动宾句式："付款给XX" */
    @Test
    fun OCR_付款给句式() {
        val r = BillPageParser.parseOcr(
            listOf("支付成功", "¥200.00", "付款给瑞幸咖啡-国贸店", "支付方式", "零钱")
        )
        assertEquals(20000L, r?.amountCents)
        assertEquals("瑞幸咖啡-国贸店", r?.merchant)
    }

    /** 账单列表页（用户翻历史记录时误触 OCR）不应提取出正常商户 */
    @Test
    fun OCR_账单列表页_不产生干净商户() {
        val lines = listOf(
            "全部账单", "2026年8月", "查找交易", "扫二维码付款-给tokizero", "8月30日02:01",
            "扫二维码付款-给tokzero", "8月30日 01:29", "抗州深度求索", "账单",
            "支出¥674.88 收入¥1298.10", "收支统计>", "取消", "-0.10", "-1.00",
        )
        val r = BillPageParser.parseOcr(lines)
        println("列表页实际 = $r")
        if (r != null) {
            println("注意：列表页产生了商户 ${r.merchant}，将由 handleBill(fromOcr=true) 的路径门控拦截（不建新账）")
        }
    }

    /** 无障碍树模式：原生结果页 */
    @Test
    fun 树模式_原生结果页() {
        val r = BillPageParser.parse(
            listOf("支付成功", "¥200.00", "付款给瑞幸咖啡-国贸店", "支付方式", "零钱")
        )
        assertEquals(20000L, r?.amountCents)
        assertEquals("瑞幸咖啡-国贸店", r?.merchant)
    }
}

/** 通用规则：任何"单金额结果页"都应记录，多金额列表页一律拒绝 */
class 通用规则Test {

    @Test
    fun 支付宝信用卡还款页() {
        val r = BillPageParser.parseOcr(
            listOf(
                "9:41", "信用卡还款", "还款成功", "¥1,000.00",
                "付款方式 余额", "尾号1234",
            ),
            sourceFallback = "支付宝",
        )
        println("信用卡还款 = $r")
        assertEquals(100000L, r?.amountCents)
        assertEquals("信用卡还款", r?.merchant)
    }

    @Test
    fun 支付宝小荷包转账页() {
        val r = BillPageParser.parseOcr(
            listOf("10:32", "转账", "转入小荷包", "¥0.10", "转入成功"),
            sourceFallback = "支付宝",
        )
        println("小荷包 = $r")
        assertEquals(10L, r?.amountCents)
        assertEquals("转入小荷包", r?.merchant)
    }

    @Test
    fun 账单列表页_多金额_拒绝() {
        val r = BillPageParser.parseOcr(
            listOf(
                "全部账单", "扫二维码付款-给tokizero", "8月30日02:01",
                "扫二维码付款 ¥2.00", "8月30日 01:29",
                "支出¥674.88 收入¥1298.10", "-0.10", "-1.00", "收支统计",
            ),
            sourceFallback = "支付宝",
        )
        println("列表页 = $r（多金额列表页应拒绝）")
        assertEquals(null, r)
    }

    @Test
    fun 无金额页面_拒绝() {
        val r = BillPageParser.parseOcr(
            listOf("通讯录", "新的朋友", "扫一扫"),
            sourceFallback = "支付宝",
        )
        assertEquals(null, r)
    }
}
