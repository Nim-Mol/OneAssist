package app.oneassist.data

import app.oneassist.core.AccountSource
import app.oneassist.core.Category
import app.oneassist.core.Leak
import app.oneassist.core.SavingsTip
import app.oneassist.core.Subscription
import app.oneassist.core.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.YearMonth

/** Summary of one month for the Overview screen. Amounts in cents (spending is positive here). */
data class MonthSummary(
    val month: YearMonth,
    val incomeCents: Long,
    val spendingCents: Long,
    val spendingByCategory: Map<String, Long>,
    val previousMonthSpendingCents: Long,
)

data class ImportReport(val added: Int, val duplicates: Int, val skipped: List<String>)

/**
 * The only door between UI and data. The UI never touches Room, files or the network directly.
 * Implemented by the back end (RoomFinanceRepository).
 */
interface FinanceRepository {
    fun transactions(month: YearMonth): Flow<List<Transaction>>
    fun categories(): Flow<List<Category>>
    fun monthSummary(month: YearMonth): Flow<MonthSummary>
    fun subscriptions(): Flow<List<Subscription>>
    fun leaks(): Flow<List<Leak>>
    fun savingsTips(): Flow<List<SavingsTip>>

    /** Change a transaction's category and learn a rule so similar ones follow. */
    suspend fun recategorize(transactionId: String, categoryId: String)

    suspend fun importCsv(text: String): ImportReport
    /** Runs on-device OCR on the image at [uri] and imports what it finds. */
    suspend fun importScreenshot(uri: android.net.Uri, account: AccountSource): ImportReport
}

/** Chat with the personal assistant. */
interface AssistantRepository {
    fun hasApiKey(): Boolean
    fun saveApiKey(key: String)
    fun clearApiKey()
    /** Sends the conversation plus an aggregate-only summary of the user's finances. */
    suspend fun ask(history: List<ChatMessage>, question: String): Result<String>
}

data class ChatMessage(val fromUser: Boolean, val text: String)
