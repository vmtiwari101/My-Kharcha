package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.data.firestore.FirestoreRepository
import com.example.data.repository.AuthenticatedUidSource
import com.example.data.repository.KharchaRepository
import com.example.ui.screens.calculateAccountBalance
import com.example.ui.screens.openingBalanceForCurrentBalance
import com.example.utils.IngestionStatus
import com.example.utils.TransactionIdentityResolver
import com.example.utils.TransactionIngestionEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
class BalanceIntegrityTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var dao: com.example.data.dao.KharchaDao
    private lateinit var auth: TestUidSource
    private lateinit var repository: KharchaRepository
    private lateinit var previousUidProvider: () -> String?

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(database)
        dao = database.kharchaDao()
        auth = TestUidSource("user-a")
        repository = KharchaRepository(
            dao,
            FirestoreRepository(firestoreProvider = { null }, authProvider = { null }),
            auth
        )
        previousUidProvider = TransactionIngestionEngine.liveAuthenticatedUidProvider
        TransactionIngestionEngine.liveAuthenticatedUidProvider = { auth.currentUid }
        dao.insertAccount(account("bank-a", "user-a", initialBalance = 1_000.0))
        dao.insertAccount(account("foreign-bank", "user-b", initialBalance = 2_000.0))
        dao.insertAccount(account("card-account", "user-a", type = "Credit Card", outstanding = 1_200.0))
        dao.insertCard(
            CardEntity(
                id = "card-a",
                accountId = "card-account",
                name = "User card",
                type = "Credit Card",
                last4Digits = "3333",
                outstandingAmount = 1_200.0,
                userId = "user-a"
            )
        )
    }

    @After
    fun tearDown() {
        TransactionIngestionEngine.liveAuthenticatedUidProvider = previousUidProvider
        AppDatabase.setTestInstance(null)
        database.close()
    }

    @Test
    fun updatingBalanceChangesOnlySnapshotAndRepeatedUpdateIsIdempotent() = runBlocking {
        val expense = transaction(
            id = "expense",
            userId = "user-a",
            accountId = "bank-a",
            amount = 300.0,
            type = "EXPENSE",
            direction = "DEBIT"
        )
        val income = transaction(
            id = "income",
            userId = "user-a",
            accountId = "bank-a",
            amount = 100.0,
            type = "INCOME",
            direction = "CREDIT"
        )
        dao.insertTransaction(expense)
        dao.insertTransaction(income)
        val historyBefore = dao.getAllTransactionsSyncForUser("user-a")

        val openingBalance = openingBalanceForCurrentBalance(
            "bank-a",
            historyBefore,
            currentBalance = 5_000.0
        )
        repository.insertAccount(
            dao.getAllAccountsSyncForUser("user-a").first { it.id == "bank-a" }
                .copy(initialBalance = openingBalance)
        )
        val sameOpeningBalance = openingBalanceForCurrentBalance(
            "bank-a",
            historyBefore,
            currentBalance = 5_000.0
        )
        repository.insertAccount(
            dao.getAllAccountsSyncForUser("user-a").first { it.id == "bank-a" }
                .copy(initialBalance = sameOpeningBalance)
        )

        assertEquals(5_000.0, calculateAccountBalance("bank-a", historyBefore, sameOpeningBalance), 0.0)
        assertEquals(historyBefore, dao.getAllTransactionsSyncForUser("user-a"))
    }

    @Test
    fun expenseIncomeAndPairedTransferEffectsAreAppliedOnce() {
        val expense = transaction("expense", "user-a", "bank-a", 300.0, "EXPENSE", "DEBIT")
        val income = transaction("income", "user-a", "bank-a", 100.0, "INCOME", "CREDIT")
        val debit = transfer("transfer-out", "bank-a", "bank-b", "DEBIT")
        val credit = transfer("transfer-in", "bank-b", "bank-a", "CREDIT")
        val transactions = listOf(expense, income, debit, credit)

        assertEquals(600.0, calculateAccountBalance("bank-a", transactions, 1_000.0), 0.0)
        assertEquals(700.0, calculateAccountBalance("bank-b", transactions, 500.0), 0.0)
    }

    @Test
    fun cardPurchaseAndPaymentContributeOnceToAnchoredOutstanding() {
        val purchase = transaction(
            id = "purchase",
            userId = "user-a",
            accountId = "card-account",
            amount = 250.0,
            type = "EXPENSE",
            direction = "DEBIT"
        ).copy(cardId = "card-a", paymentMethod = "Credit Card")
        val payment = transaction(
            id = "payment",
            userId = "user-a",
            accountId = "bank-a",
            amount = 100.0,
            type = "INTERNAL_TRANSFER",
            direction = "DEBIT"
        ).copy(
            transactionType = "CARD_PAYMENT",
            counterpartyAccountId = "card-account",
            isInternalTransfer = true
        )
        val transactions = listOf(purchase, payment)

        assertEquals(
            1_350.0,
            TransactionIdentityResolver.creditCardOutstandingFromAnchor(
                1_200.0,
                transactions,
                "card-a",
                "card-account",
                "3333"
            ),
            0.0
        )
        assertEquals(
            1_200.0,
            TransactionIdentityResolver.creditCardAnchorForOutstanding(
                1_350.0,
                transactions,
                "card-a",
                "card-account",
                "3333"
            ) + TransactionIdentityResolver.creditCardTransactionNet(
                transactions,
                "card-a",
                "card-account",
                "3333"
            ),
            0.0
        )
    }

    @Test
    fun sameTransactionAcrossSmsNotificationAndEmailDoesNotReapplyBalance() = runBlocking {
        val base = transaction(
            id = "sms-event",
            userId = "user-a",
            accountId = "bank-a",
            amount = 100.0,
            type = "EXPENSE",
            direction = "DEBIT"
        ).copy(
            transactionReference = "BANK-REF-73190",
            originalReference = "SMS-73190-event",
            last4Digits = "1234",
            updatedAt = "2026-10-06T12:00:00Z"
        )
        dao.insertAccount(
            dao.getAllAccountsSyncForUser("user-a").first { it.id == "bank-a" }
                .copy(last4Digits = "1234")
        )
        val text = "A/c XX1234 debited by Rs 100. Available Balance Rs 900"
        val (first, firstStatus) = TransactionIngestionEngine.ingestTransaction(context, base, text)
        val (notification, notificationStatus) = TransactionIngestionEngine.ingestTransaction(
            context,
            base.copy(
                id = "notification-event",
                source = "NOTIFICATION",
                originalReference = "NOTIF-EVENT-73190",
                updatedAt = "2026-10-06T12:00:00Z"
            ),
            text
        )
        val (email, emailStatus) = TransactionIngestionEngine.ingestTransaction(
            context,
            base.copy(
                id = "email-event",
                source = "EMAIL",
                originalReference = "EMAIL-73190",
                updatedAt = "2026-10-06T12:00:00Z"
            ),
            text
        )

        assertNotNull(first)
        assertTrue(firstStatus == IngestionStatus.IMPORTED || firstStatus == IngestionStatus.NEEDS_REVIEW)
        assertEquals(IngestionStatus.DUPLICATE, notificationStatus)
        assertEquals(IngestionStatus.DUPLICATE, emailStatus)
        assertEquals(first?.id, notification?.id)
        assertEquals(first?.id, email?.id)
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
        val account = dao.getAllAccountsSyncForUser("user-a").first { it.id == "bank-a" }
        assertEquals(
            900.0,
            calculateAccountBalance(
                account.id,
                dao.getAllTransactionsSyncForUser("user-a"),
                account.initialBalance
            ),
            0.0
        )
    }

    @Test
    fun balanceSnapshotsRejectForeignAndLoggedOutAccounts() = runBlocking {
        TransactionIngestionEngine.applyExtractedBalance(
            dao,
            "foreign-bank",
            null,
            TransactionIngestionEngine.ExtractedBalanceInfo(accountBalance = 9_000.0),
            System.currentTimeMillis()
        )
        assertEquals(
            2_000.0,
            dao.getAllAccountsSyncForUser("user-b").first { it.id == "foreign-bank" }.initialBalance,
            0.0
        )

        auth.setUid(null)
        TransactionIngestionEngine.applyExtractedBalance(
            dao,
            "bank-a",
            null,
            TransactionIngestionEngine.ExtractedBalanceInfo(accountBalance = 9_000.0),
            System.currentTimeMillis()
        )
        assertEquals(
            1_000.0,
            dao.getAllAccountsSyncForUser("user-a").first { it.id == "bank-a" }.initialBalance,
            0.0
        )
        assertNull(
            TransactionIngestionEngine.ingestTransaction(
                context,
                transaction("logged-out", "user-a", "bank-a", 50.0, "EXPENSE", "DEBIT"),
                "debit Rs 50"
            ).first
        )
    }

    @Test
    fun olderSameDaySnapshotCannotReplaceNewerBalance() = runBlocking {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val newerTime = formatter.parse("2026-10-06T12:00:00Z")!!.time
        val olderTime = formatter.parse("2026-10-06T11:00:00Z")!!.time
        TransactionIngestionEngine.applyExtractedBalance(
            dao,
            "bank-a",
            null,
            TransactionIngestionEngine.ExtractedBalanceInfo(accountBalance = 5_000.0),
            newerTime
        )
        TransactionIngestionEngine.applyExtractedBalance(
            dao,
            "bank-a",
            null,
            TransactionIngestionEngine.ExtractedBalanceInfo(accountBalance = 4_000.0),
            olderTime
        )

        val account = dao.getAllAccountsSyncForUser("user-a").first { it.id == "bank-a" }
        assertEquals(5_000.0, calculateAccountBalance("bank-a", emptyList(), account.initialBalance), 0.0)
    }

    @Test
    fun manualCreditCardSnapshotSyncsMappedCardAndPreventsPaymentReversal() = runBlocking {
        val payment = transaction(
            id = "applied-payment",
            userId = "user-a",
            accountId = "bank-a",
            amount = 500.0,
            type = "INTERNAL_TRANSFER",
            direction = "DEBIT"
        ).copy(
            transactionType = "CARD_PAYMENT",
            counterpartyAccountId = "card-account",
            isInternalTransfer = true,
            cardPaymentBalanceApplied = true
        )
        dao.insertTransaction(payment)
        val account = dao.getAllAccountsSyncForUser("user-a").first { it.id == "card-account" }
        repository.insertAccount(account.copy(outstandingAmount = 700.0, initialBalance = 1_200.0))

        assertEquals(
            700.0,
            dao.getAllCardsSyncForUser("user-a").single().outstandingAmount,
            0.0
        )
        assertFalse(
            dao.getTransactionByIdSync("user-a", payment.id)?.cardPaymentBalanceApplied ?: true
        )
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
    }

    private fun account(
        id: String,
        userId: String,
        type: String = "Bank Account",
        initialBalance: Double = 0.0,
        outstanding: Double = 0.0
    ) = AccountEntity(
        id = id,
        name = id,
        type = type,
        bankName = id,
        last4Digits = "1234",
        initialBalance = initialBalance,
        outstandingAmount = outstanding,
        userId = userId
    )

    private fun transaction(
        id: String,
        userId: String,
        accountId: String,
        amount: Double,
        type: String,
        direction: String
    ) = TransactionEntity(
        id = id,
        type = type,
        amount = amount,
        date = "2026-10-06",
        time = "12:00",
        merchant = id,
        categoryId = "cat-other",
        subcategoryId = "",
        accountId = accountId,
        paymentMethod = "Bank Account",
        note = "",
        source = "MANUAL",
        transactionReference = id,
        direction = direction,
        userId = userId
    )

    private fun transfer(id: String, accountId: String, counterparty: String, direction: String) =
        transaction(id, "user-a", accountId, 200.0, "INTERNAL_TRANSFER", direction).copy(
            isInternalTransfer = true,
            transactionType = "INTERNAL_TRANSFER",
            transferGroupId = "transfer-group",
            counterpartyAccountId = counterparty
        )

    private class TestUidSource(initialUid: String?) : AuthenticatedUidSource {
        private val state = MutableStateFlow(initialUid)
        override val currentUid: String?
            get() = state.value
        override val uidChanges: Flow<String?> = state

        fun setUid(uid: String?) {
            state.value = uid
        }
    }
}
