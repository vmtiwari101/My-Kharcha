package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.receiver.SmsReceiver
import com.example.utils.BillIngestionResult
import com.example.utils.CreditCardBillIngestionEngine
import com.example.utils.CreditCardBillInfo
import com.example.utils.IngestionStatus
import com.example.utils.SmsParser
import com.example.utils.TransactionIngestionEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsOwnershipTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private val receiver = SmsReceiver()
    private var previousParserUidProvider: () -> String? = { null }
    private var previousTransactionUidProvider: () -> String? = { null }
    private var previousBillUidProvider: () -> String? = { null }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(database)
        previousParserUidProvider = SmsParser.liveAuthenticatedUidProvider
        previousTransactionUidProvider = TransactionIngestionEngine.liveAuthenticatedUidProvider
        previousBillUidProvider = CreditCardBillIngestionEngine.liveAuthenticatedUidProvider
        setOwner("sms-test-owner")
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("sms_tracking_enabled", true)
            .commit()
    }

    @After
    fun tearDown() {
        SmsParser.liveAuthenticatedUidProvider = previousParserUidProvider
        TransactionIngestionEngine.liveAuthenticatedUidProvider = previousTransactionUidProvider
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = previousBillUidProvider
        AppDatabase.setTestInstance(null)
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        database.close()
    }

    @Test
    fun loggedOutIncomingAndHistoricalSmsDoNotMutate() = runBlocking {
        val dao = database.kharchaDao()
        setOwner(null)

        val receiverProcessed = receiver.processIncomingSmsNow(
            context,
            sender,
            transactionBody,
            timestamp,
            expectedOwnerUid = "user-a"
        )
        val scanResult = SmsParser.processSmsList(
            context,
            listOf(SmsParser.RawSms("historical-1", sender, transactionBody, timestamp))
        )

        assertFalse(receiverProcessed)
        assertEquals(0, scanResult.importedCount)
        assertTrue(dao.getAllTransactionsSync().isEmpty())
    }

    @Test
    fun receiverWritesSmsOnlyForCapturedOwnerAndDoesNotMatchForeignAccountOrCard() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertAccount(account("user-b", "shared-account"))
        dao.insertCard(card("user-b", "shared-card", "shared-account"))
        setOwner("user-a")

        val processed = receiver.processIncomingSmsNow(
            context,
            sender,
            transactionBody,
            timestamp,
            expectedOwnerUid = "user-a"
        )

        assertTrue(processed)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
        val imported = dao.getAllTransactionsSyncForUser("user-a").single()
        assertEquals("user-a", imported.userId)
        assertTrue(dao.getAllTransactionsSyncForUser("user-b").isEmpty())
        assertEquals(1, dao.getAllAccountsSyncForUser("user-b").size)
        assertEquals(1, dao.getAllCardsSyncForUser("user-b").size)
        assertTrue(dao.getAllAccountsSyncForUser("user-a").all { it.userId == "user-a" })
        assertTrue(dao.getAllCardsSyncForUser("user-a").all { it.userId == "user-a" })
        assertTrue(imported.accountId != "shared-account")
        assertTrue(imported.cardId != "shared-card")
    }

    @Test
    fun ownerSwitchDuringSmsParsingFailsClosed() = runBlocking {
        val dao = database.kharchaDao()
        var authReads = 0
        SmsParser.liveAuthenticatedUidProvider = {
            authReads++
            if (authReads <= 2) "user-a" else "user-b"
        }
        TransactionIngestionEngine.liveAuthenticatedUidProvider = { "user-b" }

        val processed = receiver.processIncomingSmsNow(
            context,
            sender,
            transactionBody,
            timestamp,
            expectedOwnerUid = "user-a"
        )

        assertFalse(processed)
        assertTrue(dao.getAllTransactionsSyncForUser("user-a").isEmpty())
        assertTrue(dao.getAllTransactionsSyncForUser("user-b").isEmpty())
    }

    @Test
    fun ownerSwitchAfterTransactionEngineStartsFailsBeforeItsRoomWrite() = runBlocking {
        var authReads = 0
        TransactionIngestionEngine.liveAuthenticatedUidProvider = {
            authReads++
            if (authReads == 1) "user-a" else "user-b"
        }

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(
            context,
            transaction("user-a", "switching-sms"),
            transactionBody,
            expectedOwnerUid = "user-a"
        )

        assertEquals(null, ingested)
        assertEquals(IngestionStatus.FAILED, status)
        assertTrue(database.kharchaDao().getAllTransactionsSyncForUser("user-a").isEmpty())
        assertTrue(database.kharchaDao().getAllTransactionsSyncForUser("user-b").isEmpty())
    }

    @Test
    fun ownerSwitchInsideBillEngineFailsBeforeRoomMutation() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertAccount(account("user-a", "owned-account"))
        var authReads = 0
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = {
            authReads++
            if (authReads == 1) "user-a" else "user-b"
        }
        val accountBefore = dao.getAllAccountsSyncForUser("user-a").single()

        val result = CreditCardBillIngestionEngine.ingestBillInfo(
            context,
            CreditCardBillInfo(
                bankName = "Test Bank",
                last4Digits = "4092",
                totalAmountDue = 250.0,
                source = "SMS",
                messageId = "switching-bill"
            ),
            expectedOwnerUid = "user-a"
        )

        assertTrue(result is BillIngestionResult.InvalidData)
        assertEquals(accountBefore, dao.getAllAccountsSyncForUser("user-a").single())
        assertTrue(dao.getAllAccountsSyncForUser("user-b").isEmpty())
    }

    @Test
    fun trackingDisabledPreventsIncomingIngestion() = runBlocking {
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("sms_tracking_enabled", false)
            .commit()

        val processed = receiver.processIncomingSmsNow(
            context,
            sender,
            transactionBody,
            timestamp,
            expectedOwnerUid = "sms-test-owner"
        )
        assertFalse(processed)
        assertTrue(database.kharchaDao().getAllTransactionsSync().isEmpty())
    }

    @Test
    fun historicalSmsDuplicateLookupIsScopedToCurrentOwner() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertTransaction(
            transaction("user-b", "foreign-duplicate").copy(
                amount = 500.0,
                transactionReference = "11223344",
                originalReference = "SMS-REF-11223344",
                last4Digits = "4092"
            )
        )
        setOwner("user-a")

        val result = SmsParser.processSmsList(
            context,
            listOf(
                SmsParser.RawSms(
                    "historical-duplicate",
                    sender,
                    "Your A/C XX4092 debited by Rs. 500.00 on 20-09-26 at Amazon. Ref: 11223344",
                    timestamp
                )
            )
        )

        assertEquals(1, result.importedCount)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-b").size)
    }

    @Test
    fun legacyOwnerIsRejectedAndNeverAdopted() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("legacy:unassigned", "legacy-existing"))
        setOwner("legacy:unassigned")

        val incoming = receiver.processIncomingSmsNow(
            context,
            sender,
            transactionBody,
            timestamp,
            expectedOwnerUid = "legacy:unassigned"
        )
        val historical = SmsParser.processSmsList(
            context,
            listOf(SmsParser.RawSms("legacy-scan", sender, transactionBody, timestamp))
        )

        assertFalse(incoming)
        assertEquals(0, historical.importedCount)
        assertEquals(1, dao.getAllTransactionsSyncForUser("legacy:unassigned").size)
    }

    @Test
    fun incomingSmsUsesDirectImmediateIngestionResult() = runBlocking {
        setOwner("user-a")

        val processed = receiver.processIncomingSmsNow(
            context,
            sender,
            transactionBody,
            timestamp,
            expectedOwnerUid = "user-a"
        )

        assertTrue(processed)
        assertEquals(1, database.kharchaDao().getAllTransactionsSyncForUser("user-a").size)
    }

    private fun setOwner(uid: String?) {
        SmsParser.liveAuthenticatedUidProvider = { uid }
        TransactionIngestionEngine.liveAuthenticatedUidProvider = { uid }
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = { uid }
    }

    private fun transaction(owner: String, id: String) = TransactionEntity(
        id = id,
        type = "EXPENSE",
        amount = 500.0,
        date = "2026-09-20",
        time = "12:00",
        merchant = "Amazon",
        categoryId = "cat-other",
        subcategoryId = "",
        accountId = "",
        paymentMethod = "UPI",
        note = "",
        source = "SMS",
        transactionReference = "",
        userId = owner
    )

    private fun account(owner: String, id: String) = AccountEntity(
        id = id,
        name = "Test Bank",
        type = "Savings Account",
        bankName = "Test Bank",
        last4Digits = "4092",
        userId = owner
    )

    private fun card(owner: String, id: String, accountId: String) = CardEntity(
        id = id,
        accountId = accountId,
        name = "Test Card",
        type = "Debit Card",
        last4Digits = "4092",
        userId = owner
    )

    private companion object {
        const val sender = "VM-EXAMPLE"
        const val transactionBody =
            "Your A/C XX4092 debited by Rs. 500.00 on 20-09-26 at Amazon. Ref: 11223344"
        const val timestamp = 1_800_000_000_000L
    }
}
