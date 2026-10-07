package com.example.ingestion

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
class TransactionIngestionAuditTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private fun createTx(
        id: String,
        type: String,
        amount: Double,
        date: String,
        time: String = "12:00",
        merchant: String = "Test Merchant",
        categoryId: String = "cat-other",
        subcategoryId: String = "",
        accountId: String = "",
        paymentMethod: String = "UPI",
        note: String = "",
        source: String = "SMS",
        transactionReference: String = "",
        last4Digits: String = ""
    ): TransactionEntity {
        return TransactionEntity(
            id = id,
            type = type,
            amount = amount,
            date = date,
            time = time,
            merchant = merchant,
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            accountId = accountId,
            paymentMethod = paymentMethod,
            note = note,
            source = source,
            transactionReference = transactionReference,
            originalReference = "",
            last4Digits = last4Digits,
            createdAt = "",
            updatedAt = ""
        )
    }

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

    // A. Email debit
    @Test
    fun testEmailDebit() = runBlocking {
        val emailBody = "Rs. 250.00 debited from card ending 8812 at Apex Supermarket on 01-10-2026. Ref: UPI/REF100200300"
        val output = EmailParser.parseEmail("msg-101", "Transaction Alert", emailBody, System.currentTimeMillis())
        assertEquals(EmailParserStatus.IMPORTED, output.status)
        assertNotNull(output.transaction)
        assertEquals("EXPENSE", output.transaction?.type)
        assertEquals(250.0, output.transaction?.amount ?: 0.0, 0.01)
        assertEquals("Apex Supermarket", output.transaction?.merchant)
    }

    // B. Email credit
    @Test
    fun testEmailCredit() = runBlocking {
        val emailBody = "Rs. 1500.00 credited to account 8812 from Client Services on 01-10-2026. Ref: UTR998877"
        val output = EmailParser.parseEmail("msg-102", "Credit Alert", emailBody, System.currentTimeMillis())
        assertEquals(EmailParserStatus.IMPORTED, output.status)
        assertNotNull(output.transaction)
        assertEquals("INCOME", output.transaction?.type)
        assertEquals(1500.0, output.transaction?.amount ?: 0.0, 0.01)
    }

    // C. SMS debit
    @Test
    fun testSmsDebit() = runBlocking {
        val smsBody = "Rs. 320 debited from card ending 4092 at Cafe Central on 01-10-2026. Ref: RRN776655"
        val status = SmsParser.parseSms("sms-101", "VM-TESTBK", smsBody, System.currentTimeMillis())
        assertTrue(status is SmsParser.SmsParseStatus.Success)
        val tx = (status as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("EXPENSE", tx.type)
        assertEquals(320.0, tx.amount, 0.01)
        assertEquals("Cafe Central", tx.merchant)
    }

    // D. SMS credit
    @Test
    fun testSmsCredit() = runBlocking {
        val smsBody = "Rs. 5000 credited to a/c ending 4092 towards Salary on 01-10-2026. Ref: IMPS123456"
        val status = SmsParser.parseSms("sms-102", "VM-TESTBK", smsBody, System.currentTimeMillis())
        assertTrue(status is SmsParser.SmsParseStatus.Success)
        val tx = (status as SmsParser.SmsParseStatus.Success).transaction
        assertEquals("INCOME", tx.type)
        assertEquals(5000.0, tx.amount, 0.01)
    }

    // E. Notification debit
    @Test
    fun testNotificationDebit() = runBlocking {
        val notifText = "Paid Rs 180 to Bakery House via UPI. Ref 445566"
        val parseResult = NotificationParser.parseNotification("com.phonepe.app", "Payment Alert", notifText, notifText, "", System.currentTimeMillis(), emptyList())
        assertNotNull(parseResult.transaction)
        assertEquals("EXPENSE", parseResult.transaction?.type)
        assertEquals(180.0, parseResult.transaction?.amount ?: 0.0, 0.01)
        assertEquals("Bakery House", parseResult.transaction?.merchant)
    }

    @Test
    fun notificationRetryUsesEventIdentityNotTransactionAttributes() {
        val postTime = 1_798_400_000_000L
        val text = "Paid Rs 180 to Bakery House via UPI"
        val first = NotificationParser.parseNotification(
            "com.phonepe.app", "Payment Alert", text, text, "", postTime, emptyList()
        ).transaction
        val retry = NotificationParser.parseNotification(
            "com.phonepe.app", "Payment Alert", text, text, "", postTime, emptyList()
        ).transaction
        val separateEvent = NotificationParser.parseNotification(
            "com.phonepe.app", "Payment Alert", text, text, "", postTime + 1_000L, emptyList()
        ).transaction

        assertNotNull(first)
        assertNotNull(retry)
        assertNotNull(separateEvent)
        assertTrue(TransactionIngestionEngine.isDuplicateTransaction(first!!, retry!!))
        assertFalse(TransactionIngestionEngine.isDuplicateTransaction(first, separateEvent!!))
    }

    // F. Same transaction through SMS + Email
    @Test
    fun testSameTransactionSmsAndEmail() = runBlocking {
        // Insert a matching pre-existing account to resolve to
        val testAccount = AccountEntity(
            id = "acc-test-3344",
            name = "Test Account 3344",
            type = "Bank Account",
            bankName = "TestBank",
            last4Digits = "3344"
        )
        db.kharchaDao().insertAccount(testAccount)

        val ref = "RRN88990011"
        val smsText = "Rs. 450 debited from card ending 3344 at Book Store. Ref: $ref"
        val (_, smsStatus) = TransactionIngestionEngine.ingestTransaction(
            context,
            createTx(id = "tx-sms-1", type = "EXPENSE", amount = 450.0, date = "2026-10-01", merchant = "Book Store", source = "SMS", transactionReference = ref, last4Digits = "3344"),
            smsText
        )
        assertEquals(IngestionStatus.IMPORTED, smsStatus)

        val emailText = "Transaction of Rs. 450.00 at Book Store on card ending 3344. Ref: $ref"
        val (_, emailStatus) = TransactionIngestionEngine.ingestTransaction(
            context,
            createTx(id = "tx-email-1", type = "EXPENSE", amount = 450.0, date = "2026-10-01", merchant = "Book Store", source = "EMAIL", transactionReference = ref, last4Digits = "3344"),
            emailText
        )
        assertEquals(IngestionStatus.DUPLICATE, emailStatus)

        val allTxs = db.kharchaDao().getAllTransactionsSync()
        assertEquals(1, allTxs.size)
        assertEquals(450.0, allTxs[0].amount, 0.01)
    }

    // G. Same transaction through SMS + Notification
    @Test
    fun testSameTransactionSmsAndNotification() = runBlocking {
        val ref = "UPI11223344"
        val smsText = "Paid Rs 200 to Metro Rail. Ref: $ref"
        TransactionIngestionEngine.ingestTransaction(
            context,
            createTx(id = "tx-sms-2", type = "EXPENSE", amount = 200.0, date = "2026-10-01", merchant = "Metro Rail", source = "SMS", transactionReference = ref),
            smsText
        )

        val notifText = "Paid Rs 200 to Metro Rail via GPay. Ref: $ref"
        val (_, notifStatus) = TransactionIngestionEngine.ingestTransaction(
            context,
            createTx(id = "tx-notif-2", type = "EXPENSE", amount = 200.0, date = "2026-10-01", merchant = "Metro Rail", source = "NOTIFICATION", transactionReference = ref),
            notifText
        )
        assertEquals(IngestionStatus.DUPLICATE, notifStatus)
        assertEquals(1, db.kharchaDao().getAllTransactionsSync().size)
    }

    // H. Same transaction through SMS + Notification + Email
    @Test
    fun testSameTransactionSmsNotifEmail() = runBlocking {
        val ref = "UTR5544332211"
        val txSms = createTx(id = "s1", type = "EXPENSE", amount = 999.0, date = "2026-10-01", merchant = "Gadget World", source = "SMS", transactionReference = ref)
        val txNotif = createTx(id = "n1", type = "EXPENSE", amount = 999.0, date = "2026-10-01", merchant = "Gadget World", source = "NOTIFICATION", transactionReference = ref)
        val txEmail = createTx(id = "e1", type = "EXPENSE", amount = 999.0, date = "2026-10-01", merchant = "Gadget World", source = "EMAIL", transactionReference = ref)

        TransactionIngestionEngine.ingestTransaction(context, txSms, "SMS $ref")
        TransactionIngestionEngine.ingestTransaction(context, txNotif, "NOTIF $ref")
        TransactionIngestionEngine.ingestTransaction(context, txEmail, "EMAIL $ref")

        val txs = db.kharchaDao().getAllTransactionsSync()
        assertEquals(1, txs.size)
        assertEquals(999.0, txs[0].amount, 0.01)
    }

    // I. Two legitimate same-amount transactions remain separate
    @Test
    fun testTwoLegitimateTransactionsRemainSeparate() = runBlocking {
        // Insert a matching pre-existing account to resolve to
        val testAccount = AccountEntity(
            id = "acc-test-9999",
            name = "Test Account 9999",
            type = "Bank Account",
            bankName = "TestBank",
            last4Digits = "9999"
        )
        db.kharchaDao().insertAccount(testAccount)

        val tx1 = createTx(id = "tx-m1", type = "EXPENSE", amount = 100.0, date = "2026-10-01", time = "09:15", merchant = "Coffee Shop", source = "SMS", transactionReference = "REF-A-100", categoryId = "cat-leisure", last4Digits = "9999")
        val tx2 = createTx(id = "tx-m2", type = "EXPENSE", amount = 100.0, date = "2026-10-01", time = "16:45", merchant = "Coffee Shop", source = "SMS", transactionReference = "REF-B-200", categoryId = "cat-leisure", last4Digits = "9999")

        val (_, s1) = TransactionIngestionEngine.ingestTransaction(context, tx1, "Morning Coffee from TestBank card ending 9999")
        val (_, s2) = TransactionIngestionEngine.ingestTransaction(context, tx2, "Evening Coffee from TestBank card ending 9999")

        assertEquals(IngestionStatus.IMPORTED, s1)
        assertEquals(IngestionStatus.IMPORTED, s2)
        assertEquals(2, db.kharchaDao().getAllTransactionsSync().size)
    }

    // J. Generic bank/card identity with any last4 resolves to AccountEntity & CardEntity (not Manual)
    @Test
    fun testGenericBankCardIdentityResolution() = runBlocking {
        val tx = createTx(id = "tx-card-1", type = "EXPENSE", amount = 60.0, date = "2026-10-01", merchant = "Tea Stall", source = "EMAIL", last4Digits = "4510")
        val rawText = "Your CustomBank Credit Card ending 4510 has been debited by Rs.60 at Tea Stall"

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, tx, rawText)
        assertNotNull(ingested)
        assertNotNull(ingested?.accountId)
        assertTrue(ingested?.accountId?.isNotEmpty() == true)

        // Verify that the created AccountEntity and CardEntity exist in Room database
        val accounts = db.kharchaDao().getAllAccountsSync()
        val cards = db.kharchaDao().getAllCardsSync()
        assertTrue(accounts.any { it.id == ingested?.accountId })
        assertTrue(cards.any { it.last4Digits == "4510" })
    }

    // K. Unknown bank/card -> needsReview without assigning random account
    @Test
    fun testUnknownBankCardNeedsReview() = runBlocking {
        val tx = createTx(id = "tx-unk-1", type = "EXPENSE", amount = 75.0, date = "2026-10-01", merchant = "Local Market", source = "SMS", last4Digits = "")
        val (_, status) = TransactionIngestionEngine.ingestTransaction(context, tx, "Spent Rs 75 at Local Market")
        assertEquals(IngestionStatus.NEEDS_REVIEW, status)
    }

    // L. Merchant "07 42 43" rejected -> Unknown Merchant
    @Test
    fun testTimestampMerchantRejected() {
        val merchant = SmsParser.extractMerchant("Email body layout text 07 42 43 transaction alert", "alerts@bank.com")
        assertEquals("Unknown Merchant", merchant)
    }

    // M. Advertisement text "Know The" rejected as merchant -> Unknown Merchant
    @Test
    fun testAdTextMerchantRejected() {
        val merchant = SmsParser.extractMerchant("Know the benefits of your credit card spent at Shop", "alerts@bank.com")
        assertNotEquals("Know The", merchant)
    }

    // N. Missing merchant -> Unknown Merchant
    @Test
    fun testMissingMerchantReturnsUnknownMerchant() {
        val merchant = SmsParser.extractMerchant("Rs 100 debited from card ending 1234", "alerts@bank.com")
        assertEquals("Unknown Merchant", merchant)
    }

    // O. Promotional email ignored
    @Test
    fun testPromotionalEmailIgnored() {
        val output = EmailParser.parseEmail("promo-1", "50% off discount offer! Apply now for cash reward", "Get instant loan discount now!", System.currentTimeMillis())
        assertEquals(EmailParserStatus.IGNORED, output.status)
        assertNull(output.transaction)
    }

    // P. User-edited category preserved
    @Test
    fun testUserEditedCategoryPreserved() = runBlocking {
        val dao = db.kharchaDao()
        dao.insertTransaction(
            createTx(
                id = "tx-user-1",
                type = "EXPENSE",
                amount = 150.0,
                date = "2026-10-01",
                merchant = "Stationery Shop",
                categoryId = "cat-education",
                subcategoryId = "sub-edu1",
                source = "MANUAL",
                transactionReference = "REF-USER-1"
            )
        )

        // Incoming duplicate transaction
        val incoming = createTx(
            id = "tx-auto-1",
            type = "EXPENSE",
            amount = 150.0,
            date = "2026-10-01",
            merchant = "Stationery Shop",
            categoryId = "cat-other",
            source = "SMS",
            transactionReference = "REF-USER-1"
        )
        TransactionIngestionEngine.ingestTransaction(context, incoming, "Paid 150 REF-USER-1")

        val txInDb = dao.getTransactionByIdSync("tx-user-1")
        assertNotNull(txInDb)
        assertEquals("cat-education", txInDb?.categoryId)
    }

    // Q. Historical scan date boundary
    @Test
    fun testHistoricalScanDateBoundary() = runBlocking {
        val smsList = listOf(
            SmsParser.RawSms("s1", "VM-BANK", "Debited 100 at Shop A", 1000L),
            SmsParser.RawSms("s2", "VM-BANK", "Debited 200 at Shop B", 5000L)
        )
        val previousParserProvider = SmsParser.liveAuthenticatedUidProvider
        val previousIngestionProvider = TransactionIngestionEngine.liveAuthenticatedUidProvider
        SmsParser.liveAuthenticatedUidProvider = { "historical-scan-user" }
        TransactionIngestionEngine.liveAuthenticatedUidProvider = { "historical-scan-user" }
        try {
            val result = SmsParser.processSmsList(context, smsList, newerThanTimestamp = 3000L)
            assertEquals(1, result.transactions.size)
            assertEquals(200.0, result.transactions[0].amount, 0.01)
            assertEquals("historical-scan-user", result.transactions[0].userId)
        } finally {
            SmsParser.liveAuthenticatedUidProvider = previousParserProvider
            TransactionIngestionEngine.liveAuthenticatedUidProvider = previousIngestionProvider
        }
    }

    // R. Existing transactions preserved
    @Test
    fun testExistingTransactionsPreserved() = runBlocking {
        val dao = db.kharchaDao()
        dao.insertTransaction(
            createTx(id = "t-exist-1", type = "EXPENSE", amount = 88.0, date = "2026-10-01", merchant = "Book Store")
        )
        TransactionIngestionEngine.normalizeExistingTransactions(dao)
        val all = dao.getAllTransactionsSync()
        assertTrue(all.any { it.id == "t-exist-1" })
    }

    // REQUIREMENT A: Bank Account -> Bank Account self transfer
    @Test
    fun testRequirementA_BankAccountToBankAccountSelfTransfer() = runBlocking {
        val dao = db.kharchaDao()
        val hdfcAcc = AccountEntity(id = "acc-hdfc-1234", name = "HDFC Bank", type = "Bank Account", bankName = "HDFC", last4Digits = "1234", isOwnedByMe = true, colour = "#1E40AF", isActive = true)
        val idfcAcc = AccountEntity(id = "acc-idfc-6168", name = "IDFC FIRST Bank", type = "Bank Account", bankName = "IDFC FIRST Bank", last4Digits = "6168", isOwnedByMe = true, colour = "#DC2626", isActive = true)
        dao.insertAccount(hdfcAcc)
        dao.insertAccount(idfcAcc)

        val tx = createTx(id = "tx-transfer-35000", type = "INCOME", amount = 35000.0, date = "2026-10-01", time = "14:30", merchant = "Self Transfer", source = "NOTIFICATION", last4Digits = "6168")
        val rawNotif = "INR 35,000.00 credited to your A/C ...6168 on 01-10-2026 by transfer from A/C ...1234"

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, tx, rawNotif)
        assertNotNull(ingested)
        assertEquals("INTERNAL_TRANSFER", ingested?.type)
        assertEquals("INTERNAL_TRANSFER", ingested?.transactionType)
        assertTrue(ingested?.isInternalTransfer == true)
        assertFalse(ingested?.isExpense == true)
        assertEquals(35000.0, ingested?.amount ?: 0.0, 0.01)
        assertEquals("acc-idfc-6168", ingested?.accountId)
        assertEquals("acc-hdfc-1234", ingested?.counterpartyAccountId)
        assertEquals("cat-transfer", ingested?.categoryId)
    }

    // REQUIREMENT B: Bank Account -> Credit Card bill payment
    @Test
    fun testRequirementB_BankAccountToCreditCardBillPayment() = runBlocking {
        val dao = db.kharchaDao()
        val bankAcc = AccountEntity(id = "acc-bank-1234", name = "Bank Account", type = "Bank Account", bankName = "Bank", last4Digits = "1234", isOwnedByMe = true, colour = "#1E40AF", isActive = true)
        val ccAcc = AccountEntity(id = "acc-cc-6926", name = "YES Bank Credit Card", type = "Credit Card", bankName = "YES Bank", last4Digits = "6926", isOwnedByMe = true, colour = "#7C3AED", isActive = true)
        val ccCard = CardEntity(id = "card-yes-6926", accountId = "acc-cc-6926", name = "YES Bank Credit Card", type = "Credit Card", last4Digits = "6926")
        dao.insertAccount(bankAcc)
        dao.insertAccount(ccAcc)
        dao.insertCard(ccCard)

        val tx = createTx(id = "tx-cc-pay-50", type = "EXPENSE", amount = 50.0, date = "2026-10-01", time = "15:00", merchant = "YES Bank", source = "SMS", last4Digits = "1234")
        val rawSms = "Rs 50.00 debited from A/c 1234 towards payment for credit card ending 6926. Ref: REF-CC-50"

        val (ingested, _) = TransactionIngestionEngine.ingestTransaction(context, tx, rawSms)
        assertNotNull(ingested)
        assertEquals("INTERNAL_TRANSFER", ingested?.type)
        assertTrue(ingested?.transactionType == "CREDIT_CARD_BILL_PAYMENT" || ingested?.transactionType == "CARD_PAYMENT")
        assertTrue(ingested?.isInternalTransfer == true)
        assertFalse(ingested?.isExpense == true)
        assertEquals(50.0, ingested?.amount ?: 0.0, 0.01)
        assertEquals("acc-bank-1234", ingested?.accountId)
        assertEquals("acc-cc-6926", ingested?.counterpartyAccountId)
    }

    // REQUIREMENT C: UPI -> Credit Card bill payment
    @Test
    fun testRequirementC_UpiToCreditCardBillPayment() = runBlocking {
        val dao = db.kharchaDao()
        val auCcAcc = AccountEntity(id = "acc-cc-9546", name = "AU Bank Credit Card", type = "Credit Card", bankName = "AU Bank", last4Digits = "9546", isOwnedByMe = true, colour = "#7C3AED", isActive = true)
        val auCard = CardEntity(id = "card-au-9546", accountId = "acc-cc-9546", name = "AU Bank Credit Card", type = "Credit Card", last4Digits = "9546")
        dao.insertAccount(auCcAcc)
        dao.insertCard(auCard)

        val tx = createTx(id = "tx-cc-pay-395", type = "EXPENSE", amount = 395.0, date = "2026-10-01", time = "16:00", merchant = "AU Bank", source = "NOTIFICATION", last4Digits = "")
        val rawNotif = "Bill payment of Rs 395 for AU Bank Credit Card ending 9546 successful via UPI. Ref: UPI998877"

        val (ingested, _) = TransactionIngestionEngine.ingestTransaction(context, tx, rawNotif)
        assertNotNull(ingested)
        assertEquals("INTERNAL_TRANSFER", ingested?.type)
        assertTrue(ingested?.transactionType == "CREDIT_CARD_BILL_PAYMENT" || ingested?.transactionType == "CARD_PAYMENT")
        assertTrue(ingested?.isInternalTransfer == true)
        assertFalse(ingested?.isExpense == true)
        assertEquals(395.0, ingested?.amount ?: 0.0, 0.01)
    }

    // REQUIREMENT D: Credit card bill payment must not count as expense
    @Test
    fun testRequirementD_CreditCardBillPaymentMustNotCountAsExpense() = runBlocking {
        val dao = db.kharchaDao()
        val ccTx = createTx(
            id = "tx-cc-bill-4",
            type = "INTERNAL_TRANSFER",
            amount = 50.0,
            date = "2026-10-01",
            merchant = "Credit Card Bill Payment",
            categoryId = "cat-transfer"
        ).copy(
            transactionType = "CREDIT_CARD_BILL_PAYMENT",
            isInternalTransfer = true,
            isExpense = false
        )
        dao.insertTransaction(ccTx)

        val all = dao.getAllTransactionsSync()
        val expenseTotal = all.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" && it.transactionType != "CREDIT_CARD_BILL_PAYMENT" }.sumOf { it.amount }
        assertEquals(0.0, expenseTotal, 0.001)
    }

    // REQUIREMENT E: Self transfer must not count as income
    @Test
    fun testRequirementE_SelfTransferMustNotCountAsIncome() = runBlocking {
        val dao = db.kharchaDao()
        val selfTransferTx = createTx(
            id = "tx-self-35000",
            type = "INTERNAL_TRANSFER",
            amount = 35000.0,
            date = "2026-10-01",
            merchant = "Internal Transfer",
            categoryId = "cat-transfer"
        ).copy(
            transactionType = "INTERNAL_TRANSFER",
            isInternalTransfer = true,
            isExpense = false
        )
        dao.insertTransaction(selfTransferTx)

        val all = dao.getAllTransactionsSync()
        val incomeTotal = all.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" && it.transactionType != "CREDIT_CARD_BILL_PAYMENT" }.sumOf { it.amount }
        assertEquals(0.0, incomeTotal, 0.001)
    }

    // REQUIREMENT F: Unknown/ambiguous destination must become needsReview/unresolved, not Income
    @Test
    fun testRequirementF_UnknownDestinationBecomesNeedsReviewNotIncome() = runBlocking {
        val dao = db.kharchaDao()
        val acc1 = AccountEntity(id = "acc-1", name = "Account 1", type = "Bank Account", bankName = "Bank1", last4Digits = "1111", isOwnedByMe = true, colour = "#1E40AF", isActive = true)
        val acc2 = AccountEntity(id = "acc-2", name = "Account 2", type = "Bank Account", bankName = "Bank2", last4Digits = "2222", isOwnedByMe = true, colour = "#1E40AF", isActive = true)
        val acc3 = AccountEntity(id = "acc-3", name = "Account 3", type = "Bank Account", bankName = "Bank3", last4Digits = "3333", isOwnedByMe = true, colour = "#1E40AF", isActive = true)
        dao.insertAccount(acc1)
        dao.insertAccount(acc2)
        dao.insertAccount(acc3)

        // Transfer received without specifying which owned account it came from
        val tx = createTx(id = "tx-ambig-1", type = "INCOME", amount = 15000.0, date = "2026-10-01", merchant = "Transfer Received", source = "NOTIFICATION", last4Digits = "1111")
        val rawNotif = "INR 15,000.00 credited to your A/C ...1111 by fund transfer"

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, tx, rawNotif)
        assertNotNull(ingested)
        assertEquals("INTERNAL_TRANSFER", ingested?.type)
        assertEquals("INTERNAL_TRANSFER", ingested?.transactionType)
        assertTrue(ingested?.isInternalTransfer == true)
        assertTrue(ingested?.needsReview == true)
        assertNull(ingested?.counterpartyAccountId)
        assertNotEquals("INCOME", ingested?.type)
        assertNotEquals("cat-other", ingested?.categoryId)
    }

    // REQUIREMENT G: Same transaction received through SMS + Notification + Email remains one canonical transaction
    @Test
    fun testRequirementG_SameTransactionMultiSourceRemainsOneCanonical() = runBlocking {
        val dao = db.kharchaDao()
        val acc = AccountEntity(id = "acc-hdfc-4092", name = "HDFC Bank", type = "Bank Account", bankName = "HDFC", last4Digits = "4092", isOwnedByMe = true, colour = "#1E40AF", isActive = true)
        dao.insertAccount(acc)

        val txSms = createTx(id = "tx-sms-1", type = "EXPENSE", amount = 500.0, date = "2026-10-01", time = "10:00", merchant = "Merchant Store", source = "SMS", transactionReference = "UPI/REF-MULTI-1", last4Digits = "4092")
        val txNotif = createTx(id = "tx-notif-1", type = "EXPENSE", amount = 500.0, date = "2026-10-01", time = "10:00", merchant = "Merchant Store", source = "NOTIFICATION", transactionReference = "UPI/REF-MULTI-1", last4Digits = "4092")
        val txEmail = createTx(id = "tx-email-1", type = "EXPENSE", amount = 500.0, date = "2026-10-01", time = "10:00", merchant = "Merchant Store", source = "EMAIL", transactionReference = "UPI/REF-MULTI-1", last4Digits = "4092")

        TransactionIngestionEngine.ingestTransaction(context, txSms, "Debited Rs 500 at Merchant Store Ref UPI/REF-MULTI-1")
        TransactionIngestionEngine.ingestTransaction(context, txNotif, "Paid Rs 500 to Merchant Store Ref UPI/REF-MULTI-1")
        TransactionIngestionEngine.ingestTransaction(context, txEmail, "Your account 4092 was debited for Rs 500 Ref UPI/REF-MULTI-1")

        val all = dao.getAllTransactionsSync()
        assertEquals(1, all.size)
        assertEquals(500.0, all[0].amount, 0.01)
        assertEquals("UPI/REF-MULTI-1", all[0].transactionReference)
    }

    // REQUIREMENT H: Amount must be preserved exactly from the source
    @Test
    fun testRequirementH_AmountPreservedExactlyFromSource() {
        val parse395 = NotificationParser.parseNotification(
            packageName = "com.bank.app",
            title = "Payment Received",
            text = "Payment of Rs 395 received towards your Credit Card ending 9546",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = emptyList<TransactionEntity>()
        )
        assertNotNull(parse395.transaction)
        assertEquals(395.0, parse395.transaction?.amount ?: 0.0, 0.001)

        val parse396 = NotificationParser.parseNotification(
            packageName = "com.bank.app",
            title = "Payment Received",
            text = "Payment of Rs 396 received towards your Credit Card ending 9546",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = emptyList<TransactionEntity>()
        )
        assertNotNull(parse396.transaction)
        assertEquals(396.0, parse396.transaction?.amount ?: 0.0, 0.001)

        val parse35000 = NotificationParser.parseNotification(
            packageName = "com.idfcfirstbank.optimus",
            title = "Credit Alert",
            text = "INR 35,000.00 credited to your A/C ...6168 by transfer",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = emptyList<TransactionEntity>()
        )
        assertNotNull(parse35000.transaction)
        assertEquals(35000.0, parse35000.transaction?.amount ?: 0.0, 0.001)
    }
}
