package com.example.service

import android.app.Notification
import android.content.Context
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.CreditCardBillIngestionEngine
import com.example.utils.TransactionIngestionEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KharchaNotificationListenerOwnershipTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var service: KharchaNotificationListenerService
    private var previousServiceUidProvider: () -> String? = { null }
    private var previousTransactionUidProvider: () -> String? = { null }
    private var previousBillUidProvider: () -> String? = { null }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(database)
        previousServiceUidProvider = KharchaNotificationListenerService.liveAuthenticatedUidProvider
        previousTransactionUidProvider = TransactionIngestionEngine.liveAuthenticatedUidProvider
        previousBillUidProvider = CreditCardBillIngestionEngine.liveAuthenticatedUidProvider
        service = KharchaNotificationListenerService()
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("notification_tracking_enabled", true)
            .commit()
    }

    @After
    fun tearDown() {
        KharchaNotificationListenerService.liveAuthenticatedUidProvider = previousServiceUidProvider
        TransactionIngestionEngine.liveAuthenticatedUidProvider = previousTransactionUidProvider
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = previousBillUidProvider
        AppDatabase.setTestInstance(null)
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        database.close()
    }

    @Test
    fun authenticatedNotificationIngestsForCurrentOwnerOnly() = runBlocking {
        authenticateAs("user-a")
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("user-b", "foreign-existing"))
        dao.insertAccount(account("user-b", "foreign-account"))
        dao.insertCard(card("user-b", "foreign-card", "foreign-account"))

        val result = processAs(owner = "user-a")

        assertEquals(0, result.second)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-b").size)
        assertTrue(dao.getAllTransactionsSyncForUser("user-a").all { it.userId == "user-a" })
        assertEquals(1, dao.getAllAccountsSyncForUser("user-b").size)
        assertEquals(1, dao.getAllCardsSyncForUser("user-b").size)
        assertTrue(dao.getAllAccountsSyncForUser("user-a").isEmpty())
        assertTrue(dao.getAllCardsSyncForUser("user-a").isEmpty())
    }

    @Test
    fun loggedOutNotificationDoesNotIngestOrMutate() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("user-a", "existing-a"))
        authenticateAs(null)

        val result = processAs(owner = "user-a")

        assertEquals(0, result.first)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
        assertTrue(dao.getAllTransactionsSyncForUser("user-b").isEmpty())
    }

    @Test
    fun staleUserANotificationCannotBeProcessedAsUserB() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("user-b", "existing-b"))
        authenticateAs("user-b")

        val result = processAs(owner = "user-a")

        assertEquals(0, result.first)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-b").size)
        assertTrue(dao.getAllTransactionsSyncForUser("user-a").isEmpty())
    }

    @Test
    fun activeNotificationBatchFailsClosedWhenOwnerChanges() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("user-b", "existing-b"))
        authenticateAs("user-b")

        val result = service.processActiveNotificationBatch(
            context,
            arrayOf(statusBarNotification()),
            expectedOwnerUid = "user-a"
        )

        assertEquals(0, result.first)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-b").size)
        assertTrue(dao.getAllTransactionsSyncForUser("user-a").isEmpty())
    }

    @Test
    fun activeNotificationBatchProcessesOnlyItsAuthenticatedOwner() = runBlocking {
        authenticateAs("user-a")
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("user-b", "existing-b"))

        service.processActiveNotificationBatch(
            context,
            arrayOf(statusBarNotification()),
            expectedOwnerUid = "user-a"
        )

        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-b").size)
        assertTrue(dao.getAllTransactionsSyncForUser("user-a").all { it.userId == "user-a" })
    }

    @Test
    fun activeScanDoesNotRunWhenLoggedOutOrTrackingDisabled() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("user-a", "existing-a"))
        authenticateAs(null)

        assertEquals(0 to 0, service.processActiveNotifications(context))

        authenticateAs("user-a")
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("notification_tracking_enabled", false)
            .commit()
        assertEquals(0 to 0, service.processActiveNotifications(context))
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
    }

    @Test
    fun legacyAndBlankOwnersAreNeverAdopted() = runBlocking {
        val dao = database.kharchaDao()
        authenticateAs("legacy:unassigned")
        assertEquals(0, processAs("legacy:unassigned").first)
        authenticateAs("")
        assertEquals(0, processAs("").first)
        assertTrue(dao.getAllTransactionsSyncForUser("legacy:unassigned").isEmpty())
        assertTrue(dao.getAllTransactionsSyncForUser("").isEmpty())
    }

    @Test
    fun activeBatchRespectsNotificationTrackingPreference() = runBlocking {
        authenticateAs("user-a")
        val dao = database.kharchaDao()
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("notification_tracking_enabled", false)
            .commit()

        val result = service.processActiveNotificationBatch(
            context,
            arrayOf(statusBarNotification()),
            expectedOwnerUid = "user-a"
        )

        assertEquals(0 to 0, result)
        assertTrue(dao.getAllTransactionsSyncForUser("user-a").isEmpty())
    }

    @Test
    fun postedNotificationRespectsTrackingPreference() = runBlocking {
        authenticateAs("user-a")
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("notification_tracking_enabled", false)
            .commit()

        val result = service.processSingleNotificationSync(
            statusBarNotification(),
            context,
            expectedOwnerUid = "user-a"
        )

        assertEquals(0 to 0, result)
        assertTrue(database.kharchaDao().getAllTransactionsSyncForUser("user-a").isEmpty())
    }

    private suspend fun processAs(owner: String): Pair<Int, Int> =
        service.processSingleNotificationSync(
            statusBarNotification(),
            context,
            expectedOwnerUid = owner
        )

    private fun authenticateAs(uid: String?) {
        KharchaNotificationListenerService.liveAuthenticatedUidProvider = { uid }
        TransactionIngestionEngine.liveAuthenticatedUidProvider = { uid }
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = { uid }
    }

    private fun statusBarNotification(): StatusBarNotification {
        val notification = Notification.Builder(context, "test-channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Debit alert")
            .setContentText("INR 150.00 debited from account XX4821 at Example Store")
            .build()
        return StatusBarNotification(
            "com.example.bank",
            "com.example.bank",
            1,
            null,
            1000,
            0,
            notification,
            UserHandle.of(0),
            null,
            1_800_000_000_000L
        )
    }

    private fun transaction(owner: String, id: String) = TransactionEntity(
        id = id,
        type = "EXPENSE",
        amount = 150.0,
        date = "2026-10-06",
        time = "10:00",
        merchant = "Example Store",
        categoryId = "cat-other",
        subcategoryId = "",
        accountId = "",
        paymentMethod = "UPI",
        note = "",
        source = "NOTIFICATION",
        transactionReference = "REF-$id",
        originalReference = "NOTIF-$id",
        last4Digits = "4821",
        userId = owner
    )

    private fun account(owner: String, id: String) = AccountEntity(
        id = id,
        name = "Foreign Bank Account",
        type = "Bank Account",
        last4Digits = "4821",
        userId = owner
    )

    private fun card(owner: String, id: String, accountId: String) = CardEntity(
        id = id,
        accountId = accountId,
        name = "Foreign Card",
        type = "Debit Card",
        last4Digits = "4821",
        userId = owner
    )
}
