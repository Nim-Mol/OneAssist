package app.oneassist.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ING NL "Af- en bijschrijvingen" export. Handles the classic comma separated file, the newer
 * semicolon separated file with "Saldo na mutatie" and "Tag", and the English variant
 * ("Name / Description", "Debit/credit", "Amount (EUR)"). Columns are found by header name.
 */
class IngCsvParser : CsvParser {
    override val account = AccountSource.ING

    override fun canParse(headerLine: String): Boolean {
        val h = headerLine.lowercase()
        return ("naam / omschrijving" in h || "name / description" in h) &&
            ("af bij" in h || "debit/credit" in h)
    }

    override fun parse(csv: String): ParseResult {
        val header = Csv.firstLine(csv)
        if (!canParse(header)) return ParseResult(emptyList(), listOf("Not an ING export"))
        val records = Csv.read(csv, Csv.detectDelimiter(header))
        if (records.isEmpty()) return ParseResult(emptyList())
        val cols = records.first().map(Csv::normalizeHeader)
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> cols.indexOf(n).takeIf { it >= 0 } } ?: -1

        val iDate = col("datum", "date")
        val iName = col("naam / omschrijving", "name / description")
        val iCounter = col("tegenrekening", "counterparty")
        val iCode = col("code")
        val iSign = col("af bij", "debit/credit")
        val iAmount = col("bedrag (eur)", "amount (eur)", "bedrag", "amount")
        val iType = col("mutatiesoort", "transaction type")
        val iNotes = col("mededelingen", "notifications")
        if (iDate < 0 || iName < 0 || iSign < 0 || iAmount < 0) {
            return ParseResult(emptyList(), listOf("ING header is missing date, name, debit/credit or amount column"))
        }

        val out = mutableListOf<RawTransaction>()
        val skipped = mutableListOf<String>()
        records.drop(1).forEachIndexed { index, r ->
            val line = index + 2
            fun f(i: Int) = if (i in r.indices) r[i].trim() else ""
            try {
                val date = parseDate(f(iDate)) ?: run { skipped += "Line $line: unreadable date"; return@forEachIndexed }
                val cents = Amounts.parseCents(f(iAmount))?.let { kotlin.math.abs(it) }
                    ?: run { skipped += "Line $line: unreadable amount"; return@forEachIndexed }
                val sign = f(iSign).lowercase()
                val signed = when {
                    sign.startsWith("af") || sign.startsWith("debit") -> -cents
                    sign.startsWith("bij") || sign.startsWith("credit") -> cents
                    else -> { skipped += "Line $line: unknown Af/Bij value"; return@forEachIndexed }
                }
                val code = f(iCode).uppercase()
                val type = f(iType)
                val name = f(iName)
                val notes = f(iNotes)
                // Bank transfers (OV/GT/ST) usually go to people or own accounts; card, direct debit,
                // iDEAL and cash withdrawals do not.
                val p2p = code in setOf("OV", "GT", "ST") ||
                    type.lowercase() in setOf("overschrijving", "online bankieren", "transfer", "online banking")
                out += RawTransaction(
                    account = account,
                    date = date,
                    amountCents = signed,
                    currency = "EUR",
                    counterparty = name.ifBlank { f(iCounter).ifBlank { type } },
                    description = listOf(type, notes).filter { it.isNotBlank() }.joinToString(" | "),
                    source = ImportSource.CSV,
                    peerToPeer = p2p,
                )
            } catch (e: Exception) {
                skipped += "Line $line: ${e.javaClass.simpleName}"
            }
        }
        return ParseResult(out, skipped)
    }

    companion object {
        private val formats = listOf("yyyyMMdd", "dd-MM-yyyy", "yyyy-MM-dd", "dd/MM/yyyy", "d-M-yyyy")
            .map { DateTimeFormatter.ofPattern(it) }

        internal fun parseDate(s: String): LocalDate? {
            val t = s.trim()
            for (f in formats) {
                try { return LocalDate.parse(t, f) } catch (_: Exception) {}
            }
            return null
        }
    }
}
