package app.oneassist.ui

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * Formats cents in the Dutch style: `€ 1.234,56`, negative as `€ -1.234,56`.
 * Other currencies use their code instead of the euro sign, for example `USD 12,00`.
 */
fun formatMoney(cents: Long, currency: String = "EUR"): String {
    val symbol = if (currency.equals("EUR", ignoreCase = true)) "€" else currency.uppercase(Locale.ROOT)
    val negative = cents < 0
    // absoluteValue of Long.MIN_VALUE overflows; clamp so we never print garbage.
    val abs = if (cents == Long.MIN_VALUE) Long.MAX_VALUE else cents.absoluteValue
    val euros = abs / 100
    val rest = abs % 100
    val grouped = euros.toString().reversed().chunked(3).joinToString(".").reversed()
    val sign = if (negative) "-" else ""
    return "$symbol $sign$grouped,${rest.toString().padStart(2, '0')}"
}

private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val dayHeaderFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private val shortDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

fun formatMonth(month: YearMonth): String = month.format(monthFormatter)

fun formatShortDate(date: LocalDate): String = date.format(shortDateFormatter)

/** "Today", "Yesterday" or "Monday 3 November". */
fun formatDayHeader(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(dayHeaderFormatter)
}
