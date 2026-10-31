package app.oneassist.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.oneassist.core.Leak
import app.oneassist.core.LeakType
import app.oneassist.core.SavingsTip
import app.oneassist.data.FinanceRepository
import app.oneassist.ui.appViewModel
import app.oneassist.ui.components.Badge
import app.oneassist.ui.components.EmptyState
import app.oneassist.ui.components.LoadingState
import app.oneassist.ui.components.SectionTitle
import app.oneassist.ui.formatMoney
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class InsightsUiState(val leaks: List<Leak>, val tips: List<SavingsTip>)

class InsightsViewModel(repository: FinanceRepository) : ViewModel() {
    val state: StateFlow<InsightsUiState?> = combine(repository.leaks(), repository.savingsTips()) { leaks, tips ->
        InsightsUiState(
            leaks = leaks.sortedByDescending { it.monthlyImpactCents },
            tips = tips.sortedByDescending { it.estimatedMonthlySavingCents },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen() {
    val viewModel = appViewModel { InsightsViewModel(it.container.financeRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Insights") }) },
    ) { padding ->
        val current = state
        when {
            current == null -> LoadingState(Modifier.padding(padding))
            current.leaks.isEmpty() && current.tips.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Lightbulb,
                title = "Nothing to flag yet",
                message = "Once OneAssist has a few weeks of transactions it will point out money leaks " +
                    "and ways to save here.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (current.leaks.isNotEmpty()) {
                    item {
                        val total = current.leaks.sumOf { it.monthlyImpactCents }
                        Column {
                            SectionTitle("Money leaks")
                            Text(
                                "Together about ${formatMoney(total)} a month, biggest first.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    itemsIndexed(current.leaks, key = { i, leak -> "leak-$i-${leak.title}" }) { _, leak -> LeakCard(leak) }
                }
                if (current.tips.isNotEmpty()) {
                    item {
                        val total = current.tips.sumOf { it.estimatedMonthlySavingCents }
                        Column(Modifier.padding(top = 8.dp)) {
                            SectionTitle("Ways to save")
                            Text(
                                "Up to ${formatMoney(total)} a month if you follow all of them.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    itemsIndexed(current.tips, key = { i, tip -> "tip-$i-${tip.title}" }) { _, tip -> TipCard(tip) }
                }
            }
        }
    }
}

@Composable
private fun LeakCard(leak: Leak) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.WaterDrop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Badge(leakTypeLabel(leak.type))
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatMoney(leak.monthlyImpactCents)}/mo",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(leak.title, style = MaterialTheme.typography.titleMedium)
            if (leak.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(leak.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (leak.merchants.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    leak.merchants.take(5).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TipCard(tip: SavingsTip) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(
                Icons.Outlined.Savings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tip.title, style = MaterialTheme.typography.titleMedium)
                if (tip.detail.isNotBlank()) {
                    Text(tip.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tip.estimatedMonthlySavingCents > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Save about ${formatMoney(tip.estimatedMonthlySavingCents)} a month",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun leakTypeLabel(type: LeakType): String = when (type) {
    LeakType.SMALL_FREQUENT -> "Small but often"
    LeakType.PRICE_INCREASE -> "Price increase"
    LeakType.DUPLICATE_SERVICE -> "Overlapping services"
    LeakType.FEES -> "Fees"
    LeakType.CATEGORY_SPIKE -> "Spending spike"
    LeakType.FORGOTTEN_SUBSCRIPTION -> "Forgotten subscription"
}
