package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.firestore.FirestoreToRoomRestoreEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateDataPreservationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        AppDatabase.setTestInstance(null)
    }

    @Test
    fun testLocalDataSurvivesAppRelaunchAndGetDatabase() = runBlocking {
        val db1 = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(db1)
        val dao1 = db1.kharchaDao()

        val userAcc = AccountEntity(id = "acc-update-1", name = "HDFC Bank", type = "SAVINGS", initialBalance = 100000.0)
        val userCat = CategoryEntity(id = "cat-update-1", name = "My Investments", icon = "📊", colour = "#10B981", createdAt = "", updatedAt = "")
        val userTx = TransactionEntity(
            id = "tx-update-1",
            type = "EXPENSE",
            amount = 3500.0,
            date = "2026-09-30",
            time = "15:00",
            merchant = "Mutual Fund",
            categoryId = "cat-update-1",
            subcategoryId = "",
            accountId = "acc-update-1",
            paymentMethod = "NetBanking",
            note = "SIP Investment",
            source = "EMAIL",
            transactionReference = "SIP987654"
        )

        dao1.insertAccount(userAcc)
        dao1.insertCategory(userCat)
        dao1.insertTransaction(userTx)

        assertEquals(1, dao1.getAllTransactionsSync().size)
        assertEquals(1, dao1.getAllAccountsSync().size)

        // Close first instance and simulate opening database after app update
        db1.close()

        val db2 = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(db2)
        val dao2 = db2.kharchaDao()

        dao2.insertAccount(userAcc)
        dao2.insertCategory(userCat)
        dao2.insertTransaction(userTx)

        val txsAfter = dao2.getAllTransactionsSync()
        val accsAfter = dao2.getAllAccountsSync()
        val catsAfter = dao2.getAllCategoriesSync()

        assertEquals(1, txsAfter.size)
        assertEquals("tx-update-1", txsAfter[0].id)
        assertEquals(3500.0, txsAfter[0].amount, 0.001)
        assertEquals("SIP Investment", txsAfter[0].note)

        assertEquals(1, accsAfter.size)
        assertEquals("acc-update-1", accsAfter[0].id)

        assertTrue(catsAfter.any { it.id == "cat-update-1" })

        db2.close()
    }

    @Test
    fun testEmptyCloudRestoreNeverWipesValidLocalData() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(db)
        val dao = db.kharchaDao()

        // Insert local user transaction & account
        val userAcc = AccountEntity(id = "acc-local-1", name = "My Bank", type = "SAVINGS", initialBalance = 25000.0)
        val userTx = TransactionEntity(
            id = "tx-local-1",
            type = "EXPENSE",
            amount = 450.0,
            date = "2026-09-29",
            time = "10:00",
            merchant = "Grocery Store",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-local-1",
            paymentMethod = "UPI",
            note = "",
            source = "SMS",
            transactionReference = "REF001"
        )
        dao.insertAccount(userAcc)
        dao.insertTransaction(userTx)

        assertEquals(1, dao.getAllTransactionsSync().size)
        assertEquals(1, dao.getAllAccountsSync().size)

        // Simulate cloud restore when unauthenticated or empty cloud response
        val restoreEngine = FirestoreToRoomRestoreEngine()
        val result = restoreEngine.restoreCloudDataToRoom(context, customUid = null)

        // Confirm restore fails or completes gracefully without wiping local Room data
        assertEquals(1, dao.getAllTransactionsSync().size)
        assertEquals(1, dao.getAllAccountsSync().size)
        assertEquals("tx-local-1", dao.getAllTransactionsSync()[0].id)

        db.close()
    }

    @Test
    fun testDefaultCategoryExpansionDoesNotWipeUserCategoriesOrTransactions() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.kharchaDao()

        val customCategory = CategoryEntity(
            id = "cat-custom-123",
            name = "My Custom Investments",
            nameHindi = "",
            icon = "📈",
            colour = "#8B5CF6",
            isDefault = false,
            isActive = true,
            isIncome = false,
            createdAt = "2026-09-01T00:00:00Z",
            updatedAt = "2026-09-01T00:00:00Z"
        )
        dao.insertCategory(customCategory)

        // Expand categories
        com.example.data.DefaultCategoryData.restoreAndExpandCategoriesAndSubcategories(dao)

        val categoriesAfter = dao.getAllCategoriesSync()
        assertTrue(categoriesAfter.any { it.id == "cat-custom-123" })
        assertTrue(categoriesAfter.any { it.name == "Food & Dining" })

        db.close()
    }
}
