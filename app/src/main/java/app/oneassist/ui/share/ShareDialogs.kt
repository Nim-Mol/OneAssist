package app.oneassist.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.oneassist.core.AccountSource
import app.oneassist.ui.importer.AccountSelector
import app.oneassist.ui.importer.ImportReportContent

/** Dialogs for files shared into OneAssist from another app. Shown only while unlocked. */
@Composable
fun ShareDialogs(viewModel: ShareViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        ShareState.Idle -> Unit

        is ShareState.ConfirmCsv -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Import shared CSV?") },
            text = {
                Text(
                    "OneAssist will read the transactions in this file. Ones it already knows are skipped, " +
                        "and the bank's own record replaces matching notification entries.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmCsv) { Text("Import") } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Cancel") } },
        )

        is ShareState.ChooseAccount -> {
            var account by rememberSaveable { mutableStateOf(AccountSource.CREDIT_CARD) }
            AlertDialog(
                onDismissRequest = viewModel::dismiss,
                title = {
                    Text(if (current.uris.size == 1) "Import screenshot" else "Import ${current.uris.size} screenshots")
                },
                text = {
                    Column {
                        Text("Which account is this from? The text is read on your phone only.")
                        Spacer(Modifier.height(12.dp))
                        AccountSelector(selected = account, onSelect = { account = it })
                    }
                },
                confirmButton = { TextButton(onClick = { viewModel.importScreenshots(account) }) { Text("Import") } },
                dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Cancel") } },
            )
        }

        ShareState.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Importing…") },
            text = {
                Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                }
            },
            confirmButton = {},
        )

        is ShareState.Done -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Import finished") },
            text = { ImportReportContent(current.report) },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("Done") } },
        )

        is ShareState.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Import didn't work") },
            text = { Text(current.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("OK") } },
        )
    }
}
