package app.oneassist.core

import java.time.LocalDate

/**
 * Shared logic for bank push notifications in Dutch and English. Recognises texts such as
 * "Paid €4.50 at Albert Heijn", "You paid €12.99 to Netflix", "€4,50 betaald bij Albert Heijn",
 * "Je hebt €25,00 betaald aan Jumbo", "Bijschrijving van €1.200,00 van Werkgever BV" and
 * "Afschrijving €12,99 Netflix". Returns null for anything that is not a completed payment
 * (login alerts, promotions, declined payments, balance updates).
 */
abstract class BaseBankNotificationParser : NotificationParser {
    protected abstract val account: AccountSource
    /** Titles that are just the bank's name and therefore not a merchant. */
    protected abstract val bankTitles: Set<String>

    override fun parse(title: String, text: String, postedOn: LocalDate): RawTransaction? {
        val body = text.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
        val head = title.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
        val all = "$head $body".lowercase()
        if (rejectWords.any { it.containsMatchIn(all) }) return null

        // Prefer the body; fall back to the title (some apps put "€4,50 bij Jumbo" in the title).
        val (source, match) = AMOUNT.find(body)?.let { body to it }
            ?: AMOUNT.find(head)?.let { head to it }
            ?: return null
        val cents = Amounts.parseCents(match.value)?.let { kotlin.math.abs(it) } ?: return null
        if (cents == 0L) return null

        val lower = all
        val before = source.lowercase().substring(0, match.range.first)
        val explicitMinus = match.value.trim().startsWith("-") || match.value.trim().startsWith("−")
        val direction = when {
            incomingWords.any { it.containsMatchIn(lower) } && !explicitMinus -> 1
            outgoingWords.any { it.containsMatchIn(lower) } || explicitMinus -> -1
            // Amount only plus a merchant title ("Albert Heijn" / "€ 4,50"): treat as a card payment.
            head.isNotBlank() && head.lowercase() !in bankTitles && before.isBlank() && source == body &&
                body.substring(match.range.last + 1).isBlank() -> -1
            else -> return null
        }

        val after = source.substring(match.range.last + 1)
        var merchant = merchantAfter(after)
            ?: merchantBefore(source.substring(0, match.range.first))
            ?: head.takeIf { it.isNotBlank() && it.lowercase() !in bankTitles && AMOUNT.find(it) == null }
            ?: return null
        merchant = cleanMerchant(merchant)
        if (merchant.isBlank()) return null

        val isTransfer = transferWords.any { it.containsMatchIn(lower) } ||
            (direction > 0 && refundWords.none { it.containsMatchIn(lower) })
        return RawTransaction(
            account = account,
            date = postedOn,
            amountCents = direction * cents,
            currency = if (match.value.contains("$")) "USD" else "EUR",
            counterparty = merchant,
            description = "", // raw notification text is never stored
            source = ImportSource.NOTIFICATION,
            peerToPeer = isTransfer,
        )
    }

    private fun merchantAfter(after: String): String? {
        val m = PREPOSITION.find(after)
        if (m != null) return m.groupValues[2].trim().takeIf { it.isNotBlank() }
        // "Afschrijving €12,99 Netflix": whatever follows directly, minus trailing words.
        val rest = after.trim().trimStart(':', '-', ' ')
        if (rest.isBlank()) return null
        val firstWord = rest.substringBefore(' ').lowercase()
        if (firstWord in fillerWords) return null
        return rest
    }

    private fun merchantBefore(before: String): String? {
        // "Albert Heijn: €4,50" / "Netflix - € 12,99"
        val m = Regex("^(.+?)\\s*[:\\-–]\\s*$").find(before.trim() + " ") ?: return null
        val cand = m.groupValues[1].trim()
        val lower = cand.lowercase()
        if (lower in bankTitles || (outgoingWords + incomingWords).any { it.containsMatchIn(lower) }) return null
        return cand
    }

