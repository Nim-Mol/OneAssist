package app.oneassist.core

/**
 * Turns raw counterparty text from banks, notifications and OCR into one clean display name, so
 * "CCV*ALBERT HEIJN 1234 AMSTERDAM NLD", "Albert Heijn 1234" and "ALBERT HEIJN" all become
 * "Albert Heijn". [key] gives a lowercase comparison key for grouping and de-duplication.
 */
object MerchantNormalizer {

    /** Payment terminal / processor prefixes, matched case-insensitively at the start. */
    private val processorPrefixes = listOf(
        Regex("^(ccv|bck|sumup|sum up|zettle_?|izettle|iz|sq|paypal|pp|mollie|pay\\.nl|adyen|buckaroo|stripe|mypos|tikkie|wl|worldline)\\s*[*_]\\s*", RegexOption.IGNORE_CASE),
        Regex("^(ccv|sumup|zettle|izettle)\\s+", RegexOption.IGNORE_CASE),
        Regex("^(bea|gea|ba),?\\s+(betaalpas\\s+)?", RegexOption.IGNORE_CASE),
        Regex("^betaalpas\\s+", RegexOption.IGNORE_CASE),
    )

    private val legalSuffixes = setOf("b.v.", "bv", "b.v", "n.v.", "nv", "n.v", "v.o.f.", "vof", "ltd", "ltd.", "limited", "gmbh", "inc", "inc.", "llc", "s.a.", "sa", "sarl", "ab", "uab", "plc", "holding", "nederland", "netherlands", "europe", "emea", "international", "intl")
    private val countryTokens = setOf("nld", "nl", "ned", "nederland", "the netherlands", "irl", "ie", "gbr", "gb", "lu", "lux", "deu", "de", "bel", "be")

    /** Common Dutch city names that terminals append. */
    private val cities = setOf(
        "amsterdam", "rotterdam", "den haag", "'s-gravenhage", "s-gravenhage", "utrecht", "eindhoven", "groningen",
        "tilburg", "almere", "breda", "nijmegen", "haarlem", "arnhem", "zaandam", "amersfoort", "apeldoorn",
        "hoofddorp", "leiden", "delft", "schiphol", "maastricht", "zwolle", "den bosch", "'s-hertogenbosch",
        "s-hertogenbosch", "enschede", "amstelveen", "diemen", "zoetermeer", "dordrecht", "leeuwarden", "alkmaar",
        "hilversum", "deventer", "purmerend", "hengelo", "schiedam", "lelystad", "gouda", "rijswijk", "ede",
        "venlo", "heerlen", "helmond", "oss", "roosendaal", "vlaardingen", "capelle a/d ijssel", "nieuwegein",
        "veenendaal", "zeist", "katwijk", "assen", "emmen", "dublin", "london", "luxembourg", "amsterdam zuid",
        "amsterdam-zuid", "utrecht cs", "badhoevedorp", "duivendrecht", "houten", "wageningen", "voorburg",
    )

    /** Known brands: if the cleaned key starts with the alias, the display name becomes the brand. */
    private val aliases: List<Pair<String, String>> = listOf(
        "albert heijn" to "Albert Heijn", "ah to go" to "AH to go", "ah " to "Albert Heijn", "ah" to "Albert Heijn",
        "jumbo" to "Jumbo", "lidl" to "Lidl", "aldi" to "Aldi", "plus " to "Plus", "dirk" to "Dirk",
        "ns groep" to "NS", "ns reizigers" to "NS", "ns international" to "NS International", "ns " to "NS",
        "uber eats" to "Uber Eats", "ubereats" to "Uber Eats", "thuisbezorgd" to "Thuisbezorgd", "takeaway" to "Thuisbezorgd",
        "deliveroo" to "Deliveroo", "starbucks" to "Starbucks", "netflix" to "Netflix", "spotify" to "Spotify",
        "disney" to "Disney+", "videoland" to "Videoland", "youtube" to "YouTube", "google youtube" to "YouTube",
        "apple com bill" to "Apple", "apple com" to "Apple", "itunes" to "Apple", "google one" to "Google One",
        "google play" to "Google Play", "vattenfall" to "Vattenfall", "eneco" to "Eneco", "essent" to "Essent",
        "ziggo" to "Ziggo", "kpn" to "KPN", "odido" to "Odido", "t mobile" to "Odido", "vodafone" to "Vodafone",
        "zilveren kruis" to "Zilveren Kruis", "menzis" to "Menzis", "bol com" to "bol.com", "coolblue" to "Coolblue",
        "amazon prime" to "Amazon Prime", "amzn" to "Amazon", "amazon" to "Amazon", "hema" to "HEMA", "ikea" to "IKEA",
        "mcdonald" to "McDonald's", "kruidvat" to "Kruidvat", "etos" to "Etos", "basic fit" to "Basic-Fit",
        "shell" to "Shell", "tango" to "Tango", "gvb" to "GVB", "ret " to "RET", "ret" to "RET",
        "openai" to "OpenAI", "chatgpt" to "OpenAI", "hbo max" to "HBO Max", "icloud" to "Apple",
    )

    private val acronyms = setOf("ns", "kpn", "gvb", "ret", "htm", "bp", "cz", "vgz", "dsw", "ah", "hbo", "kfc", "ing", "abn", "amro", "sns", "asn", "uwv", "svb", "cjib", "hema", "ikea", "anwb", "dhl", "ups", "tnt", "nlziet", "ov", "bk", "h&m", "c&a", "dm", "atm")

