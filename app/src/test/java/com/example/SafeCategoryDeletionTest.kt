package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.dao.SafeDeleteResult
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
class SafeCategoryDeletionTest {
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun unusedCategoryDeletionRemovesOnlyTheRequestedCategory() = runBlocking {
        dao.insertCategory(category("category-a", "user-a"))
        dao.insertCategory(category("category-b", "user-a"))

        assertEquals(SafeDeleteResult.AVAILABLE, repository.checkCategoryDeleteStatus("category-a"))
        assertEquals(SafeDeleteResult.DELETED, repository.deleteCategory("category-a"))

        assertTrue(dao.getAllCategoriesSyncForUser("user-a").none { it.id == "category-a" })
        assertTrue(dao.getAllCategoriesSyncForUser("user-a").any { it.id == "category-b" })
    }

    @Test
    fun transactionReferenceBlocksCategoryDeletionWithoutChangingTransaction() = runBlocking {
        dao.insertCategory(category("category-a", "user-a"))
        val original = transaction("transaction-a", "user-a").copy(categoryId = "category-a")
        dao.insertTransaction(original)

        assertEquals(SafeDeleteResult.IN_USE, repository.deleteCategory("category-a"))
        assertEquals(original, dao.getTransactionByIdSync("user-a", original.id))
        assertTrue(dao.getAllCategoriesSyncForUser("user-a").any { it.id == "category-a" })
    }

    @Test
    fun splitReferenceBlocksCategoryDeletionWithoutChangingSplitOrParentTransaction() = runBlocking {
        dao.insertCategory(category("category-a", "user-a"))
        val originalTransaction = transaction("transaction-a", "user-a")
        val originalSplit = split("split-a", originalTransaction.id, "user-a").copy(categoryId = "category-a")
        dao.insertTransaction(originalTransaction)
        dao.insertSplits(listOf(originalSplit))

        assertEquals(SafeDeleteResult.IN_USE, repository.deleteCategory("category-a"))
        assertEquals(originalTransaction, dao.getTransactionByIdSync("user-a", originalTransaction.id))
        assertEquals(originalSplit, dao.getSplitsForTransactionSync("user-a", originalTransaction.id).single())
        assertTrue(dao.getAllCategoriesSyncForUser("user-a").any { it.id == "category-a" })
    }

    @Test
    fun childSubcategoryBlocksCategoryDeletionToPreventOrphan() = runBlocking {
        dao.insertCategory(category("category-a", "user-a"))
        val original = subcategory("subcategory-a", "category-a", "user-a")
        dao.insertSubcategory(original)

        assertEquals(SafeDeleteResult.IN_USE, repository.deleteCategory("category-a"))
        assertEquals(original, dao.getAllSubcategoriesSyncForUser("user-a").single())
        assertTrue(dao.getAllCategoriesSyncForUser("user-a").any { it.id == "category-a" })
    }

    @Test
    fun unusedSubcategoryDeletionRemovesOnlyTheRequestedSubcategory() = runBlocking {
        dao.insertCategory(category("category-a", "user-a"))
        val target = subcategory("subcategory-a", "category-a", "user-a")
        val other = subcategory("subcategory-b", "category-a", "user-a")
        dao.insertSubcategory(target)
        dao.insertSubcategory(other)

        assertEquals(SafeDeleteResult.AVAILABLE, repository.checkSubcategoryDeleteStatus(target.id))
        assertEquals(SafeDeleteResult.DELETED, repository.deleteSubcategory(target.id))

        assertTrue(dao.getAllSubcategoriesSyncForUser("user-a").none { it.id == target.id })
        assertEquals(other, dao.getAllSubcategoriesSyncForUser("user-a").single())
    }

    @Test
    fun transactionReferenceBlocksSubcategoryDeletionWithoutChangingTransaction() = runBlocking {
        dao.insertCategory(category("category-a", "user-a"))
        val originalSubcategory = subcategory("subcategory-a", "category-a", "user-a")
        val originalTransaction = transaction("transaction-a", "user-a").copy(
            categoryId = "category-a",
            subcategoryId = originalSubcategory.id
        )
        dao.insertSubcategory(originalSubcategory)
        dao.insertTransaction(originalTransaction)

        assertEquals(SafeDeleteResult.IN_USE, repository.deleteSubcategory(originalSubcategory.id))
        assertEquals(originalTransaction, dao.getTransactionByIdSync("user-a", originalTransaction.id))
        assertEquals(originalSubcategory, dao.getAllSubcategoriesSyncForUser("user-a").single())
    }

