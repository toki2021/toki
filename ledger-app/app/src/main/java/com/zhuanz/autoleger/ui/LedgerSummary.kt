package com.zhuanz.autoleger.ui

import com.zhuanz.autoleger.data.TransactionEntity
import com.zhuanz.autoleger.data.TYPE_REFUND
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One accounting period for the list, overview and statistics. End is exclusive. */
data class MonthSummary(
    val transactions: List<TransactionEntity>,
    val expense: Long,
    val refund: Long,
    val dailyAverage: Long,
)

fun summarizeMonth(
    transactions: List<TransactionEntity>,
    month: YearMonth,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): MonthSummary {
    val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val entries = transactions.filter { it.time >= start && it.time < end }
        .sortedWith(compareByDescending<TransactionEntity> { it.time }.thenByDescending { it.id })
    val expense = entries.filter { it.type != TYPE_REFUND }.sumOf { it.amountCents }
    val refund = entries.filter { it.type == TYPE_REFUND }.sumOf { it.amountCents }
    val days = if (month == YearMonth.from(today)) today.dayOfMonth else month.lengthOfMonth()
    return MonthSummary(entries, expense, refund, expense / days)
}

fun filterCategory(entries: List<TransactionEntity>, selectedId: Long?): List<TransactionEntity> =
    if (selectedId == null) entries else entries.filter {
        if (selectedId == UNCATEGORIZED_ID) it.categoryId == null else it.categoryId == selectedId
    }

const val UNCATEGORIZED_ID = -1L
fun transactionDay(tx: TransactionEntity, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(tx.time).atZone(zone).toLocalDate()
