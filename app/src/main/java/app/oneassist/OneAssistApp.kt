package app.oneassist

import android.app.Application
import android.content.Context
import app.oneassist.assistant.AnthropicClient
import app.oneassist.assistant.ApiKeyStore
import app.oneassist.assistant.AssistantRepositoryImpl
import app.oneassist.data.AssistantRepository
import app.oneassist.data.FinanceRepository
import app.oneassist.data.OneAssistDatabase
import app.oneassist.data.RoomFinanceRepository
import app.oneassist.security.DatabaseKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the app-wide object graph (manual DI, see ARCHITECTURE.md). */
class OneAssistApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Manual dependency injection. Everything is created lazily on first use, so the encrypted
 * database is only opened when a screen or the notification listener needs it.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Lives as long as the process; used for shared flows. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: OneAssistDatabase by lazy {
        val passphrase = DatabaseKeyProvider(appContext).getOrCreatePassphrase(
            onUnrecoverable = { OneAssistDatabase.deleteFiles(appContext) },
        )
        OneAssistDatabase.open(appContext, passphrase)
    }

    /** The concrete repository, for back end callers such as the notification listener. */
    val financeRepositoryImpl: RoomFinanceRepository by lazy {
        RoomFinanceRepository(appContext, database, applicationScope)
    }

    val financeRepository: FinanceRepository get() = financeRepositoryImpl

    val apiKeyStore: ApiKeyStore by lazy { ApiKeyStore(appContext) }

    val assistantRepository: AssistantRepository by lazy {
        AssistantRepositoryImpl(apiKeyStore, AnthropicClient(), financeRepositoryImpl)
    }
}
