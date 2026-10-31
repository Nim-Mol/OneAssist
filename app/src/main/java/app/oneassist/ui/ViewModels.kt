package app.oneassist.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.oneassist.OneAssistApp

/**
 * Creates (or reuses) a ViewModel scoped to the current navigation entry, built from the app's
 * manual DI container. Usage: `appViewModel { app -> OverviewViewModel(app.container.financeRepository) }`.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (OneAssistApp) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as OneAssistApp
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(app) } },
    )
}
