package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.dao.SplitOperationResult
import com.example.data.database.AppDatabase
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.data.firestore.FirestoreRepository
import com.example.data.repository.AuthenticatedUidSource
import com.example.data.repository.KharchaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
@Config(sdk = [36])
class SplitTransactionIntegrityTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: com.example.data.dao.KharchaDao
    private lateinit var auth: TestUidSource
    private lateinit var repository: KharchaRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.kharchaDao()
        auth = TestUidSource("user-a")
        repository = KharchaRepository(
            dao,
            FirestoreRepository(firestoreProvider = { null }, authProvider = { null }),
            auth
        )
        runBlocking {
            listOf("category-a", "category-b", "category-income", "category-transfer").forEach {
                dao.insertCategory(category(it, "user-a"))
            }
            dao.insertCategory(category("category-a", "user-b"))
            dao.insertSubcategory(subcategory("subcategory-a", "category-a", "user-a"))
            dao.insertSubcategory(subcategory("subcategory-b", "category-b", "user-a"))
            dao.insertSubcategory(subcategory("subcategory-a", "category-a", "user-b"))
            dao.insertSubcategory(subcategory("subcategory-b-only", "category-a", "user-b"))
            dao.insertCategory(category("category-b-only", "user-b"))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun savesBalancedExpenseAndIncomeSplitsWithoutChangingParentFields() = runBlocking {
        listOf(
            transaction("expense", "user-a", "EXPENSE", 100.0),
            transaction("income", "user-a", "INCOME", 100.0, categoryId = "category-income"),
            transaction("negative-income", "user-a", "INCOME", -100.0, categoryId = "category-income"),
            transaction(
                "transfer",
                "user-a",
                "INTERNAL_TRANSFER",
                100.0,
                categoryId = "category-transfer"
            ).copy(
                isInternalTransfer = true,
                transactionType = "INTERNAL_TRANSFER",
                accountId = "source-account",
                counterpartyAccountId = "destination-account",
                cardId = "card-ref"
            ),
            transaction("card-payment", "user-a", "EXPENSE", 100.0).copy(
                transactionType = "CREDIT_CARD_BILL_PAYMENT",
                accountId = "card-account",
                cardId = "payment-card"
            )
        ).forEach { original ->
            val result = repository.saveTransactionWithSplits(
                original,
                listOf(
                    split("${original.id}-1", original.id, "category-a", 35.0),
                    split("${original.id}-2", original.id, "category-b", 65.0)
                )
            )
            assertEquals(SplitOperationResult.SAVED, result)
            assertEquals(original, dao.getTransactionByIdSync("user-a", original.id))
            assertEquals(100.0, dao.getSplitsForTransactionSync("user-a", original.id).sumOf { it.amount }, 0.0)
        }
    }

    @Test
    fun rejectsOverAndUnderAllocationWithoutPartiallySavingParent() = runBlocking {
        val parent = transaction("new-parent", "user-a")

        assertEquals(
            SplitOperationResult.INVALID_TOTAL,
            repository.saveTransactionWithSplits(
                parent,
                listOf(
                    split("over-1", parent.id, "category-a", 75.0),
                    split("over-2", parent.id, "category-b", 50.0)
                )
            )
        )
        assertEquals(
            SplitOperationResult.INVALID_TOTAL,
            repository.saveTransactionWithSplits(
                parent,
                listOf(
                    split("under-1", parent.id, "category-a", 25.0),
                    split("under-2", parent.id, "category-b", 50.0)
                )
            )
        )
        assertEquals(null, dao.getTransactionByIdSync("user-a", parent.id))
        assertTrue(dao.getAllSplitsSyncForUser("user-a").isEmpty())
    }

    @Test
    fun rejectsZeroAndNegativeSplitAmounts() = runBlocking {
        val parent = transaction("invalid-amount-parent", "user-a")
        dao.insertTransaction(parent)

        for (amounts in listOf(0.0 to 100.0, -1.0 to 101.0)) {
            assertEquals(
                SplitOperationResult.INVALID_AMOUNT,
                repository.insertSplitsForTransaction(
                    parent.id,
                    listOf(
                        split("invalid-a-${amounts.first}", parent.id, "category-a", amounts.first),
                        split("invalid-b-${amounts.first}", parent.id, "category-b", amounts.second)
                    )
                )
            )
        }
        assertTrue(dao.getSplitsForTransactionSync("user-a", parent.id).isEmpty())
    }

    @Test
    fun failedSplitEditLeavesOriginalSplitsAndParentUnchanged() = runBlocking {
        val parent = transaction("edit-parent", "user-a")
        val oldSplits = listOf(
            split("edit-split-a", parent.id, "category-a", 40.0),
            split("edit-split-b", parent.id, "category-b", 60.0)
        )
        dao.insertTransaction(parent)
        assertEquals(SplitOperationResult.SAVED, repository.insertSplitsForTransaction(parent.id, oldSplits))

        assertEquals(
            SplitOperationResult.INVALID_TOTAL,
            repository.insertSplitsForTransaction(
                parent.id,
                listOf(
                    split("new-split-a", parent.id, "category-a", 40.0),
                    split("new-split-b", parent.id, "category-b", 50.0)
                )
            )
        )
        assertEquals(parent, dao.getTransactionByIdSync("user-a", parent.id))
        assertEquals(oldSplits, dao.getSplitsForTransactionSync("user-a", parent.id))
    }

    @Test
    fun validSplitEditUpdatesParentAndSplitsAtomically() = runBlocking {
        val parent = transaction("atomic-edit", "user-a")
        val oldSplits = listOf(
            split("atomic-old-a", parent.id, "category-a", 40.0),
            split("atomic-old-b", parent.id, "category-b", 60.0)
        )
        dao.insertTransaction(parent)
        assertEquals(SplitOperationResult.SAVED, repository.insertSplitsForTransaction(parent.id, oldSplits))

        val updatedParent = parent.copy(amount = 125.0, merchant = "Updated merchant")
        val updatedSplits = listOf(
            split("atomic-new-a", parent.id, "category-a", 75.0),
            split("atomic-new-b", parent.id, "category-b", 50.0)
        )
        assertEquals(
            SplitOperationResult.SAVED,
            repository.updateTransactionWithSplits(updatedParent, updatedSplits)
        )
        assertEquals(updatedParent, dao.getTransactionByIdSync("user-a", parent.id))
        assertEquals(updatedSplits, dao.getSplitsForTransactionSync("user-a", parent.id))
        assertEquals(125.0, dao.getSplitsForTransactionSync("user-a", parent.id).sumOf { it.amount }, 0.0)
    }

    @Test
    fun failedAtomicEditLeavesOriginalParentAndSplitsUnchanged() = runBlocking {
        val parent = transaction("failed-atomic-edit", "user-a")
        val originalSplits = listOf(
            split("failed-old-a", parent.id, "category-a", 40.0),
            split("failed-old-b", parent.id, "category-b", 60.0)
        )
        dao.insertTransaction(parent)
        assertEquals(SplitOperationResult.SAVED, repository.insertSplitsForTransaction(parent.id, originalSplits))

        assertEquals(
            SplitOperationResult.INVALID_TOTAL,
            repository.updateTransactionWithSplits(
                parent.copy(amount = 200.0, accountId = "new-account"),
                listOf(
                    split("failed-new-a", parent.id, "category-a", 75.0),
                    split("failed-new-b", parent.id, "category-b", 50.0)
                )
            )
        )
        assertEquals(parent, dao.getTransactionByIdSync("user-a", parent.id))
        assertEquals(originalSplits, dao.getSplitsForTransactionSync("user-a", parent.id))
    }

    @Test
    fun editingParentAmountCannotInvalidateExistingSplitsOrChangeOtherFields() = runBlocking {
        val parent = transaction("parent-edit", "user-a")
        val originalSplits = listOf(
            split("parent-edit-a", parent.id, "category-a", 40.0),
            split("parent-edit-b", parent.id, "category-b", 60.0)
        )
        dao.insertTransaction(parent)
        assertEquals(SplitOperationResult.SAVED, repository.insertSplitsForTransaction(parent.id, originalSplits))

        val changedAmount = parent.copy(amount = 125.0, merchant = "Changed merchant")
        assertEquals(-1L, repository.insertTransaction(changedAmount))
        assertEquals(parent, dao.getTransactionByIdSync("user-a", parent.id))
        assertEquals(originalSplits, dao.getSplitsForTransactionSync("user-a", parent.id))
    }

    @Test
    fun transactionDeletionRemovesOnlyItsSplitsAndNeverAnotherUsers() = runBlocking {
        val transactionA = transaction("transaction-a", "user-a")
        val transactionB = transaction("transaction-b", "user-b")
        dao.insertTransaction(transactionA)
        dao.insertTransaction(transactionB)
        val splitsA = listOf(
            split("split-a1", transactionA.id, "category-a", 40.0),
            split("split-a2", transactionA.id, "category-b", 60.0)
        )
        val splitB = split("split-b", transactionB.id, "category-a", 100.0, "user-b")
        dao.insertSplits(splitsA + splitB)

        assertTrue(!repository.deleteTransaction(transactionB.id))
        assertEquals(splitB, dao.getSplitsForTransactionSync("user-b", transactionB.id).single())
        assertTrue(repository.deleteTransaction(transactionA.id))
        assertEquals(null, dao.getTransactionByIdSync("user-a", transactionA.id))
        assertTrue(dao.getSplitsForTransactionSync("user-a", transactionA.id).isEmpty())
        assertEquals(transactionB, dao.getTransactionByIdSync("user-b", transactionB.id))
        assertEquals(splitB, dao.getSplitsForTransactionSync("user-b", transactionB.id).single())
    }

    @Test
    fun foreignParentSplitCategoryAndSubcategoryAreRejected() = runBlocking {
        val parent = transaction("foreign-check", "user-a")
        dao.insertTransaction(parent)

        assertEquals(
            SplitOperationResult.NOT_FOUND,
            repository.insertSplitsForTransaction(
                "transaction-owned-by-b",
                listOf(
                    split("foreign-parent-a", "transaction-owned-by-b", "category-a", 50.0),
                    split("foreign-parent-b", "transaction-owned-by-b", "category-b", 50.0)
                )
            )
        )
        assertEquals(
            SplitOperationResult.INVALID_REFERENCE,
            repository.insertSplitsForTransaction(
                parent.id,
                listOf(
                    split("foreign-category-a", parent.id, "category-b-only", 50.0),
                    split("foreign-category-b", parent.id, "category-b", 50.0)
                )
            )
        )
        assertEquals(
            SplitOperationResult.INVALID_REFERENCE,
            repository.insertSplitsForTransaction(
                parent.id,
                listOf(
                    split("foreign-subcategory-a", parent.id, "category-a", 50.0, subcategoryId = "subcategory-b-only"),
                    split("foreign-subcategory-b", parent.id, "category-b", 50.0)
                )
            )
        )
        assertEquals(
            SplitOperationResult.INVALID_REFERENCE,
            repository.saveTransactionWithSplits(
                transaction("foreign-parent-category", "user-a", categoryId = "category-b-only"),
                listOf(
                    split("foreign-parent-category-a", "foreign-parent-category", "category-a", 50.0),
                    split("foreign-parent-category-b", "foreign-parent-category", "category-b", 50.0)
                )
            )
        )
        assertEquals(
            SplitOperationResult.INVALID_REFERENCE,
            repository.saveTransactionWithSplits(
                transaction("foreign-parent-subcategory", "user-a").copy(subcategoryId = "subcategory-b-only"),
                listOf(
                    split("foreign-parent-subcategory-a", "foreign-parent-subcategory", "category-a", 50.0),
                    split("foreign-parent-subcategory-b", "foreign-parent-subcategory", "category-b", 50.0)
                )
            )
        )
        assertTrue(dao.getSplitsForTransactionSync("user-a", parent.id).isEmpty())
        assertEquals(null, dao.getTransactionByIdSync("user-a", "foreign-parent-category"))
        assertEquals(null, dao.getTransactionByIdSync("user-a", "foreign-parent-subcategory"))
    }

    @Test
    fun foreignSplitOwnerAndLoggedOutUserAreRejected() = runBlocking {
        val parent = transaction("owner-check", "user-a")
        dao.insertTransaction(parent)
        val foreignSplit = split("foreign-split", parent.id, "category-a", 50.0, "user-b")

        assertEquals(
            SplitOperationResult.INVALID_REFERENCE,
            repository.insertSplitsForTransaction(
                parent.id,
                listOf(foreignSplit, split("owner-check-b", parent.id, "category-b", 50.0))
            )
        )
        auth.setUid(null)
        assertEquals(
            SplitOperationResult.NOT_AUTHENTICATED,
            repository.insertSplitsForTransaction(
                parent.id,
                listOf(
                    split("logged-out-a", parent.id, "category-a", 50.0),
                    split("logged-out-b", parent.id, "category-b", 50.0)
                )
            )
        )
        assertTrue(dao.getSplitsForTransactionSync("user-a", parent.id).isEmpty())
    }

    @Test
    fun foreignTransactionIdCannotBeUsedToReplaceSplits() = runBlocking {
        val foreignParent = transaction("foreign-transaction", "user-b")
        val foreignSplits = listOf(
            split("foreign-original-a", foreignParent.id, "category-a", 40.0, "user-b"),
            split("foreign-original-b", foreignParent.id, "category-a", 60.0, "user-b")
        )
        dao.insertTransaction(foreignParent)
        dao.insertSplits(foreignSplits)

        assertEquals(
            SplitOperationResult.NOT_FOUND,
            repository.insertSplitsForTransaction(
                foreignParent.id,
                listOf(
                    split("attempt-a", foreignParent.id, "category-a", 50.0),
                    split("attempt-b", foreignParent.id, "category-a", 50.0)
                )
            )
        )
        assertEquals(foreignSplits, dao.getSplitsForTransactionSync("user-b", foreignParent.id))
    }

    @Test
    fun duplicateParentProcessingDoesNotReplaceExistingSplits() = runBlocking {
        val parent = transaction("duplicate-parent", "user-a")
        val originalSplits = listOf(
            split("duplicate-split-a", parent.id, "category-a", 50.0),
            split("duplicate-split-b", parent.id, "category-b", 50.0)
        )

        assertEquals(SplitOperationResult.SAVED, repository.saveTransactionWithSplits(parent, originalSplits))
        assertEquals(
            SplitOperationResult.DUPLICATE,
            repository.saveTransactionWithSplits(
                parent.copy(merchant = "Retry"),
                listOf(
                    split("retry-split-a", parent.id, "category-a", 20.0),
                    split("retry-split-b", parent.id, "category-b", 80.0)
                )
            )
        )
        assertEquals(parent, dao.getTransactionByIdSync("user-a", parent.id))
        assertEquals(originalSplits, dao.getSplitsForTransactionSync("user-a", parent.id))
    }

    private fun category(id: String, userId: String) = CategoryEntity(
        id = id,
        name = id,
        icon = "📁",
        colour = "#123456",
        createdAt = "created",
        updatedAt = "updated",
        userId = userId
    )

    private fun subcategory(id: String, categoryId: String, userId: String) = SubcategoryEntity(
        id = id,
        categoryId = categoryId,
        name = id,
        icon = "🏷️",
        colour = "#123456",
        createdAt = "created",
        updatedAt = "updated",
        userId = userId
    )

    private fun transaction(
        id: String,
        userId: String,
        type: String = "EXPENSE",
        amount: Double = 100.0,
        categoryId: String = "category-a"
    ) = TransactionEntity(
        id = id,
        type = type,
        amount = amount,
        date = "2026-10-06",
        time = "12:00",
        merchant = "Original merchant",
        categoryId = categoryId,
        subcategoryId = "",
        accountId = "account-ref",
        paymentMethod = "Card",
        note = "Original note",
        source = "MANUAL",
        transactionReference = "reference",
        cardId = "card-ref",
        userId = userId
    )

    private fun split(
        id: String,
        transactionId: String,
        categoryId: String,
        amount: Double,
        userId: String = "user-a",
        subcategoryId: String = ""
    ) = TransactionSplitEntity(
        id = id,
        transactionId = transactionId,
        categoryId = categoryId,
        subcategoryId = subcategoryId,
        amount = amount,
        note = id,
        createdAt = "created",
        updatedAt = "updated",
        userId = userId
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
