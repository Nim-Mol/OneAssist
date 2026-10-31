package app.oneassist.core

import kotlin.math.abs

/** Parsing and formatting of money amounts. All amounts are Long cents. */
object Amounts {

    /**
     * Parses an amount written in Dutch ("1.234,56", "4,50") or English ("1,234.56", "4.50") style,
     * with optional currency sign, spaces and a leading or trailing sign. Returns null when the text
     * holds no usable number.
     *
     * A single separator followed by exactly three digits is read as a thousands separator
     * ("1.200" = 1200 euro); followed by one or two digits it is the decimal separator.
     */
    fun parseCents(text: String): Long? {
        var s = text.trim()
            .replace('−', '-') // unicode minus
            .replace('–', '-') // en dash, seen in OCR
            .replace("EUR", "", ignoreCase = true)
            .replace("€", "")
            .replace(" ", "")
            .replace(" ", "")
            .replace(" ", "")
            .replace("'", "")
        if (s.isEmpty()) return null
        var negative = false
        while (s.isNotEmpty() && (s[0] == '-' || s[0] == '+')) {
            if (s[0] == '-') negative = true
            s = s.substring(1)
        }
        while (s.isNotEmpty() && (s.last() == '-' || s.last() == '+')) {
            if (s.last() == '-') negative = true
            s = s.dropLast(1)
        }
        s = s.trimEnd('.', ',')
        if (s.isEmpty() || !s.all { it.isDigit() || it == '.' || it == ',' }) return null
        if (!s[0].isDigit()) return null

        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val decimalIndex: Int = when {
            lastDot >= 0 && lastComma >= 0 -> maxOf(lastDot, lastComma)
            lastDot >= 0 || lastComma >= 0 -> {
                val idx = maxOf(lastDot, lastComma)
                val sep = s[idx]
                val digitsAfter = s.length - idx - 1
                val occurrences = s.count { it == sep }
                if (occurrences == 1 && digitsAfter in 1..2) idx else -1
            }
            else -> -1
        }
        val intPart: String
        val fracPart: String
        if (decimalIndex >= 0) {
            intPart = s.substring(0, decimalIndex).filter { it.isDigit() }
            fracPart = s.substring(decimalIndex + 1).filter { it.isDigit() }
            if (fracPart.length > 2 || fracPart.isEmpty()) return null
        } else {
            intPart = s.filter { it.isDigit() }
            fracPart = ""
        }
        if (intPart.isEmpty() || intPart.length > 13) return null
        val cents = intPart.toLong() * 100 + fracPart.padEnd(2, '0').toLong()
        return if (negative) -cents else cents
    }

    /** Dutch display format: "€ 1.234,56", negative "€ -1.234,56". */
    fun format(cents: Long, currency: String = "EUR"): String {
        val symbol = if (currency.equals("EUR", ignoreCase = true)) "€" else currency.uppercase()
        val a = if (cents == Long.MIN_VALUE) Long.MAX_VALUE else abs(cents)
        val grouped = (a / 100).toString().reversed().chunked(3).joinToString(".").reversed()
        val sign = if (cents < 0) "-" else ""
        return "$symbol $sign$grouped,${(a % 100).toString().padStart(2, '0')}"
    }
}
