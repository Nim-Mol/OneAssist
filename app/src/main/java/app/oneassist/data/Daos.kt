package app.oneassist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE date BETWEEN :from AND :to ORDER BY date DESC, createdAt DESC")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<TransactionEntity>>

    /** Rows of the period carrying [tag] (normalized). Tags are stored as ",a,b," so this matches whole tags. */
    @Query(
        "SELECT * FROM transactions WHERE date BETWEEN :from AND :to AND instr(tags, ',' || :tag || ',') > 0 " +
            "ORDER BY date DESC, createdAt DESC"
    )
    fun observeBetweenWithTag(from: LocalDate, to: LocalDate, tag: String): Flow<List<TransactionEntity>>

    /** Encoded tag column of every tagged row; split in the repository. */
    @Query("SELECT DISTINCT tags FROM transactions WHERE tags != ''")
    fun observeTagColumns(): Flow<List<String>>

    @Query("SELECT * FROM transactions WHERE recurring = 1 AND amountCents > 0")
    fun observeRecurringIncome(): Flow<List<TransactionEntity>>

    @Query("UPDATE transactions SET tags = :tags WHERE id = :id")
    suspend fun setTags(id: String, tags: String): Int

    @Query("UPDATE transactions SET recurring = :recurring WHERE id = :id AND amountCents > 0")
    suspend fun setRecurring(id: String, recurring: Boolean): Int

    /** Income: set the category and stop treating the row as a transfer. Money in only. */
    @Query("UPDATE transactions SET categoryId = :categoryId, peerToPeer = 0 WHERE id = :id AND amountCents > 0")
    suspend fun markAsIncome(id: String, categoryId: String): Int

    /** Same as [markAsIncome] for all money in from [merchant]; payments to them are left alone. */
    @Query("UPDATE transactions SET categoryId = :categoryId, peerToPeer = 0 WHERE merchant = :merchant AND amountCents > 0")
    suspend fun markAsIncomeForMerchant(merchant: String, categoryId: String): Int

    @Query("SELECT * FROM transactions WHERE date >= :from ORDER BY date ASC")
    fun observeSince(from: LocalDate): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE date >= :from ORDER BY date ASC")
    suspend fun since(from: LocalDate): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE date BETWEEN :from AND :to")
    suspend fun between(from: LocalDate, to: LocalDate): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<TransactionEntity>)

    @Update
    suspend fun updateAll(rows: List<TransactionEntity>)

    /** Moves the merchant's rows to [categoryId], except rows the user marked as income ([keep]). */
    @Query(
        "UPDATE transactions SET categoryId = :categoryId WHERE merchant = :merchant " +
            "AND (categoryId IS NULL OR categoryId NOT IN (:keep))"
    )
    suspend fun setCategoryForMerchant(merchant: String, categoryId: String, keep: List<String>): Int

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE id = :id")
    suspend fun setCategory(id: String, categoryId: String): Int
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    suspend fun all(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<CategoryEntity>)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules")
    suspend fun all(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RuleEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(rules: List<RuleEntity>)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: String): Int
}
