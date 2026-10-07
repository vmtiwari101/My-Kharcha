package com.example.data.firestore

import android.content.Context
import android.util.Log
import com.example.data.database.AppDatabase
import com.example.data.entity.UserEntity
import com.example.utils.AuthManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Engine responsible for synchronizing local Room database records to Cloud Firestore
 * for the currently authenticated user.
 */
class RoomToFirestoreSyncEngine(
    private val firestoreRepository: FirestoreRepository = FirestoreRepository()
) {
    companion object {
        private const val TAG = "RoomToFirestoreSyncEngine"
    }

    /**
     * Performs a full synchronization of all local Room records to Cloud Firestore
     * under users/{uid}/...
     * Never crashes if Firestore or network is unavailable.
     */
    suspend fun syncAllLocalDataToFirestore(context: Context, customUid: String? = null): Boolean = withContext(Dispatchers.IO) {
        val firebaseUser = try {
            FirebaseAuth.getInstance().currentUser
        } catch (e: Exception) {
            Log.w(TAG, "Unable to verify authenticated user for sync: ${e.message}", e)
            null
        }
        val uid = firebaseUser?.uid
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext false
        if (customUid != null && customUid != uid) {
            Log.w(TAG, "Sync rejected because requested UID does not match the authenticated user")
            return@withContext false
        }
        val database = AppDatabase.getDatabase(context)
        val dao = database.kharchaDao()

        try {
            // 1. User Profile Sync
            val currentUserEntity = AuthManager.currentUser.value
                ?.takeIf { it.id == uid }
                ?: UserEntity(
                id = uid,
                name = "My Kharcha User",
                email = firebaseUser.email ?: "",
                phoneNumber = firebaseUser.phoneNumber ?: "",
                createdAt = "",
                lastLoginAt = ""
            )
            firestoreRepository.saveUserProfile(currentUserEntity, uid)

            // 2. Bank Accounts Sync
            val accounts = dao.getAllAccountsSyncForUser(uid)
                .filter { it.userId == uid }
            val accountIds = accounts.mapTo(mutableSetOf()) { it.id }

            firestoreRepository.saveAccountsBatch(accounts, uid)

            // 3. Credit Cards Sync
            val cards = dao.getAllCardsSyncForUser(uid)
                .filter { it.userId == uid && it.accountId in accountIds }
            val cardIds = cards.mapTo(mutableSetOf()) { it.id }
            firestoreRepository.saveCardsBatch(cards, uid)

            // 4. Categories Sync
            val categories = dao.getAllCategoriesSyncForUser(uid)
                .filter { it.userId == uid }
            val categoryIds = categories.mapTo(mutableSetOf()) { it.id }
            firestoreRepository.saveCategoriesBatch(categories, uid)

            // 5. Subcategories Sync
            val subcategories = dao.getAllSubcategoriesSyncForUser(uid)
                .filter { it.userId == uid && it.categoryId in categoryIds }
            val subcategoriesById = subcategories.associateBy { it.id }
            firestoreRepository.saveSubcategoriesBatch(subcategories, uid)

            // 6. Transactions Sync
            val transactions = dao.getAllTransactionsSyncForUser(uid)
                .filter { transaction ->
                    transaction.userId == uid &&
                        (transaction.accountId.isBlank() || transaction.accountId in accountIds) &&
                        (transaction.categoryId.isBlank() || transaction.categoryId in categoryIds) &&
                        (transaction.subcategoryId.isBlank() ||
                            subcategoriesById[transaction.subcategoryId]?.categoryId == transaction.categoryId) &&
                        (transaction.cardId.isNullOrBlank() || transaction.cardId in cardIds) &&
                        (transaction.counterpartyAccountId.isNullOrBlank() ||
                            transaction.counterpartyAccountId in accountIds ||
                            transaction.counterpartyAccountId in cardIds)
                }
            firestoreRepository.saveTransactionsBatch(transactions, uid)

            // 7. Transaction splits
            val transactionIds = transactions.mapTo(mutableSetOf()) { it.id }
            val splits = dao.getAllSplitsSyncForUser(uid)
                .filter { split ->
                    split.userId == uid &&
                        split.transactionId in transactionIds &&
                        (split.categoryId.isBlank() || split.categoryId in categoryIds) &&
                        (split.subcategoryId.isBlank() ||
                            subcategoriesById[split.subcategoryId]?.categoryId == split.categoryId)
                }
            firestoreRepository.saveTransactionSplitsBatch(splits, uid)

            // 8. Settings / Preferences Sync
            val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
            val settingsMap = mapOf(
                "sms_tracking_enabled" to prefs.getBoolean("sms_tracking_enabled", false),
                "notification_tracking_enabled" to prefs.getBoolean("notification_tracking_enabled", false),
                "email_tracking_enabled" to prefs.getBoolean("email_tracking_enabled", false),
                "monthly_budget" to prefs.getFloat("monthly_budget", 0f).toDouble(),
                "updated_at" to System.currentTimeMillis()
            )
            firestoreRepository.saveSetting("app_preferences", settingsMap, uid)

            Log.i(TAG, "Successfully synced ${transactions.size} transactions, ${accounts.size} accounts, ${categories.size} categories to Firestore for $uid")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Sync to Firestore failed safely: ${e.message}", e)
            false
        }
    }
}
