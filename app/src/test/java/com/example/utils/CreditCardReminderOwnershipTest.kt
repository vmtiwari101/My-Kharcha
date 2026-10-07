package com.example.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.receiver.CreditCardReminderReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CreditCardReminderOwnershipTest {
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
        previousUidProvider = CreditCardReminderManager.liveAuthenticatedUidProvider
        context.getSharedPreferences(CreditCardReminderManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        CreditCardReminderManager.liveAuthenticatedUidProvider = previousUidProvider
        AppDatabase.setTestInstance(null)
        database.close()
    }

    @Test
    fun reminderPreferencesAreSeparatedBetweenUsersAndUnavailableWhenLoggedOut() {
        val cardKey = CreditCardReminderManager.getCardKey("Example Bank", "4821")
        authenticateAs("user-a")
        CreditCardReminderManager.setReminderEnabledForCard(context, cardKey, false)
        CreditCardReminderManager.setEnabledOffsetsForCard(context, cardKey, setOf(1))

        authenticateAs("user-b")
        assertTrue(CreditCardReminderManager.isReminderEnabledForCard(context, cardKey))
        assertEquals(CreditCardReminderManager.DEFAULT_OFFSETS, CreditCardReminderManager.getEnabledOffsetsForCard(context, cardKey))

        CreditCardReminderManager.liveAuthenticatedUidProvider = { null }
        assertFalse(CreditCardReminderManager.isReminderEnabledForCard(context, cardKey))
        assertTrue(CreditCardReminderManager.getEnabledOffsetsForCard(context, cardKey).isEmpty())
        CreditCardReminderManager.setReminderEnabledForCard(context, cardKey, true)
        CreditCardReminderManager.setEnabledOffsetsForCard(context, cardKey, setOf(0))

        authenticateAs("user-a")
        assertFalse(CreditCardReminderManager.isReminderEnabledForCard(context, cardKey))
        assertEquals(setOf(1), CreditCardReminderManager.getEnabledOffsetsForCard(context, cardKey))
    }

    @Test
    fun oldUserAlarmIsRejectedAfterLogoutOrUserSwitch() {
        assertFalse(CreditCardReminderManager.isScheduledOwnerValid(null, "user-a"))
        assertFalse(CreditCardReminderManager.isScheduledOwnerValid("", "user-a"))
        assertFalse(CreditCardReminderManager.isScheduledOwnerValid("user-b", "user-a"))
        assertFalse(
            CreditCardReminderManager.isScheduledOwnerValid(
                "legacy:unassigned",
                "legacy:unassigned"
            )
        )
        assertTrue(CreditCardReminderManager.isScheduledOwnerValid("user-a", "user-a"))
    }

    @Test
    fun loggedOutReceiverDoesNotTouchRoomData() {
        val dao = database.kharchaDao()
        runBlocking {
            dao.insertAccount(account("user-b", "account-b", outstanding = 100.0))
            dao.insertCard(card("user-b", "card-b", "account-b", outstanding = 100.0))
            dao.insertTransaction(transaction("user-b", "tx-b", "account-b"))
        }
        val beforeAccounts = runBlocking { dao.getAllAccountsSyncForUser("user-b") }
        val beforeCards = runBlocking { dao.getAllCardsSyncForUser("user-b") }
        val beforeTransactions = runBlocking { dao.getAllTransactionsSyncForUser("user-b") }
        CreditCardReminderManager.liveAuthenticatedUidProvider = { null }

        CreditCardReminderReceiver().onReceive(
            context,
            reminderIntent(ownerUid = "user-a")
        )

        assertEquals(beforeAccounts, runBlocking { dao.getAllAccountsSyncForUser("user-b") })
        assertEquals(beforeCards, runBlocking { dao.getAllCardsSyncForUser("user-b") })
        assertEquals(beforeTransactions, runBlocking { dao.getAllTransactionsSyncForUser("user-b") })
    }

    @Test
    fun oldAlarmCannotBeProcessedAsNewUsersReminder() {
        val dao = database.kharchaDao()
        runBlocking {
            dao.insertAccount(account("user-b", "account-b", outstanding = 100.0))
            dao.insertCard(card("user-b", "card-b", "account-b", outstanding = 100.0))
            dao.insertTransaction(transaction("user-b", "tx-b", "account-b"))
        }
        CreditCardReminderManager.liveAuthenticatedUidProvider = { "user-b" }

        CreditCardReminderReceiver().onReceive(
            context,
            reminderIntent(ownerUid = "user-a")
        )

        assertEquals(1, runBlocking { dao.getAllAccountsSyncForUser("user-b").size })
        assertEquals(1, runBlocking { dao.getAllCardsSyncForUser("user-b").size })
        assertEquals(1, runBlocking { dao.getAllTransactionsSyncForUser("user-b").size })
    }

    @Test
    fun reschedulingOnlyCreatesAlarmsForLiveUsersRecords() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertAccount(account("user-a", "account-a", dueDate = 28, outstanding = 500.0))
        dao.insertCard(card("user-a", "card-a", "account-a", dueDate = 28, outstanding = 500.0))
        dao.insertAccount(account("user-b", "account-b", dueDate = 28, outstanding = 900.0))
        dao.insertCard(card("user-b", "card-b", "account-b", dueDate = 28, outstanding = 900.0))
        authenticateAs("user-a")

        CreditCardReminderManager.rescheduleAllForOwner(context, "user-a")

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val userARequestCode = CreditCardReminderManager.getRequestCode(
            "user-a_${CreditCardReminderManager.getCardKey("Example Bank", "4821")}",
            28,
            7
        )
        val userBRequestCode = CreditCardReminderManager.getRequestCode(
            "user-b_${CreditCardReminderManager.getCardKey("Example Bank", "4821")}",
            28,
            7
        )
        val intent = Intent(context, CreditCardReminderReceiver::class.java).apply {
            action = CreditCardReminderReceiver.ACTION_CREDIT_CARD_DUE_REMINDER
        }
        val userAAlarm = PendingIntent.getBroadcast(
            context,
            userARequestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        val userBAlarm = PendingIntent.getBroadcast(
            context,
            userBRequestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        assertNotNull(alarmManager)
        assertNotNull("Expected live user's reminder alarm", userAAlarm)
        assertNull("Other user's reminder alarm must not be created", userBAlarm)
    }

    @Test
    fun rescheduleRejectsLegacyOwner() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertAccount(account("legacy:unassigned", "legacy-account", dueDate = 28, outstanding = 500.0))
        dao.insertCard(card("legacy:unassigned", "legacy-card", "legacy-account", dueDate = 28, outstanding = 500.0))
        authenticateAs("user-a")

        CreditCardReminderManager.rescheduleAllForOwner(context, "legacy:unassigned")

        assertTrue(dao.getAllAccountsSyncForUser("user-a").isEmpty())
    }

    private fun authenticateAs(uid: String) {
        CreditCardReminderManager.liveAuthenticatedUidProvider = { uid }
    }

    private fun reminderIntent(ownerUid: String) = Intent(
        context,
        CreditCardReminderReceiver::class.java
    ).apply {
        action = CreditCardReminderReceiver.ACTION_CREDIT_CARD_DUE_REMINDER
        putExtra(CreditCardReminderReceiver.EXTRA_CARD_KEY, "examplebank_4821")
        putExtra(CreditCardReminderReceiver.EXTRA_OWNER_UID, ownerUid)
        putExtra(CreditCardReminderReceiver.EXTRA_BANK_NAME, "Example Bank")
        putExtra(CreditCardReminderReceiver.EXTRA_LAST4, "4821")
        putExtra(CreditCardReminderReceiver.EXTRA_DUE_DATE, 28)
        putExtra(CreditCardReminderReceiver.EXTRA_OFFSET_DAYS, 7)
        putExtra(CreditCardReminderReceiver.EXTRA_ACCOUNT_ID, "account-a")
    }

    private fun account(
        owner: String,
        id: String,
        dueDate: Int = 0,
        outstanding: Double = 0.0
    ) = AccountEntity(
        id = id,
        name = "Example Bank Credit Card",
        type = "Credit Card",
        bankName = "Example Bank",
        last4Digits = "4821",
        createdAt = "",
        updatedAt = "",
        outstandingAmount = outstanding,
        dueDate = dueDate,
        userId = owner
    )

    private fun card(
        owner: String,
        id: String,
        accountId: String,
        dueDate: Int = 0,
        outstanding: Double = 0.0
    ) = CardEntity(
        id = id,
        accountId = accountId,
        name = "Example Bank Credit Card",
        type = "Credit Card",
        last4Digits = "4821",
        createdAt = "",
        updatedAt = "",
        outstandingAmount = outstanding,
        dueDate = dueDate,
        userId = owner
    )

    private fun transaction(owner: String, id: String, accountId: String) =
        TransactionEntity(
            id = id,
            type = "EXPENSE",
            amount = 100.0,
            date = "2026-10-06",
            time = "09:00",
            merchant = "Example Store",
            categoryId = "category",
            subcategoryId = "",
            accountId = accountId,
            paymentMethod = "CARD",
            note = "",
            source = "SMS",
            transactionReference = "",
            userId = owner
        )
}
