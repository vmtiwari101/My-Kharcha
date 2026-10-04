package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.data.database.AppDatabase
import com.example.utils.NotificationParser
import com.example.utils.NotificationStatus
import com.example.utils.TransactionIngestionEngine
import com.example.utils.IngestionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class KharchaNotificationListenerService : NotificationListenerService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        private const val TAG = "KharchaNotifService"
        var activeInstance: KharchaNotificationListenerService? = null
            private set

        suspend fun scanActiveNotifications(context: Context): Pair<Int, Int> = withContext(Dispatchers.IO) {
            val service = activeInstance
            if (service == null) {
                Log.d(TAG, "Notification listener service not active currently.")
                return@withContext Pair(0, 0)
            }
            return@withContext service.processActiveNotifications(context)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        Log.d(TAG, "Notification Listener connected.")
        // Perform initial scan of active notifications
        serviceScope.launch {
            processActiveNotifications(applicationContext)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (activeInstance == this) {
            activeInstance = null
        }
        Log.d(TAG, "Notification Listener disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = applicationContext.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
        val trackingEnabled = prefs.getBoolean("notification_tracking_enabled", false)
        if (!trackingEnabled) return

        processSingleNotification(sbn, applicationContext)
    }

    suspend fun processActiveNotifications(context: Context): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var importedCount = 0
        var duplicatesCount = 0

        try {
            val activeNotifs = getActiveNotifications() ?: emptyArray()
            Log.d(TAG, "Scanning ${activeNotifs.size} active notifications in system tray")

            for (sbn in activeNotifs) {
                val (imported, duplicate) = processSingleNotificationSync(sbn, context)
                importedCount += imported
                duplicatesCount += duplicate
            }

            val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
            val nowFmt = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(java.util.Date())
            val status = "Last scanned: $nowFmt ($importedCount imported)"
            prefs.edit()
                .putString("last_notif_scan", status)
                .putInt("notif_imported_count", prefs.getInt("notif_imported_count", 0) + importedCount)
                .putInt("notif_duplicate_count", prefs.getInt("notif_duplicate_count", 0) + duplicatesCount)
                .apply()

        } catch (e: Exception) {
            Log.e(TAG, "Error scanning active notifications: ${e.message}", e)
        }

        return@withContext Pair(importedCount, duplicatesCount)
    }

    private fun processSingleNotification(sbn: StatusBarNotification, context: Context) {
        serviceScope.launch {
            processSingleNotificationSync(sbn, context)
        }
    }

    private suspend fun processSingleNotificationSync(sbn: StatusBarNotification, context: Context): Pair<Int, Int> {
        val startTime = System.currentTimeMillis()
        var imported = 0
        var duplicates = 0

        try {
            val packageName = sbn.packageName ?: ""
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
            val subText = extras.getCharSequence("android.subText")?.toString() ?: ""
            val textLines = extras.getCharSequenceArray("android.textLines")?.joinToString(" ") ?: ""

            val combinedContent = "$title $text $bigText $subText $textLines".lowercase(Locale.ENGLISH)
            Log.d("KharchaNotifService", "[STAGE 1: Notification Received] Package: $packageName, Time: ${System.currentTimeMillis()}, Content length: ${combinedContent.length}")

            val keywords = listOf(
                "debited", "debit", "spent", "paid", "payment", "purchase", "withdrawn", "deducted", "sent", "transferred", "upi payment",
                "credited", "credit", "received", "deposited", "refund", "cashback", "salary", "deposit"
            )
            val hasKeyword = keywords.any { combinedContent.contains(it) }
            val hasAmount = combinedContent.contains("rs") || 
                             combinedContent.contains("inr") || 
                             combinedContent.contains("₹") || 
                             Regex("\\d+\\.\\d{2}").containsMatchIn(combinedContent)

            if (!hasKeyword || !hasAmount) {
                Log.d("KharchaNotifService", "Notification skipped: does not match transaction keywords or amount format.")
                return Pair(0, 0)
            }

            val postTime = sbn.postTime
            val parseStart = System.currentTimeMillis()

            if (com.example.utils.CreditCardBillIngestionEngine.isCreditCardBillMessage(combinedContent)) {
                val billInfo = com.example.utils.CreditCardBillIngestionEngine.extractBillInfo(combinedContent, packageName, "NOTIFICATION", "notif-$postTime", postTime)
                if (billInfo != null) {
                    com.example.utils.CreditCardBillIngestionEngine.ingestBillInfo(context, billInfo)
                }
                return Pair(0, 0)
            }

            val dao = AppDatabase.getDatabase(context).kharchaDao()
            val existing = dao.getAllTransactionsSync()

            Log.d("KharchaNotifService", "[STAGE 2: Parsing Start] Time: $parseStart, Existing transactions size: ${existing.size}")
            val result = NotificationParser.parseNotification(
                packageName = packageName,
                title = title,
                text = text,
                bigText = bigText,
                subText = subText,
                postTime = postTime,
                existingTransactions = existing
            )
            val parseEnd = System.currentTimeMillis()
            Log.d("KharchaNotifService", "[STAGE 2: Parsing End] Result status: ${result.status}, Time taken: ${parseEnd - parseStart} ms")

            if (result.status == NotificationStatus.IMPORTED && result.transaction != null) {
                val ingestStart = System.currentTimeMillis()
                Log.d("KharchaNotifService", "[STAGE 3: Ingestion Engine Start] Time: $ingestStart")
                val (ingested, status) = TransactionIngestionEngine.ingestTransaction(
                    context = context,
                    rawTx = result.transaction,
                    rawText = combinedContent
                )
                val ingestEnd = System.currentTimeMillis()
                Log.d("KharchaNotifService", "[STAGE 4: Room Insert/Update Complete] Status: $status, Time taken: ${ingestEnd - ingestStart} ms, Total pipeline time: ${ingestEnd - startTime} ms")
                when (status) {
                    IngestionStatus.IMPORTED -> imported++
                    IngestionStatus.ENRICHED -> imported++
                    IngestionStatus.DUPLICATE -> duplicates++
                    else -> {}
                }
            } else if (result.status == NotificationStatus.DUPLICATE) {
                duplicates++
                Log.d("KharchaNotifService", "Notification processed as duplicate in parsing stage.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification: ${e.message}", e)
        }

        return Pair(imported, duplicates)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance == this) {
            activeInstance = null
        }
        serviceJob.cancel()
    }
}
