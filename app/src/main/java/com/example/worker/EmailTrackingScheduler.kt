package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/**
 * Manages periodic scheduling of [EmailTrackingWorker] with WorkManager.
 *
 * Uses a unique periodic work name and replaces stale work when its owner changes.
 */
object EmailTrackingScheduler {

    private const val TAG = "EmailTrackingScheduler"
    const val WORK_NAME = "my_kharcha_email_tracking"
    const val INPUT_OWNER_UID = "email_tracking_owner_uid"
    const val PREFS_OWNER_UID = "gmail_owner_uid"
    private const val PREFS_NAME = "kharcha_prefs"

    @Volatile
    internal var liveAuthenticatedUidProvider: () -> String? = {
        try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (e: Exception) {
            Log.w(TAG, "Firebase authentication is unavailable", e)
            null
        }
    }

    /**
     * Schedules the periodic background worker if email tracking is enabled and Gmail is connected.
     */
    fun schedule(context: Context) {
        try {
            val ownerUid = liveAuthenticatedUidProvider()
                ?.takeIf { it.isNotBlank() && it != "legacy:unassigned" }
            if (ownerUid == null) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.w(TAG, "Email sync scheduling skipped because no live authenticated user is available")
                return
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getString(PREFS_OWNER_UID, null) != ownerUid ||
                !prefs.getBoolean("email_tracking_enabled", false) ||
                !prefs.getBoolean("gmail_connected", false)
            ) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.w(TAG, "Email sync scheduling skipped because Gmail settings are not bound to the live user")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // 15 minutes is the minimum interval supported by Android WorkManager
            val periodicWork = PeriodicWorkRequestBuilder<EmailTrackingWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setInputData(Data.Builder().putString(INPUT_OWNER_UID, ownerUid).build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                periodicWork
            )
            Log.d(TAG, "Scheduled unique periodic work '$WORK_NAME'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule EmailTrackingWorker: ${e.message}", e)
        }
    }

    /**
     * Cancels the periodic background worker.
     */
    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled unique periodic work '$WORK_NAME'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel EmailTrackingWorker: ${e.message}", e)
        }
    }

    /**
     * Reschedules or cancels the worker based on current user preferences.
     * Called on app startup, device reboot, and package updates.
     */
    fun rescheduleIfNeeded(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ownerUid = liveAuthenticatedUidProvider()
                ?.takeIf { it.isNotBlank() && it != "legacy:unassigned" }
            val isEnabled = prefs.getBoolean("email_tracking_enabled", false)
            val isConnected = prefs.getBoolean("gmail_connected", false)
            val account = prefs.getString("gmail_account", "") ?: ""
            val settingsOwnerUid = prefs.getString(PREFS_OWNER_UID, null)

            val shouldRun = ownerUid != null &&
                settingsOwnerUid == ownerUid &&
                isEnabled &&
                isConnected &&
                account.isNotBlank() &&
                !account.equals("Not connected", ignoreCase = true)

            if (shouldRun) {
                Log.d(TAG, "Email tracking is enabled and account is connected. Ensuring worker is scheduled.")
                schedule(context)
            } else {
                Log.d(TAG, "Email tracking is not active. Ensuring worker is cancelled.")
                cancel(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed in rescheduleIfNeeded: ${e.message}", e)
        }
    }
}
