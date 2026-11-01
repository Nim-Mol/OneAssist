package app.oneassist.ingest

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.oneassist.OneAssistApp
import app.oneassist.core.BankNotificationParsers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Reads payment notifications from the ING and Revolut apps only and stores the parsed
 * transaction (date, amount, merchant). Raw notification text is never stored or logged.
 */
class BankNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val parser = BankNotificationParsers.forPackage(sbn.packageName) ?: return
        val notification = sbn.notification ?: return
        // Group summaries repeat the content of their children.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val postedOn = Instant.ofEpochMilli(sbn.postTime).atZone(ZoneId.systemDefault()).toLocalDate()
        val raw = try {
            parser.parse(title, text, postedOn)
        } catch (_: Exception) {
            null
        } ?: return

        val app = application as? OneAssistApp ?: return
        scope.launch {
            // Failures are swallowed on purpose: logging could expose financial data.
            runCatching { app.container.financeRepositoryImpl.insertNotificationTransaction(raw) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
