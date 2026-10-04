package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.SmsParser
import com.example.utils.NotificationParser
import com.example.utils.TransactionIngestionEngine
import com.example.utils.IngestionStatus
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutomaticScanningTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(db)
        
        runBlocking {
            val dao = db.kharchaDao()
            AppDatabase.prepopulateData(dao)
        }
    }

    @After
    fun tearDown() {
        AppDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testSmsToAutomaticExpense() = runBlocking {
        // A. New debit SMS -> automatic Expense.
        val dao = db.kharchaDao()
        val smsId = "sms-exp-1"
        val sender = "HDFC-BANK"
        val body = "Your A/C ending 4092 has been debited with Rs. 1500.00 for online purchase at Swiggy."
        val timestamp = System.currentTimeMillis()

        val parseStatus = SmsParser.parseSms(smsId, sender, body, timestamp)
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction

        assertEquals("EXPENSE", tx.type)
        assertEquals(1500.0, tx.amount, 0.01)
        assertEquals("4092", tx.last4Digits)
        assertEquals("Swiggy", tx.merchant)

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, tx, body)
        assertEquals(IngestionStatus.IMPORTED, status)
        assertNotNull(ingested)

        // Verify it exists in database
        val saved = dao.getAllTransactionsSync()
        assertTrue(saved.any { it.amount == 1500.0 && it.type == "EXPENSE" })
    }

    @Test
    fun testSmsToAutomaticIncome() = runBlocking {
        // B. New credit SMS -> correct Income when genuinely external income.
        val dao = db.kharchaDao()
        val smsId = "sms-inc-1"
        val sender = "SBI-BANK"
        val body = "Your A/C ending 1234 has been credited with Rs. 50000.00 for Salary from Acme Corp."
        val timestamp = System.currentTimeMillis()

        val parseStatus = SmsParser.parseSms(smsId, sender, body, timestamp)
        assertTrue(parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction

        assertEquals("INCOME", tx.type)
        assertEquals(50000.0, tx.amount, 0.01)

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, tx, body)
        println("[Test Income] Ingest status: $status, tx: $ingested")
        assertEquals(IngestionStatus.IMPORTED, status)

        val saved = dao.getAllTransactionsSync()
        println("[Test Income] Total saved txs: ${saved.size}, txs: ${saved.map { "${it.id}: ${it.type} ${it.amount} acc=${it.accountId}" }}")
        // Check if our specific transaction exists, allowing for other prepopulated transactions
        assertTrue("Could not find the 50000.0 INCOME transaction", saved.any { it.amount == 50000.0 && it.type == "INCOME" })
    }

    @Test
    fun testSmsOwnAccountTransfer() = runBlocking {
        // C. New own-account transfer -> Internal Transfer.
        val dao = db.kharchaDao()
        
        // Setup two owned accounts in database for transfer
        val accHdfc = AccountEntity("acc-hdfc-test", "HDFC Bank Salary", "Bank Account", "HDFC", "4092", "landmark", "#1E40AF", true, true, true, "2026-09-23", "2026-09-23")
        val accSbi = AccountEntity("acc-sbi-test", "SBI Savings", "Bank Account", "SBI", "1234", "landmark", "#14B8A6", true, false, true, "2026-09-23", "2026-09-23")
        dao.insertAccount(accHdfc)
        dao.insertAccount(accSbi)

        // Outgoing transaction from HDFC to SBI
        val txOut = TransactionEntity(
            id = "tx-out-1",
            type = "EXPENSE",
            amount = 3000.0,
            date = "2026-09-23",
            time = "10:00",
            merchant = "SBI Account",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "acc-hdfc-test",
            paymentMethod = "Bank Transfer",
            note = "Transfer to SBI",
            source = "SMS",
            transactionReference = "TXN3000",
            originalReference = "sms-ref-out",
            last4Digits = "4092",
            createdAt = "2026-09-23T10:00:00Z",
            updatedAt = "2026-09-23T10:00:00Z"
        )

        // Incoming transaction to SBI from HDFC
        val txIn = TransactionEntity(
            id = "tx-in-1",
            type = "INCOME",
            amount = 3000.0,
            date = "2026-09-23",
            time = "10:00",
            merchant = "HDFC Account",
            categoryId = "cat-income-other",
            subcategoryId = "",
            accountId = "acc-sbi-test",
            paymentMethod = "Bank Transfer",
            note = "Transfer from HDFC",
            source = "SMS",
            transactionReference = "TXN3000",
            originalReference = "sms-ref-in",
            last4Digits = "1234",
            createdAt = "2026-09-23T10:00:00Z",
            updatedAt = "2026-09-23T10:00:00Z"
        )

        val (ingestedOut, statusOut) = TransactionIngestionEngine.ingestTransaction(context, txOut, "Sent Rs. 3000 to SBI ending 1234")
        val (ingestedIn, statusIn) = TransactionIngestionEngine.ingestTransaction(context, txIn, "Received Rs. 3000 from HDFC ending 4092")
        println("[Test Transfer] statusOut=$statusOut, statusIn=$statusIn")

        assertEquals(IngestionStatus.IMPORTED, statusOut)
        val savedTxs = dao.getAllTransactionsSync()
        println("[Test Transfer] Total saved: ${savedTxs.size}, items: ${savedTxs.map { "${it.id}: type=${it.type} txType=${it.transactionType} internal=${it.isInternalTransfer} amount=${it.amount} acc=${it.accountId}" }}")
        
        // Assert that we have detected internal transfers
        assertTrue("No internal transfer detected", savedTxs.any { it.isInternalTransfer && it.amount == 3000.0 })
    }

    @Test
    fun testSmsBankToOwnCreditCard() = runBlocking {
        // D. Bank -> own credit card -> Card Payment/Internal Transfer.
        val dao = db.kharchaDao()

        val accHdfc = AccountEntity("acc-hdfc", "HDFC Bank Salary", "Bank Account", "HDFC", "4092", "landmark", "#1E40AF", true, true, true, "2026-09-23", "2026-09-23")
        val accAxis = AccountEntity("acc-axis", "AXIS Credit Card", "Credit Card", "Axis", "8134", "credit-card", "#991B1B", true, false, true, "2026-09-23", "2026-09-23")
        dao.insertAccount(accHdfc)
        dao.insertAccount(accAxis)

        val tx = TransactionEntity(
            id = "tx-cc-pay",
            type = "EXPENSE",
            amount = 4500.0,
            date = "2026-09-23",
            time = "12:00",
            merchant = "Axis Card Payment",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "Bank Transfer",
            note = "Payment of Rs 4500 to Axis card ending 8134",
            source = "SMS",
            transactionReference = "REF99923",
            originalReference = "sms-pay-cc",
            last4Digits = "8134",
            createdAt = "2026-09-23T12:00:00Z",
            updatedAt = "2026-09-23T12:00:00Z"
        )

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, tx, "Paid Rs 4500 to Axis card ending 8134")
        println("[Test CardPay] Ingest status: $status, tx: $ingested")
        assertNotNull(ingested)
        assertTrue(ingested!!.isInternalTransfer)
        assertEquals("acc-axis", ingested.counterpartyAccountId)
    }

    @Test
    fun testSmsBankToCashWithdrawal() = runBlocking {
        // E. Bank -> Cash withdrawal -> Cash Transfer.
        val dao = db.kharchaDao()

        val accHdfc = AccountEntity("acc-hdfc-test", "HDFC Bank", "Bank Account", "HDFC", "4092", "landmark", "#1E40AF", true, true, true, "2026-09-23", "2026-09-23")
        val accCash = AccountEntity("acc-cash-test", "Cash in Hand", "Cash", "Cash", "", "banknote", "#B45309", true, false, true, "2026-09-23", "2026-09-23")
        dao.insertAccount(accHdfc)
        dao.insertAccount(accCash)

        val tx = TransactionEntity(
            id = "tx-cash-atm",
            type = "EXPENSE",
            amount = 10000.0,
            date = "2026-09-23",
            time = "14:00",
            merchant = "HDFC ATM",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "acc-hdfc-test",
            paymentMethod = "ATM Withdrawal",
            note = "Withdrawn Cash 10000 at ATM",
            source = "SMS",
            transactionReference = "ATM-WITHDRAW",
            originalReference = "sms-atm",
            last4Digits = "4092",
            createdAt = "2026-09-23T14:00:00Z",
            updatedAt = "2026-09-23T14:00:00Z"
        )

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, tx, "Withdrawn Cash 10000 at ATM ending 4092")
        assertNotNull(ingested)
        assertTrue(ingested!!.isInternalTransfer)
        assertEquals("acc-cash-test", ingested.counterpartyAccountId)
    }

    @Test
    fun testSmsAndNotificationDeduplication() = runBlocking {
        // F, H. Same transaction via SMS + notification + Truecaller notification -> one transaction.
        val dao = db.kharchaDao()

        val txSms = TransactionEntity(
            id = "tx-sms-dup",
            type = "EXPENSE",
            amount = 250.0,
            date = "2026-09-23",
            time = "15:00",
            merchant = "Zomato",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "SMS alert",
            source = "SMS",
            transactionReference = "UPI-ZOMATO-9923",
            originalReference = "sms-zom",
            last4Digits = "4092",
            createdAt = "2026-09-23T15:00:00Z",
            updatedAt = "2026-09-23T15:00:00Z"
        )

        val txNotif = TransactionEntity(
            id = "tx-notif-dup",
            type = "EXPENSE",
            amount = 250.0,
            date = "2026-09-23",
            time = "15:00",
            merchant = "Zomato",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "App notification",
            source = "NOTIFICATION",
            transactionReference = "UPI-ZOMATO-9923",
            originalReference = "notif-zom",
            last4Digits = "4092",
            createdAt = "2026-09-23T15:00:00Z",
            updatedAt = "2026-09-23T15:00:00Z"
        )

        val (ingestedSms, statusSms) = TransactionIngestionEngine.ingestTransaction(context, txSms, "Sent Rs.250 to Zomato via HDFC account ending 4092")
        val (ingestedNotif, statusNotif) = TransactionIngestionEngine.ingestTransaction(context, txNotif, "Sent Rs.250 to Zomato via HDFC account ending 4092")

        assertEquals(IngestionStatus.IMPORTED, statusSms)
        assertEquals(IngestionStatus.DUPLICATE, statusNotif)

        val totalSaved = dao.getAllTransactionsSync().filter { it.amount == 250.0 }
        assertEquals(1, totalSaved.size)
    }

    @Test
    fun testSmsAndGmailDeduplication() = runBlocking {
        // G. Same transaction via SMS + Gmail -> one transaction.
        val dao = db.kharchaDao()

        val txSms = TransactionEntity(
            id = "tx-sms-email-dup",
            type = "EXPENSE",
            amount = 99.0,
            date = "2026-09-23",
            time = "16:00",
            merchant = "Spotify",
            categoryId = "cat-entertainment",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "Spotify via SMS",
            source = "SMS",
            transactionReference = "SPOTIFY-99",
            originalReference = "sms-spot",
            last4Digits = "4092",
            createdAt = "2026-09-23T16:00:00Z",
            updatedAt = "2026-09-23T16:00:00Z"
        )

        val txEmail = TransactionEntity(
            id = "tx-email-dup",
            type = "EXPENSE",
            amount = 99.0,
            date = "2026-09-23",
            time = "16:00",
            merchant = "Spotify",
            categoryId = "cat-entertainment",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "Spotify via Email",
            source = "EMAIL",
            transactionReference = "SPOTIFY-99",
            originalReference = "email-spot",
            last4Digits = "4092",
            createdAt = "2026-09-23T16:00:00Z",
            updatedAt = "2026-09-23T16:00:00Z"
        )

        val (ingestedSms, statusSms) = TransactionIngestionEngine.ingestTransaction(context, txSms, "Paid Rs.99 to Spotify via HDFC 4092")
        val (ingestedEmail, statusEmail) = TransactionIngestionEngine.ingestTransaction(context, txEmail, "Paid Rs.99 to Spotify via HDFC 4092")

        assertEquals(IngestionStatus.IMPORTED, statusSms)
        assertEquals(IngestionStatus.DUPLICATE, statusEmail)

        val totalSaved = dao.getAllTransactionsSync().filter { it.amount == 99.0 }
        assertEquals(1, totalSaved.size)
    }

    @Test
    fun testOtpIgnored() {
        // I. OTP -> ignored.
        val parseStatus = SmsParser.parseSms("otp-1", "BANK", "Your OTP for login is 992345. Valid for 10 minutes.", System.currentTimeMillis())
        assertEquals(SmsParser.SmsParseStatus.Ignored, parseStatus)
    }

    @Test
    fun testPromotionalSmsIgnored() {
        // J. Promotional SMS -> ignored.
        val parseStatus = SmsParser.parseSms("promo-1", "AD-OFFER", "Get 50% off on your next burger! Use code BURGER50.", System.currentTimeMillis())
        assertEquals(SmsParser.SmsParseStatus.Ignored, parseStatus)
    }

    @Test
    fun testBalanceInquiryIgnored() {
        // K. Balance inquiry -> ignored.
        val parseStatus = SmsParser.parseSms("bal-1", "BANK", "Your account balance for A/C 4092 is INR 102540.50.", System.currentTimeMillis())
        assertEquals(SmsParser.SmsParseStatus.Ignored, parseStatus)
    }
}
