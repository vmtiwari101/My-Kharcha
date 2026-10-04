package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.TransactionEntity
import com.example.viewmodel.KharchaViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MonthlyBudgetTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var app: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context as Application
        // Clear prefs before test
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(db)
    }

    @After
    fun tearDown() {
        AppDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testDefaultMonthlyBudgetIs45000() {
        val vm = KharchaViewModel(app)
        assertEquals(45000.0, vm.getMonthlyBudget("2026-09"), 0.01)
        assertEquals(45000.0, vm.getMonthlyBudget("2026-10"), 0.01)
    }

    @Test
    fun testSetMonthlyBudgetUpdatesSelectedMonth() {
        val vm = KharchaViewModel(app)
        vm.setMonthlyBudget("2026-09", 52000.0)

        // September 2026 should be updated
        assertEquals(52000.0, vm.getMonthlyBudget("2026-09"), 0.01)
        // October 2026 should still be the default
        assertEquals(45000.0, vm.getMonthlyBudget("2026-10"), 0.01)
    }

    @Test
    fun testMultipleMonthsHaveIndependentBudgets() {
        val vm = KharchaViewModel(app)
        vm.setMonthlyBudget("2026-09", 50000.0)
        vm.setMonthlyBudget("2026-10", 60000.0)
        vm.setMonthlyBudget("2026-11", 40000.0)

        assertEquals(50000.0, vm.getMonthlyBudget("2026-09"), 0.01)
        assertEquals(60000.0, vm.getMonthlyBudget("2026-10"), 0.01)
        assertEquals(40000.0, vm.getMonthlyBudget("2026-11"), 0.01)
    }

    @Test
    fun testUpdatingExistingBudgetOverwritesWithoutDuplicates() {
        val vm = KharchaViewModel(app)
        vm.setMonthlyBudget("2026-09", 50000.0)
        assertEquals(50000.0, vm.getMonthlyBudget("2026-09"), 0.01)

        // Update to new amount
        vm.setMonthlyBudget("2026-09", 58000.0)
        assertEquals(58000.0, vm.getMonthlyBudget("2026-09"), 0.01)
    }

    @Test
    fun testInvalidBudgetAmountRejected() {
        val vm = KharchaViewModel(app)
        vm.setMonthlyBudget("2026-09", 50000.0)

        // Zero and negative should not overwrite
        vm.setMonthlyBudget("2026-09", 0.0)
        assertEquals(50000.0, vm.getMonthlyBudget("2026-09"), 0.01)

        vm.setMonthlyBudget("2026-09", -500.0)
        assertEquals(50000.0, vm.getMonthlyBudget("2026-09"), 0.01)
    }

    @Test
    fun testBudgetPersistenceAcrossViewModelInstances() {
        val vm1 = KharchaViewModel(app)
        vm1.setMonthlyBudget("2026-09", 75000.0)

        // Create new ViewModel instance simulating app reopen
        val vm2 = KharchaViewModel(app)
        assertEquals(75000.0, vm2.getMonthlyBudget("2026-09"), 0.01)
    }

    @Test
    fun testBudgetChangeDoesNotAffectTransactions() = runBlocking {
        val dao = db.kharchaDao()
        val tx = TransactionEntity(
            id = "test-tx-1",
            type = "EXPENSE",
            amount = 1500.0,
            date = "2026-09-15",
            time = "12:00",
            merchant = "Store",
            categoryId = "cat-shopping",
            subcategoryId = "",
            accountId = "",
            paymentMethod = "UPI",
            note = "Shoes",
            source = "MANUAL",
            transactionReference = "REF-1",
            originalReference = "",
            last4Digits = "",
            createdAt = "2026-09-15T12:00:00Z",
            updatedAt = "2026-09-15T12:00:00Z"
        )
        dao.insertTransaction(tx)

        val vm = KharchaViewModel(app)
        val initialTxs = vm.transactions.first()

        // Modify budget
        vm.setMonthlyBudget("2026-09", 80000.0)

        val txsAfterBudgetChange = dao.getAllTransactionsSync()
        assertEquals(1, txsAfterBudgetChange.size)
        assertEquals("test-tx-1", txsAfterBudgetChange[0].id)
        assertEquals(1500.0, txsAfterBudgetChange[0].amount, 0.01)
    }
}
