package com.zhuanz.autoleger.data

import kotlinx.coroutines.flow.first
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 支付宝/微信账单导入器共享类型与工具。
 *
 * Csv 格式解析见 [CsvImporter]；
 * Excel (.xlsx) 格式解析见 [XlsxImporter]。
 */
data class ImportRow(
    val time: Long,
    val merchant: String,
    val amountCents: Long,
    val isExpense: Boolean,
)

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val errors: List<String>,
)

/** 解析一行金额 "12.90" / "¥12.90" 为分 */
fun parseAmountCents(str: String): Long? {
    val clean = str.replace("¥", "").replace("$", "").replace(",", "").trim()
    if (clean.isEmpty()) return null
    val yuan = clean.toDoubleOrNull() ?: return null
    return (yuan * 100 + 0.5).toLong()
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
    var imported = 0
    var skipped = 0
    for (row in rows) {
        val dup = existing.any { tx ->
            tx.amountCents == row.amountCents &&
                tx.merchant == row.merchant &&
                kotlin.math.abs(tx.time - row.time) < 300_000L
        }
        if (dup) {
            skipped++
            continue
        }
        container.transactionDao.insert(
            TransactionEntity(
                type = if (row.isExpense) TYPE_EXPENSE else TYPE_REFUND,
                amountCents = row.amountCents,
                merchant = row.merchant,
                categoryId = null,
                time = row.time,
                source = SOURCE_CSV,
            )
        )
        imported++
    }
    return ImportResult(imported, skipped, emptyList())
}

/** CSV 格式解析器 */
object CsvImporter {

    private enum class Format { ALIPAY, WECHAT }

    suspend fun parse(container: AppContainer, inputStream: InputStream): ImportResult {
        val reader = try {
            BufferedReader(InputStreamReader(inputStream, "GBK"))
        } catch (_: Exception) {
            BufferedReader(InputStreamReader(inputStream, "UTF-8"))
        }

        val headerLine = reader.readLine() ?: return ImportResult(0, 0, listOf("文件为空"))
        val format = detectFormat(headerLine)
        if (format == null) {
            val lines = listOf(headerLine) + reader.readLines().take(2)
            return ImportResult(0, 0, listOf("不支持的格式，列头：${lines.firstOrNull() ?: ""}"))
        }

        val rows = mutableListOf<ImportRow>()
        val errors = mutableListOf<String>()
        var lineNo = 1

        reader.forEachLine { line ->
            lineNo++
            if (line.isBlank()) return@forEachLine
            val row = parseLine(format, line, lineNo)
            if (row != null) {
                rows.add(row)
            } else {
                errors.add("第 $lineNo 行解析失败：${line.take(40)}")
            }
        }
        reader.close()

        if (rows.isEmpty()) {
            return ImportResult(0, 0, errors.take(5).ifEmpty { listOf("未找到有效账单行") })
        }

        val result = importRows(container, rows)
        return result.copy(errors = errors.take(5))
    }

    private fun detectFormat(header: String): Format? {
        val cols = header.split(",").map { it.trim() }
        if (cols.any { it.contains("交易对方") } && cols.any { it.contains("收/支") }) {
            return Format.ALIPAY
        }
        if (cols.any { it.contains("交易类型") } && cols.any { it.contains("交易对方") }) {
            return Format.WECHAT
        }
        return null
    }

    private fun findIndex(cols: List<String>, vararg keywords: String): Int {
        for (kw in keywords) {
            val idx = cols.indexOfFirst { it.contains(kw, ignoreCase = true) }
            if (idx >= 0) return idx
        }
        return -1
    }

    private fun parseLine(format: Format, line: String, lineNo: Int): ImportRow? {
        try {
            val cols = line.split(",").map { it.trim().replace("^\"|\"$".toRegex(), "") }
            return when (format) {
                Format.ALIPAY -> parseAlipay(cols, lineNo)
                Format.WECHAT -> parseWechat(cols, lineNo)
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseAlipay(cols: List<String>, lineNo: Int): ImportRow? {
        val timeIdx = findIndex(cols, "交易时间")
        val merchantIdx = findIndex(cols, "交易对方")
        val typeIdx = findIndex(cols, "收/支")
        val amountIdx = findIndex(cols, "金额")
        if (timeIdx < 0 || merchantIdx < 0 || typeIdx < 0 || amountIdx < 0) return null

        val timeStr = cols.getOrNull(timeIdx)?.trim() ?: return null
        val merchant = cols.getOrNull(merchantIdx)?.trim() ?: return null
        val type = cols.getOrNull(typeIdx)?.trim() ?: ""
        val amountStr = cols.getOrNull(amountIdx)?.trim() ?: return null

        val isExpense = when {
            type.contains("支出") -> true
            type.contains("退款") -> false
            else -> return null
        }

        val time = parseDateTime(timeStr) ?: return null
        val amountCents = parseAmountCents(amountStr) ?: return null
        return ImportRow(time, merchant, amountCents, isExpense)
    }

    private fun parseWechat(cols: List<String>, lineNo: Int): ImportRow? {
        val timeIdx = findIndex(cols, "交易时间")
        val merchantIdx = findIndex(cols, "交易对方")
        val typeIdx = findIndex(cols, "收/支")
        val amountIdx = findIndex(cols, "金额")
        if (timeIdx < 0 || merchantIdx < 0 || typeIdx < 0 || amountIdx < 0) return null

        val timeStr = cols.getOrNull(timeIdx)?.trim() ?: return null
        val merchant = cols.getOrNull(merchantIdx)?.trim() ?: return null
        val type = cols.getOrNull(typeIdx)?.trim() ?: ""
        val amountStr = cols.getOrNull(amountIdx)?.trim() ?: return null

        val isExpense = when {
            type.contains("支出") -> true
            type.contains("退款") -> false
            else -> return null
        }

        val time = parseDateTime(timeStr) ?: return null
        val amountCents = parseAmountCents(amountStr) ?: return null
        return ImportRow(time, merchant, amountCents, isExpense)
    }
}