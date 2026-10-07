package com.example.utils

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.dao.KharchaDao
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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
class TransactionIngestionOwnershipTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var dao: KharchaDao
    private lateinit var previousUidProvider: () -> String?

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(database)
        dao = database.kharchaDao()
        previousUidProvider = TransactionIngestionEngine.liveAuthenticatedUidProvider
    }

    @After
    fun tearDown() {
        TransactionIngestionEngine.liveAuthenticatedUidProvider = previousUidProvider
        AppDatabase.setTestInstance(null)
        database.close()
    }

    @Test
    fun ingestionDetectsDuplicatesOnlyWithinAuthenticatedOwner() = runBlocking {
        authenticateAs("user-a")
        dao.insertTransaction(transaction("user-b", "b-duplicate", reference = "REF-SHARED"))

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(
            context,
            transaction("", "a-new", reference = "REF-SHARED"),
            "Purchase at Example Store, reference REF-SHARED"
        )

        assertNotNull(ingested)
        assertEquals("user-a", ingested?.userId)
        assertEquals(IngestionStatus.IMPORTED, status)
        assertEquals(2, dao.getAllTransactionsSyncForUser("user-b").size)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
    }

    @Test
    fun manualIdentityCannotResolveForeignAccountOrCard() = runBlocking {
        authenticateAs("user-a")
        dao.insertAccount(account("user-b", "shared-account", last4 = "4821"))
        dao.insertCard(card("user-b", "shared-card", "shared-account", last4 = "4821"))

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(
            context,
            transaction("", "manual-input", source = "MANUAL").copy(
                accountId = "shared-account",
                cardId = "shared-card",
                last4Digits = "4821"
            ),
            ""
        )

        assertNotNull(ingested)
        assertEquals("user-a", ingested?.userId)
        assertEquals("", ingested?.accountId)
        assertNull(ingested?.cardId)
        assertTrue(ingested?.needsReview == true)
        assertTrue(status == IngestionStatus.NEEDS_REVIEW || status == IngestionStatus.IMPORTED)
        assertTrue(dao.getAllAccountsSyncForUser("user-a").isEmpty())
        assertEquals(1, dao.getAllAccountsSyncForUser("user-b").size)
    }

    @Test
    fun foreignOwnedInputTransactionIsRejectedWithoutMutation() = runBlocking {
        authenticateAs("user-a")
        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(
            context,
            transaction("user-b", "foreign-input"),
            "Purchase at Example Store"
        )

        assertNull(ingested)
        assertEquals(IngestionStatus.FAILED, status)
        assertTrue(dao.getAllTransactionsSyncForUser("user-a").isEmpty())
        assertTrue(dao.getAllTransactionsSyncForUser("user-b").isEmpty())
    }

    @Test
    fun balanceUpdatesOnlyAuthenticatedUsersAccountAndCard() = runBlocking {
        authenticateAs("user-a")
        dao.insertAccount(account("user-a", "shared-bank-account", initialBalance = 10.0))
        dao.insertAccount(account("user-b", "shared-bank-account", initialBalance = 20.0))
        dao.insertAccount(account("user-a", "shared-card-account", type = "Credit Card"))
        dao.insertAccount(account("user-b", "shared-card-account", type = "Credit Card"))
        dao.insertCard(card("user-a", "shared-card", "shared-card-account", outstanding = 5.0))
        dao.insertCard(card("user-b", "shared-card", "shared-card-account", outstanding = 15.0))

        TransactionIngestionEngine.applyExtractedBalance(
            dao,
            accountId = "shared-bank-account",
            cardId = null,
            balanceInfo = TransactionIngestionEngine.ExtractedBalanceInfo(accountBalance = 100.0),
            messageTimestamp = 1_800_000_000_000L
        )
        TransactionIngestionEngine.applyExtractedBalance(
            dao,
            accountId = "shared-card-account",
            cardId = "shared-card",
            balanceInfo = TransactionIngestionEngine.ExtractedBalanceInfo(
                outstandingAmount = 40.0
            ),
            messageTimestamp = 1_800_000_000_000L
        )

        assertEquals(100.0, dao.getAllAccountsSyncForUser("user-a").first { it.id == "shared-bank-account" }.initialBalance, 0.0)
        assertEquals(20.0, dao.getAllAccountsSyncForUser("user-b").first { it.id == "shared-bank-account" }.initialBalance, 0.0)
        assertEquals(40.0, dao.getAllCardsSyncForUser("user-a").single().outstandingAmount, 0.0)
        assertEquals(15.0, dao.getAllCardsSyncForUser("user-b").single().outstandingAmount, 0.0)
    }

    @Test
    fun duplicateCleanupKeepsOtherUsersTransactionsAndSplits() = runBlocking {
        authenticateAs("user-a")
        dao.insertTransaction(transaction("user-a", "a-primary", reference = "REF-DUP"))
        dao.insertTransaction(transaction("user-a", "a-duplicate", reference = "REF-DUP"))
        dao.insertTransaction(transaction("user-b", "b-primary", reference = "REF-DUP"))
        dao.insertTransaction(transaction("user-b", "b-duplicate", reference = "REF-DUP"))
        dao.insertSplits(listOf(
            split("user-a", "a-duplicate-split", "a-duplicate"),
            split("user-b", "b-primary-split", "b-primary"),
            split("user-b", "b-duplicate-split", "b-duplicate")
        ))

        TransactionIngestionEngine.cleanupDuplicateTransactions(dao)

        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
        assertEquals(2, dao.getAllTransactionsSyncForUser("user-b").size)
        assertEquals(2, dao.getAllSplitsSyncForUser("user-b").size)
        assertTrue(dao.getAllSplitsSyncForUser("user-a").all { it.userId == "user-a" })
    }

    @Test
    fun transferLinkingDoesNotModifyOtherUsersTransactions() = runBlocking {
        authenticateAs("user-a")
        dao.insertAccount(account("user-b", "b-debit"))
        dao.insertAccount(account("user-b", "b-credit"))
        dao.insertTransaction(transaction("user-b", "b-debit-tx", accountId = "b-debit", type = "EXPENSE"))
        dao.insertTransaction(transaction("user-b", "b-credit-tx", accountId = "b-credit", type = "INCOME"))

        TransactionIngestionEngine.linkExistingInternalTransfers(dao)

        val foreignTransactions = dao.getAllTransactionsSyncForUser("user-b")
        assertEquals(2, foreignTransactions.size)
        assertTrue(foreignTransactions.none { it.isInternalTransfer })
        assertTrue(dao.getAllCategoriesSyncForUser("user-b").isEmpty())
    }

    @Test
    fun normalizationOnlyUpdatesAuthenticatedUsersTransactions() = runBlocking {
        authenticateAs("user-a")
        dao.insertTransaction(transaction("user-a", "a-normalize").copy(merchant = "https://invalid.example/path"))
        dao.insertTransaction(transaction("user-b", "b-normalize").copy(merchant = "https://invalid.example/path"))

        TransactionIngestionEngine.normalizeExistingTransactions(dao)

        val aTransaction = dao.getTransactionByIdSync("user-a", "a-normalize")
        val bTransaction = dao.getTransactionByIdSync("user-b", "b-normalize")
        assertNotNull(aTransaction)
        assertNotNull(bTransaction)
        assertEquals("https://invalid.example/path", bTransaction?.merchant)
        assertTrue(aTransaction?.merchant != "https://invalid.example/path")
    }

    @Test
    fun historicalCleanupDeletesOnlyAuthenticatedUsersRows() = runBlocking {
        authenticateAs("user-a")
        val incorrectA = transaction("user-a", "a-incorrect").copy(
            amount = 0.0,
            merchant = "Available balance notification"
        )
        val incorrectB = transaction("user-b", "b-incorrect").copy(
            amount = 0.0,
            merchant = "Available balance notification"
        )
        dao.insertTransaction(incorrectA)
        dao.insertTransaction(incorrectB)

        assertEquals(1, TransactionIngestionEngine.cleanupHistoricalIncorrectSmsTransactions(dao))

        assertNull(dao.getTransactionByIdSync("user-a", "a-incorrect"))
        assertNotNull(dao.getTransactionByIdSync("user-b", "b-incorrect"))
    }

    @Test
    fun loggedOutCleanupBalanceAndIngestionPerformNoMutation() = runBlocking {
        TransactionIngestionEngine.liveAuthenticatedUidProvider = { null }
        dao.insertAccount(account("user-a", "account-a"))
        dao.insertTransaction(transaction("user-a", "tx-a"))

        val beforeTransactions = dao.getAllTransactionsSyncForUser("user-a")
        val beforeAccounts = dao.getAllAccountsSyncForUser("user-a")
        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(
            context,
            transaction("", "logged-out"),
            "Purchase at Example Store"
        )
        TransactionIngestionEngine.applyExtractedBalance(
            dao,
            "account-a",
            null,
            TransactionIngestionEngine.ExtractedBalanceInfo(accountBalance = 500.0),
            1_800_000_000_000L
        )
        assertEquals(0, TransactionIngestionEngine.cleanupDuplicateTransactions(dao))
        assertEquals(0, TransactionIngestionEngine.cleanupHistoricalIncorrectSmsTransactions(dao))
        assertEquals(0, TransactionIngestionEngine.normalizeExistingTransactions(dao))
        assertEquals(0, TransactionIngestionEngine.linkExistingInternalTransfers(dao))
        assertEquals(0, TransactionIngestionEngine.cleanupOrphanCreditCards(dao))

        assertNull(ingested)
        assertEquals(IngestionStatus.FAILED, status)
        assertEquals(beforeTransactions, dao.getAllTransactionsSyncForUser("user-a"))
        assertEquals(beforeAccounts, dao.getAllAccountsSyncForUser("user-a"))
        assertTrue(dao.getAllCategoriesSyncForUser("user-a").isEmpty())
    }

    @Test
    fun legacyRowsAreNotAdoptedAndValidIngestionIsImmediate() = runBlocking {
        authenticateAs("user-a")
        dao.insertAccount(account("legacy:unassigned", "legacy-account"))
        dao.insertTransaction(transaction("legacy:unassigned", "legacy-tx"))

        val (legacyInput, legacyStatus) = TransactionIngestionEngine.ingestTransaction(
            context,
            transaction("legacy:unassigned", "legacy-input"),
            "Purchase at Example Store"
        )
        assertNull(legacyInput)
        assertEquals(IngestionStatus.FAILED, legacyStatus)
        assertEquals(1, dao.getAllTransactionsSyncForUser("legacy:unassigned").size)
        assertTrue(dao.getAllTransactionsSyncForUser("user-a").isEmpty())

        val (ingested, status) = withTimeout(5_000L) {
            TransactionIngestionEngine.ingestTransaction(
                context,
                transaction("", "immediate-tx"),
                "Purchase at Example Store"
            )
        }
        assertNotNull(ingested)
        assertEquals("user-a", ingested?.userId)
        assertTrue(status == IngestionStatus.IMPORTED || status == IngestionStatus.NEEDS_REVIEW)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
    }

    private fun authenticateAs(uid: String) {
        TransactionIngestionEngine.liveAuthenticatedUidProvider = { uid }
    }

    private fun transaction(
        userId: String,
        id: String,
        reference: String = "",
        accountId: String = "",
        source: String = "SMS",
        type: String = "EXPENSE"
    ) = TransactionEntity(
        id = id,
        type = type,
        amount = 25.0,
        date = "2026-10-06",
        time = "09:00",
        merchant = "Example Store",
        categoryId = "cat-other",
        subcategoryId = "",
        accountId = accountId,
        paymentMethod = "UPI",
        note = "",
        source = source,
        transactionReference = reference,
        originalReference = reference,
        createdAt = "2026-10-06T09:00:00Z",
        updatedAt = "2026-10-06T09:00:00Z",
        userId = userId
    )

    private fun account(
        userId: String,
        id: String,
        last4: String = "",
        initialBalance: Double = 0.0,
        type: String = "Bank Account"
    ) = AccountEntity(
        id = id,
        name = "Example Account",
        type = type,
        bankName = "Example Bank",
        last4Digits = last4,
        createdAt = "2026-10-06T09:00:00Z",
        updatedAt = "2026-10-06T09:00:00Z",
        initialBalance = initialBalance,
        userId = userId
    )

    private fun card(
        userId: String,
        id: String,
        accountId: String,
        last4: String = "",
        outstanding: Double = 0.0
    ) = CardEntity(
        id = id,
        accountId = accountId,
        name = "Example Card",
        type = "Credit Card",
        last4Digits = last4,
        outstandingAmount = outstanding,
        createdAt = "2026-10-06T09:00:00Z",
        updatedAt = "2026-10-06T09:00:00Z",
        userId = userId
    )

    private fun split(userId: String, id: String, transactionId: String) =
        TransactionSplitEntity(
            id = id,
            transactionId = transactionId,
            categoryId = "cat-other",
            amount = 25.0,
            createdAt = "2026-10-06T09:00:00Z",
            updatedAt = "2026-10-06T09:00:00Z",
            userId = userId
        )
}
