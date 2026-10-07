package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.dao.SafeDeleteResult
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.data.firestore.FirestoreRepository
import com.example.data.repository.AuthenticatedUidSource
import com.example.data.repository.KharchaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KharchaRepositoryOwnershipTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: com.example.data.dao.KharchaDao
    private lateinit var authSource: TestAuthenticatedUidSource
    private lateinit var repository: KharchaRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.kharchaDao()
        authSource = TestAuthenticatedUidSource("user-a")
        repository = KharchaRepository(
            dao,
            FirestoreRepository(firestoreProvider = { null }, authProvider = { null }),
            authSource
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun readsOnlyAuthenticatedUsersRowsAndQuarantinesLegacyRows() = runBlocking {
        dao.insertTransaction(transaction("a-row", "user-a"))
        dao.insertTransaction(transaction("b-row", "user-b"))
        dao.insertTransaction(transaction("legacy-row", "legacy:unassigned"))
        dao.insertSplits(listOf(
            split("a-split", "a-row", "user-a"),
            split("b-split", "b-row", "user-b"),
            split("legacy-split", "legacy-row", "legacy:unassigned")
        ))
        dao.insertAccount(AccountEntity(id = "a-account", name = "A", type = "Bank", userId = "user-a"))
        dao.insertAccount(AccountEntity(id = "b-account", name = "B", type = "Bank", userId = "user-b"))
        dao.insertAccount(AccountEntity(id = "legacy-account", name = "Legacy", type = "Bank", userId = "legacy:unassigned"))
        dao.insertCard(card("a-card", "user-a"))
        dao.insertCard(card("b-card", "user-b"))
        dao.insertCard(card("legacy-card", "legacy:unassigned"))
        dao.insertCategory(category("a-category", "user-a"))
        dao.insertCategory(category("b-category", "user-b"))
        dao.insertCategory(category("legacy-category", "legacy:unassigned"))
        dao.insertSubcategory(subcategory("a-subcategory", "a-category", "user-a"))
        dao.insertSubcategory(subcategory("b-subcategory", "b-category", "user-b"))
        dao.insertSubcategory(subcategory("legacy-subcategory", "legacy-category", "legacy:unassigned"))

        assertEquals(listOf("a-row"), repository.allTransactions.first().map { it.id })
        assertEquals(listOf("a-split"), repository.allSplits.first().map { it.id })
        assertEquals(listOf("a-account"), repository.allAccounts.first().map { it.id })
        assertEquals(listOf("a-card"), repository.allCards.first().map { it.id })
        assertEquals(listOf("a-category"), repository.allCategories.first().map { it.id })
        assertEquals(listOf("a-subcategory"), repository.allSubcategories.first().map { it.id })

        authSource.setUid(null)
        assertTrue(repository.allTransactions.first().isEmpty())
    }

    @Test
    fun rejectsForeignOwnedWritesAndForeignUpdates() = runBlocking {
        dao.insertTransaction(transaction("b-row", "user-b", amount = 20.0))

        assertEquals(-1L, repository.insertTransaction(transaction("b-row", "user-b", amount = 99.0)))
        repository.insertAccount(AccountEntity(id = "b-account", name = "Account", type = "Bank", userId = "user-b"))
        repository.insertCard(
            CardEntity(
                id = "b-card",
                accountId = "b-account",
                name = "Card",
                type = "Debit Card",
                last4Digits = "",
                userId = "user-b"
            )
        )
        repository.insertCategory(
            CategoryEntity(
                id = "b-category",
                name = "Category",
                icon = "",
                colour = "",
                createdAt = "",
                updatedAt = "",
                userId = "user-b"
            )
        )
        repository.insertSubcategory(
            SubcategoryEntity(
                id = "b-subcategory",
                categoryId = "b-category",
                name = "Subcategory",
                icon = "",
                colour = "",
                createdAt = "",
                updatedAt = "",
                userId = "user-b"
            )
        )

        assertEquals(20.0, dao.getTransactionByIdSync("user-b", "b-row")?.amount ?: 0.0, 0.0)
        assertTrue(dao.getAllAccountsSyncForUser("user-b").isEmpty())
        assertTrue(dao.getAllCardsSyncForUser("user-b").isEmpty())
        assertTrue(dao.getAllCategoriesSyncForUser("user-b").isEmpty())
        assertTrue(dao.getAllSubcategoriesSyncForUser("user-b").isEmpty())
    }

    @Test
    fun rejectsForeignOwnerDeleteArguments() = runBlocking {
        dao.insertTransaction(transaction("b-row", "user-b"))
        dao.insertAccount(AccountEntity(id = "b-account", name = "Account", type = "Bank", userId = "user-b"))
        dao.insertCard(
            CardEntity(
                id = "b-card",
                accountId = "b-account",
                name = "Card",
                type = "Debit Card",
                last4Digits = "",
                userId = "user-b"
            )
        )
        dao.insertSplits(listOf(split("b-split", "b-row", "user-b")))

        repository.deleteTransaction("b-row", "user-b")
        repository.deleteAccount("b-account", "user-b")
        repository.deleteCard("b-card", "user-b")
        repository.deleteSplitsForTransaction("b-row", "user-b")

        assertEquals("b-row", dao.getTransactionByIdSync("user-b", "b-row")?.id)
        assertEquals(1, dao.getAllAccountsSyncForUser("user-b").size)
        assertEquals(1, dao.getAllCardsSyncForUser("user-b").size)
        assertEquals(1, dao.getSplitsForTransactionSync("user-b", "b-row").size)
    }

    @Test
    fun splitWritesRequireOwnedParentAndMatchingSplitOwner() = runBlocking {
        dao.insertTransaction(transaction("a-row", "user-a"))
        dao.insertSplits(listOf(split("existing-a-split", "a-row", "user-a")))

        repository.insertSplitsForTransaction("a-row", listOf(split("foreign-split", "a-row", "user-b")))
        repository.insertSplitsForTransaction("missing-row", listOf(split("missing-parent-split", "missing-row", "user-a")))

        assertEquals(
            listOf("existing-a-split"),
            dao.getSplitsForTransactionSync("user-a", "a-row").map { it.id }
        )
        assertTrue(dao.getSplitsForTransactionSync("user-a", "missing-row").isEmpty())
    }

    @Test
    fun loggedOutRepositoryRejectsReadsWritesAndDeletes() = runBlocking {
        dao.insertTransaction(transaction("a-row", "user-a"))
        authSource.setUid(null)

        assertTrue(repository.allTransactions.first().isEmpty())
        assertEquals(-1L, repository.insertTransaction(transaction("new-row", "")))
        repository.deleteTransaction("a-row")

        assertNull(dao.getTransactionByIdSync("user-a", "new-row"))
        assertEquals("a-row", dao.getTransactionByIdSync("user-a", "a-row")?.id)
    }

    @Test
    fun subcategoryMovePreservesExistingTransactionAndSplitHistoryByDefault() = runBlocking {
        dao.insertCategory(category("old-category", "user-a"))
        dao.insertCategory(category("new-category", "user-a"))
        dao.insertCategory(category("new-category", "user-b"))
        dao.insertSubcategory(subcategory("shared-subcategory", "old-category", "user-a"))
        dao.insertSubcategory(subcategory("shared-subcategory", "old-category", "user-b"))
        dao.insertTransaction(
            transaction("a-transaction", "user-a").copy(
                categoryId = "old-category",
                subcategoryId = "shared-subcategory"
            )
        )
        dao.insertTransaction(
            transaction("b-transaction", "user-b").copy(
                categoryId = "old-category",
                subcategoryId = "shared-subcategory"
            )
        )
        dao.insertSplits(
            listOf(
                split("a-split", "a-transaction", "user-a").copy(
                    categoryId = "old-category",
                    subcategoryId = "shared-subcategory"
                ),
                split("b-split", "b-transaction", "user-b").copy(
                    categoryId = "old-category",
                    subcategoryId = "shared-subcategory"
                )
            )
        )

        repository.moveSubcategory("shared-subcategory", "new-category", "moved-at")

        assertEquals(
            "new-category",
            dao.getAllSubcategoriesSyncForUser("user-a").single().categoryId
        )
        assertEquals(
            "old-category",
            dao.getTransactionByIdSync("user-a", "a-transaction")?.categoryId
        )
        assertEquals(
            "shared-subcategory",
            dao.getTransactionByIdSync("user-a", "a-transaction")?.subcategoryId
        )
        assertEquals(
            "old-category",
            dao.getSplitsForTransactionSync("user-a", "a-transaction").single().categoryId
        )
        assertEquals(
            "old-category",
            dao.getTransactionByIdSync("user-b", "b-transaction")?.categoryId
        )
        assertEquals(
            "old-category",
            dao.getSplitsForTransactionSync("user-b", "b-transaction").single().categoryId
        )
    }

    @Test
    fun confirmedHistoricalMoveUpdatesOwnedTransactionsAndSplitsOnly() = runBlocking {
        dao.insertCategory(category("old-category", "user-a"))
        dao.insertCategory(category("new-category", "user-a"))
        dao.insertSubcategory(subcategory("shared-subcategory", "old-category", "user-a"))
        dao.insertSubcategory(subcategory("shared-subcategory", "old-category", "user-b"))
        dao.insertTransaction(
            transaction("a-transaction", "user-a").copy(
                categoryId = "old-category",
                subcategoryId = "shared-subcategory"
            )
        )
        dao.insertTransaction(
            transaction("b-transaction", "user-b").copy(
                categoryId = "old-category",
                subcategoryId = "shared-subcategory"
            )
        )
        dao.insertSplits(
            listOf(
                split("a-split", "a-transaction", "user-a").copy(
                    categoryId = "old-category",
                    subcategoryId = "shared-subcategory"
                ),
                split("b-split", "b-transaction", "user-b").copy(
                    categoryId = "old-category",
                    subcategoryId = "shared-subcategory"
                )
            )
        )

        repository.moveSubcategory(
            "shared-subcategory",
            "new-category",
            "moved-at",
            moveTransactions = true
        )

        val movedTransaction = dao.getTransactionByIdSync("user-a", "a-transaction")
        val movedSplit = dao.getSplitsForTransactionSync("user-a", "a-transaction").single()
        assertEquals("new-category", movedTransaction?.categoryId)
        assertEquals("shared-subcategory", movedTransaction?.subcategoryId)
        assertEquals("user-a", movedTransaction?.userId)
        assertEquals("new-category", movedSplit.categoryId)
        assertEquals("shared-subcategory", movedSplit.subcategoryId)
        assertEquals("user-a", movedSplit.userId)
        assertEquals(
            "old-category",
            dao.getTransactionByIdSync("user-b", "b-transaction")?.categoryId
        )
        assertEquals(
            "old-category",
            dao.getSplitsForTransactionSync("user-b", "b-transaction").single().categoryId
        )
    }

    @Test
    fun subcategoryMoveRejectsForeignSourceInvalidDestinationAndLoggedOutOwner() = runBlocking {
        dao.insertCategory(category("a-category", "user-a"))
        dao.insertCategory(category("b-category", "user-b"))
        dao.insertSubcategory(subcategory("a-subcategory", "a-category", "user-a"))
        dao.insertSubcategory(subcategory("b-subcategory", "b-category", "user-b"))
        dao.insertTransaction(
            transaction("a-transaction", "user-a").copy(
                categoryId = "a-category",
                subcategoryId = "a-subcategory"
            )
        )

        repository.moveSubcategory("a-subcategory", "b-category", "invalid-target")
        repository.moveSubcategory("b-subcategory", "a-category", "foreign-source")

        assertEquals(
            "a-category",
            dao.getAllSubcategoriesSyncForUser("user-a").single().categoryId
        )
        assertEquals(
            "b-category",
            dao.getAllSubcategoriesSyncForUser("user-b").single().categoryId
        )
        assertEquals(
            "a-category",
            dao.getTransactionByIdSync("user-a", "a-transaction")?.categoryId
        )

        authSource.setUid(null)
        repository.moveSubcategory("a-subcategory", "a-category", "logged-out")
        assertEquals(
            "a-category",
            dao.getAllSubcategoriesSyncForUser("user-a").single().categoryId
        )
    }

    @Test
    fun referencedSubcategoryDeletionIsBlockedWithoutChangingTransactionHistory() = runBlocking {
        dao.insertCategory(category("category-a", "user-a"))
        dao.insertSubcategory(subcategory("subcategory-a", "category-a", "user-a"))
        dao.insertSubcategory(subcategory("subcategory-a", "category-a", "user-b"))
        dao.insertTransaction(
            transaction("a-transaction", "user-a").copy(
                categoryId = "category-a",
                subcategoryId = "subcategory-a"
            )
        )
        dao.insertSplits(
            listOf(
                split("a-split", "a-transaction", "user-a").copy(
                    categoryId = "category-a",
                    subcategoryId = "subcategory-a"
                )
            )
        )

        val result = repository.deleteSubcategory("subcategory-a")

        val transaction = dao.getTransactionByIdSync("user-a", "a-transaction")
        val split = dao.getSplitsForTransactionSync("user-a", "a-transaction").single()
        assertEquals(SafeDeleteResult.IN_USE, result)
        assertEquals("category-a", transaction?.categoryId)
        assertEquals("subcategory-a", transaction?.subcategoryId)
        assertEquals("category-a", split.categoryId)
        assertEquals("subcategory-a", split.subcategoryId)
        assertEquals(1, dao.getAllSubcategoriesSyncForUser("user-a").size)
        assertEquals(1, dao.getAllSubcategoriesSyncForUser("user-b").size)
    }

    @Test
    fun blankOwnerIsAssignedOnlyWithLiveAuthenticatedUid() = runBlocking {
        val result = repository.insertTransaction(transaction("assigned-row", ""))

        assertTrue(result >= 0L)
        assertEquals("user-a", dao.getTransactionByIdSync("user-a", "assigned-row")?.userId)

        authSource.setUid(null)
        assertEquals(-1L, repository.insertTransaction(transaction("unassigned-row", "")))
        assertNull(dao.getTransactionByIdSync("legacy:unassigned", "unassigned-row"))
    }

    @Test
    fun globalDuplicateCleanupIsRejectedUntilOwnerScopedImplementationExists() = runBlocking {
        dao.insertTransaction(transaction("a-row", "user-a"))
        dao.insertTransaction(transaction("b-row", "user-b"))

        assertEquals(-1, repository.cleanupDuplicateTransactions())
        assertEquals("a-row", dao.getTransactionByIdSync("user-a", "a-row")?.id)
        assertEquals("b-row", dao.getTransactionByIdSync("user-b", "b-row")?.id)
    }

    private fun transaction(
        id: String,
        userId: String,
        amount: Double = 10.0
    ) = TransactionEntity(
        id = id,
        type = "EXPENSE",
        amount = amount,
        date = "2026-01-01",
        time = "12:00",
        merchant = "Merchant",
        categoryId = "category",
        subcategoryId = "",
        accountId = "",
        paymentMethod = "CASH",
        note = "",
        source = "MANUAL",
        transactionReference = "",
        userId = userId
    )

    private fun split(id: String, transactionId: String, userId: String) =
        TransactionSplitEntity(
            id = id,
            transactionId = transactionId,
            categoryId = "category",
            amount = 10.0,
            createdAt = "",
            updatedAt = "",
            userId = userId
        )

    private fun card(id: String, userId: String) = CardEntity(
        id = id,
        accountId = "account",
        name = id,
        type = "Debit Card",
        last4Digits = "",
        userId = userId
    )

    private fun category(id: String, userId: String) = CategoryEntity(
        id = id,
        name = id,
        icon = "",
        colour = "",
        createdAt = "",
        updatedAt = "",
        userId = userId
    )

    private fun subcategory(id: String, categoryId: String, userId: String) = SubcategoryEntity(
        id = id,
        categoryId = categoryId,
        name = id,
        icon = "",
        colour = "",
        createdAt = "",
        updatedAt = "",
        userId = userId
    )

    private class TestAuthenticatedUidSource(initialUid: String?) : AuthenticatedUidSource {
        private val state = MutableStateFlow(initialUid)

        override val currentUid: String?
            get() = state.value

        override val uidChanges: Flow<String?> = state

        fun setUid(uid: String?) {
            state.value = uid
        }
    }
}
