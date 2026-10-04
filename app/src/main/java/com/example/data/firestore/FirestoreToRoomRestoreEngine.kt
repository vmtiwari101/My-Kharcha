package com.example.data.firestore

import android.content.Context
import android.util.Log
import com.example.data.database.AppDatabase
import com.example.utils.AuthManager
import com.example.utils.MerchantLearningEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RestoreResult(
    val success: Boolean,
    val restoredTransactionsCount: Int = 0,
    val restoredAccountsCount: Int = 0,
    val restoredCategoriesCount: Int = 0,
    val message: String = ""
)

class FirestoreToRoomRestoreEngine(
    private val firestoreRepository: FirestoreRepository = FirestoreRepository()
) {
    companion object {
        private const val TAG = "FirestoreToRoomRestoreEngine"
    }

    /**
     * Restores Cloud Firestore data into local Room database in dependency order:
     * 1. Profile & Settings
     * 2. Accounts & Cards
     * 3. Categories & Subcategories
     * 4. Merchants
     * 5. Transactions
     *
     * Never wipes existing local Room data if Firestore is empty or unavailable.
     * Uses deterministic IDs to update/merge without creating duplicates.
     */
    suspend fun restoreCloudDataToRoom(
        context: Context,
        customUid: String? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        val uid = customUid ?: firestoreRepository.currentUid
        if (uid.isNullOrBlank()) {
            return@withContext RestoreResult(
                success = false,
                message = "User is not authenticated. Cannot perform restore."
            )
        }

        val database = AppDatabase.getDatabase(context)
        val dao = database.kharchaDao()

        try {
            var restoredTxs = 0
            var restoredAccs = 0
            var restoredCats = 0

            // 1. Profile & Settings
            val profile = firestoreRepository.fetchUserProfile(uid)
            if (profile != null) {
                AuthManager.setAuthenticatedUser(
                    context = context,
                    userId = profile.id,
                    email = profile.email,
                    name = profile.name,
                    phone = profile.phoneNumber
                )
            }

            val settings = firestoreRepository.fetchSetting("app_preferences", uid)
            if (settings != null) {
                val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                (settings["sms_tracking_enabled"] as? Boolean)?.let { editor.putBoolean("sms_tracking_enabled", it) }
                (settings["notification_tracking_enabled"] as? Boolean)?.let { editor.putBoolean("notification_tracking_enabled", it) }
                (settings["email_tracking_enabled"] as? Boolean)?.let { editor.putBoolean("email_tracking_enabled", it) }
                ((settings["monthly_budget"] as? Number)?.toFloat())?.let { editor.putFloat("monthly_budget", it) }
                editor.apply()
            }

            // 2. Accounts & Cards
            val accounts = firestoreRepository.fetchAccounts(uid)
            if (accounts.isNotEmpty()) {
                accounts.forEach { acc ->
                    dao.insertAccount(acc)
                    restoredAccs++
                }
            }

            val cards = firestoreRepository.fetchCards(uid)
            if (cards.isNotEmpty()) {
                cards.forEach { card ->
                    dao.insertCard(card)
                }
            }

            // 3. Categories & Subcategories
            val categories = firestoreRepository.fetchCategories(uid)
            if (categories.isNotEmpty()) {
                categories.forEach { cat ->
                    dao.insertCategory(cat)
                    restoredCats++
                }
            }

            val subcategories = firestoreRepository.fetchSubcategories(uid)
            if (subcategories.isNotEmpty()) {
                subcategories.forEach { sub ->
                    dao.insertSubcategory(sub)
                }
            }

            // 4. Merchants
            val merchants = firestoreRepository.fetchMerchants(uid)
            if (merchants.isNotEmpty()) {
                merchants.forEach { m ->
                    val merchantName = m["merchantName"] as? String
                    val categoryId = m["categoryId"] as? String ?: ""
                    val subcategoryId = m["subcategoryId"] as? String ?: ""
                    if (!merchantName.isNullOrBlank()) {
                        MerchantLearningEngine.saveMapping(context, merchantName, categoryId, subcategoryId)
                    }
                }
            }

            // 5. Transactions
            val transactions = firestoreRepository.fetchTransactions(uid)
            if (transactions.isNotEmpty()) {
                transactions.forEach { tx ->
                    dao.insertTransaction(tx)
                    restoredTxs++
                }
            }

            val totalRestored = restoredTxs + restoredAccs + restoredCats
            Log.i(TAG, "Restore completed for $uid: $restoredTxs transactions, $restoredAccs accounts, $restoredCats categories")

            RestoreResult(
                success = true,
                restoredTransactionsCount = restoredTxs,
                restoredAccountsCount = restoredAccs,
                restoredCategoriesCount = restoredCats,
                message = if (totalRestored > 0) "Successfully restored $totalRestored items from Cloud Firestore." else "No cloud data found to restore."
            )
        } catch (e: Exception) {
            Log.w(TAG, "Restore from Firestore failed: ${e.message}", e)
            RestoreResult(
                success = false,
                message = "Restore failed: ${e.localizedMessage ?: e.message}"
            )
        }
    }
}
