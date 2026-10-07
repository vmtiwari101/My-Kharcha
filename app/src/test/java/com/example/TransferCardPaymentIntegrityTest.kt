package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.data.firestore.FirestoreRepository
import com.example.data.repository.AuthenticatedUidSource
import com.example.data.repository.KharchaRepository
import com.example.utils.IngestionStatus
import com.example.utils.TransactionIdentityResolver
import com.example.utils.TransactionIngestionEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class TransferCardPaymentIntegrityTest {
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
        dao.insertAccount(account("bank-a", "user-a", "Bank Account", "1111", 10_000.0))
        dao.insertAccount(account("bank-b", "user-a", "Bank Account", "2222", 7_000.0))
        dao.insertAccount(account("card-account", "user-a", "Credit Card", "3333", outstanding = 1_200.0))
        dao.insertCard(
            CardEntity(
                id = "card-a",
                accountId = "card-account",
                name = "Credit Card",
                type = "Credit Card",
                last4Digits = "3333",
                outstandingAmount = 1_200.0,
                userId = "user-a"
            )
        )
        dao.insertAccount(account("bank-a", "user-b", "Bank Account", "1111", 5_000.0))
        dao.insertAccount(account("foreign-card-account", "user-b", "Credit Card", "9999", outstanding = 900.0))
        dao.insertCard(
            CardEntity(
                id = "foreign-card",
                accountId = "foreign-card-account",
                name = "Foreign Credit Card",
                type = "Credit Card",
                last4Digits = "9999",
                outstandingAmount = 900.0,
                userId = "user-b"
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
    fun transferPairCreatesOnceAndAppliesEachAccountSideOnce() = runBlocking {
        val debit = transfer("transfer-debit", "bank-a", "bank-b", "DEBIT", 2_000.0)
        val credit = transfer("transfer-credit", "bank-b", "bank-a", "CREDIT", 2_000.0)

        assertTrue(dao.saveLinkedTransferPair("user-a", debit, credit))
        dao.insertSplits(listOf(
            split("debit-split", debit.id, 2_000.0),
            split("credit-split", credit.id, 2_000.0)
        ))
        assertEquals(-1L, repository.insertTransaction(debit.copy(amount = 3_000.0)))
        assertEquals(2_000.0, dao.getTransactionByIdSync("user-a", debit.id)?.amount ?: 0.0, 0.0)
        assertEquals(2_000.0, dao.getTransactionByIdSync("user-a", credit.id)?.amount ?: 0.0, 0.0)
        assertTrue(dao.saveLinkedTransferPair("user-a", debit, credit))

        val rows = dao.getAllTransactionsSyncForUser("user-a")
        assertEquals(2, rows.size)
        assertEquals(1, dao.getSplitsForTransactionSync("user-a", debit.id).size)
        assertEquals(1, dao.getSplitsForTransactionSync("user-a", credit.id).size)
        assertEquals(8_000.0, accountBalance("bank-a", rows), 0.0)
        assertEquals(9_000.0, accountBalance("bank-b", rows), 0.0)

        assertTrue(repository.deleteTransaction(debit.id))
        assertTrue(dao.getSplitsForTransactionSync("user-a", debit.id).isEmpty())
        assertTrue(dao.getSplitsForTransactionSync("user-a", credit.id).isEmpty())
        assertEquals(10_000.0, accountBalance("bank-a", dao.getAllTransactionsSyncForUser("user-a")), 0.0)
        assertEquals(7_000.0, accountBalance("bank-b", dao.getAllTransactionsSyncForUser("user-a")), 0.0)
    }

    @Test
    fun editingAndDeletingTransferUpdatesOnlyItsLinkedSide() = runBlocking {
        val debit = transfer("transfer-debit", "bank-a", "bank-b", "DEBIT", 2_000.0)
        val credit = transfer("transfer-credit", "bank-b", "bank-a", "CREDIT", 2_000.0)
        assertTrue(dao.saveLinkedTransferPair("user-a", debit, credit))
        val unrelated = ordinaryExpense("unrelated")
        assertTrue(repository.insertTransaction(unrelated) > 0L)

        val edited = debit.copy(amount = 3_000.0, date = "2026-10-07")
        assertTrue(repository.insertTransaction(edited) > 0L)
        val linkedAfterEdit = dao.getTransactionByIdSync("user-a", credit.id)
        assertEquals(3_000.0, linkedAfterEdit?.amount ?: 0.0, 0.0)
        assertEquals("2026-10-07", linkedAfterEdit?.date)

        assertTrue(repository.deleteTransaction(debit.id))
        assertNull(dao.getTransactionByIdSync("user-a", debit.id))
        assertNull(dao.getTransactionByIdSync("user-a", credit.id))
        assertNotNull(dao.getTransactionByIdSync("user-a", unrelated.id))
    }

    @Test
    fun cardPaymentRetryReducesOutstandingOnceAndLeavesPurchaseAsExpense() = runBlocking {
        val purchase = ordinaryExpense("purchase").copy(
            amount = 900.0,
            accountId = "card-account",
            cardId = "card-a",
            last4Digits = "3333",
            paymentMethod = "Credit Card"
        )
        dao.insertTransaction(purchase)

        val first = cardPayment("payment-first")
        val (saved, firstStatus) = TransactionIngestionEngine.ingestTransaction(
            context,
            first,
            "Rs 500 debited from account ending 1111 for credit card payment to card ending 3333 Ref PAY998877"
        )
        assertNotNull(saved)
        assertTrue(firstStatus == IngestionStatus.IMPORTED || firstStatus == IngestionStatus.NEEDS_REVIEW)
        assertEquals("CARD_PAYMENT", saved?.transactionType)
        assertEquals("INTERNAL_TRANSFER", saved?.type)
        assertFalse(saved?.isExpense ?: true)
        assertTrue(dao.getTransactionByIdSync("user-a", saved!!.id)?.cardPaymentBalanceApplied == true)
        assertEquals(700.0, dao.getAllAccountsSyncForUser("user-a").first { it.id == "card-account" }.outstandingAmount, 0.0)
        assertEquals(700.0, dao.getAllCardsSyncForUser("user-a").single().outstandingAmount, 0.0)

        val retry = first.copy(id = "payment-retry")
        val (_, retryStatus) = TransactionIngestionEngine.ingestTransaction(
            context,
            retry,
            "Rs 500 debited from account ending 1111 for credit card payment to card ending 3333 Ref PAY998877"
        )
        assertEquals(IngestionStatus.DUPLICATE, retryStatus)
        val all = dao.getAllTransactionsSyncForUser("user-a")
        assertEquals(2, all.size)
        assertEquals(9_500.0, accountBalance("bank-a", all), 0.0)
        assertEquals(700.0, dao.getAllAccountsSyncForUser("user-a").first { it.id == "card-account" }.outstandingAmount, 0.0)
        assertTrue(TransactionIdentityResolver.isCreditCardPurchase(purchase, "card-a", "card-account", "3333"))
        assertEquals(
            1,
            all.count { TransactionIdentityResolver.isCreditCardPaymentOrRefund(it, "card-a", "card-account", "3333") }
        )
        assertTrue(repository.deleteTransaction(saved!!.id))
        assertEquals(1, dao.getAllTransactionsSyncForUser("user-a").size)
        assertEquals("EXPENSE", dao.getTransactionByIdSync("user-a", purchase.id)?.type)
        assertEquals(1_200.0, dao.getAllAccountsSyncForUser("user-a").first { it.id == "card-account" }.outstandingAmount, 0.0)
        assertEquals(1_200.0, dao.getAllCardsSyncForUser("user-a").single().outstandingAmount, 0.0)
        assertEquals(10_000.0, accountBalance("bank-a", dao.getAllTransactionsSyncForUser("user-a")), 0.0)
    }

    @Test
    fun statementSnapshotClearsAppliedPaymentMarkerBeforePaymentDeletion() = runBlocking {
        val payment = cardPayment("payment-before-statement").copy(
            type = "INTERNAL_TRANSFER",
            transactionType = "CARD_PAYMENT",
            counterpartyAccountId = "card-account",
            isInternalTransfer = true,
            isExpense = false
        )
        assertTrue(dao.insertTransaction(payment) > 0L)
        assertEquals(700.0, dao.getAllAccountsSyncForUser("user-a").first { it.id == "card-account" }.outstandingAmount, 0.0)
        assertTrue(dao.getTransactionByIdSync("user-a", payment.id)?.cardPaymentBalanceApplied == true)

        val accountSnapshot = dao.getAllAccountsSyncForUser("user-a")
            .first { it.id == "card-account" }
            .copy(outstandingAmount = 450.0)
        val cardSnapshot = dao.getAllCardsSyncForUser("user-a")
            .single()
            .copy(outstandingAmount = 450.0)
        assertTrue(
            dao.applyCardBillSnapshot(
                userId = "user-a",
                account = accountSnapshot,
                card = cardSnapshot,
                includedPaymentIds = listOf(payment.id),
                targetIds = listOf("card-account", "card-a")
            )
        )
        assertFalse(dao.getTransactionByIdSync("user-a", payment.id)?.cardPaymentBalanceApplied ?: true)

        assertTrue(repository.deleteTransaction(payment.id))
        assertEquals(450.0, dao.getAllAccountsSyncForUser("user-a").first { it.id == "card-account" }.outstandingAmount, 0.0)
        assertEquals(450.0, dao.getAllCardsSyncForUser("user-a").single().outstandingAmount, 0.0)
    }

    @Test
    fun cardPaymentCannotTargetAnotherUsersCard() = runBlocking {
        val payment = cardPayment("foreign-card-payment").copy(
            type = "INTERNAL_TRANSFER",
            transactionType = "CARD_PAYMENT",
            counterpartyAccountId = "foreign-card",
            isInternalTransfer = true,
            isExpense = false
        )
        assertEquals(-1L, repository.insertTransaction(payment))
        assertNull(dao.getTransactionByIdSync("user-a", payment.id))
        assertEquals(
            900.0,
            dao.getAllAccountsSyncForUser("user-b").first { it.id == "foreign-card-account" }.outstandingAmount,
            0.0
        )
        assertEquals(
            900.0,
            dao.getAllCardsSyncForUser("user-b").single().outstandingAmount,
            0.0
        )
    }

    @Test
    fun foreignAndLoggedOutTransferMutationsAreRejected() = runBlocking {
        val foreign = transfer("foreign-transfer", "bank-a", "bank-b", "DEBIT", 100.0, "user-b")
        dao.insertTransaction(foreign)

        assertFalse(repository.deleteTransaction(foreign.id))
        assertEquals(-1L, repository.insertTransaction(foreign.copy(amount = 200.0)))
        assertNotNull(dao.getTransactionByIdSync("user-b", foreign.id))

        auth.setUid(null)
        assertFalse(repository.deleteTransaction(foreign.id))
        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(
            context,
            cardPayment("logged-out"),
            "credit card payment"
        )
        assertNull(ingested)
        assertEquals(IngestionStatus.FAILED, status)
        assertNotNull(dao.getTransactionByIdSync("user-b", foreign.id))
    }

    private fun account(
        id: String,
        userId: String,
        type: String,
        last4: String,
        initialBalance: Double = 0.0,
        outstanding: Double = 0.0
    ) = AccountEntity(
        id = id,
        name = id,
        type = type,
        bankName = id,
        last4Digits = last4,
        initialBalance = initialBalance,
        outstandingAmount = outstanding,
        userId = userId
    )

    private fun transfer(
        id: String,
        accountId: String,
        counterpartyId: String,
        direction: String,
        amount: Double,
        userId: String = "user-a"
    ) = TransactionEntity(
        id = id,
        type = "INTERNAL_TRANSFER",
        amount = amount,
        date = "2026-10-06",
        time = "12:00",
        merchant = "Internal Transfer",
        categoryId = "cat-transfer",
        subcategoryId = "",
        accountId = accountId,
        paymentMethod = "Transfer",
        note = "",
        source = "SMS",
        transactionReference = "TRF-IDENTITY-12345",
        originalReference = "SMS-123-bank-$amount-2026-10-06",
        transactionType = "INTERNAL_TRANSFER",
        direction = direction,
        transferGroupId = "group-transfer",
        counterpartyAccountId = counterpartyId,
        isInternalTransfer = true,
        isExpense = false,
        userId = userId
    )

    private fun cardPayment(id: String) = TransactionEntity(
        id = id,
        type = "EXPENSE",
        amount = 500.0,
        date = "2026-10-06",
        time = "12:00",
        merchant = "Credit Card Bill Payment",
        categoryId = "cat-other",
        subcategoryId = "",
        accountId = "bank-a",
        paymentMethod = "Bank Transfer",
        note = "Credit card payment",
        source = "SMS",
        transactionReference = "PAY-998877",
        originalReference = "SMS-321-bank-500-2026-10-06",
        last4Digits = "1111",
        transactionType = null,
        direction = "DEBIT",
        userId = "user-a"
    )

    private fun ordinaryExpense(id: String) = TransactionEntity(
        id = id,
        type = "EXPENSE",
        amount = 100.0,
        date = "2026-10-06",
        time = "09:00",
        merchant = "Purchase",
        categoryId = "cat-food",
        subcategoryId = "",
        accountId = "bank-a",
        paymentMethod = "Card",
        note = "",
        source = "MANUAL",
        transactionReference = "PURCHASE-$id",
        direction = "DEBIT",
        userId = "user-a"
    )

    private fun split(id: String, transactionId: String, amount: Double) = TransactionSplitEntity(
        id = id,
        transactionId = transactionId,
        categoryId = "cat-food",
        subcategoryId = "",
        amount = amount,
        note = "",
        createdAt = "",
        updatedAt = "",
        userId = "user-a"
    )

    private fun accountBalance(accountId: String, transactions: List<TransactionEntity>): Double {
        val account = dao.getAllAccountsSyncForUser("user-a").first { it.id == accountId }
        return com.example.ui.screens.calculateAccountBalance(accountId, transactions, account.initialBalance)
    }

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
