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
class TransactionAccountMappingSafetyTest {

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

    @Test
    fun testA_CreditCardEnding7008_LinkedToExistingCreditCard() = runBlocking {
        val dao = db.kharchaDao()

        // Setup existing HDFC Bank parent account and Credit Card 7008
        val hdfcAccount = AccountEntity(
            id = "acc-hdfc-parent",
            name = "HDFC Savings",
            type = "Bank Account",
            bankName = "HDFC Bank",
            last4Digits = "1234",
            isOwnedByMe = true,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        dao.insertAccount(hdfcAccount)

        val creditCard7008 = CardEntity(
            id = "card-cc-7008",
            accountId = "acc-hdfc-parent",
            name = "HDFC Millennia",
            type = "Credit Card",
            last4Digits = "7008",
            creditLimit = 150000.0,
            outstandingAmount = 1200.0,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        dao.insertCard(creditCard7008)

        // Real credit card SMS
        val sms = "Spent Rs.500.00 on your Credit Card ending 7008 at STARBUCKS on 25-SEP-26. Avbl Limit: Rs.148300.00. Ref: CC998877."
        val parseStatus = SmsParser.parseSms("sms-1", "HDFCBK", sms, System.currentTimeMillis())
        assertTrue("SMS should parse successfully", parseStatus is SmsParser.SmsParseStatus.Success)

        val rawTx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        val (ingestedTx, status) = TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = rawTx,
            rawText = "HDFCBK $sms"
        )

        assertNotNull(ingestedTx)
        assertEquals(IngestionStatus.IMPORTED, status)
        assertEquals("acc-hdfc-parent", ingestedTx!!.accountId)
        assertEquals("card-cc-7008", ingestedTx.cardId)
        assertEquals("7008", ingestedTx.last4Digits)
        assertEquals(500.0, ingestedTx.amount, 0.001)

        // Verify no extra account was created
        val allAccounts = dao.getAllAccountsSync()
        assertEquals(1, allAccounts.size)
    }

    @Test
    fun testB_HdfcAccountEnding4510_DisambiguatedFrom5495() = runBlocking {
        val dao = db.kharchaDao()

        // Two HDFC accounts in database
        val hdfc4510 = AccountEntity(
            id = "acc-hdfc-4510",
            name = "HDFC Salary",
            type = "Bank Account",
            bankName = "HDFC Bank",
            last4Digits = "4510",
            isOwnedByMe = true,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        val hdfc5495 = AccountEntity(
            id = "acc-hdfc-5495",
            name = "HDFC Savings",
            type = "Bank Account",
            bankName = "HDFC Bank",
            last4Digits = "5495",
            isOwnedByMe = true,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        dao.insertAccount(hdfc4510)
        dao.insertAccount(hdfc5495)

        val sms = "Rs.1500.00 debited from HDFC Bank A/c ending 4510 on 25-SEP-26 to SWIGGY. Ref: 88472911."
        val parseStatus = SmsParser.parseSms("sms-2", "HDFCBK", sms, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)

        val rawTx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        val (ingestedTx, status) = TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = rawTx,
            rawText = "HDFCBK $sms"
        )

        assertNotNull(ingestedTx)
        assertEquals("acc-hdfc-4510", ingestedTx!!.accountId)
        assertEquals("4510", ingestedTx.last4Digits)
        assertEquals(2, dao.getAllAccountsSync().size)
    }

    @Test
    fun testC_IdfcAccountEnding2345_DisambiguatedFrom6168() = runBlocking {
        val dao = db.kharchaDao()

        val idfc2345 = AccountEntity(
            id = "acc-idfc-2345",
            name = "IDFC Savings",
            type = "Bank Account",
            bankName = "IDFC FIRST Bank",
            last4Digits = "2345",
            isOwnedByMe = true,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        val idfc6168 = AccountEntity(
            id = "acc-idfc-6168",
            name = "IDFC Digital",
            type = "Bank Account",
            bankName = "IDFC FIRST Bank",
            last4Digits = "6168",
            isOwnedByMe = true,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        dao.insertAccount(idfc2345)
        dao.insertAccount(idfc6168)

        val sms = "Money Sent: Rs.300.00 from IDFC FIRST Bank A/c ending 2345 via UPI to Tea Stall. UTR: 99281726."
        val parseStatus = SmsParser.parseSms("sms-3", "IDFCFB", sms, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)

        val rawTx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        val (ingestedTx, status) = TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = rawTx,
            rawText = "IDFCFB $sms"
        )

        assertNotNull(ingestedTx)
        assertEquals("acc-idfc-2345", ingestedTx!!.accountId)
        assertEquals("2345", ingestedTx.last4Digits)
        assertEquals(2, dao.getAllAccountsSync().size)
    }

    @Test
    fun testD_CreditCardBillPayment_ClassifiedAsCardPaymentAndInternal() = runBlocking {
        val dao = db.kharchaDao()

        val bankAcc = AccountEntity(
            id = "acc-bank-1",
            name = "HDFC Bank",
            type = "Bank Account",
            bankName = "HDFC Bank",
            last4Digits = "4510",
            isOwnedByMe = true,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        val ccAcc = AccountEntity(
            id = "acc-cc-7008",
            name = "HDFC Credit Card",
            type = "Credit Card",
            bankName = "HDFC Bank",
            last4Digits = "7008",
            creditLimit = 100000.0,
            isOwnedByMe = true,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        dao.insertAccount(bankAcc)
        dao.insertAccount(ccAcc)

        val sms = "Payment of Rs.5000.00 towards your credit card ending 7008 has been received. Thank you."
        val parseStatus = SmsParser.parseSms("sms-4", "HDFCBK", sms, System.currentTimeMillis())
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)

        val rawTx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        val (ingestedTx, status) = TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = rawTx,
            rawText = "HDFCBK $sms"
        )

        assertNotNull(ingestedTx)
        assertTrue("Should be marked as internal transfer/card payment", ingestedTx!!.isInternalTransfer)
        assertEquals("CARD_PAYMENT", ingestedTx.transactionType)
    }

    @Test
    fun testE_OwnAccountTransfer_CreatesExactlyTwoPairedTransactions() = runBlocking {
        val dao = db.kharchaDao()

        val sbi = AccountEntity(
            id = "acc-sbi-1",
            name = "SBI Savings",
            type = "Bank Account",
            bankName = "SBI",
            last4Digits = "3321",
            isOwnedByMe = true,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        val hdfc = AccountEntity(
            id = "acc-hdfc-1",
            name = "HDFC Savings",
            type = "Bank Account",
            bankName = "HDFC Bank",
            last4Digits = "4510",
            isOwnedByMe = true,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        dao.insertAccount(sbi)
        dao.insertAccount(hdfc)

        // Side 1: Debit from SBI on 2026-09-25
        val debitSms = "Rs.2000.00 debited from SBI A/c 3321 for self transfer to HDFC on 25-09-2026. Ref: TRF998811."
        val parseDebit = SmsParser.parseSms("sms-5a", "SBIBANK", debitSms, System.currentTimeMillis())
        assertTrue(parseDebit is SmsParser.SmsParseStatus.Success)
        val rawDebit = (parseDebit as SmsParser.SmsParseStatus.Success).transaction
        TransactionIngestionEngine.ingestTransaction(context, rawDebit, "SBIBANK $debitSms")

        // Side 2: Credit to HDFC on same date
        val creditSms = "Rs.2000.00 credited to HDFC Bank A/c 4510 from SBI self transfer on 25-09-2026. Ref: TRF998811."
        val parseCredit = SmsParser.parseSms("sms-5b", "HDFCBK", creditSms, System.currentTimeMillis())
        assertTrue(parseCredit is SmsParser.SmsParseStatus.Success)
        val rawCredit = (parseCredit as SmsParser.SmsParseStatus.Success).transaction
        TransactionIngestionEngine.ingestTransaction(context, rawCredit, "HDFCBK $creditSms")

        val txs = dao.getAllTransactionsSync()
        // Exactly two transactions
        assertEquals(2, txs.size)

        val debitTx = txs.find { it.accountId == "acc-sbi-1" }
        val creditTx = txs.find { it.accountId == "acc-hdfc-1" }

        assertNotNull(debitTx)
        assertNotNull(creditTx)
        assertTrue(debitTx!!.isInternalTransfer)
        assertTrue(creditTx!!.isInternalTransfer)
        assertEquals("INTERNAL_TRANSFER", debitTx.type)
        assertEquals("INTERNAL_TRANSFER", creditTx.type)
        assertEquals(debitTx.transferGroupId, creditTx.transferGroupId)
        assertEquals("DEBIT", debitTx.direction)
        assertEquals("CREDIT", creditTx.direction)
    }

    @Test
    fun testF_SameTransactionReceivedThroughSmsAndNotification_DeduplicatedToOne() = runBlocking {
        val dao = db.kharchaDao()

        val hdfc = AccountEntity(
            id = "acc-hdfc-1",
            name = "HDFC Savings",
            type = "Bank Account",
            bankName = "HDFC Bank",
            last4Digits = "4510",
            isOwnedByMe = true,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        dao.insertAccount(hdfc)

        // 1. Ingest via SMS
        val sms = "Rs.750.00 debited from HDFC Bank A/c 4510 on 25-09-2026 to BLINKIT. Ref: UTR778899."
        val parseSms = SmsParser.parseSms("sms-6", "HDFCBK", sms, System.currentTimeMillis())
        assertTrue(parseSms is SmsParser.SmsParseStatus.Success)
        val rawSmsTx = (parseSms as SmsParser.SmsParseStatus.Success).transaction
        val (tx1, status1) = TransactionIngestionEngine.ingestTransaction(context, rawSmsTx, "HDFCBK $sms")
        assertEquals(IngestionStatus.IMPORTED, status1)

        // 2. Ingest via Notification with same reference
        val notifResult = NotificationParser.parseNotification(
            packageName = "com.snapwork.hdfc",
            title = "HDFC Bank Alert",
            text = "Rs. 750.00 debited from A/c XX4510 for Blinkit. Ref UTR778899",
            bigText = "",
            subText = "",
            postTime = System.currentTimeMillis(),
            existingTransactions = dao.getAllTransactionsSync()
        )

        if (notifResult.status == NotificationStatus.IMPORTED && notifResult.transaction != null) {
            val (_, status2) = TransactionIngestionEngine.ingestTransaction(context, notifResult.transaction, "HDFC Alert")
            assertEquals(IngestionStatus.DUPLICATE, status2)
        } else {
            assertEquals(NotificationStatus.DUPLICATE, notifResult.status)
        }

        // Only 1 transaction in database
        val allTxs = dao.getAllTransactionsSync()
        assertEquals(1, allTxs.size)
        assertEquals(750.0, allTxs.first().amount, 0.001)
    }

    @Test
    fun testG_PromotionalMessageWithAmount_IgnoredByFilter() = runBlocking {
        val promoSms = "Flash Sale Alert! Get flat Rs.25 on wallet load via credit card. Use code: BONUS HOUR. Limited Period Offer. Add Now."
        val isPromo = SmsParser.isPromotionalOrAdvertisementMessage(promoSms)
        assertTrue("Promotional SMS with Rs.25 must be detected as promotional", isPromo)

        val parseStatus = SmsParser.parseSms("sms-promo", "MOBIKWIK", promoSms, System.currentTimeMillis())
        assertTrue("Parser must ignore promotional SMS", parseStatus is SmsParser.SmsParseStatus.Ignored)

        val promoEmail = "Special Sale! Enjoy flat Rs.500 cashback on recharge. Use promo code: FESTIVE500. Offer valid today only."
        val isPromoEmail = SmsParser.isPromotionalOrAdvertisementMessage(promoEmail)
        assertTrue("Promotional email must be detected", isPromoEmail)

        val emailResult = EmailParser.parseEmail("email-promo", "Special Offer", promoEmail, System.currentTimeMillis())
        assertEquals(EmailParserStatus.IGNORED, emailResult.status)
    }
}