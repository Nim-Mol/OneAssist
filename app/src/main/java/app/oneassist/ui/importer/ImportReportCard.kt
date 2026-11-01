package app.oneassist.ui.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oneassist.data.ImportReport

/** One line summary, e.g. "12 added, 3 already known, 1 skipped". */
fun ImportReport.summary(): String {
    val parts = mutableListOf(if (added == 1) "1 transaction added" else "$added transactions added")
    if (duplicates > 0) parts += "$duplicates already known"
    if (skipped.isNotEmpty()) parts += "${skipped.size} skipped"
    return parts.joinToString(", ")
}

@Composable
fun ImportReportContent(report: ImportReport) {
    var showSkipped by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(report.summary(), style = MaterialTheme.typography.bodyLarge)
        if (report.added == 0 && report.duplicates == 0) {
            Text(
                "Nothing could be read. Check that this is a transaction export or a screenshot of a transaction list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (report.skipped.isNotEmpty()) {
            TextButton(onClick = { showSkipped = !showSkipped }) {
                Text(if (showSkipped) "Hide skipped lines" else "Show skipped lines")
            }
            if (showSkipped) {
                report.skipped.take(20).forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (report.skipped.size > 20) {
                    Text("…and ${report.skipped.size - 20} more", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun ImportReportCard(report: ImportReport, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Import finished", style = MaterialTheme.typography.titleMedium)
            ImportReportContent(report)
        }
    }
}
