package app.oneassist.core

import java.time.LocalDate
import java.time.YearMonth

enum class FlowType { INCOME, SPENDING, TRANSFER, SAVINGS }

/** Totals for a set of transactions. Spending, savings and transfer amounts are positive here. */
data class Totals(
    val incomeCents: Long,
    val spendingCents: Long,
    val spendingByCategory: Map<String, Long>,
    val savingsCents: Long,
    val transfersOutCents: Long,
    val transfersInCents: Long,
    /** Income per income category; uncategorized money in counts as [DefaultCategories.INCOME]. */
    val incomeByCategory: Map<String, Long> = emptyMap(),
) {
    /** (income - spending) / income, or null without income. See [Ledger.savingsRate]. */
    val savingsRate: Double? get() = Ledger.savingsRate(incomeCents, spendingCents)
}

/**
 * Shared bookkeeping rules so the repository, detectors and prompt builder agree on what counts as
 * spending. Only EUR rows are counted; other currencies would distort the euro totals.
 */
object Ledger {

    fun kinds(categories: List<Category>): Map<String, CategoryKind> =
        (DefaultCategories.all + categories).associate { it.id to it.kind }

    fun flowType(t: Transaction, kinds: Map<String, CategoryKind>): FlowType =
        when (t.categoryId?.let { kinds[it] }) {
            CategoryKind.INCOME -> FlowType.INCOME
            CategoryKind.TRANSFER -> FlowType.TRANSFER
            CategoryKind.SAVINGS -> FlowType.SAVINGS
            CategoryKind.ESSENTIAL, CategoryKind.DISCRETIONARY -> FlowType.SPENDING
            null -> when {
                t.peerToPeer -> FlowType.TRANSFER
                t.amountCents < 0 -> FlowType.SPENDING
                else -> FlowType.INCOME
            }
        }

    fun categoryKey(t: Transaction): String = t.categoryId ?: DefaultCategories.UNCATEGORIZED

    fun isEur(t: Transaction) = t.currency.equals("EUR", ignoreCase = true)

    fun totals(transactions: List<Transaction>, categories: List<Category>): Totals {
        val kinds = kinds(categories)
        var income = 0L; var spending = 0L; var savings = 0L; var tOut = 0L; var tIn = 0L
        val byCat = LinkedHashMap<String, Long>()
        val incomeByCat = LinkedHashMap<String, Long>()
        for (t in transactions) {
            if (!isEur(t)) continue
            when (flowType(t, kinds)) {
                FlowType.INCOME -> {
                    income += t.amountCents
                    incomeByCat.merge(t.categoryId ?: DefaultCategories.INCOME, t.amountCents, Long::plus)
                }
                FlowType.SPENDING -> {
                    spending -= t.amountCents
                    byCat.merge(categoryKey(t), -t.amountCents, Long::plus)
                }
                FlowType.SAVINGS -> savings -= t.amountCents
                FlowType.TRANSFER -> if (t.amountCents < 0) tOut -= t.amountCents else tIn += t.amountCents
            }
        }
        return Totals(income, spending, byCat, savings, tOut, tIn, incomeByCat)
    }

    /**
     * Share of income not spent: (income - spending) / income. Null when there is no income.
     * Negative when spending is higher than income.
     */
    fun savingsRate(incomeCents: Long, spendingCents: Long): Double? =
        if (incomeCents <= 0) null else (incomeCents - spendingCents).toDouble() / incomeCents.toDouble()

    /**
     * Spending and income per tag, biggest first. A transaction with several tags counts for
     * each of them. Transfers and savings are left out.
     */
    fun totalsByTag(transactions: List<Transaction>, categories: List<Category>): List<TagTotal> {
        val kinds = kinds(categories)
        val spend = HashMap<String, Long>(); val inc = HashMap<String, Long>(); val count = HashMap<String, Int>()
        for (t in transactions) {
            if (!isEur(t) || t.tags.isEmpty()) continue
            val flow = flowType(t, kinds)
            for (tag in t.tags) {
                when (flow) {
                    FlowType.SPENDING -> spend.merge(tag, -t.amountCents, Long::plus)
                    FlowType.INCOME -> inc.merge(tag, t.amountCents, Long::plus)
                    FlowType.SAVINGS, FlowType.TRANSFER -> {}
                }
                count.merge(tag, 1, Int::plus)
            }
        }
        return count.keys
            .map { TagTotal(it, spend[it] ?: 0L, inc[it] ?: 0L, count.getValue(it)) }
            .sortedWith(compareByDescending<TagTotal> { it.spendingCents + it.incomeCents }.thenBy { it.tag })
    }

    /** Transactions carrying [tag] (normalized before comparing). */
    fun withTag(transactions: List<Transaction>, tag: String): List<Transaction> {
        val n = Tags.normalize(tag) ?: return emptyList()
        return transactions.filter { n in it.tags }
    }

    fun inMonth(transactions: List<Transaction>, month: YearMonth) =
        transactions.filter { YearMonth.from(it.date) == month }

    fun between(transactions: List<Transaction>, fromInclusive: LocalDate, toInclusive: LocalDate) =
        transactions.filter { !it.date.isBefore(fromInclusive) && !it.date.isAfter(toInclusive) }

    /** Money-out merchant payments that may be named (not transfers, income or savings). */
    fun isNamedSpend(t: Transaction, kinds: Map<String, CategoryKind>): Boolean =
        isEur(t) && t.amountCents < 0 && !t.peerToPeer && flowType(t, kinds) == FlowType.SPENDING
}
