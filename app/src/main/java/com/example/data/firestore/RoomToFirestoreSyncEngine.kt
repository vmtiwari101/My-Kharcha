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
        val uid = customUid ?: firestoreRepository.currentUid ?: return@withContext false
        val database = AppDatabase.getDatabase(context)
        val dao = database.kharchaDao()

        try {
            // 1. User Profile Sync
            val currentUserEntity = AuthManager.currentUser.value ?: UserEntity(
                id = uid,
                name = "My Kharcha User",
                email = FirebaseAuth.getInstance().currentUser?.email ?: "",
                phoneNumber = FirebaseAuth.getInstance().currentUser?.phoneNumber ?: "",
                createdAt = "",
                lastLoginAt = ""
            )
            firestoreRepository.saveUserProfile(currentUserEntity, uid)

            // 2. Bank Accounts Sync
            val accounts = dao.getAllAccountsSync()
            firestoreRepository.saveAccountsBatch(accounts, uid)

            // 3. Credit Cards Sync
            val cards = dao.getAllCardsSync()
            firestoreRepository.saveCardsBatch(cards, uid)

            // 4. Categories Sync
            val categories = dao.getAllCategoriesSync()
            firestoreRepository.saveCategoriesBatch(categories, uid)

            // 5. Subcategories Sync
            val subcategories = dao.getAllSubcategoriesSync()
            firestoreRepository.saveSubcategoriesBatch(subcategories, uid)

            // 6. Transactions Sync
            val transactions = dao.getAllTransactionsSync()
            firestoreRepository.saveTransactionsBatch(transactions, uid)

            // 7. Settings / Preferences Sync
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
