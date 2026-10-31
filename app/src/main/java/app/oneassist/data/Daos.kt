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

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE merchant = :merchant")
    suspend fun setCategoryForMerchant(merchant: String, categoryId: String): Int

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
}
