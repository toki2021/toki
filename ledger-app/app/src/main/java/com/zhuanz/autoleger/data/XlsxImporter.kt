package com.zhuanz.autoleger.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Excel (.xlsx) 账单导入器。
 * 使用 Android 内置 ZipInputStream + XmlPullParser，无需额外依赖。
 * 支持支付宝/微信导出的 .xlsx 格式。
 *
 * 解析策略：先将 ZIP 全部读入内存（账单文件通常很小），再解析 XML，
 * 避免 ZipInputStream 不能 seek 的问题。
 */
object XlsxImporter {

    suspend fun parse(container: AppContainer, inputStream: InputStream): ImportResult {
        // 第一步：将 ZIP 全部读入内存
        val entries = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(inputStream).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            return ImportResult(0, 0, listOf("无法读取 xlsx 文件：${e.message?.take(60) ?: ""}"))
        }

        // 第二步：解析共享字符串表
        val sharedStrings = mutableMapOf<Int, String>()
        entries["xl/sharedStrings.xml"]?.let { bytes ->
            parseSharedStrings(ByteArrayInputStream(bytes), sharedStrings)
        }

        // 第三步：解析工作表
        val sheetEntry = entries.keys.firstOrNull {
            it.startsWith("xl/worksheets/") && it.endsWith(".xml")
        } ?: return ImportResult(0, 0, listOf("xlsx 中未找到工作表"))

        return parseSheet(ByteArrayInputStream(entries[sheetEntry]!!), sharedStrings, container)
    }

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
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "si" -> { inSi = true; text.clear() }
                            "t" -> if (inSi) inText = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inText) text.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "t" -> inText = false
                            "si" -> {
                                out[index] = text.toString()
                                index++
                                inSi = false
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { /* 无共享字符串表也可继续 */ }
    }

    private suspend fun parseSheet(
        input: InputStream,
        sharedStrings: Map<Int, String>,
        container: AppContainer,
    ): ImportResult {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, "UTF-8")

            val rows = mutableListOf<List<String>>()
            val currentRow = mutableListOf<String>()
            var inCell = false
            var inCellValue = false
            var cellType: String? = null
            val cellValue = StringBuilder()

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "c" -> {
                                inCell = true
                                cellType = parser.getAttributeValue(null, "t")
                                cellValue.clear()
                            }
                            "v" -> if (inCell) inCellValue = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inCellValue) cellValue.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "v" -> inCellValue = false
                            "c" -> {
                                inCell = false
                                val raw = cellValue.toString()
                                val resolved = if (cellType == "s") {
                                    val idx = raw.toIntOrNull()
                                    if (idx != null) sharedStrings[idx] ?: raw else raw
                                } else {
                                    raw
                                }
                                currentRow.add(resolved)
                            }
                            "row" -> {
                                if (currentRow.isNotEmpty()) {
                                    rows.add(currentRow.toList())
                                    currentRow.clear()
                                }
                            }
                        }
                    }
                }
            }

            if (rows.isEmpty()) return ImportResult(0, 0, listOf("工作表中没有数据"))

            // 第一行是列头
            val header = rows.first()
            val format = detectFormat(header)
            if (format == null) {
                return ImportResult(0, 0, listOf("不支持的格式，列头：${header.joinToString(",")}"))
            }

            // 解析数据行
            val importRows = mutableListOf<ImportRow>()
            val errors = mutableListOf<String>()
            var lineNo = 1
            for (dataRow in rows.drop(1)) {
                lineNo++
                val row = parseRow(format, header, dataRow)
                if (row != null) {
                    importRows.add(row)
                } else {
                    errors.add("第 $lineNo 行解析失败：${dataRow.joinToString(",").take(40)}")
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

    private fun detectFormat(header: List<String>): String? {
        if (header.any { it.contains("交易对方") } && header.any { it.contains("收/支") }) return "ALIPAY"
        if (header.any { it.contains("交易类型") } && header.any { it.contains("交易对方") }) return "WECHAT"
        return null
    }

    private fun findIndex(cols: List<String>, vararg keywords: String): Int {
        for (kw in keywords) {
            val idx = cols.indexOfFirst { it.contains(kw, ignoreCase = true) }
            if (idx >= 0) return idx
        }
        return -1
    }

    private fun parseRow(format: String?, header: List<String>, cols: List<String>): ImportRow? {
        return when (format) {
            "ALIPAY" -> parseAlipay(header, cols)
            "WECHAT" -> parseWechat(header, cols)
            else -> null
        }
    }

    private fun parseAlipay(header: List<String>, cols: List<String>): ImportRow? {
        val timeIdx = findIndex(header, "交易时间")
        val merchantIdx = findIndex(header, "交易对方")
        val typeIdx = findIndex(header, "收/支")
        val amountIdx = findIndex(header, "金额")
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

    private fun parseWechat(header: List<String>, cols: List<String>): ImportRow? {
        val timeIdx = findIndex(header, "交易时间")
        val merchantIdx = findIndex(header, "交易对方")
        val typeIdx = findIndex(header, "收/支")
        val amountIdx = findIndex(header, "金额")
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