    @Test
    fun splitReferenceBlocksSubcategoryDeletionWithoutChangingSplitOrParentTransaction() = runBlocking {
        dao.insertCategory(category("category-a", "user-a"))
        val originalSubcategory = subcategory("subcategory-a", "category-a", "user-a")
        val originalTransaction = transaction("transaction-a", "user-a")
        val originalSplit = split("split-a", originalTransaction.id, "user-a").copy(
            categoryId = "category-a",
            subcategoryId = originalSubcategory.id
        )
        dao.insertSubcategory(originalSubcategory)
        dao.insertTransaction(originalTransaction)
        dao.insertSplits(listOf(originalSplit))

        assertEquals(SafeDeleteResult.IN_USE, repository.deleteSubcategory(originalSubcategory.id))
        assertEquals(originalTransaction, dao.getTransactionByIdSync("user-a", originalTransaction.id))
        assertEquals(originalSplit, dao.getSplitsForTransactionSync("user-a", originalTransaction.id).single())
        assertEquals(originalSubcategory, dao.getAllSubcategoriesSyncForUser("user-a").single())
    }

    @Test
    fun foreignOwnedCategoryAndSubcategoryCannotBeDeleted() = runBlocking {
        dao.insertCategory(category("shared-category", "user-a"))
        dao.insertCategory(category("shared-category", "user-b"))
        dao.insertSubcategory(subcategory("shared-subcategory", "shared-category", "user-a"))
        val foreignSubcategory = subcategory("shared-subcategory", "shared-category", "user-b")
        dao.insertSubcategory(foreignSubcategory)

        assertEquals(
            SafeDeleteResult.NOT_AUTHENTICATED,
            repository.deleteCategory("shared-category", expectedOwner = "user-b")
        )
        assertEquals(
            SafeDeleteResult.NOT_AUTHENTICATED,
            repository.deleteSubcategory("shared-subcategory", expectedOwner = "user-b")
        )

        assertEquals(1, dao.getAllCategoriesSyncForUser("user-b").size)
        assertEquals(foreignSubcategory, dao.getAllSubcategoriesSyncForUser("user-b").single())
    }

    @Test
    fun loggedOutCategoryAndSubcategoryDeletionFailsWithoutChangingRecords() = runBlocking {
        val originalCategory = category("category-a", "user-a")
        val originalSubcategory = subcategory("subcategory-a", originalCategory.id, "user-a")
        dao.insertCategory(originalCategory)
        dao.insertSubcategory(originalSubcategory)
        auth.setUid(null)

        assertEquals(SafeDeleteResult.NOT_AUTHENTICATED, repository.deleteCategory(originalCategory.id))
        assertEquals(SafeDeleteResult.NOT_AUTHENTICATED, repository.deleteSubcategory(originalSubcategory.id))

        assertEquals(originalCategory, dao.getAllCategoriesSyncForUser("user-a").single())
        assertEquals(originalSubcategory, dao.getAllSubcategoriesSyncForUser("user-a").single())
    }

    private fun category(id: String, userId: String) = CategoryEntity(
        id = id,
        name = id,
        nameHindi = id,
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
        nameHindi = id,
        icon = "🏷️",
        colour = "#123456",
        createdAt = "created",
        updatedAt = "updated",
        userId = userId
    )

    private fun transaction(id: String, userId: String) = TransactionEntity(
        id = id,
        type = "EXPENSE",
        amount = 125.5,
        date = "2026-10-06",
        time = "12:00",
        merchant = "Merchant",
        categoryId = "unrelated-category",
        subcategoryId = "",
        accountId = "account",
        paymentMethod = "Cash",
        note = "Original transaction",
        source = "MANUAL",
        transactionReference = "reference",
        userId = userId
    )

    private fun split(id: String, transactionId: String, userId: String) = TransactionSplitEntity(
        id = id,
        transactionId = transactionId,
        categoryId = "unrelated-category",
        subcategoryId = "",
        amount = 125.5,
        note = "Original split",
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
