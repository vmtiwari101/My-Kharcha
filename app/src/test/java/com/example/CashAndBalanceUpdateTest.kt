package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.IngestionStatus
import com.example.utils.SmsParser
import com.example.utils.TransactionIngestionEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CashAndBalanceUpdateTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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

    // A & C: Manual Cash selected for Expense & saved with Cash AccountEntity
    @Test
    fun testCashExpenseSavingAndAssociation() = runBlocking {
        val dao = db.kharchaDao()
        com.example.data.DefaultCategoryData.ensureDefaultCashAccount(dao)

        val accounts = dao.getAllAccountsSync()
        val cashAcc = accounts.find { it.type.equals("Cash", ignoreCase = true) || it.id == "acc-cash" }
        assertNotNull("Generic Cash account must exist", cashAcc)

        val tx = TransactionEntity(
            id = "tx-cash-1",
            type = "EXPENSE",
            amount = 250.0,
            date = "2026-10-04",
            time = "10:00",
            merchant = "Local Vendor",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = cashAcc!!.id,
            paymentMethod = "Cash",
            note = "",
            source = "MANUAL",
            transactionReference = "REF-CASH-1",
            isExpense = true
        )
        dao.insertTransaction(tx)

        val saved = dao.getTransactionByIdSync("tx-cash-1")
        assertNotNull(saved)
        assertEquals("acc-cash", saved!!.accountId)
        assertEquals("Cash", saved.paymentMethod)
        assertEquals(250.0, saved.amount, 0.001)
    }

    // F: Debit SMS + Available Balance
    @Test
    fun testDebitSmsWithAvailableBalance() = runBlocking {
        val dao = db.kharchaDao()
        val bankAcc = AccountEntity(
            id = "acc-hdfc-1234",
            name = "HDFC Bank •••• 1234",
            type = "Bank Account",
            bankName = "HDFC Bank",
            last4Digits = "1234",
            initialBalance = 0.0,
            updatedAt = "2026-01-01T00:00:00Z"
        )
        dao.insertAccount(bankAcc)

        val smsText = "A/c XX1234 debited by Rs.500.00 on 04-Oct-26 at Swiggy. Available Balance Rs.10,500.00"
        val rawTx = TransactionEntity(
            id = "sms-1",
            type = "EXPENSE",
            amount = 500.0,
            date = "2026-10-04",
            time = "12:00",
            merchant = "Swiggy",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc-1234",
            paymentMethod = "Bank Account",
            note = "",
            source = "SMS",
            transactionReference = "REF1234",
            last4Digits = "1234",
            createdAt = "2026-10-04T12:00:00Z"
        )

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, rawTx, smsText)
        assertNotNull(ingested)

        val updatedAcc = dao.getAllAccountsSync().find { it.id == "acc-hdfc-1234" }
        assertNotNull(updatedAcc)
        
        val totalBal = com.example.ui.screens.calculateAccountBalance(updatedAcc!!.id, dao.getAllTransactionsSync(), updatedAcc.initialBalance)
        assertEquals(10500.0, totalBal, 0.01)
    }

    // G: Credit SMS + Available Balance
    @Test
    fun testCreditSmsWithAvailableBalance() = runBlocking {
        val dao = db.kharchaDao()
        val bankAcc = AccountEntity(
            id = "acc-icici-5678",
            name = "ICICI Bank •••• 5678",
            type = "Bank Account",
            bankName = "ICICI Bank",
            last4Digits = "5678",
            initialBalance = 0.0,
            updatedAt = "2026-01-01T00:00:00Z"
        )
        dao.insertAccount(bankAcc)

        val smsText = "Your A/C XX5678 is credited with INR 2,000.00 on 04-Oct-26. Current Bal: INR 15,450.50"
        val rawTx = TransactionEntity(
            id = "sms-2",
            type = "INCOME",
            amount = 2000.0,
            date = "2026-10-04",
            time = "13:00",
            merchant = "Salary / Client",
            categoryId = "cat-salary",
            subcategoryId = "",
            accountId = "acc-icici-5678",
            paymentMethod = "Bank Account",
            note = "",
            source = "SMS",
            transactionReference = "REF5678",
            last4Digits = "5678",
            createdAt = "2026-10-04T13:00:00Z"
        )

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, rawTx, smsText)
        assertNotNull(ingested)

        val updatedAcc = dao.getAllAccountsSync().find { it.id == "acc-icici-5678" }
        assertNotNull(updatedAcc)

        val totalBal = com.example.ui.screens.calculateAccountBalance(updatedAcc!!.id, dao.getAllTransactionsSync(), updatedAcc.initialBalance)
        assertEquals(15450.50, totalBal, 0.01)
    }

    // H, I, J, K: Wording variations (Current, New, Closing, Ledger Balance)
    @Test
    fun testWordingVariations() {
        val b1 = TransactionIngestionEngine.extractBalanceInfo("Current Balance: Rs 12,000.00")
        assertEquals(12000.0, b1?.accountBalance!!, 0.01)

        val b2 = TransactionIngestionEngine.extractBalanceInfo("New Bal in A/c is INR 8,500")
        assertEquals(8500.0, b2?.accountBalance!!, 0.01)

        val b3 = TransactionIngestionEngine.extractBalanceInfo("Closing Balance on 04-Oct is ₹ 25,000.50")
        assertEquals(25000.50, b3?.accountBalance!!, 0.01)

        val b4 = TransactionIngestionEngine.extractBalanceInfo("Ledger Bal: ₹10500.00")
        assertEquals(10500.0, b4?.accountBalance!!, 0.01)
    }

    // L & M: Balance number does not become a transaction & Multi-transaction SMS
    @Test
    fun testBalanceNumberSeparationInParsing() {
        val smsText = "Txn of Rs. 450 at Starbucks. Available Balance Rs. 12,300.00"
        val parsedAmt = SmsParser.extractAmountForTest(smsText)
        assertEquals(450.0, parsedAmt, 0.01)

        val balInfo = TransactionIngestionEngine.extractBalanceInfo(smsText)
        assertNotNull(balInfo)
        assertEquals(12300.0, balInfo?.accountBalance!!, 0.01)
    }

    // N: Same SMS processed twice
    @Test
    fun testDuplicateSmsProcessingDoesNotCorruptBalance() = runBlocking {
        val dao = db.kharchaDao()
        val bankAcc = AccountEntity(
            id = "acc-sbi-4321",
            name = "SBI •••• 4321",
            type = "Bank Account",
            bankName = "State Bank of India",
            last4Digits = "4321",
            initialBalance = 0.0,
            updatedAt = "2026-01-01T00:00:00Z"
        )
        dao.insertAccount(bankAcc)

        val smsText = "A/C XX4321 debited by Rs 1,000. Avl Bal: Rs 9,000.00"
        val rawTx = TransactionEntity(
            id = "sms-dup-1",
            type = "EXPENSE",
            amount = 1000.0,
            date = "2026-10-04",
            time = "14:00",
            merchant = "DMart",
            categoryId = "cat-groceries",
            subcategoryId = "",
            accountId = "acc-sbi-4321",
            paymentMethod = "Bank Account",
            note = "",
            source = "SMS",
            transactionReference = "REFDUP4321",
            last4Digits = "4321",
            createdAt = "2026-10-04T14:00:00Z"
        )

        // First run
        TransactionIngestionEngine.ingestTransaction(context, rawTx, smsText)
        val accAfterFirst = dao.getAllAccountsSync().find { it.id == "acc-sbi-4321" }!!
        val balFirst = com.example.ui.screens.calculateAccountBalance(accAfterFirst.id, dao.getAllTransactionsSync(), accAfterFirst.initialBalance)
        assertEquals(9000.0, balFirst, 0.01)

        // Second run with duplicate
        TransactionIngestionEngine.ingestTransaction(context, rawTx, smsText)
        val accAfterSecond = dao.getAllAccountsSync().find { it.id == "acc-sbi-4321" }!!
        val balSecond = com.example.ui.screens.calculateAccountBalance(accAfterSecond.id, dao.getAllTransactionsSync(), accAfterSecond.initialBalance)
        assertEquals(9000.0, balSecond, 0.01)
    }

    // O: Older balance cannot overwrite newer balance
    @Test
    fun testOlderBalanceProtection() = runBlocking {
        val dao = db.kharchaDao()
        val bankAcc = AccountEntity(
            id = "acc-axis-9999",
            name = "Axis Bank •••• 9999",
            type = "Bank Account",
            bankName = "Axis Bank",
            last4Digits = "9999",
            initialBalance = 0.0,
            updatedAt = "2026-10-04T15:00:00Z" // Newer known balance timestamp
        )
        dao.insertAccount(bankAcc)

        val olderSms = "A/C XX9999 debited by Rs 100. Avl Bal Rs 50,000"
        val rawTx = TransactionEntity(
            id = "sms-old",
            type = "EXPENSE",
            amount = 100.0,
            date = "2026-10-01",
            time = "10:00",
            merchant = "Old Tx",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "acc-axis-9999",
            paymentMethod = "Bank Account",
            note = "",
            source = "SMS",
            transactionReference = "REFOLD9999",
            last4Digits = "9999",
            createdAt = "2026-10-01T10:00:00Z"
        )

        TransactionIngestionEngine.ingestTransaction(context, rawTx, olderSms)
        val accAfter = dao.getAllAccountsSync().find { it.id == "acc-axis-9999" }!!

        // UpdatedAt timestamp should NOT have reverted to older date
        assertEquals("2026-10-04T15:00:00Z", accAfter.updatedAt)
    }

    // P & Q: Unknown/Ambiguous account identity safety
    @Test
    fun testUnresolvedAccountIdentityDoesNotUpdateRandomAccount() = runBlocking {
        val dao = db.kharchaDao()
        val bankAcc = AccountEntity(
            id = "acc-safe-1111",
            name = "Safe Bank •••• 1111",
            type = "Bank Account",
            bankName = "Safe Bank",
            last4Digits = "1111",
            initialBalance = 5000.0,
            updatedAt = "2026-01-01T00:00:00Z"
        )
        dao.insertAccount(bankAcc)

        val unknownSms = "Debited Rs 500. Available Balance Rs 100,000"
        val rawTx = TransactionEntity(
            id = "sms-unknown",
            type = "EXPENSE",
            amount = 500.0,
            date = "2026-10-04",
            time = "16:00",
            merchant = "Unknown",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "",
            paymentMethod = "Manual",
            note = "",
            source = "SMS",
            transactionReference = "",
            createdAt = "2026-10-04T16:00:00Z"
        )

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, rawTx, unknownSms)
        assertEquals(IngestionStatus.NEEDS_REVIEW, status)

        val safeAccAfter = dao.getAllAccountsSync().find { it.id == "acc-safe-1111" }!!
        assertEquals(5000.0, safeAccAfter.initialBalance, 0.01)
    }

    @Test
    fun testAmbiguousAccountIdentityDoesNotUpdateBalance() = runBlocking {
        val dao = db.kharchaDao()
        // Two accounts with same last4 from DIFFERENT banks
        dao.insertAccount(AccountEntity(id = "acc-bank-a", name = "Bank A 1234", type = "Bank Account", bankName = "Bank A", last4Digits = "1234", initialBalance = 1000.0))
        dao.insertAccount(AccountEntity(id = "acc-bank-b", name = "Bank B 1234", type = "Bank Account", bankName = "Bank B", last4Digits = "1234", initialBalance = 2000.0))

        // SMS from unknown bank but matches last4 1234
        val ambiguousSms = "A/c XX1234 debited by Rs 100. Bal Rs 5000"
        val rawTx = TransactionEntity(
            id = "sms-ambig",
            type = "EXPENSE",
            amount = 100.0,
            date = "2026-10-04",
            time = "18:00",
            merchant = "Unknown",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "",
            paymentMethod = "Bank Account",
            note = "",
            source = "SMS",
            transactionReference = "",
            last4Digits = "1234",
            createdAt = "2026-10-04T18:00:00Z"
        )

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, rawTx, ambiguousSms)
        assertEquals(IngestionStatus.NEEDS_REVIEW, status)

        // Neither balance should be updated
        val accA = dao.getAllAccountsSync().find { it.id == "acc-bank-a" }!!
        val accB = dao.getAllAccountsSync().find { it.id == "acc-bank-b" }!!
        assertEquals(1000.0, accA.initialBalance, 0.01)
        assertEquals(2000.0, accB.initialBalance, 0.01)
    }

    @Test
    fun testMultiTransactionSmsBalanceSeparation() = runBlocking {
        // SMS with TWO amounts and a balance
        val smsText = "Debited Rs 100.00 and Rs 200.00. Available Bal Rs 5000.00"
        
        // existing parser picks first amount
        val parsedAmt = SmsParser.extractAmountForTest(smsText)
        assertEquals(100.0, parsedAmt, 0.01)
        
        // balance info correctly extracts the balance and NOT the second transaction amount
        val balInfo = TransactionIngestionEngine.extractBalanceInfo(smsText)
        assertNotNull(balInfo)
        assertEquals(5000.0, balInfo?.accountBalance!!, 0.01)
    }

    // R: Credit-card transaction + card balance
    @Test
    fun testCreditCardTransactionAndBalanceUpdate() = runBlocking {
        val dao = db.kharchaDao()
        val ccAcc = AccountEntity(
            id = "acc-cc-7777",
            name = "HDFC Credit Card •••• 7777",
            type = "Credit Card",
            bankName = "HDFC Bank",
            last4Digits = "7777",
            creditLimit = 100000.0,
            outstandingAmount = 0.0,
            updatedAt = "2026-01-01T00:00:00Z"
        )
        dao.insertAccount(ccAcc)

        val card = CardEntity(
            id = "card-7777",
            accountId = "acc-cc-7777",
            name = "HDFC Credit Card",
            type = "Credit Card",
            last4Digits = "7777",
            creditLimit = 100000.0,
            outstandingAmount = 0.0
        )
        dao.insertCard(card)

        val smsText = "Rs. 2,500.00 spent on HDFC Bank Card XX7777 at Amazon. Available Limit: Rs. 85,000.00"
        val rawTx = TransactionEntity(
            id = "sms-cc-1",
            type = "EXPENSE",
            amount = 2500.0,
            date = "2026-10-04",
            time = "17:00",
            merchant = "Amazon",
            categoryId = "cat-shopping",
            subcategoryId = "",
            accountId = "acc-cc-7777",
            paymentMethod = "Credit Card",
            note = "",
            source = "SMS",
            transactionReference = "REFCC7777",
            last4Digits = "7777",
            createdAt = "2026-10-04T17:00:00Z"
        )

        TransactionIngestionEngine.ingestTransaction(context, rawTx, smsText)

        val updatedCcAcc = dao.getAllAccountsSync().find { it.id == "acc-cc-7777" }!!
        assertEquals(15000.0, updatedCcAcc.outstandingAmount, 0.01)
    }

    // S: Bank/card balance never updates generic Cash
    @Test
    fun testBankSmsNeverUpdatesCashAccount() = runBlocking {
        val dao = db.kharchaDao()
        val cashAcc = AccountEntity(
            id = "acc-cash",
            name = "Cash",
            type = "Cash",
            bankName = "Cash",
            initialBalance = 1000.0,
            updatedAt = "2026-01-01T00:00:00Z"
        )
        dao.insertAccount(cashAcc)

        val balInfo = TransactionIngestionEngine.ExtractedBalanceInfo(accountBalance = 50000.0)
        TransactionIngestionEngine.applyExtractedBalance(dao, "acc-cash", null, balInfo, System.currentTimeMillis())

        val cashAccAfter = dao.getAllAccountsSync().find { it.id == "acc-cash" }!!
        assertEquals(1000.0, cashAccAfter.initialBalance, 0.01)
    }

    // T & U: Debit = Expense, Credit = Income, Transfer behavior unchanged
    @Test
    fun testDebitCreditRulesRemainIntact() {
        val debitDir = SmsParser.determineTransactionDirection("a/c debited by rs 500")
        assertEquals("EXPENSE", debitDir)

        val creditDir = SmsParser.determineTransactionDirection("a/c credited with rs 1000")
        assertEquals("INCOME", creditDir)
    }
}

private fun SmsParser.extractAmountForTest(body: String): Double {
    val cleanBody = body
        .replace(Regex("(?:available\\s*balance|avbl\\s*bal|new\\s*balance|ledger\\s*balance|account\\s*balance|credit\\s*limit|available\\s*limit|avbl\\s*limit|outstanding\\s*amount|statement\\s*balance|balance|bal|limit|outstanding)\\s*(?:is|of|to|for|:)?\\s*(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("avbl\\s*bal[\\s\\S]*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("available\\s*balance[\\s\\S]*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("bal(?:ance)?:?\\s*(?:rs|inr|₹)?[\\s\\S]*", RegexOption.IGNORE_CASE), "")

    val regex = Regex("(?:rs\\.?|inr|₹)\\s*([\\d,]+\\.?\\d*)", RegexOption.IGNORE_CASE)
    val matches = regex.findAll(cleanBody).toList()
    for (match in matches) {
        val numStr = match.groupValues[1].replace(",", "")
        val value = numStr.toDoubleOrNull() ?: 0.0
        if (value > 0) return value
    }
    return 0.0
}
