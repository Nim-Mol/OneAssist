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
import app.oneassist.core.ImportSource
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
) {
    fun toModel() = Transaction(
        id = id, account = account, date = date, amountCents = amountCents, currency = currency,
        merchant = merchant, description = description, categoryId = categoryId, source = source,
        peerToPeer = peerToPeer,
    )
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
 * "learned:<pattern>" so correcting the same merchant again replaces the earlier rule.
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
            id = if (rule.learnedFromUser) "learned:${rule.pattern}" else "default:${rule.categoryId}:${rule.pattern}",
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
