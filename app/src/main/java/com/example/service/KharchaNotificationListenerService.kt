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
import com.example.utils.CreditCardBillIngestionEngine
import com.google.firebase.auth.FirebaseAuth
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
        private const val LEGACY_OWNER = "legacy:unassigned"
        private const val PREFS_NAME = "kharcha_prefs"
        private const val TRACKING_ENABLED_KEY = "notification_tracking_enabled"
        var activeInstance: KharchaNotificationListenerService? = null
            private set

        @Volatile
        internal var liveAuthenticatedUidProvider: () -> String? = {
            try {
                FirebaseAuth.getInstance().currentUser?.uid
            } catch (e: Exception) {
                Log.w(TAG, "Firebase authentication is unavailable", e)
                null
            }
        }

        private fun liveOwner(): String? =
            liveAuthenticatedUidProvider()?.takeIf { it.isNotBlank() && it != LEGACY_OWNER }

        private fun isTrackingEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(TRACKING_ENABLED_KEY, false)

        suspend fun scanActiveNotifications(context: Context): Pair<Int, Int> = withContext(Dispatchers.IO) {
            val service = activeInstance
            if (service == null) {
                Log.d(TAG, "Notification listener service not active currently.")
                return@withContext Pair(0, 0)
            }
            if (!isTrackingEnabled(context) || liveOwner() == null) {
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

        if (!isTrackingEnabled(applicationContext)) return
        val owner = liveOwner() ?: return

        processSingleNotification(sbn, applicationContext, owner)
    }

    suspend fun processActiveNotifications(context: Context): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!isTrackingEnabled(context)) return@withContext Pair(0, 0)
        val owner = liveOwner() ?: return@withContext Pair(0, 0)
        try {
            if (liveOwner() != owner) return@withContext Pair(0, 0)
            val activeNotifs = getActiveNotifications() ?: emptyArray()
            processActiveNotificationBatch(context, activeNotifs, owner)
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving active notifications: ${e.message}", e)
            Pair(0, 0)
        }
    }

    internal suspend fun processActiveNotificationBatch(
        context: Context,
        activeNotifs: Array<StatusBarNotification>,
        expectedOwnerUid: String
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!isTrackingEnabled(context) || liveOwner() != expectedOwnerUid) {
            return@withContext Pair(0, 0)
        }
        var importedCount = 0
        var duplicatesCount = 0

        try {
            Log.d(TAG, "Scanning ${activeNotifs.size} active notifications in system tray")

            for (sbn in activeNotifs) {
                if (liveOwner() != expectedOwnerUid) {
                    return@withContext Pair(importedCount, duplicatesCount)
                }
                val (imported, duplicate) = processSingleNotificationSync(sbn, context, expectedOwnerUid)
                importedCount += imported
                duplicatesCount += duplicate
            }

            if (liveOwner() == expectedOwnerUid && isTrackingEnabled(context)) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val nowFmt = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(java.util.Date())
                val status = "Last scanned: $nowFmt ($importedCount imported)"
                prefs.edit()
                    .putString("last_notif_scan", status)
                    .putInt("notif_imported_count", prefs.getInt("notif_imported_count", 0) + importedCount)
                    .putInt("notif_duplicate_count", prefs.getInt("notif_duplicate_count", 0) + duplicatesCount)
                    .apply()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error scanning active notifications: ${e.message}", e)
        }

        return@withContext Pair(importedCount, duplicatesCount)
    }

    private fun processSingleNotification(sbn: StatusBarNotification, context: Context, expectedOwnerUid: String) {
        serviceScope.launch {
            processSingleNotificationSync(sbn, context, expectedOwnerUid)
        }
    }

    internal suspend fun processSingleNotificationSync(
        sbn: StatusBarNotification,
        context: Context,
        expectedOwnerUid: String
    ): Pair<Int, Int> {
        if (!isTrackingEnabled(context) || liveOwner() != expectedOwnerUid) {
            return Pair(0, 0)
        }
        val extras = sbn.notification.extras
        return processNotificationContent(
            packageName = sbn.packageName ?: "",
            title = extras.getCharSequence("android.title")?.toString() ?: "",
            text = extras.getCharSequence("android.text")?.toString() ?: "",
            bigText = extras.getCharSequence("android.bigText")?.toString() ?: "",
            subText = extras.getCharSequence("android.subText")?.toString() ?: "",
            textLines = extras.getCharSequenceArray("android.textLines")?.joinToString(" ") ?: "",
            postTime = sbn.postTime,
            context = context,
            expectedOwnerUid = expectedOwnerUid
        )
    }

    internal suspend fun processNotificationContent(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        subText: String,
        textLines: String,
        postTime: Long,
        context: Context,
        expectedOwnerUid: String
    ): Pair<Int, Int> {
        val owner = liveOwner()
        if (!isTrackingEnabled(context) || owner == null || owner != expectedOwnerUid) {
            return Pair(0, 0)
        }
        val startTime = System.currentTimeMillis()
        var imported = 0
        var duplicates = 0

        try {
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

            val parseStart = System.currentTimeMillis()

            if (CreditCardBillIngestionEngine.isCreditCardBillMessage(combinedContent)) {
                val billInfo = CreditCardBillIngestionEngine.extractBillInfo(combinedContent, packageName, "NOTIFICATION", "notif-$postTime", postTime)
                if (billInfo != null) {
                    if (liveOwner() == owner) {
                        CreditCardBillIngestionEngine.ingestBillInfo(context, billInfo, expectedOwnerUid = owner)
                    }
                }
                return Pair(0, 0)
            }

            val dao = AppDatabase.getDatabase(context).kharchaDao()
            if (liveOwner() != owner) return Pair(0, 0)
            val existing = dao.getAllTransactionsSyncForUser(owner).filter { it.userId == owner }
            if (liveOwner() != owner) return Pair(0, 0)

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
                if (liveOwner() != owner) return Pair(0, 0)
                val ingestStart = System.currentTimeMillis()
                Log.d("KharchaNotifService", "[STAGE 3: Ingestion Engine Start] Time: $ingestStart")
                val (ingested, status) = TransactionIngestionEngine.ingestTransaction(
                    context = context,
                    rawTx = result.transaction.copy(userId = owner),
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
