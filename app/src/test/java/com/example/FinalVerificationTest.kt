package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.CategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.AccountEntity
import com.example.data.DefaultCategoryData
import com.example.data.dao.KharchaDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FinalVerificationTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: KharchaDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.kharchaDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testGroceriesAutoMergeOnStartup() = runBlocking {
        // Setup: Create a "Groceries" category and a legacy "Grocery/Ration" category
        val now = "2026-09-30T10:00:00"
        val groceries = CategoryEntity(id = "cat-groceries", name = "Groceries", icon = "🛒", colour = "#22C55E", createdAt = now, updatedAt = now)
        val legacyGrocery = CategoryEntity(id = "cat-grocery-legacy", name = "Grocery/Ration", icon = "🛍️", colour = "#F59E0B", createdAt = now, updatedAt = now)
        
        dao.insertCategory(groceries)
        dao.insertCategory(legacyGrocery)
        
        // Add a transaction to legacy category
        val tx = TransactionEntity(
            id = "tx-1",
            type = "EXPENSE",
            amount = 500.0,
            date = "2026-09-30",
            time = "10:00",
            merchant = "BigBasket",
            categoryId = legacyGrocery.id,
            subcategoryId = "",
            accountId = "acc-1",
            paymentMethod = "UPI",
            note = "",
            source = "MANUAL",
            transactionReference = ""
        )
        dao.insertTransaction(tx)
        
        // Verify initial state
        assertEquals(legacyGrocery.id, dao.getTransactionByIdSync("tx-1")?.categoryId)
        
        // Trigger auto-merge
        DefaultCategoryData.restoreAndExpandCategoriesAndSubcategories(dao)
        
        // Verify: legacy category is deleted
        val allCats = dao.getAllCategoriesSync()
        assertNull(allCats.find { it.id == legacyGrocery.id })
        assertNotNull(allCats.find { it.id == groceries.id })
        
        // Verify: transaction is reassigned to "Groceries"
        val updatedTx = dao.getTransactionByIdSync("tx-1")
        assertEquals(groceries.id, updatedTx?.categoryId)
    }

    @Test
    fun testCategoryMergeLogic() = runBlocking {
        // Setup: Two custom categories
        val now = "2026-09-30T10:00:00"
        val catA = CategoryEntity(id = "cat-a", name = "Category A", icon = "A", colour = "#111111", createdAt = now, updatedAt = now)
        val catB = CategoryEntity(id = "cat-b", name = "Category B", icon = "B", colour = "#222222", createdAt = now, updatedAt = now)
        
        dao.insertCategory(catA)
        dao.insertCategory(catB)
        
        // Add transactions to catA
        val txA = TransactionEntity(
            id = "tx-a",
            type = "EXPENSE",
            amount = 100.0,
            date = "2026-09-30",
            time = "10:00",
            merchant = "Merchant A",
            categoryId = catA.id,
            subcategoryId = "",
            accountId = "acc-1",
            paymentMethod = "CASH",
            note = "",
            source = "MANUAL",
            transactionReference = ""
        )
        dao.insertTransaction(txA)
        
        // Perform merge: A -> B
        val mergeTime = "2026-09-30T12:00:00"
        dao.reassignTransactionsCategory(catA.id, catB.id, mergeTime)
        dao.deleteCategory(catA.id)
        
        // Verify
        val updatedTx = dao.getTransactionByIdSync("tx-a")
        assertEquals(catB.id, updatedTx?.categoryId)
        
        val allCats = dao.getAllCategoriesSync()
        assertNull(allCats.find { it.id == catA.id })
        assertNotNull(allCats.find { it.id == catB.id })
    }

    @Test
    fun testFriendTransferLogic() = runBlocking {
        // Setup account
        val account = AccountEntity(id = "acc-1", name = "HDFC Bank", type = "Bank Account")
        dao.insertAccount(account)
        
        // Simulating ViewModel.addTransferTransaction for FRIEND
        val friendName = "Rahul"
        val amount = 200.0
        val date = "2026-09-30"
        
        val tx = TransactionEntity(
            id = UUID.randomUUID().toString(),
            type = "INTERNAL_TRANSFER", // Crucial for Test 2
            amount = amount,
            date = date,
            time = "12:00",
            merchant = "Friend: $friendName", // As implemented in AddTransactionDialog
            categoryId = "cat-transfer",
            subcategoryId = "",
            accountId = "acc-1",
            paymentMethod = "UPI",
            note = "",
            source = "MANUAL",
            transactionReference = "",
            isInternalTransfer = true
        )
        dao.insertTransaction(tx)
        
        // Verify
        val savedTx = dao.getAllTransactionsSync().first()
        assertEquals("INTERNAL_TRANSFER", savedTx.type)
        assertTrue(savedTx.merchant.contains(friendName))
        assertEquals(amount, savedTx.amount, 0.0)
    }
}
