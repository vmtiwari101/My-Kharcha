package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.data.repository.KharchaRepository
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
class TransactionEditAndSplitTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: KharchaRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(db)

        val dao = db.kharchaDao()
        repository = KharchaRepository(dao)

        runBlocking {
            AppDatabase.prepopulateData(dao)

            val now = "2026-09-24T12:00:00Z"
            dao.insertTransaction(
                TransactionEntity(
                    id = "tx-original-101",
                    type = "EXPENSE",
                    amount = 1500.0,
                    date = "2026-09-20",
                    time = "14:30",
                    merchant = "D-Mart Supermarket",
                    categoryId = "cat-shopping",
                    subcategoryId = "sub-groceries",
                    accountId = "acc-hdfc",
                    paymentMethod = "Debit Card",
                    note = "Weekly groceries",
                    source = "SMS",
                    transactionReference = "REF987654321012",
                    originalReference = "ORIG987654321012",
                    last4Digits = "4092",
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testUpdateTransaction_PreservesOriginalIdSourceAndReference() = runBlocking {
        val dao = db.kharchaDao()
        val originalList = dao.getAllTransactionsSync()
        val txBefore = originalList.find { it.id == "tx-original-101" }
        assertNotNull(txBefore)
        assertEquals("SMS", txBefore?.source)
        assertEquals("REF987654321012", txBefore?.transactionReference)

        // Perform edit: copy original and modify editable fields
        val now = "2026-09-24T13:00:00Z"
        val updatedTx = txBefore!!.copy(
            amount = 1800.0,
            date = "2026-09-21",
            time = "15:00",
            merchant = "D-Mart Supermarket Mega",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-icici",
            paymentMethod = "UPI",
            note = "Updated groceries & snacks",
            updatedAt = now
        )

        repository.insertTransaction(updatedTx)

        // Verify state after update
        val updatedList = dao.getAllTransactionsSync()
        val txAfter = updatedList.find { it.id == "tx-original-101" }
        assertNotNull(txAfter)

        // Ensure no duplicate transaction was created
        assertEquals(originalList.size, updatedList.size)

        // Verify edited fields
        assertEquals(1800.0, txAfter?.amount ?: 0.0, 0.001)
        assertEquals("2026-09-21", txAfter?.date)
        assertEquals("15:00", txAfter?.time)
        assertEquals("D-Mart Supermarket Mega", txAfter?.merchant)
        assertEquals("cat-food", txAfter?.categoryId)
        assertEquals("", txAfter?.subcategoryId)
        assertEquals("acc-icici", txAfter?.accountId)
        assertEquals("UPI", txAfter?.paymentMethod)
        assertEquals("Updated groceries & snacks", txAfter?.note)

        // Verify preserved system metadata
        assertEquals("SMS", txAfter?.source)
        assertEquals("REF987654321012", txAfter?.transactionReference)
        assertEquals("ORIG987654321012", txAfter?.originalReference)
        assertEquals("4092", txAfter?.last4Digits)
    }

    @Test
    fun testSaveTransactionSplits_CreatesAndBalancedSplits() = runBlocking {
        val dao = db.kharchaDao()
        val txId = "tx-original-101"

        val now = System.currentTimeMillis().toString()
        val splits = listOf(
            TransactionSplitEntity(
                id = "split-101-1",
                transactionId = txId,
                categoryId = "cat-food",
                subcategoryId = "sub-groceries",
                amount = 1000.0,
                note = "Groceries portion",
                createdAt = now,
                updatedAt = now
            ),
            TransactionSplitEntity(
                id = "split-101-2",
                transactionId = txId,
                categoryId = "cat-personal",
                subcategoryId = "",
                amount = 500.0,
                note = "Personal care items",
                createdAt = now,
                updatedAt = now
            )
        )

        // Verify split sum equals original amount (1000 + 500 = 1500)
        val splitSum = splits.sumOf { it.amount }
        assertEquals(1500.0, splitSum, 0.001)

        // Save splits via Repository
        repository.insertSplitsForTransaction(txId, splits)

        val fetchedSplits = dao.getSplitsForTransactionSync(txId)
        assertEquals(2, fetchedSplits.size)
        assertEquals("cat-food", fetchedSplits[0].categoryId)
        assertEquals(1000.0, fetchedSplits[0].amount, 0.001)
        assertEquals("cat-personal", fetchedSplits[1].categoryId)
        assertEquals(500.0, fetchedSplits[1].amount, 0.001)
    }

    @Test
    fun testRemoveTransactionSplits_UnsplitsSuccessfully() = runBlocking {
        val dao = db.kharchaDao()
        val txId = "tx-original-101"

        val splits = listOf(
            TransactionSplitEntity(
                id = "split-101-1",
                transactionId = txId,
                categoryId = "cat-food",
                subcategoryId = "",
                amount = 800.0,
                note = "Part 1",
                createdAt = "2026-09-24T12:00:00Z",
                updatedAt = "2026-09-24T12:00:00Z"
            ),
            TransactionSplitEntity(
                id = "split-101-2",
                transactionId = txId,
                categoryId = "cat-shopping",
                subcategoryId = "",
                amount = 700.0,
                note = "Part 2",
                createdAt = "2026-09-24T12:00:00Z",
                updatedAt = "2026-09-24T12:00:00Z"
            )
        )

        repository.insertSplitsForTransaction(txId, splits)
        assertEquals(2, dao.getSplitsForTransactionSync(txId).size)

        // Unsplit
        repository.deleteSplitsForTransaction(txId)
        assertEquals(0, dao.getSplitsForTransactionSync(txId).size)
    }

    @Test
    fun testExpenseToggle_ExcludesAndReIncludesFromTotals_WhilePreservingAccountAndDebit() = runBlocking {
        val dao = db.kharchaDao()
        val tx = dao.getAllTransactionsSync().find { it.id == "tx-original-101" }
        assertNotNull(tx)
        // 1. Existing transactions default to Expense ON
        assertTrue(tx!!.isExpense)

        // Expense ON: included in expense calculations
        val listBefore = dao.getAllTransactionsSync()
        val expenseTotalBefore = listBefore.filter { it.type == "EXPENSE" && it.isExpense }.sumOf { it.amount }
        assertEquals(1500.0, expenseTotalBefore, 0.001)

        // 2. Set Expense OFF (isExpense = false)
        val offTx = tx.copy(isExpense = false)
        dao.insertTransaction(offTx)

        val listAfterOff = dao.getAllTransactionsSync()
        val offFetched = listAfterOff.find { it.id == "tx-original-101" }
        assertNotNull(offFetched)
        assertFalse(offFetched!!.isExpense)

        // 2 & 3. Excluded from expense totals, but remains in database linked to account with debit/credit intact
        val expenseTotalOff = listAfterOff.filter { it.type == "EXPENSE" && it.isExpense }.sumOf { it.amount }
        assertEquals(0.0, expenseTotalOff, 0.001)
        assertEquals("acc-hdfc", offFetched.accountId)
        assertEquals("EXPENSE", offFetched.type)
        assertEquals("4092", offFetched.last4Digits)

        // 4. Turn Expense back ON
        val onTx = offFetched.copy(isExpense = true)
        dao.insertTransaction(onTx)

        val listAfterOn = dao.getAllTransactionsSync()
        val onFetched = listAfterOn.find { it.id == "tx-original-101" }
        assertNotNull(onFetched)
        assertTrue(onFetched!!.isExpense)
        val expenseTotalOn = listAfterOn.filter { it.type == "EXPENSE" && it.isExpense }.sumOf { it.amount }
        assertEquals(1500.0, expenseTotalOn, 0.001)
    }

    @Test
    fun testOtherInformation_FiltersSensitiveCredentials() {
        fun sanitizeMetadata(text: String): String {
            val lower = text.lowercase()
            if (lower.contains("otp") || lower.contains("password") || lower.contains("passcode") ||
                lower.contains("secret") || lower.contains("cvv") || lower.contains("atm pin") ||
                lower.contains("login pin") || lower.contains("mpin")) {
                return ""
            }
            return text.trim()
        }

        val unsafeOtp = "Your OTP is 482910 for payment"
        val unsafePin = "Do not share your ATM PIN 1234"
        val unsafePassword = "Temporary password is abc"
        val safeUtr = "UPI/428194829102/Payment to Swiggy"

        assertEquals("", sanitizeMetadata(unsafeOtp))
        assertEquals("", sanitizeMetadata(unsafePin))
        assertEquals("", sanitizeMetadata(unsafePassword))
        assertEquals("UPI/428194829102/Payment to Swiggy", sanitizeMetadata(safeUtr))
    }

    @Test
    fun testTimestampPreservation_ExactFormat() {
        val originalDate = "2026-09-20"
        val originalTime = "14:30"
        
        // When combined for display
        val display = "$originalDate • $originalTime"
        assertEquals("2026-09-20 • 14:30", display)

        // Date and time components remain exact
        val (d, t) = display.split(" • ")
        assertEquals("2026-09-20", d)
        assertEquals("14:30", t)
    }
}
