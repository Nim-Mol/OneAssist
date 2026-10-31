package app.oneassist.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Finds recurring charges: same normalized merchant, amounts within ±15% of the latest charge and
 * regular intervals (weekly, monthly, quarterly or yearly; one missed charge is tolerated).
 * Transfers to people and own accounts are never subscriptions. Stopped series are dropped.
 */
class DefaultSubscriptionDetector : SubscriptionDetector {

    override fun detect(transactions: List<Transaction>, today: LocalDate): List<Subscription> {
        val groups = transactions
            .filter { it.amountCents < 0 && !it.peerToPeer && !it.date.isAfter(today) && Ledger.isEur(it) }
            .filter { it.categoryId !in nonSubscriptionCategories }
            .groupBy { MerchantNormalizer.key(it.merchant) }
            .filterKeys { it.isNotBlank() }

        return groups.values.mapNotNull { detectOne(it.sortedBy(Transaction::date), today) }
            .sortedByDescending { it.monthlyCostCents }
    }

    private fun detectOne(all: List<Transaction>, today: LocalDate): Subscription? {
        if (all.size < 2) return null
        val latest = all.last()
        val latestAmount = abs(latest.amountCents)
        // Walk back from the latest charge, collecting charges within ±15% of the newer neighbour,
        // so a gradual price increase stays in one series.
        val series = mutableListOf(latest)
        var reference = latestAmount
        for (t in all.dropLast(1).asReversed()) {
            val a = abs(t.amountCents)
            if (within(a, reference, 0.15) && within(a, latestAmount, 0.30)) {
                // One charge per day at most (ignore same-day duplicates in the series).
                if (series.none { it.date == t.date }) { series += t; reference = a }
            }
        }
        series.reverse()
        if (series.size < 2) return null

        // The series must be the main thing bought there (filters out regular shopping).
        val span = all.filter { !it.date.isBefore(series.first().date) }
        if (span.size > series.size * 2) return null

        val intervals = series.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
        val median = intervals.sorted()[intervals.size / 2]
        val cadence = Cadence.entries.firstOrNull { median in range(it) } ?: return null
        val ok = intervals.all { d -> d in range(cadence) || d in doubleRange(cadence) }
        if (!ok) return null
        if (intervals.count { it in doubleRange(cadence) } > 1) return null

        val amounts = series.map { abs(it.amountCents) }
        val nearlyIdentical = amounts.all { within(it, latestAmount, 0.02) }
        val minCount = when (cadence) {
            Cadence.WEEKLY -> 4
            Cadence.MONTHLY -> if (nearlyIdentical) 2 else 3
            Cadence.QUARTERLY, Cadence.YEARLY -> if (nearlyIdentical) 2 else 3
        }
        if (series.size < minCount) return null

        // Stopped? More than one and a half periods since the last charge.
        val sinceLast = ChronoUnit.DAYS.between(latest.date, today)
        if (sinceLast > cadence.approxDays * 1.5 + tolerance(cadence)) return null

        var next = advance(latest.date, cadence)
        while (next.isBefore(today)) next = advance(next, cadence)

        val previousDifferent = series.dropLast(1).lastOrNull { abs(it.amountCents) != latestAmount }
        val increasedFrom = previousDifferent
            ?.takeIf { abs(it.amountCents) < latestAmount && ChronoUnit.DAYS.between(it.date, today) <= 400 }
            ?.let { abs(it.amountCents) }

        return Subscription(
            merchant = MerchantNormalizer.normalize(latest.merchant).ifBlank { latest.merchant },
            amountCents = latestAmount,
            cadence = cadence,
            lastCharged = latest.date,
            nextExpected = next,
            monthlyCostCents = monthly(latestAmount, cadence),
            priceIncreasedFromCents = increasedFrom,
        )
    }

    companion object {
        /** Categories whose recurring payments are not subscriptions in the "cancel me" sense. */
        private val nonSubscriptionCategories = setOf(
            DefaultCategories.GROCERIES, DefaultCategories.TRANSFERS, DefaultCategories.SAVINGS,
            DefaultCategories.INCOME, DefaultCategories.CASH, DefaultCategories.TRANSPORT,
            DefaultCategories.HOUSING, DefaultCategories.TAXES, DefaultCategories.BANK_FEES,
        )

        fun monthly(amountCents: Long, cadence: Cadence): Long = when (cadence) {
            Cadence.WEEKLY -> amountCents * 52 / 12
            Cadence.MONTHLY -> amountCents
            Cadence.QUARTERLY -> amountCents / 3
            Cadence.YEARLY -> amountCents / 12
        }

        fun advance(date: LocalDate, cadence: Cadence): LocalDate = when (cadence) {
            Cadence.WEEKLY -> date.plusWeeks(1)
            Cadence.MONTHLY -> date.plusMonths(1)
            Cadence.QUARTERLY -> date.plusMonths(3)
            Cadence.YEARLY -> date.plusYears(1)
        }

        private fun tolerance(c: Cadence): Long = when (c) {
            Cadence.WEEKLY -> 2; Cadence.MONTHLY -> 5; Cadence.QUARTERLY -> 10; Cadence.YEARLY -> 20
        }

        private fun range(c: Cadence) = (c.approxDays - tolerance(c))..(c.approxDays + tolerance(c) + if (c == Cadence.MONTHLY) 1 else 0)
        private fun doubleRange(c: Cadence) = (2L * c.approxDays - 2 * tolerance(c))..(2L * c.approxDays + 2 * tolerance(c))

        private fun within(a: Long, b: Long, fraction: Double): Boolean =
            b != 0L && abs(a - b).toDouble() / b.toDouble() <= fraction
    }
}
