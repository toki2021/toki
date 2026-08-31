package com.zhuanz.autoleger.notify

import com.zhuanz.autoleger.data.toCents

/**
 * 从微信/支付宝的支付通知里启发式地解析金额、商户名和类型（支出/退款）。
 *
 * 通知文案没有官方格式保证，解析失败时条目会进入"待处理（未解析）"列表，
 * 由用户手动补录，因此这里宁可解析保守也不猜错。
 *
 * 「金额文本 → 分」的公共实现放在 [com.zhuanz.autoleger.data.toCents]，
 * 解析层与 UI 层统一走 BigDecimal 路径，避免 double 精度损失。
 */
object PaymentParser {

    data class Parsed(
        val amountCents: Long,
        val merchant: String,
        val isRefund: Boolean,
    )

    private val amountWithSymbol = Regex("[¥￥]\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val amountWithYuan = Regex("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元")

    // 优惠/抵扣语境：金额前 6 字内出现这些词，说明它是优惠金额而不是实付（"已优惠¥0.46"、"立减0.46元"）
    private val discountPrefix = listOf(
        "优惠", "立减", "满减", "折扣", "已省", "省了", "节省", "减免",
        "抵扣", "返现", "已减", "券", "红包",
    )

    // 明确实付标签："实付¥14.14"、"支付金额：14.14元"
    private val labeledAmount = Regex(
        "(?:实付|实际支付|实付款|支付金额|付款金额|交易金额|合计)[：:\\s]*[¥￥]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    )

    // 明显不是商户名的行
    private val merchantExclusions = listOf(
        "支付成功", "付款成功", "收款成功", "退款", "已支付", "已付款",
        "微信支付", "支付宝", "凭证", "账单", "余额", "银行卡", "零钱",
        "本次", "您已", "交易", "扣款", "查看", "详情", "点击",
        "实名", "尾号", "支付通知", "服务通知",
        // 支付宝通知句式与结果页按钮词（真机样本）
        "支出", "收入", "一笔", "你有", "完成", "提醒", "领取", "积分", "点此",
        // 带优惠的账单文案
        "优惠", "实付",
    )

    // "商户：XXX"、"商家 - XXX"、"收款方: XXX" 这类带标签的行
    private val labeledMerchant = Regex("(?:商户|商家|收款方|收款商户|门店|付款对象)[：:·\\-—]\\s*(.+)")
    // "向XX转账/付款"
    private val transferTo = Regex("向(.{2,20}?)(?:转账|付款|支付)")

    fun parse(title: String, text: String): Parsed? {
        // 系统合并通知前缀，如"[2条] 微信支付: 已支付¥8.00"
        val cleanText = text.replace(Regex("^\\[?\\d+条\\]?[：:]?\\s*"), "")
        val cleanTitle = title.replace(Regex("^\\[?\\d+条\\]?[：:]?\\s*"), "")
        val full = "$cleanTitle $cleanText"
        val amount = parseAmount(cleanText) ?: return null
        val isRefund = full.contains("退款")

        // 无法判断是支出还是收入/收款的不要：收款不是支出
        if (!isRefund && (full.contains("收款成功") || full.contains("收到付款") || full.contains("转入成功"))) {
            return null
        }
        return Parsed(amount, parseMerchant(cleanTitle, cleanText), isRefund)
    }

    private fun parseAmount(text: String): Long? {
        // 1) "实付/支付金额/合计"等明确标签的金额最优先（多金额的优惠账单里标签最可靠）
        labeledAmount.find(text)?.groupValues?.get(1)?.replace(",", "")
            ?.toCents()?.takeIf { it > 0 }?.let { return it }
        // 2) 剔除优惠语境金额后，主金额必须唯一；多个无法确定时宁可不解析（进待处理手动补）
        return mainAmounts(text).singleOrNull()
    }

    /** 文本中所有"主金额"（剔除"已优惠/立减"等语境的金额），按出现顺序去重 */
    private fun mainAmounts(text: String): List<Long> {
        val matches = (amountWithSymbol.findAll(text) + amountWithYuan.findAll(text))
            .sortedBy { it.range.first }
            .toList()
        return matches
            .filter { m ->
                val prefix = text.substring(maxOf(0, m.range.first - 6), m.range.first)
                discountPrefix.none { it in prefix }
            }
            .mapNotNull { it.groupValues[1].replace(",", "").toCents()?.takeIf { c -> c > 0 } }
            .distinct()
    }

    private fun parseMerchant(title: String, text: String): String {
        val lines = text.split('\n', '，', '。')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            // 纯系统合并标记/符号的行不是商户
            .filterNot { Regex("^\\[?\\d*条\\]?$").containsMatchIn(it) }

        // 策略一：带"商户/收款方"标签的行，或"向XX转账"
        for (line in lines) {
            labeledMerchant.find(line)?.groupValues?.get(1)?.let { return cleanMerchant(it, title) }
            transferTo.find(line)?.groupValues?.get(1)?.let { return cleanMerchant(it, title) }
        }

        // 策略二：不含金额、不含描述性词语的干净行（真实微信格式里商户常在最后一行，优先取靠后的）
        for (line in lines.reversed()) {
            if (amountWithSymbol.containsMatchIn(line) || amountWithYuan.containsMatchIn(line)) continue
            if (merchantExclusions.any { it in line }) continue
            val cleaned = cleanMerchant(line, title)
            if (cleaned.isNotBlank()) return cleaned
        }

        // 策略三：单行格式"支付成功 ¥xx 商户名"，剔除已知噪音后取剩余部分
        for (line in lines) {
            val cleaned = cleanMerchant(
                line
                    .replace(Regex("(支付|付款|收款|退款)(成功|失败)"), " ")
                    .replace(amountWithSymbol, " ")
                    .replace(amountWithYuan, " ")
                    .replace(Regex("[¥￥]"), " "),
                title,
            )
            if (cleaned.isNotBlank() && cleaned != title) return cleaned
        }

        return title.ifBlank { "未知商户" }
    }

    private fun cleanMerchant(raw: String, title: String): String {
        val cleaned = raw
            .replace(Regex("^[\\-–—·\\s]+"), "")
            .replace(Regex("^向"), "")
            .replace(Regex("(付款|支付)$"), "")
            .replace(Regex("^[\\[\\(【（]|[\\]\\)】）]$"), "")
            .replace(exclusionRegex, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { it.length in 2..30 && it != title } ?: ""
    }

    /** 注意必须传 Regex 对象；传字符串会走字面量替换导致关键词剔除失效 */
    private val exclusionRegex =
        Regex(merchantExclusions.joinToString("|") { Regex.escape(it) })

    /** 明确的"已发生支出/转账/退款"句式，命中才放行入账 */
    private val paidSignals = listOf(
        "支付成功", "付款成功", "已支付", "已付款", "支付完成", "付款完成",
        "成功支付", "交易成功", "扣款成功", "已扣款",
        "转账成功", "已转账", "退款成功", "已退款", "退款完成",
    )

    // 支付宝实付句式（真机样本）："你有一笔1.87元的支出，点击领取10个支付宝积分。"
    private val expenseStatement = Regex("一笔[0-9][0-9.,]*元的?(?:支出|消费|付款)")

    /**
     * 是否疑似支付/收款类通知（用于粗过滤其他 App 通知）。
     *
     * 只认明确的支付/转账/退款信号或实付句式，营销/事件通知（红包失效、多笔立减、
     * 订单送达）天然不含这些信号，会被拦下；不再用词表硬拒——否则"支付成功，已优惠
     * 0.46，实付14.14元"这类带优惠的真实账单会被整条误杀。
     */
    fun looksLikePayment(packageName: String, title: String, text: String): Boolean {
        val isKnownApp = packageName in setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
        if (!isKnownApp) return false
        val full = "$title $text"
        if (full.contains("验证码") || full.contains("登录确认")) return false
        if (paidSignals.any { it in full }) return true
        if (transferTo.containsMatchIn(full)) return true
        return expenseStatement.containsMatchIn(full)
    }
}
