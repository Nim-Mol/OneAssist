package app.oneassist.core

import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * What to do with a batch of incoming transactions given what is already stored.
 * [replace] maps an existing transaction id to the incoming row that should overwrite it
 * (CSV replacing a notification or screenshot row; keep the existing id and category).
 */
data class DedupPlan(
    val insert: List<RawTransaction>,
    val replace: List<Pair<String, RawTransaction>>,
    val duplicates: Int,
)

/**
 * De-duplication per ARCHITECTURE.md: an incoming row matches an existing one when account and
 * amount are equal, dates are within [windowDays] days and the normalized merchants match.
 * Each existing row matches at most one incoming row, so two genuine identical coffees on the
 * same day survive a re-import. CSV wins over notification and screenshot rows.
 */
object Deduplicator {
    const val windowDays = 3L

    fun plan(incoming: List<RawTransaction>, existing: List<Transaction>): DedupPlan {
        val consumed = HashSet<String>()
        val insert = mutableListOf<RawTransaction>()
        val replace = mutableListOf<Pair<String, RawTransaction>>()
        var duplicates = 0
        val byKey = existing.groupBy { it.account to it.amountCents }

        for (raw in incoming) {
            val candidates = byKey[raw.account to raw.amountCents].orEmpty()
                .filter { it.id !in consumed }
                .filter { abs(ChronoUnit.DAYS.between(it.date, raw.date)) <= windowDays }
                .filter { MerchantNormalizer.sameMerchant(it.merchant, raw.counterparty) }
            // Prefer the closest date, and a row from the same source on equal distance.
            val match = candidates.minWithOrNull(
                compareBy<Transaction> { abs(ChronoUnit.DAYS.between(it.date, raw.date)) }
                    .thenBy { if (it.source == raw.source) 0 else 1 }
            )
            if (match == null) { insert += raw; continue }
            consumed += match.id
            if (raw.source == ImportSource.CSV && match.source in setOf(ImportSource.NOTIFICATION, ImportSource.SCREENSHOT)) {
                replace += match.id to raw
            } else {
                duplicates++
            }
        }
        return DedupPlan(insert, replace, duplicates)
    }
}
