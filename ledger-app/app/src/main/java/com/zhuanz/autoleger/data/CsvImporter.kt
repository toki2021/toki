package com.zhuanz.autoleger.data

import kotlinx.coroutines.flow.first
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** 导入的一行账单 */
data class ImportRow(
    val time: Long,
    val merchant: String,
    val amountCents: Long,
    val isExpense: Boolean,
)

/** 导入结果 */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val errors: List<String>,
)

/** 金额（元/字符串）转分 */
fun parseAmountCents(str: String): Long? {
    val clean = str.replace("¥", "").replace("$", "").replace(",", "").trim()
    if (clean.isEmpty()) return null
    val yuan = clean.toDoubleOrNull() ?: return null
    // 用 BigDecimal 避免浮点误差
    return try {
        java.math.BigDecimal(clean).movePointRight(2).longValueExact()
    } catch (_: Exception) {
        (yuan * 100 + 0.5).toLong()
    }
}

/** 解析 "2024-01-01 12:00:00" 或 "2024-01-01 12:00" 为 epoch 毫秒 */
val dateTimePattern = Regex("""(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2})(?::(\d{2}))?""")

fun parseDateTime(str: String): Long? {
    val m = dateTimePattern.find(str) ?: return null
    val g = m.groupValues
    if (g.size < 6) return null
    val y = g[1].toIntOrNull() ?: return null
    val mo = g[2].toIntOrNull() ?: return null
    val d = g[3].toIntOrNull() ?: return null
    val h = g[4].toIntOrNull() ?: return null
    val mi = g[5].toIntOrNull() ?: return null
    val s = g.getOrNull(6)?.toIntOrNull() ?: 0
    // 范围校验，避免 Calendar 自动归一化非法日期
    if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || s !in 0..59) return null
    val cal = java.util.Calendar.getInstance().apply {
        set(y, mo - 1, d, h, mi, s)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

/**
 * 将解析后的行批量写入数据库，自动去重（相同金额+商户+时间±5分钟跳过）。
 */
suspend fun importRows(container: AppContainer, rows: List<ImportRow>): ImportResult {
    val existing = container.transactionDao.observeAll().first()
    // 一次取回规则+分类 id，避免逐行重复查询；未命中规则则落到"其他"
    val rules = container.ruleDao.observeAll().first()
    val catById = container.categoryDao.observeAll().first().associate { it.id to it.name }
    val otherId = catById.entries.firstOrNull { it.value == "其他" }?.key
    val matchCategoryId = { merchant: String ->
        rules.firstOrNull { merchant.contains(it.pattern, ignoreCase = true) }?.categoryId ?: otherId
    }
    var imported = 0
    var skipped = 0
    for (row in rows) {
        val dup = existing.filter { tx ->
            tx.amountCents == row.amountCents &&
                tx.merchant == row.merchant &&
                kotlin.math.abs(tx.time - row.time) < 300_000L
        }
        if (dup.isNotEmpty()) {
            // 已存在：若之前是"未知"分类则补上分类（重新导入可修复旧数据），否则跳过
            val categoryId = matchCategoryId(row.merchant)
            dup.filter { it.categoryId == null }.forEach { tx ->
                container.transactionDao.update(tx.copy(categoryId = categoryId))
            }
            skipped++
            continue
        }
        container.transactionDao.insert(
            TransactionEntity(
                type = if (row.isExpense) TYPE_EXPENSE else TYPE_REFUND,
                amountCents = row.amountCents,
                merchant = row.merchant,
                categoryId = matchCategoryId(row.merchant),
                time = row.time,
                source = SOURCE_CSV,
            )
        )
        imported++
    }
    return ImportResult(imported, skipped, emptyList())
}

/**
 * 支付宝/微信账单 CSV 导入器。
 *
 * 支付宝导出的 CSV 结构：
 *  - 文件开头是大量说明文本（导出信息、特别提示等）
 *  - 实际表头在第 20+ 行，形如：
 *      交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
 *  - 收/支列值：支出 / 退款 / 不计收支
 *
 * 处理逻辑：逐行扫描，跳过说明文本，找到真正的表头行后再按列名定位数据。
 * 支持 GBK / UTF-8 编码。
 */
object CsvImporter {

    suspend fun parse(container: AppContainer, inputStream: InputStream): ImportResult {
        // 先整体读入字节，探测编码（支持 BOM / UTF-8 / GBK）
        val raw = inputStream.readBytes()
        val charset = detectCharset(raw)
        val reader = BufferedReader(InputStreamReader(raw.inputStream(), charset))

        // 逐行扫描，找表头行（同时识别来源）
        var header: List<String>? = null
        var format: String? = null
        var errorLines = mutableListOf<String>()
        var candidates = mutableListOf<String>()

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cols = splitCsvLine(line)
            if (cols.size < 3) continue
            val joined = cols.joinToString("|")
            val guess = detectFormat(joined, cols)
            if (joined.contains("交易时间") && guess != null) {
                header = cols
                format = guess
                break
            }
            candidates.add(line.take(40))
        }

        if (header == null || format == null) {
            reader.close()
            return ImportResult(0, 0, listOf("未识别出账单表头，可能是文件格式不对。前几行：${candidates.take(3).joinToString(" / ")}"))
        }

        val h = header!!
        val timeCol = h.indexOfFirst { it.contains("交易时间") }
        val merchantCol = h.indexOfFirst { it.contains("交易对方") }
        val ioCol = h.indexOfFirst { it.contains("收/支") }
        val amountCol = h.indexOfFirst { it.contains("金额") }
        // 支付宝的"退款"体现在"交易分类"列；微信 CSV 则是"交易类型"列
        val categoryCol = h.indexOfFirst { it.contains("交易分类") }
        val typeCol = h.indexOfFirst { it.contains("交易类型") }
        if (timeCol < 0 || merchantCol < 0 || ioCol < 0 || amountCol < 0) {
            reader.close()
            return ImportResult(0, 0, listOf("表头缺少必需列（交易时间/交易对方/收支/金额）"))
        }

        val importRows = mutableListOf<ImportRow>()
        val errors = mutableListOf<String>()
        var lineNo = 0
        var line: String
        while (true) {
            line = reader.readLine() ?: break
            lineNo++
            if (line.isBlank()) continue
            val pos = splitCsvLine(line)
            val row = parseRow(format, pos, timeCol, merchantCol, ioCol, categoryCol, typeCol, amountCol, lineNo)
            if (row != null) importRows.add(row)
            else if (pos.any { it.isNotBlank() }) {
                errors.add("第 $lineNo 行解析失败：${pos.take(4).joinToString(",")}")
            }
        }
        reader.close()

        if (importRows.isEmpty()) {
            return ImportResult(0, 0, errors.take(5).ifEmpty { listOf("未找到有效账单行") })
        }
        val result = importRows(container, importRows)
        return result.copy(errors = errors.take(5))
    }

    /** 探测 CSV 编码：BOM 优先，其次严格 UTF-8，失败则回退 GBK，再以"是否含表头"二次确认 */
    internal fun detectCharset(raw: ByteArray): java.nio.charset.Charset {
        val hasBom = raw.size >= 3 &&
            (raw[0].toInt() and 0xFF) == 0xEF && (raw[1].toInt() and 0xFF) == 0xBB && (raw[2].toInt() and 0xFF) == 0xBF
        if (hasBom) return Charsets.UTF_8

        val gbk = java.nio.charset.Charset.forName("GBK")
        val utf8 = Charsets.UTF_8
        // 严格 UTF-8 是否合法
        val utf8Valid = try {
            utf8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(raw))
            true
        } catch (_: Exception) {
            false
        }
        if (!utf8Valid) return gbk

        // UTF-8 合法时再确认表头是否真的出现；否则极可能是"恰好合法"的 GBK 文件
        val gbkHasHeader = String(raw, gbk).contains("交易时间") ||
            String(raw, gbk).contains("交易分类") ||
            String(raw, gbk).contains("交易类型")
        if (gbkHasHeader) {
            val utf8HasHeader = String(raw, utf8).contains("交易时间") ||
                String(raw, utf8).contains("交易分类") ||
                String(raw, utf8).contains("交易类型")
            if (!utf8HasHeader) return gbk
        }
        return utf8
    }

    /** 正确处理带引号的 CSV 字段（内容含逗号时用引号包裹） */
    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuote && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"'); i++
                    } else inQuote = !inQuote
                }
                c == ',' && !inQuote -> { result.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString().trim())
        return result
    }

    private fun detectFormat(joined: String, cols: List<String>): String? {
        return when {
            cols.any { it.contains("交易类型") } -> "WECHAT"
            cols.any { it.contains("交易分类") } -> "ALIPAY"
            joined.contains("商品说明") -> "ALIPAY"
            joined.contains("商户单号") -> "WECHAT"
            else -> null
        }
    }

    private fun parseRow(
        format: String?,
        pos: List<String>,
        timeCol: Int, merchantCol: Int, ioCol: Int, categoryCol: Int, typeCol: Int, amountCol: Int,
        lineNo: Int,
    ): ImportRow? {
        val timeStr = pos.getOrNull(timeCol)?.trim() ?: return null
        val merchant = pos.getOrNull(merchantCol)?.trim()?.takeIf { it.isNotBlank() && it != "/" } ?: return null
        val io = pos.getOrNull(ioCol)?.trim() ?: ""
        val category = if (categoryCol >= 0) pos.getOrNull(categoryCol)?.trim() ?: "" else ""
        val type = if (typeCol >= 0) pos.getOrNull(typeCol)?.trim() ?: "" else ""
        val amountStr = pos.getOrNull(amountCol)?.trim() ?: return null
        val typeInfo = if (category.isNotBlank()) category else type

        val isExpense = when (format) {
            "ALIPAY" -> when {
                io.contains("支出") -> true
                // 支付宝退款：收/支为"不计收支/收入/退款"，交易分类列含"退款"
                io.contains("退款") || typeInfo.contains("退款") -> false
                else -> return null
            }
            "WECHAT" -> when {
                io.contains("支出") -> true
                // 微信退款：收/支为"收入"，但交易类型含"退款"
                io.contains("收入") && typeInfo.contains("退款") -> false
                else -> return null
            }
            else -> return null
        }

        val time = parseDateTime(timeStr) ?: return null
        val amountCents = parseAmountCents(amountStr) ?: return null
        return ImportRow(time, merchant, amountCents, isExpense)
    }
}