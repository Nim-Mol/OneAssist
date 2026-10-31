package app.oneassist.core

/** The categories the app ships with. Ids are stable; names are shown in the UI. */
object DefaultCategories {
    const val GROCERIES = "groceries"
    const val TRANSPORT = "transport"
    const val EATING_OUT = "eating_out"
    const val SUBSCRIPTIONS = "subscriptions"
    const val UTILITIES = "utilities"
    const val HEALTH_INSURANCE = "health_insurance"
    const val INSURANCE = "insurance"
    const val HOUSING = "housing"
    const val HEALTH = "health"
    const val SHOPPING = "shopping"
    const val LEISURE = "leisure"
    const val CASH = "cash"
    const val TAXES = "taxes"
    const val BANK_FEES = "bank_fees"
    const val INCOME = "income"
    const val SAVINGS = "savings"
    const val TRANSFERS = "transfers"
    /** Used as the key for spending without a category (categoryId == null). */
    const val UNCATEGORIZED = "uncategorized"

    val all: List<Category> = listOf(
        Category(GROCERIES, "Groceries", CategoryKind.ESSENTIAL),
        Category(HOUSING, "Housing", CategoryKind.ESSENTIAL),
        Category(UTILITIES, "Utilities & telecom", CategoryKind.ESSENTIAL),
        Category(HEALTH_INSURANCE, "Health insurance", CategoryKind.ESSENTIAL),
        Category(INSURANCE, "Other insurance", CategoryKind.ESSENTIAL),
        Category(TRANSPORT, "Transport", CategoryKind.ESSENTIAL),
        Category(HEALTH, "Health & pharmacy", CategoryKind.ESSENTIAL),
        Category(TAXES, "Taxes & government", CategoryKind.ESSENTIAL),
        Category(EATING_OUT, "Eating out & delivery", CategoryKind.DISCRETIONARY),
        Category(SUBSCRIPTIONS, "Subscriptions & streaming", CategoryKind.DISCRETIONARY),
        Category(SHOPPING, "Shopping", CategoryKind.DISCRETIONARY),
        Category(LEISURE, "Leisure & going out", CategoryKind.DISCRETIONARY),
        Category(CASH, "Cash withdrawals", CategoryKind.DISCRETIONARY),
        Category(BANK_FEES, "Bank fees & interest", CategoryKind.DISCRETIONARY),
        Category(UNCATEGORIZED, "Uncategorized", CategoryKind.DISCRETIONARY),
        Category(INCOME, "Income", CategoryKind.INCOME),
        Category(SAVINGS, "Savings & investing", CategoryKind.SAVINGS),
        Category(TRANSFERS, "Transfers", CategoryKind.TRANSFER),
    )
}

/**
 * Dutch starter rules. Patterns are matched as whole words, case-insensitively, against the
 * normalized merchant first and the description second. Higher priority wins.
 */
object DefaultRules {
    private fun rules(categoryId: String, priority: Int, vararg patterns: String) =
        patterns.map { CategoryRule(it, categoryId, priority) }

