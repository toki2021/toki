package com.zhuanz.autoleger.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhuanz.autoleger.data.AppContainer
import com.zhuanz.autoleger.data.CategoryEntity
import com.zhuanz.autoleger.data.TransactionEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.YearMonth

data class HomeUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val month: YearMonth = YearMonth.now(),
    val selectedCategory: Long? = null,
    val categories: List<CategoryEntity> = emptyList(),
    val summary: MonthSummary = MonthSummary(emptyList(), 0, 0, 0),
    val visible: List<TransactionEntity> = emptyList(),
    val yearExpense: Long = 0,
    val allExpense: Long = 0,
)

class HomeViewModel(private val container: AppContainer, private val saved: SavedStateHandle) : ViewModel() {
    private val month = saved.getStateFlow("month", YearMonth.now().toString())
    private val category = saved.getStateFlow<Long?>("category", null)
    private val today = MutableStateFlow(LocalDate.now())
    private val undoChannel = Channel<TransactionEntity>(Channel.BUFFERED)
    val undoEvents = undoChannel.receiveAsFlow()
    private val errorChannel = Channel<String>(Channel.BUFFERED)
    val errors = errorChannel.receiveAsFlow()
    val uiState = combine(container.transactionDao.observeAll(), container.categoryDao.observeAll(), month, category, today) {
        entries, categories, period, selected, date ->
        val summary = summarizeMonth(entries, YearMonth.parse(period), date)
        val validCategory = selected?.takeIf { it == UNCATEGORIZED_ID || categories.any { c -> c.id == it } }
        // Deleted categories are presented as uncategorized, including historical references.
        val known = categories.map { it.id }.toSet()
        val normalized = summary.transactions.map { if (it.categoryId !in known) it.copy(categoryId = null) else it }
        val zone = java.time.ZoneId.systemDefault()
        val yearStart = date.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val yearEnd = date.withDayOfYear(1).plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
        HomeUiState(loading = false, today = date, month = YearMonth.parse(period), selectedCategory = validCategory,
            categories = categories, summary = summary, visible = filterCategory(normalized, validCategory),
            yearExpense = entries.filter { it.type != "REFUND" && it.time >= yearStart && it.time < yearEnd }.sumOf { it.amountCents },
            allExpense = entries.filter { it.type != "REFUND" }.sumOf { it.amountCents })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(month = YearMonth.parse(month.value), selectedCategory = category.value))

    fun selectMonth(value: YearMonth) { saved["month"] = value.toString() }
    fun selectCategory(value: Long?) { saved["category"] = value }
    fun refreshDate() { today.value = LocalDate.now() }
    fun delete(tx: TransactionEntity, onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
            val original = container.transactionRepository.getById(tx.id) ?: return@launch
            container.transactionRepository.deleteById(original.id)
            undoChannel.send(original)
            onDeleted()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { errorChannel.send("删除失败，请重试") }
        }
    }
    fun undo(tx: TransactionEntity) {
        viewModelScope.launch {
            try { container.transactionRepository.restore(tx) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { errorChannel.send("撤销失败，请重试") }
        }
    }
}
