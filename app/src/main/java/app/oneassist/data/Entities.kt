package app.oneassist.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import app.oneassist.core.AccountSource
import app.oneassist.core.Category
import app.oneassist.core.CategoryKind
import app.oneassist.core.CategoryRule
import app.oneassist.core.DefaultCategories
import app.oneassist.core.ImportSource
import app.oneassist.core.Tags
import app.oneassist.core.Transaction
import java.time.LocalDate

@Entity(
    tableName = "transactions",
    indices = [Index("date"), Index("account", "amountCents"), Index("merchant")],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val account: AccountSource,
    val date: LocalDate,
    val amountCents: Long,
    val currency: String,
    /** Normalized merchant name (MerchantNormalizer). */
    val merchant: String,
    /** Bank's own description (CSV only). Stays on the device; never sent to the assistant. */
    val description: String,
    val categoryId: String?,
    val source: ImportSource,
    @ColumnInfo(defaultValue = "0") val peerToPeer: Boolean,
    val createdAt: Long,
    /** User tags encoded with Tags.encode: ",a,b," or "". */
    @ColumnInfo(defaultValue = "") val tags: String = "",
    /** The user's own note. Stays on the device; never sent to the assistant. */
    @ColumnInfo(defaultValue = "") val note: String = "",
    /** Manual income that repeats monthly. */
    @ColumnInfo(defaultValue = "0") val recurring: Boolean = false,
) {
    fun toModel() = Transaction(
        id = id, account = account, date = date, amountCents = amountCents, currency = currency,
        merchant = merchant, description = description, categoryId = categoryId, source = source,
        peerToPeer = peerToPeer, tags = Tags.decode(tags), note = note, recurring = recurring,
    )

    companion object {
        fun from(t: Transaction, createdAt: Long) = TransactionEntity(
            id = t.id, account = t.account, date = t.date, amountCents = t.amountCents, currency = t.currency,
            merchant = t.merchant, description = t.description, categoryId = t.categoryId, source = t.source,
            peerToPeer = t.peerToPeer, createdAt = createdAt, tags = Tags.encode(t.tags), note = t.note,
            recurring = t.recurring,
        )
    }
}

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: CategoryKind,
    val sortOrder: Int,
) {
    fun toModel() = Category(id, name, kind)
}

/**
 * Categorization rules. Default rules have id "default:<category>:<pattern>", learned rules
 * "learned:<pattern>" so correcting the same merchant again replaces the earlier rule. Learned
 * income rules use "learned-income:<pattern>": they only apply to money coming in, so they live
 * next to the expense rule for the same name (a friend you pay and who pays you back).
 */
@Entity(tableName = "rules", indices = [Index("categoryId")])
data class RuleEntity(
    @PrimaryKey val id: String,
    val pattern: String,
    val categoryId: String,
    val priority: Int,
    val learnedFromUser: Boolean,
) {
    fun toModel() = CategoryRule(pattern, categoryId, priority, learnedFromUser)

    companion object {
        fun from(rule: CategoryRule) = RuleEntity(
            id = when {
                !rule.learnedFromUser -> "default:${rule.categoryId}:${rule.pattern}"
                rule.categoryId in DefaultCategories.incomeIds -> "learned-income:${rule.pattern}"
                else -> "learned:${rule.pattern}"
            },
            pattern = rule.pattern,
            categoryId = rule.categoryId,
            priority = rule.priority,
            learnedFromUser = rule.learnedFromUser,
        )
    }
}

class Converters {
    @TypeConverter fun dateToEpochDay(date: LocalDate): Long = date.toEpochDay()
    @TypeConverter fun epochDayToDate(day: Long): LocalDate = LocalDate.ofEpochDay(day)

    @TypeConverter fun accountToString(v: AccountSource): String = v.name
    @TypeConverter fun stringToAccount(v: String): AccountSource = AccountSource.valueOf(v)

    @TypeConverter fun sourceToString(v: ImportSource): String = v.name
    @TypeConverter fun stringToSource(v: String): ImportSource = ImportSource.valueOf(v)

    @TypeConverter fun kindToString(v: CategoryKind): String = v.name
    @TypeConverter fun stringToKind(v: String): CategoryKind = CategoryKind.valueOf(v)
}
