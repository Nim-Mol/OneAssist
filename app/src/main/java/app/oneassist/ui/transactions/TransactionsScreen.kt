package app.oneassist.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import app.oneassist.ui.components.TagBadges
import app.oneassist.ui.components.TagEditor
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
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val selectedTag by viewModel.tag.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Transactions") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add transaction")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            MonthSwitcher(
                month = month,
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
            )
            if (allTags.isNotEmpty() || selectedTag != null) {
                TagFilterRow(
                    tags = (allTags + listOfNotNull(selectedTag)).distinct(),
                    selected = selectedTag,
                    onSelect = viewModel::selectTag,
                )
            }
            HorizontalDivider()
            val current = days
            when {
                current == null -> LoadingState()
                current.isEmpty() && selectedTag != null -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    title = "Nothing tagged #$selectedTag in ${formatMonth(month)}",
                    message = "Try another month, or tap the tag again to see everything.",
                )
                current.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    title = "No transactions in ${formatMonth(month)}",
                    message = "New payments appear here automatically from bank notifications, " +
                        "or import a CSV or screenshot from the Overview screen. Tap + to add one yourself.",
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
        TransactionSheet(
            transaction = editing,
            categories = categories,
            tagSuggestions = allTags,
            onPick = { category ->
                viewModel.recategorize(editing, category)
                editingId = null
            },
            onTagsChange = { tags -> viewModel.setTags(editing, tags) },
            onMarkAsIncome = { category, always ->
                viewModel.markAsIncome(editing, category, always)
                editingId = null
            },
            onRecurringChange = { viewModel.setRecurring(editing, it) },
            onDismiss = { editingId = null },
        )
    }

    if (adding) {
        AddTransactionDialog(
            categories = categories,
            tagSuggestions = allTags,
            onSave = { entry ->
                viewModel.addManual(entry)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun TagFilterRow(tags: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        item(key = "all") {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") })
        }
        items(tags, key = { "tag-$it" }) { tag ->
            FilterChip(selected = tag == selected, onClick = { onSelect(tag) }, label = { Text("#$tag") })
        }
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
            Column {
                Text(
                    "${categoryName ?: "Uncategorized"} · ${accountLabel(transaction.account)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (categoryName == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (transaction.tags.isNotEmpty()) {
                    TagBadges(transaction.tags, modifier = Modifier.padding(top = 4.dp))
                }
            }
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
private fun TransactionSheet(
    transaction: Transaction,
    categories: List<Category>,
    tagSuggestions: List<String>,
    onPick: (Category) -> Unit,
    onTagsChange: (Set<String>) -> Unit,
    onMarkAsIncome: (Category, Boolean) -> Unit,
    onRecurringChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val incomeCategories = categories.filter { it.kind == CategoryKind.INCOME }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "header") {
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
                    if (transaction.note.isNotBlank()) {
                        Spacer(Modifier.size(4.dp))
                        Text(transaction.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item(key = "tags") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    SheetHeading("Tags")
                    TagEditor(tags = transaction.tags, suggestions = tagSuggestions, onChange = onTagsChange)
                }
            }
            if (transaction.amountCents > 0 && incomeCategories.isNotEmpty()) {
                item(key = "income") {
                    MarkAsIncomeSection(
                        transaction = transaction,
                        incomeCategories = incomeCategories,
                        onConfirm = onMarkAsIncome,
                    )
                }
                if (transaction.source == ImportSource.MANUAL) {
                    item(key = "recurring") {
                        Row(
                            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Repeats monthly", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Switch(checked = transaction.recurring, onCheckedChange = onRecurringChange)
                        }
                    }
                }
            }
            item(key = "category-heading") {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    SheetHeading("Category")
                    Text(
                        "Pick a category. OneAssist remembers your choice for similar transactions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val grouped = categories.groupBy { it.kind }.toSortedMap(compareBy { it.ordinal })
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

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** Money in: pick an income category, optionally for every payment from this payer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkAsIncomeSection(
    transaction: Transaction,
    incomeCategories: List<Category>,
    onConfirm: (Category, Boolean) -> Unit,
) {
    val current = incomeCategories.find { it.id == transaction.categoryId }
    var chosenId by rememberSaveable(transaction.id) { mutableStateOf(current?.id) }
    var always by rememberSaveable(transaction.id) { mutableStateOf(true) }
    val chosen = incomeCategories.find { it.id == chosenId }
    val payer = transaction.merchant.ifBlank { "this payer" }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        SheetHeading("Mark as income")
        Text(
            when {
                current != null -> "Counted as income: ${current.name}."
                transaction.peerToPeer -> "Now counted as a transfer. Mark it as income if it is salary, a gift or other income."
                else -> "Choose what kind of income this is."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            incomeCategories.forEach { c ->
                FilterChip(selected = c.id == chosenId, onClick = { chosenId = c.id }, label = { Text(c.name) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = always, onCheckedChange = { always = it })
            Text(
                "Always treat payments from $payer as ${chosen?.name ?: "this kind of income"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = { chosen?.let { onConfirm(it, always) } },
            enabled = chosen != null && (chosen.id != transaction.categoryId || transaction.peerToPeer || always),
        ) { Text("Mark as income") }
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
    AccountSource.CASH -> "Cash"
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
