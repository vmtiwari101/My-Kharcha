package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android WorkManager CoroutineWorker for automatic background Gmail synchronization.
 *
 * Runs periodically to fetch recent transaction emails, parse them with EmailParser,
 * and ingest them through the central TransactionIngestionEngine.
 */
class EmailTrackingWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "EmailTrackingWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "EmailTrackingWorker triggered by WorkManager.")
        try {
            val result = EmailSyncEngine.syncEmails(applicationContext, isBackground = true)
            when (result) {
                is SyncResult.Success -> {
                    Log.d(TAG, "Email tracking sync completed successfully.")
                    Result.success()
                }
                is SyncResult.Skipped -> {
                    Log.d(TAG, "Email tracking sync skipped: ${result.reason}")
                    Result.success()
                }
                is SyncResult.AuthRequired -> {
                    Log.w(TAG, "Email tracking authorization required. User must re-authenticate in UI.")
                    // User intervention needed in UI. Do NOT retry blindly or launch Activity.
                    Result.success()
                }
                is SyncResult.NetworkError -> {
                    Log.w(TAG, "Email tracking network error: ${result.message}. Requesting retry.")
                    Result.retry()
                }
                is SyncResult.Error -> {
                    Log.e(TAG, "Email tracking unexpected error: ${result.throwable.message}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "EmailTrackingWorker exception: ${e.message}", e)
            Result.retry()
        }
    }
}
