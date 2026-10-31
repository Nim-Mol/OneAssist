package app.oneassist.core

import java.time.LocalDate

/** Where money lives. One entry per real account. */
enum class AccountSource { ING, REVOLUT, CREDIT_CARD }

/** How a transaction reached the app. Used for de-duplication and trust. */
enum class ImportSource { NOTIFICATION, CSV, SCREENSHOT, MANUAL }

enum class CategoryKind { INCOME, ESSENTIAL, DISCRETIONARY, SAVINGS, TRANSFER }

data class Category(
    val id: String,
    val name: String,
    val kind: CategoryKind,
)

/**
 * A single money movement. Amounts are cents: negative = money out, positive = money in.
 * [merchant] is the normalized counterparty name (see MerchantNormalizer).
 */
data class Transaction(
    val id: String,
    val account: AccountSource,
    val date: LocalDate,
    val amountCents: Long,
    val currency: String = "EUR",
    val merchant: String,
    val description: String,
    val categoryId: String? = null,
    val source: ImportSource,
)

/** A parsed transaction before it gets an id and category. */
data class RawTransaction(
    val account: AccountSource,
    val date: LocalDate,
    val amountCents: Long,
    val currency: String = "EUR",
    val counterparty: String,
    val description: String,
    val source: ImportSource,
)

/** Result of parsing a file or text: good rows plus lines that were skipped and why. */
data class ParseResult(
    val transactions: List<RawTransaction>,
    val skipped: List<String> = emptyList(),
)

/** "If the merchant or description contains [pattern], it belongs to [categoryId]." */
data class CategoryRule(
    val pattern: String,
    val categoryId: String,
    val priority: Int = 0,
    val learnedFromUser: Boolean = false,
)

enum class Cadence(val approxDays: Int) { WEEKLY(7), MONTHLY(30), QUARTERLY(91), YEARLY(365) }

data class Subscription(
    val merchant: String,
    val amountCents: Long,
    val cadence: Cadence,
    val lastCharged: LocalDate,
    val nextExpected: LocalDate,
    /** Normalized to a monthly cost so subscriptions can be compared and summed. */
    val monthlyCostCents: Long,
    val priceIncreasedFromCents: Long? = null,
)

enum class LeakType {
    SMALL_FREQUENT,      // many small purchases that add up (coffee, snacks, delivery)
    PRICE_INCREASE,      // a subscription got more expensive
    DUPLICATE_SERVICE,   // two services doing the same job (two streaming services, two cloud storages)
    FEES,                // bank fees, interest, late fees, foreign transaction fees
    CATEGORY_SPIKE,      // a category well above its own average
    FORGOTTEN_SUBSCRIPTION, // subscription with no related activity, or newly started trial
}

data class Leak(
    val type: LeakType,
    val title: String,
    val detail: String,
    val monthlyImpactCents: Long,
    val merchants: List<String> = emptyList(),
)

data class SavingsTip(
    val title: String,
    val detail: String,
    val estimatedMonthlySavingCents: Long,
)
