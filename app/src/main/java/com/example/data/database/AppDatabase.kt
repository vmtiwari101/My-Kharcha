package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.KharchaDao
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        TransactionEntity::class,
        TransactionSplitEntity::class,
        CategoryEntity::class,
        SubcategoryEntity::class,
        AccountEntity::class,
        CardEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun kharchaDao(): KharchaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private fun ensureColumnsExist(database: SupportSQLiteDatabase, tableName: String, expectedColumns: List<Triple<String, String, String>>) {
            val cursor = database.query("PRAGMA table_info(`$tableName`)")
            val existingColumns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                val nameIdx = cursor.getColumnIndex("name")
                if (nameIdx != -1) {
                    existingColumns.add(cursor.getString(nameIdx))
                }
            }
            cursor.close()

            for (triple in expectedColumns) {
                val colName = triple.first
                val colType = triple.second
                val colDefault = triple.third
                if (!existingColumns.contains(colName)) {
                    database.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$colName` $colType NOT NULL DEFAULT $colDefault")
                }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create transaction_splits table if not exists
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transaction_splits` (
                        `id` TEXT NOT NULL,
                        `transactionId` TEXT NOT NULL,
                        `categoryId` TEXT NOT NULL,
                        `subcategoryId` TEXT NOT NULL,
                        `amount` REAL NOT NULL,
                        `note` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_splits_transactionId` ON `transaction_splits` (`transactionId`)")

                // 2. Heal accounts table columns to match AppDatabase expectations
                ensureColumnsExist(
                    db,
                    "accounts",
                    listOf(
                        Triple("bankName", "TEXT", "''"),
                        Triple("last4Digits", "TEXT", "''"),
                        Triple("icon", "TEXT", "'landmark'"),
                        Triple("colour", "TEXT", "'#1E40AF'"),
                        Triple("isActive", "INTEGER", "1"),
                        Triple("isDefault", "INTEGER", "0"),
                        Triple("createdAt", "TEXT", "''"),
                        Triple("updatedAt", "TEXT", "''")
                    )
                )

                // 3. Heal transactions table columns to match AppDatabase expectations
                ensureColumnsExist(
                    db,
                    "transactions",
                    listOf(
                        Triple("source", "TEXT", "'MANUAL'"),
                        Triple("transactionReference", "TEXT", "''"),
                        Triple("originalReference", "TEXT", "''"),
                        Triple("last4Digits", "TEXT", "''"),
                        Triple("createdAt", "TEXT", "''"),
                        Triple("updatedAt", "TEXT", "''")
                    )
                )

                // 4. Heal categories table columns to match AppDatabase expectations
                ensureColumnsExist(
                    db,
                    "categories",
                    listOf(
                        Triple("isDefault", "INTEGER", "1"),
                        Triple("isActive", "INTEGER", "1"),
                        Triple("isIncome", "INTEGER", "0"),
                        Triple("createdAt", "TEXT", "''"),
                        Triple("updatedAt", "TEXT", "''")
                    )
                )

                // 5. Heal subcategories table columns to match AppDatabase expectations
                ensureColumnsExist(
                    db,
                    "subcategories",
                    listOf(
                        Triple("isDefault", "INTEGER", "1"),
                        Triple("isActive", "INTEGER", "1"),
                        Triple("createdAt", "TEXT", "''"),
                        Triple("updatedAt", "TEXT", "''")
                    )
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create cards table if not exists
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cards` (
                        `id` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `last4Digits` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                // Add non-null columns to transactions table
                ensureColumnsExist(
                    db,
                    "transactions",
                    listOf(
                        Triple("isInternalTransfer", "INTEGER", "0"),
                        Triple("needsReview", "INTEGER", "0")
                    )
                )

                // Check and add nullable TEXT columns for transactions
                val cursor = db.query("PRAGMA table_info(`transactions`)")
                val existingCols = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val nameIdx = cursor.getColumnIndex("name")
                    if (nameIdx != -1) {
                        existingCols.add(cursor.getString(nameIdx))
                    }
                }
                cursor.close()

                val nullableCols = listOf(
                    "transactionId", "cardId", "transactionType", "direction",
                    "referenceId", "transferGroupId", "counterpartyAccountId",
                    "last4", "duplicateFingerprint"
                )
                for (col in nullableCols) {
                    if (!existingCols.contains(col)) {
                        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `$col` TEXT DEFAULT NULL")
                    }
                }

                // Add isOwnedByMe to accounts
                ensureColumnsExist(
                    db,
                    "accounts",
                    listOf(
                        Triple("isOwnedByMe", "INTEGER", "1")
                    )
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add credit card metadata to cards table
                ensureColumnsExist(
                    db,
                    "cards",
                    listOf(
                        Triple("creditLimit", "REAL", "0.0"),
                        Triple("outstandingAmount", "REAL", "0.0"),
                        Triple("billingDate", "INTEGER", "0"),
                        Triple("dueDate", "INTEGER", "0")
                    )
                )

                // Add credit card metadata to accounts table
                ensureColumnsExist(
                    db,
                    "accounts",
                    listOf(
                        Triple("creditLimit", "REAL", "0.0"),
                        Triple("outstandingAmount", "REAL", "0.0"),
                        Triple("billingDate", "INTEGER", "0"),
                        Triple("dueDate", "INTEGER", "0"),
                        Triple("initialBalance", "REAL", "0.0")
                    )
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnsExist(
                    db,
                    "accounts",
                    listOf(
                        Triple("initialBalance", "REAL", "0.0")
                    )
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnsExist(
                    db,
                    "accounts",
                    listOf(
                        Triple("minimumAmountDue", "REAL", "0.0"),
                        Triple("paymentDueDate", "TEXT", "''"),
                        Triple("statementDate", "TEXT", "''"),
                        Triple("lastBillSource", "TEXT", "''"),
                        Triple("lastBillMessageId", "TEXT", "''"),
                        Triple("lastBillUpdatedAt", "TEXT", "''")
                    )
                )
                ensureColumnsExist(
                    db,
                    "cards",
                    listOf(
                        Triple("minimumAmountDue", "REAL", "0.0"),
                        Triple("paymentDueDate", "TEXT", "''"),
                        Triple("statementDate", "TEXT", "''"),
                        Triple("lastBillSource", "TEXT", "''"),
                        Triple("lastBillMessageId", "TEXT", "''"),
                        Triple("lastBillUpdatedAt", "TEXT", "''")
                    )
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnsExist(
                    db,
                    "categories",
                    listOf(
                        Triple("nameHindi", "TEXT", "''")
                    )
                )
                ensureColumnsExist(
                    db,
                    "subcategories",
                    listOf(
                        Triple("nameHindi", "TEXT", "''")
                    )
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnsExist(
                    db,
                    "transactions",
                    listOf(
                        Triple("isExpense", "INTEGER", "1")
                    )
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val legacyOwner = "legacy:unassigned"

                fun migrateTable(oldName: String, newName: String, createSql: String, selectColumns: String, columnOrder: List<String>) {
                    val tableExists = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='${oldName}'").use { cursor ->
                        cursor.moveToFirst() && cursor.count > 0
                    }

                    if (!tableExists) return

                    db.execSQL("ALTER TABLE `${oldName}` RENAME TO `${newName}`;")
                    db.execSQL("CREATE TABLE `${oldName}` (${createSql})")
                    db.execSQL(
                        "INSERT INTO `${oldName}` (${columnOrder.joinToString(", ")}) SELECT ${selectColumns} FROM `${newName}`;"
                    )
                    db.execSQL("DROP TABLE `${newName}`;")
                }

                val accountCreate = "`userId` TEXT NOT NULL DEFAULT '${legacyOwner}', `id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `bankName` TEXT NOT NULL DEFAULT '', `last4Digits` TEXT NOT NULL DEFAULT '', `icon` TEXT NOT NULL DEFAULT 'landmark', `colour` TEXT NOT NULL DEFAULT '#1E40AF', `isActive` INTEGER NOT NULL DEFAULT 1, `isDefault` INTEGER NOT NULL DEFAULT 0, `isOwnedByMe` INTEGER NOT NULL DEFAULT 1, `createdAt` TEXT NOT NULL DEFAULT '', `updatedAt` TEXT NOT NULL DEFAULT '', `creditLimit` REAL NOT NULL DEFAULT 0.0, `outstandingAmount` REAL NOT NULL DEFAULT 0.0, `billingDate` INTEGER NOT NULL DEFAULT 0, `dueDate` INTEGER NOT NULL DEFAULT 0, `initialBalance` REAL NOT NULL DEFAULT 0.0, `minimumAmountDue` REAL NOT NULL DEFAULT 0.0, `paymentDueDate` TEXT NOT NULL DEFAULT '', `statementDate` TEXT NOT NULL DEFAULT '', `lastBillSource` TEXT NOT NULL DEFAULT '', `lastBillMessageId` TEXT NOT NULL DEFAULT '', `lastBillUpdatedAt` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`userId`, `id`))"
                val cardCreate = "`userId` TEXT NOT NULL DEFAULT '${legacyOwner}', `id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `last4Digits` TEXT NOT NULL, `createdAt` TEXT NOT NULL DEFAULT '', `updatedAt` TEXT NOT NULL DEFAULT '', `creditLimit` REAL NOT NULL DEFAULT 0.0, `outstandingAmount` REAL NOT NULL DEFAULT 0.0, `billingDate` INTEGER NOT NULL DEFAULT 0, `dueDate` INTEGER NOT NULL DEFAULT 0, `minimumAmountDue` REAL NOT NULL DEFAULT 0.0, `paymentDueDate` TEXT NOT NULL DEFAULT '', `statementDate` TEXT NOT NULL DEFAULT '', `lastBillSource` TEXT NOT NULL DEFAULT '', `lastBillMessageId` TEXT NOT NULL DEFAULT '', `lastBillUpdatedAt` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`userId`, `id`))"
                val categoryCreate = "`userId` TEXT NOT NULL DEFAULT '${legacyOwner}', `id` TEXT NOT NULL, `name` TEXT NOT NULL, `nameHindi` TEXT NOT NULL DEFAULT '', `icon` TEXT NOT NULL, `colour` TEXT NOT NULL, `isDefault` INTEGER NOT NULL DEFAULT 0, `isActive` INTEGER NOT NULL DEFAULT 1, `isIncome` INTEGER NOT NULL DEFAULT 0, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`userId`, `id`))"
                val subcategoryCreate = "`userId` TEXT NOT NULL DEFAULT '${legacyOwner}', `id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `name` TEXT NOT NULL, `nameHindi` TEXT NOT NULL DEFAULT '', `icon` TEXT NOT NULL, `colour` TEXT NOT NULL, `isDefault` INTEGER NOT NULL DEFAULT 0, `isActive` INTEGER NOT NULL DEFAULT 1, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`userId`, `id`))"
                val transactionCreate = "`userId` TEXT NOT NULL DEFAULT '${legacyOwner}', `id` TEXT NOT NULL, `type` TEXT NOT NULL, `amount` REAL NOT NULL, `date` TEXT NOT NULL, `time` TEXT NOT NULL, `merchant` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `subcategoryId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `paymentMethod` TEXT NOT NULL, `note` TEXT NOT NULL, `source` TEXT NOT NULL DEFAULT 'MANUAL', `transactionReference` TEXT NOT NULL DEFAULT '', `originalReference` TEXT NOT NULL DEFAULT '', `last4Digits` TEXT NOT NULL DEFAULT '', `createdAt` TEXT NOT NULL DEFAULT '', `updatedAt` TEXT NOT NULL DEFAULT '', `transactionId` TEXT, `cardId` TEXT, `transactionType` TEXT, `direction` TEXT, `referenceId` TEXT, `transferGroupId` TEXT, `counterpartyAccountId` TEXT, `last4` TEXT, `duplicateFingerprint` TEXT, `isInternalTransfer` INTEGER NOT NULL DEFAULT 0, `needsReview` INTEGER NOT NULL DEFAULT 0, `isExpense` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`userId`, `id`))"
                val splitCreate = "`userId` TEXT NOT NULL DEFAULT '${legacyOwner}', `id` TEXT NOT NULL, `transactionId` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `subcategoryId` TEXT NOT NULL DEFAULT '', `amount` REAL NOT NULL, `note` TEXT NOT NULL DEFAULT '', `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`userId`, `id`), FOREIGN KEY(`userId`, `transactionId`) REFERENCES `transactions`(`userId`, `id`) ON DELETE CASCADE)"

                fun renameAddUserId(oldName: String, createSql: String, selectCols: String, columns: List<String>) {
                    val tempName = "${oldName}_v10_tmp"
                    db.execSQL("ALTER TABLE `$oldName` RENAME TO `$tempName`")
                    db.execSQL("CREATE TABLE `$oldName` ($createSql)")
                    db.execSQL("INSERT INTO `$oldName` (${columns.joinToString(", ")}) SELECT ${selectCols} FROM `$tempName`")
                    db.execSQL("DROP TABLE `$tempName`")
                }

                renameAddUserId(
                    "accounts",
                    accountCreate,
                    "`id`, `name`, `type`, `bankName`, `last4Digits`, `icon`, `colour`, `isActive`, `isDefault`, `isOwnedByMe`, `createdAt`, `updatedAt`, `creditLimit`, `outstandingAmount`, `billingDate`, `dueDate`, `initialBalance`, `minimumAmountDue`, `paymentDueDate`, `statementDate`, `lastBillSource`, `lastBillMessageId`, `lastBillUpdatedAt`, '${legacyOwner}'",
                    listOf("userId","id","name","type","bankName","last4Digits","icon","colour","isActive","isDefault","isOwnedByMe","createdAt","updatedAt","creditLimit","outstandingAmount","billingDate","dueDate","initialBalance","minimumAmountDue","paymentDueDate","statementDate","lastBillSource","lastBillMessageId","lastBillUpdatedAt")
                )
                renameAddUserId(
                    "cards",
                    cardCreate,
                    "`id`, `accountId`, `name`, `type`, `last4Digits`, `createdAt`, `updatedAt`, `creditLimit`, `outstandingAmount`, `billingDate`, `dueDate`, `minimumAmountDue`, `paymentDueDate`, `statementDate`, `lastBillSource`, `lastBillMessageId`, `lastBillUpdatedAt`, '${legacyOwner}'",
                    listOf("userId","id","accountId","name","type","last4Digits","createdAt","updatedAt","creditLimit","outstandingAmount","billingDate","dueDate","minimumAmountDue","paymentDueDate","statementDate","lastBillSource","lastBillMessageId","lastBillUpdatedAt")
                )
                renameAddUserId(
                    "categories",
                    categoryCreate,
                    "`id`, `name`, `nameHindi`, `icon`, `colour`, `isDefault`, `isActive`, `isIncome`, `createdAt`, `updatedAt`, '${legacyOwner}'",
                    listOf("userId","id","name","nameHindi","icon","colour","isDefault","isActive","isIncome","createdAt","updatedAt")
                )
                renameAddUserId(
                    "subcategories",
                    subcategoryCreate,
                    "`id`, `categoryId`, `name`, `nameHindi`, `icon`, `colour`, `isDefault`, `isActive`, `createdAt`, `updatedAt`, '${legacyOwner}'",
                    listOf("userId","id","categoryId","name","nameHindi","icon","colour","isDefault","isActive","createdAt","updatedAt")
                )

                db.execSQL(
                    """
                    CREATE TABLE `transaction_splits_v10_data` AS
                    SELECT `id`, `transactionId`, `categoryId`, `subcategoryId`, `amount`, `note`, `createdAt`, `updatedAt`
                    FROM `transaction_splits`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `transaction_splits`")

                renameAddUserId(
                    "transactions",
                    transactionCreate,
                    "`id`, `type`, `amount`, `date`, `time`, `merchant`, `categoryId`, `subcategoryId`, `accountId`, `paymentMethod`, `note`, `source`, `transactionReference`, `originalReference`, `last4Digits`, `createdAt`, `updatedAt`, `transactionId`, `cardId`, `transactionType`, `direction`, `referenceId`, `transferGroupId`, `counterpartyAccountId`, `last4`, `duplicateFingerprint`, `isInternalTransfer`, `needsReview`, `isExpense`, '${legacyOwner}'",
                    listOf("userId","id","type","amount","date","time","merchant","categoryId","subcategoryId","accountId","paymentMethod","note","source","transactionReference","originalReference","last4Digits","createdAt","updatedAt","transactionId","cardId","transactionType","direction","referenceId","transferGroupId","counterpartyAccountId","last4","duplicateFingerprint","isInternalTransfer","needsReview","isExpense")
                )

                db.execSQL("CREATE TABLE `transaction_splits` ($splitCreate)")
                db.execSQL(
                    """
                    INSERT INTO `transaction_splits`
                        (`userId`, `id`, `transactionId`, `categoryId`, `subcategoryId`, `amount`, `note`, `createdAt`, `updatedAt`)
                    SELECT
                        '${legacyOwner}', `id`, `transactionId`, `categoryId`, `subcategoryId`, `amount`, `note`, `createdAt`, `updatedAt`
                    FROM `transaction_splits_v10_data`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `transaction_splits_v10_data`")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_splits_userId_transactionId ON `transaction_splits` (`userId`, `transactionId`)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumnsExist(
                    db,
                    "transactions",
                    listOf(Triple("cardPaymentBalanceApplied", "INTEGER", "0"))
                )
            }
        }

        @Volatile
        private var TEST_INSTANCE: AppDatabase? = null
        
        fun setTestInstance(database: AppDatabase?) {
            TEST_INSTANCE = database
        }

        fun getDatabase(context: Context): AppDatabase {
            TEST_INSTANCE?.let { return it }
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kharcha_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        suspend fun prepopulateData(dao: KharchaDao) {
            com.example.data.DefaultCategoryData.restoreAndExpandCategoriesAndSubcategories(
                dao,
                ensureCashAccount = false
            )
        }
    }
}
