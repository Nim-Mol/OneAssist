package app.oneassist.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Finds money leaks of every [LeakType]. Impacts are positive cents per month.
 * Only merchant payments are named; transfers to people never appear in a leak.
 */
class DefaultLeakDetector : LeakDetector {

    override fun detect(
        transactions: List<Transaction>,
        subscriptions: List<Subscription>,
        categories: List<Category>,
        today: LocalDate,
    ): List<Leak> {
        val kinds = Ledger.kinds(categories)
        val names = (DefaultCategories.all + categories).associate { it.id to it.name }
        val past = transactions.filter { !it.date.isAfter(today) }
        return buildList {
            addAll(smallFrequent(past, kinds, today))
            addAll(priceIncreases(subscriptions))
            addAll(duplicateServices(subscriptions))
            fees(past, kinds, today)?.let { add(it) }
            addAll(categorySpikes(past, kinds, names, today))
            addAll(forgottenSubscriptions(past, subscriptions, kinds, today))
        }.filter { it.monthlyImpactCents > 0 }.sortedByDescending { it.monthlyImpactCents }
    }

    /** Four or more purchases of at most € 15 at one place in the last 30 days, adding up to € 20+. */
    private fun smallFrequent(tx: List<Transaction>, kinds: Map<String, CategoryKind>, today: LocalDate): List<Leak> {
        val from = today.minusDays(29)
        return Ledger.between(tx, from, today)
            .filter { Ledger.isNamedSpend(it, kinds) && abs(it.amountCents) <= SMALL_MAX }
            .filter { it.categoryId == null || it.categoryId !in smallFrequentExcluded }
            .filter { it.categoryId?.let(kinds::get) != CategoryKind.ESSENTIAL || it.categoryId == DefaultCategories.GROCERIES }
            .groupBy { MerchantNormalizer.key(it.merchant) }
            .values
            .filter { g -> g.size >= 4 && g.sumOf { -it.amountCents } >= 2_000 }
            .sortedByDescending { g -> g.sumOf { -it.amountCents } }
            .take(3)
            .map { g ->
                val total = g.sumOf { -it.amountCents }
                val name = MerchantNormalizer.normalize(g.last().merchant)
                Leak(
                    type = LeakType.SMALL_FREQUENT,
                    title = "$name: ${g.size} small buys, ${Amounts.format(total)} a month",
                    detail = "You paid at $name ${g.size} times in the last 30 days, ${Amounts.format(total / g.size)} on average. " +
                        "Each one is small, together it is ${Amounts.format(total * 12)} a year.",
                    monthlyImpactCents = total,
                    merchants = listOf(name),
                )
            }
    }

    private fun priceIncreases(subs: List<Subscription>): List<Leak> = subs.mapNotNull { s ->
        val old = s.priceIncreasedFromCents ?: return@mapNotNull null
        val delta = DefaultSubscriptionDetector.monthly(s.amountCents - old, s.cadence)
        val pct = if (old > 0) ((s.amountCents - old) * 100 / old) else 0
        Leak(
            type = LeakType.PRICE_INCREASE,
            title = "${s.merchant} went up from ${Amounts.format(old)} to ${Amounts.format(s.amountCents)}",
            detail = "That is $pct% more, about ${Amounts.format(delta * 12)} extra a year. Check for a cheaper plan or whether you still need it.",
            monthlyImpactCents = delta,
            merchants = listOf(s.merchant),
        )
    }

    private fun duplicateServices(subs: List<Subscription>): List<Leak> =
        serviceGroups.mapNotNull { (label, patterns) ->
            val members = subs.filter { s -> val k = MerchantNormalizer.key(s.merchant); patterns.any { RuleCategorizer.wordRegex(it).containsMatchIn(k) } }
                .distinctBy { MerchantNormalizer.key(it.merchant) }
            if (members.size < 2) return@mapNotNull null
            val sorted = members.sortedByDescending { it.monthlyCostCents }
            val saving = sorted.drop(1).sumOf { it.monthlyCostCents }
            Leak(
                type = LeakType.DUPLICATE_SERVICE,
                title = "${members.size} $label services: ${members.joinToString(", ") { it.merchant }}",
                detail = "Together they cost ${Amounts.format(members.sumOf { it.monthlyCostCents })} a month. " +
                    "Keeping only ${sorted.first().merchant} would save ${Amounts.format(saving)} a month. You can also rotate: one at a time.",
                monthlyImpactCents = saving,
                merchants = members.map { it.merchant },
            )
        }

    /** Bank fees, interest and similar charges over the last 90 days, per month. */
    private fun fees(tx: List<Transaction>, kinds: Map<String, CategoryKind>, today: LocalDate): Leak? {
        val window = Ledger.between(tx, today.minusDays(89), today).filter { it.amountCents < 0 && Ledger.isEur(it) }
        val feeRows = window.filter { t ->
            t.categoryId == DefaultCategories.BANK_FEES ||
                (t.categoryId == null && feeWords.any { w -> RuleCategorizer.wordRegex(w).containsMatchIn(t.merchant.lowercase()) })
        }
        if (feeRows.isEmpty()) return null
        val total = feeRows.sumOf { -it.amountCents }
        val months = monthsCovered(tx, today, 3)
        val monthly = total / months
        val sources = feeRows.map { MerchantNormalizer.normalize(it.merchant) }.distinct().take(5)
        return Leak(
            type = LeakType.FEES,
            title = "Bank fees and interest: ${Amounts.format(monthly)} a month",
            detail = "${feeRows.size} charges in the last ${if (months == 1L) "month" else "$months months"}, ${Amounts.format(total)} in total " +
                "(${sources.joinToString(", ")}). Check your account package and avoid card or currency fees where you can.",
            monthlyImpactCents = monthly,
            merchants = sources,
        )
    }

