package app.oneassist.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/**
 * Best-effort parser for OCR lines of a bank app transaction list (ING, Revolut, credit card apps).
 *
 * It understands date headers ("Vandaag", "Gisteren", "Today", "28 oktober", "28 Oct", "Oct 28",
 * "maandag 28 oktober 2024", "28-10-2024"), amount lines ("-€ 12,99", "-12.99", "+ € 2.500,00",
 * "€ 4,50") and merchant lines. Merchant and amount may share a line or be on separate lines,
 * row by row or column by column. Amounts without a sign count as money out; "+" means money in.
 */
class BankListScreenshotParser : ScreenshotTextParser {

    private sealed interface Item
    private data class Name(val text: String) : Item
    private data class Amount(val cents: Long) : Item
    private data class Pair_(val name: String, val cents: Long) : Item

    override fun parse(ocrLines: List<String>, account: AccountSource, today: LocalDate): ParseResult {
        val out = mutableListOf<RawTransaction>()
        val skipped = mutableListOf<String>()
        var date = today
        var section = mutableListOf<Item>()

        fun flush() {
            pairUp(section, skipped).forEach { (name, cents) ->
                out += RawTransaction(
                    account = account, date = date, amountCents = cents, currency = "EUR",
                    counterparty = name, description = "", source = ImportSource.SCREENSHOT,
                )
            }
            section = mutableListOf()
        }

        for (rawLine in ocrLines) {
            val line = rawLine.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
            if (line.isEmpty()) continue
            val headerDate = parseDateHeader(line, today)
            if (headerDate != null) { flush(); date = headerDate; continue }
            if (isNoise(line)) continue

            val m = AMOUNT_ANYWHERE.find(line)
            if (m != null) {
                val cents = signedCents(m.value) ?: continue
                val rest = (line.substring(0, m.range.first) + " " + line.substring(m.range.last + 1)).trim().trim('-', '·', '|', ' ')
                if (rest.length >= 2 && rest.any(Char::isLetter) && !isNoise(rest)) section += Pair_(rest, cents)
                else section += Amount(cents)
            } else if (line.any(Char::isLetter)) {
                section += Name(line)
            }
        }
        flush()
        return ParseResult(out, skipped)
    }

    /** Groups runs of names and runs of amounts and pairs them (row layout or column layout). */
    private fun pairUp(items: List<Item>, skipped: MutableList<String>): List<kotlin.Pair<String, Long>> {
        val result = mutableListOf<kotlin.Pair<String, Long>>()
        var names = mutableListOf<String>()
        var amounts = mutableListOf<Long>()
        fun settle() {
            when {
                amounts.isEmpty() -> {}
                names.isEmpty() -> skipped += "${amounts.size} amount(s) without a merchant"
                amounts.size == 1 -> result += names.first() to amounts.first()
                names.size == amounts.size -> names.zip(amounts).forEach { result += it }
                names.size == amounts.size * 2 -> names.filterIndexed { i, _ -> i % 2 == 0 }.zip(amounts).forEach { result += it }
                else -> {
                    names.zip(amounts).forEach { result += it }
                    if (amounts.size > names.size) skipped += "${amounts.size - names.size} amount(s) without a merchant"
                }
            }
            names = mutableListOf(); amounts = mutableListOf()
        }
        for (item in items) {
            when (item) {
                is Pair_ -> { settle(); result += item.name to item.cents }
                is Name -> { if (amounts.isNotEmpty()) settle(); names += item.text }
                is Amount -> amounts += item.cents
            }
        }
        settle()
        return result
    }

    private fun signedCents(token: String): Long? {
        val t = token.trim()
        val cents = Amounts.parseCents(t) ?: return null
        if (cents == 0L) return null
        val positive = t.startsWith("+")
        return if (positive) kotlin.math.abs(cents) else -kotlin.math.abs(cents)
    }

    private fun isNoise(line: String): Boolean {
        val l = line.lowercase()
        if (TIME.matches(l)) return true
        if (l.length < 2) return true
        if (noise.any { l == it || l.startsWith("$it ") || l.startsWith("$it:") }) return true
        if (l.contains("saldo") || l.contains("balance") || l.contains("totaal") || l.contains("total")) return true
        // Status bar and app chrome.
        if (Regex("^\\d{1,2}[:.]\\d{2}\\b.*").matches(l) && l.length < 12) return true
        return false
    }

