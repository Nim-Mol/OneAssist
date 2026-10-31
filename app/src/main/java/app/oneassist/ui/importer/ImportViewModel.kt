package app.oneassist.ui.importer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oneassist.core.AccountSource
import app.oneassist.data.FinanceRepository
import app.oneassist.data.ImportReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportUiState(
    val account: AccountSource = AccountSource.CREDIT_CARD,
    val busy: Boolean = false,
    val report: ImportReport? = null,
    val error: String? = null,
)

class ImportViewModel(
    private val app: Application,
    private val repository: FinanceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun selectAccount(account: AccountSource) = _state.update { it.copy(account = account) }

    fun importCsv(uri: Uri) = launchImport {
        val text = withContext(Dispatchers.IO) { app.contentResolver.readTextLimited(uri) }
        repository.importCsv(text)
    }

    fun importScreenshot(uri: Uri) {
        val account = _state.value.account
        launchImport { repository.importScreenshot(uri, account) }
    }

    fun dismissReport() = _state.update { it.copy(report = null, error = null) }

    private fun launchImport(block: suspend () -> ImportReport) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, report = null, error = null) }
        viewModelScope.launch {
            try {
                val report = block()
                _state.update { it.copy(busy = false, report = report) }
            } catch (e: FileTooLargeException) {
                _state.update { it.copy(busy = false, error = "This file is larger than 5 MB. Export a shorter period and try again.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "Import failed: ${e.message ?: "unknown error"}") }
            }
        }
    }
}
