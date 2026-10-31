package app.oneassist.core

import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

/**
 * Concrete tips based on the user's own numbers. Every estimate is derived from their spending,
 * with the assumption stated in the detail text.
 */
class DefaultSavingsAdvisor : SavingsAdvisor {

    override fun tips(
        transactions: List<Transaction>,
        subscriptions: List<Subscription>,
        leaks: List<Leak>,
        categories: List<Category>,
        today: LocalDate,
    ): List<SavingsTip> {
        val tips = mutableListOf<SavingsTip>()
        leaks.forEach { leakTip(it)?.let(tips::add) }

        val past = transactions.filter { !it.date.isAfter(today) }
        // Average over up to three complete calendar months the data covers; otherwise this month so far.
        val earliest = past.minOfOrNull { it.date }
        val current = YearMonth.from(today)
        val complete = (1L..3L).takeWhile { n -> earliest != null && !earliest.isAfter(current.minusMonths(n).atDay(5)) }.size.toLong()
        val months = complete.coerceAtLeast(1)
        val window = if (complete > 0) {
            Ledger.between(past, current.minusMonths(complete).atDay(1), current.minusMonths(1).atEndOfMonth())
        } else {
            Ledger.inMonth(past, current)
        }
        val totals = Ledger.totals(window, categories)
        fun monthlyAvg(cat: String) = (totals.spendingByCategory[cat] ?: 0L) / months

        val eatingOut = monthlyAvg(DefaultCategories.EATING_OUT)
        if (eatingOut >= 10_000) {
            tips += SavingsTip(
                title = "Cook at home one more evening a week",
                detail = "Eating out and delivery cost you ${Amounts.format(eatingOut)} a month on average. Cutting a quarter of that is realistic without giving it up.",
                estimatedMonthlySavingCents = eatingOut / 4,
            )
        }

        val groceries = monthlyAvg(DefaultCategories.GROCERIES)
        if (groceries >= 35_000) {
            tips += SavingsTip(
                title = "Try house brands and a discounter for the basics",
                detail = "Groceries are ${Amounts.format(groceries)} a month. House brands, a weekly list and Lidl or Aldi for staples typically save around 10%.",
                estimatedMonthlySavingCents = groceries / 10,
            )
        }

        val subsTotal = subscriptions.sumOf { it.monthlyCostCents }
        if (subscriptions.size >= 3 && subsTotal >= 4_000) {
            val cheapest = subscriptions.sortedBy { it.monthlyCostCents }.take(2)
            tips += SavingsTip(
                title = "Review your ${subscriptions.size} subscriptions",
                detail = "Together ${Amounts.format(subsTotal)} a month (${Amounts.format(subsTotal * 12)} a year). " +
                    "Dropping the two you use least, for example ${cheapest.joinToString(" and ") { it.merchant }}, saves ${Amounts.format(cheapest.sumOf { it.monthlyCostCents })} a month.",
                estimatedMonthlySavingCents = cheapest.sumOf { it.monthlyCostCents },
            )
        }

        val health = monthlyAvg(DefaultCategories.HEALTH_INSURANCE)
        if (health > 0 && (today.month == Month.NOVEMBER || today.month == Month.DECEMBER || today.month == Month.OCTOBER)) {
            tips += SavingsTip(
                title = "Compare health insurance before 31 December",
                detail = "You pay about ${Amounts.format(health)} a month. In the Netherlands you can switch every year until 31 December; " +
                    "a cheaper policy or a higher voluntary deductible often saves around 5%.",
                estimatedMonthlySavingCents = health / 20,
            )
        }

        val cash = monthlyAvg(DefaultCategories.CASH)
        if (cash >= 10_000) {
            tips += SavingsTip(
                title = "Pay by card instead of cash",
                detail = "You withdraw ${Amounts.format(cash)} a month in cash, which the app cannot track. Paying by card shows where it goes; people usually spend about 10% less when they can see it.",
                estimatedMonthlySavingCents = cash / 10,
            )
        }

        if (totals.incomeCents > 0) {
            val monthlyIncome = totals.incomeCents / months
            val monthlySpending = totals.spendingCents / months
            val left = monthlyIncome - monthlySpending
            if (left > 5_000) {
                tips += SavingsTip(
                    title = "Move ${Amounts.format(left / 2)} to savings on payday",
                    detail = "Income averages ${Amounts.format(monthlyIncome)} and spending ${Amounts.format(monthlySpending)} a month. " +
                        "An automatic transfer on payday makes sure half of what is left is kept.",
                    estimatedMonthlySavingCents = left / 2,
                )
            }
        }

        return tips.filter { it.estimatedMonthlySavingCents > 0 }
            .distinctBy { it.title }
            .sortedByDescending { it.estimatedMonthlySavingCents }
            .take(10)
    }

    private fun leakTip(leak: Leak): SavingsTip? = when (leak.type) {
        LeakType.SMALL_FREQUENT -> SavingsTip(
            title = "Halve the small buys at ${leak.merchants.firstOrNull() ?: "one place"}",
            detail = "${leak.detail} Halving the number of visits saves about ${Amounts.format(leak.monthlyImpactCents / 2)} a month.",
            estimatedMonthlySavingCents = leak.monthlyImpactCents / 2,
        )
        LeakType.PRICE_INCREASE -> SavingsTip(
            title = "Ask ${leak.merchants.firstOrNull() ?: "the provider"} for the old price or a cheaper plan",
            detail = leak.title + ". " + leak.detail,
            estimatedMonthlySavingCents = leak.monthlyImpactCents,
        )
        LeakType.DUPLICATE_SERVICE -> SavingsTip(
            title = "Keep one: ${leak.merchants.joinToString(" or ")}",
            detail = leak.detail,
            estimatedMonthlySavingCents = leak.monthlyImpactCents,
        )
        LeakType.FEES -> SavingsTip(
            title = "Cut bank fees",
            detail = "${leak.detail} A cheaper package or avoiding paid features could remove most of the ${Amounts.format(leak.monthlyImpactCents)} a month.",
            estimatedMonthlySavingCents = leak.monthlyImpactCents / 2,
        )
        LeakType.CATEGORY_SPIKE -> SavingsTip(
            title = "Bring ${leak.title.substringBefore(" is ")} back to normal",
            detail = leak.detail,
            estimatedMonthlySavingCents = leak.monthlyImpactCents,
        )
        LeakType.FORGOTTEN_SUBSCRIPTION -> SavingsTip(
            title = "Cancel ${leak.merchants.firstOrNull() ?: "unused subscriptions"} if you don't use it",
            detail = leak.detail,
            estimatedMonthlySavingCents = leak.monthlyImpactCents,
        )
    }
}
