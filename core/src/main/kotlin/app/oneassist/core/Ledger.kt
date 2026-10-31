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
)

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
        for (t in transactions) {
            if (!isEur(t)) continue
            when (flowType(t, kinds)) {
                FlowType.INCOME -> income += t.amountCents
                FlowType.SPENDING -> {
                    spending -= t.amountCents
                    byCat.merge(categoryKey(t), -t.amountCents, Long::plus)
                }
                FlowType.SAVINGS -> savings -= t.amountCents
                FlowType.TRANSFER -> if (t.amountCents < 0) tOut -= t.amountCents else tIn += t.amountCents
            }
        }
        return Totals(income, spending, byCat, savings, tOut, tIn)
    }

    fun inMonth(transactions: List<Transaction>, month: YearMonth) =
        transactions.filter { YearMonth.from(it.date) == month }

    fun between(transactions: List<Transaction>, fromInclusive: LocalDate, toInclusive: LocalDate) =
        transactions.filter { !it.date.isBefore(fromInclusive) && !it.date.isAfter(toInclusive) }

    /** Money-out merchant payments that may be named (not transfers, income or savings). */
    fun isNamedSpend(t: Transaction, kinds: Map<String, CategoryKind>): Boolean =
        isEur(t) && t.amountCents < 0 && !t.peerToPeer && flowType(t, kinds) == FlowType.SPENDING
}
