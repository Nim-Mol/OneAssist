package app.oneassist.core

/** Minimal RFC 4180 style reader: quoted fields, doubled quotes, newlines inside quotes. */
internal object Csv {

    /** Picks ';' or ',' by counting them (outside quotes) in the first line. */
    fun detectDelimiter(headerLine: String): Char {
        var semis = 0
        var commas = 0
        var tabs = 0
        var inQuotes = false
        for (c in headerLine) {
            when {
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ';' -> semis++
                !inQuotes && c == ',' -> commas++
                !inQuotes && c == '\t' -> tabs++
            }
        }
        return when {
            semis >= commas && semis >= tabs && semis > 0 -> ';'
            tabs > commas -> '\t'
            else -> ','
        }
    }

    fun firstLine(text: String): String = stripBom(text).lineSequence().firstOrNull { it.isNotBlank() } ?: ""

    fun stripBom(text: String): String = text.removePrefix("﻿")

    /** Returns records (each a list of fields). Blank records are dropped. */
    fun read(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val field = StringBuilder()
        var record = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        val s = stripBom(text)
        fun endRecord() {
            record.add(field.toString()); field.setLength(0)
            if (record.any { it.isNotBlank() }) records.add(record)
            record = mutableListOf()
        }
        while (i < s.length) {
            val c = s[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    delimiter -> { record.add(field.toString()); field.setLength(0) }
                    '\r' -> {}
                    '\n' -> endRecord()
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return records
    }

    fun normalizeHeader(h: String): String = h.trim().trim('"').lowercase().replace(Regex("\\s+"), " ")
}

/** Picks the right CSV parser by looking at the header. */
class CsvImporter(private val parsers: List<CsvParser> = listOf(IngCsvParser(), RevolutCsvParser())) {
    fun parserFor(csv: String): CsvParser? {
        val header = Csv.firstLine(csv)
        return parsers.firstOrNull { it.canParse(header) }
    }

    fun parse(csv: String): ParseResult =
        parserFor(csv)?.parse(csv)
            ?: ParseResult(emptyList(), listOf("Unknown CSV format: the header is not an ING or Revolut export"))
}
