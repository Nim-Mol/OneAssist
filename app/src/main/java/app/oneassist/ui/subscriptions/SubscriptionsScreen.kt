package app.oneassist.ui.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.oneassist.core.Cadence
import app.oneassist.core.Subscription
import app.oneassist.data.FinanceRepository
import app.oneassist.ui.appViewModel
import app.oneassist.ui.components.Badge
import app.oneassist.ui.components.EmptyState
import app.oneassist.ui.components.LoadingState
import app.oneassist.ui.formatMoney
import app.oneassist.ui.formatShortDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SubscriptionsViewModel(repository: FinanceRepository) : ViewModel() {
    /** Most expensive first. Null while loading. */
    val subscriptions: StateFlow<List<Subscription>?> = repository.subscriptions()
        .map { list -> list.sortedByDescending { it.monthlyCostCents } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen() {
    val viewModel = appViewModel { SubscriptionsViewModel(it.container.financeRepository) }
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Subscriptions") }) },
    ) { padding ->
        val list = subscriptions
        when {
            list == null -> LoadingState(Modifier.padding(padding))
            list.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Autorenew,
                title = "No subscriptions found yet",
                message = "OneAssist spots repeating payments once it has seen them a couple of times. " +
                    "Importing a few months of CSV history speeds this up.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { TotalsCard(list) }
                items(list, key = { "${it.merchant}-${it.cadence}" }) { SubscriptionCard(it) }
            }
        }
    }
}

@Composable
private fun TotalsCard(list: List<Subscription>) {
    val monthly = list.sumOf { it.monthlyCostCents }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Per month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatMoney(monthly), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("Per year", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatMoney(monthly * 12), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            if (list.size == 1) "1 active subscription" else "${list.size} active subscriptions",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun SubscriptionCard(subscription: Subscription) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    subscription.merchant,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(formatMoney(subscription.amountCents.absoluteCents()), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cadenceLabel(subscription.cadence) +
                        if (subscription.cadence != Cadence.MONTHLY) {
                            " · ${formatMoney(subscription.monthlyCostCents)} a month"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                subscription.priceIncreasedFromCents?.let { previous ->
                    Badge(
                        "Price up from ${formatMoney(previous.absoluteCents())}",
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Text(
                "Next charge around ${formatShortDate(subscription.nextExpected)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Subscription amounts may be stored as negative (money out); show them as positive costs. */
private fun Long.absoluteCents(): Long = if (this < 0) -this else this

private fun cadenceLabel(cadence: Cadence): String = when (cadence) {
    Cadence.WEEKLY -> "Weekly"
    Cadence.MONTHLY -> "Monthly"
    Cadence.QUARTERLY -> "Every 3 months"
    Cadence.YEARLY -> "Yearly"
}
