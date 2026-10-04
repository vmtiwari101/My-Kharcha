package com.example.data.repository

import android.util.Log
import com.example.data.dao.KharchaDao
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.data.firestore.FirestoreRepository
import kotlinx.coroutines.flow.Flow

class KharchaRepository(
    private val dao: KharchaDao,
    private val firestoreRepository: FirestoreRepository = FirestoreRepository()
) {
    companion object {
        private const val TAG = "KharchaRepository"
    }

    val allTransactions: Flow<List<TransactionEntity>> = dao.getAllTransactions()
    val allCategories: Flow<List<CategoryEntity>> = dao.getAllCategories()
    val allSubcategories: Flow<List<SubcategoryEntity>> = dao.getAllSubcategories()
    val allAccounts: Flow<List<AccountEntity>> = dao.getAllAccounts()
    val allSplits: Flow<List<TransactionSplitEntity>> = dao.getAllSplits()
    val allCards: Flow<List<CardEntity>> = dao.getAllCards()

    suspend fun getAllCardsSync(): List<CardEntity> = dao.getAllCardsSync()

    suspend fun insertCard(card: CardEntity) {
        dao.insertCard(card)
        try {
            if (firestoreRepository.currentUid != null) {
                firestoreRepository.saveCard(card)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore card sync skipped: ${e.message}")
        }
    }

    suspend fun deleteCard(id: String) {
        dao.deleteCard(id)
        try {
            if (firestoreRepository.currentUid != null) {
                firestoreRepository.deleteCard(id)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore card delete skipped: ${e.message}")
        }
    }

    suspend fun insertTransaction(tx: TransactionEntity): Long {
        val rowId = dao.insertTransaction(tx)
        try {
            if (firestoreRepository.currentUid != null) {
                firestoreRepository.saveTransaction(tx)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore transaction sync skipped: ${e.message}")
        }
        return rowId
    }

    suspend fun deleteTransaction(id: String) {
        dao.deleteTransaction(id)
        try {
            if (firestoreRepository.currentUid != null) {
                firestoreRepository.deleteTransaction(id)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore transaction delete skipped: ${e.message}")
        }
    }

    suspend fun insertSplitsForTransaction(txId: String, splits: List<TransactionSplitEntity>) {
        dao.deleteSplitsForTransaction(txId)
        if (splits.isNotEmpty()) {
            dao.insertSplits(splits)
        }
    }

    suspend fun deleteSplitsForTransaction(txId: String) = dao.deleteSplitsForTransaction(txId)

    suspend fun insertCategory(cat: CategoryEntity) {
        dao.insertCategory(cat)
        try {
            if (firestoreRepository.currentUid != null) {
                firestoreRepository.saveCategory(cat)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore category sync skipped: ${e.message}")
        }
    }

    suspend fun getTransactionCountForCategory(categoryId: String): Int {
        return dao.getTransactionCountForCategory(categoryId) + dao.getSplitCountForCategory(categoryId)
    }

    suspend fun mergeCategory(sourceCategoryId: String, targetCategoryId: String, updatedAt: String) {
        dao.reassignTransactionsCategory(sourceCategoryId, targetCategoryId, updatedAt)
        dao.reassignSplitsCategory(sourceCategoryId, targetCategoryId)
        dao.reassignSubcategoriesCategory(sourceCategoryId, targetCategoryId, updatedAt)
        dao.deleteCategory(sourceCategoryId)
        try {
            if (firestoreRepository.currentUid != null) {
                firestoreRepository.deleteCategory(sourceCategoryId)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore category merge delete skipped: ${e.message}")
        }
    }

    suspend fun deleteCategory(id: String, updatedAt: String) {
        dao.clearCategoryFromTransactions(id, updatedAt)
        dao.clearCategoryFromSplits(id)
        dao.deleteSubcategoriesForCategory(id)
        dao.deleteCategory(id)
        try {
            if (firestoreRepository.currentUid != null) {
                firestoreRepository.deleteCategory(id)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore category delete skipped: ${e.message}")
        }
    }

    suspend fun insertSubcategory(sub: SubcategoryEntity) {
        dao.insertSubcategory(sub)
        try {
            if (firestoreRepository.currentUid != null) {
                firestoreRepository.saveSubcategory(sub)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore subcategory sync skipped: ${e.message}")
        }
    }

    suspend fun deleteSubcategory(id: String, updatedAt: String) {
        dao.clearSubcategoryFromTransactions(id, updatedAt)
        dao.clearSubcategoryFromSplits(id)
        dao.deleteSubcategory(id)
        try {
            if (firestoreRepository.currentUid != null) {
                firestoreRepository.deleteSubcategory(id)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore subcategory delete skipped: ${e.message}")
        }
    }

    suspend fun moveSubcategory(subcategoryId: String, newCategoryId: String, updatedAt: String, moveTransactions: Boolean = true) {
        val allSubs = dao.getAllSubcategoriesSync()
        val sub = allSubs.find { it.id == subcategoryId }
        if (sub != null) {
            val updatedSub = sub.copy(categoryId = newCategoryId, updatedAt = updatedAt)
            dao.insertSubcategory(updatedSub)
            try {
                if (firestoreRepository.currentUid != null) {
                    firestoreRepository.saveSubcategory(updatedSub)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Firestore move subcategory sync skipped: ${e.message}")
            }
            if (moveTransactions) {
                dao.updateTransactionsCategoryForSubcategory(subcategoryId, newCategoryId, updatedAt)
                dao.updateSplitsCategoryForSubcategory(subcategoryId, newCategoryId)
            }
        }
    }

    suspend fun insertAccount(acc: AccountEntity) {
        dao.insertAccount(acc)
        try {
            if (firestoreRepository.currentUid != null) {
                firestoreRepository.saveAccount(acc)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore account sync skipped: ${e.message}")
        }
    }

    suspend fun deleteAccount(id: String) {
        dao.deleteAccount(id)
        try {
            if (firestoreRepository.currentUid != null) {
                firestoreRepository.deleteAccount(id)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore account delete skipped: ${e.message}")
        }
    }

    suspend fun getCategoryCount(): Int = dao.getCategoryCount()

    suspend fun cleanupDuplicateTransactions(): Int {
        return com.example.utils.TransactionIngestionEngine.cleanupDuplicateTransactions(dao)
    }
}
