package app.oneassist.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oneassist.core.Category
import app.oneassist.core.Transaction
import app.oneassist.data.FinanceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class DayGroup(val date: LocalDate, val transactions: List<Transaction>)

class TransactionsViewModel(private val repository: FinanceRepository) : ViewModel() {
    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    /** Transactions of the selected month, newest day first. Null while loading. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val days: StateFlow<List<DayGroup>?> = _month
        .flatMapLatest { repository.transactions(it) }
        .map { list ->
            list.sortedWith(compareByDescending<Transaction> { it.date }.thenBy { it.merchant })
                .groupBy { it.date }
                .map { (date, items) -> DayGroup(date, items) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val categories: StateFlow<List<Category>> = repository.categories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-off messages for a snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun previousMonth() = _month.update { it.minusMonths(1) }

    fun nextMonth() {
        if (_month.value < YearMonth.now()) _month.update { it.plusMonths(1) }
    }

    fun recategorize(transaction: Transaction, category: Category) {
        if (transaction.categoryId == category.id) return
        viewModelScope.launch {
            val message = try {
                repository.recategorize(transaction.id, category.id)
                "Moved to ${category.name}. Similar transactions will follow."
            } catch (e: Exception) {
                "Could not change the category. Please try again."
            }
            _messages.send(message)
        }
    }
}
