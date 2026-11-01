package app.oneassist.core

/**
 * Free-form user tags such as "holiday-2026", "work" or "gift".
 *
 * Normalized form: trimmed, lowercase, whitespace and unusual characters become "-", at most
 * [MAX_LENGTH] characters. A transaction has at most [MAX_PER_TRANSACTION] tags. The restricted
 * character set keeps tags safe to store in one column and to show to the assistant.
 */
object Tags {
    const val MAX_LENGTH = 30
    const val MAX_PER_TRANSACTION = 10
    private const val SEPARATOR = ','
    private val allowed = Regex("[^\\p{L}\\p{N}&._+-]+")

    /** Normalized tag, or null when nothing usable is left. */
    fun normalize(tag: String): String? {
        val t = tag.trim().lowercase()
            .replace(allowed, "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .take(MAX_LENGTH)
            .trimEnd('-')
        return t.ifEmpty { null }
    }

    /** Normalizes, removes duplicates and keeps the first [MAX_PER_TRANSACTION] tags, in order. */
    fun normalizeAll(tags: Collection<String>): Set<String> =
        tags.mapNotNull(::normalize).distinct().take(MAX_PER_TRANSACTION).toCollection(LinkedHashSet())

    /**
     * Storage form: ",a,b," (sorted), or "" when empty. The leading and trailing separator lets a
     * query match one whole tag with instr(tags, ',' || tag || ',').
     */
    fun encode(tags: Set<String>): String {
        val n = normalizeAll(tags)
        return if (n.isEmpty()) "" else n.sorted().joinToString(SEPARATOR.toString(), prefix = ",", postfix = ",")
    }

    fun decode(stored: String): Set<String> =
        normalizeAll(stored.split(SEPARATOR).filter { it.isNotBlank() })
}

/** Money per tag in one period. Spending and income are positive. */
data class TagTotal(val tag: String, val spendingCents: Long, val incomeCents: Long, val count: Int)
