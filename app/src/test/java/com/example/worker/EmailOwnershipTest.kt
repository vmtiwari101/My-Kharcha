package com.example.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.database.AppDatabase
import com.example.data.entity.TransactionEntity
import com.example.utils.CreditCardBillIngestionEngine
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
class EmailOwnershipTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fakeSource: FakeSource
    private lateinit var previousMessageSource: GmailMessageSource
    private var previousEmailUidProvider: () -> String? = { null }
    private var previousSchedulerUidProvider: () -> String? = { null }
    private var previousTransactionUidProvider: () -> String? = { null }
    private var previousBillUidProvider: () -> String? = { null }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(database)

        previousEmailUidProvider = EmailSyncEngine.liveAuthenticatedUidProvider
        previousSchedulerUidProvider = EmailTrackingScheduler.liveAuthenticatedUidProvider
        previousTransactionUidProvider = TransactionIngestionEngine.liveAuthenticatedUidProvider
        previousBillUidProvider = CreditCardBillIngestionEngine.liveAuthenticatedUidProvider
        previousMessageSource = EmailSyncEngine.messageSource
        authenticateAs("test-owner")

        fakeSource = FakeSource()
        EmailSyncEngine.messageSource = fakeSource
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("email_tracking_enabled", true)
            .putBoolean("gmail_connected", true)
            .putString("gmail_account", "test@gmail.com")
            .putString(EmailTrackingScheduler.PREFS_OWNER_UID, "test-owner")
            .putString("last_email_scan", "")
            .commit()
    }

    @After
    fun tearDown() {
        EmailSyncEngine.liveAuthenticatedUidProvider = previousEmailUidProvider
        EmailTrackingScheduler.liveAuthenticatedUidProvider = previousSchedulerUidProvider
        TransactionIngestionEngine.liveAuthenticatedUidProvider = previousTransactionUidProvider
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = previousBillUidProvider
        EmailSyncEngine.messageSource = previousMessageSource
        AppDatabase.setTestInstance(null)
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        database.close()
    }

    @Test
    fun syncReadsOnlyCurrentOwnersExistingTransactionsAndCreatesOwnedTransactions() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("user-a", "a-existing", "EMAIL-shared"))
        dao.insertTransaction(transaction("user-b", "b-existing", "OTHER-REF"))
        authenticateAs("user-b")
        fakeSource.messages = listOf(emailMessage("shared"))

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)

        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).imported)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
        assertEquals(2, dao.getAllTransactionsSyncForUser("user-b").size)
        assertTrue(dao.getAllTransactionsSyncForUser("user-b").all { it.userId == "user-b" })
        assertTrue(dao.getAllTransactionsSyncForUser("user-a").any { it.userId == "user-a" })
    }

    @Test
    fun existingUserATransactionDoesNotSuppressUserBEmailImport() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("user-a", "a-message", "EMAIL-shared"))
        authenticateAs("user-b")
        fakeSource.messages = listOf(emailMessage("shared"))

        val result = EmailSyncEngine.syncEmails(context)

        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).imported)
        assertTrue(dao.getAllTransactionsSyncForUser("user-b").all { it.userId == "user-b" })
    }

    @Test
    fun loggedOutSyncStopsBeforeAuthorizationOrDatabaseMutation() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("user-a", "a-existing", "REF-A"))
        EmailSyncEngine.liveAuthenticatedUidProvider = { null }
        fakeSource.messages = listOf(emailMessage("new"))

        val result = EmailSyncEngine.syncEmails(context)

        assertTrue(result is SyncResult.Skipped)
        assertEquals(0, fakeSource.authorizationCalls)
        assertEquals(0, fakeSource.fetchCalls)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
        assertTrue(dao.getAllTransactionsSyncForUser("user-b").isEmpty())
    }

    @Test
    fun staleUserAWorkerCannotProcessMessagesAsUserB() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("user-a", "a-existing", "REF-A"))
        dao.insertTransaction(transaction("user-b", "b-existing", "REF-B"))
        authenticateAs("user-b")
        fakeSource.messages = listOf(emailMessage("stale"))

        val worker = TestListenableWorkerBuilder<EmailTrackingWorker>(context)
            .setInputData(
                androidx.work.workDataOf(
                    EmailTrackingScheduler.INPUT_OWNER_UID to "user-a"
                )
            )
            .build()
        val result = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals(0, fakeSource.authorizationCalls)
        assertEquals(0, fakeSource.fetchCalls)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-b").size)
    }

    @Test
    fun gmailSettingsBoundToUserACannotBeSyncedUnderUserB() = runBlocking {
        val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
        authenticateAs("user-b")
        prefs.edit().putString(EmailTrackingScheduler.PREFS_OWNER_UID, "user-a").commit()
        fakeSource.messages = listOf(emailMessage("wrong-mailbox"))

        val result = EmailSyncEngine.syncEmails(context)

        assertTrue(result is SyncResult.Skipped)
        assertEquals(0, fakeSource.authorizationCalls)
        assertEquals(0, fakeSource.fetchCalls)
        assertTrue(database.kharchaDao().getAllTransactionsSyncForUser("user-b").isEmpty())
    }

    @Test
    fun workerWithoutOwnerAndLoggedOutWorkerFailClosed() = runBlocking {
        fakeSource.messages = listOf(emailMessage("no-owner"))
        authenticateAs("user-a")
        val missingOwnerWorker = TestListenableWorkerBuilder<EmailTrackingWorker>(context).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), missingOwnerWorker.doWork())
        assertEquals(0, fakeSource.fetchCalls)

        EmailSyncEngine.liveAuthenticatedUidProvider = { null }
        val loggedOutWorker = TestListenableWorkerBuilder<EmailTrackingWorker>(context)
            .setInputData(
                androidx.work.workDataOf(
                    EmailTrackingScheduler.INPUT_OWNER_UID to "user-a"
                )
            )
            .build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), loggedOutWorker.doWork())
        assertEquals(0, fakeSource.fetchCalls)
        assertTrue(database.kharchaDao().getAllTransactionsSyncForUser("user-a").isEmpty())
    }

    @Test
    fun schedulerBindsPeriodicWorkToAuthenticatedUid() {
        authenticateAs("user-a")

        EmailTrackingScheduler.schedule(context)

        val workInfo = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(EmailTrackingScheduler.WORK_NAME)
            .get()
            .single()
        assertTrue(workInfo.state == WorkInfo.State.ENQUEUED || workInfo.state == WorkInfo.State.RUNNING)
        assertEquals(
            "user-a",
            WorkManager.getInstance(context).getWorkInfoById(workInfo.id).get()
                ?.inputData?.getString(EmailTrackingScheduler.INPUT_OWNER_UID)
        )
    }

    @Test
    fun schedulerRejectsGmailSettingsBoundToAnotherUser() {
        authenticateAs("user-a")
        EmailTrackingScheduler.cancel(context)
        authenticateAs("user-b")
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit()
            .putString(EmailTrackingScheduler.PREFS_OWNER_UID, "user-a")
            .commit()

        EmailTrackingScheduler.schedule(context)

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(EmailTrackingScheduler.WORK_NAME)
            .get()
        assertTrue(workInfos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun legacyRowsAreNotUsedAsEmailOwners() = runBlocking {
        val dao = database.kharchaDao()
        dao.insertTransaction(transaction("legacy:unassigned", "legacy-email", "EMAIL-legacy"))
        authenticateAs("user-b")
        fakeSource.messages = listOf(emailMessage("legacy"))

        val result = EmailSyncEngine.syncEmails(context)

        assertTrue(result is SyncResult.Success)
        assertFalse(dao.getAllTransactionsSyncForUser("user-b").any { it.userId == "legacy:unassigned" })
        assertEquals(1, dao.getAllTransactionsSyncForUser("legacy:unassigned").size)
        assertTrue(dao.getAllTransactionsSyncForUser("user-b").all { it.userId == "user-b" })
    }

    private fun authenticateAs(uid: String) {
        EmailSyncEngine.liveAuthenticatedUidProvider = { uid }
        EmailTrackingScheduler.liveAuthenticatedUidProvider = { uid }
        TransactionIngestionEngine.liveAuthenticatedUidProvider = { uid }
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = { uid }
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit()
            .putString(EmailTrackingScheduler.PREFS_OWNER_UID, uid)
            .commit()
    }

    private fun emailMessage(id: String) = GmailMessageData(
        id = id,
        subject = "Transaction Alert: INR 1250 spent on Example Store",
        snippet = "Rs 1250.00 debited from A/c XX1234 on 06-Oct-2026 for Example Store Ref TXN$id",
        internalDate = 1_800_000_000_000L
    )

    private fun transaction(owner: String, id: String, originalReference: String) =
        TransactionEntity(
            id = id,
            type = "EXPENSE",
            amount = 1250.0,
            date = "2026-10-06",
            time = "10:00",
            merchant = "Example Store",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "",
            paymentMethod = "UPI",
            note = "",
            source = "EMAIL",
            transactionReference = originalReference,
            originalReference = originalReference,
            createdAt = "2026-10-06T10:00:00Z",
            updatedAt = "2026-10-06T10:00:00Z",
            userId = owner
        )

    private class FakeSource : GmailMessageSource {
        var messages: List<GmailMessageData> = emptyList()
        var authorizationCalls = 0
        var fetchCalls = 0

        override suspend fun getAuthorizationToken(
            context: Context,
            accountEmail: String
        ): Result<String> {
            authorizationCalls++
            return Result.success("token")
        }

        override suspend fun fetchMessagesPage(
            token: String,
            query: String,
            maxResults: Long,
            pageToken: String?
        ): GmailPageResult {
            fetchCalls++
            return GmailPageResult(messages)
        }
    }
}