    /** Last 30 days per category against the average of the three 30-day windows before it. */
    private fun categorySpikes(tx: List<Transaction>, kinds: Map<String, CategoryKind>, names: Map<String, String>, today: LocalDate): List<Leak> {
        val earliest = tx.minOfOrNull { it.date } ?: return emptyList()
        if (ChronoUnit.DAYS.between(earliest, today) < 85) return emptyList() // not enough history
        fun window(i: Int): Map<String, Long> {
            val to = today.minusDays(30L * i)
            val from = to.minusDays(29)
            return Ledger.between(tx, from, to)
                .filter { Ledger.isEur(it) && Ledger.flowType(it, kinds) == FlowType.SPENDING }
                .groupBy(Ledger::categoryKey)
                .mapValues { (_, v) -> v.sumOf { -it.amountCents } }
        }
        val current = window(0)
        val previous = (1..3).map(::window)
        return current.mapNotNull { (cat, now) ->
            if (cat in spikeExcluded) return@mapNotNull null
            val history = previous.map { it[cat] ?: 0L }
            if (history.count { it > 0 } < 2) return@mapNotNull null
            val avg = history.sum() / 3
            val extra = now - avg
            if (avg <= 0 || now < avg * 14 / 10 || extra < 5_000) return@mapNotNull null
            val name = names[cat] ?: cat
            Leak(
                type = LeakType.CATEGORY_SPIKE,
                title = "$name is ${(extra * 100 / avg)}% above your usual",
                detail = "${Amounts.format(now)} in the last 30 days, against ${Amounts.format(avg)} on average in the three months before.",
                monthlyImpactCents = extra,
            )
        }
    }

    /**
     * New subscriptions (first charge in the last 40 days, possibly a trial that converted) and
     * yearly subscriptions that renew within 30 days, which are easy to forget.
     */
    private fun forgottenSubscriptions(tx: List<Transaction>, subs: List<Subscription>, kinds: Map<String, CategoryKind>, today: LocalDate): List<Leak> {
        val out = mutableListOf<Leak>()
        val earliest = tx.minOfOrNull { it.date }
        if (earliest != null && ChronoUnit.DAYS.between(earliest, today) >= 75) {
            tx.filter { it.categoryId == DefaultCategories.SUBSCRIPTIONS && Ledger.isNamedSpend(it, kinds) }
                .groupBy { MerchantNormalizer.key(it.merchant) }
                .values
                .filter { g -> g.minOf { it.date } >= today.minusDays(40) }
                .forEach { g ->
                    val name = MerchantNormalizer.normalize(g.last().merchant)
                    val amount = abs(g.maxBy { it.date }.amountCents)
                    out += Leak(
                        type = LeakType.FORGOTTEN_SUBSCRIPTION,
                        title = "New subscription: $name",
                        detail = "First charge of ${Amounts.format(amount)} on ${g.minOf { it.date }}. If this started as a free trial, cancel it now if you don't use it.",
                        monthlyImpactCents = amount,
                        merchants = listOf(name),
                    )
                }
        }
        subs.filter { it.cadence == Cadence.YEARLY && !it.nextExpected.isAfter(today.plusDays(30)) }.forEach { s ->
            out += Leak(
                type = LeakType.FORGOTTEN_SUBSCRIPTION,
                title = "${s.merchant} renews soon: ${Amounts.format(s.amountCents)}",
                detail = "Yearly charge expected around ${s.nextExpected}. Still using it? Cancel before then to save ${Amounts.format(s.amountCents)}.",
                monthlyImpactCents = s.monthlyCostCents,
                merchants = listOf(s.merchant),
            )
        }
        return out
    }

    /** Number of whole 30-day windows (1..max) that the data covers, to turn totals into per-month numbers. */
    private fun monthsCovered(tx: List<Transaction>, today: LocalDate, max: Long): Long {
        val earliest = tx.minOfOrNull { it.date } ?: return 1
        return (ChronoUnit.DAYS.between(earliest, today) / 30 + 1).coerceIn(1, max)
    }

    companion object {
        const val SMALL_MAX = 1_500L

        private val smallFrequentExcluded = setOf(
            DefaultCategories.TRANSPORT, DefaultCategories.HEALTH, DefaultCategories.BANK_FEES, DefaultCategories.CASH,
        )
        private val spikeExcluded = setOf(DefaultCategories.TAXES, DefaultCategories.HOUSING)
        private val feeWords = listOf("fee", "fees", "kosten", "rente", "interest", "betaalpakket")

        val serviceGroups: List<Pair<String, List<String>>> = listOf(
            "video streaming" to listOf("netflix", "disney+", "disney", "videoland", "hbo max", "max", "amazon prime", "prime video", "viaplay", "nlziet", "skyshowtime", "youtube", "apple tv"),
            "music streaming" to listOf("spotify", "apple music", "youtube music", "deezer", "tidal"),
            "cloud storage" to listOf("icloud", "google one", "dropbox", "onedrive", "microsoft 365"),
            "gym" to listOf("basic-fit", "basic fit", "sportcity", "anytime fitness", "trainmore", "fit for free", "sportschool"),
            "mobile phone" to listOf("kpn", "odido", "vodafone", "tele2", "simyo", "lebara", "youfone", "hollandsnieuwe"),
            "AI assistant" to listOf("openai", "chatgpt", "claude", "anthropic", "perplexity", "gemini"),
        )
    }
}