    internal fun parseDateHeader(line: String, today: LocalDate): LocalDate? {
        val l = line.lowercase().trim().trimEnd(':', '.')
        when (l) {
            "vandaag", "today" -> return today
            "gisteren", "yesterday" -> return today.minusDays(1)
            "eergisteren" -> return today.minusDays(2)
        }
        weekdays[l]?.let { dow ->
            var d = today.minusDays(1)
            while (d.dayOfWeek != dow) d = d.minusDays(1)
            return d
        }
        NUMERIC_DATE.matchEntire(l)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val year = m.groupValues[3].let { if (it.isEmpty()) null else if (it.length == 2) 2000 + it.toInt() else it.toInt() }
            return build(day, month, year, today)
        }
        // Optional weekday, then "28 oktober [2024]" or "oct 28[, 2024]".
        val cleaned = l.replace(",", " ").replace(Regex("\\s+"), " ").trim()
        val tokens = cleaned.split(' ').toMutableList()
        if (tokens.isNotEmpty() && tokens[0].trimEnd('.') in weekdays.keys + weekdayAbbr) tokens.removeAt(0)
        if (tokens.size !in 2..3) return null
        val year = tokens.getOrNull(2)?.takeIf { YEAR.matches(it) }?.toInt()
        if (tokens.size == 3 && year == null) return null
        val a = tokens[0].trimEnd('.')
        val b = tokens[1].trimEnd('.')
        val (day, month) = when {
            a.toIntOrNull() != null && months[b] != null -> a.toInt() to months.getValue(b)
            b.toIntOrNull() != null && months[a] != null -> b.toInt() to months.getValue(a)
            else -> return null
        }
        return build(day, month, year, today)
    }

    private fun build(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        return try {
            if (year != null) LocalDate.of(year, month, day)
            else {
                val md = MonthDay.of(month, day)
                val thisYear = if (md.isValidYear(today.year)) md.atYear(today.year) else null
                if (thisYear != null && !thisYear.isAfter(today)) thisYear else md.atYear(today.year - 1)
            }
        } catch (_: Exception) { null }
    }

    companion object {
        private val AMOUNT_ANYWHERE = Regex(
            "[-−+–]?\\s?(?:€|EUR)?\\s?[-−+–]?\\s?(?:\\d{1,3}(?:[.,]\\d{3})+|\\d+)[.,]\\d{2}(?!\\d)(?:\\s?€)?",
        )
        private val TIME = Regex("^\\d{1,2}:\\d{2}( ?(am|pm))?$")
        private val NUMERIC_DATE = Regex("^(?:\\p{L}+\\s+)?(\\d{1,2})[-/.](\\d{1,2})(?:[-/.](\\d{2}|\\d{4}))?$")
        private val YEAR = Regex("^\\d{4}$")

        private val months: Map<String, Int> = buildMap {
            listOf(
                listOf("januari", "january", "jan"), listOf("februari", "february", "feb"),
                listOf("maart", "march", "mrt", "mar"), listOf("april", "apr"), listOf("mei", "may"),
                listOf("juni", "june", "jun"), listOf("juli", "july", "jul"), listOf("augustus", "august", "aug"),
                listOf("september", "sep", "sept"), listOf("oktober", "october", "okt", "oct"),
                listOf("november", "nov"), listOf("december", "dec"),
            ).forEachIndexed { i, names -> names.forEach { put(it, i + 1) } }
        }
        private val weekdays: Map<String, DayOfWeek> = mapOf(
            "maandag" to DayOfWeek.MONDAY, "dinsdag" to DayOfWeek.TUESDAY, "woensdag" to DayOfWeek.WEDNESDAY,
            "donderdag" to DayOfWeek.THURSDAY, "vrijdag" to DayOfWeek.FRIDAY, "zaterdag" to DayOfWeek.SATURDAY,
            "zondag" to DayOfWeek.SUNDAY, "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY,
            "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
        )
        private val weekdayAbbr = setOf("ma", "di", "wo", "do", "vr", "za", "zo", "mon", "tue", "wed", "thu", "fri", "sat", "sun")
        private val noise = listOf(
            "betaalautomaat", "incasso", "ideal", "online bankieren", "overschrijving", "card payment", "kaartbetaling",
            "pending", "in behandeling", "geldautomaat", "apple pay", "google pay", "contactless",
            "contactloos", "betaalpas", "debit card", "credit card", "creditcard", "zoeken", "search", "transactions",
            "transacties", "af- en bijschrijvingen", "home", "betalen", "payments", "cards", "kaarten",
            "bekijk", "show more", "filter", "uitgaven", "spending", "inkomsten", "income",
        )
    }
}
