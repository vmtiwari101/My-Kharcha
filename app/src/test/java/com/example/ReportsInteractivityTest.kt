package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.TransactionEntity
import com.example.viewmodel.KharchaViewModel
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
class ReportsInteractivityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var viewModel: KharchaViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(db)
        runBlocking {
            val dao = db.kharchaDao()
            AppDatabase.prepopulateData(dao)

            // Add test transactions for September 2026
            val now = "2026-09-24T12:00:00Z"
            dao.insertTransaction(
                TransactionEntity(
                    id = "test-tx-inc-1",
                    type = "INCOME",
                    amount = 5000.0,
                    date = "2026-09-10",
                    time = "10:00",
                    merchant = "Freelance Client",
                    categoryId = "cat-business",
                    subcategoryId = "",
                    accountId = "acc-hdfc",
                    paymentMethod = "Bank Transfer",
                    note = "Project Payment",
                    source = "MANUAL",
                    transactionReference = "REF-BUS-1",
                    originalReference = "",
                    last4Digits = "4092",
                    createdAt = now,
                    updatedAt = now
                )
            )

            dao.insertTransaction(
                TransactionEntity(
                    id = "test-tx-sms-1",
                    type = "EXPENSE",
                    amount = 1200.0,
                    date = "2026-09-15",
                    time = "14:30",
                    merchant = "Supermarket",
                    categoryId = "cat-groceries",
                    subcategoryId = "sub-g1",
                    accountId = "acc-sbi",
                    paymentMethod = "Debit Card",
                    note = "Weekly groceries",
                    source = "SMS",
                    transactionReference = "REF-SMS-G1",
                    originalReference = "",
                    last4Digits = "1234",
                    createdAt = now,
                    updatedAt = now
                )
            )

            dao.insertTransaction(
                TransactionEntity(
                    id = "test-tx-notif-1",
                    type = "EXPENSE",
                    amount = 450.0,
                    date = "2026-09-20",
                    time = "18:00",
                    merchant = "Uber",
                    categoryId = "cat-transport",
                    subcategoryId = "sub-t4",
                    accountId = "acc-upi",
                    paymentMethod = "UPI",
                    note = "Ride",
                    source = "NOTIFICATION",
                    transactionReference = "REF-NOTIF-U1",
                    originalReference = "",
                    last4Digits = "",
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        viewModel = KharchaViewModel(context.applicationContext as android.app.Application)
    }

    @After
    fun tearDown() {
        AppDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testMonthSelectionFiltering() = runBlocking {
        viewModel.selectedMonth.value = "2026-09"
        val dao = db.kharchaDao()
        val allTxs = dao.getAllTransactionsSync()

        val sepTxs = allTxs.filter { it.date.startsWith("2026-09") }
        assertTrue("Should have September 2026 transactions", sepTxs.isNotEmpty())

        viewModel.selectedMonth.value = "2025-01"
        val janTxs = allTxs.filter { it.date.startsWith("2025-01") }
        assertTrue("Should have zero transactions for January 2025", janTxs.isEmpty())
    }

    @Test
    fun testIncomeSummaryData() = runBlocking {
        val month = "2026-09"
        val dao = db.kharchaDao()
        val allTxs = dao.getAllTransactionsSync()
        val monthTxs = allTxs.filter { it.date.startsWith(month) }
        val incomeTxs = monthTxs.filter { it.type == "INCOME" && !it.isInternalTransfer }

        assertTrue("Income transactions list must not be empty", incomeTxs.isNotEmpty())
        val totalIncome = incomeTxs.sumOf { it.amount }
        assertEquals(5000.0, totalIncome, 0.01)
    }

    @Test
    fun testExpenseSummaryData() = runBlocking {
        val month = "2026-09"
        val dao = db.kharchaDao()
        val allTxs = dao.getAllTransactionsSync()
        val monthTxs = allTxs.filter { it.date.startsWith(month) }
        val expenseTxs = monthTxs.filter { it.type == "EXPENSE" && !it.isInternalTransfer }

        assertTrue("Expense transactions list must not be empty", expenseTxs.isNotEmpty())
        val totalExpense = expenseTxs.sumOf { it.amount }
        assertTrue("Total expense should be greater than zero", totalExpense > 0)
    }

    @Test
    fun testCategorySpecificFiltering() = runBlocking {
        val month = "2026-09"
        val catId = "cat-groceries"
        val dao = db.kharchaDao()
        val allTxs = dao.getAllTransactionsSync()
        val monthExpenseTxs = allTxs.filter {
            it.date.startsWith(month) && it.type == "EXPENSE" && !it.isInternalTransfer
        }
        val groceryTxs = monthExpenseTxs.filter { it.categoryId == catId }

        assertTrue("Groceries transactions must exist for September 2026", groceryTxs.isNotEmpty())
        val totalGrocerySpend = groceryTxs.sumOf { it.amount }
        assertEquals(1200.0, totalGrocerySpend, 0.01)
    }

    @Test
    fun testAccountSpecificFiltering() = runBlocking {
        val month = "2026-09"
        val accId = "acc-hdfc"
        val dao = db.kharchaDao()
        val allTxs = dao.getAllTransactionsSync()
        val monthTxs = allTxs.filter {
            it.date.startsWith(month) && !it.isInternalTransfer
        }
        val hdfcTxs = monthTxs.filter { it.accountId == accId }

        assertTrue("HDFC transactions must exist", hdfcTxs.isNotEmpty())
        val last4 = hdfcTxs.firstOrNull { it.last4Digits.isNotEmpty() }?.last4Digits
        assertEquals("4092", last4)
    }

    @Test
    fun testSourceSpecificFiltering() = runBlocking {
        val month = "2026-09"
        val dao = db.kharchaDao()
        val allTxs = dao.getAllTransactionsSync()
        val monthTxs = allTxs.filter { it.date.startsWith(month) }

        val manualTxs = monthTxs.filter { it.source == "MANUAL" }
        val smsTxs = monthTxs.filter { it.source == "SMS" }
        val notifTxs = monthTxs.filter { it.source == "NOTIFICATION" }

        assertTrue("MANUAL transactions should exist", manualTxs.isNotEmpty())
        assertTrue("SMS transactions should exist", smsTxs.isNotEmpty())
        assertTrue("NOTIFICATION transactions should exist", notifTxs.isNotEmpty())
    }
}
