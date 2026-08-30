package com.zhuanz.autoleger.notify

/**
 * 解析微信/支付宝账单页面的文本。
 *
 * 两个入口：
 * - parse(texts)：无障碍树文本（原生页面），按"页面特征 + 标签找值"提取
 * - parseOcr(lines)：OCR 文本行（微信 H5 页面对无障碍不可见，只能靠截屏识别）。
 *   OCR 有误识噪声（"微信支付凭证"→"啟信支付凭证"、"收款方"→"女款方"），
 *   因此页面特征放宽为关键词、商户改用"金额行锚点 + 动宾句式"提取，
 *   并拒绝含噪声词的候选——宁可提不出，也不能错配。
 */
object BillPageParser {

    data class Bill(
        val amountCents: Long,
        val merchant: String,
        /** 强特征：命中"支付成功/微信支付凭证"等结果页标志（新建账单必须） */
        val strongPage: Boolean = false,
    )

    private val amountRe = Regex("[¥￥]\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")

    // 页面身份特征：支付完成后的结果页 + 账单详情页都算（结果页在每次支付后自动弹出）
    private val pageMarkers = listOf(
        "微信支付凭证", "账单详情", "交易详情", "付款详情", "支付成功", "付款成功",
        "成功付款", "支付完成", "已入账", "已存入零钱", "已付款",
    )

    // OCR 模式的页面特征：任意资金类关键词（通用，不再逐词打补丁）
    private val ocrMarkers = listOf(
        "支付", "付款", "收款", "转账", "转入", "转出", "还款", "充值",
        "余额", "零钱", "账单", "凭证", "入账", "银行卡", "红包",
    )

    // 商户字段的标签（微信"收款方"，支付宝"商家"等）
    private val merchantLabels = listOf("收款方", "收款商户", "收款商家", "商家", "商户", "对方账户")

    // 结果页里的动宾结构："付款给XX"、"转账给XX"、"向XX付款"
    private val payTo = Regex("(?:付款给|转账给|支付给|付款至)(.{2,20})")
    private val transferTo = Regex("向(.{2,20}?)(?:转账|付款|支付)")

    // 值字段里不该当成商户的标签/噪音
    private val noiseLabels = merchantLabels + listOf(
        "付款时间", "交易单号", "商户单号", "支付方式", "当前状态", "商品说明",
        "备注", "转账说明", "退款", "优惠", "红包", "金额", "尾号",
    )

    // OCR 误识产生的噪声词（含这些词的商户候选直接拒绝）
    private val noiseWords = listOf(
        "支付", "付款", "收款", "退款", "成功", "失败", "账单", "凭证",
        "金额", "零钱", "银行卡", "余额", "正在加载",
    )

    // 时间/日期行（历史列表页里大量出现，不是商户）
    private val timeOrDate = Regex("^(\\d{1,2}:\\d{2}|\\d{1,2}月\\d{1,2}日|\\d{4}[年/-])")

    fun parse(texts: List<String>, fromOcr: Boolean = false): Bill? {
        val markers = if (fromOcr) ocrMarkers else pageMarkers
        if (texts.none { t -> markers.any { m -> t.contains(m) } }) return null
        val joined = texts.joinToString(" ")
        val amount = amountRe.find(joined)?.groupValues?.get(1)
            ?.replace(",", "")?.toDoubleOrNull() ?: return null
        val amountCents = Math.round(amount * 100)
        if (amountCents <= 0) return null

        val merchant = extractMerchant(texts) ?: return null
        return Bill(amountCents, merchant)
    }

