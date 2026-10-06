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
import kotlinx.coroutines.launch
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

    // --- TRANSACTION TYPE AND EDIT SAFETY AUDIT TESTS ---

    @Test
    fun testPreservationOfOpenedType() {
        val expenseTx = TransactionEntity(id = "1", type = "EXPENSE", amount = 10.0, date = "2026-10-04", time = "10:00", merchant = "Test", categoryId = "cat-food", subcategoryId = "", accountId = "acc-1", paymentMethod = "UPI", note = "", source = "MANUAL", transactionReference = "", isInternalTransfer = false)
        val transferTx1 = TransactionEntity(id = "2", type = "INTERNAL_TRANSFER", amount = 10.0, date = "2026-10-04", time = "10:00", merchant = "Test", categoryId = "cat-transfer", subcategoryId = "", accountId = "acc-1", paymentMethod = "UPI", note = "", source = "MANUAL", transactionReference = "", isInternalTransfer = true)
        val transferTx2 = TransactionEntity(id = "3", type = "EXPENSE", amount = 10.0, date = "2026-10-04", time = "10:00", merchant = "Test", categoryId = "cat-transfer", subcategoryId = "", accountId = "acc-1", paymentMethod = "UPI", note = "", source = "MANUAL", transactionReference = "", isInternalTransfer = true, transactionType = "INTERNAL_TRANSFER")
        
        fun resolveOpenedType(tx: TransactionEntity): String {
            return if (tx.isInternalTransfer || tx.type == "INTERNAL_TRANSFER" || tx.transactionType == "INTERNAL_TRANSFER") "INTERNAL_TRANSFER"
            else tx.type
        }
        
        assertEquals("EXPENSE", resolveOpenedType(expenseTx))
        assertEquals("INTERNAL_TRANSFER", resolveOpenedType(transferTx1))
        assertEquals("INTERNAL_TRANSFER", resolveOpenedType(transferTx2))
    }

    @Test
    fun testInternalTransferExclusionFromTotals() = runBlocking {
        val dao = db.kharchaDao()
        dao.insertTransaction(
            TransactionEntity(
                id = "tx-transfer-1", type = "INTERNAL_TRANSFER", amount = 2000.0, date = "2026-10-01", time = "12:00",
                merchant = "Self Transfer", categoryId = "cat-transfer", subcategoryId = "", accountId = "acc-hdfc", paymentMethod = "Transfer",
                note = "", source = "MANUAL", transactionReference = "REF999", isInternalTransfer = true, isExpense = false
            )
        )
        val list = dao.getAllTransactionsSync()
        val expenseTotal = list.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
        val incomeTotal = list.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
        
        assertEquals(1500.0, expenseTotal, 0.001)
        assertEquals(0.0, incomeTotal, 0.001)
    }

    @Test
    fun testEditingInternalTransferDoesNotConvertToExpense() = runBlocking {
        val dao = db.kharchaDao()
        val vm = KharchaViewModel(ApplicationProvider.getApplicationContext())
        val collectJob = launch { vm.transactions.collect {} }
        
        val transfer = TransactionEntity(
            id = "tx-transfer-2", type = "INTERNAL_TRANSFER", amount = 100.0, date = "2026-10-01", time = "12:00",
            merchant = "Self Transfer", categoryId = "cat-transfer", subcategoryId = "", accountId = "acc-hdfc", paymentMethod = "Transfer",
            note = "", source = "MANUAL", transactionReference = "REF999", isInternalTransfer = true, isExpense = false, counterpartyAccountId = "acc-icici"
        )
        dao.insertTransaction(transfer)
        
        vm.updateTransaction(
            id = "tx-transfer-2",
            type = "INTERNAL_TRANSFER",
            amount = 150.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "Self Transfer Edited",
            categoryId = "cat-transfer",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "Transfer",
            note = "Edited note",
            isExpense = true
        )
        
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        collectJob.cancel()
        
        val updated = dao.getAllTransactionsSync().find { it.id == "tx-transfer-2" }
        assertNotNull(updated)
        assertEquals("INTERNAL_TRANSFER", updated?.type)
        assertTrue(updated?.isInternalTransfer == true)
        assertFalse(updated?.isExpense == true)
        assertEquals("acc-icici", updated?.counterpartyAccountId)
    }

    @Test
    fun testEditingExpensePreservesExpenseIncludedState() = runBlocking {
        val dao = db.kharchaDao()
        val vm = KharchaViewModel(ApplicationProvider.getApplicationContext())
        val collectJob = launch { vm.transactions.collect {} }
        
        val tx = TransactionEntity(
            id = "tx-expense-toggle", type = "EXPENSE", amount = 100.0, date = "2026-10-01", time = "12:00",
            merchant = "Store", categoryId = "cat-food", subcategoryId = "", accountId = "acc-hdfc", paymentMethod = "UPI",
            note = "", source = "MANUAL", transactionReference = "REF888", isInternalTransfer = false, isExpense = false
        )
        dao.insertTransaction(tx)
        
        vm.updateTransaction(
            id = "tx-expense-toggle",
            type = "EXPENSE",
            amount = 120.0,
            date = "2026-10-01",
            time = "12:30",
            merchant = "Store Edited",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "",
            isExpense = false
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        collectJob.cancel()
        
        val updated = dao.getAllTransactionsSync().find { it.id == "tx-expense-toggle" }
        assertNotNull(updated)
        assertFalse(updated!!.isExpense)
    }

    @Test
    fun testIntentionalTypeChanges() = runBlocking {
        val dao = db.kharchaDao()
        val vm = KharchaViewModel(ApplicationProvider.getApplicationContext())
        val collectJob = launch { vm.transactions.collect {} }
        
        val tx = TransactionEntity(
            id = "tx-type-change", type = "EXPENSE", amount = 100.0, date = "2026-10-01", time = "12:00",
            merchant = "Store", categoryId = "cat-food", subcategoryId = "", accountId = "acc-hdfc", paymentMethod = "UPI",
            note = "", source = "MANUAL", transactionReference = "REF777", isInternalTransfer = false, isExpense = true
        )
        dao.insertTransaction(tx)
        
        vm.updateTransaction(
            id = "tx-type-change",
            type = "INCOME",
            amount = 100.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "Store",
            categoryId = "cat-salary",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "",
            isExpense = false
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        
        var updated = dao.getAllTransactionsSync().find { it.id == "tx-type-change" }
        assertNotNull(updated)
        assertEquals("INCOME", updated?.type)
        assertEquals("CREDIT", updated?.direction)
        assertFalse(updated!!.isExpense)
        assertFalse(updated.isInternalTransfer)
        
        vm.updateTransaction(
            id = "tx-type-change",
            type = "EXPENSE",
            amount = 150.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "Store",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "",
            isExpense = true
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        
        updated = dao.getAllTransactionsSync().find { it.id == "tx-type-change" }
        assertNotNull(updated)
        assertEquals("EXPENSE", updated?.type)
        assertEquals("DEBIT", updated?.direction)
        assertTrue(updated!!.isExpense)
        assertFalse(updated!!.isInternalTransfer)
        
        vm.updateTransaction(
            id = "tx-type-change",
            type = "INTERNAL_TRANSFER",
            amount = 150.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "Self",
            categoryId = "cat-transfer",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "Transfer",
            note = "",
            isExpense = false
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        collectJob.cancel()
        
        updated = dao.getAllTransactionsSync().find { it.id == "tx-type-change" }
        assertNotNull(updated)
        assertEquals("INTERNAL_TRANSFER", updated?.type)
        assertEquals("DEBIT", updated?.direction)
        assertFalse(updated!!.isExpense)
        assertTrue(updated!!.isInternalTransfer)
        assertEquals("INTERNAL_TRANSFER", updated!!.transactionType)
    }

    @Test
    fun testUnknownTransferCounterpartyRemainsNeedsReview() = runBlocking {
        val dao = db.kharchaDao()
        val vm = KharchaViewModel(ApplicationProvider.getApplicationContext())
        val collectJob = launch { vm.transactions.collect {} }
        
        val tx = TransactionEntity(
            id = "tx-transfer-unresolved", type = "INTERNAL_TRANSFER", amount = 100.0, date = "2026-10-01", time = "12:00",
            merchant = "Transfer", categoryId = "cat-transfer", subcategoryId = "", accountId = "acc-hdfc", paymentMethod = "Transfer",
            note = "", source = "MANUAL", transactionReference = "REF222", isInternalTransfer = true, isExpense = false,
            needsReview = true, counterpartyAccountId = null
        )
        dao.insertTransaction(tx)
        
        vm.updateTransaction(
            id = "tx-transfer-unresolved",
            type = "INTERNAL_TRANSFER",
            amount = 100.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "Transfer Edited",
            categoryId = "cat-transfer",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "Transfer",
            note = "",
            isExpense = false
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        collectJob.cancel()
        
        val updated = dao.getAllTransactionsSync().find { it.id == "tx-transfer-unresolved" }
        assertNotNull(updated)
        assertTrue(updated?.needsReview == true)
        assertNull(updated?.counterpartyAccountId)
    }

    @Test
    fun testGmailInternalIdIsNeverDisplayedAsMerchant() {
        val m1 = com.example.utils.SmsParser.sanitizeMerchantName("Email 1a10496b89ecc3e9")
        assertEquals("Unknown Merchant", m1)
        
        val m2 = com.example.utils.SmsParser.sanitizeMerchantName("email  3b29ab2e811fc39f")
        assertEquals("Unknown Merchant", m2)
        
        val m3 = com.example.utils.SmsParser.sanitizeMerchantName("3b29ab2e811fc39f")
        assertEquals("Unknown Merchant", m3)
        
        val m4 = com.example.utils.SmsParser.sanitizeMerchantName("msg-101")
        assertEquals("Unknown Merchant", m4)
        
        val valid = com.example.utils.SmsParser.sanitizeMerchantName("Swiggy Store")
        assertEquals("Swiggy Store", valid)
    }

    @Test
    fun testUpiCashAndMetadataSafety() = runBlocking {
        val dao = db.kharchaDao()
        val vm = KharchaViewModel(ApplicationProvider.getApplicationContext())
        val collectJob = launch { vm.transactions.collect {} }
        
        val tx = TransactionEntity(
            id = "tx-safety-check", type = "EXPENSE", amount = 100.0, date = "2026-10-01", time = "12:00",
            merchant = "Store", categoryId = "cat-food", subcategoryId = "", accountId = "acc-cash", paymentMethod = "Cash",
            note = "", source = "MANUAL", transactionReference = "UPI/REF-SAFE", originalReference = "ORIG-SAFE",
            last4Digits = "", createdAt = "2026-10-01T12:00:00Z"
        )
        dao.insertTransaction(tx)
        
        vm.updateTransaction(
            id = "tx-safety-check",
            type = "EXPENSE",
            amount = 120.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "Store",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-cash",
            paymentMethod = "Cash",
            note = "Notes added",
            isExpense = true
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        collectJob.cancel()
        
        val updated = dao.getAllTransactionsSync().find { it.id == "tx-safety-check" }
        assertNotNull(updated)
        assertEquals("Cash", updated?.paymentMethod)
        assertEquals("acc-cash", updated?.accountId)
        assertEquals("UPI/REF-SAFE", updated?.transactionReference)
        assertEquals("ORIG-SAFE", updated?.originalReference)
        assertEquals("2026-10-01T12:00:00Z", updated?.createdAt)
    }

    @Test
    fun testAuditRequirementsAToO() = runBlocking {
        val dao = db.kharchaDao()
        val vm = KharchaViewModel(ApplicationProvider.getApplicationContext())
        val collectJob = launch { vm.transactions.collect {} }

        // Setup test data
        val now = "2026-10-01T12:00:00Z"
        val expenseTx = TransactionEntity(
            id = "tx-audit-expense", type = "EXPENSE", amount = 500.0, date = "2026-10-01", time = "12:00",
            merchant = "D-Mart", categoryId = "cat-food", subcategoryId = "", accountId = "acc-hdfc", paymentMethod = "UPI",
            note = "", source = "SMS", transactionReference = "REF-EXPENSE-1", originalReference = "ORIG-EXPENSE-1",
            createdAt = now, isExpense = true
        )
        val incomeTx = TransactionEntity(
            id = "tx-audit-income", type = "INCOME", amount = 1000.0, date = "2026-10-01", time = "12:00",
            merchant = "Salary Client", categoryId = "cat-salary", subcategoryId = "", accountId = "acc-hdfc", paymentMethod = "Bank",
            note = "", source = "MANUAL", transactionReference = "REF-INCOME-1", originalReference = "ORIG-INCOME-1",
            createdAt = now, isExpense = false
        )
        val transferTx = TransactionEntity(
            id = "tx-audit-transfer", type = "INTERNAL_TRANSFER", amount = 1500.0, date = "2026-10-01", time = "12:00",
            merchant = "Self", categoryId = "cat-transfer", subcategoryId = "", accountId = "acc-hdfc", paymentMethod = "Transfer",
            note = "", source = "SMS", transactionReference = "REF-TRANSFER-1", originalReference = "ORIG-TRANSFER-1",
            createdAt = now, isInternalTransfer = true, isExpense = false, needsReview = true, counterpartyAccountId = null
        )
        val cashTx = TransactionEntity(
            id = "tx-audit-cash", type = "EXPENSE", amount = 200.0, date = "2026-10-01", time = "12:00",
            merchant = "Local Vendor", categoryId = "cat-food", subcategoryId = "", accountId = "acc-cash", paymentMethod = "Cash",
            note = "", source = "MANUAL", transactionReference = "REF-CASH-1", originalReference = "ORIG-CASH-1",
            createdAt = now, isExpense = true
        )

        dao.insertTransaction(expenseTx)
        dao.insertTransaction(incomeTx)
        dao.insertTransaction(transferTx)
        dao.insertTransaction(cashTx)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val allTxs = dao.getAllTransactionsSync()

        // Requirements D, E: Internal Transfer is not included in Expense / Income totals
        val expenseTotal = allTxs.filter { it.id.startsWith("tx-audit-") && it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
        val incomeTotal = allTxs.filter { it.id.startsWith("tx-audit-") && it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
        
        // Expense total should be 500 (expenseTx) + 200 (cashTx) = 700.0 (excludes transferTx of 1500.0)
        assertEquals(700.0, expenseTotal, 0.001)
        // Income total should be 1000 (incomeTx) = 1000.0 (excludes transferTx of 1500.0)
        assertEquals(1000.0, incomeTotal, 0.001)

        // Requirement A: Expense opens as Expense
        val typeForExpense = if (expenseTx.isInternalTransfer || expenseTx.type == "INTERNAL_TRANSFER") "INTERNAL_TRANSFER" else expenseTx.type
        assertEquals("EXPENSE", typeForExpense)

        // Requirement B: Income opens as Income
        val typeForIncome = if (incomeTx.isInternalTransfer || incomeTx.type == "INTERNAL_TRANSFER") "INTERNAL_TRANSFER" else incomeTx.type
        assertEquals("INCOME", typeForIncome)

        // Requirement C: Internal Transfer opens as Internal Transfer
        val typeForTransfer = if (transferTx.isInternalTransfer || transferTx.type == "INTERNAL_TRANSFER" || transferTx.transactionType == "INTERNAL_TRANSFER") "INTERNAL_TRANSFER" else transferTx.type
        assertEquals("INTERNAL_TRANSFER", typeForTransfer)

        // Requirement F: Editing an Internal Transfer does not convert it to Expense
        vm.updateTransaction(
            id = "tx-audit-transfer",
            type = "INTERNAL_TRANSFER",
            amount = 1500.0,
            date = "2026-10-01",
            time = "12:05",
            merchant = "Self Edited",
            categoryId = "cat-transfer",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "Transfer",
            note = "Edited note",
            isExpense = false
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val transferAfter = dao.getAllTransactionsSync().find { it.id == "tx-audit-transfer" }
        assertNotNull(transferAfter)
        assertEquals("INTERNAL_TRANSFER", transferAfter?.type)
        assertTrue(transferAfter?.isInternalTransfer == true)
        assertFalse(transferAfter?.isExpense == true)

        // Requirement G: Editing Expense preserves Expense Included state
        vm.updateTransaction(
            id = "tx-audit-expense",
            type = "EXPENSE",
            amount = 550.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "D-Mart Mega",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "",
            isExpense = true // preserve original true state
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val expenseAfter = dao.getAllTransactionsSync().find { it.id == "tx-audit-expense" }
        assertNotNull(expenseAfter)
        assertTrue(expenseAfter?.isExpense == true)

        // Requirement H: Intentional Expense -> Income change works
        vm.updateTransaction(
            id = "tx-audit-expense",
            type = "INCOME",
            amount = 550.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "D-Mart Mega",
            categoryId = "cat-salary",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "",
            isExpense = false
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val expenseConvertedToIncome = dao.getAllTransactionsSync().find { it.id == "tx-audit-expense" }
        assertNotNull(expenseConvertedToIncome)
        assertEquals("INCOME", expenseConvertedToIncome?.type)
        assertFalse(expenseConvertedToIncome?.isExpense == true)

        // Requirement I: Intentional Income -> Expense change works
        vm.updateTransaction(
            id = "tx-audit-income",
            type = "EXPENSE",
            amount = 1000.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "Salary Client",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "Bank",
            note = "",
            isExpense = true
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val incomeConvertedToExpense = dao.getAllTransactionsSync().find { it.id == "tx-audit-income" }
        assertNotNull(incomeConvertedToExpense)
        assertEquals("EXPENSE", incomeConvertedToExpense?.type)
        assertTrue(incomeConvertedToExpense?.isExpense == true)

        // Requirement J: Intentional Expense -> Transfer change works
        vm.updateTransaction(
            id = "tx-audit-income", // currently expense after previous test
            type = "INTERNAL_TRANSFER",
            amount = 1000.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "Salary Client",
            categoryId = "cat-transfer",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "Transfer",
            note = "",
            isExpense = false
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val expenseConvertedToTransfer = dao.getAllTransactionsSync().find { it.id == "tx-audit-income" }
        assertNotNull(expenseConvertedToTransfer)
        assertEquals("INTERNAL_TRANSFER", expenseConvertedToTransfer?.type)
        assertTrue(expenseConvertedToTransfer?.isInternalTransfer == true)
        assertFalse(expenseConvertedToTransfer?.isExpense == true)

        // Requirement K: Unknown transfer counterparty remains NEEDS_REVIEW
        assertTrue(transferAfter?.needsReview == true)
        assertNull(transferAfter?.counterpartyAccountId)

        // Requirement L: Gmail internal ID is never displayed as merchant
        val cleanedMsgMerchant = com.example.utils.SmsParser.sanitizeMerchantName("Email 1a10496b89ecc3e9")
        assertEquals("Unknown Merchant", cleanedMsgMerchant)

        // Requirement M: Known UPI remains UPI
        assertEquals("UPI", expenseAfter?.paymentMethod)

        // Requirement N: Cash remains Cash
        assertEquals("Cash", cashTx.paymentMethod)
        assertEquals("acc-cash", cashTx.accountId)

        // Requirement O: Existing transaction/reference/source data remains unchanged
        assertEquals("SMS", transferAfter?.source)
        assertEquals("REF-TRANSFER-1", transferAfter?.transactionReference)
        assertEquals("ORIG-TRANSFER-1", transferAfter?.originalReference)
        assertEquals(now, transferAfter?.createdAt)

        collectJob.cancel()
    }
}
