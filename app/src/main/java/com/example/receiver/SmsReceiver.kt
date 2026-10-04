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

                        processIncomingSms(context, sender, body, timestamp)
                    }
                }
            }
        }
    }

    private fun processIncomingSms(context: Context, sender: String, body: String, timestamp: Long) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val smsId = "rec-${System.currentTimeMillis()}"

                if (com.example.utils.CreditCardBillIngestionEngine.isCreditCardBillMessage("$sender $body")) {
                    val billInfo = com.example.utils.CreditCardBillIngestionEngine.extractBillInfo(body, sender, "SMS", smsId, timestamp)
                    if (billInfo != null) {
                        val billResult = com.example.utils.CreditCardBillIngestionEngine.ingestBillInfo(context, billInfo)
                        Log.d("SmsReceiver", "Ingested credit card bill message: $billResult")
                    }
                    val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
                    val nowStr = SimpleDateFormat("dd MMM, h:mm a", Locale.US).format(Date())
                    prefs.edit()
                        .putString("last_auto_sync_time", nowStr)
                        .putString("last_auto_sync_result", "Imported: 0, Updated: 1, Duplicates: 0, Ignored: 0, Needs Review: 0")
                        .apply()
                    return@launch
                }

                val parseStatus = SmsParser.parseSms(smsId, sender, body, timestamp)
                if (parseStatus is SmsParser.SmsParseStatus.Success) {
                    val tx = parseStatus.transaction
                    val rawText = "${tx.merchant} ${tx.note} ${tx.last4Digits} $body"

                    Log.d("SmsReceiver", "SMS parsed successfully. Ingesting transaction...")
                    val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, tx, rawText)

                    val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
                    val nowStr = SimpleDateFormat("dd MMM, h:mm a", Locale.US).format(Date())

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

                    val resultStr = "Imported: $imported, Updated: $updated, Duplicates: $duplicates, Ignored: $ignored, Needs Review: $needsReview"
                    prefs.edit()
                        .putString("last_auto_sync_time", nowStr)
                        .putString("last_auto_sync_result", resultStr)
                        .apply()
                } else {
                    val prefs = context.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)
                    val nowStr = SimpleDateFormat("dd MMM, h:mm a", Locale.US).format(Date())
                    prefs.edit()
                        .putString("last_auto_sync_time", nowStr)
                        .putString("last_auto_sync_result", "Imported: 0, Updated: 0, Duplicates: 0, Ignored: 1, Needs Review: 0")
                        .apply()
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Error processing incoming SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
