package com.zhuanz.autoleger.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Excel (.xlsx) 账单导入器（微信支付账单流水）。
 *
 * 微信导出的 xlsx 结构：
 *  - 文件前几行是指明的说明文本（标题、昵称、统计等）
 *  - 之后是表头行：交易时间 | 交易类型 | 交易对方 | 商品 | 收/支 | 金额(元) | 支付方式 | 当前状态 | 交易单号 | 商户单号 | 备注
 *  - 时间列是 Excel 序列号数字，需转换为毫秒
 *
 * 使用 Android 内置 ZipInputStream + XmlPullParser，无第三方依赖。
 * 先读入内存再解析。按表头列名定位数据列，兼容列序变化。
 */
object XlsxImporter {

    suspend fun parse(container: AppContainer, inputStream: InputStream): ImportResult {
        val entries = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            return ImportResult(0, 0, listOf("无法读取 xlsx 文件：${e.message?.take(60) ?: ""}"))
        }

        // 共享字符串表
        val shared = mutableMapOf<Int, String>()
        entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(ByteArrayInputStream(it), shared) }

        // 工作表
        val sheetName = entries.keys.firstOrNull {
            it.startsWith("xl/worksheets/") && it.endsWith(".xml")
        } ?: return ImportResult(0, 0, listOf("xlsx 中未找到工作表"))

        return parseSheet(ByteArrayInputStream(entries[sheetName]!!), shared, container)
    }

    /** 解析共享字符串表，得到 index -> 文本 */
    private fun parseSharedStrings(input: InputStream, out: MutableMap<Int, String>) {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, "UTF-8")
            var index = 0
            var inSi = false
            var inText = false
            val text = StringBuilder()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "si" -> { inSi = true; text.clear() }
                        "t" -> if (inSi) inText = true
                    }
                    XmlPullParser.TEXT -> if (inText) text.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "t" -> inText = false
                        "si" -> { out[index] = text.toString(); index++; inSi = false }
                    }
                }
            }
        } catch (_: Exception) { /* 无共享字符串表也可继续 */ }
    }

    private data class Cell(val column: Int, val value: String)

    private suspend fun parseSheet(
        input: InputStream,
        shared: Map<Int, String>,
        container: AppContainer,
    ): ImportResult {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, "UTF-8")

            // 逐行解析为 "列号 索引 -> 值"，并存下所有行
            val rows = mutableListOf<Map<Int, String>>()
            var current = mutableMapOf<Int, String>()
            var inCell = false
            var inValue = false
            var cellType: String? = null
            var cellCol = -1
            val cellBuf = StringBuilder()

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "row" -> current = mutableMapOf()
                        "c" -> {
                            inCell = true
                            cellType = parser.getAttributeValue(null, "t")
                            cellCol = colIndex(parser.getAttributeValue(null, "r") ?: "")
                            cellBuf.clear()
                        }
                        "v" -> if (inCell) inValue = true
                    }
                    XmlPullParser.TEXT -> if (inValue) cellBuf.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "v" -> inValue = false
                        "c" -> {
                            inCell = false
                            val raw = cellBuf.toString()
                            val resolved = if (cellType == "s") {
                                val idx = raw.toIntOrNull()
                                if (idx != null) shared[idx] ?: raw else raw
                            } else raw
                            if (cellCol >= 0 && resolved.isNotEmpty()) current[cellCol] = resolved
                        }
                        "row" -> if (current.isNotEmpty()) rows.add(current)
                    }
                }
            }

            if (rows.isEmpty()) return ImportResult(0, 0, listOf("工作表中没有数据"))

            // 在若干行中找表头行（含"交易时间"），并识别来源
            var headerIndex = -1
            var format: String? = null
            for ((idx, row) in rows.withIndex()) {
                val valuesRow = row.values.toList()
                val headerText = row.values.joinToString("|")
                val formatGuess = detectFormat(headerText, valuesRow)
                if (headerText.contains("交易时间") && formatGuess != null) {
                    headerIndex = idx
                    format = formatGuess
                    break
                }
            }
            if (headerIndex < 0 || format == null) {
                return ImportResult(0, 0, listOf("未识别出微信/支付宝账单表头"))
            }

            val header = rows[headerIndex]
            val timeCol = header.entries.firstOrNull { it.value.contains("交易时间") }?.key ?: -1
            val merchantCol = header.entries.firstOrNull { it.value.contains("交易对方") }?.key ?: -1
            val typeCol = header.entries.firstOrNull { it.value.contains("交易类型") }?.key
            val catCol = header.entries.firstOrNull { it.value.contains("交易分类") }?.key
            val ioCol = header.entries.firstOrNull { it.value.contains("收/支") }?.key ?: -1
            val amountCol = header.entries.firstOrNull { it.value.contains("金额") }?.key ?: -1
            if (timeCol < 0 || merchantCol < 0 || ioCol < 0 || amountCol < 0) {
                return ImportResult(0, 0, listOf("表头缺少必需列（交易时间/交易对方/收支/金额）"))
            }

            val importRows = mutableListOf<ImportRow>()
            val errors = mutableListOf<String>()
            var lineNo = 0
            for (rIdx in (headerIndex + 1) until rows.size) {
                lineNo++
                val row = rows[rIdx]
                val rowNo = headerIndex + 1 + lineNo
                val parsed = parseRow(format, row, timeCol, merchantCol, typeCol, catCol, ioCol, amountCol)
                if (parsed != null) importRows.add(parsed)
                else {
                    // 空行忽略；仅记录看似有内容的失败
                    val anyContent = row.values.any { it.isNotBlank() }
                    if (anyContent) errors.add("第 $rowNo 行解析失败：${row.values.take(4).joinToString(",")}")
                }
            }

            if (importRows.isEmpty()) {
                return ImportResult(0, 0, errors.take(5).ifEmpty { listOf("未找到有效账单行") })
            }
            val result = importRows(container, importRows)
            return result.copy(errors = errors.take(5))
        } catch (e: Exception) {
            return ImportResult(0, 0, listOf("xlsx 解析异常：${e.message?.take(60) ?: ""}"))
        }
    }

    /** 从 "A18" 解析出列索引 0 */
    private fun colIndex(ref: String): Int {
        var i = 0
        var col = 0
        while (i < ref.length && ref[i].isLetter()) {
            col = col * 26 + (ref[i] - 'A' + 1)
            i++
        }
        return col - 1
    }

    private fun detectFormat(headerText: String, valuesRow: List<String>): String? {
        return when {
            valuesRow.any { it.contains("交易类型") } -> "WECHAT"
            valuesRow.any { it.contains("交易分类") } -> "ALIPAY"
            headerText.contains("商品说明") -> "ALIPAY"
            headerText.contains("商户单号") -> "WECHAT"
            else -> null
        }
    }

    private fun parseRow(
        format: String,
        row: Map<Int, String>,
        timeCol: Int, merchantCol: Int,
        typeCol: Int?, catCol: Int?,
        ioCol: Int, amountCol: Int,
    ): ImportRow? {
        val timeStr = row[timeCol]?.trim() ?: return null
        val merchant = row[merchantCol]?.trim()?.takeIf { it.isNotBlank() && it != "/" } ?: return null
        val io = row[ioCol]?.trim() ?: ""
        val type = (typeCol?.let { row[it] } ?: catCol?.let { row[it] } ?: "").trim()
        val amountStr = row[amountCol]?.trim() ?: return null

        // 判断支出 / 退款
        val isExpense = when (format) {
            "ALIPAY" -> when {
                io.contains("支出") -> true
                // 支付宝退款：交易分类列含"退款"
                io.contains("退款") || type.contains("退款") -> false
                else -> return null
            }
            else -> when {
                io.contains("支出") -> true
                // 微信退款：收/支为"收入"，但交易类型含"退款"
                io.contains("收入") && type.contains("退款") -> false
                else -> return null
            }
        }

        val time = parseCellTime(timeStr) ?: return null
        val amountCents = parseAmountCents(amountStr) ?: return null
        return ImportRow(time, merchant, amountCents, isExpense)
    }

    /**
     * 时间可能是文本（"2026-09-01 20:25:28"）或 Excel 序列号（"46266.71"）。
     */
    private fun parseCellTime(str: String): Long? {
        if (str.contains("-")) {
            return parseDateTime(str)
        }
        val serial = str.toDoubleOrNull() ?: return null
        if (serial > 70000) return null // 数值过大的非有效序列
        // Excel 序列号 -> epoch 毫秒：25569 = 1970-01-01
        return ((serial - 25569) * 86400000L).toLong()
    }
}