    val all: List<CategoryRule> = buildList {
        addAll(rules(DefaultCategories.BANK_FEES, 20,
            "revolut fee", "kosten betaalpakket", "betaalpakket", "kosten oranjepakket", "ing kosten", "bankkosten",
            "fee", "fees", "debetrente", "rente roodstand", "interest charge", "transactiekosten", "koersverschil",
            "plan fee", "premium plan", "metal plan"))
        addAll(rules(DefaultCategories.INCOME, 15,
            "salaris", "salary", "loon", "payroll", "uwv", "svb", "zorgtoeslag", "huurtoeslag", "kinderbijslag",
            "kindgebonden budget", "belastingteruggave", "vakantiegeld", "eindejaarsuitkering"))
        addAll(rules(DefaultCategories.TRANSFERS, 10,
            "revolut", "revolut bank", "to pocket", "from pocket", "pocket", "savings vault", "eigen rekening",
            "top-up", "topup", "opwaardering", "exchanged to", "exchanged from", "oranje spaarrekening"))
        addAll(rules(DefaultCategories.SAVINGS, 10,
            "spaarrekening", "degiro", "flatex", "meesman", "brand new day", "trade republic", "bux", "peaks", "lightyear"))
        addAll(rules(DefaultCategories.HOUSING, 12,
            "huur", "rent", "hypotheek", "mortgage", "vve", "woonstad", "ymere", "eigen haard", "de alliantie",
            "stadgenoot", "rochdale", "vestia", "portaal", "mitros", "bo-ex", "servicekosten"))
        addAll(rules(DefaultCategories.SUBSCRIPTIONS, 5,
            "netflix", "spotify", "disney", "disney+", "videoland", "youtube", "apple", "google", "google one",
            "hbo max", "amazon prime", "prime video", "viaplay", "nlziet", "skyshowtime", "storytel", "audible",
            "openai", "chatgpt", "icloud", "adobe", "microsoft", "dropbox", "basic-fit", "sportcity", "anytime fitness",
            "deezer", "tidal", "kindle unlimited", "patreon", "nrc", "volkskrant", "de telegraaf", "het parool"))
        addAll(rules(DefaultCategories.EATING_OUT, 5,
            "thuisbezorgd", "uber eats", "deliveroo", "starbucks", "mcdonald's", "mcdonalds", "burger king", "kfc",
            "domino's", "dominos", "new york pizza", "febo", "dunkin", "coffee company", "bagels & beans", "la place",
            "subway", "five guys", "restaurant", "cafe", "café", "eetcafe", "pizzeria", "sushi", "bakkerij", "lunchroom"))
        addAll(rules(DefaultCategories.GROCERIES, 3,
            "albert heijn", "ah", "ah to go", "jumbo", "lidl", "aldi", "plus", "dirk", "dirk van den broek",
            "dekamarkt", "coop", "spar", "vomar", "hoogvliet", "picnic", "crisp", "ekoplaza", "flink", "nettorama",
            "boni", "poiesz", "jan linders", "marqt", "odin", "toko", "slagerij", "kaashandel"))
        addAll(rules(DefaultCategories.TRANSPORT, 3,
            "ns", "ns reizigers", "gvb", "ret", "htm", "arriva", "connexxion", "qbuzz", "keolis", "ebs", "translink",
            "ov-chipkaart", "ovpay", "shell", "bp", "esso", "tango", "tinq", "totalenergies", "texaco", "gulf",
            "q-park", "parkmobile", "yellowbrick", "easypark", "swapfiets", "uber", "bolt", "felyx",
            "greenwheels", "mywheels", "anwb", "parkeren", "tankstation", "fastned", "allego", "vandebron laden"))
        addAll(rules(DefaultCategories.UTILITIES, 3,
            "vattenfall", "eneco", "essent", "greenchoice", "budget energie", "vandebron", "oxxio", "energiedirect",
            "ziggo", "kpn", "odido", "t-mobile", "vodafone", "tele2", "simyo", "lebara", "youfone", "hollandsnieuwe",
            "delta fiber", "waternet", "dunea", "evides", "vitens", "brabant water", "pwn", "oasen"))
        addAll(rules(DefaultCategories.HEALTH_INSURANCE, 6,
            "zilveren kruis", "cz", "vgz", "menzis", "ohra", "dsw", "ditzo", "fbto", "zorgverzekering", "interpolis zorg",
            "a.s.r. ziektekosten", "zekur", "anderzorg", "unive zorg"))
        addAll(rules(DefaultCategories.INSURANCE, 4,
            "centraal beheer", "interpolis", "nationale-nederlanden", "nationale nederlanden", "asr", "a.s.r.", "allianz",
            "inshared", "unive", "univé", "verzekering", "verzekeringen", "ditzo verzekeringen"))
        addAll(rules(DefaultCategories.TAXES, 6,
            "belastingdienst", "gemeente", "waterschap", "cjib", "rdw", "gemeentebelastingen", "bsgr", "svhw"))
        addAll(rules(DefaultCategories.HEALTH, 3,
            "kruidvat", "etos", "apotheek", "tandarts", "huisarts", "fysio", "fysiotherapie", "opticien", "pearle",
            "hans anders", "da drogist", "holland & barrett"))
        addAll(rules(DefaultCategories.SHOPPING, 2,
            "bol.com", "bol", "coolblue", "amazon", "zalando", "hema", "action", "ikea", "mediamarkt", "primark",
            "h&m", "zara", "wehkamp", "blokker", "decathlon", "gamma", "praxis", "karwei", "hornbach", "temu", "shein",
            "aliexpress", "uniqlo", "c&a", "xenos", "flying tiger", "intertoys", "bijenkorf", "de bijenkorf"))
        addAll(rules(DefaultCategories.LEISURE, 2,
            "pathe", "pathé", "vue", "kinepolis", "ticketmaster", "eventim", "efteling", "museum", "museumkaart",
            "booking.com", "airbnb", "transavia", "klm", "easyjet", "ryanair", "steam", "playstation", "nintendo"))
        addAll(rules(DefaultCategories.CASH, 8, "geldautomaat", "atm", "cash withdrawal", "opname"))
        // Generic bank-transfer markers: only used when nothing more specific matched.
        addAll(rules(DefaultCategories.TRANSFERS, 1,
            "online bankieren", "overschrijving", "transfer", "overboeking", "tikkie", "betaalverzoek"))
    }
}

/**
 * Rule-based categorizer. Rules are checked highest priority first; merchant matches beat
 * description matches. Patterns match whole words so "ns" does not match "transactie".
 */
class RuleCategorizer(rules: List<CategoryRule>) : Categorizer {
    private data class Compiled(val rule: CategoryRule, val regex: Regex)

    private val compiled: List<Compiled> = rules
        .filter { it.pattern.isNotBlank() }
        .sortedWith(compareByDescending<CategoryRule> { it.priority }.thenByDescending { it.pattern.length })
        .map { Compiled(it, wordRegex(it.pattern)) }

    override fun categorize(merchant: String, description: String, amountCents: Long): String? {
        val m = merchant.lowercase()
        val normalized = MerchantNormalizer.normalize(merchant).lowercase()
        compiled.firstOrNull { it.regex.containsMatchIn(m) || it.regex.containsMatchIn(normalized) }?.let { return it.rule.categoryId }
        // Wallet names appear in card payment notes and say nothing about the merchant.
        val d = description.lowercase().replace("apple pay", " ").replace("google pay", " ")
        if (d.isBlank()) return null
        return compiled.firstOrNull { it.regex.containsMatchIn(d) }?.rule?.categoryId
    }

    companion object {
        /** Priority given to rules learned from the user's own corrections. */
        const val LEARNED_PRIORITY = 100

        fun wordRegex(pattern: String): Regex =
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(pattern.lowercase().trim()) + "(?![\\p{L}\\p{N}])")

        /** The rule to save when the user moves a transaction with this merchant to [categoryId]. */
        fun learnedRule(merchant: String, categoryId: String): CategoryRule =
            CategoryRule(MerchantNormalizer.normalize(merchant).lowercase(), categoryId, LEARNED_PRIORITY, learnedFromUser = true)

        fun withDefaults(extra: List<CategoryRule> = emptyList()) = RuleCategorizer(DefaultRules.all + extra)
    }
}
