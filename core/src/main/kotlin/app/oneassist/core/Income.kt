package app.oneassist.core

import java.time.LocalDate
import java.time.YearMonth

/** Rules for turning incoming money into income. */
object IncomeRules {

    fun isIncomeCategory(categoryId: String?, categories: List<Category> = DefaultCategories.all): Boolean =
        categoryId != null && Ledger.kinds(categories)[categoryId] == CategoryKind.INCOME

    /**
     * Marks an incoming transaction as income. Bank transfers (peerToPeer) are normally treated as
     * transfers; once the user says it is income (salary paid by bank transfer, a gift from a
     * friend) it must count as income, so the flag is cleared.
     */
    fun markAsIncome(t: Transaction, categoryId: String, categories: List<Category> = DefaultCategories.all): Transaction {
        require(t.amountCents > 0) { "Only money coming in can be income" }
        require(isIncomeCategory(categoryId, categories)) { "Not an income category: $categoryId" }
        return t.copy(categoryId = categoryId, peerToPeer = false)
    }

    /**
     * The peerToPeer flag to store after categorizing: money in that lands in an income category
     * (for example through a learned "always treat payments from X as salary" rule) is income.
     */
    fun peerToPeerAfterCategorizing(
        categoryId: String?,
        amountCents: Long,
        parsedPeerToPeer: Boolean,
        categories: List<Category> = DefaultCategories.all,
    ): Boolean = if (amountCents > 0 && isIncomeCategory(categoryId, categories)) false else parsedPeerToPeer
}

/** Income the user said repeats monthly. [received] is true once it showed up in the month. */
data class ExpectedIncome(
    val name: String,
    val categoryId: String?,
    val amountCents: Long,
    val expectedDate: LocalDate,
    val received: Boolean,
)

object IncomeInsights {

    /**
     * Expected income for [month] from transactions marked recurring (manual monthly income).
     * For each payer and category the latest recurring entry on or before the month is the template.
     * It counts as received when that month has money in from the same payer in the same category.
     */
    fun expectedIncome(transactions: List<Transaction>, month: YearMonth): List<ExpectedIncome> {
        val end = month.atEndOfMonth()
        val templates = transactions
            .filter { it.recurring && it.amountCents > 0 && !it.date.isAfter(end) && Ledger.isEur(it) }
            .groupBy { MerchantNormalizer.key(it.merchant) to it.categoryId }
            .values.map { group -> group.maxWith(compareBy<Transaction> { it.date }.thenBy { it.id }) }
        return templates.map { tpl ->
            val key = MerchantNormalizer.key(tpl.merchant)
            val received = transactions.any {
                it.amountCents > 0 && YearMonth.from(it.date) == month &&
                    MerchantNormalizer.key(it.merchant) == key && it.categoryId == tpl.categoryId
            }
            val day = minOf(tpl.date.dayOfMonth, month.lengthOfMonth())
            ExpectedIncome(tpl.merchant, tpl.categoryId, tpl.amountCents, month.atDay(day), received)
        }.sortedBy { it.expectedDate }
    }
}

/** What the user typed in the "Add transaction" form. [amountCents] is positive; [isIncome] sets the sign. */
data class ManualEntry(
    val isIncome: Boolean,
    val amountCents: Long,
    val date: LocalDate,
    val name: String,
    val account: AccountSource,
    val categoryId: String?,
    val tags: Set<String> = emptySet(),
    val note: String = "",
    val recurring: Boolean = false,
)

object ManualEntries {
    const val MAX_NAME = 120
    const val MAX_NOTE = 500
    /** € 10 million; anything above is a typo. */
    const val MAX_AMOUNT_CENTS = 1_000_000_000L

    /** Problems with the entry, empty when it can be saved. */
    fun validate(e: ManualEntry): List<String> = buildList {
        if (e.amountCents <= 0) add("Enter an amount above zero")
        if (e.amountCents > MAX_AMOUNT_CENTS) add("That amount looks too large")
        if (e.name.isBlank()) add("Enter a name")
    }

    /** The transaction to store. Manual rows are never treated as transfers between people. */
    fun toTransaction(id: String, e: ManualEntry): Transaction {
        require(validate(e).isEmpty()) { validate(e).joinToString() }
        val name = e.name.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(MAX_NAME)
        return Transaction(
            id = id,
            account = e.account,
            date = e.date,
            amountCents = if (e.isIncome) e.amountCents else -e.amountCents,
            merchant = MerchantNormalizer.normalize(name).ifBlank { name },
            description = "",
            categoryId = e.categoryId,
            source = ImportSource.MANUAL,
            peerToPeer = false,
            tags = Tags.normalizeAll(e.tags),
            note = e.note.trim().take(MAX_NOTE),
            recurring = e.isIncome && e.recurring,
        )
    }
}
