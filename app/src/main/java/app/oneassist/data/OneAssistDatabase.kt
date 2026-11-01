package app.oneassist.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.oneassist.core.DefaultCategories
import app.oneassist.core.DefaultRules
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class, RuleEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OneAssistDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun ruleDao(): RuleDao

    companion object {
        const val NAME = "oneassist.db"

        /** v2 adds tags, notes and the recurring-income flag. Keeps data from earlier test builds. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurring INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Opens the database encrypted with SQLCipher using [passphrase] (raw key bytes). */
        fun open(context: Context, passphrase: ByteArray): OneAssistDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context.applicationContext, OneAssistDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2)
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
