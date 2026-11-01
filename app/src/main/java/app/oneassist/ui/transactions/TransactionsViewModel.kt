package app.oneassist.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oneassist.core.Category
import app.oneassist.core.ManualEntry
import app.oneassist.core.Transaction
import app.oneassist.data.FinanceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    private val _tag = MutableStateFlow<String?>(null)
    /** The tag the list is filtered on, or null for all transactions. */
    val tag: StateFlow<String?> = _tag.asStateFlow()

    /** Transactions of the selected month (and tag), newest day first. Null while loading. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val days: StateFlow<List<DayGroup>?> = combine(_month, _tag) { m, t -> m to t }
        .flatMapLatest { (m, t) -> repository.transactions(m, t) }
        .map { list ->
            list.sortedWith(compareByDescending<Transaction> { it.date }.thenBy { it.merchant })
                .groupBy { it.date }
                .map { (date, items) -> DayGroup(date, items) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val categories: StateFlow<List<Category>> = repository.categories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every tag in use, for the filter row and suggestions. */
    val allTags: StateFlow<List<String>> = repository.allTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-off messages for a snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun previousMonth() = _month.update { it.minusMonths(1) }

    fun nextMonth() {
        if (_month.value < YearMonth.now()) _month.update { it.plusMonths(1) }
    }

    /** Filters on [tag]; selecting the active tag again (or null) clears the filter. */
    fun selectTag(tag: String?) = _tag.update { current -> if (tag == null || tag == current) null else tag }

    fun recategorize(transaction: Transaction, category: Category) {
        if (transaction.categoryId == category.id) return
        launchWithMessage("Could not change the category. Please try again.") {
            repository.recategorize(transaction.id, category.id)
            "Moved to ${category.name}. Similar transactions will follow."
        }
    }

    fun setTags(transaction: Transaction, tags: Set<String>) {
        if (transaction.tags == tags) return
        launchWithMessage("Could not save the tags. Please try again.") {
            repository.setTags(transaction.id, tags)
            null
        }
    }

    fun markAsIncome(transaction: Transaction, category: Category, alwaysForMerchant: Boolean) {
        launchWithMessage("Could not mark this as income. Please try again.") {
            repository.markAsIncome(transaction.id, category.id, alwaysForMerchant)
            if (alwaysForMerchant) {
                "Counted as ${category.name}. Money from ${transaction.merchant} will be too."
            } else {
                "Counted as ${category.name}."
            }
        }
    }

    fun setRecurring(transaction: Transaction, recurring: Boolean) {
        if (transaction.recurring == recurring) return
        launchWithMessage("Could not change this. Please try again.") {
            repository.setRecurring(transaction.id, recurring)
            if (recurring) "Expected again every month." else "No longer expected every month."
        }
    }

    fun addManual(entry: ManualEntry) {
        launchWithMessage("Could not save the transaction. Please try again.") {
            repository.addManual(entry)
            if (entry.isIncome) "Income added." else "Expense added."
        }
    }

    /** Runs [block] and shows the message it returns, or [failure] when it throws. */
    private fun launchWithMessage(failure: String, block: suspend () -> String?) {
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure
            }
            if (message != null) _messages.send(message)
        }
    }
}
