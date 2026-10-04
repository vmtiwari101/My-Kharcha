package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KharchaDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC, time DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions")
    suspend fun getAllTransactionsSync(): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionByIdSync(id: String): TransactionEntity?

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: String)

    @Query("SELECT * FROM transaction_splits")
    fun getAllSplits(): Flow<List<TransactionSplitEntity>>

    @Query("SELECT * FROM transaction_splits")
    suspend fun getAllSplitsSync(): List<TransactionSplitEntity>

    @Query("SELECT * FROM transaction_splits WHERE transactionId = :txId")
    suspend fun getSplitsForTransactionSync(txId: String): List<TransactionSplitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<TransactionSplitEntity>)

    @Query("DELETE FROM transaction_splits WHERE transactionId = :txId")
    suspend fun deleteSplitsForTransaction(txId: String)

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun getAllCategoriesSync(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: String)

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun getTransactionCountForCategory(categoryId: String): Int

    @Query("SELECT COUNT(*) FROM transaction_splits WHERE categoryId = :categoryId")
    suspend fun getSplitCountForCategory(categoryId: String): Int

    @Query("UPDATE transactions SET categoryId = :targetCategoryId, updatedAt = :updatedAt WHERE categoryId = :sourceCategoryId")
    suspend fun reassignTransactionsCategory(sourceCategoryId: String, targetCategoryId: String, updatedAt: String)

    @Query("UPDATE transaction_splits SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignSplitsCategory(sourceCategoryId: String, targetCategoryId: String)

    @Query("UPDATE subcategories SET categoryId = :targetCategoryId, updatedAt = :updatedAt WHERE categoryId = :sourceCategoryId")
    suspend fun reassignSubcategoriesCategory(sourceCategoryId: String, targetCategoryId: String, updatedAt: String)

    @Query("UPDATE transactions SET categoryId = 'cat-other', updatedAt = :updatedAt WHERE categoryId = :categoryId")
    suspend fun clearCategoryFromTransactions(categoryId: String, updatedAt: String)

    @Query("UPDATE transaction_splits SET categoryId = 'cat-other' WHERE categoryId = :categoryId")
    suspend fun clearCategoryFromSplits(categoryId: String)

    @Query("DELETE FROM subcategories WHERE categoryId = :categoryId")
    suspend fun deleteSubcategoriesForCategory(categoryId: String)

    @Query("SELECT * FROM subcategories ORDER BY name ASC")
    fun getAllSubcategories(): Flow<List<SubcategoryEntity>>

    @Query("SELECT * FROM subcategories")
    suspend fun getAllSubcategoriesSync(): List<SubcategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubcategory(subcategory: SubcategoryEntity)

    @Query("DELETE FROM subcategories WHERE id = :id")
    suspend fun deleteSubcategory(id: String)

    @Query("UPDATE transactions SET categoryId = :newCategoryId, updatedAt = :updatedAt WHERE subcategoryId = :subcategoryId")
    suspend fun updateTransactionsCategoryForSubcategory(subcategoryId: String, newCategoryId: String, updatedAt: String)

    @Query("UPDATE transaction_splits SET categoryId = :newCategoryId WHERE subcategoryId = :subcategoryId")
    suspend fun updateSplitsCategoryForSubcategory(subcategoryId: String, newCategoryId: String)

    @Query("UPDATE transactions SET subcategoryId = '', updatedAt = :updatedAt WHERE subcategoryId = :subcategoryId")
    suspend fun clearSubcategoryFromTransactions(subcategoryId: String, updatedAt: String)

    @Query("UPDATE transaction_splits SET subcategoryId = '' WHERE subcategoryId = :subcategoryId")
    suspend fun clearSubcategoryFromSplits(subcategoryId: String)

    @Query("SELECT * FROM accounts ORDER BY name ASC")
    fun getAllAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun getAllAccountsSync(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccount(id: String)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCategoryCount(): Int

    @Query("SELECT * FROM cards ORDER BY name ASC")
    fun getAllCards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards")
    suspend fun getAllCardsSync(): List<CardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: CardEntity)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun deleteCard(id: String)
}
