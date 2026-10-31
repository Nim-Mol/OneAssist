package app.oneassist.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oneassist.core.Leak
import app.oneassist.data.FinanceRepository
import app.oneassist.data.MonthSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

data class CategorySpend(val name: String, val cents: Long)

data class OverviewUiState(
    val summary: MonthSummary,
    val topCategories: List<CategorySpend>,
    val biggestLeak: Leak?,
    val subscriptionsMonthlyCents: Long,
    val subscriptionCount: Int,
) {
    val isEmpty: Boolean
        get() = summary.spendingCents == 0L && summary.incomeCents == 0L &&
            summary.previousMonthSpendingCents == 0L && subscriptionCount == 0
}

class OverviewViewModel(repository: FinanceRepository) : ViewModel() {
    private val month: YearMonth = YearMonth.now()

    /** Null while loading. */
    val state: StateFlow<OverviewUiState?> = combine(
        repository.monthSummary(month),
        repository.categories(),
        repository.subscriptions(),
        repository.leaks(),
    ) { summary, categories, subscriptions, leaks ->
        val names = categories.associate { it.id to it.name }
        OverviewUiState(
            summary = summary,
            topCategories = summary.spendingByCategory
                .filterValues { it > 0 }
                .entries
                .sortedByDescending { it.value }
                .take(6)
                .map { (id, cents) -> CategorySpend(names[id] ?: id.replaceFirstChar { it.uppercase() }, cents) },
            biggestLeak = leaks.maxByOrNull { it.monthlyImpactCents },
            subscriptionsMonthlyCents = subscriptions.sumOf { it.monthlyCostCents },
            subscriptionCount = subscriptions.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
