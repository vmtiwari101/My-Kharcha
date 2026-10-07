package com.example.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.database.AppDatabase
import com.example.utils.CreditCardReminderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class CreditCardReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CCReminderReceiver"
        const val ACTION_CREDIT_CARD_DUE_REMINDER = "com.example.ACTION_CREDIT_CARD_DUE_REMINDER"

        const val EXTRA_CARD_KEY = "extra_card_key"
        const val EXTRA_BANK_NAME = "extra_bank_name"
        const val EXTRA_LAST4 = "extra_last4"
        const val EXTRA_DUE_DATE = "extra_due_date"
        const val EXTRA_OFFSET_DAYS = "extra_offset_days"
        const val EXTRA_ACCOUNT_ID = "extra_account_id"
        const val EXTRA_OWNER_UID = "extra_owner_uid"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val liveUid = CreditCardReminderManager.liveAuthenticatedUid() ?: run {
            Log.w(TAG, "Reminder broadcast ignored because no live authenticated user is available")
            return
        }
        Log.d(TAG, "Received broadcast action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "Device rebooted or package replaced: rescheduling credit card reminders and email tracking")
                CreditCardReminderManager.rescheduleAllAsync(context.applicationContext)
                com.example.worker.EmailTrackingScheduler.rescheduleIfNeeded(context.applicationContext)
            }
            ACTION_CREDIT_CARD_DUE_REMINDER -> {
                val cardKey = intent.getStringExtra(EXTRA_CARD_KEY) ?: ""
                val bankName = intent.getStringExtra(EXTRA_BANK_NAME) ?: "Credit Card"
                val last4 = intent.getStringExtra(EXTRA_LAST4) ?: ""
                val dueDate = intent.getIntExtra(EXTRA_DUE_DATE, 0)
                val offsetDays = intent.getIntExtra(EXTRA_OFFSET_DAYS, 0)
                val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: ""
                val scheduledOwnerUid = intent.getStringExtra(EXTRA_OWNER_UID)
                if (!CreditCardReminderManager.isScheduledOwnerValid(liveUid, scheduledOwnerUid)) {
                    Log.w(TAG, "Reminder alarm ignored because its owner does not match the live user")
                    return
                }

                handleReminderTrigger(
                    context = context,
                    cardKey = cardKey,
                    bankName = bankName,
                    last4 = last4,
                    dueDate = dueDate,
                    offsetDays = offsetDays,
                    accountId = accountId,
                    scheduledOwnerUid = scheduledOwnerUid
                )
            }
        }
    }

    private fun handleReminderTrigger(
        context: Context,
        cardKey: String,
        bankName: String,
        last4: String,
        dueDate: Int,
        offsetDays: Int,
        accountId: String,
        scheduledOwnerUid: String?
    ) {
        val ownerUid = CreditCardReminderManager.liveAuthenticatedUid() ?: return
        if (!CreditCardReminderManager.isScheduledOwnerValid(ownerUid, scheduledOwnerUid)) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!CreditCardReminderManager.isScheduledOwnerValid(
                        CreditCardReminderManager.liveAuthenticatedUid(),
                        ownerUid
                    )
                ) return@launch

                // Verify user hasn't disabled reminders for this card
                if (!CreditCardReminderManager.isReminderEnabledForOwner(appContext, ownerUid, cardKey)) {
                    Log.d(TAG, "Reminders disabled for $bankName ($last4), skipping notification")
                    return@launch
                }
                if (!CreditCardReminderManager.isScheduledOwnerValid(
                        CreditCardReminderManager.liveAuthenticatedUid(),
                        ownerUid
                    )
                ) return@launch

                val db = AppDatabase.getDatabase(appContext)
                val dao = db.kharchaDao()

                val accounts = dao.getAllAccountsSyncForUser(ownerUid).filter { it.userId == ownerUid }
                val cards = dao.getAllCardsSyncForUser(ownerUid).filter { it.userId == ownerUid }
                val transactions = dao.getAllTransactionsSyncForUser(ownerUid).filter { it.userId == ownerUid }

                val matchedAcc = accounts.find { it.id == accountId }
                    ?: accounts.find {
                        it.last4Digits == last4 && it.type.equals("Credit Card", ignoreCase = true)
                    }
                val matchedCard = cards.find {
                    it.last4Digits == last4 && (matchedAcc == null || it.accountId == matchedAcc.id)
                }
                if (matchedAcc == null && matchedCard == null) {
                    Log.w(TAG, "Reminder alarm ignored because no owner-matched account/card exists")
                    return@launch
                }

                val ownerBankName = matchedAcc?.bankName?.ifEmpty { matchedAcc.name } ?: matchedCard?.name.orEmpty()
                val ownerLast4 = matchedCard?.last4Digits?.ifEmpty { matchedAcc?.last4Digits.orEmpty() }
                    ?: matchedAcc?.last4Digits.orEmpty()
                if (CreditCardReminderManager.getCardKey(bankName, last4) != cardKey ||
                    ownerLast4 != last4 ||
                    !com.example.utils.TransactionIngestionEngine.isBankNameMatch(ownerBankName, bankName)
                ) {
                    Log.w(TAG, "Reminder alarm ignored because its card identity does not match owner data")
                    return@launch
                }

                // Calculate current live outstanding balance directly from centralized transactions
                val linkedTxs = transactions.filter { tx ->
                    if (matchedCard != null && tx.cardId == matchedCard.id) return@filter true
                    if (matchedAcc != null && tx.accountId == matchedAcc.id) return@filter true
                    if (last4.length == 4 && tx.last4Digits == last4) return@filter true
                    false
                }

                val expenseTotal = linkedTxs.filter { (it.direction == "DEBIT" || it.type == "EXPENSE") && !it.isInternalTransfer && it.transactionType != "CARD_PAYMENT" }.sumOf { it.amount }
                val paymentTotal = linkedTxs.filter {
                    com.example.utils.TransactionIdentityResolver.isCreditCardPaymentOrRefund(
                        it,
                        cardId = matchedCard?.id,
                        accountId = matchedAcc?.id ?: matchedCard?.accountId.orEmpty(),
                        last4 = last4
                    )
                }.sumOf { it.amount }
                val txOutstanding = (expenseTotal - paymentTotal).coerceAtLeast(0.0)
                val storedOutstanding = maxOf(matchedAcc?.outstandingAmount ?: 0.0, matchedCard?.outstandingAmount ?: 0.0)
                val effectiveOutstanding = if (txOutstanding > 0.0) txOutstanding else storedOutstanding

                // Zero-outstanding protection: Do NOT notify if balance is paid off or zero
                if (effectiveOutstanding <= 0.0) {
                    Log.d(TAG, "Zero outstanding balance for $bankName ($last4), skipping notification")
                    return@launch
                }

                val formatINR: (Double) -> String = { amt ->
                    "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(amt)
                }

                val offsetText = when (offsetDays) {
                    0 -> "today"
                    1 -> "tomorrow"
                    else -> "in $offsetDays days"
                }

                val title = if (offsetDays == 0) {
                    "Payment Due Today: $ownerBankName •••• $ownerLast4"
                } else {
                    "Upcoming Due: $ownerBankName •••• $ownerLast4"
                }

                val content = "Outstanding amount of ${formatINR(effectiveOutstanding)} is due $offsetText (${dueDate}th of this month)."

                if (!CreditCardReminderManager.isScheduledOwnerValid(
                        CreditCardReminderManager.liveAuthenticatedUid(),
                        ownerUid
                    )
                ) return@launch
                showNotification(appContext, "$ownerUid:$cardKey", dueDate, offsetDays, title, content)

                // Reschedule for next month's cycle
                CreditCardReminderManager.scheduleRemindersForOwner(
                    context = appContext,
                    owner = ownerUid,
                    bankName = ownerBankName,
                    last4 = ownerLast4,
                    dueDate = dueDate,
                    accountId = accountId,
                    outstandingAmount = effectiveOutstanding
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing reminder trigger: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(
        context: Context,
        cardKey: String,
        dueDate: Int,
        offsetDays: Int,
        title: String,
        content: String
    ) {
        CreditCardReminderManager.createNotificationChannel(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            CreditCardReminderManager.getRequestCode(cardKey, dueDate, offsetDays),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CreditCardReminderManager.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notificationId = CreditCardReminderManager.getRequestCode(cardKey, dueDate, offsetDays)
        notificationManager?.notify(notificationId, notification)
        Log.d(TAG, "Posted notification [ID=$notificationId]: $title - $content")
    }
}
