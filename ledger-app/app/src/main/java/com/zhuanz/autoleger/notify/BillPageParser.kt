package com.zhuanz.autoleger.notify

import com.zhuanz.autoleger.data.toCents

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

    // 带负号的支出金额：支付宝"账单详情"等页面显示为 -16.60（无 ¥、带负号）。
    // 仅匹配带小数，避免把日期里的 "-09" 误当金额。
    private val negativeAmountRe = Regex("-\\s*([0-9]+\\.\\d{1,2})")

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

    // OCR 强页面特征：结果页/凭证页才算"可凭空新建待确认"的页面。
    // 只命中弱特征（如"恭喜获得¥0.46红包"营销弹窗）不允许新建账单
    private val ocrStrongMarkers = listOf(
        "支付成功", "付款成功", "成功付款", "支付完成", "已支付", "已付款", "已完成支付",
        "交易成功", "转账成功", "还款成功", "转入成功", "转出成功", "充值成功",
        "支付凭证", "付款凭证", "交易凭证",
        "付款详情", "交易详情", "账单详情",
        "已入账", "已存入零钱",
    )

    // 优惠/抵扣语境词：整行含这些词的 OCR 行，其金额不是实付（"已优惠¥0.46"）
    private val discountWords = listOf(
        "优惠", "立减", "满减", "折扣", "已省", "省了", "节省", "减免", "抵扣", "返现", "已减",
    )
    // 拼接文本里金额前 6 字内的语境词（比整行判定更细，额外含"券/红包"）
    private val discountPrefix = discountWords + listOf("券", "红包")

    // 明确实付标签："实付¥14.14"、"支付金额：14.14元"、"合计 ¥14.14"
    private val labeledAmount = Regex(
        "(?:实付|实际支付|实付款|支付金额|付款金额|交易金额|合计)[：:\\s]*[¥￥]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    )

    // 裸数字行（OCR 把大字号金额拆成 "¥"+"14.14" 两行时，第二行就是这个形态）
    private val bareNumber = Regex("[0-9][0-9,]*(?:\\.[0-9]{1,2})?")

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

    fun parse(
        texts: List<String>,
        fromOcr: Boolean = false,
        expectedAmountCents: Long? = null,
    ): Bill? {
        val markers = if (fromOcr) ocrMarkers else pageMarkers
        if (texts.none { t -> markers.any { m -> t.contains(m) } }) return null
        val joined = texts.joinToString(" ")
        // BigDecimal 精确换算，避免 Math.round(amount * 100) 的 double 精度损失
        val amountCents = pickAmount(joined, expectedAmountCents) ?: return null
        if (amountCents <= 0) return null

        val merchant = extractMerchant(texts) ?: return null
        return Bill(amountCents, merchant)
    }

    /**
     * OCR 模式专用：以金额行锚点提取商户。
     * 真实样本（用户手机实测）："¥0.10 / 收款方(误识为女款方) / tokizero / 使用零钱支付"
     *
     * 金额挑选规则（"付14.60优惠0.46实付14.14"这类页面曾把 0.46 记成入账金额）：
     * 1) 通知侧解析出的实付金额是权威值（expectedAmountCents），页面上确认存在即采用；
     * 2) 剔除"已优惠/立减"等语境行的金额，OCR 把大字号金额拆成 "¥"+"14.14" 两行时先拼回；
     * 3) 剩余主金额唯一才采用，多个（列表页）一律放弃——宁可少记，不可错记。
     */
    fun parseOcr(
        lines: List<String>,
        sourceFallback: String? = null,
        expectedAmountCents: Long? = null,
    ): Bill? {
        val norm = joinSplitYuan(lines)
        // 1) 页面须含资金类关键词（排除随机 H5 页面）
        if (norm.none { t -> ocrMarkers.any { m -> t.contains(m) } }) return null

        // 2) 金额：期望金额锚定 → 剔除优惠语境后须唯一
        val amountCents = pickOcrAmount(norm, expectedAmountCents) ?: return null

        // 3) 商户/对象尽力提取：动宾句式 → 标签值 → 金额行邻近 → 页面顶部干净行
        val merchant = norm.firstNotNullOfOrNull { l ->
            payTo.find(l)?.groupValues?.get(1)?.let { sanitize(it) }
                ?: transferTo.find(l)?.groupValues?.get(1)?.let { sanitize(it) }
        }
            ?: run {
                val amountIdx = norm.indexOfFirst {
                    ocrAmountToCents(it) == amountCents && discountWords.none { w -> w in it }
                }
                val candidates = buildList {
                    if (amountIdx >= 0) {
                        addAll(norm.drop(amountIdx + 1).take(5))
                        addAll(norm.take(amountIdx).reversed().take(4))
                    }
                }
                candidates.firstNotNullOfOrNull { ocrCandidateMerchant(it) }
            }
            ?: norm.firstNotNullOfOrNull { ocrCandidateMerchant(it) }
            ?: sourceFallback?.let { sanitize(it) }
            ?: return null

        // 4) 强页面特征：只有结果页/凭证页允许 OCR 凭空新建待确认条目
        val strongPage = norm.any { t -> ocrStrongMarkers.any { m -> t.contains(m) } }
        return Bill(amountCents, merchant, strongPage = strongPage)
    }

    /**
     * 拼接文本（无障碍树/通知）的金额挑选：期望金额 → 实付标签 → 主金额唯一。
     * 期望金额来自通知侧解析的实付值，页面只需确认存在即采用——OCR/读屏不覆盖通知金额。
     */
    private fun pickAmount(text: String, expected: Long?): Long? {
        expected?.let { e -> if (mainAmounts(text).contains(e)) return e }
        labeledAmount.find(text)?.groupValues?.get(1)?.replace(",", "")
            ?.toCents()?.takeIf { it > 0 }?.let { return it }
        // 带负号的唯一支出金额（支付宝账单详情等无 ¥ 页面）：出现次数唯一才采用
        val negatives = negativeAmounts(text)
        if (negatives.size == 1) return negatives[0]
        return mainAmounts(text).singleOrNull()
    }

    /** 带负号的支出金额集合（-16.60），剔除"券/红包/积分/优惠"语境 */
    private fun negativeAmounts(text: String): List<Long> {
        val out = mutableListOf<Long>()
        for (m in negativeAmountRe.findAll(text)) {
            val prefix = text.substring(maxOf(0, m.range.first - 6), m.range.first)
            if (discountPrefix.any { it in prefix }) continue
            m.groupValues[1].replace(",", "").toCents()?.takeIf { it > 0 }?.let { out.add(it) }
        }
        return out.distinct()
    }

    /** 拼接文本中所有"主金额"（剔除"已优惠/立减/红包"等语境的金额），按出现顺序去重 */
    private fun mainAmounts(text: String): List<Long> {
        val matches = amountRe.findAll(text).toList()
        return matches
            .filter { m ->
                val prefix = text.substring(maxOf(0, m.range.first - 6), m.range.first)
                discountPrefix.none { it in prefix }
            }
            .mapNotNull { it.groupValues[1].replace(",", "").toCents()?.takeIf { c -> c > 0 } }
            .distinct()
    }

    /** OCR 行的金额挑选：期望金额锚定 → 剔除优惠/券/红包/积分语境行后，负号唯一 → 普通金额唯一 */
    private fun pickOcrAmount(lines: List<String>, expected: Long?): Long? {
        val mains = mutableListOf<Long>()
        val negatives = mutableListOf<Long>()
        for ((i, l) in lines.withIndex()) {
            // 行内含"已优惠/立减"等语境词 → 该行金额不是实付
            if (discountWords.any { it in l }) continue
            // 语境词单独成行（"已优惠" + "¥0.46" 被拆成两行）时，紧随其后的金额也是优惠金额
            val prev = lines.getOrNull(i - 1)
            if (prev != null && ocrAmountToCents(prev) == null && discountWords.any { it in prev }) continue
            // 券/红包/积分行的金额不是实付（"13元新人优惠券"）
            if (l.contains("券") || l.contains("红包") || l.contains("积分")) continue
            ocrAmountToCents(l)?.let { mains.add(it) }
            parseOcrNegative(l)?.let { negatives.add(it) }
        }
        val distinct = mains.distinct()
        expected?.let { e -> if (e in distinct) return e }
        val distNeg = negatives.distinct()
        if (distNeg.size == 1) return distNeg[0]
        return distinct.singleOrNull()
    }

    /** OCR 行里的负号支出金额（-16.60），金额须带小数避免误抓日期/单号 */
    private fun parseOcrNegative(line: String): Long? =
        negativeAmountRe.find(line)?.groupValues?.get(1)?.replace(",", "")
            ?.toCents()?.takeIf { it > 0 }

    /**
     * OCR 把大字号金额拆成两行的常见情形拼回一行："¥" + "14.14" → "¥14.14"，
     * 或 "已支付¥" + "14.14" → "已支付¥14.14"。不拼回的话这两行都匹配不到金额，
     * 页面上唯一能识别的 ¥ 金额会剩下小字号的"已优惠¥0.46"——正是误记账的来源
     */
    private fun joinSplitYuan(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val cur = lines[i].trim()
            val next = lines.getOrNull(i + 1)?.trim()
            val yuanOnly = cur == "¥" || cur == "￥" ||
                ((cur.endsWith("¥") || cur.endsWith("￥")) && cur.none { it.isDigit() })
            if (yuanOnly && next != null && bareNumber.matches(next)) {
                out.add(cur + next)
                i += 2
            } else {
                out.add(cur)
                i++
            }
        }
        return out
    }


    /** 只提取商户；金额好办，商户才是读屏的价值所在 */
    private fun ocrAmountToCents(line: String): Long? =
        amountRe.find(line)?.groupValues?.get(1)?.replace(",", "")
            ?.toCents()?.takeIf { it > 0 }

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
        // 兜底：无"商户/收款方"标签的账单详情页（如支付宝账单详情），商户是裸文本。
        // 取第一个干净的商户候选（排除金额/时间/状态/账单等噪音），宁缺勿错。
        return texts.firstNotNullOfOrNull { t ->
            val s = t.trim()
            if (s.isEmpty()) null else cleanFallbackMerchant(s)
        }
    }

    /** 兜底商户候选的干净性检查：排除金额、时间、噪声词、状态词、纯数字 */
    private fun cleanFallbackMerchant(t: String): String? {
        val s = t.trim().trimEnd('：', ':', ' ', '>')
        if (s.length < 2 || s.length > 30) return null
        if (s.contains('¥') || s.contains('￥') || s.contains('元')) return null
        if (timeOrDate.containsMatchIn(s)) return null
        if (noiseWords.any { it in s }) return null
        if (noiseLabels.any { s.contains(it) }) return null
        val hasCjk = s.any { it.code in 0x4E00..0x9FFF }
        val hasLetter = s.any { it.isLetter() }
        if (s.any { it.isDigit() } && !hasCjk) return null
        if (!hasCjk && !hasLetter) return null
        return sanitize(s)
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