    private fun cleanMerchant(raw: String): String {
        var s = raw
        // Cut at sentence end or at trailing clauses.
        s = s.split(Regex("(?<!\\b[a-zA-Z])\\.(\\s|$)|[!\\n]|\\s+(with|met|using|via|on|op|from your|van je|from|van|naar)\\s+|\\s+(at|om)\\s+\\d|\\s+[-–(]\\s*|,\\s+"), limit = 2)[0]
        s = s.trim().trimEnd('.', ',', '!', ':', ' ')
        return s.take(80)
    }

    companion object {
        /** € or EUR before or after a number, either decimal separator, optional sign. */
        val AMOUNT = Regex(
            "[-−+]?\\s?(?:€|EUR|\\$)\\s?[-−]?(?:\\d{1,3}(?:[.,]\\d{3})+|\\d+)(?:[.,]\\d{1,2})?(?!\\d)" +
                "|[-−+]?(?:\\d{1,3}(?:[.,]\\d{3})+|\\d+)(?:[.,]\\d{1,2})?\\s?(?:€|EUR)(?![A-Za-z])",
            RegexOption.IGNORE_CASE,
        )
        /** Up to two verbs, then a preposition, then the counterparty: " betaald bij Albert Heijn". */
        private val PREPOSITION = Regex("^\\s*(?:\\p{L}+\\s+){0,2}?(at|to|bij|aan|door|voor|from|van|naar)\\s+(.+)$", RegexOption.IGNORE_CASE)

        private fun words(vararg w: String) = w.map { Regex("\\b$it", RegexOption.IGNORE_CASE) }

        private val rejectWords = words(
            "log(ged)? ?in", "inlog", "ingelogd", "new device", "nieuw apparaat", "verification", "verificatie",
            "beveiligingscode", "security code", "one-time", "declined", "geweigerd", "afgewezen", "failed", "mislukt",
            "insufficient", "onvoldoende", "not completed", "niet gelukt", "cancelled", "geannuleerd",
            "invite", "nodig .* uit", "earn", "verdien", "cashback", "offer", "aanbieding", "promo", "win ", "upgrade",
            "your balance", "je saldo", "uw saldo", "saldo is", "balance is", "limit", "limiet", "reminder", "herinnering",
            "request(ed)? (€|eur)", "verzoek", "betaalverzoek", "tikkie", "would like", "wil graag", "scheduled", "gepland",
        )
        private val outgoingWords = words(
            "paid", "spent", "payment", "purchase", "sent", "you sent", "betaald", "betaling", "uitgegeven",
            "afschrijving", "afgeschreven", "overgemaakt", "verstuurd", "gestuurd", "card used", "withdr", "opgenomen",
        )
        private val incomingWords = words(
            "received", "ontvangen", "bijschrijving", "bijgeschreven", "refund", "terugbetaling", "teruggestort",
            "terugbetaald", "added to", "top-?up", "gestort", "salary", "salaris", "credited",
        )
        private val refundWords = words("refund", "terugbetaling", "teruggestort", "terugbetaald")
        private val transferWords = words("sent", "overgemaakt", "verstuurd", "gestuurd", "transfer", "overboeking", "top-?up", "pocket", "spaarrekening")
        private val fillerWords = setOf("is", "has", "have", "was", "werd", "wordt", "betaald", "paid", "afgeschreven", "bijgeschreven", "ontvangen", "received")
    }
}

/** ING Bankieren app (NL). */
class IngNotificationParser : BaseBankNotificationParser() {
    override val packageNames = setOf("com.ing.mobile")
    override val account = AccountSource.ING
    override val bankTitles = setOf("ing", "ing bankieren", "ing bank", "ing nederland", "betaling", "payment", "bijschrijving", "afschrijving")
}

/** Revolut app. */
class RevolutNotificationParser : BaseBankNotificationParser() {
    override val packageNames = setOf("com.revolut.revolut")
    override val account = AccountSource.REVOLUT
    override val bankTitles = setOf("revolut", "payment", "betaling", "card payment", "kaartbetaling")
}

/** All notification parsers, looked up by package name. */
object BankNotificationParsers {
    val all: List<NotificationParser> = listOf(IngNotificationParser(), RevolutNotificationParser())
    val packageNames: Set<String> = all.flatMap { it.packageNames }.toSet()
    fun forPackage(packageName: String): NotificationParser? = all.firstOrNull { packageName in it.packageNames }
}
