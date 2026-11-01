package app.oneassist.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.oneassist.core.AccountSource
import app.oneassist.core.Amounts
import app.oneassist.core.Category
import app.oneassist.core.CategoryKind
import app.oneassist.core.DefaultCategories
import app.oneassist.core.ManualEntries
import app.oneassist.core.ManualEntry
import app.oneassist.ui.components.SectionTitle
import app.oneassist.ui.components.TagEditor
import app.oneassist.ui.formatShortDate
import app.oneassist.ui.importer.AccountSelector
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * Full screen form to add income or an expense by hand (cash, a salary the bank feed missed,
 * anything). The result has source MANUAL and is never removed by de-duplication.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(
    categories: List<Category>,
    tagSuggestions: List<String>,
    onSave: (ManualEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var isIncome by rememberSaveable { mutableStateOf(false) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var epochDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var name by rememberSaveable { mutableStateOf("") }
    var accountName by rememberSaveable { mutableStateOf(AccountSource.CASH.name) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var recurring by rememberSaveable { mutableStateOf(false) }
    var tags by remember { mutableStateOf(emptySet<String>()) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    val amountCents = Amounts.parseCents(amountText)?.let { kotlin.math.abs(it) } ?: 0L
    val date = LocalDate.ofEpochDay(epochDay)
    val entry = ManualEntry(
        isIncome = isIncome,
        amountCents = amountCents,
        date = date,
        name = name,
        account = AccountSource.valueOf(accountName),
        categoryId = categoryId,
        tags = tags,
        note = note,
        recurring = isIncome && recurring,
    )
    val problems = ManualEntries.validate(entry)
    val choices = categories.filter { c ->
        if (isIncome) c.kind == CategoryKind.INCOME else c.kind != CategoryKind.INCOME && c.id != DefaultCategories.UNCATEGORIZED
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                    Text("Add transaction", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (problems.isEmpty()) onSave(entry) else showErrors = true
                    }) { Text("Save") }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isIncome,
                            onClick = { if (isIncome) { isIncome = false; categoryId = null } },
                            label = { Text("Expense") },
                            leadingIcon = if (!isIncome) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                        )
                        FilterChip(
                            selected = isIncome,
                            onClick = { if (!isIncome) { isIncome = true; categoryId = DefaultCategories.SALARY } },
                            label = { Text("Income") },
                            leadingIcon = if (isIncome) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                        )
                    }
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.take(16) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Amount") },
                        prefix = { Text("€ ") },
                        placeholder = { Text("12,50") },
                        isError = showErrors && (amountCents <= 0 || amountCents > ManualEntries.MAX_AMOUNT_CENTS),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(ManualEntries.MAX_NAME) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(if (isIncome) "From (employer, client, …)" else "Name (shop, person, …)") },
                        isError = showErrors && name.isBlank(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                    OutlinedButton(onClick = { pickingDate = true }) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(formatShortDate(date))
                    }

                    SectionTitle("Account")
                    AccountSelector(
                        selected = AccountSource.valueOf(accountName),
                        onSelect = { accountName = it.name },
                        accounts = AccountSource.entries,
                    )

                    SectionTitle("Category")
                    CategoryDropdown(
                        choices = choices,
                        selectedId = categoryId,
                        onSelect = { categoryId = it },
                    )

                    if (isIncome) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Repeats monthly", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Shows as expected income on the Overview every month.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = recurring, onCheckedChange = { recurring = it })
                        }
                    }

                    SectionTitle("Tags")
                    TagEditor(tags = tags, suggestions = tagSuggestions, onChange = { tags = it })

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(ManualEntries.MAX_NOTE) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Note (optional, stays on this phone)") },
                        minLines = 2,
                    )
                    if (showErrors && problems.isNotEmpty()) {
                        Text(
                            problems.joinToString(". "),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = epochDay * MILLIS_PER_DAY)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        epochDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun CategoryDropdown(choices: List<Category>, selectedId: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = choices.find { it.id == selectedId }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.name ?: "Automatic (by name)", modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Automatic (by name)") },
                onClick = { onSelect(null); open = false },
            )
            choices.sortedBy { it.name }.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name) },
                    onClick = { onSelect(c.id); open = false },
                )
            }
        }
    }
}
