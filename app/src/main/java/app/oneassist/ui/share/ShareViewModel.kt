package app.oneassist.ui.share

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.oneassist.OneAssistApp
import app.oneassist.core.AccountSource
import app.oneassist.data.FinanceRepository
import app.oneassist.data.ImportReport
import app.oneassist.ui.importer.FileTooLargeException
import app.oneassist.ui.importer.MAX_CSV_BYTES
import app.oneassist.ui.importer.readTextLimited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the UI should show for something shared into the app from another app. */
sealed interface ShareState {
    data object Idle : ShareState
    /** A CSV arrived, either as a file ([uri]) or as plain text ([text]). Waits for the user to confirm. */
    data class ConfirmCsv(val uri: Uri?, val text: String?) : ShareState
    /** One or more screenshots arrived. Waits for the user to pick the account. */
    data class ChooseAccount(val uris: List<Uri>) : ShareState
    data object Working : ShareState
    data class Done(val report: ImportReport) : ShareState
    data class Failed(val message: String) : ShareState
}

/**
 * Handles ACTION_SEND / ACTION_SEND_MULTIPLE intents. Nothing is imported until the user, who
 * has unlocked the app, confirms it. Scoped to MainActivity so it survives rotation.
 */
class ShareViewModel(
    private val app: Application,
    private val repository: FinanceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<ShareState>(ShareState.Idle)
    val state: StateFlow<ShareState> = _state.asStateFlow()

    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        if (_state.value == ShareState.Working) return
        val type = intent.type.orEmpty()

        if (type.startsWith("image/")) {
            val uris = if (action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.take(MAX_SCREENSHOTS)
            _state.value = if (uris.isEmpty()) {
                ShareState.Failed("The shared image could not be opened.")
            } else {
                ShareState.ChooseAccount(uris)
            }
            return
        }

        if (action != Intent.ACTION_SEND) return
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        _state.value = when {
            stream != null && stream.scheme == ContentResolver.SCHEME_CONTENT -> ShareState.ConfirmCsv(stream, null)
            stream != null -> ShareState.Failed("The shared file could not be opened.")
            text.isNullOrBlank() -> ShareState.Failed("Nothing to import was shared.")
            text.length > MAX_CSV_BYTES -> ShareState.Failed("The shared text is larger than 5 MB.")
            else -> ShareState.ConfirmCsv(null, text)
        }
    }

    fun confirmCsv() {
        val pending = _state.value as? ShareState.ConfirmCsv ?: return
        launchImport {
            val text = pending.text ?: withContext(Dispatchers.IO) {
                app.contentResolver.readTextLimited(requireNotNull(pending.uri))
            }
            repository.importCsv(text)
        }
    }

    fun importScreenshots(account: AccountSource) {
        val pending = _state.value as? ShareState.ChooseAccount ?: return
        launchImport {
            pending.uris
                .map { repository.importScreenshot(it, account) }
                .reduce { a, b -> ImportReport(a.added + b.added, a.duplicates + b.duplicates, a.skipped + b.skipped) }
        }
    }

    fun dismiss() {
        if (_state.value != ShareState.Working) _state.value = ShareState.Idle
    }

    private fun launchImport(block: suspend () -> ImportReport) {
        _state.value = ShareState.Working
        viewModelScope.launch {
            _state.value = try {
                ShareState.Done(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileTooLargeException) {
                ShareState.Failed("This file is larger than 5 MB. Export a shorter period and try again.")
            } catch (e: Exception) {
                ShareState.Failed("Import failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    companion object {
        private const val MAX_SCREENSHOTS = 10

        fun factory(app: OneAssistApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShareViewModel(app, app.container.financeRepository) }
        }
    }
}
