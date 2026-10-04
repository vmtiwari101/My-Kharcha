package com.example.data.firestore

import android.util.Log
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.UserEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Data-layer abstraction for Cloud Firestore operations.
 * Operates strictly on user-scoped paths under `users/{uid}`.
 * Safe to instantiate even in JVM unit test environments without Firebase initialized.
 */
class FirestoreRepository(
    private val firestoreProvider: () -> FirebaseFirestore? = {
        try { FirebaseFirestore.getInstance() } catch (e: Throwable) { null }
    },
    private val authProvider: () -> FirebaseAuth? = {
        try { FirebaseAuth.getInstance() } catch (e: Throwable) { null }
    }
) {
    companion object {
        private const val TAG = "FirestoreRepository"
    }

    private val firestore: FirebaseFirestore? get() = firestoreProvider()
    private val auth: FirebaseAuth? get() = authProvider()

    val currentUid: String?
        get() = try { auth?.currentUser?.uid } catch (e: Throwable) { null }

    /**
     * Saves user profile to users/{uid}/profile/info
     */
    suspend fun saveUserProfile(user: UserEntity, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.profileDocumentPath(uid)
            fs.document(path).set(user.toFirestoreMap()).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save user profile: ${e.message}")
            false
        }
    }

    /**
     * Saves account to users/{uid}/accounts/{account.id}
     */
    suspend fun saveAccount(account: AccountEntity, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.accountDocumentPath(uid, account.id)
            fs.document(path).set(account.toFirestoreMap()).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save account ${account.id}: ${e.message}")
            false
        }
    }

    /**
     * Deletes account from users/{uid}/accounts/{accountId}
     */
    suspend fun deleteAccount(accountId: String, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.accountDocumentPath(uid, accountId)
            fs.document(path).delete().await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete account $accountId: ${e.message}")
            false
        }
    }

    /**
     * Saves card to users/{uid}/cards/{card.id}
     */
    suspend fun saveCard(card: CardEntity, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.cardDocumentPath(uid, card.id)
            fs.document(path).set(card.toFirestoreMap()).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save card ${card.id}: ${e.message}")
            false
        }
    }

    /**
     * Deletes card from users/{uid}/cards/{cardId}
     */
    suspend fun deleteCard(cardId: String, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.cardDocumentPath(uid, cardId)
            fs.document(path).delete().await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete card $cardId: ${e.message}")
            false
        }
    }

    /**
     * Saves transaction to users/{uid}/transactions/{tx.id}
     */
    suspend fun saveTransaction(tx: TransactionEntity, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.transactionDocumentPath(uid, tx.id)
            fs.document(path).set(tx.toFirestoreMap()).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save transaction ${tx.id}: ${e.message}")
            false
        }
    }

    /**
     * Deletes transaction from users/{uid}/transactions/{transactionId}
     */
    suspend fun deleteTransaction(transactionId: String, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.transactionDocumentPath(uid, transactionId)
            fs.document(path).delete().await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete transaction $transactionId: ${e.message}")
            false
        }
    }

    /**
     * Saves category to users/{uid}/categories/{category.id}
     */
    suspend fun saveCategory(category: CategoryEntity, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.categoryDocumentPath(uid, category.id)
            fs.document(path).set(category.toFirestoreMap()).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save category ${category.id}: ${e.message}")
            false
        }
    }

    /**
     * Deletes category from users/{uid}/categories/{categoryId}
     */
    suspend fun deleteCategory(categoryId: String, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.categoryDocumentPath(uid, categoryId)
            fs.document(path).delete().await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete category $categoryId: ${e.message}")
            false
        }
    }

    /**
     * Saves subcategory to users/{uid}/subcategories/{sub.id}
     */
    suspend fun saveSubcategory(subcategory: SubcategoryEntity, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.subcategoryDocumentPath(uid, subcategory.id)
            fs.document(path).set(subcategory.toFirestoreMap()).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save subcategory ${subcategory.id}: ${e.message}")
            false
        }
    }

    /**
     * Deletes subcategory from users/{uid}/subcategories/{subcategoryId}
     */
    suspend fun deleteSubcategory(subcategoryId: String, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.subcategoryDocumentPath(uid, subcategoryId)
            fs.document(path).delete().await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete subcategory $subcategoryId: ${e.message}")
            false
        }
    }

    /**
     * Saves merchant mapping to users/{uid}/merchants/{merchantId}
     */
    suspend fun saveMerchantMapping(merchantId: String, merchantName: String, categoryId: String, subcategoryId: String, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.merchantDocumentPath(uid, merchantId)
            val data = mapOf(
                "id" to merchantId,
                "merchantName" to sanitizeFirestoreText(merchantName),
                "categoryId" to categoryId,
                "subcategoryId" to subcategoryId
            )
            fs.document(path).set(data).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save merchant mapping $merchantId: ${e.message}")
            false
        }
    }

    /**
     * Saves preference settings to users/{uid}/settings/{settingId}
     */
    suspend fun saveSetting(settingId: String, settingsMap: Map<String, Any?>, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        return try {
            val path = FirestorePaths.settingsDocumentPath(uid, settingId)
            fs.document(path).set(settingsMap).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save setting $settingId: ${e.message}")
            false
        }
    }

    /**
     * Batch save transactions to users/{uid}/transactions/{tx.id}
     */
    suspend fun saveTransactionsBatch(transactions: List<TransactionEntity>, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        if (transactions.isEmpty()) return true
        return try {
            transactions.chunked(500).forEach { chunk ->
                val batch = fs.batch()
                chunk.forEach { tx ->
                    val ref = fs.document(FirestorePaths.transactionDocumentPath(uid, tx.id))
                    batch.set(ref, tx.toFirestoreMap())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Batch save transactions failed: ${e.message}")
            false
        }
    }

    /**
     * Batch save accounts to users/{uid}/accounts/{account.id}
     */
    suspend fun saveAccountsBatch(accounts: List<AccountEntity>, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        if (accounts.isEmpty()) return true
        return try {
            accounts.chunked(500).forEach { chunk ->
                val batch = fs.batch()
                chunk.forEach { acc ->
                    val ref = fs.document(FirestorePaths.accountDocumentPath(uid, acc.id))
                    batch.set(ref, acc.toFirestoreMap())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Batch save accounts failed: ${e.message}")
            false
        }
    }

    /**
     * Batch save cards to users/{uid}/cards/{card.id}
     */
    suspend fun saveCardsBatch(cards: List<CardEntity>, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        if (cards.isEmpty()) return true
        return try {
            cards.chunked(500).forEach { chunk ->
                val batch = fs.batch()
                chunk.forEach { card ->
                    val ref = fs.document(FirestorePaths.cardDocumentPath(uid, card.id))
                    batch.set(ref, card.toFirestoreMap())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Batch save cards failed: ${e.message}")
            false
        }
    }

    /**
     * Batch save categories to users/{uid}/categories/{category.id}
     */
    suspend fun saveCategoriesBatch(categories: List<CategoryEntity>, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        if (categories.isEmpty()) return true
        return try {
            categories.chunked(500).forEach { chunk ->
                val batch = fs.batch()
                chunk.forEach { cat ->
                    val ref = fs.document(FirestorePaths.categoryDocumentPath(uid, cat.id))
                    batch.set(ref, cat.toFirestoreMap())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Batch save categories failed: ${e.message}")
            false
        }
    }

    /**
     * Batch save subcategories to users/{uid}/subcategories/{sub.id}
     */
    suspend fun saveSubcategoriesBatch(subcategories: List<SubcategoryEntity>, customUid: String? = null): Boolean {
        val uid = customUid ?: currentUid ?: return false
        val fs = firestore ?: return false
        if (subcategories.isEmpty()) return true
        return try {
            subcategories.chunked(500).forEach { chunk ->
                val batch = fs.batch()
                chunk.forEach { sub ->
                    val ref = fs.document(FirestorePaths.subcategoryDocumentPath(uid, sub.id))
                    batch.set(ref, sub.toFirestoreMap())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Batch save subcategories failed: ${e.message}")
            false
        }
    }

    /**
     * Fetches user profile from users/{uid}/profile/info
     */
    suspend fun fetchUserProfile(customUid: String? = null): UserEntity? {
        val uid = customUid ?: currentUid ?: return null
        val fs = firestore ?: return null
        return try {
            val doc = fs.document(FirestorePaths.profileDocumentPath(uid)).get().await()
            if (doc.exists()) {
                val data = doc.data ?: return null
                data.toUserEntity(uid)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch user profile: ${e.message}")
            null
        }
    }

    /**
     * Fetches bank accounts from users/{uid}/accounts
     */
    suspend fun fetchAccounts(customUid: String? = null): List<AccountEntity> {
        val uid = customUid ?: currentUid ?: return emptyList()
        val fs = firestore ?: return emptyList()
        return try {
            val snapshot = fs.collection(FirestorePaths.accountsCollectionPath(uid)).get().await()
            snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                data.toAccountEntity(doc.id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch accounts: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches credit cards from users/{uid}/cards
     */
    suspend fun fetchCards(customUid: String? = null): List<CardEntity> {
        val uid = customUid ?: currentUid ?: return emptyList()
        val fs = firestore ?: return emptyList()
        return try {
            val snapshot = fs.collection(FirestorePaths.cardsCollectionPath(uid)).get().await()
            snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                data.toCardEntity(doc.id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch cards: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches categories from users/{uid}/categories
     */
    suspend fun fetchCategories(customUid: String? = null): List<CategoryEntity> {
        val uid = customUid ?: currentUid ?: return emptyList()
        val fs = firestore ?: return emptyList()
        return try {
            val snapshot = fs.collection(FirestorePaths.categoriesCollectionPath(uid)).get().await()
            snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                data.toCategoryEntity(doc.id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch categories: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches subcategories from users/{uid}/subcategories
     */
    suspend fun fetchSubcategories(customUid: String? = null): List<SubcategoryEntity> {
        val uid = customUid ?: currentUid ?: return emptyList()
        val fs = firestore ?: return emptyList()
        return try {
            val snapshot = fs.collection(FirestorePaths.subcategoriesCollectionPath(uid)).get().await()
            snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                data.toSubcategoryEntity(doc.id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch subcategories: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches merchants from users/{uid}/merchants
     */
    suspend fun fetchMerchants(customUid: String? = null): List<Map<String, Any?>> {
        val uid = customUid ?: currentUid ?: return emptyList()
        val fs = firestore ?: return emptyList()
        return try {
            val snapshot = fs.collection(FirestorePaths.merchantsCollectionPath(uid)).get().await()
            snapshot.documents.mapNotNull { doc -> doc.data }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch merchants: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches transactions from users/{uid}/transactions
     */
    suspend fun fetchTransactions(customUid: String? = null): List<TransactionEntity> {
        val uid = customUid ?: currentUid ?: return emptyList()
        val fs = firestore ?: return emptyList()
        return try {
            val snapshot = fs.collection(FirestorePaths.transactionsCollectionPath(uid)).get().await()
            snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                data.toTransactionEntity(doc.id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch transactions: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches settings map from users/{uid}/settings/{settingId}
     */
    suspend fun fetchSetting(settingId: String = "app_preferences", customUid: String? = null): Map<String, Any?>? {
        val uid = customUid ?: currentUid ?: return null
        val fs = firestore ?: return null
        return try {
            val doc = fs.document(FirestorePaths.settingsDocumentPath(uid, settingId)).get().await()
            if (doc.exists()) doc.data else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch setting $settingId: ${e.message}")
            null
        }
    }
}
