package app.oneassist.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.oneassist.core.Tags

/** Up to [max] tags as small badges, then "+n". */
@Composable
fun TagBadges(tags: Set<String>, modifier: Modifier = Modifier, max: Int = 3) {
    if (tags.isEmpty()) return
    val sorted = tags.sorted()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        sorted.take(max).forEach { Badge("#$it") }
        if (sorted.size > max) Badge("+${sorted.size - max}")
    }
}

/**
 * Edits a set of tags: current tags as removable chips, existing tags as one-tap suggestions and a
 * text field for a new one. Every change is reported through [onChange] (already normalized).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditor(
    tags: Set<String>,
    suggestions: List<String>,
    onChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val full = tags.size >= Tags.MAX_PER_TRANSACTION
    val pending = Tags.normalize(text)

    fun add(tag: String?) {
        val n = tag?.let { Tags.normalize(it) } ?: return
        if (n !in tags && !full) onChange(Tags.normalizeAll(tags + n))
        text = ""
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.sorted().forEach { tag ->
                    FilterChip(
                        selected = true,
                        onClick = { onChange(tags - tag) },
                        label = { Text("#$tag") },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove tag $tag", modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(Tags.MAX_LENGTH + 10) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !full,
            label = { Text(if (full) "At most ${Tags.MAX_PER_TRANSACTION} tags" else "Add a tag") },
            placeholder = { Text("holiday-2026, work, gift") },
            supportingText = if (pending != null && pending != text.trim()) {
                { Text("Saved as #$pending") }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add(text) }),
            trailingIcon = {
                IconButton(onClick = { add(text) }, enabled = pending != null && !full) {
                    Icon(Icons.Filled.Add, contentDescription = "Add tag")
                }
            },
        )
        val filter = pending.orEmpty()
        val shown = suggestions.filter { it !in tags && (filter.isEmpty() || it.contains(filter)) }.take(MAX_SUGGESTIONS)
        if (shown.isNotEmpty() && !full) {
            Text("Your tags", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                shown.forEach { tag ->
                    SuggestionChip(onClick = { add(tag) }, label = { Text("#$tag") })
                }
            }
        }
    }
}

private const val MAX_SUGGESTIONS = 12
