package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import com.example.utils.SmsParser
import com.example.utils.TransactionIngestionEngine
import com.example.utils.IngestionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            if (!isSmsTrackingEnabled(context)) {
                Log.d("SmsReceiver", "Ignoring incoming SMS because SMS tracking is disabled")
                return
            }
            val owner = SmsParser.liveAuthenticatedUid() ?: run {
                Log.w("SmsReceiver", "Ignoring incoming SMS because no live Firebase user is available")
                return
            }

            val bundle = intent.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as? Array<*>
                val format = bundle.getString("format")
                if (pdus != null) {
                    for (pdu in pdus) {
                        val sms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            SmsMessage.createFromPdu(pdu as ByteArray, format)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsMessage.createFromPdu(pdu as ByteArray)
                        }
                        val body = sms.messageBody ?: ""
                        val sender = sms.originatingAddress ?: ""
                        val timestamp = sms.timestampMillis

                        Log.d("SmsReceiver", "Received SMS from $sender: $body")

                        if (SmsParser.liveAuthenticatedUid() == owner) {
                            processIncomingSms(context, sender, body, timestamp, owner)
                        }
                    }
                }
            }
        }
    }

    private fun processIncomingSms(
        context: Context,
        sender: String,
        body: String,
        timestamp: Long,
        expectedOwnerUid: String
    ) {
        if (!isSmsTrackingEnabled(context)) {
            Log.d("SmsReceiver", "Ignoring incoming SMS because SMS tracking is disabled")
            return
        }
        if (SmsParser.liveAuthenticatedUid() != expectedOwnerUid) {
            Log.w("SmsReceiver", "Ignoring incoming SMS because the authenticated user changed")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                processIncomingSmsNow(context, sender, body, timestamp, expectedOwnerUid)
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Error processing incoming SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal suspend fun processIncomingSmsNow(
        context: Context,
        sender: String,
        body: String,
        timestamp: Long,
        expectedOwnerUid: String
    ): Boolean {
        if (!isSmsTrackingEnabled(context) || SmsParser.liveAuthenticatedUid() != expectedOwnerUid) {
            return false
        }

        val smsId = "rec-${System.currentTimeMillis()}"
        if (com.example.utils.CreditCardBillIngestionEngine.isCreditCardBillMessage("$sender $body")) {
            val billInfo = com.example.utils.CreditCardBillIngestionEngine.extractBillInfo(body, sender, "SMS", smsId, timestamp)
            if (billInfo != null) {
                if (SmsParser.liveAuthenticatedUid() != expectedOwnerUid) return false
                val billResult = com.example.utils.CreditCardBillIngestionEngine.ingestBillInfo(
                    context,
                    billInfo,
                    expectedOwnerUid = expectedOwnerUid
                )
                if (SmsParser.liveAuthenticatedUid() != expectedOwnerUid) return false
                Log.d("SmsReceiver", "Ingested credit card bill message: $billResult")
            }
            updateLastSyncStatus(context, "Imported: 0, Updated: 1, Duplicates: 0, Ignored: 0, Needs Review: 0")
            return true
        }

        if (SmsParser.liveAuthenticatedUid() != expectedOwnerUid) return false
        val parseStatus = SmsParser.parseSms(smsId, sender, body, timestamp)
        if (SmsParser.liveAuthenticatedUid() != expectedOwnerUid) return false
        if (parseStatus is SmsParser.SmsParseStatus.Success) {
            val tx = parseStatus.transaction.copy(userId = expectedOwnerUid)
            val rawText = "${tx.merchant} ${tx.note} ${tx.last4Digits} $body"

            Log.d("SmsReceiver", "SMS parsed successfully. Ingesting transaction...")
            val (_, status) = TransactionIngestionEngine.ingestTransaction(
                context,
                tx,
                rawText,
                expectedOwnerUid = expectedOwnerUid
            )
            if (SmsParser.liveAuthenticatedUid() != expectedOwnerUid) return false

            var imported = 0
            var updated = 0
            var duplicates = 0
            var ignored = 0
            var needsReview = 0

            when (status) {
                IngestionStatus.IMPORTED -> imported++
                IngestionStatus.ENRICHED -> updated++
                IngestionStatus.DUPLICATE -> duplicates++
                IngestionStatus.NEEDS_REVIEW -> needsReview++
                IngestionStatus.IGNORED, IngestionStatus.FAILED -> ignored++
            }

            updateLastSyncStatus(
                context,
                "Imported: $imported, Updated: $updated, Duplicates: $duplicates, Ignored: $ignored, Needs Review: $needsReview"
            )
        } else {
            updateLastSyncStatus(
                context,
                "Imported: 0, Updated: 0, Duplicates: 0, Ignored: 1, Needs Review: 0"
            )
        }
        return true
    }

    private fun updateLastSyncStatus(context: Context, result: String) {
        val nowStr = SimpleDateFormat("dd MMM, h:mm a", Locale.US).format(Date())
        context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE).edit()
            .putString("last_auto_sync_time", nowStr)
            .putString("last_auto_sync_result", result)
            .apply()
    }

    private fun isSmsTrackingEnabled(context: Context): Boolean {
        return context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
            .getBoolean("sms_tracking_enabled", false)
    }
}
