package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun readStringFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertTrue(appName.isNotEmpty())
    }

    @Test
    fun testDatabaseMigrationFromVersion1ToVersion2() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // 1. Manually create the database with version 1 tables
        val dbName = "test_migration_db"
        context.deleteDatabase(dbName)
        
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // Create version 1 tables exactly
                        db.execSQL(
                            """
                            CREATE TABLE `accounts` (
                                `id` TEXT NOT NULL, 
                                `name` TEXT NOT NULL, 
                                `type` TEXT NOT NULL, 
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE `transactions` (
                                `id` TEXT NOT NULL, 
                                `type` TEXT NOT NULL, 
                                `amount` REAL NOT NULL, 
                                `date` TEXT NOT NULL, 
                                `time` TEXT NOT NULL, 
                                `merchant` TEXT NOT NULL, 
                                `categoryId` TEXT NOT NULL, 
                                `subcategoryId` TEXT NOT NULL, 
                                `accountId` TEXT NOT NULL, 
                                `paymentMethod` TEXT NOT NULL, 
                                `note` TEXT NOT NULL, 
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE `categories` (
                                `id` TEXT NOT NULL, 
                                `name` TEXT NOT NULL, 
                                `icon` TEXT NOT NULL, 
                                `colour` TEXT NOT NULL, 
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE `subcategories` (
                                `id` TEXT NOT NULL, 
                                `categoryId` TEXT NOT NULL, 
                                `name` TEXT NOT NULL, 
                                `icon` TEXT NOT NULL, 
                                `colour` TEXT NOT NULL, 
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        
                        // Seed some old data
                        db.execSQL("INSERT INTO `accounts` (id, name, type) VALUES ('acc-hdfc', 'HDFC Salary Bank', 'Bank Account')")
                        db.execSQL("INSERT INTO `categories` (id, name, icon, colour) VALUES ('cat-food', 'Food', '🍔', '#F97316')")
                        db.execSQL("INSERT INTO `transactions` (id, type, amount, date, time, merchant, categoryId, subcategoryId, accountId, paymentMethod, note) VALUES ('tx-1', 'EXPENSE', 100.0, '2026-09-22', '12:00', 'Swiggy', 'cat-food', '', 'acc-hdfc', 'UPI', 'Lunch')")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    }
                })
                .build()
        )
        
        // Open the DB to trigger onCreate and write seed data
        val writableDb = openHelper.writableDatabase
        writableDb.close()

        // 2. Open and upgrade using Room and AppDatabase
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9)
            .build()

        // Trigger migration by performing a query
        kotlinx.coroutines.runBlocking {
            val count = roomDb.kharchaDao().getCategoryCount()
            assertEquals(1, count)
        }
        
        roomDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testNotificationParserCases() {
        val existing = mutableListOf<com.example.data.entity.TransactionEntity>()

        // CASE 1: "Your A/C XXXXX652345 is credited with INR 1.00." -> Expected: INCOME ₹1, account last4 2345, Imported.
        val result1 = com.example.utils.NotificationParser.parseNotification(
            packageName = "com.idfcfirstbank",
            title = "IDFC First Bank",
            text = "Your A/C XXXXX652345 is credited with INR 1.00.",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = existing
        )
        assertEquals(com.example.utils.NotificationStatus.IMPORTED, result1.status)
        assertEquals("INCOME", result1.transaction?.type)
        assertEquals(1.0, result1.transaction?.amount ?: 0.0, 0.01)
        assertEquals("2345", result1.transaction?.last4Digits)

        // Add 1 to existing
        existing.add(result1.transaction!!)

        // CASE 2: "Sent Rs.1.00..." -> Expected: EXPENSE ₹1, Imported.
        val result2 = com.example.utils.NotificationParser.parseNotification(
            packageName = "com.hdfcbank",
            title = "HDFC Bank",
            text = "Sent Rs.1.00...",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = existing
        )
        assertEquals(com.example.utils.NotificationStatus.IMPORTED, result2.status)
        assertEquals("EXPENSE", result2.transaction?.type)
        assertEquals(1.0, result2.transaction?.amount ?: 0.0, 0.01)

        // Add 2 to existing
        existing.add(result2.transaction!!)

        // CASE 3: "Sent Rs.2.00..." -> Expected: EXPENSE ₹2, Imported.
        val result3 = com.example.utils.NotificationParser.parseNotification(
            packageName = "com.hdfcbank",
            title = "HDFC Bank",
            text = "Sent Rs.2.00...",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = existing
        )
        assertEquals(com.example.utils.NotificationStatus.IMPORTED, result3.status)
        assertEquals("EXPENSE", result3.transaction?.type)
        assertEquals(2.0, result3.transaction?.amount ?: 0.0, 0.01)

        // Add 3 to existing
        existing.add(result3.transaction!!)

        // CASE 4: "Rs.3.00 is debited from your account..." -> Expected: EXPENSE ₹3, Imported.
        val result4 = com.example.utils.NotificationParser.parseNotification(
            packageName = "com.google.android.gmail",
            title = "Gmail Alert",
            text = "Rs.3.00 is debited from your account ending 5495.",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = existing
        )
        assertEquals(com.example.utils.NotificationStatus.IMPORTED, result4.status)
        assertEquals("EXPENSE", result4.transaction?.type)
        assertEquals(3.0, result4.transaction?.amount ?: 0.0, 0.01)
        assertEquals("5495", result4.transaction?.last4Digits)

        // Add 4 to existing
        existing.add(result4.transaction!!)

        // CASE 5: "A/C XXXXX056168 is credited with INR 1.00." -> Expected: INCOME ₹1, account last4 6168, Imported.
        val result5 = com.example.utils.NotificationParser.parseNotification(
            packageName = "com.idfcfirstbank",
            title = "IDFC First Bank",
            text = "A/C XXXXX056168 is credited with INR 1.00.",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = existing
        )
        assertEquals(com.example.utils.NotificationStatus.IMPORTED, result5.status)
        assertEquals("INCOME", result5.transaction?.type)
        assertEquals(1.0, result5.transaction?.amount ?: 0.0, 0.01)
        assertEquals("6168", result5.transaction?.last4Digits)

        // Add 5 to existing
        existing.add(result5.transaction!!)

        // CASE 6: "Your OTP is 123456." -> Expected: Ignored.
        val result6 = com.example.utils.NotificationParser.parseNotification(
            packageName = "com.banking.secure",
            title = "OTP Bank",
            text = "Your OTP is 123456.",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = existing
        )
        assertEquals(com.example.utils.NotificationStatus.IGNORED, result6.status)

        // CASE 7: "Your account balance is INR 10,000." -> Expected: Ignored.
        val result7 = com.example.utils.NotificationParser.parseNotification(
            packageName = "com.banking.secure",
            title = "Balance Enquiry",
            text = "Your account balance is INR 10,000.",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = existing
        )
        assertEquals(com.example.utils.NotificationStatus.IGNORED, result7.status)

        // CASE 8: The same ₹2 HDFC debit received through SMS and Gmail -> Expected: ONLY ONE transaction.
        val result8 = com.example.utils.NotificationParser.parseNotification(
            packageName = "com.google.android.gmail",
            title = "Gmail Alert duplicate",
            text = "Rs.2.00 is debited from your account ending 5495.",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = existing
        )
        assertEquals(com.example.utils.NotificationStatus.DUPLICATE, result8.status)
    }

    @Test
    fun testCategoryAndMerchantDrillDownMatchingAndSorting() {
        val sampleTxs = listOf(
            com.example.data.entity.TransactionEntity(
                id = "tx-1",
                type = "EXPENSE",
                amount = 250.0,
                date = "2026-09-20",
                time = "10:00",
                merchant = "Swiggy",
                categoryId = "cat-food",
                subcategoryId = "sub-lunch",
                accountId = "acc-1",
                paymentMethod = "UPI",
                note = "Lunch with friends",
                source = "SMS",
                transactionReference = "TXN1001",
                originalReference = "REF1001",
                last4Digits = "1234",
                createdAt = "2026-09-20T10:00:00Z",
                updatedAt = "2026-09-20T10:00:00Z"
            ),
            com.example.data.entity.TransactionEntity(
                id = "tx-2",
                type = "EXPENSE",
                amount = 150.0,
                date = "2026-09-22",
                time = "14:30",
                merchant = "Swiggy",
                categoryId = "cat-food",
                subcategoryId = "sub-snacks",
                accountId = "acc-1",
                paymentMethod = "UPI",
                note = "Snacks",
                source = "NOTIFICATION",
                transactionReference = "TXN1002",
                originalReference = "REF1002",
                last4Digits = "1234",
                createdAt = "2026-09-22T14:30:00Z",
                updatedAt = "2026-09-22T14:30:00Z"
            ),
            com.example.data.entity.TransactionEntity(
                id = "tx-3",
                type = "EXPENSE",
                amount = 700.0,
                date = "2026-09-23",
                time = "09:15",
                merchant = "CRED",
                categoryId = "cat-bills",
                subcategoryId = "",
                accountId = "acc-1",
                paymentMethod = "NetBanking",
                note = "Credit card bill",
                source = "EMAIL",
                transactionReference = "TXN1003",
                originalReference = "REF1003",
                last4Digits = "5678",
                createdAt = "2026-09-23T09:15:00Z",
                updatedAt = "2026-09-23T09:15:00Z"
            ),
            com.example.data.entity.TransactionEntity(
                id = "tx-4",
                type = "EXPENSE",
                amount = 450.0,
                date = "2026-09-24",
                time = "18:00",
                merchant = "Swiggy",
                categoryId = "cat-food",
                subcategoryId = "sub-dinner",
                accountId = "acc-1",
                paymentMethod = "UPI",
                note = "Dinner",
                source = "MANUAL",
                transactionReference = "TXN1004",
                originalReference = "REF1004",
                last4Digits = "1234",
                createdAt = "2026-09-24T18:00:00Z",
                updatedAt = "2026-09-24T18:00:00Z"
            )
        )

        // 1. Category Drill-Down: cat-food
        val foodTxs = sampleTxs.filter { it.categoryId == "cat-food" }
            .sortedWith(compareByDescending<com.example.data.entity.TransactionEntity> { it.date }.thenByDescending { it.time })

        assertEquals(3, foodTxs.size)
        assertEquals(850.0, foodTxs.sumOf { it.amount }, 0.01)
        // Check sorting: newest first (tx-4 from Sep 24, then tx-2 from Sep 22, then tx-1 from Sep 20)
        assertEquals("tx-4", foodTxs[0].id)
        assertEquals("tx-2", foodTxs[1].id)
        assertEquals("tx-1", foodTxs[2].id)

        // 2. Merchant Drill-Down: Swiggy
        val swiggyTxs = sampleTxs.filter { it.merchant.equals("Swiggy", ignoreCase = true) }
            .sortedWith(compareByDescending<com.example.data.entity.TransactionEntity> { it.date }.thenByDescending { it.time })

        assertEquals(3, swiggyTxs.size)
        assertEquals(850.0, swiggyTxs.sumOf { it.amount }, 0.01)
        assertEquals("tx-4", swiggyTxs[0].id)
        assertEquals("tx-2", swiggyTxs[1].id)
        assertEquals("tx-1", swiggyTxs[2].id)

        // 3. Merchant Drill-Down: CRED
        val credTxs = sampleTxs.filter { it.merchant.equals("CRED", ignoreCase = true) }
        assertEquals(1, credTxs.size)
        assertEquals(700.0, credTxs.first().amount, 0.01)
        assertEquals("cat-bills", credTxs.first().categoryId)
    }

    @Test
    fun testSubcategoryManagementAndMove() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = com.example.data.repository.KharchaRepository(db.kharchaDao())

        // 1. Create Parent Categories
        val transportCat = com.example.data.entity.CategoryEntity(
            id = "cat-transport",
            name = "Transport",
            nameHindi = "परिवहन",
            icon = "🚗",
            colour = "#2563EB",
            isDefault = false,
            isActive = true,
            isIncome = false,
            createdAt = "2026-09-24T00:00:00Z",
            updatedAt = "2026-09-24T00:00:00Z"
        )
        val travelCat = com.example.data.entity.CategoryEntity(
            id = "cat-travel",
            name = "Travel",
            nameHindi = "यात्रा",
            icon = "✈️",
            colour = "#7C3AED",
            isDefault = false,
            isActive = true,
            isIncome = false,
            createdAt = "2026-09-24T00:00:00Z",
            updatedAt = "2026-09-24T00:00:00Z"
        )
        repository.insertCategory(transportCat)
        repository.insertCategory(travelCat)

        // 2. Create Subcategory "Auto" under Transport
        val autoSub = com.example.data.entity.SubcategoryEntity(
            id = "sub-auto",
            categoryId = "cat-transport",
            name = "Auto",
            nameHindi = "ऑटो",
            icon = "🛺",
            colour = "#2563EB",
            isDefault = false,
            isActive = true,
            createdAt = "2026-09-24T00:00:00Z",
            updatedAt = "2026-09-24T00:00:00Z"
        )
        repository.insertSubcategory(autoSub)

        // 3. Create Transaction using Auto under Transport
        val tx1 = com.example.data.entity.TransactionEntity(
            id = "tx-auto-1",
            type = "EXPENSE",
            amount = 120.0,
            date = "2026-09-24",
            time = "10:30",
            merchant = "Auto Ride",
            categoryId = "cat-transport",
            subcategoryId = "sub-auto",
            accountId = "acc-cash",
            paymentMethod = "Cash",
            note = "Metro station to office",
            source = "MANUAL",
            transactionReference = "",
            originalReference = "",
            last4Digits = "",
            createdAt = "2026-09-24T10:30:00Z",
            updatedAt = "2026-09-24T10:30:00Z"
        )
        repository.insertTransaction(tx1)

        // Verify initial state
        var allSubs = db.kharchaDao().getAllSubcategoriesSync()
        var allTxs = db.kharchaDao().getAllTransactionsSync()
        assertEquals("cat-transport", allSubs.find { it.id == "sub-auto" }?.categoryId)
        assertEquals("cat-transport", allTxs.find { it.id == "tx-auto-1" }?.categoryId)
        assertEquals("sub-auto", allTxs.find { it.id == "tx-auto-1" }?.subcategoryId)

        // 4. Move Subcategory "Auto" from Transport to Travel
        repository.moveSubcategory("sub-auto", "cat-travel", "2026-09-24T11:00:00Z")

        // 5. Verify Subcategory and Transactions are updated consistently
        allSubs = db.kharchaDao().getAllSubcategoriesSync()
        allTxs = db.kharchaDao().getAllTransactionsSync()

        // Subcategory parent is updated to Travel
        val movedSub = allSubs.find { it.id == "sub-auto" }
        assertEquals("cat-travel", movedSub?.categoryId)

        // Transport subcategories count = 0, Travel subcategories count = 1
        assertEquals(0, allSubs.filter { it.categoryId == "cat-transport" }.size)
        assertEquals(1, allSubs.filter { it.categoryId == "cat-travel" }.size)

        // Existing transaction's categoryId is automatically updated to Travel
        val updatedTx = allTxs.find { it.id == "tx-auto-1" }
        assertEquals(1, allTxs.size) // No transactions lost or duplicated
        assertEquals("cat-travel", updatedTx?.categoryId)
        assertEquals("sub-auto", updatedTx?.subcategoryId)
        assertEquals(120.0, updatedTx?.amount ?: 0.0, 0.01)

        db.close()
    }

    @Test
    fun testDuplicateDetectionWithSameReferenceIdDifferentAccounts() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.setTestInstance(db)

        // Add accounts
        val acc1 = com.example.data.entity.AccountEntity("acc-hdfc-4092", "HDFC Bank Salary", "Bank Account", bankName = "HDFC", last4Digits = "4092")
        val acc2 = com.example.data.entity.AccountEntity("acc-hdfc-2434", "HDFC Bank Salary", "Credit Card", bankName = "HDFC", last4Digits = "2434")
        db.kharchaDao().insertAccount(acc1)
        db.kharchaDao().insertAccount(acc2)

        // 1. First Jio transaction with ref 2026082310083700756, Account 4092, Bank Transfer
        val tx1 = com.example.data.entity.TransactionEntity(
            id = "tx-jio-1",
            type = "EXPENSE",
            amount = 899.0,
            date = "2026-08-23",
            time = "10:08",
            merchant = "Jio",
            categoryId = "cat-recharge",
            subcategoryId = "",
            accountId = "acc-hdfc-4092",
            paymentMethod = "Bank Transfer",
            note = "SMS from VK-HDFCBK",
            source = "SMS",
            transactionReference = "2026082310083700756",
            originalReference = "SMS-REF-2026082310083700756",
            last4Digits = "4092"
        )
        val (ingested1, status1) = com.example.utils.TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = tx1,
            rawText = "HDFC Bank: Rs 899.00 debited from A/C XX4092 for Jio recharge. Ref 2026082310083700756"
        )
        assertEquals(com.example.utils.IngestionStatus.IMPORTED, status1)

        // 2. Second Jio transaction with exact same ref 2026082310083700756, Account 2434, Credit Card
        val tx2 = com.example.data.entity.TransactionEntity(
            id = "tx-jio-2",
            type = "EXPENSE",
            amount = 899.0,
            date = "2026-08-23",
            time = "10:08",
            merchant = "Jio",
            categoryId = "cat-recharge",
            subcategoryId = "",
            accountId = "acc-hdfc-2434",
            paymentMethod = "Credit Card",
            note = "SMS from VK-HDFCBK",
            source = "SMS",
            transactionReference = "2026082310083700756",
            originalReference = "SMS-REF-2026082310083700756",
            last4Digits = "2434"
        )
        val (ingested2, status2) = com.example.utils.TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = tx2,
            rawText = "HDFC Card: Rs 899.00 spent on Card XX2434 at Jio. Ref 2026082310083700756"
        )
        // Must be detected as duplicate!
        assertEquals(com.example.utils.IngestionStatus.DUPLICATE, status2)

        // Verify that database only contains 1 transaction
        val allTxs = db.kharchaDao().getAllTransactionsSync()
        assertEquals(1, allTxs.size)
        assertEquals(899.0, allTxs[0].amount, 0.01)
        assertEquals("2026082310083700756", com.example.utils.TransactionIngestionEngine.extractNormalizedReference(allTxs[0].transactionReference))

        AppDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testDuplicateCrossSourceSmsNotificationEmail() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.setTestInstance(db)

        val acc = com.example.data.entity.AccountEntity("acc-hdfc", "HDFC Bank", "Bank Account", bankName = "HDFC", last4Digits = "1234")
        db.kharchaDao().insertAccount(acc)

        val sharedRef = "UPI9876543210"

        // 1. Transaction from SMS
        val smsTx = com.example.data.entity.TransactionEntity(
            id = "tx-sms-1",
            type = "EXPENSE",
            amount = 450.0,
            date = "2026-09-24",
            time = "12:30",
            merchant = "Swiggy",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "SMS alert",
            source = "SMS",
            transactionReference = sharedRef,
            originalReference = "SMS-REF-$sharedRef",
            last4Digits = "1234"
        )
        val (_, status1) = com.example.utils.TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = smsTx,
            rawText = "Paid Rs 450 to Swiggy UPI Ref $sharedRef"
        )
        assertEquals(com.example.utils.IngestionStatus.IMPORTED, status1)

        // 2. Same transaction from Notification
        val notifTx = com.example.data.entity.TransactionEntity(
            id = "tx-notif-1",
            type = "EXPENSE",
            amount = 450.0,
            date = "2026-09-24",
            time = "12:30",
            merchant = "Swiggy",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "Notification from Google Pay",
            source = "NOTIFICATION",
            transactionReference = sharedRef,
            originalReference = "NOTIF-REF-$sharedRef",
            last4Digits = "1234"
        )
        val (_, status2) = com.example.utils.TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = notifTx,
            rawText = "Google Pay: Paid Rs 450 to Swiggy UPI Ref $sharedRef"
        )
        assertEquals(com.example.utils.IngestionStatus.DUPLICATE, status2)

        // 3. Same transaction from Email
        val emailTx = com.example.data.entity.TransactionEntity(
            id = "tx-email-1",
            type = "EXPENSE",
            amount = 450.0,
            date = "2026-09-24",
            time = "12:30",
            merchant = "Swiggy",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "Email receipt",
            source = "EMAIL",
            transactionReference = sharedRef,
            originalReference = "EMAIL-REF-$sharedRef",
            last4Digits = "1234"
        )
        val (_, status3) = com.example.utils.TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = emailTx,
            rawText = "Swiggy order confirmed Rs 450 UPI Ref $sharedRef"
        )
        assertEquals(com.example.utils.IngestionStatus.DUPLICATE, status3)

        // Total records must be exactly 1
        val allTxs = db.kharchaDao().getAllTransactionsSync()
        assertEquals(1, allTxs.size)

        AppDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testDifferentReferenceIdsSameAmountNotDuplicate() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.setTestInstance(db)

        val acc = com.example.data.entity.AccountEntity("acc-hdfc", "HDFC Bank", "Bank Account", bankName = "HDFC", last4Digits = "1234")
        db.kharchaDao().insertAccount(acc)

        // Two distinct chai payments of ₹20 on the same date with different UTRs
        val txA = com.example.data.entity.TransactionEntity(
            id = "tx-tea-1",
            type = "EXPENSE",
            amount = 20.0,
            date = "2026-09-24",
            time = "09:00",
            merchant = "Chai Point",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "Morning tea",
            source = "SMS",
            transactionReference = "REF999111222",
            originalReference = "SMS-REF-REF999111222",
            last4Digits = "1234"
        )
        val txB = com.example.data.entity.TransactionEntity(
            id = "tx-tea-2",
            type = "EXPENSE",
            amount = 20.0,
            date = "2026-09-24",
            time = "16:00",
            merchant = "Chai Point",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "Evening tea",
            source = "SMS",
            transactionReference = "REF888333444",
            originalReference = "SMS-REF-REF888333444",
            last4Digits = "1234"
        )

        val (_, statusA) = com.example.utils.TransactionIngestionEngine.ingestTransaction(context, txA, "Chai Point Rs 20 Ref REF999111222")
        val (_, statusB) = com.example.utils.TransactionIngestionEngine.ingestTransaction(context, txB, "Chai Point Rs 20 Ref REF888333444")

        assertEquals(com.example.utils.IngestionStatus.IMPORTED, statusA)
        assertEquals(com.example.utils.IngestionStatus.IMPORTED, statusB)

        // Must keep both transactions
        val allTxs = db.kharchaDao().getAllTransactionsSync()
        assertEquals(2, allTxs.size)

        AppDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testCleanupDuplicateTransactionsMigration() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        // Manually insert duplicate records into database before cleanup
        val tx1 = com.example.data.entity.TransactionEntity(
            id = "tx-dup-1",
            type = "EXPENSE",
            amount = 899.0,
            date = "2026-08-23",
            time = "10:08",
            merchant = "Unknown Merchant",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "acc-hdfc-4092",
            paymentMethod = "Bank Transfer",
            note = "Old record",
            source = "SMS",
            transactionReference = "2026082310083700756",
            originalReference = "SMS-REF-2026082310083700756",
            last4Digits = ""
        )
        val tx2 = com.example.data.entity.TransactionEntity(
            id = "tx-dup-2",
            type = "EXPENSE",
            amount = 899.0,
            date = "2026-08-23",
            time = "10:08",
            merchant = "Jio",
            categoryId = "cat-recharge",
            subcategoryId = "sub-prepaid",
            accountId = "acc-hdfc-2434",
            paymentMethod = "Credit Card",
            note = "Detailed record",
            source = "SMS",
            transactionReference = "2026082310083700756",
            originalReference = "SMS-REF-2026082310083700756",
            last4Digits = "2434"
        )
        db.kharchaDao().insertTransaction(tx1)
        db.kharchaDao().insertTransaction(tx2)

        assertEquals(2, db.kharchaDao().getAllTransactionsSync().size)

        // Run cleanup
        val removed = com.example.utils.TransactionIngestionEngine.cleanupDuplicateTransactions(db.kharchaDao())
        assertEquals(1, removed)

        // Verify remaining record has merged metadata
        val remaining = db.kharchaDao().getAllTransactionsSync()
        assertEquals(1, remaining.size)
        val surviving = remaining.first()
        assertEquals("Jio", surviving.merchant)
        assertEquals("cat-recharge", surviving.categoryId)
        assertEquals("sub-prepaid", surviving.subcategoryId)
        assertEquals("2434", surviving.last4Digits)
        assertEquals(899.0, surviving.amount, 0.01)

        db.close()
    }

    @Test
    fun testMultipleCardsAndAccountsUnderOneBank() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = db.kharchaDao()

        // 1. Create HDFC Bank Account • 4092
        val hdfcBank = com.example.data.entity.AccountEntity(
            id = "acc-hdfc-4092",
            name = "HDFC Bank Salary",
            type = "Bank Account",
            bankName = "HDFC Bank",
            last4Digits = "4092"
        )
        // 2. Create HDFC Credit Card • 2434
        val hdfcCard1 = com.example.data.entity.AccountEntity(
            id = "acc-hdfc-2434",
            name = "HDFC Millennia Credit Card",
            type = "Credit Card",
            bankName = "HDFC Bank",
            last4Digits = "2434"
        )
        // 3. Create HDFC Credit Card • 5678
        val hdfcCard2 = com.example.data.entity.AccountEntity(
            id = "acc-hdfc-5678",
            name = "HDFC Regalia Credit Card",
            type = "Credit Card",
            bankName = "HDFC Bank",
            last4Digits = "5678"
        )
        // 4. Create Cash in Hand
        val cashAcc = com.example.data.entity.AccountEntity(
            id = "acc-cash",
            name = "Cash in Hand",
            type = "Cash",
            bankName = "",
            last4Digits = ""
        )

        dao.insertAccount(hdfcBank)
        dao.insertAccount(hdfcCard1)
        dao.insertAccount(hdfcCard2)
        dao.insertAccount(cashAcc)

        val accounts = dao.getAllAccountsSync()
        assertEquals(4, accounts.size)

        // Verify grouping by bank name
        val hdfcAccounts = accounts.filter { it.bankName == "HDFC Bank" }
        assertEquals(3, hdfcAccounts.size)
        assertEquals(setOf("4092", "2434", "5678"), hdfcAccounts.map { it.last4Digits }.toSet())

        // Insert transactions for each payment instrument
        val txBank = com.example.data.entity.TransactionEntity(
            id = "tx-bank-1",
            type = "EXPENSE",
            amount = 15000.0,
            date = "2026-09-01",
            time = "10:00",
            merchant = "Rent Payment",
            categoryId = "cat-bills",
            subcategoryId = "",
            accountId = hdfcBank.id,
            paymentMethod = "Bank Transfer",
            note = "",
            source = "MANUAL",
            last4Digits = "4092",
            transactionReference = "REF-RENT-999"
        )
        val txCard1 = com.example.data.entity.TransactionEntity(
            id = "tx-card1-1",
            type = "EXPENSE",
            amount = 3200.0,
            date = "2026-09-05",
            time = "14:20",
            merchant = "Amazon",
            categoryId = "cat-shopping",
            subcategoryId = "",
            accountId = hdfcCard1.id,
            paymentMethod = "Credit Card",
            note = "",
            source = "MANUAL",
            last4Digits = "2434",
            transactionReference = "REF-AMZ-222"
        )
        val txCard2 = com.example.data.entity.TransactionEntity(
            id = "tx-card2-1",
            type = "EXPENSE",
            amount = 4500.0,
            date = "2026-09-10",
            time = "19:00",
            merchant = "Flight Booking",
            categoryId = "cat-travel",
            subcategoryId = "",
            accountId = hdfcCard2.id,
            paymentMethod = "Credit Card",
            note = "",
            source = "MANUAL",
            last4Digits = "5678",
            transactionReference = "REF-FLIGHT-333"
        )
        val txCash = com.example.data.entity.TransactionEntity(
            id = "tx-cash-1",
            type = "EXPENSE",
            amount = 120.0,
            date = "2026-09-15",
            time = "11:30",
            merchant = "Local Grocery",
            categoryId = "cat-groceries",
            subcategoryId = "",
            accountId = cashAcc.id,
            paymentMethod = "Cash",
            note = "",
            source = "MANUAL",
            last4Digits = "",
            transactionReference = ""
        )

        dao.insertTransaction(txBank)
        dao.insertTransaction(txCard1)
        dao.insertTransaction(txCard2)
        dao.insertTransaction(txCash)

        val allTxs = dao.getAllTransactionsSync()
        assertEquals(4, allTxs.size)

        // Test Account Filtering: Card 1 (Millennia • 2434)
        val card1Txs = allTxs.filter { it.accountId == hdfcCard1.id }
        assertEquals(1, card1Txs.size)
        assertEquals("Amazon", card1Txs.first().merchant)
        assertEquals("2434", card1Txs.first().last4Digits)
        assertEquals("Credit Card", card1Txs.first().paymentMethod)

        // Test Account Filtering: Card 2 (Regalia • 5678)
        val card2Txs = allTxs.filter { it.accountId == hdfcCard2.id }
        assertEquals(1, card2Txs.size)
        assertEquals("Flight Booking", card2Txs.first().merchant)
        assertEquals("5678", card2Txs.first().last4Digits)

        // Test Account Filtering: Bank Transfer (• 4092)
        val bankTxs = allTxs.filter { it.accountId == hdfcBank.id }
        assertEquals(1, bankTxs.size)
        assertEquals("Rent Payment", bankTxs.first().merchant)
        assertEquals("4092", bankTxs.first().last4Digits)

        // Test Account Filtering: Cash
        val cashTxs = allTxs.filter { it.accountId == cashAcc.id }
        assertEquals(1, cashTxs.size)
        assertEquals("Local Grocery", cashTxs.first().merchant)
        assertEquals("Cash", cashTxs.first().paymentMethod)

        db.close()
    }

    @Test
    fun testSmartClassificationAndMerchantCleaning() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Jio recharge
        val resJio = com.example.utils.SmsParser.parseSms("1", "HDFCBK", "Rs 199.00 debited from A/C x4092 for Jio recharge Ref: UTR12345", System.currentTimeMillis())
        assertTrue(resJio is com.example.utils.SmsParser.SmsParseStatus.Success)
        val txJio = (resJio as com.example.utils.SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", txJio.type)
        assertEquals("Jio", txJio.merchant)
        assertEquals("cat-recharge", txJio.categoryId)
        assertEquals(199.0, txJio.amount, 0.01)

        // 2. Vi recharge
        val resVi = com.example.utils.SmsParser.parseSms("2", "AXISBK", "Paid Rs. 299 for Vi recharge via UPI", System.currentTimeMillis())
        assertTrue(resVi is com.example.utils.SmsParser.SmsParseStatus.Success)
        val txVi = (resVi as com.example.utils.SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", txVi.type)
        assertEquals("Vi", txVi.merchant)
        assertEquals("cat-recharge", txVi.categoryId)

        // 3. Amazon purchase
        val resAmazon = com.example.utils.SmsParser.parseSms("3", "SBIBK", "Rs 1,499.00 spent on Amazon purchase using card x2434", System.currentTimeMillis())
        assertTrue(resAmazon is com.example.utils.SmsParser.SmsParseStatus.Success)
        val txAmazon = (resAmazon as com.example.utils.SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", txAmazon.type)
        assertEquals("Amazon", txAmazon.merchant)
        assertEquals("cat-shopping", txAmazon.categoryId)

        // 4. Flipkart purchase
        val resFlipkart = com.example.utils.SmsParser.parseSms("4", "HDFCBK", "Debited Rs. 2500 for Flipkart purchase", System.currentTimeMillis())
        assertTrue(resFlipkart is com.example.utils.SmsParser.SmsParseStatus.Success)
        val txFlipkart = (resFlipkart as com.example.utils.SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", txFlipkart.type)
        assertEquals("Flipkart", txFlipkart.merchant)
        assertEquals("cat-shopping", txFlipkart.categoryId)

        // 5. Electricity bill
        val resElec = com.example.utils.SmsParser.parseSms("5", "BESCOM", "Rs 850 paid for electricity bill", System.currentTimeMillis())
        assertTrue(resElec is com.example.utils.SmsParser.SmsParseStatus.Success)
        val txElec = (resElec as com.example.utils.SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", txElec.type)
        assertEquals("cat-bills", txElec.categoryId)
        assertEquals("sub-b1", txElec.subcategoryId)

        // 6. Salary credit
        val resSalary = com.example.utils.SmsParser.parseSms("6", "HDFCBK", "Your A/C x4092 is credited with INR 75,000.00 by SALARY CORP", System.currentTimeMillis())
        assertTrue(resSalary is com.example.utils.SmsParser.SmsParseStatus.Success)
        val txSalary = (resSalary as com.example.utils.SmsParser.SmsParseStatus.Success).transaction
        assertEquals("INCOME", txSalary.type)
        assertEquals("cat-salary", txSalary.categoryId)
        assertEquals(75000.0, txSalary.amount, 0.01)

        // 7. Bank transfer received
        val resTransfer = com.example.utils.SmsParser.parseSms("7", "HDFCBK", "Amount Rs 5,000 received via bank transfer", System.currentTimeMillis())
        assertTrue(resTransfer is com.example.utils.SmsParser.SmsParseStatus.Success)
        val txTransfer = (resTransfer as com.example.utils.SmsParser.SmsParseStatus.Success).transaction
        assertEquals("INCOME", txTransfer.type)

        // 8. Refund received
        val resRefund = com.example.utils.SmsParser.parseSms("8", "AMAZON", "Refund of Rs 500 received from Amazon", System.currentTimeMillis())
        assertTrue(resRefund is com.example.utils.SmsParser.SmsParseStatus.Success)
        val txRefund = (resRefund as com.example.utils.SmsParser.SmsParseStatus.Success).transaction
        assertEquals("INCOME", txRefund.type)
        assertEquals("Amazon", txRefund.merchant)
        assertEquals("cat-refund", txRefund.categoryId)

        // 9. URL merchant cleaning
        val cleanedUrl = com.example.utils.SmsParser.cleanMerchantName("https://www.jio.com")
        assertEquals("Jio", cleanedUrl)

        // 10. Technical UPI token cleaning
        val cleanedUpi = com.example.utils.SmsParser.cleanMerchantName("UPI/JIO/987654")
        assertEquals("Jio", cleanedUpi)

        // 11. Unknown merchant fallback
        val unknown = com.example.utils.SmsParser.cleanMerchantName("https://")
        assertTrue(unknown == "Other" || unknown == "Unknown Merchant")

        // 12. Merchant Learning Engine test
        com.example.utils.MerchantLearningEngine.saveMapping(context, "TestMerchant", "cat-food", "sub-f2")
        val mapping = com.example.utils.MerchantLearningEngine.getMapping(context, "TestMerchant")
        assertEquals("cat-food", mapping?.first)
        assertEquals("sub-f2", mapping?.second)
    }

    @Test
    fun testCategoriesAndSubcategoriesRestorationAndExpansion() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = db.kharchaDao()

        try {
            // Run restore and expand
            com.example.data.DefaultCategoryData.restoreAndExpandCategoriesAndSubcategories(dao)

            val cats = dao.getAllCategoriesSync()
            val subs = dao.getAllSubcategoriesSync()

            // Verify categories exist
            val eduCat = cats.find { it.name.equals("Education", ignoreCase = true) }
            val repCat = cats.find { it.name.equals("Repair & Maintenance", ignoreCase = true) }

            assertTrue("Education category must exist", eduCat != null)
            assertTrue("Repair & Maintenance category must exist", repCat != null)

            assertEquals("📚", eduCat?.icon)
            assertEquals("🔧", repCat?.icon)

            // Verify subcategories
            val eduSubs = subs.filter { it.categoryId == eduCat?.id }
            val repSubs = subs.filter { it.categoryId == repCat?.id }

            assertTrue("Education must have at least 16 subcategories", eduSubs.size >= 16)
            assertTrue("Repair & Maintenance must have at least 24 subcategories", repSubs.size >= 24)

            val eduSubNames = eduSubs.map { it.name }
            assertTrue(eduSubNames.contains("School Fees"))
            assertTrue(eduSubNames.contains("Tuition Fees"))
            assertTrue(eduSubNames.contains("College Fees"))
            assertTrue(eduSubNames.contains("Books"))
            assertTrue(eduSubNames.contains("Stationery"))

            val repSubNames = repSubs.map { it.name }
            assertTrue(repSubNames.contains("Bike Repair"))
            assertTrue(repSubNames.contains("Car Repair"))
            assertTrue(repSubNames.contains("AC Repair"))
            assertTrue(repSubNames.contains("Mobile Repair"))
            assertTrue(repSubNames.contains("Plumbing Repair"))

            // Verify idempotency: run again
            com.example.data.DefaultCategoryData.restoreAndExpandCategoriesAndSubcategories(dao)
            val catsAfter = dao.getAllCategoriesSync()
            val subsAfter = dao.getAllSubcategoriesSync()

            assertEquals(cats.size, catsAfter.size)
            assertEquals(subs.size, subsAfter.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun testPromotionalMessagesAreIgnoredAndGenuineTransactionsAreImported() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.setTestInstance(db)
        val dao = db.kharchaDao()

        try {
            // 1. Promotional SMS with amount should be completely ignored
            val promoSms1 = "Flash Sale Alert! Get flat Rs.25 on wallet load via credit card. Use code: BONUS HOUR. Limited Period Offer. Add Now..."
            val parseStatus1 = com.example.utils.SmsParser.parseSms("sms-p1", "MOBIKWIK", promoSms1, System.currentTimeMillis())
            assertEquals(com.example.utils.SmsParser.SmsParseStatus.Ignored, parseStatus1)

            // 2. Another promotional offer with amount
            val promoSms2 = "Get flat Rs.25 on wallet load via credit card"
            val parseStatus2 = com.example.utils.SmsParser.parseSms("sms-p2", "WALLET", promoSms2, System.currentTimeMillis())
            assertEquals(com.example.utils.SmsParser.SmsParseStatus.Ignored, parseStatus2)

            // 3. Loan offer with amount
            val promoSms3 = "Special Loan Offer! Pre-approved personal loan of Rs. 5,00,000 at low interest. Apply Now: https://bit.ly/loan"
            val parseStatus3 = com.example.utils.SmsParser.parseSms("sms-p3", "LOANCO", promoSms3, System.currentTimeMillis())
            assertEquals(com.example.utils.SmsParser.SmsParseStatus.Ignored, parseStatus3)

            // 4. Recharge offer with promo code
            val promoSms4 = "Recharge Offer! Get flat Rs.30 cashback on recharge. Use code FLAT30. Recharge Now"
            val parseStatus4 = com.example.utils.SmsParser.parseSms("sms-p4", "JIO", promoSms4, System.currentTimeMillis())
            assertEquals(com.example.utils.SmsParser.SmsParseStatus.Ignored, parseStatus4)

            // 5. Genuine Debit Transaction with Rs.25 -> must be successfully parsed as EXPENSE
            val realDebitSms = "Rs.25 debited from your bank account for UPI payment to ABC Store Ref 12345"
            val parseDebit = com.example.utils.SmsParser.parseSms("sms-r1", "HDFCBK", realDebitSms, System.currentTimeMillis())
            assertTrue(parseDebit is com.example.utils.SmsParser.SmsParseStatus.Success)
            val debitTx = (parseDebit as com.example.utils.SmsParser.SmsParseStatus.Success).transaction
            assertEquals("EXPENSE", debitTx.type)
            assertEquals(25.0, debitTx.amount, 0.01)

            // Ingest real debit
            dao.insertAccount(com.example.data.entity.AccountEntity("acc-hdfc", "HDFC Bank", "Bank Account", "HDFC", "1234", isDefault = true))
            val (ingestedDebit, statusDebit) = com.example.utils.TransactionIngestionEngine.ingestTransaction(context, debitTx, realDebitSms)
            assertNotNull(ingestedDebit)
            assertEquals(com.example.utils.IngestionStatus.IMPORTED, statusDebit)

            // 6. Genuine Credit Transaction with Rs.25 -> must be successfully parsed as INCOME
            val realCreditSms = "Rs.25 credited to your bank account"
            val parseCredit = com.example.utils.SmsParser.parseSms("sms-r2", "HDFCBK", realCreditSms, System.currentTimeMillis())
            assertTrue(parseCredit is com.example.utils.SmsParser.SmsParseStatus.Success)
            val creditTx = (parseCredit as com.example.utils.SmsParser.SmsParseStatus.Success).transaction
            assertEquals("INCOME", creditTx.type)
            assertEquals(25.0, creditTx.amount, 0.01)

            // 7. Genuine UPI payment of Rs.25 was successful -> must be parsed as EXPENSE
            val realUpiSms = "Your UPI payment of Rs.25 to ABC Store was successful. UPI Ref 998877"
            val parseUpi = com.example.utils.SmsParser.parseSms("sms-r3", "HDFCBK", realUpiSms, System.currentTimeMillis())
            assertTrue(parseUpi is com.example.utils.SmsParser.SmsParseStatus.Success)
            val upiTx = (parseUpi as com.example.utils.SmsParser.SmsParseStatus.Success).transaction
            assertEquals("EXPENSE", upiTx.type)
            assertEquals(25.0, upiTx.amount, 0.01)

            // Verify total transactions saved in DB is only the genuine one
            val allTxs = dao.getAllTransactionsSync()
            assertEquals(1, allTxs.size)
            assertEquals(25.0, allTxs[0].amount, 0.01)
        } finally {
            db.close()
            AppDatabase.setTestInstance(null)
        }
    }

    @Test
    fun testDashboardAndReportsConsistency() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = db.kharchaDao()

        // 1. Seed Categories
        val foodCat = com.example.data.entity.CategoryEntity("cat-food", "Food", "Food Hindi", "🍔", "#F97316", isDefault = true, isActive = true, isIncome = false, createdAt = "", updatedAt = "")
        val salaryCat = com.example.data.entity.CategoryEntity("cat-salary", "Salary", "Salary Hindi", "💰", "#059669", isDefault = true, isActive = true, isIncome = true, createdAt = "", updatedAt = "")
        dao.insertCategory(foodCat)
        dao.insertCategory(salaryCat)

        // 2. Seed Accounts
        val acc1 = com.example.data.entity.AccountEntity("acc-1", "HDFC Bank", "Bank Account", bankName = "HDFC", last4Digits = "1234")
        dao.insertAccount(acc1)

        // 3. Seed Transactions
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).format(java.util.Date())
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -10)
        val tenDaysAgo = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).format(cal.time)

        val txToday = com.example.data.entity.TransactionEntity(
            id = "tx-today", type = "EXPENSE", amount = 500.0, date = today, time = "12:00",
            merchant = "McDonalds", categoryId = "cat-food", subcategoryId = "", accountId = "acc-1", paymentMethod = "UPI", note = "", source = "MANUAL", transactionReference = "REF1"
        )
        val txOld = com.example.data.entity.TransactionEntity(
            id = "tx-old", type = "EXPENSE", amount = 1000.0, date = tenDaysAgo, time = "12:00",
            merchant = "Amazon", categoryId = "cat-food", subcategoryId = "", accountId = "acc-1", paymentMethod = "UPI", note = "", source = "MANUAL", transactionReference = "REF2"
        )
        val incomeToday = com.example.data.entity.TransactionEntity(
            id = "tx-income", type = "INCOME", amount = 5000.0, date = today, time = "09:00",
            merchant = "Company", categoryId = "cat-salary", subcategoryId = "", accountId = "acc-1", paymentMethod = "Transfer", note = "", source = "MANUAL", transactionReference = "REF3"
        )

        dao.insertTransaction(txToday)
        dao.insertTransaction(txOld)
        dao.insertTransaction(incomeToday)

        val allTxs = dao.getAllTransactionsSync()
        
        // 4. Test Date Filtering Logic (simulating DateFilterUtils)
        val todayTxs = com.example.utils.DateFilterUtils.filterByRange(allTxs, "TODAY", null, null)
        assertEquals(2, todayTxs.size)
        assertEquals(500.0, todayTxs.filter { it.type == "EXPENSE" }.sumOf { it.amount }, 0.01)
        assertEquals(5000.0, todayTxs.filter { it.type == "INCOME" }.sumOf { it.amount }, 0.01)

        val weekTxs = com.example.utils.DateFilterUtils.filterByRange(allTxs, "THIS_WEEK", null, null)
        // tx-today is definitely in this week, tx-old (10 days ago) is not.
        assertTrue(weekTxs.any { it.id == "tx-today" })
        assertTrue(weekTxs.none { it.id == "tx-old" })

        // 5. Test Totals Consistency
        val expenseTxs = todayTxs.filter { it.type == "EXPENSE" }
        val categoryTotals = expenseTxs.groupBy { it.categoryId }.mapValues { it.value.sumOf { tx -> tx.amount } }
        assertEquals(500.0, categoryTotals["cat-food"] ?: 0.0, 0.01)

        val merchantTotals = expenseTxs.groupBy { it.merchant }.mapValues { it.value.sumOf { tx -> tx.amount } }
        assertEquals(500.0, merchantTotals["McDonalds"] ?: 0.0, 0.01)

        db.close()
    }

    @Test
    fun testRealWorldCreditCardIdentityAndParenthesesResolution() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.setTestInstance(db)
        val dao = db.kharchaDao()

        try {
            // 1. "RBL Bank credit card (5412)" -> last4 = 5412
            val text1 = "Your RBL Bank credit card (5412) was used for INR 450.00 at Swiggy on 28-Sep-2026."
            val last4_1 = com.example.utils.TransactionIdentityResolver.extractLast4(text1)
            assertEquals("5412", last4_1)

            // Bank resolution receives and uses extracted last4 generically
            val bank1 = com.example.utils.TransactionIdentityResolver.extractBankName(text1.lowercase(), "RBL", last4_1)
            assertEquals("RBL Bank", bank1)

            // 2. Parenthesized card number does not cause unrelated 4-digit numbers to be extracted
            val textUnrelated1 = "Your OTP is (5412) for login. Do not share with anyone."
            assertEquals("", com.example.utils.TransactionIdentityResolver.extractLast4(textUnrelated1))

            val textUnrelated2 = "Spent INR 5412 at Store on 28-09-2026."
            assertEquals("", com.example.utils.TransactionIdentityResolver.extractLast4(textUnrelated2))

            val textUnrelated3 = "Ref no (5412) for transaction"
            assertEquals("", com.example.utils.TransactionIdentityResolver.extractLast4(textUnrelated3))

            // Verify variations: XXXX(5412), XX5412, x5412, card (5412)
            assertEquals("5412", com.example.utils.TransactionIdentityResolver.extractLast4("Credit card XXXX(5412)"))
            assertEquals("5412", com.example.utils.TransactionIdentityResolver.extractLast4("Credit card XX5412"))
            assertEquals("5412", com.example.utils.TransactionIdentityResolver.extractLast4("card ending in x5412"))
            assertEquals("5412", com.example.utils.TransactionIdentityResolver.extractLast4("card (5412)"))
            assertEquals("7008", com.example.utils.TransactionIdentityResolver.extractLast4("ICICI Bank Credit Card XX7008"))
            assertEquals("6926", com.example.utils.TransactionIdentityResolver.extractLast4("YES Bank Credit Card X6926"))
            assertEquals("8134", com.example.utils.TransactionIdentityResolver.extractLast4("Axis Bank Flipkart Visa Credit Card XX8134"))
            assertEquals("9546", com.example.utils.TransactionIdentityResolver.extractLast4("AU Bank Credit Card ending 9546"))

            // 3. Test Ingestion with duplicate where pending new AccountEntity is auto-created and persisted
            // First: seed existing transaction (e.g. from an SMS with no accountId yet)
            val initialTx = com.example.data.entity.TransactionEntity(
                id = "tx-rbl-sms",
                type = "EXPENSE",
                amount = 450.0,
                date = "2026-09-28",
                time = "10:30",
                merchant = "Swiggy",
                categoryId = "cat-food",
                subcategoryId = "",
                accountId = "",
                paymentMethod = "Credit Card",
                note = "",
                source = "SMS",
                transactionReference = "REF-RBL-12345",
                originalReference = "REF-RBL-12345"
            )
            dao.insertTransaction(initialTx)

            // Second: incoming duplicate from Email which has "RBL Bank credit card (5412)"
            val emailTx = com.example.data.entity.TransactionEntity(
                id = "tx-rbl-email",
                type = "EXPENSE",
                amount = 450.0,
                date = "2026-09-28",
                time = "10:31",
                merchant = "Swiggy",
                categoryId = "cat-food",
                subcategoryId = "",
                accountId = "",
                paymentMethod = "Credit Card",
                note = "Order delivered",
                source = "EMAIL",
                transactionReference = "REF-RBL-12345",
                originalReference = "REF-RBL-12345"
            )
            val (mergedTx, status) = com.example.utils.TransactionIngestionEngine.ingestTransaction(
                context,
                emailTx,
                rawText = text1
            )

            assertEquals(com.example.utils.IngestionStatus.DUPLICATE, status)
            assertNotNull(mergedTx)
            assertTrue("Merged transaction must have non-empty accountId", mergedTx!!.accountId.isNotEmpty())

            // Verify that the auto-created AccountEntity and CardEntity were persisted to Room database!
            val savedAccounts = dao.getAllAccountsSync()
            val savedCards = dao.getAllCardsSync()
            val persistedAcc = savedAccounts.find { it.id == mergedTx.accountId }
            assertNotNull("Auto-created AccountEntity must be persisted in database on duplicate merge", persistedAcc)
            assertEquals("5412", persistedAcc?.last4Digits)
            assertTrue(persistedAcc?.name?.contains("RBL") == true)

            val persistedCard = savedCards.find { it.last4Digits == "5412" }
            assertNotNull("Auto-created CardEntity must be persisted in database on duplicate merge", persistedCard)

            // 4. Verify ambiguous/needsReview transaction does NOT silently persist account
            val accountsBefore = dao.getAllAccountsSync().size
            val ambiguousTx = com.example.data.entity.TransactionEntity(
                id = "tx-ambig",
                type = "EXPENSE",
                amount = 100.0,
                date = "2026-09-28",
                time = "11:00",
                merchant = "Test Store",
                categoryId = "cat-food",
                subcategoryId = "",
                accountId = "",
                paymentMethod = "",
                note = "",
                source = "SMS",
                transactionReference = "REF-AMBIG-1"
            )
            val (resAmbig, statusAmbig) = com.example.utils.TransactionIngestionEngine.ingestTransaction(
                context,
                ambiguousTx,
                rawText = "Paid 100 at Store."
            )
            assertEquals(com.example.utils.IngestionStatus.NEEDS_REVIEW, statusAmbig)
            assertEquals(accountsBefore, dao.getAllAccountsSync().size)
        } finally {
            db.close()
            AppDatabase.setTestInstance(null)
        }
    }
}


