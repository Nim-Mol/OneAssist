package app.oneassist.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Revolut account statement CSV:
 * Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance
 * Only COMPLETED rows are imported. A non-zero fee becomes its own negative "Revolut fee" row.
 */
class RevolutCsvParser : CsvParser {
    override val account = AccountSource.REVOLUT

    override fun canParse(headerLine: String): Boolean {
        val h = headerLine.lowercase()
        return "started date" in h && "description" in h && "amount" in h && "state" in h
    }

    override fun parse(csv: String): ParseResult {
        val header = Csv.firstLine(csv)
        if (!canParse(header)) return ParseResult(emptyList(), listOf("Not a Revolut export"))
        val records = Csv.read(csv, Csv.detectDelimiter(header))
        if (records.isEmpty()) return ParseResult(emptyList())
        val cols = records.first().map(Csv::normalizeHeader)
        fun col(name: String) = cols.indexOf(name)
        val iType = col("type")
        val iProduct = col("product")
        val iStarted = col("started date")
        val iCompleted = col("completed date")
        val iDesc = col("description")
        val iAmount = col("amount")
        val iFee = col("fee")
        val iCurrency = col("currency")
        val iState = col("state")

        val out = mutableListOf<RawTransaction>()
        val skipped = mutableListOf<String>()
        records.drop(1).forEachIndexed { index, r ->
            val line = index + 2
            fun f(i: Int) = if (i in r.indices) r[i].trim() else ""
            try {
                val state = f(iState).uppercase()
                if (iState >= 0 && state != "COMPLETED") {
                    skipped += "Line $line: state ${state.ifBlank { "empty" }}"
                    return@forEachIndexed
                }
                val date = parseDate(f(iStarted)) ?: parseDate(f(iCompleted))
                    ?: run { skipped += "Line $line: unreadable date"; return@forEachIndexed }
                val amount = parseRevolutAmount(f(iAmount))
                    ?: run { skipped += "Line $line: unreadable amount"; return@forEachIndexed }
                val fee = if (iFee >= 0) parseRevolutAmount(f(iFee)) ?: 0L else 0L
                val currency = f(iCurrency).uppercase().ifBlank { "EUR" }
                val type = f(iType).uppercase()
                val product = f(iProduct)
                val desc = f(iDesc)
                val p2p = type in setOf("TRANSFER", "TOPUP", "EXCHANGE") ||
                    product.equals("savings", ignoreCase = true) ||
                    desc.lowercase().let { it.startsWith("to ") || it.startsWith("from ") || it.startsWith("payment from ") || "pocket" in it }
                if (amount != 0L) {
                    out += RawTransaction(
                        account = account,
                        date = date,
                        amountCents = amount,
                        currency = currency,
                        counterparty = desc.ifBlank { type.lowercase() },
                        description = listOf(type, product).filter { it.isNotBlank() }.joinToString(" | "),
                        source = ImportSource.CSV,
                        peerToPeer = p2p,
                    )
                }
                if (fee != 0L) {
                    out += RawTransaction(
                        account = account,
                        date = date,
                        amountCents = -kotlin.math.abs(fee),
                        currency = currency,
                        counterparty = "Revolut fee",
                        description = "FEE | $type",
                        source = ImportSource.CSV,
                    )
                }
            } catch (e: Exception) {
                skipped += "Line $line: ${e.javaClass.simpleName}"
            }
        }
        return ParseResult(out, skipped)
    }

    /** Revolut writes plain numbers with a dot ("-12.5", "1200.00"), never thousands separators. */
    private fun parseRevolutAmount(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return t.toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.toLong()
            ?: Amounts.parseCents(t)
    }

    private fun parseDate(s: String): LocalDate? {
        val t = s.trim()
        if (t.isEmpty()) return null
        for (p in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd/MM/yyyy HH:mm", "dd-MM-yyyy HH:mm:ss")) {
            try { return LocalDateTime.parse(t, DateTimeFormatter.ofPattern(p)).toLocalDate() } catch (_: Exception) {}
        }
        return try { LocalDate.parse(t.take(10)) } catch (_: Exception) { null }
    }
}
