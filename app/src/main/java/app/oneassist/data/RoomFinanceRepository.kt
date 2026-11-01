package app.oneassist.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.oneassist.core.AccountSource
import app.oneassist.core.AssistantPromptBuilder
import app.oneassist.core.BankListScreenshotParser
import app.oneassist.core.Category
import app.oneassist.core.CategoryKind
import app.oneassist.core.CsvImporter
import app.oneassist.core.DefaultLeakDetector
import app.oneassist.core.DefaultSavingsAdvisor
import app.oneassist.core.DefaultSubscriptionDetector
import app.oneassist.core.Deduplicator
import app.oneassist.core.ImportSource
import app.oneassist.core.IncomeInsights
import app.oneassist.core.IncomeRules
import app.oneassist.core.Leak
import app.oneassist.core.Ledger
import app.oneassist.core.ManualEntries
import app.oneassist.core.ManualEntry
import app.oneassist.core.MerchantNormalizer
import app.oneassist.core.ParseResult
import app.oneassist.core.RawTransaction
import app.oneassist.core.RuleCategorizer
import app.oneassist.core.SavingsTip
import app.oneassist.core.Subscription
import app.oneassist.core.TagTotal
import app.oneassist.core.Tags
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

    override fun transactions(month: YearMonth, tag: String?): Flow<List<Transaction>> {
        val normalized = tag?.let { Tags.normalize(it) }
        val rows = if (normalized == null) {
            transactionDao.observeBetween(month.atDay(1), month.atEndOfMonth())
        } else {
            transactionDao.observeBetweenWithTag(month.atDay(1), month.atEndOfMonth(), normalized)
        }
        return rows.map { list -> list.map { it.toModel() } }.flowOn(Dispatchers.Default)
    }

    override fun allTags(): Flow<List<String>> =
        transactionDao.observeTagColumns()
            .map { columns -> columns.flatMap { Tags.decode(it) }.distinct().sorted() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override fun tagTotals(month: YearMonth): Flow<List<TagTotal>> =
        combine(transactionDao.observeBetween(month.atDay(1), month.atEndOfMonth()), categoryDao.observeAll()) { rows, cats ->
            Ledger.totalsByTag(rows.map { it.toModel() }, cats.map { it.toModel() })
        }.flowOn(Dispatchers.Default)

    override fun categories(): Flow<List<Category>> =
        categoryDao.observeAll().map { rows -> rows.map { it.toModel() } }

    override fun monthSummary(month: YearMonth): Flow<MonthSummary> =
        combine(
            transactionDao.observeBetween(month.minusMonths(1).atDay(1), month.atEndOfMonth()),
            categoryDao.observeAll(),
            transactionDao.observeRecurringIncome(),
        ) { rows, cats, recurringRows ->
            val txs = rows.map { it.toModel() }
            val categories = cats.map { it.toModel() }
            val thisMonth = Ledger.inMonth(txs, month)
            val current = Ledger.totals(thisMonth, categories)
            val previous = Ledger.totals(Ledger.inMonth(txs, month.minusMonths(1)), categories)
            val recurring = recurringRows.map { it.toModel() }
            val expected = IncomeInsights.expectedIncome(
                (recurring + thisMonth).distinctBy { it.id },
                month,
            )
            MonthSummary(
                month = month,
                incomeCents = current.incomeCents,
                spendingCents = current.spendingCents,
                spendingByCategory = current.spendingByCategory,
                previousMonthSpendingCents = previous.spendingCents,
                incomeByCategory = current.incomeByCategory,
                savingsRate = current.savingsRate,
                expectedIncome = expected,
            )
        }.flowOn(Dispatchers.Default)

    override fun subscriptions(): Flow<List<Subscription>> = analysis.map { it.subscriptions }
    override fun leaks(): Flow<List<Leak>> = analysis.map { it.leaks }
    override fun savingsTips(): Flow<List<SavingsTip>> = analysis.map { it.tips }

    override suspend fun recategorize(transactionId: String, categoryId: String) {
        val current = transactionDao.byId(transactionId) ?: return
        if (current.amountCents > 0 && IncomeRules.isIncomeCategory(categoryId, categoryDao.all().map { it.toModel() })) {
            // Picking an income category for money in is "mark as income, always for this payer".
            markAsIncome(transactionId, categoryId, alwaysForMerchant = true)
            return
        }
        importMutex.withLock {
            db.withTransaction {
                val row = transactionDao.byId(transactionId) ?: return@withTransaction
                transactionDao.setCategory(transactionId, categoryId)
                val rule = RuleCategorizer.learnedRule(row.merchant, categoryId)
                if (rule.pattern.isNotBlank()) {
                    ruleDao.upsert(RuleEntity.from(rule))
                    // Money in from this payer moved out of income: forget the learned income rule.
                    if (row.amountCents > 0) ruleDao.delete("learned-income:${rule.pattern}")
                    // Same merchant everywhere follows the correction, except rows marked as income.
                    val incomeIds = categoryDao.all().filter { it.kind == CategoryKind.INCOME }.map { it.id }
                    transactionDao.setCategoryForMerchant(row.merchant, categoryId, incomeIds.ifEmpty { listOf("") })
                }
                Unit
            }
        }
    }

    override suspend fun setTags(transactionId: String, tags: Set<String>) {
        transactionDao.setTags(transactionId, Tags.encode(tags))
    }

    override suspend fun markAsIncome(transactionId: String, categoryId: String, alwaysForMerchant: Boolean) {
        importMutex.withLock {
            db.withTransaction {
                val categories = categoryDao.all().map { it.toModel() }
                require(IncomeRules.isIncomeCategory(categoryId, categories)) { "Not an income category" }
                val row = transactionDao.byId(transactionId) ?: return@withTransaction
                if (row.amountCents <= 0) return@withTransaction
                transactionDao.markAsIncome(transactionId, categoryId)
                if (alwaysForMerchant) {
                    val rule = RuleCategorizer.learnedRule(row.merchant, categoryId)
                    if (rule.pattern.isNotBlank()) {
                        ruleDao.upsert(RuleEntity.from(rule))
                        transactionDao.markAsIncomeForMerchant(row.merchant, categoryId)
                    }
                }
                Unit
            }
        }
    }

    override suspend fun addManual(entry: ManualEntry): String {
        val problems = ManualEntries.validate(entry)
        require(problems.isEmpty()) { problems.joinToString() }
        val id = UUID.randomUUID().toString()
        return importMutex.withLock {
            db.withTransaction {
                var t = ManualEntries.toTransaction(id, entry)
                if (t.categoryId == null) {
                    val categorizer = RuleCategorizer(ruleDao.all().map { it.toModel() })
                    t = t.copy(categoryId = categorizer.categorize(t.merchant, "", t.amountCents))
                }
                // Manual rows skip de-dup: the user typed them on purpose.
                transactionDao.insertAll(listOf(TransactionEntity.from(t, System.currentTimeMillis())))
                id
            }
        }
    }

    override suspend fun setRecurring(transactionId: String, recurring: Boolean) {
        transactionDao.setRecurring(transactionId, recurring)
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
                val categories = categoryDao.all().map { it.toModel() }
                val now = System.currentTimeMillis()

                fun categoryFor(raw: RawTransaction) =
                    categorizer.categorize(raw.counterparty, raw.description, raw.amountCents)

                val newRows = plan.insert.mapIndexed { i, raw ->
                    val categoryId = categoryFor(raw)
                    TransactionEntity(
                        id = UUID.randomUUID().toString(),
                        account = raw.account,
                        date = raw.date,
                        amountCents = raw.amountCents,
                        currency = raw.currency,
                        merchant = merchantName(raw),
                        description = raw.description.take(MAX_DESCRIPTION),
                        categoryId = categoryId,
                        source = raw.source,
                        // Money in that a learned rule calls income is income, not a transfer.
                        peerToPeer = IncomeRules.peerToPeerAfterCategorizing(categoryId, raw.amountCents, raw.peerToPeer, categories),
                        createdAt = now + i,
                    )
                }
                val replaced = plan.replace.mapNotNull { (id, raw) ->
                    val old = existingById[id] ?: return@mapNotNull null
                    // Keep a category the user or rules already gave; otherwise categorize now.
                    val categoryId = old.categoryId ?: categoryFor(raw)
                    old.copy(
                        date = raw.date,
                        currency = raw.currency,
                        merchant = merchantName(raw),
                        description = raw.description.take(MAX_DESCRIPTION),
                        categoryId = categoryId,
                        source = raw.source,
                        peerToPeer = IncomeRules.peerToPeerAfterCategorizing(categoryId, old.amountCents, raw.peerToPeer, categories),
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
