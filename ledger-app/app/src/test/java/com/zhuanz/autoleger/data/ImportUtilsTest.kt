package com.zhuanz.autoleger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class ImportUtilsTest {

    @Test
    fun 支付宝CSV_时间格式() {
        // 支付宝导出的时间 "2026-09-01 20:25:28"
        val ms = parseDateTime("2026-09-01 20:25:28")
        assertEquals("2026-09-01 20:25", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(ms))
    }

    @Test
    fun 微信xlsx_时间序列号转日期() {
        // 微信表里时间列是 Excel 序列号（数值），已知 45292 = 2024-01-01
        val serial = 45292.0
        val ms = ((serial - 25569) * 86400000L).toLong()
        assertEquals("2024-01-01", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(ms))

        // 真实微信数据首条的时间序列号，应落在导出范围内的 2026 年
        val first = 46266.70866898148
        val firstMs = ((first - 25569) * 86400000L).toLong()
        val dd = SimpleDateFormat("yyyy", Locale.US).format(firstMs)
        assertEquals("2026", dd)
    }

    @Test
    fun 金额文本转分() {
        assertEquals(1052L, parseAmountCents("10.52"))
        assertEquals(1290L, parseAmountCents("12.90"))
        assertEquals(1500L, parseAmountCents("15"))
        assertEquals(703L, parseAmountCents("7.03"))
        assertEquals(0L, parseAmountCents("0"))
        assertEquals(123456L, parseAmountCents("1234.56"))
    }

    @Test
    fun 金额含符号() {
        assertEquals(1414L, parseAmountCents("¥14.14"))
        assertEquals(2400L, parseAmountCents("24"))
    }

    @Test
    fun 无效值() {
        assertNull(parseAmountCents(""))
        assertNull(parseAmountCents("abc"))
        assertNull(parseAmountCents("--"))
    }

    @Test
    fun 无效时间() {
        assertNull(parseDateTime("2026/09/01 20:25"))
        assertNull(parseDateTime("not a date"))
        assertNull(parseDateTime("2026-13-41 20:25:28")) // 越界日期
        assertNull(parseDateTime("2026-09-01 25:61"))    // 越界时分
    }
}