    /**
     * OCR 模式专用：以金额行锚点提取商户。
     * 真实样本（用户手机实测）："¥0.10 / 收款方(误识为女款方) / tokizero / 使用零钱支付"
     */
    fun parseOcr(lines: List<String>, sourceFallback: String? = null): Bill? {
        // 1) 页面须含资金类关键词（排除随机 H5 页面）
        if (lines.none { t -> ocrMarkers.any { m -> t.contains(m) } }) return null

        // 2) 通用结果页判定：恰好一个金额。多个金额 = 列表页，放弃
        val distinctAmounts = lines.mapNotNull { ocrAmountToCents(it) }.distinct()
        if (distinctAmounts.size != 1) return null
        val amountCents = distinctAmounts.first()

        // 3) 商户/对象尽力提取：动宾句式 → 标签值 → 金额行邻近 → 页面顶部干净行
        val merchant = lines.firstNotNullOfOrNull { l ->
            payTo.find(l)?.groupValues?.get(1)?.let { sanitize(it) }
                ?: transferTo.find(l)?.groupValues?.get(1)?.let { sanitize(it) }
        }
            ?: run {
                val amountIdx = lines.indexOfFirst { amountRe.containsMatchIn(it) }
                val candidates = buildList {
                    if (amountIdx >= 0) {
                        addAll(lines.drop(amountIdx + 1).take(5))
                        addAll(lines.take(amountIdx).reversed().take(4))
                    }
                }
                candidates.firstNotNullOfOrNull { ocrCandidateMerchant(it) }
            }
            ?: lines.firstNotNullOfOrNull { ocrCandidateMerchant(it) }
            ?: sourceFallback?.let { sanitize(it) }
            ?: return null

        return Bill(amountCents, merchant, strongPage = true)
    }


    /** 只提取商户；金额好办，商户才是读屏的价值所在 */
    private fun ocrAmountToCents(line: String): Long? =
        amountRe.find(line)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            ?.let { Math.round(it * 100) }?.takeIf { it > 0 }

    /** OCR 行是否像商户/对象名：拒绝金额、时间日期、噪声词、纯数字符号 */
    private fun ocrCandidateMerchant(s: String): String? {
        val t = s.trim().trimEnd('：', ':', ' ', '>')
        if (t.length < 2 || t.length > 30) return null
        if (t.contains('¥') || t.contains('￥') || t.contains("元")) return null
        if (timeOrDate.containsMatchIn(t)) return null
        if (noiseWords.any { it in t }) return null
        if (noiseLabels.any { t.contains(it) }) return null
        val hasCjk = t.any { it.code in 0x4E00..0x9FFF }
        val hasLetter = t.any { it.isLetter() }
        if (t.any { it.isDigit() } && !hasCjk) return null
        if (!hasCjk && !hasLetter) return null
        return t
    }

    fun extractMerchant(texts: List<String>): String? {
        for (i in texts.indices) {
            val t = texts[i].trim()

            // 结果页的动宾结构："付款给瑞幸咖啡" / "向XX付款"
            payTo.find(t)?.groupValues?.get(1)?.let { sanitize(it)?.let { m -> return m } }
            transferTo.find(t)?.groupValues?.get(1)?.let { sanitize(it)?.let { m -> return m } }

            val label = merchantLabels.firstOrNull { t.startsWith(it) } ?: continue

            // 值可能跟在标签同一节点里："收款方：XX商户"
            val sameNode = t.removePrefix(label).trimStart('：', ':', '·', '-', ' ', '\n')
            if (sameNode.length >= 2 && !sameNode.contains('¥') && noiseLabels.none { sameNode.startsWith(it) }) {
                return sanitize(sameNode)
            }

            // 更常见：标签和值是相邻节点，取后面 1~3 个节点里第一个像商户名的
            for (j in (i + 1)..minOf(i + 3, texts.size - 1)) {
                val v = texts[j].trim()
                if (v.isEmpty()) continue
                if (v.contains('¥') || v.contains("元")) continue
                if (noiseLabels.any { v.startsWith(it) }) break
                if (v.length >= 2 && v.length <= 30) return sanitize(v)
            }
        }
        return null
    }

    private fun sanitize(raw: String): String? {
        val cleaned = raw.trim().trimEnd('：', ':', ' ')
        // "XX(个体经营)"这类后缀对分类没帮助，去掉括号段
        val trimmed = cleaned.replace(Regex("（[^）]*）|\\([^)]*\\)"), "").trim()
        val result = (trimmed.ifBlank { cleaned }).takeIf { it.length in 2..30 } ?: return null
        if (noiseWords.any { it in result }) return null
        return result
    }
}
