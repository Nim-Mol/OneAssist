package app.oneassist.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.oneassist.ui.appViewModel
import app.oneassist.ui.components.EmptyState
import app.oneassist.ui.components.LoadingState
import app.oneassist.ui.formatMoney
import app.oneassist.ui.formatMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    onOpenImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenSubscriptions: () -> Unit,
) {
    val viewModel = appViewModel { OverviewViewModel(it.container.financeRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("OneAssist") },
                actions = {
                    IconButton(onClick = onOpenImport) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = "Import transactions")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        val current = state
        when {
            current == null -> LoadingState(Modifier.padding(padding))
            current.isEmpty -> EmptyState(
                icon = Icons.Outlined.Savings,
                title = "Welcome to OneAssist",
                message = "Bring in your transactions to see where your money goes. " +
                    "Turn on bank notifications, share a CSV from your bank app, or pick a screenshot.",
                actionLabel = "Import transactions",
                onAction = onOpenImport,
                modifier = Modifier.padding(padding),
            )
            else -> OverviewContent(
                state = current,
                contentPadding = padding,
                onOpenInsights = onOpenInsights,
                onOpenSubscriptions = onOpenSubscriptions,
            )
        }
    }
}

@Composable
private fun OverviewContent(
    state: OverviewUiState,
    contentPadding: PaddingValues,
    onOpenInsights: () -> Unit,
    onOpenSubscriptions: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SpendingCard(state) }
        if (state.topCategories.isNotEmpty()) {
            item { TopCategoriesCard(state.topCategories) }
        }
        state.biggestLeak?.let { leak ->
            item {
                LinkCard(
                    icon = Icons.Outlined.WaterDrop,
                    label = "Biggest leak",
                    title = leak.title,
                    value = "≈ ${formatMoney(leak.monthlyImpactCents)} a month",
                    onClick = onOpenInsights,
                    highlight = true,
                )
            }
        }
        item {
            LinkCard(
                icon = Icons.Outlined.Autorenew,
                label = "Subscriptions",
                title = when (state.subscriptionCount) {
                    0 -> "No subscriptions found yet"
                    1 -> "1 subscription"
                    else -> "${state.subscriptionCount} subscriptions"
                },
                value = "${formatMoney(state.subscriptionsMonthlyCents)} a month",
                onClick = onOpenSubscriptions,
            )
        }
    }
}

@Composable
private fun SpendingCard(state: OverviewUiState) {
    val summary = state.summary
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Spent in ${formatMonth(summary.month)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatMoney(summary.spendingCents),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            val diff = summary.spendingCents - summary.previousMonthSpendingCents
            val comparison = when {
                summary.previousMonthSpendingCents == 0L -> "No spending recorded last month"
                diff > 0 -> "${formatMoney(diff)} more than last month (${formatMoney(summary.previousMonthSpendingCents)})"
                diff < 0 -> "${formatMoney(-diff)} less than last month (${formatMoney(summary.previousMonthSpendingCents)})"
                else -> "Same as last month"
            }
            Text(
                comparison,
                style = MaterialTheme.typography.bodyMedium,
                color = if (diff > 0 && summary.previousMonthSpendingCents > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Income this month",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatMoney(summary.incomeCents),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TopCategoriesCard(categories: List<CategorySpend>) {
    val max = categories.maxOf { it.cents }.coerceAtLeast(1L)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Top categories", style = MaterialTheme.typography.titleMedium)
            categories.forEach { category ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            category.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatMoney(category.cents), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    val fraction = (category.cents.toFloat() / max.toFloat()).coerceIn(0.02f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(10.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    title: String,
    value: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (highlight) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
