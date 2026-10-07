package com.example.utils

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.BillIngestionResult.Duplicate
import com.example.utils.BillIngestionResult.UnlinkedReminder
import com.example.utils.BillIngestionResult.Updated
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CreditCardBillIngestionOwnershipTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var previousUidProvider: () -> String?

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(database)
        previousUidProvider = CreditCardBillIngestionEngine.liveAuthenticatedUidProvider
        context.getSharedPreferences("unlinked_reminders_pref", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = previousUidProvider
        AppDatabase.setTestInstance(null)
        database.close()
    }

    @Test
    fun userACannotMatchOrUpdateUserBCardAccountOrBill() = runBlocking {
        authenticateAs("user-a")
        val dao = database.kharchaDao()
        dao.insertAccount(account("user-b", "account-b", lastBillId = "old-bill"))
        dao.insertCard(card("user-b", "card-b", "account-b", lastBillId = "old-bill"))
        dao.insertTransaction(transaction("user-b", "payment-b", accountId = "account-b"))

        val result = CreditCardBillIngestionEngine.ingestBillInfo(context, bill())

        assertTrue(result is UnlinkedReminder)
        assertEquals("old-bill", dao.getAllAccountsSyncForUser("user-b").single().lastBillMessageId)
        assertEquals("old-bill", dao.getAllCardsSyncForUser("user-b").single().lastBillMessageId)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-b").size)
        assertTrue(dao.getAllAccountsSyncForUser("user-a").isEmpty())
        assertTrue(dao.getAllCardsSyncForUser("user-a").isEmpty())
        assertTrue(dao.getAllTransactionsSyncForUser("user-a").isEmpty())
        assertTrue(
            context.getSharedPreferences("unlinked_reminders_pref", Context.MODE_PRIVATE)
                .all.keys.any { it.startsWith("unlinked_user-a_") }
        )
    }

    @Test
    fun duplicateDetectionOnlyUsesAuthenticatedUsersBill() = runBlocking {
        authenticateAs("user-a")
        val dao = database.kharchaDao()
        dao.insertAccount(account("user-a", "account-a", lastBillId = "incoming-message"))
        dao.insertCard(card("user-a", "card-a", "account-a", lastBillId = "incoming-message"))
        dao.insertAccount(account("user-b", "account-b", lastBillId = "different-bill"))
        dao.insertCard(card("user-b", "card-b", "account-b", lastBillId = "different-bill"))

        val result = CreditCardBillIngestionEngine.ingestBillInfo(
            context,
            bill(messageId = "incoming-message", amount = 700.0)
        )

        assertTrue(result is Duplicate)
        assertEquals("incoming-message", dao.getAllAccountsSyncForUser("user-a").single().lastBillMessageId)
        assertEquals("different-bill", dao.getAllAccountsSyncForUser("user-b").single().lastBillMessageId)
        assertEquals("different-bill", dao.getAllCardsSyncForUser("user-b").single().lastBillMessageId)
    }

    @Test
    fun matchedUpdatesPreserveAuthenticatedOwner() = runBlocking {
        authenticateAs("user-a")
        val dao = database.kharchaDao()
        dao.insertAccount(account("user-a", "account-a"))
        dao.insertCard(card("user-a", "card-a", "account-a"))
        dao.insertAccount(account("user-b", "account-b", outstanding = 10.0))
        dao.insertCard(card("user-b", "card-b", "account-b", outstanding = 10.0))

        val result = CreditCardBillIngestionEngine.ingestBillInfo(
            context,
            bill(messageId = "new-bill", amount = 700.0)
        )

        assertTrue(result is Updated)
        assertEquals(700.0, dao.getAllAccountsSyncForUser("user-a").single().outstandingAmount, 0.0)
        assertEquals(700.0, dao.getAllCardsSyncForUser("user-a").single().outstandingAmount, 0.0)
        assertEquals(10.0, dao.getAllAccountsSyncForUser("user-b").single().outstandingAmount, 0.0)
        assertEquals(10.0, dao.getAllCardsSyncForUser("user-b").single().outstandingAmount, 0.0)
        assertTrue(dao.getAllAccountsSyncForUser("user-a").all { it.userId == "user-a" })
        assertTrue(dao.getAllCardsSyncForUser("user-a").all { it.userId == "user-a" })
    }

    @Test
    fun loggedOutIngestionDoesNotMutateDatabaseOrUnlinkedReminderStorage() = runBlocking {
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = { null }
        val dao = database.kharchaDao()
        dao.insertAccount(account("user-a", "account-a"))
        dao.insertCard(card("user-a", "card-a", "account-a"))
        dao.insertTransaction(transaction("user-a", "tx-a", accountId = "account-a"))

        val accountsBefore = dao.getAllAccountsSyncForUser("user-a")
        val cardsBefore = dao.getAllCardsSyncForUser("user-a")
        val transactionsBefore = dao.getAllTransactionsSyncForUser("user-a")
        val result = CreditCardBillIngestionEngine.ingestBillInfo(context, bill())

        assertTrue(result is com.example.utils.BillIngestionResult.InvalidData)
        assertEquals(accountsBefore, dao.getAllAccountsSyncForUser("user-a"))
        assertEquals(cardsBefore, dao.getAllCardsSyncForUser("user-a"))
        assertEquals(transactionsBefore, dao.getAllTransactionsSyncForUser("user-a"))
        assertTrue(context.getSharedPreferences("unlinked_reminders_pref", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun legacyRecordsAreNotAdoptedAndNewUnlinkedDataIsOwnerNamespaced() = runBlocking {
        authenticateAs("user-a")
        val dao = database.kharchaDao()
        dao.insertAccount(account("legacy:unassigned", "legacy-account"))
        dao.insertCard(card("legacy:unassigned", "legacy-card", "legacy-account"))
        dao.insertTransaction(transaction("legacy:unassigned", "legacy-tx", accountId = "legacy-account"))

        val result = CreditCardBillIngestionEngine.ingestBillInfo(context, bill())

        assertTrue(result is UnlinkedReminder)
        assertEquals(1, dao.getAllAccountsSyncForUser("legacy:unassigned").size)
        assertEquals(1, dao.getAllCardsSyncForUser("legacy:unassigned").size)
        assertEquals(1, dao.getAllTransactionsSyncForUser("legacy:unassigned").size)
        assertTrue(dao.getAllAccountsSyncForUser("user-a").isEmpty())
        val reminderKeys = context.getSharedPreferences("unlinked_reminders_pref", Context.MODE_PRIVATE).all.keys
        assertTrue(reminderKeys.isNotEmpty())
        assertTrue(reminderKeys.all { it.startsWith("unlinked_user-a_") })
    }

    @Test
    fun noRoomBillTransactionIsCreatedByBillIngestion() = runBlocking {
        authenticateAs("user-a")
        val result = CreditCardBillIngestionEngine.ingestBillInfo(context, bill())

        assertTrue(result is UnlinkedReminder)
        val dao = database.kharchaDao()
        assertTrue(dao.getAllTransactionsSyncForUser("user-a").isEmpty())
        assertTrue(dao.getAllAccountsSyncForUser("user-a").isEmpty())
        assertTrue(dao.getAllCardsSyncForUser("user-a").isEmpty())
        assertNotNull(result)
    }

    @Test
    fun emailBillWithMatchingExpectedAndLiveOwnerCanProceed() = runBlocking {
        authenticateAs("user-a")
        val dao = database.kharchaDao()
        dao.insertAccount(account("user-a", "account-a"))
        dao.insertCard(card("user-a", "card-a", "account-a"))

        val result = CreditCardBillIngestionEngine.ingestBillInfo(
            context,
            bill().copy(source = "EMAIL"),
            expectedOwnerUid = "user-a"
        )

        assertTrue(result is Updated)
        assertEquals(500.0, dao.getAllAccountsSyncForUser("user-a").single().outstandingAmount, 0.0)
        assertEquals(500.0, dao.getAllCardsSyncForUser("user-a").single().outstandingAmount, 0.0)
    }

    @Test
    fun rejectedEmailOwnerMismatchAndInvalidOwnersDoNotMutateAnything() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertAccount(account("user-a", "account-a", lastBillId = "before"))
        dao.insertCard(card("user-a", "card-a", "account-a", lastBillId = "before"))
        dao.insertAccount(account("user-b", "account-b", lastBillId = "before"))
        dao.insertCard(card("user-b", "card-b", "account-b", lastBillId = "before"))
        dao.insertTransaction(transaction("user-a", "tx-a", "account-a"))
        dao.insertTransaction(transaction("user-b", "tx-b", "account-b"))
        val accountsBeforeA = dao.getAllAccountsSyncForUser("user-a")
        val cardsBeforeA = dao.getAllCardsSyncForUser("user-a")
        val transactionsBeforeA = dao.getAllTransactionsSyncForUser("user-a")
        val accountsBeforeB = dao.getAllAccountsSyncForUser("user-b")
        val cardsBeforeB = dao.getAllCardsSyncForUser("user-b")
        val transactionsBeforeB = dao.getAllTransactionsSyncForUser("user-b")
        val reminderPrefs = context.getSharedPreferences("unlinked_reminders_pref", Context.MODE_PRIVATE)

        authenticateAs("user-b")
        assertRejectedWithoutMutation("user-a", dao)

        authenticateAs(null)
        assertRejectedWithoutMutation("user-a", dao)

        authenticateAs("user-a")
        assertRejectedWithoutMutation(null, dao)
        assertRejectedWithoutMutation("", dao)
        assertRejectedWithoutMutation("legacy:unassigned", dao)

        authenticateAs("legacy:unassigned")
        assertRejectedWithoutMutation("legacy:unassigned", dao)

        assertEquals(accountsBeforeA, dao.getAllAccountsSyncForUser("user-a"))
        assertEquals(cardsBeforeA, dao.getAllCardsSyncForUser("user-a"))
        assertEquals(transactionsBeforeA, dao.getAllTransactionsSyncForUser("user-a"))
        assertEquals(accountsBeforeB, dao.getAllAccountsSyncForUser("user-b"))
        assertEquals(cardsBeforeB, dao.getAllCardsSyncForUser("user-b"))
        assertEquals(transactionsBeforeB, dao.getAllTransactionsSyncForUser("user-b"))
        assertTrue(reminderPrefs.all.isEmpty())
    }

    private suspend fun assertRejectedWithoutMutation(expectedOwnerUid: String?, dao: com.example.data.dao.KharchaDao) {
        val accountsBeforeA = dao.getAllAccountsSyncForUser("user-a")
        val cardsBeforeA = dao.getAllCardsSyncForUser("user-a")
        val transactionsBeforeA = dao.getAllTransactionsSyncForUser("user-a")
        val accountsBeforeB = dao.getAllAccountsSyncForUser("user-b")
        val cardsBeforeB = dao.getAllCardsSyncForUser("user-b")
        val transactionsBeforeB = dao.getAllTransactionsSyncForUser("user-b")

        val result = CreditCardBillIngestionEngine.ingestBillInfo(
            context,
            bill().copy(source = "EMAIL"),
            expectedOwnerUid
        )
        assertTrue(result is com.example.utils.BillIngestionResult.InvalidData)
        assertTrue(context.getSharedPreferences("unlinked_reminders_pref", Context.MODE_PRIVATE).all.isEmpty())
        assertEquals(accountsBeforeA, dao.getAllAccountsSyncForUser("user-a"))
        assertEquals(cardsBeforeA, dao.getAllCardsSyncForUser("user-a"))
        assertEquals(transactionsBeforeA, dao.getAllTransactionsSyncForUser("user-a"))
        assertEquals(accountsBeforeB, dao.getAllAccountsSyncForUser("user-b"))
        assertEquals(cardsBeforeB, dao.getAllCardsSyncForUser("user-b"))
        assertEquals(transactionsBeforeB, dao.getAllTransactionsSyncForUser("user-b"))
    }

    private fun authenticateAs(uid: String?) {
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = { uid }
    }

    private fun bill(
        messageId: String = "bill-message",
        amount: Double = 500.0
    ) = CreditCardBillInfo(
        bankName = "Example Bank",
        last4Digits = "4628",
        totalAmountDue = amount,
        minimumAmountDue = 50.0,
        source = "SMS",
        messageId = messageId,
        timestamp = 1_800_000_000_000L
    )

    private fun account(
        owner: String,
        id: String,
        lastBillId: String = "",
        outstanding: Double = 0.0
    ) = AccountEntity(
        id = id,
        name = "Example Bank Credit Card",
        type = "Credit Card",
        bankName = "Example Bank",
        last4Digits = "4628",
        createdAt = "2026-10-06T09:00:00Z",
        updatedAt = "2026-10-06T09:00:00Z",
        outstandingAmount = outstanding,
        lastBillMessageId = lastBillId,
        userId = owner
    )

    private fun card(
        owner: String,
        id: String,
        accountId: String,
        lastBillId: String = "",
        outstanding: Double = 0.0
    ) = CardEntity(
        id = id,
        accountId = accountId,
        name = "Example Bank Credit Card",
        type = "Credit Card",
        last4Digits = "4628",
        createdAt = "2026-10-06T09:00:00Z",
        updatedAt = "2026-10-06T09:00:00Z",
        outstandingAmount = outstanding,
        lastBillMessageId = lastBillId,
        userId = owner
    )

    private fun transaction(owner: String, id: String, accountId: String) =
        TransactionEntity(
            id = id,
            type = "INTERNAL_TRANSFER",
            amount = 100.0,
            date = "2026-10-06",
            time = "09:00",
            merchant = "Credit Card Bill Payment",
            categoryId = "cat-transfer",
            subcategoryId = "",
            accountId = accountId,
            paymentMethod = "UPI",
            note = "Credit card payment",
            source = "SMS",
            transactionReference = "",
            transactionType = "CARD_PAYMENT",
            direction = "DEBIT",
            userId = owner
        )
}
