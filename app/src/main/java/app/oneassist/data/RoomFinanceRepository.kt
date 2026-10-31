package app.oneassist.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.oneassist.core.AccountSource
import app.oneassist.core.AssistantPromptBuilder
import app.oneassist.core.BankListScreenshotParser
import app.oneassist.core.Category
import app.oneassist.core.CsvImporter
import app.oneassist.core.DefaultLeakDetector
import app.oneassist.core.DefaultSavingsAdvisor
import app.oneassist.core.DefaultSubscriptionDetector
import app.oneassist.core.Deduplicator
import app.oneassist.core.ImportSource
import app.oneassist.core.Leak
import app.oneassist.core.Ledger
import app.oneassist.core.MerchantNormalizer
import app.oneassist.core.ParseResult
import app.oneassist.core.RawTransaction
import app.oneassist.core.RuleCategorizer
import app.oneassist.core.SavingsTip
import app.oneassist.core.Subscription
import app.oneassist.core.Transaction
import app.oneassist.ingest.ScreenshotOcr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * FinanceRepository backed by the encrypted Room database. Detectors from core run on
 * Dispatchers.Default whenever transactions or categories change.
 */
class RoomFinanceRepository(
    private val context: Context,
    private val db: OneAssistDatabase,
    scope: CoroutineScope,
) : FinanceRepository {

    private val transactionDao = db.transactionDao()
    private val categoryDao = db.categoryDao()
    private val ruleDao = db.ruleDao()
    private val importMutex = Mutex()

    private val subscriptionDetector = DefaultSubscriptionDetector()
    private val leakDetector = DefaultLeakDetector()
    private val savingsAdvisor = DefaultSavingsAdvisor()

    private data class Analysis(
        val transactions: List<Transaction>,
        val categories: List<Category>,
        val subscriptions: List<Subscription>,
        val leaks: List<Leak>,
        val tips: List<SavingsTip>,
    )

    /** One shared analysis of the last ~2 years, recomputed when data changes. */
    private val analysis: Flow<Analysis> = combine(
        transactionDao.observeSince(analysisStart()),
        categoryDao.observeAll(),
    ) { rows, cats -> rows to cats }
        .distinctUntilChanged()
        .map { (rows, cats) -> analyze(rows.map { it.toModel() }, cats.map { it.toModel() }) }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private fun analyze(transactions: List<Transaction>, categories: List<Category>): Analysis {
        val today = LocalDate.now()
        val subs = subscriptionDetector.detect(transactions, today)
        val leaks = leakDetector.detect(transactions, subs, categories, today)
        val tips = savingsAdvisor.tips(transactions, subs, leaks, categories, today)
        return Analysis(transactions, categories, subs, leaks, tips)
    }

    override fun transactions(month: YearMonth): Flow<List<Transaction>> =
        transactionDao.observeBetween(month.atDay(1), month.atEndOfMonth())
            .map { rows -> rows.map { it.toModel() } }
            .flowOn(Dispatchers.Default)

    override fun categories(): Flow<List<Category>> =
        categoryDao.observeAll().map { rows -> rows.map { it.toModel() } }

    override fun monthSummary(month: YearMonth): Flow<MonthSummary> =
        combine(
            transactionDao.observeBetween(month.minusMonths(1).atDay(1), month.atEndOfMonth()),
            categoryDao.observeAll(),
        ) { rows, cats ->
            val txs = rows.map { it.toModel() }
            val categories = cats.map { it.toModel() }
            val current = Ledger.totals(Ledger.inMonth(txs, month), categories)
            val previous = Ledger.totals(Ledger.inMonth(txs, month.minusMonths(1)), categories)
            MonthSummary(
                month = month,
                incomeCents = current.incomeCents,
                spendingCents = current.spendingCents,
                spendingByCategory = current.spendingByCategory,
                previousMonthSpendingCents = previous.spendingCents,
            )
        }.flowOn(Dispatchers.Default)

    override fun subscriptions(): Flow<List<Subscription>> = analysis.map { it.subscriptions }
    override fun leaks(): Flow<List<Leak>> = analysis.map { it.leaks }
    override fun savingsTips(): Flow<List<SavingsTip>> = analysis.map { it.tips }

    override suspend fun recategorize(transactionId: String, categoryId: String) {
        importMutex.withLock {
            db.withTransaction {
                val row = transactionDao.byId(transactionId) ?: return@withTransaction
                transactionDao.setCategory(transactionId, categoryId)
                val rule = RuleCategorizer.learnedRule(row.merchant, categoryId)
                if (rule.pattern.isNotBlank()) {
                    ruleDao.upsert(RuleEntity.from(rule))
                    // Same merchant everywhere follows the correction.
                    transactionDao.setCategoryForMerchant(row.merchant, categoryId)
                }
                Unit
            }
        }
    }

    override suspend fun importCsv(text: String): ImportReport {
        if (text.length > MAX_CSV_CHARS) {
            return ImportReport(0, 0, listOf("File is too large (over ${MAX_CSV_CHARS / 1_000_000} MB of text)"))
        }
        val parsed = withContext(Dispatchers.Default) { CsvImporter().parse(text) }
        return store(parsed)
    }

    override suspend fun importScreenshot(uri: Uri, account: AccountSource): ImportReport {
        val lines = try {
            ScreenshotOcr.recognizeLines(context, uri)
        } catch (e: Exception) {
            return ImportReport(0, 0, listOf("Could not read the image (${e.javaClass.simpleName})"))
        }
        if (lines.isEmpty()) return ImportReport(0, 0, listOf("No text found in the image"))
        val parsed = withContext(Dispatchers.Default) {
            BankListScreenshotParser().parse(lines, account, LocalDate.now())
        }
        if (parsed.transactions.isEmpty()) {
            return ImportReport(0, 0, parsed.skipped + "No transactions recognised in the image")
        }
        return store(parsed)
    }

    /**
     * Stores one transaction parsed from a bank notification, with de-duplication.
     * Returns true when a new row was added.
     */
    suspend fun insertNotificationTransaction(raw: RawTransaction): Boolean {
        require(raw.source == ImportSource.NOTIFICATION)
        return store(ParseResult(listOf(raw))).added > 0
    }

    /** Aggregate-only text for the assistant (see AssistantPromptBuilder). */
    suspend fun financialContext(builder: AssistantPromptBuilder): String {
        val a = analysis.first()
        return withContext(Dispatchers.Default) {
            builder.financialContext(a.transactions, a.subscriptions, a.leaks, a.categories, LocalDate.now())
        }
    }

    private suspend fun store(parsed: ParseResult): ImportReport {
        val incoming = parsed.transactions
        if (incoming.isEmpty()) return ImportReport(0, 0, parsed.skipped)
        return importMutex.withLock {
            db.withTransaction {
                val from = incoming.minOf { it.date }.minusDays(Deduplicator.windowDays)
                val to = incoming.maxOf { it.date }.plusDays(Deduplicator.windowDays)
                val existingRows = transactionDao.between(from, to)
                val existingById = existingRows.associateBy { it.id }
                val plan = Deduplicator.plan(incoming, existingRows.map { it.toModel() })
                val categorizer = RuleCategorizer(ruleDao.all().map { it.toModel() })
                val now = System.currentTimeMillis()

                fun categoryFor(raw: RawTransaction) =
                    categorizer.categorize(raw.counterparty, raw.description, raw.amountCents)

                val newRows = plan.insert.mapIndexed { i, raw ->
                    TransactionEntity(
                        id = UUID.randomUUID().toString(),
                        account = raw.account,
                        date = raw.date,
                        amountCents = raw.amountCents,
                        currency = raw.currency,
                        merchant = merchantName(raw),
                        description = raw.description.take(MAX_DESCRIPTION),
                        categoryId = categoryFor(raw),
                        source = raw.source,
                        peerToPeer = raw.peerToPeer,
                        createdAt = now + i,
                    )
                }
                val replaced = plan.replace.mapNotNull { (id, raw) ->
                    val old = existingById[id] ?: return@mapNotNull null
                    old.copy(
                        date = raw.date,
                        currency = raw.currency,
                        merchant = merchantName(raw),
                        description = raw.description.take(MAX_DESCRIPTION),
                        // Keep a category the user or rules already gave; otherwise categorize now.
                        categoryId = old.categoryId ?: categoryFor(raw),
                        source = raw.source,
                        peerToPeer = raw.peerToPeer,
                    )
                }
                if (newRows.isNotEmpty()) transactionDao.insertAll(newRows)
                if (replaced.isNotEmpty()) transactionDao.updateAll(replaced)
                ImportReport(added = newRows.size, duplicates = plan.duplicates + replaced.size, skipped = parsed.skipped)
            }
        }
    }

    private fun merchantName(raw: RawTransaction): String =
        MerchantNormalizer.normalize(raw.counterparty).ifBlank { raw.counterparty.trim() }.take(MAX_MERCHANT)

    private companion object {
        const val MAX_CSV_CHARS = 10_000_000
        const val MAX_DESCRIPTION = 500
        const val MAX_MERCHANT = 120

        fun analysisStart(): LocalDate = LocalDate.now().minusMonths(25).withDayOfMonth(1)
    }
}
