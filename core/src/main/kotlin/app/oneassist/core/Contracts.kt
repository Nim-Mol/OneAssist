package app.oneassist.core

import java.time.LocalDate

/** Parses a CSV export from one bank. */
interface CsvParser {
    val account: AccountSource
    /** True if the header row looks like this bank's export. */
    fun canParse(headerLine: String): Boolean
    fun parse(csv: String): ParseResult
}

/** Parses one push notification from a bank app into a transaction, or null if it is not a payment. */
interface NotificationParser {
    /** Android package names this parser accepts, for example "com.revolut.revolut". */
    val packageNames: Set<String>
    fun parse(title: String, text: String, postedOn: LocalDate): RawTransaction?
}

/** Parses OCR text from a screenshot of a bank app's transaction list. */
interface ScreenshotTextParser {
    fun parse(ocrLines: List<String>, account: AccountSource, today: LocalDate): ParseResult
}

interface Categorizer {
    /** Returns the category id for a transaction, or null if no rule matches. */
    fun categorize(merchant: String, description: String, amountCents: Long): String?
}

interface SubscriptionDetector {
    fun detect(transactions: List<Transaction>, today: LocalDate): List<Subscription>
}

interface LeakDetector {
    fun detect(transactions: List<Transaction>, subscriptions: List<Subscription>, categories: List<Category>, today: LocalDate): List<Leak>
}

interface SavingsAdvisor {
    fun tips(transactions: List<Transaction>, subscriptions: List<Subscription>, leaks: List<Leak>, categories: List<Category>, today: LocalDate): List<SavingsTip>
}

/**
 * Builds the text sent to the AI model. It must contain aggregates only:
 * no IBANs, account numbers, people's names or raw descriptions.
 */
interface AssistantPromptBuilder {
    fun systemPrompt(): String
    fun financialContext(transactions: List<Transaction>, subscriptions: List<Subscription>, leaks: List<Leak>, categories: List<Category>, today: LocalDate): String
}
