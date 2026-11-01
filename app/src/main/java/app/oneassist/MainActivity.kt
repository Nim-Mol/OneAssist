package app.oneassist

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import app.oneassist.ui.OneAssistRoot
import app.oneassist.ui.lock.AppLock
import app.oneassist.ui.share.ShareViewModel
import app.oneassist.ui.theme.OneAssistTheme

/**
 * Single activity. A FragmentActivity because BiometricPrompt needs one.
 * FLAG_SECURE keeps balances out of screenshots and the recent apps preview.
 */
class MainActivity : FragmentActivity() {
    private lateinit var shareViewModel: ShareViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        AppLock.install()

        val app = application as OneAssistApp
        shareViewModel = ViewModelProvider(this, ShareViewModel.factory(app))[ShareViewModel::class]
        // On recreation (rotation, process restore) the share was already handled.
        if (savedInstanceState == null) shareViewModel.handleIntent(intent)

        setContent {
            OneAssistTheme {
                OneAssistRoot(shareViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareViewModel.handleIntent(intent)
    }
}
