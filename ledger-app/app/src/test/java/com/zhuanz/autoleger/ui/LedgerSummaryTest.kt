package com.zhuanz.autoleger.ui

import com.zhuanz.autoleger.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LedgerSummaryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun tx(date: String, cents: Long = 100, type: String = TYPE_EXPENSE, category: Long? = 1) =
        TransactionEntity(id = date.hashCode().toLong(), time = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli(),
            amountCents = cents, type = type, categoryId = category, merchant = "测试", source = SOURCE_MANUAL)

    @Test fun monthUsesExclusiveEndAcrossYear() {
        val result = summarizeMonth(listOf(tx("2025-12-01"), tx("2025-12-31"), tx("2026-01-01"), tx("2025-11-30")),
            YearMonth.of(2025, 12), LocalDate.of(2026, 1, 2), zone)
        assertEquals(2, result.transactions.size)
        assertEquals(200L, result.expense)
        assertEquals(6L, result.dailyAverage)
    }
    @Test fun currentMonthUsesElapsedDaysAndSeparatesRefunds() {
        val result = summarizeMonth(listOf(tx("2026-09-01", 1000), tx("2026-09-02", 300, TYPE_REFUND)),
            YearMonth.of(2026, 9), LocalDate.of(2026, 9, 5), zone)
        assertEquals(1000L, result.expense)
        assertEquals(300L, result.refund)
        assertEquals(200L, result.dailyAverage)
    }
    @Test fun leapFebruaryUses29Days() {
        val result = summarizeMonth(listOf(tx("2024-02-29", 2900), tx("2024-03-01", 500)),
            YearMonth.of(2024, 2), LocalDate.of(2026, 9, 30), zone)
        assertEquals(100L, result.dailyAverage)
        assertEquals(2900L, result.expense)
    }
    @Test fun ordinaryFebruaryUses28Days() {
        val result = summarizeMonth(listOf(tx("2025-02-28", 2800)), YearMonth.of(2025, 2), LocalDate.of(2026, 9, 30), zone)
        assertEquals(100L, result.dailyAverage)
    }
    @Test fun categoryIdentityAndUncategorizedAreIndependent() {
        val entries = listOf(tx("2026-09-01", category = 1), tx("2026-09-02", category = 2), tx("2026-09-03", category = null))
        assertEquals(3, filterCategory(entries, null).size)
        assertEquals(listOf(entries[1]), filterCategory(entries, 2))
        assertEquals(listOf(entries[2]), filterCategory(entries, UNCATEGORIZED_ID))
        assertTrue(filterCategory(entries, 9).isEmpty())
    }
    @Test fun emptyMonthHasZeroSummary() {
        val result = summarizeMonth(emptyList(), YearMonth.of(2026, 9), LocalDate.of(2026, 9, 1), zone)
        assertEquals(0L, result.expense); assertEquals(0L, result.refund); assertEquals(0L, result.dailyAverage)
    }
    @Test fun sortingAndLocalDayUseTransactionTime() {
        val older = tx("2026-09-01")
        val newer = tx("2026-09-02")
        val result = summarizeMonth(listOf(older, newer), YearMonth.of(2026, 9), LocalDate.of(2026, 9, 30), zone)
        assertEquals(listOf(newer, older), result.transactions)
        assertEquals(LocalDate.of(2026, 9, 2), transactionDay(newer, zone))
    }
    @Test fun futureMonthDoesNotEnterCurrentSummary() {
        val result = summarizeMonth(listOf(tx("2026-10-01", 999)), YearMonth.of(2026, 9), LocalDate.of(2026, 9, 30), zone)
        assertEquals(0L, result.expense)
    }
}
