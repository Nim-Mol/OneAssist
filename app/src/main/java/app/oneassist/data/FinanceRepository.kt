package app.oneassist.data

import app.oneassist.core.AccountSource
import app.oneassist.core.Category
import app.oneassist.core.ExpectedIncome
import app.oneassist.core.Leak
import app.oneassist.core.ManualEntry
import app.oneassist.core.SavingsTip
import app.oneassist.core.Subscription
import app.oneassist.core.TagTotal
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
    /** Income per income category id (uncategorized money in counts as "income", Other income). */
    val incomeByCategory: Map<String, Long> = emptyMap(),
    /** (income - spending) / income, null without income. */
    val savingsRate: Double? = null,
    /** Recurring manual income expected this month. */
    val expectedIncome: List<ExpectedIncome> = emptyList(),
)

data class ImportReport(val added: Int, val duplicates: Int, val skipped: List<String>)

/**
 * The only door between UI and data. The UI never touches Room, files or the network directly.
 * Implemented by the back end (RoomFinanceRepository).
 */
interface FinanceRepository {
    /** Transactions of [month], only those carrying [tag] when it is given. */
    fun transactions(month: YearMonth, tag: String? = null): Flow<List<Transaction>>
    /** Every tag in use, sorted. */
    fun allTags(): Flow<List<String>>
    /** Spending and income per tag in [month], biggest first. */
    fun tagTotals(month: YearMonth): Flow<List<TagTotal>>
    fun categories(): Flow<List<Category>>
    fun monthSummary(month: YearMonth): Flow<MonthSummary>
    fun subscriptions(): Flow<List<Subscription>>
    fun leaks(): Flow<List<Leak>>
    fun savingsTips(): Flow<List<SavingsTip>>

    /** Change a transaction's category and learn a rule so similar ones follow. */
    suspend fun recategorize(transactionId: String, categoryId: String)

    /** Replaces the transaction's tags (normalized, at most 10). */
    suspend fun setTags(transactionId: String, tags: Set<String>)

    /**
     * Marks money coming in as income in [categoryId] and stops treating it as a transfer.
     * With [alwaysForMerchant], a learned rule makes future money in from the same payer income too.
     */
    suspend fun markAsIncome(transactionId: String, categoryId: String, alwaysForMerchant: Boolean)

    /** Stores a manual entry (source MANUAL) and returns its id. */
    suspend fun addManual(entry: ManualEntry): String

    /** Turns "repeats monthly" on or off for manual income. */
    suspend fun setRecurring(transactionId: String, recurring: Boolean)

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
