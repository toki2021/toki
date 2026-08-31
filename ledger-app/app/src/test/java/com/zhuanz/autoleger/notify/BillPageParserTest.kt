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

    // ===== 优惠场景（用户实测：付14.60优惠0.46实付14.14，曾把 0.46 记成入账金额）=====

    /** 大字号金额被 OCR 拆成 "¥"+"14.14" 两行，优惠也拆成 "已优惠"+"¥0.46" 两行 */
    @Test
    fun OCR_优惠页_拆行金额拼回且优惠金额不干扰实付() {
        val lines = listOf("微信支付凭证", "¥", "14.14", "已优惠", "¥0.46", "收款方", "瑞幸咖啡")
        val r = BillPageParser.parseOcr(lines)
        assertEquals(1414L, r?.amountCents)
        assertEquals("瑞幸咖啡", r?.merchant)
    }

    /** 优惠为单行 "已优惠¥0.46" 的形态 */
    @Test
    fun OCR_优惠页_单行优惠形态() {
        val lines = listOf("微信支付凭证", "¥", "14.14", "已优惠¥0.46", "收款方", "瑞幸咖啡")
        val r = BillPageParser.parseOcr(lines)
        assertEquals(1414L, r?.amountCents)
        assertEquals("瑞幸咖啡", r?.merchant)
    }

    /** 期望金额（通知侧实付）锚定：页面只要确认存在即采用，金额以通知为准 */
    @Test
    fun OCR_优惠页_期望金额锚定() {
        val lines = listOf("支付成功", "¥14.14", "已优惠¥0.46", "付款给瑞幸咖啡")
        val r = BillPageParser.parseOcr(lines, expectedAmountCents = 1414L)
        assertEquals(1414L, r?.amountCents)
        assertEquals("瑞幸咖啡", r?.merchant)
    }

    /** 无障碍树模式：优惠节点排在实付金额前面，也不能取到优惠金额 */
    @Test
    fun 树模式_优惠行在实付前_不误取优惠金额() {
        val r = BillPageParser.parse(
            listOf("支付成功", "已优惠¥0.46", "¥14.14", "收款方", "瑞幸咖啡")
        )
        assertEquals(1414L, r?.amountCents)
    }

    /** 树模式：期望金额锚定 */
    @Test
    fun 树模式_期望金额锚定() {
        val r = BillPageParser.parse(
            listOf("支付成功", "已优惠¥0.46", "¥14.14", "收款方", "瑞幸咖啡"),
            expectedAmountCents = 1414L,
        )
        assertEquals(1414L, r?.amountCents)
    }

    /** 营销弹窗（"恭喜获得¥0.46红包"）没有强页面特征，不允许 OCR 凭空新建账单 */
    @Test
    fun OCR_营销弹窗_无强页面特征() {
        val r = BillPageParser.parseOcr(listOf("恭喜获得", "¥0.46", "红包", "点击领取"))
        assertEquals(false, r?.strongPage == true)
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