    private val keepDomains = setOf("bol.com")

    fun normalize(raw: String): String {
        var s = raw.replace(' ', ' ').replace(Regex("\\s+"), " ").trim().trim('"', '\'', ',', '-', ' ')
        if (s.isEmpty()) return ""
        val original = s

        // Processor prefixes, possibly stacked ("SumUp *CCV*Bakker").
        repeat(3) { processorPrefixes.forEach { r -> s = s.replace(r, "") } }
        s = s.trim()
        // "Google *YouTube" / "UBER *EATS": the part after '*' is the interesting one for platforms.
        Regex("^(google|apple|microsoft|amazon|amzn mktp|paypal)\\s*\\*\\s*(.+)$", RegexOption.IGNORE_CASE).find(s)?.let {
            val rest = it.groupValues[2]
            s = if (it.groupValues[1].equals("google", true) && !rest.lowercase().startsWith("youtube")) "Google ${rest}" else rest
        }
        s = s.replace('*', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        // Anything after a comma or " - " is usually location or reference noise (ING card payments).
        s = s.split(Regex(",|\\s+-\\s+|\\s+/\\s+")).first().trim()

        val tokens = s.split(' ').filter { it.isNotBlank() }.toMutableList()
        // Store numbers, reference codes and "#12" style tokens (never the first token).
        for (i in tokens.indices.reversed()) {
            if (i == 0) continue
            val t = tokens[i]
            val isNumber = t.trim('#', '.', ':').let { it.isNotEmpty() && it.all(Char::isDigit) }
            val isRef = t.length >= 6 && t.count(Char::isDigit) >= 3 && t.any(Char::isLetter)
            val isStoreNo = Regex("^(nr\\.?|no\\.?|filiaal|store|#)\\d*$", RegexOption.IGNORE_CASE).matches(t)
            if (isNumber || isRef || isStoreNo) tokens.removeAt(i)
        }
        // Trailing country, legal form and city tokens, repeatedly.
        var changed = true
        while (changed && tokens.size > 1) {
            changed = false
            val last = tokens.last().lowercase()
            val lastTwo = if (tokens.size > 2) (tokens[tokens.size - 2] + " " + tokens.last()).lowercase() else null
            when {
                lastTwo != null && (lastTwo in cities || lastTwo in countryTokens) -> { tokens.removeAt(tokens.size - 1); tokens.removeAt(tokens.size - 1); changed = true }
                last in countryTokens || last in legalSuffixes || last in cities -> { tokens.removeAt(tokens.size - 1); changed = true }
                last.all(Char::isDigit) -> { tokens.removeAt(tokens.size - 1); changed = true }
            }
        }
        s = tokens.joinToString(" ").trim().trim('.', ',', '-', ' ')
        if (s.isEmpty()) s = original

        // Domain suffix: "NETFLIX.COM" -> "NETFLIX" (but keep brands that are their domain).
        if (s.lowercase() !in keepDomains) {
            s = s.replace(Regex("\\.(com|nl|eu|co\\.uk|io|net|org)$", RegexOption.IGNORE_CASE), "")
        }

        val key = keyOf(s)
        aliases.firstOrNull { (prefix, _) -> key == prefix.trimEnd() || (key.startsWith(prefix) && aliasBoundary(key, prefix)) }
            ?.let { return it.second }

        return prettyCase(s)
    }

    private fun aliasBoundary(key: String, prefix: String): Boolean {
        if (prefix.endsWith(" ")) return true
        if (key.length == prefix.length) return true
        // "ret" must not match "retail"; multi-word or long brand prefixes may be followed by anything.
        return prefix.length >= 5 || !key[prefix.length].isLetterOrDigit()
    }

    /** Lowercase key for grouping: normalized name without punctuation. */
    fun key(raw: String): String = keyOf(normalize(raw))

    private fun keyOf(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}&+ ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** True when two raw names refer to the same merchant, tolerant of extra words on one side. */
    fun sameMerchant(a: String, b: String): Boolean {
        val ka = key(a)
        val kb = key(b)
        if (ka.isEmpty() || kb.isEmpty()) return false
        if (ka == kb) return true
        val (short, long) = if (ka.length <= kb.length) ka to kb else kb to ka
        if (short.length >= 3 && (long.startsWith("$short ") || long.endsWith(" $short") || long.contains(" $short "))) return true
        val fa = ka.substringBefore(' ')
        val fb = kb.substringBefore(' ')
        return fa.length >= 4 && fa == fb
    }

    private fun prettyCase(s: String): String {
        val letters = s.filter(Char::isLetter)
        val shouty = letters.isNotEmpty() && letters.all(Char::isUpperCase)
        val quiet = letters.isNotEmpty() && letters.all(Char::isLowerCase)
        if (!shouty && !quiet) return s
        return s.split(' ').joinToString(" ") { word ->
            val lw = word.lowercase()
            when {
                lw in acronyms -> lw.uppercase()
                lw.contains('.') && lw.length > 4 -> lw // domains like bol.com
                else -> lw.split('-').joinToString("-") { part -> part.replaceFirstChar { c -> c.titlecase() } }
            }
        }
    }
}
