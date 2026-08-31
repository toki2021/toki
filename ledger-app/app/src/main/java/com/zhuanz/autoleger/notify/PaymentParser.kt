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

    private val amountWithSymbol = Regex("[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)")
    private val amountWithYuan = Regex("([0-9]+(?:\\.[0-9]{1,2})?)\\s*元")

    // 明显不是商户名的行
    private val merchantExclusions = listOf(
        "支付成功", "付款成功", "收款成功", "退款", "已支付", "已付款",
        "微信支付", "支付宝", "凭证", "账单", "余额", "银行卡", "零钱",
        "本次", "您已", "交易", "扣款", "查看", "详情", "点击",
        "实名", "尾号", "支付通知", "服务通知",
        // 支付宝通知句式与结果页按钮词（真机样本）
        "支出", "收入", "一笔", "你有", "完成", "提醒", "领取", "积分", "点此",
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
        val m = amountWithSymbol.find(text) ?: amountWithYuan.find(text) ?: return null
        // BigDecimal 精确换算，避免 Math.round(yuan * 100) 的 double 精度损失
        return m.groupValues[1].toCents()
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

    /** 是否疑似支付/收款类通知（用于粗过滤其他 App 通知） */
    fun looksLikePayment(packageName: String, title: String, text: String): Boolean {
        val isKnownApp = packageName in setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
        if (!isKnownApp) return false
        val full = "$title $text"
        if (full.contains("验证码") || full.contains("登录确认")) return false
        val paymentHint = listOf("支付", "付款", "退款", "收款", "转账", "扣款", "¥", "￥", "元")
        return paymentHint.any { it in full }
    }
}
