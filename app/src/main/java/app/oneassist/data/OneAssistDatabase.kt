package app.oneassist.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import app.oneassist.core.DefaultCategories
import app.oneassist.core.DefaultRules
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class, RuleEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OneAssistDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun ruleDao(): RuleDao

    companion object {
        const val NAME = "oneassist.db"

        /** Opens the database encrypted with SQLCipher using [passphrase] (raw key bytes). */
        fun open(context: Context, passphrase: ByteArray): OneAssistDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context.applicationContext, OneAssistDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addCallback(SeedCallback)
                .build()
        }

        /** Removes the database files, used only when its key can never be recovered. */
        fun deleteFiles(context: Context) {
            context.applicationContext.deleteDatabase(NAME)
        }
    }

    /**
     * Seeds default categories and starter rules. Runs on every open with INSERT OR IGNORE, so
     * categories and rules added in later app versions appear without touching user data.
     */
    private object SeedCallback : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                DefaultCategories.all.forEachIndexed { index, c ->
                    db.insert("categories", SQLiteDatabase.CONFLICT_IGNORE, ContentValues().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("kind", c.kind.name)
                        put("sortOrder", index)
                    })
                }
                DefaultRules.all.forEach { r ->
                    val e = RuleEntity.from(r)
                    db.insert("rules", SQLiteDatabase.CONFLICT_IGNORE, ContentValues().apply {
                        put("id", e.id)
                        put("pattern", e.pattern)
                        put("categoryId", e.categoryId)
                        put("priority", e.priority)
                        put("learnedFromUser", if (e.learnedFromUser) 1 else 0)
                    })
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
