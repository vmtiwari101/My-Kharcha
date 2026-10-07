package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.IngestionStatus
import com.example.utils.SmsParser
import com.example.utils.TransactionIngestionEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmsParserEdgeCaseTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var previousUidProvider: () -> String?

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(database)
        previousUidProvider = TransactionIngestionEngine.liveAuthenticatedUidProvider
        TransactionIngestionEngine.liveAuthenticatedUidProvider = { "sms-test-user" }
        AppDatabase.prepopulateData(database.kharchaDao())
    }

    @After
    fun tearDown() {
        TransactionIngestionEngine.liveAuthenticatedUidProvider = previousUidProvider
        AppDatabase.setTestInstance(null)
        database.close()
    }

    @Test
    fun directionCasesCoverDebitCreditUpiAtmAndTransfers() {
        val cases = listOf(
            "A/c debited by Rs 500" to "EXPENSE",
            "A/c credited with Rs 500" to "INCOME",
            "UPI payment of Rs 500 sent to a merchant" to "EXPENSE",
            "UPI payment received of Rs 500 from a payer" to "INCOME",
            "ATM cash withdrawal of Rs 2,000" to "EXPENSE",
            "Bank transfer received: Rs 1,500 credited to account" to "INCOME",
            "Rs 1,500 transferred from account to another account" to "EXPENSE",
            "Credit Card used for Rs 899 at a merchant" to "EXPENSE",
            "Payment of Rs 5,000 towards your credit card ending 7008 has been received" to "EXPENSE"
        )

        cases.forEach { (body, expected) ->
            assertEquals(body, expected, SmsParser.determineTransactionDirection(body))
        }

        val cardPurchase = parse(
            "Credit Card ending 7008 used for Rs 899.25 at a merchant on 06-10-2026 at 14:32. Ref: TXN99887766"
        )
        assertEquals("EXPENSE", cardPurchase.type)
        assertEquals(899.25, cardPurchase.amount, 0.0)
        assertEquals("2026-10-06", cardPurchase.date)
        assertEquals("14:32", cardPurchase.time)
        assertEquals("TXN99887766", SmsParser.extractTransactionReference(
            "Credit Card used for Rs 899.25. Ref: TXN99887766"
        ))

        val ccPayment = parse(
            "Payment of Rs 5,000 towards your credit card ending 7008 has been received on 2026-10-06 at 15:05."
        )
        assertEquals("Credit Card Bill Payment", ccPayment.merchant)
        assertEquals("EXPENSE", ccPayment.type)
    }

    @Test
    fun smsWithoutIssuerEvidenceDoesNotMapByLastFourAlone() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertAccount(account("matching-account", "sms-test-user", "Bank Account", "1234"))
        val rawTx = parse(
            "A/c XX1234 debited by Rs 100 on 06-10-2026. Ref: ABC1234567",
            id = "unknown-sender",
            address = "UNKNOWN"
        )

        val (saved, status) = TransactionIngestionEngine.ingestTransaction(
            context,
            rawTx,
            "UNKNOWN ${rawTx.merchant} A/c XX1234 debited by Rs 100 on 06-10-2026. Ref: ABC1234567"
        )

        assertNotNull(saved)
        assertTrue(status == IngestionStatus.NEEDS_REVIEW)
        assertTrue(saved!!.needsReview)
        assertEquals("", saved.accountId)
    }

    @Test
    fun ambiguousSameIssuerAccountsRemainForReview() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertAccount(account("first-account", "sms-test-user", "Bank Account", "4510"))
        dao.insertAccount(account("second-account", "sms-test-user", "Bank Account", "4510"))
        val body = "HDFC Bank A/c ending 4510 debited by Rs 100. Ref: ABC1234567"
        val rawTx = parse(body, id = "ambiguous", address = "HDFCBK")

        val (saved, status) = TransactionIngestionEngine.ingestTransaction(
            context,
            rawTx,
            "HDFCBK $body"
        )

        assertNotNull(saved)
        assertEquals(IngestionStatus.NEEDS_REVIEW, status)
        assertTrue(saved!!.needsReview)
        assertEquals("", saved.accountId)
    }

    @Test
    fun sameSmsEventWithGeneratedTransactionIdsIsIdempotent() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertAccount(account("sender-account", "sms-test-user", "Bank Account", "4092", bankName = "HDFC Bank"))
        val body = "A/c XX4092 debited by Rs 250.00 on 06-10-2026 at 13:14"
        val timestamp = 1_791_294_840_000L
        val firstParsed = parse(body, "historical-id-1", "HDFCBK", timestamp)
        val retryParsed = parse(body, "receiver-generated-id-2", "HDFCBK", timestamp)
        val separateEvent = parse(body, "later-event", "HDFCBK", timestamp + 1)

        assertNotEquals(firstParsed.id, retryParsed.id)
        assertEquals(firstParsed.originalReference, retryParsed.originalReference)
        assertNotEquals(firstParsed.originalReference, separateEvent.originalReference)

        val (first, firstStatus) = TransactionIngestionEngine.ingestTransaction(
            context,
            firstParsed,
            "HDFCBK $body"
        )
        val (retry, retryStatus) = TransactionIngestionEngine.ingestTransaction(
            context,
            retryParsed,
            "HDFCBK $body"
        )

        assertNotNull(first)
        assertEquals(IngestionStatus.IMPORTED, firstStatus)
        assertEquals(IngestionStatus.DUPLICATE, retryStatus)
        assertEquals(first?.id, retry?.id)
        assertEquals(1, dao.getAllTransactionsSyncForUser("sms-test-user").size)
    }

    @Test
    fun creditCardBillPaymentIngestsAsPaymentNotOrdinaryExpense() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertAccount(account("source-bank", "sms-test-user", "Bank Account", "1111", bankName = "HDFC Bank"))
        dao.insertAccount(
            account("target-card", "sms-test-user", "Credit Card", "7008", bankName = "HDFC Bank")
        )
        val body = "Rs 500 debited from HDFC Bank A/c ending 1111 as payment towards your credit card ending 7008. Thank you."
        val parsed = parse(body, "card-bill-payment", "HDFCBK")

        val (saved, status) = TransactionIngestionEngine.ingestTransaction(
            context,
            parsed,
            "HDFCBK $body"
        )

        assertNotNull(saved)
        assertTrue(status == IngestionStatus.IMPORTED || status == IngestionStatus.NEEDS_REVIEW)
        assertEquals("CARD_PAYMENT", saved!!.transactionType)
        assertTrue(saved.isInternalTransfer)
        assertFalse(saved.isExpense)
        assertEquals("source-bank", saved.accountId)
        assertEquals("target-card", saved.counterpartyAccountId)
    }

    private fun parse(
        body: String,
        id: String = "sms-parser-case",
        address: String = "TESTBANK",
        timestamp: Long = 1_791_294_840_000L
    ): TransactionEntity {
        val result = SmsParser.parseSms(id, address, body, timestamp)
        assertTrue("Expected parsed transaction, got $result", result is SmsParser.SmsParseStatus.Success)
        return (result as SmsParser.SmsParseStatus.Success).transaction.copy(userId = "sms-test-user")
    }

    private fun account(
        id: String,
        userId: String,
        type: String,
        last4: String,
        bankName: String = ""
    ) = AccountEntity(
        id = id,
        name = id,
        type = type,
        bankName = bankName,
        last4Digits = last4,
        userId = userId
    )
}
