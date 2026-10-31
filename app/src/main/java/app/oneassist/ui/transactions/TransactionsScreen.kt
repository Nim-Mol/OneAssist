package app.oneassist.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.oneassist.core.AccountSource
import app.oneassist.core.Category
import app.oneassist.core.CategoryKind
import app.oneassist.core.ImportSource
import app.oneassist.core.Transaction
import app.oneassist.ui.appViewModel
import app.oneassist.ui.components.EmptyState
import app.oneassist.ui.components.LoadingState
import app.oneassist.ui.formatDayHeader
import app.oneassist.ui.formatMoney
import app.oneassist.ui.formatMonth
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen() {
    val viewModel = appViewModel { TransactionsViewModel(it.container.financeRepository) }
    val month by viewModel.month.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Transactions") }) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            MonthSwitcher(
                month = month,
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
            )
            HorizontalDivider()
            val current = days
            when {
                current == null -> LoadingState()
                current.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    title = "No transactions in ${formatMonth(month)}",
                    message = "New payments appear here automatically from bank notifications, " +
                        "or import a CSV or screenshot from the Overview screen.",
                )
                else -> TransactionList(
                    days = current,
                    categoryNames = categories.associate { it.id to it.name },
                    onClick = { editingId = it.id },
                )
            }
        }
    }

    val editing = editingId?.let { id -> days?.firstNotNullOfOrNull { day -> day.transactions.find { it.id == id } } }
    if (editing != null) {
        CategorySheet(
            transaction = editing,
            categories = categories,
            onPick = { category ->
                viewModel.recategorize(editing, category)
                editingId = null
            },
            onDismiss = { editingId = null },
        )
    }
}

@Composable
private fun MonthSwitcher(month: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Text(
            formatMonth(month),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onNext, enabled = month < YearMonth.now()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
        }
    }
}

@Composable
private fun TransactionList(
    days: List<DayGroup>,
    categoryNames: Map<String, String>,
    onClick: (Transaction) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        days.forEach { day ->
            item(key = "header-${day.date}") {
                val dayTotal = day.transactions.sumOf { it.amountCents }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                ) {
                    Text(
                        formatDayHeader(day.date),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatMoney(dayTotal),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(day.transactions, key = { it.id }) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    categoryName = transaction.categoryId?.let { categoryNames[it] ?: it },
                    onClick = { onClick(transaction) },
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(transaction: Transaction, categoryName: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                transaction.merchant.ifBlank { transaction.description.ifBlank { "Unknown" } },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                "${categoryName ?: "Uncategorized"} · ${accountLabel(transaction.account)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (categoryName == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                sourceIcon(transaction.source),
                contentDescription = sourceLabel(transaction.source),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingContent = {
            Text(
                formatMoney(transaction.amountCents, transaction.currency),
                style = MaterialTheme.typography.titleSmall,
                color = if (transaction.amountCents > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(
    transaction: Transaction,
    categories: List<Category>,
    onPick: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                transaction.merchant.ifBlank { "Transaction" },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatMoney(transaction.amountCents, transaction.currency)} · " +
                    "${accountLabel(transaction.account)} · via ${sourceLabel(transaction.source)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Pick a category. OneAssist remembers your choice for similar transactions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val grouped = categories.groupBy { it.kind }.toSortedMap(compareBy { it.ordinal })
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            grouped.forEach { (kind, list) ->
                item(key = "kind-$kind") {
                    Text(
                        kindLabel(kind),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(list.sortedBy { it.name }, key = { it.id }) { category ->
                    val selected = category.id == transaction.categoryId
                    ListItem(
                        modifier = Modifier.clickable { onPick(category) },
                        headlineContent = { Text(category.name) },
                        trailingContent = {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = "Current category")
                        },
                    )
                }
            }
        }
    }
}

private fun kindLabel(kind: CategoryKind): String = when (kind) {
    CategoryKind.INCOME -> "Income"
    CategoryKind.ESSENTIAL -> "Essentials"
    CategoryKind.DISCRETIONARY -> "Lifestyle"
    CategoryKind.SAVINGS -> "Savings"
    CategoryKind.TRANSFER -> "Transfers"
}

fun accountLabel(account: AccountSource): String = when (account) {
    AccountSource.ING -> "ING"
    AccountSource.REVOLUT -> "Revolut"
    AccountSource.CREDIT_CARD -> "Credit card"
}

private fun sourceLabel(source: ImportSource): String = when (source) {
    ImportSource.NOTIFICATION -> "notification"
    ImportSource.CSV -> "CSV"
    ImportSource.SCREENSHOT -> "screenshot"
    ImportSource.MANUAL -> "manual entry"
}

private fun sourceIcon(source: ImportSource): ImageVector = when (source) {
    ImportSource.NOTIFICATION -> Icons.Outlined.Notifications
    ImportSource.CSV -> Icons.Outlined.Description
    ImportSource.SCREENSHOT -> Icons.Outlined.Image
    ImportSource.MANUAL -> Icons.Outlined.Edit
}
