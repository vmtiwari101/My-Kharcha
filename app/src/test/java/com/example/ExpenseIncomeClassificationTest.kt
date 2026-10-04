package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.EmailParser
import com.example.utils.EmailParserStatus
import com.example.utils.IngestionStatus
import com.example.utils.NotificationParser
import com.example.utils.NotificationStatus
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
@Config(sdk = [36])
class ExpenseIncomeClassificationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

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

    // 1. EXPENSE INDICATORS TESTS
    @Test
    fun testDebitedIndicatorIsExpense() {
        val body = "Your A/c XXXX is debited by Rs.500 on 24-09-26. Info: Swiggy."
        val direction = SmsParser.determineTransactionDirection(body)
        assertEquals("EXPENSE", direction)

        val parseStatus = SmsParser.parseSms("sms-deb-1", "HDFCBK", body, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", tx.type)
        assertEquals(500.0, tx.amount, 0.001)
    }

    @Test
    fun testUpiPaymentMadeIsExpense() {
        val body = "UPI payment of Rs.500 made from your account to Sharma Store."
        val direction = SmsParser.determineTransactionDirection(body)
        assertEquals("EXPENSE", direction)

        val parseStatus = SmsParser.parseSms("sms-upi-1", "SBIUPI", body, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", tx.type)
        assertEquals(500.0, tx.amount, 0.001)
    }

    @Test
    fun testCardTransactionAtMerchantIsExpense() {
        val body = "Card transaction Rs.500 at AMAZON on card ending 1234."
        val direction = SmsParser.determineTransactionDirection(body)
        assertEquals("EXPENSE", direction)

        val parseStatus = SmsParser.parseSms("sms-card-1", "AXISBK", body, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", tx.type)
        assertEquals(500.0, tx.amount, 0.001)
        assertEquals("Amazon", tx.merchant)
    }

    @Test
    fun testSpentAndPaidIndicatorsAreExpense() {
        val spentBody = "Rs. 1,200 spent on fuel with card ending 8134 at HPCL."
        assertEquals("EXPENSE", SmsParser.determineTransactionDirection(spentBody))

        val paidBody = "Paid Rs. 150 to Sharma Grocery via UPI Ref 12345678."
        assertEquals("EXPENSE", SmsParser.determineTransactionDirection(paidBody))

        val chargedBody = "Rs. 250 charged to your credit card ending 1234 at Swiggy."
        assertEquals("EXPENSE", SmsParser.determineTransactionDirection(chargedBody))

        val deductedBody = "Rs. 499 deducted for Netflix subscription from A/c 4092."
        assertEquals("EXPENSE", SmsParser.determineTransactionDirection(deductedBody))

        val transferredFromBody = "Amount of Rs. 1000 transferred from your account ending 4092."
        assertEquals("EXPENSE", SmsParser.determineTransactionDirection(transferredFromBody))
    }

    // 2. INCOME INDICATORS TESTS
    @Test
    fun testCreditedIndicatorIsIncome() {
        val body = "Your A/c XXXX is credited by Rs.500 on 24-09-26. Ref: 987654321."
        val direction = SmsParser.determineTransactionDirection(body)
        assertEquals("INCOME", direction)

        val parseStatus = SmsParser.parseSms("sms-cred-1", "HDFCBK", body, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("INCOME", tx.type)
        assertEquals(500.0, tx.amount, 0.001)
    }

    @Test
    fun testReceivedInAccountIsIncome() {
        val body = "Rs.500 received in your account from Rahul via UPI Ref 87654321."
        val direction = SmsParser.determineTransactionDirection(body)
        assertEquals("INCOME", direction)

        val parseStatus = SmsParser.parseSms("sms-rec-1", "SBIUPI", body, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("INCOME", tx.type)
        assertEquals(500.0, tx.amount, 0.001)
    }

    @Test
    fun testSalaryCreditedIsIncome() {
        val body = "Dear Customer, INR 65,000.00 credited to your A/c XX4092 on 01-09-26 by Salary transfer."
        val parseStatus = SmsParser.parseSms("sms-sal-1", "HDFCBK", body, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("INCOME", tx.type)
        assertEquals(65000.0, tx.amount, 0.001)
        assertEquals("cat-salary", tx.categoryId)
    }

    @Test
    fun testCashbackAndDepositAreIncome() {
        val cashbackBody = "Cashback of Rs. 50 credited to your account for transaction."
        assertEquals("INCOME", SmsParser.determineTransactionDirection(cashbackBody))

        val depositBody = "Cash deposit of Rs. 10,000 in your account ending 4092."
        assertEquals("INCOME", SmsParser.determineTransactionDirection(depositBody))
    }

    // 3. CRITICAL RULE FOR CREDIT CARDS
    @Test
    fun testIdfcFirstBankCcPaymentIsNotIncome() {
        val body = "Thank You For Payment Of INR 38,979.00 Towards Your FIRST Select Credit Card XX8950 On 03 Sep 2026. IDFC FIRST Bank"
        val direction = SmsParser.determineTransactionDirection(body)
        assertEquals("EXPENSE", direction)

        val parseStatus = SmsParser.parseSms("sms-cc-idfc", "IDFCSB", body, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", tx.type)
        assertEquals(38979.0, tx.amount, 0.001)
        assertEquals("Credit Card Bill Payment", tx.merchant)
    }

    @Test
    fun testCreditCardPurchaseIsExpense() {
        val body = "Credit Card XXXX used for Rs.899 at Jio on 23 Aug. Avbl limit: Rs.45,000."
        val direction = SmsParser.determineTransactionDirection(body)
        assertEquals("EXPENSE", direction)

        val parseStatus = SmsParser.parseSms("sms-jio-1", "INDUSB", body, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", tx.type)
        assertEquals(899.0, tx.amount, 0.001)
        assertEquals("Jio", tx.merchant)
        assertEquals("Credit Card", tx.paymentMethod)
    }

    @Test
    fun testCreditCardBillPaymentDoesNotDoubleCountSpending() = runBlocking {
        val dao = db.kharchaDao()
        AppDatabase.prepopulateData(dao)

        val bankAcc = AccountEntity("acc-hdfc-test", "HDFC Savings", "Savings Account", "Bank Account", "4092", "landmark", "#1E40AF", true, false, true, "2026-09-24", "2026-09-24")
        val cardAcc = AccountEntity("acc-indus-test", "IndusInd Credit Card", "Credit Card", "Credit Card", "8134", "credit-card", "#7C3AED", true, false, true, "2026-09-24", "2026-09-24")
        val cardEntity = CardEntity("card-indus-1", "acc-indus-test", "IndusInd Platinum", "Credit Card", "8134", "2026-09-24", "2026-09-24")

        dao.insertAccount(bankAcc)
        dao.insertAccount(cardAcc)
        dao.insertCard(cardEntity)

        // User pays credit card bill from bank account
        val billPaymentSms = "Your HDFC A/c ending 4092 debited by Rs. 5,000 towards Credit Card Payment for card ending 8134."
        val tx = TransactionEntity(
            id = "tx-cc-bill-1",
            type = "EXPENSE",
            amount = 5000.0,
            date = "2026-09-24",
            time = "12:00",
            merchant = "Credit Card Payment",
            categoryId = "cat-bills",
            subcategoryId = "",
            accountId = "acc-hdfc-test",
            paymentMethod = "Bank Transfer",
            note = billPaymentSms,
            source = "SMS",
            transactionReference = "REF-CC-BILL-1",
            originalReference = "ORIG-CC-BILL-1",
            last4Digits = "4092"
        )

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, tx, billPaymentSms)
        assertNotNull(ingested)
        assertTrue("Credit card payment between owned accounts must be marked internal transfer", ingested!!.isInternalTransfer)
        assertEquals("CARD_PAYMENT", ingested.transactionType)
    }

    // 4. REFUND RULE
    @Test
    fun testRefundIsIncomeAndNeverExpense() {
        val body = "Refund of Rs.899 credited to your account for cancelled order #12345."
        val direction = SmsParser.determineTransactionDirection(body)
        assertEquals("INCOME", direction)

        val parseStatus = SmsParser.parseSms("sms-ref-1", "HDFCBK", body, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("INCOME", tx.type)
        assertEquals(899.0, tx.amount, 0.001)
        assertEquals("cat-refund", tx.categoryId)
    }

    @Test
    fun testRefundForShoppingMerchantRemainsIncome() {
        val body = "Rs. 450 refunded to your account for your order at Amazon UPI/987654."
        val direction = SmsParser.determineTransactionDirection(body)
        assertEquals("INCOME", direction)

        val parseStatus = SmsParser.parseSms("sms-ref-amz", "HDFCBK", body, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("INCOME", tx.type)
        assertEquals(450.0, tx.amount, 0.001)
        assertEquals("cat-refund", tx.categoryId)
    }

    // 5. ATM WITHDRAWAL RULE
    @Test
    fun testAtmWithdrawalIsExpenseAndNeverIncome() {
        val body = "Cash withdrawal of Rs. 2000 from ATM using card ending 4092 on 24-09-26."
        val direction = SmsParser.determineTransactionDirection(body)
        assertEquals("EXPENSE", direction)

        val parseStatus = SmsParser.parseSms("sms-atm-1", "HDFCBK", body, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", tx.type)
        assertEquals(2000.0, tx.amount, 0.001)
        assertEquals("cat-cash", tx.categoryId)
        assertNotEquals("INCOME", tx.type)
    }

    // 6. INTERNAL TRANSFER RULE
    @Test
    fun testInternalTransferBetweenOwnedAccountsDoesNotIncreaseSpendingOrIncome() = runBlocking {
        val dao = db.kharchaDao()
        AppDatabase.prepopulateData(dao)

        val hdfcAcc = AccountEntity("acc-hdfc-own", "HDFC Savings", "Savings", "Bank Account", "4092", "landmark", "#1E40AF", true, false, true, "2026-09-24", "2026-09-24")
        val axisAcc = AccountEntity("acc-axis-own", "Axis Savings", "Savings", "Bank Account", "7788", "landmark", "#DC2626", true, false, true, "2026-09-24", "2026-09-24")
        dao.insertAccount(hdfcAcc)
        dao.insertAccount(axisAcc)

        // Outgoing debit from HDFC
        val debitTx = TransactionEntity(
            id = "tx-out-1",
            type = "EXPENSE",
            amount = 10000.0,
            date = "2026-09-24",
            time = "10:00",
            merchant = "Transfer to Axis",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "acc-hdfc-own",
            paymentMethod = "Bank Transfer",
            note = "Transfer to a/c 7788",
            source = "SMS",
            transactionReference = "REF-TRANSFER-100",
            originalReference = "ORIG-TRANSFER-100",
            last4Digits = "4092"
        )
        val (ingestedDebit, _) = TransactionIngestionEngine.ingestTransaction(context, debitTx, "Transferred Rs. 10,000 to a/c 7788 from A/c 4092")
        assertNotNull(ingestedDebit)
        assertTrue(ingestedDebit!!.isInternalTransfer)
        assertEquals("INTERNAL_TRANSFER", ingestedDebit.transactionType)

        // Incoming credit on Axis (same day, same amount, opposite direction)
        val creditTx = TransactionEntity(
            id = "tx-in-1",
            type = "INCOME",
            amount = 10000.0,
            date = "2026-09-24",
            time = "10:02",
            merchant = "Transfer from HDFC",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "acc-axis-own",
            paymentMethod = "Bank Transfer",
            note = "Transfer received",
            source = "SMS",
            transactionReference = "REF-TRANSFER-200",
            originalReference = "ORIG-TRANSFER-200",
            last4Digits = "7788"
        )
        val (ingestedCredit, _) = TransactionIngestionEngine.ingestTransaction(context, creditTx, "Rs. 10,000 credited to A/c 7788")
        assertNotNull(ingestedCredit)
        assertTrue(ingestedCredit!!.isInternalTransfer)

        // Verify that neither transaction counts as standard spending or income
        val all = dao.getAllTransactionsSync()
        val expenseSpending = all.filter { it.type == "EXPENSE" && !it.isInternalTransfer }.sumOf { it.amount }
        val incomeEarned = all.filter { it.type == "INCOME" && !it.isInternalTransfer }.sumOf { it.amount }
        assertEquals("Internal transfer must NOT count towards spending", 0.0, expenseSpending, 0.001)
        assertEquals("Internal transfer must NOT count towards income", 0.0, incomeEarned, 0.001)
    }

    // 7. STRONG EVIDENCE PRIORITY & NEVER REVERSE DIRECTION
    @Test
    fun testNeverReverseDirectionRegardlessOfMerchant() {
        // Debited for a known shopping company remains EXPENSE
        val debitSms = "Your A/c XXXX is debited by Rs.500 at Flipkart"
        assertEquals("EXPENSE", SmsParser.determineTransactionDirection(debitSms))

        // Credited from a known shopping company remains INCOME
        val creditSms = "Your A/c XXXX is credited by Rs.500 from Flipkart"
        assertEquals("INCOME", SmsParser.determineTransactionDirection(creditSms))

        // Debited to an individual remains EXPENSE
        val debitToUser = "Your A/c XXXX is debited by Rs.500 sent to Amit"
        assertEquals("EXPENSE", SmsParser.determineTransactionDirection(debitToUser))

        // Credited from an individual remains INCOME
        val creditFromUser = "Your A/c XXXX is credited by Rs.500 received from Amit"
        assertEquals("INCOME", SmsParser.determineTransactionDirection(creditFromUser))
    }

    // 8. NOTIFICATION AND EMAIL INTEGRATION
    @Test
    fun testNotificationDirectionClassification() {
        val expenseNotif = NotificationParser.parseNotification(
            packageName = "com.google.android.apps.nbu.paisa.user",
            title = "Paid Rs. 350",
            text = "Paid Rs. 350 to Starbucks on GPay",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = emptyList()
        )
        assertEquals(NotificationStatus.IMPORTED, expenseNotif.status)
        assertEquals("EXPENSE", expenseNotif.transaction?.type)
        assertEquals(350.0, expenseNotif.transaction?.amount ?: 0.0, 0.001)

        val incomeNotif = NotificationParser.parseNotification(
            packageName = "com.google.android.apps.nbu.paisa.user",
            title = "Received Rs. 800",
            text = "Rs. 800 received in your account from Neha",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = emptyList()
        )
        assertEquals(NotificationStatus.IMPORTED, incomeNotif.status)
        assertEquals("INCOME", incomeNotif.transaction?.type)
        assertEquals(800.0, incomeNotif.transaction?.amount ?: 0.0, 0.001)
    }

    @Test
    fun testEmailDirectionClassification() {
        val expenseEmail = EmailParser.parseEmail(
            messageId = "email-1",
            subject = "Your Swiggy order receipt",
            body = "Your account ending 4092 was debited for Rs. 420.00 at Swiggy.",
            timestamp = System.currentTimeMillis(),
            existingTransactions = emptyList()
        )
        assertEquals(EmailParserStatus.IMPORTED, expenseEmail.status)
        assertEquals("EXPENSE", expenseEmail.transaction?.type)
        assertEquals(420.0, expenseEmail.transaction?.amount ?: 0.0, 0.001)

        val refundEmail = EmailParser.parseEmail(
            messageId = "email-2",
            subject = "Refund Processed: Rs. 899 credited",
            body = "Refund of Rs. 899 has been credited to your card ending 8134.",
            timestamp = System.currentTimeMillis(),
            existingTransactions = emptyList()
        )
        assertEquals(EmailParserStatus.IMPORTED, refundEmail.status)
        assertEquals("INCOME", refundEmail.transaction?.type)
        assertEquals(899.0, refundEmail.transaction?.amount ?: 0.0, 0.001)
    }
}
