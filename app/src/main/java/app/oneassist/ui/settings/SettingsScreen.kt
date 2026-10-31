package app.oneassist.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.oneassist.data.AssistantRepository
import app.oneassist.ui.appViewModel
import app.oneassist.ui.importer.isNotificationAccessEnabled
import app.oneassist.ui.importer.openNotificationAccessSettings
import app.oneassist.ui.lock.AppLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val hasApiKey: Boolean? = null,
    val saving: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(private val assistant: AssistantRepository) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val hasKey = withContext(Dispatchers.IO) { runCatching { assistant.hasApiKey() }.getOrDefault(false) }
            _state.update { it.copy(hasApiKey = hasKey) }
        }
    }

    /** Returns immediately; the result shows up in [state]. */
    fun saveKey(raw: String, onSaved: () -> Unit) {
        val key = raw.trim()
        if (key.isEmpty()) return
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { assistant.saveApiKey(key) }
                val hint = if (key.startsWith("sk-ant-")) "" else " It doesn't look like an Anthropic key (sk-ant-…), so double check it."
                _state.update { it.copy(hasApiKey = true, saving = false, message = "API key saved.$hint") }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, message = "Could not save the key. Please try again.") }
            }
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { assistant.clearApiKey() }
                _state.update { it.copy(hasApiKey = false, message = "API key removed.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Could not remove the key. Please try again.") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { SettingsViewModel(it.container.assistantRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var notificationsEnabled by remember { mutableStateOf(isNotificationAccessEnabled(context)) }
    var screenLockAvailable by remember { mutableStateOf(AppLock.canAuthenticate(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsEnabled = isNotificationAccessEnabled(context)
        screenLockAvailable = AppLock.canAuthenticate(context)
        onPauseOrDispose { }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ApiKeyCard(state, onSave = viewModel::saveKey, onClear = viewModel::clearKey)

            SettingsCard(icon = Icons.Outlined.Lock, title = "App lock") {
                Text(
                    "OneAssist asks for your fingerprint, face or screen lock when it opens, and again " +
                        "after it has been in the background for more than ${AppLock.TIMEOUT_MS / 1000} seconds. " +
                        "Screenshots and the recent apps preview are blocked.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!screenLockAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your phone has no screen lock, so the app cannot be locked. " +
                            "Set a PIN, pattern or fingerprint to protect your data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { openSecuritySettings(context) }) { Text("Set up screen lock") }
                }
            }

            SettingsCard(icon = Icons.Outlined.Notifications, title = "Bank notifications") {
                Text(
                    if (notificationsEnabled) {
                        "On. Payments from ING and Revolut notifications are added automatically."
                    } else {
                        "Off. Turn it on to add payments automatically from ING and Revolut notifications."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { openNotificationAccessSettings(context) }) {
                    Text(if (notificationsEnabled) "Manage access" else "Enable notification access")
                }
            }

            SettingsCard(icon = Icons.Outlined.Info, title = "About") {
                Text("OneAssist ${appVersion(context)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Everything stays on this phone in an encrypted database. No account, no tracking.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ApiKeyCard(
    state: SettingsUiState,
    onSave: (String, () -> Unit) -> Unit,
    onClear: () -> Unit,
) {
    var keyInput by rememberSaveable { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    SettingsCard(icon = Icons.Outlined.Key, title = "Assistant API key") {
        Text(
            "The assistant is optional and uses your own Anthropic API key. Create one at " +
                "console.anthropic.com under API keys, then paste it here. It is stored encrypted on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        when (state.hasApiKey) {
            true -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("A key is saved", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onClear) { Text("Remove") }
                }
            }
            false -> {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("API key") },
                    placeholder = { Text("sk-ant-…") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (visible) "Hide key" else "Show key",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onSave(keyInput) { keyInput = "" } },
                    enabled = keyInput.isNotBlank() && !state.saving,
                ) { Text("Save key") }
            }
            null -> Unit
        }
        state.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsCard(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private fun appVersion(context: Context): String = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    info.versionName ?: ""
} catch (e: Exception) {
    ""
}

private fun openSecuritySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
