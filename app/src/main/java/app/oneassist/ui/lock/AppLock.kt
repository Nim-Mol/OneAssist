package app.oneassist.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Process-wide lock state. Starts locked (so every cold start asks for authentication) and locks
 * again when the app comes back after more than [TIMEOUT_MS] in the background.
 * Living outside the Activity means a rotation or a share into the app does not relock.
 */
object AppLock : DefaultLifecycleObserver {
    const val TIMEOUT_MS = 60_000L

    var isLocked by mutableStateOf(true)
        private set

    /** True when the device has no screen lock or biometrics, so the app cannot be protected. */
    var noScreenLock by mutableStateOf(false)
        private set

    private var backgroundedAt = 0L
    private var installed = false

    /** Call from the main thread (Activity.onCreate). Safe to call more than once. */
    fun install() {
        if (installed) return
        installed = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundedAt
        backgroundedAt = 0L
        if (since != 0L && SystemClock.elapsedRealtime() - since > TIMEOUT_MS) {
            isLocked = true
        }
    }

    internal fun unlocked() {
        noScreenLock = false
        isLocked = false
    }

    internal fun unlockedWithoutScreenLock() {
        noScreenLock = true
        isLocked = false
    }

    /**
     * Allowed authenticators. BIOMETRIC_STRONG | DEVICE_CREDENTIAL is not supported on Android 10
     * (API 29), so there we fall back to BIOMETRIC_WEAK | DEVICE_CREDENTIAL.
     */
    val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        }

    /** True when the device can authenticate the user (biometrics or a PIN, pattern or password). */
    fun canAuthenticate(context: Context): Boolean {
        val result = BiometricManager.from(context).canAuthenticate(authenticators)
        if (result == BiometricManager.BIOMETRIC_SUCCESS) return true
        // Some devices report odd biometric states while a PIN is set; trust the keyguard then.
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return result != BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED &&
            keyguard?.isDeviceSecure == true
    }
}

/** Shows [content] only when unlocked; otherwise a lock screen that asks for authentication. */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    if (!AppLock.isLocked) {
        content()
        return
    }
    val context = LocalContext.current
    val available = remember { AppLock.canAuthenticate(context) }
    if (!available) {
        // Nothing to authenticate with: let the user in, a banner recommends a screen lock.
        LaunchedEffect(Unit) { AppLock.unlockedWithoutScreenLock() }
        Surface(Modifier.fillMaxSize()) {}
        return
    }
    LockScreen()
}

@Composable
private fun LockScreen() {
    val activity = LocalContext.current.findFragmentActivity()
    var message by remember { mutableStateOf<String?>(null) }
    var prompting by remember { mutableStateOf(false) }

    fun authenticate() {
        if (prompting || activity == null) return
        prompting = true
        message = null
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                AppLock.unlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                message = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> null
                    else -> errString.toString()
                }
            }
            // onAuthenticationFailed (a finger that did not match) is handled by the system prompt.
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock OneAssist")
            .setSubtitle("Your finances stay private")
            .setAllowedAuthenticators(AppLock.authenticators)
            // No negative button: DEVICE_CREDENTIAL provides "Use PIN" itself.
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            prompting = false
            message = "Could not start the unlock prompt. Tap Unlock to try again."
        }
    }

    LaunchedEffect(Unit) { authenticate() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text("OneAssist is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Use your fingerprint, face or screen lock to continue.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(32.dp))
            Button(onClick = { authenticate() }, enabled = !prompting) { Text("Unlock") }
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
