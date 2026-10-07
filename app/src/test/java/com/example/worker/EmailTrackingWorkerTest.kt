package com.example.worker

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.CreditCardBillIngestionEngine
import com.example.utils.EmailParser
import com.example.utils.EmailParserStatus
import com.example.utils.TransactionIngestionEngine
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailTrackingWorkerTest {

    companion object {
        private const val TEST_OWNER_UID = "email-test-owner"
    }

    private lateinit var context: Context
    private lateinit var fakeMessageSource: FakeGmailMessageSource
    private lateinit var previousMessageSource: GmailMessageSource
    private var previousEmailUidProvider: () -> String? = { null }
    private var previousSchedulerUidProvider: () -> String? = { null }
    private var previousTransactionUidProvider: () -> String? = { null }
    private var previousBillUidProvider: () -> String? = { null }

    class FakeGmailMessageSource(
        var tokenResult: Result<String> = Result.success("test_oauth_token"),
        var messagesToReturn: List<GmailMessageData> = emptyList(),
        var pagesToReturn: Map<String?, GmailPageResult>? = null
    ) : GmailMessageSource {
        var authCallCount = 0
        var fetchCallCount = 0

        override suspend fun getAuthorizationToken(context: Context, accountEmail: String): Result<String> {
            authCallCount++
            return tokenResult
        }

        override suspend fun fetchMessagesPage(token: String, query: String, maxResults: Long, pageToken: String?): GmailPageResult {
            fetchCallCount++
            val pages = pagesToReturn
            if (pages != null) {
                return pages[pageToken] ?: GmailPageResult(emptyList(), null)
            }
            return GmailPageResult(messagesToReturn, null)
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        fakeMessageSource = FakeGmailMessageSource()
        previousMessageSource = EmailSyncEngine.messageSource
        EmailSyncEngine.messageSource = fakeMessageSource
        previousEmailUidProvider = EmailSyncEngine.liveAuthenticatedUidProvider
        previousSchedulerUidProvider = EmailTrackingScheduler.liveAuthenticatedUidProvider
        previousTransactionUidProvider = TransactionIngestionEngine.liveAuthenticatedUidProvider
        previousBillUidProvider = CreditCardBillIngestionEngine.liveAuthenticatedUidProvider
        EmailSyncEngine.liveAuthenticatedUidProvider = { TEST_OWNER_UID }
        EmailTrackingScheduler.liveAuthenticatedUidProvider = { TEST_OWNER_UID }
        TransactionIngestionEngine.liveAuthenticatedUidProvider = { TEST_OWNER_UID }
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = { TEST_OWNER_UID }

        val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("email_tracking_enabled", true)
            .putBoolean("gmail_connected", true)
            .putString("gmail_account", "test.user@gmail.com")
            .putString(EmailTrackingScheduler.PREFS_OWNER_UID, TEST_OWNER_UID)
            .putString("last_email_scan", "Never scanned")
            .putLong("last_email_scan_checkpoint_$TEST_OWNER_UID", 0L)
            .commit()

        // Clear DB tables
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        runBlocking {
            dao.getAllTransactionsSync().forEach { dao.deleteTransaction(it.id) }
            dao.getAllAccountsSync().forEach { dao.deleteAccount(it.id) }
            dao.getAllCardsSync().forEach { dao.deleteCard(it.id) }
        }
    }

    @After
    fun tearDown() {
        EmailSyncEngine.messageSource = previousMessageSource
        EmailSyncEngine.liveAuthenticatedUidProvider = previousEmailUidProvider
        EmailTrackingScheduler.liveAuthenticatedUidProvider = previousSchedulerUidProvider
        TransactionIngestionEngine.liveAuthenticatedUidProvider = previousTransactionUidProvider
        CreditCardBillIngestionEngine.liveAuthenticatedUidProvider = previousBillUidProvider
        val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testWorkerDoesNothingWhenEmailTrackingDisabled() = runBlocking {
        val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("email_tracking_enabled", false).commit()

        fakeMessageSource.messagesToReturn = listOf(
            GmailMessageData(
                id = "msg-101",
                subject = "Debit Alert: INR 450 debited at Starbucks",
                snippet = "INR 450.00 debited from your A/c for Starbucks",
                internalDate = System.currentTimeMillis()
            )
        )

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Skipped)
        assertEquals("Email tracking is disabled", (result as SyncResult.Skipped).reason)
        assertEquals(0, fakeMessageSource.authCallCount)
        assertEquals(0, fakeMessageSource.fetchCallCount)

        val dao = AppDatabase.getDatabase(context).kharchaDao()
        assertEquals(0, dao.getAllTransactionsSync().size)
    }

    @Test
    fun testWorkerDoesNothingWhenGmailNotConnected() = runBlocking {
        val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("gmail_connected", false)
            .putString("gmail_account", "Not connected")
            .commit()

        fakeMessageSource.messagesToReturn = listOf(
            GmailMessageData(
                id = "msg-102",
                subject = "Debit Alert: INR 600 debited",
                snippet = "INR 600.00 debited from your A/c",
                internalDate = System.currentTimeMillis()
            )
        )

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Skipped)
        assertEquals("Gmail is not connected", (result as SyncResult.Skipped).reason)
        assertEquals(0, fakeMessageSource.authCallCount)

        val dao = AppDatabase.getDatabase(context).kharchaDao()
        assertEquals(0, dao.getAllTransactionsSync().size)
    }

    @Test
    fun testWorkerProcessesNewGmailTransaction() = runBlocking {
        fakeMessageSource.messagesToReturn = listOf(
            GmailMessageData(
                id = "msg-201",
                subject = "Transaction Alert: INR 1250 spent on Swiggy",
                snippet = "Rs 1250.00 debited from A/c XX1234 on 27-Sep-2026 for Swiggy Ref UPI12345678",
                internalDate = 1700000000000L
            )
        )

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(1, success.imported)
        assertEquals(0, success.duplicates)

        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val txs = dao.getAllTransactionsSync()
        assertEquals(1, txs.size)

        val tx = txs.first()
        assertEquals(1250.0, tx.amount, 0.001)
        assertEquals("EXPENSE", tx.type)
        assertEquals("EMAIL", tx.source)
        assertEquals("EMAIL-msg-201", tx.originalReference)
        assertTrue(tx.merchant.contains("Swiggy", ignoreCase = true))
    }

    @Test
    fun testGmailTransactionGoesThroughTransactionIngestionEngine() = runBlocking {
        fakeMessageSource.messagesToReturn = listOf(
            GmailMessageData(
                id = "msg-202",
                subject = "Salary Credit Notification",
                snippet = "INR 45000.00 credited to your account towards monthly salary payroll Ref SAL98765",
                internalDate = 1700000001000L
            )
        )

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)

        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val txs = dao.getAllTransactionsSync()
        assertEquals(1, txs.size)

        val tx = txs.first()
        assertEquals(45000.0, tx.amount, 0.001)
        assertEquals("INCOME", tx.type)
        assertEquals("cat-salary", tx.categoryId)
        assertNotNull(tx.createdAt)
        assertNotNull(tx.updatedAt)
    }

    @Test
    fun testExistingEmailOriginalReferenceNotImportedAgain() = runBlocking {
        fakeMessageSource.messagesToReturn = listOf(
            GmailMessageData(
                id = "msg-duplicate-check",
                subject = "Debit: Rs 300 paid at Uber",
                snippet = "INR 300.00 debited for Uber ride Ref UBR54321",
                internalDate = 1700000002000L
            )
        )

        // First run imports
        val result1 = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result1 is SyncResult.Success)
        assertEquals(1, (result1 as SyncResult.Success).imported)

        // Second run with same message
        val result2 = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result2 is SyncResult.Success)
        val success2 = result2 as SyncResult.Success
        assertEquals(0, success2.imported)
        assertEquals(1, success2.duplicates)

        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val txs = dao.getAllTransactionsSync()
        assertEquals(1, txs.size)
    }

    @Test
    fun testSameTransactionBySmsAndGmailRemainsOneTransaction() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()

        // 1. Transaction already ingested via SMS with a bank reference ID
        val smsTx = TransactionEntity(
            id = "tx-sms-1",
            type = "EXPENSE",
            amount = 899.0,
            date = "2026-09-27",
            time = "10:30",
            merchant = "Amazon",
            categoryId = "cat-shopping",
            subcategoryId = "sub-s4",
            accountId = "",
            paymentMethod = "UPI",
            note = "SMS: Amazon purchase",
            source = "SMS",
            transactionReference = "UPI5566778899",
            originalReference = "SMS-101",
            last4Digits = "1234",
            createdAt = "2026-09-27T10:30:00Z",
            updatedAt = "2026-09-27T10:30:00Z",
            userId = TEST_OWNER_UID
        )
        dao.insertTransaction(smsTx)
        assertEquals(1, dao.getAllTransactionsSync().size)

        // 2. Email arrives for the exact same transaction with the same reference
        fakeMessageSource.messagesToReturn = listOf(
            GmailMessageData(
                id = "msg-sms-dup",
                subject = "Order Confirmation: Amazon Rs 899",
                snippet = "Paid INR 899.00 to Amazon UPI Ref: UPI5566778899",
                internalDate = System.currentTimeMillis()
            )
        )

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)

        // Cross-source duplicate prevention: should remain only 1 transaction
        val allTxs = dao.getAllTransactionsSync()
        assertEquals(1, allTxs.size)
    }

    @Test
    fun testCreditCardBillEmailUsesCreditCardBillIngestionEngine() = runBlocking {
        fakeMessageSource.messagesToReturn = listOf(
            GmailMessageData(
                id = "msg-cc-bill",
                subject = "Credit Card e-Statement for Card ending 7890",
                snippet = "Total Amount Due: Rs 14,250.00. Payment Due Date: 20-Oct-2026 for credit card ending in 7890.",
                internalDate = System.currentTimeMillis()
            )
        )

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)

        // Credit card bill emails should be handled by bill engine, NOT inserted as daily expense
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val txs = dao.getAllTransactionsSync()
        assertEquals(0, txs.size)
    }

    @Test
    fun testPromotionalEmailIsIgnored() = runBlocking {
        fakeMessageSource.messagesToReturn = listOf(
            GmailMessageData(
                id = "msg-promo",
                subject = "Big Discount Offer! Win 50% Cashback today!",
                snippet = "Unsubscribe from promotional emails. Don't miss this discount offer!",
                internalDate = System.currentTimeMillis()
            )
        )

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(0, success.imported)
        assertEquals(1, success.ignored)

        val dao = AppDatabase.getDatabase(context).kharchaDao()
        assertEquals(0, dao.getAllTransactionsSync().size)
    }

    @Test
    fun testUnknownAccountIdentityDoesNotGetSilentlyAssigned() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()

        // Create a specific user account with last4 = 9999
        val account = AccountEntity(
            id = "acc-real-user",
            name = "User Bank Account",
            type = "Bank Account",
            last4Digits = "9999",
            bankName = "User Bank",
            userId = TEST_OWNER_UID
        )
        dao.insertAccount(account)

        // Transaction email with an unknown/ambiguous account (ending in 1111)
        fakeMessageSource.messagesToReturn = listOf(
            GmailMessageData(
                id = "msg-unknown-acc",
                subject = "Debit Notice: Rs 500 debited",
                snippet = "INR 500.00 debited from A/c ending 1111 at Coffee Shop",
                internalDate = System.currentTimeMillis()
            )
        )

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)

        val txs = dao.getAllTransactionsSync()
        assertEquals(1, txs.size)
        val tx = txs.first()

        // Must NOT silently assign to acc-real-user (which has last4=9999)
        assertEquals("", tx.accountId)
        assertTrue(tx.needsReview)
    }

    @Test
    fun testWorkerRetryDoesNotCreateDuplicates() = runBlocking {
        fakeMessageSource.messagesToReturn = listOf(
            GmailMessageData(
                id = "msg-retry-test",
                subject = "Payment to Grocery Store",
                snippet = "Rs 720.00 debited from A/c XX5678 on 27-Sep-2026 for Grocery Ref TXN998877",
                internalDate = System.currentTimeMillis()
            )
        )

        // Run 1
        val res1 = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(res1 is SyncResult.Success)
        assertEquals(1, (res1 as SyncResult.Success).imported)

        // Run 2 (Simulating worker retry)
        val res2 = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(res2 is SyncResult.Success)
        assertEquals(0, (res2 as SyncResult.Success).imported)
        assertEquals(1, (res2 as SyncResult.Success).duplicates)

        val dao = AppDatabase.getDatabase(context).kharchaDao()
        assertEquals(1, dao.getAllTransactionsSync().size)
    }

    @Test
    fun testOnlyOneUniquePeriodicWorkerIsScheduled() {
        // Schedule multiple times
        EmailTrackingScheduler.schedule(context)
        EmailTrackingScheduler.schedule(context)
        EmailTrackingScheduler.schedule(context)

        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosForUniqueWork(EmailTrackingScheduler.WORK_NAME).get()

        // Enqueued with KEEP semantics: only 1 unique periodic work exists
        assertEquals(1, workInfos.size)
        val state = workInfos[0].state
        assertTrue(state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING)
    }

    @Test
    fun testDisablingEmailTrackingCancelsPeriodicWorker() {
        EmailTrackingScheduler.schedule(context)
        val workManager = WorkManager.getInstance(context)

        var workInfos = workManager.getWorkInfosForUniqueWork(EmailTrackingScheduler.WORK_NAME).get()
        assertEquals(1, workInfos.size)

        // Cancel
        EmailTrackingScheduler.cancel(context)

        workInfos = workManager.getWorkInfosForUniqueWork(EmailTrackingScheduler.WORK_NAME).get()
        assertTrue(workInfos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun testDisconnectingGmailCancelsPeriodicWorker() {
        EmailTrackingScheduler.schedule(context)
        val workManager = WorkManager.getInstance(context)

        val workInfosBefore = workManager.getWorkInfosForUniqueWork(EmailTrackingScheduler.WORK_NAME).get()
        assertEquals(1, workInfosBefore.size)

        // Disconnecting cancels
        EmailTrackingScheduler.cancel(context)

        val workInfosAfter = workManager.getWorkInfosForUniqueWork(EmailTrackingScheduler.WORK_NAME).get()
        assertTrue(workInfosAfter.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun testOtpEmailIsIgnored() = runBlocking {
        fakeMessageSource.messagesToReturn = listOf(
            GmailMessageData(
                id = "msg-otp",
                subject = "Your One Time Password (OTP) for Login",
                snippet = "Your OTP is 482910 for verifying your account. Do not share OTP with anyone.",
                internalDate = System.currentTimeMillis()
            )
        )

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(0, success.imported)
        assertEquals(1, success.ignored)

        val dao = AppDatabase.getDatabase(context).kharchaDao()
        assertEquals(0, dao.getAllTransactionsSync().size)
    }

    @Test
    fun testNetworkFailureResultsInSafeRetry() = runBlocking {
        fakeMessageSource.tokenResult = Result.failure(java.io.IOException("Network unreachable"))

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.NetworkError)

        val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
        val status = prefs.getString("last_email_scan", "")
        assertEquals("Waiting for network", status)

        val worker = androidx.work.testing.TestListenableWorkerBuilder<EmailTrackingWorker>(context)
            .setInputData(androidx.work.workDataOf(EmailTrackingScheduler.INPUT_OWNER_UID to TEST_OWNER_UID))
            .build()
        val workerResult = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.retry(), workerResult)
    }

    @Test
    fun testAuthorizationRequiredStateDoesNotAttemptToLaunchUI() = runBlocking {
        // Mock authorization failure requiring user intervention
        fakeMessageSource.tokenResult = Result.failure(
            UserRecoverableAuthException("User intervention required", Intent())
        )

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.AuthRequired)

        val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
        val status = prefs.getString("last_email_scan", "")
        assertEquals("Authorization required", status)

        // In worker, this maps to Result.success() without crashing or opening an Activity
        val worker = androidx.work.testing.TestListenableWorkerBuilder<EmailTrackingWorker>(context)
            .setInputData(androidx.work.workDataOf(EmailTrackingScheduler.INPUT_OWNER_UID to TEST_OWNER_UID))
            .build()
        val workerResult = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), workerResult)
    }

    @Test
    fun testPaginationProcessesMoreThan25MessagesWithoutSkippingOlderMessages() = runBlocking {
        // Prepare 30 messages split across 2 pages (25 on page 1, 5 on page 2)
        val page1Messages = (1..25).map { idx ->
            GmailMessageData(
                id = "msg-page1-$idx",
                subject = "Debit Alert: INR 10$idx spent",
                snippet = "INR 10$idx.00 debited from A/c XX1234 on 27-Sep-2026 for Vendor$idx Ref TXNP1-$idx",
                internalDate = 1700000000000L + (idx * 1000L)
            )
        }
        val page2Messages = (26..30).map { idx ->
            GmailMessageData(
                id = "msg-page2-$idx",
                subject = "Debit Alert: INR 10$idx spent",
                snippet = "INR 10$idx.00 debited from A/c XX1234 on 27-Sep-2026 for Vendor$idx Ref TXNP2-$idx",
                internalDate = 1700000000000L + (idx * 1000L)
            )
        }

        fakeMessageSource.pagesToReturn = mapOf(
            null to GmailPageResult(page1Messages, "token-page-2"),
            "token-page-2" to GmailPageResult(page2Messages, null)
        )

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(30, success.imported)
        assertEquals(0, success.duplicates)
        assertEquals(2, fakeMessageSource.fetchCallCount)

        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val txs = dao.getAllTransactionsSync()
        assertEquals(30, txs.size)

        // Verify checkpoint advanced to the newest message date (page2-30 timestamp)
        val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
        val checkpoint = prefs.getLong("last_email_scan_checkpoint_$TEST_OWNER_UID", 0L)
        val expectedMaxDate = 1700000000000L + (30 * 1000L)
        assertEquals(expectedMaxDate, checkpoint)
    }

    @Test
    fun testPaginationDoesNotAdvanceCheckpointWhenUnfetchedPagesRemain() = runBlocking {
        // Initial checkpoint
        val initialCheckpoint = 1700000000000L
        val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_email_scan_checkpoint_$TEST_OWNER_UID", initialCheckpoint).commit()

        val page1Messages = (1..25).map { idx ->
            GmailMessageData(
                id = "msg-stage1-$idx",
                subject = "Debit Alert: INR 10$idx spent",
                snippet = "INR 10$idx.00 debited from A/c XX1234 on 27-Sep-2026 for Vendor$idx Ref TXN1000$idx",
                internalDate = initialCheckpoint + (idx * 1000L)
            )
        }
        val page2Messages = (26..30).map { idx ->
            GmailMessageData(
                id = "msg-stage2-$idx",
                subject = "Debit Alert: INR 10$idx spent",
                snippet = "INR 10$idx.00 debited from A/c XX1234 on 27-Sep-2026 for Vendor$idx Ref TXN1000$idx",
                internalDate = initialCheckpoint + (idx * 1000L)
            )
        }

        // Run 1: Page 1 indicates there is a next page ("token-page-2"), but page 2 is not yet fetched in this run
        fakeMessageSource.pagesToReturn = mapOf(
            null to GmailPageResult(page1Messages, "token-page-2"),
            "token-page-2" to GmailPageResult(emptyList(), "token-page-3"),
            "token-page-3" to GmailPageResult(emptyList(), "token-page-4"),
            "token-page-4" to GmailPageResult(emptyList(), "token-page-5")
        )

        val result1 = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result1 is SyncResult.Success)
        val success1 = result1 as SyncResult.Success
        assertEquals(25, success1.imported)

        val dao = AppDatabase.getDatabase(context).kharchaDao()
        assertEquals(25, dao.getAllTransactionsSync().size)

        // Checkpoint must NOT advance because unfetched pages remain
        val checkpointAfterRun1 = prefs.getLong("last_email_scan_checkpoint_$TEST_OWNER_UID", 0L)
        assertEquals("Checkpoint must not advance past unprocessed pages", initialCheckpoint, checkpointAfterRun1)

        // Run 2: All remaining pages are now fully retrieved through the end (nextPageToken = null)
        fakeMessageSource.pagesToReturn = mapOf(
            null to GmailPageResult(page1Messages, "token-page-2"),
            "token-page-2" to GmailPageResult(page2Messages, null)
        )

        val result2 = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result2 is SyncResult.Success)
        val success2 = result2 as SyncResult.Success
        assertEquals(5, success2.imported)
        assertEquals(25, success2.duplicates)

        // All 30 unique transactions are imported without duplication
        assertEquals(30, dao.getAllTransactionsSync().size)

        // Now that all pages were completely processed, checkpoint advances to latest
        val checkpointAfterRun2 = prefs.getLong("last_email_scan_checkpoint_$TEST_OWNER_UID", 0L)
        val expectedFinalDate = initialCheckpoint + (30 * 1000L)
        assertEquals(expectedFinalDate, checkpointAfterRun2)
    }

    @Test
    fun testTestA_YesBankPaymentDueReminderNoTransactionCreated() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val card = CardEntity(
            id = "card-yes-6926",
            accountId = "acc-yes-6926",
            name = "YES BANK Credit Card",
            type = "Credit Card",
            last4Digits = "6926",
            creditLimit = 100000.0,
            outstandingAmount = 0.0,
            dueDate = 0,
            userId = TEST_OWNER_UID
        )
        val account = AccountEntity(
            id = "acc-yes-6926",
            name = "YES BANK Credit Card",
            bankName = "YES Bank",
            type = "Credit Card",
            last4Digits = "6926",
            colour = "#1E293B",
            creditLimit = 100000.0,
            outstandingAmount = 0.0,
            dueDate = 0,
            userId = TEST_OWNER_UID
        )
        dao.insertAccount(account)
        dao.insertCard(card)

        val reminderMsg = GmailMessageData(
            id = "msg-due-yes-1",
            subject = "Payment Due Reminder - YES BANK",
            snippet = "Dear Customer, payment of Rs. 50.00 is due on your YES BANK Credit Card ending 6926. Payment Due Date: 02 Oct 2026.",
            internalDate = System.currentTimeMillis()
        )
        fakeMessageSource.messagesToReturn = listOf(reminderMsg)

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)

        val txs = dao.getAllTransactionsSync()
        assertEquals(0, txs.size)
        assertFalse(txs.any { it.date == "2026-10-02" })

        val updatedCard = dao.getAllCardsSync().find { it.last4Digits == "6926" }
        assertNotNull(updatedCard)
        assertEquals(50.0, updatedCard!!.outstandingAmount, 0.01)
        assertEquals(2, updatedCard.dueDate)
    }

    @Test
    fun testTestB_YesBankActualTransactionPreserved() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val card = CardEntity(id = "card-yes-6926", accountId = "acc-yes-6926", name = "YES BANK Credit Card", type = "Credit Card", last4Digits = "6926", creditLimit = 50000.0, outstandingAmount = 0.0, dueDate = 0, userId = TEST_OWNER_UID)
        val account = AccountEntity(id = "acc-yes-6926", name = "YES BANK Credit Card", bankName = "YES Bank", type = "Credit Card", last4Digits = "6926", colour = "#1E293B", creditLimit = 50000.0, outstandingAmount = 0.0, dueDate = 0, userId = TEST_OWNER_UID)
        dao.insertAccount(account)
        dao.insertCard(card)

        val txMsg = GmailMessageData(
            id = "msg-tx-yes-1",
            subject = "Transaction Alert - YES BANK",
            snippet = "INR 50.00 has been spent on your YES BANK Credit Card ending with 6926 on 27-Sep-26 at Amazon.",
            internalDate = System.currentTimeMillis()
        )
        fakeMessageSource.messagesToReturn = listOf(txMsg)

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)

        val txs = dao.getAllTransactionsSync()
        assertEquals(1, txs.size)
        val tx = txs.first()
        assertEquals(50.0, tx.amount, 0.01)
        assertEquals("EXPENSE", tx.type)
        assertEquals("6926", tx.last4Digits)
        assertEquals("Amazon", tx.merchant)
    }

    @Test
    fun testTestC_SbiActualTransactionPreserved() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val card = CardEntity(id = "card-sbi-2663", accountId = "acc-sbi-2663", name = "SBI Credit Card", type = "Credit Card", last4Digits = "2663", creditLimit = 50000.0, outstandingAmount = 0.0, dueDate = 0, userId = TEST_OWNER_UID)
        val account = AccountEntity(id = "acc-sbi-2663", name = "SBI Credit Card", bankName = "SBI", type = "Credit Card", last4Digits = "2663", colour = "#1E293B", creditLimit = 50000.0, outstandingAmount = 0.0, dueDate = 0, userId = TEST_OWNER_UID)
        dao.insertAccount(account)
        dao.insertCard(card)

        val txMsg = GmailMessageData(
            id = "msg-tx-sbi-1",
            subject = "Transaction Alert - SBI Card",
            snippet = "Rs.150.00 spent on your SBI Credit Card ending with 2663 at BigBasket on 27-Sep-26.",
            internalDate = System.currentTimeMillis()
        )
        fakeMessageSource.messagesToReturn = listOf(txMsg)

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)

        val txs = dao.getAllTransactionsSync()
        assertEquals(1, txs.size)
        val tx = txs.first()
        assertEquals(150.0, tx.amount, 0.01)
        assertEquals("EXPENSE", tx.type)
        assertEquals("2663", tx.last4Digits)
    }

    @Test
    fun testTestD_IciciActualTransactionPreserved() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val card = CardEntity(id = "card-icici-7008", accountId = "acc-icici-7008", name = "ICICI Bank Credit Card", type = "Credit Card", last4Digits = "7008", creditLimit = 50000.0, outstandingAmount = 0.0, dueDate = 0, userId = TEST_OWNER_UID)
        val account = AccountEntity(id = "acc-icici-7008", name = "ICICI Bank Credit Card", bankName = "ICICI Bank", type = "Credit Card", last4Digits = "7008", colour = "#1E293B", creditLimit = 50000.0, outstandingAmount = 0.0, dueDate = 0, userId = TEST_OWNER_UID)
        dao.insertAccount(account)
        dao.insertCard(card)

        val txMsg = GmailMessageData(
            id = "msg-tx-icici-1",
            subject = "Transaction Alert - ICICI Bank",
            snippet = "Your ICICI Bank Credit Card XX7008 has been used for a transaction of INR 55.00 at Swiggy on 27-Sep-26.",
            internalDate = System.currentTimeMillis()
        )
        fakeMessageSource.messagesToReturn = listOf(txMsg)

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)

        val txs = dao.getAllTransactionsSync()
        assertEquals(1, txs.size)
        val tx = txs.first()
        assertEquals(55.0, tx.amount, 0.01)
        assertEquals("EXPENSE", tx.type)
        assertEquals("7008", tx.last4Digits)
    }

    @Test
    fun testTestE_CreditCardPromotionalEmailIgnored() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val promoMsg = GmailMessageData(
            id = "msg-promo-1",
            subject = "Convert purchases to EMI & get cashback - YES BANK",
            snippet = "Special offer! Upgrade your card now and get 5% cashback on all spends.",
            internalDate = System.currentTimeMillis()
        )
        fakeMessageSource.messagesToReturn = listOf(promoMsg)

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)

        val txs = dao.getAllTransactionsSync()
        assertEquals(0, txs.size)
    }

    @Test
    fun testTestF_CreditCardPaymentConfirmationIgnored() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val confirmMsg = GmailMessageData(
            id = "msg-confirm-1",
            subject = "Payment Received - YES BANK Credit Card",
            snippet = "Dear Customer, payment of Rs 500.00 received towards your YES BANK Credit Card ending 6926 on 25-Sep-26. Thank you for your payment.",
            internalDate = System.currentTimeMillis()
        )
        fakeMessageSource.messagesToReturn = listOf(confirmMsg)

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)

        val txs = dao.getAllTransactionsSync()
        assertEquals(0, txs.size)
    }

    @Test
    fun testTestG_DueReminderFutureDateCannotBecomeTransactionDate() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val reminderMsg = GmailMessageData(
            id = "msg-due-future-1",
            subject = "Payment Due Reminder - YES BANK",
            snippet = "Payment Due Reminder: Total Amount Due Rs 50.00. Payment Due Date: 02 Oct 2026 for YES BANK Credit Card XX6926.",
            internalDate = System.currentTimeMillis()
        )
        fakeMessageSource.messagesToReturn = listOf(reminderMsg)

        val result = EmailSyncEngine.syncEmails(context, isBackground = true)
        assertTrue(result is SyncResult.Success)

        val txs = dao.getAllTransactionsSync()
        assertEquals(0, txs.size)
        assertFalse(txs.any { it.date == "2026-10-02" })
    }
}
