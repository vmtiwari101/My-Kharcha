package com.example.utils

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.receiver.CreditCardReminderReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * Foundation for Credit Card Due Date Reminders (Phase 6D-A).
 * Supports 7-day, 3-day, 1-day, and due-date offsets.
 * Prevents duplicates, avoids fake transactions, and validates live outstanding balances.
 */
object CreditCardReminderManager {

    private const val TAG = "CCReminderManager"
    const val PREFS_NAME = "credit_card_reminders_pref"
    const val CHANNEL_ID = "credit_card_due_reminders"
    const val CHANNEL_NAME = "Credit Card Bill Reminders"

    // Supported reminder offsets (in days before due date)
    val DEFAULT_OFFSETS = setOf(7, 3, 1, 0)

    fun isReminderEnabledForCard(context: Context, cardKey: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("enabled_$cardKey", true)
    }

    fun setReminderEnabledForCard(context: Context, cardKey: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled_$cardKey", enabled).apply()
    }

    fun getEnabledOffsetsForCard(context: Context, cardKey: String): Set<Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val strSet = prefs.getStringSet("offsets_$cardKey", null)
        return if (strSet != null) {
            strSet.mapNotNull { it.toIntOrNull() }.toSet()
        } else {
            DEFAULT_OFFSETS
        }
    }

    fun setEnabledOffsetsForCard(context: Context, cardKey: String, offsets: Set<Int>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val strSet = offsets.map { it.toString() }.toSet()
        prefs.edit().putStringSet("offsets_$cardKey", strSet).apply()
    }

    fun getCardKey(bankName: String, last4: String): String {
        val cleanBank = bankName.trim().lowercase(Locale.ENGLISH).replace("[^a-z0-9]".toRegex(), "")
        val cleanLast4 = last4.trim()
        return "${cleanBank}_$cleanLast4"
    }

    fun getRequestCode(cardKey: String, dueDate: Int, offsetDays: Int): Int {
        return abs(cardKey.hashCode() xor (dueDate * 31) xor (offsetDays * 17))
    }

    /**
     * Calculates the exact trigger time (in epoch milliseconds) for the reminder.
     * Fires at 09:00 AM on (dueDate - offsetDays).
     */
    fun calculateNextReminderTime(dueDate: Int, offsetDays: Int): Long? {
        if (dueDate !in 1..31) return null

        val now = Calendar.getInstance()
        val trigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Determine maximum day for the current month
        val maxDayThisMonth = trigger.getActualMaximum(Calendar.DAY_OF_MONTH)
        val clampedDueThisMonth = dueDate.coerceAtMost(maxDayThisMonth)

        trigger.set(Calendar.DAY_OF_MONTH, clampedDueThisMonth)
        trigger.add(Calendar.DAY_OF_MONTH, -offsetDays)

        // If the reminder date has already passed for this month's cycle, move to next month
        if (trigger.before(now)) {
            trigger.add(Calendar.MONTH, 1)
            val maxDayNextMonth = trigger.getActualMaximum(Calendar.DAY_OF_MONTH)
            val clampedDueNextMonth = dueDate.coerceAtMost(maxDayNextMonth)
            trigger.set(Calendar.DAY_OF_MONTH, clampedDueNextMonth)
            trigger.add(Calendar.DAY_OF_MONTH, -offsetDays)
        }

        return trigger.timeInMillis
    }

    /**
     * Schedules persistent alarm reminders for a single credit card across all enabled offsets.
     */
    fun scheduleRemindersForCard(
        context: Context,
        bankName: String,
        last4: String,
        dueDate: Int,
        accountId: String,
        outstandingAmount: Double
    ) {
        if (dueDate !in 1..31) {
            Log.d(TAG, "Skipping reminders for $bankName ($last4): invalid due date ($dueDate)")
            return
        }

        if (outstandingAmount <= 0.0) {
            Log.d(TAG, "Skipping reminders for $bankName ($last4): outstanding is zero or negative (₹$outstandingAmount)")
            cancelRemindersForCard(context, bankName, last4, dueDate)
            return
        }

        val cardKey = getCardKey(bankName, last4)
        if (!isReminderEnabledForCard(context, cardKey)) {
            Log.d(TAG, "Skipping reminders for $bankName ($last4): reminders disabled by user")
            cancelRemindersForCard(context, bankName, last4, dueDate)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val enabledOffsets = getEnabledOffsetsForCard(context, cardKey)

        createNotificationChannel(context)

        for (offset in enabledOffsets) {
            val triggerTime = calculateNextReminderTime(dueDate, offset) ?: continue
            val requestCode = getRequestCode(cardKey, dueDate, offset)

            val intent = Intent(context, CreditCardReminderReceiver::class.java).apply {
                action = CreditCardReminderReceiver.ACTION_CREDIT_CARD_DUE_REMINDER
                putExtra(CreditCardReminderReceiver.EXTRA_CARD_KEY, cardKey)
                putExtra(CreditCardReminderReceiver.EXTRA_BANK_NAME, bankName)
                putExtra(CreditCardReminderReceiver.EXTRA_LAST4, last4)
                putExtra(CreditCardReminderReceiver.EXTRA_DUE_DATE, dueDate)
                putExtra(CreditCardReminderReceiver.EXTRA_OFFSET_DAYS, offset)
                putExtra(CreditCardReminderReceiver.EXTRA_ACCOUNT_ID, accountId)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
                Log.d(TAG, "Scheduled reminder for $bankName ($last4): offset=$offset days, triggerTime=$triggerTime, reqCode=$requestCode")
            } catch (e: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                Log.w(TAG, "Scheduled fallback non-exact reminder for $bankName: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule reminder for $bankName: ${e.message}", e)
            }
        }
    }

    /**
     * Cancels all scheduled reminder alarms for a given card.
     */
    fun cancelRemindersForCard(context: Context, bankName: String, last4: String, dueDate: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val cardKey = getCardKey(bankName, last4)

        for (offset in DEFAULT_OFFSETS) {
            val requestCode = getRequestCode(cardKey, dueDate, offset)
            val intent = Intent(context, CreditCardReminderReceiver::class.java).apply {
                action = CreditCardReminderReceiver.ACTION_CREDIT_CARD_DUE_REMINDER
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.d(TAG, "Cancelled reminder for $bankName ($last4), offset=$offset")
            }
        }
    }

    /**
     * Reschedules all valid credit card reminders asynchronously on app launch / device reboot.
     */
    fun rescheduleAllAsync(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val dao = db.kharchaDao()

                val accounts = dao.getAllAccountsSync()
                val cards = dao.getAllCardsSync()
                val transactions = dao.getAllTransactionsSync()

                val creditAccounts = accounts.filter { it.isActive && it.type.equals("Credit Card", ignoreCase = true) }
                val creditCards = cards.filter { it.type.equals("Credit Card", ignoreCase = true) }

                val processedKeys = mutableSetOf<String>()

                // 1. Process CardEntity records
                for (card in creditCards) {
                    val parentAcc = accounts.find { it.id == card.accountId }
                    val bankName = parentAcc?.bankName?.ifEmpty { parentAcc.name } ?: card.name
                    val last4 = card.last4Digits.ifEmpty { parentAcc?.last4Digits ?: "" }
                    val dueDate = if (card.dueDate in 1..31) card.dueDate else (parentAcc?.dueDate ?: 0)
                    val cardKey = getCardKey(bankName, last4)

                    if (last4.isNotEmpty() && processedKeys.contains(cardKey)) continue

                    // Calculate live outstanding balance
                    val linkedTxs = transactions.filter { tx ->
                        tx.cardId == card.id || tx.accountId == card.accountId || (parentAcc != null && tx.accountId == parentAcc.id) ||
                                (last4.length == 4 && tx.last4Digits == last4)
                    }
                    val expenseTotal = linkedTxs.filter { (it.direction == "DEBIT" || it.type == "EXPENSE") && !it.isInternalTransfer && it.transactionType != "CARD_PAYMENT" && it.transactionType != "CREDIT_CARD_BILL_PAYMENT" }.sumOf { it.amount }
                    val paymentTotal = linkedTxs.filter { it.transactionType == "CARD_PAYMENT" || it.transactionType == "CREDIT_CARD_BILL_PAYMENT" || (it.direction == "CREDIT" && !it.isInternalTransfer) }.sumOf { it.amount }
                    val txOutstanding = (expenseTotal - paymentTotal).coerceAtLeast(0.0)
                    val effectiveOutstanding = if (txOutstanding > 0.0) txOutstanding else maxOf(parentAcc?.outstandingAmount ?: 0.0, card.outstandingAmount)

                    if (dueDate in 1..31 && effectiveOutstanding > 0.0) {
                        scheduleRemindersForCard(
                            context = context,
                            bankName = bankName,
                            last4 = last4,
                            dueDate = dueDate,
                            accountId = card.accountId.ifEmpty { parentAcc?.id ?: card.id },
                            outstandingAmount = effectiveOutstanding
                        )
                    }

                    if (last4.isNotEmpty()) processedKeys.add(cardKey)
                }

                // 2. Process standalone credit card AccountEntity records
                for (acc in creditAccounts) {
                    val bankName = acc.bankName.ifEmpty { acc.name }
                    val last4 = acc.last4Digits
                    val dueDate = acc.dueDate
                    val cardKey = getCardKey(bankName, last4)

                    if (last4.isNotEmpty() && processedKeys.contains(cardKey)) continue

                    val linkedTxs = transactions.filter { tx ->
                        tx.accountId == acc.id || tx.counterpartyAccountId == acc.id || (last4.length == 4 && tx.last4Digits == last4)
                    }
                    val expenseTotal = linkedTxs.filter { (it.direction == "DEBIT" || it.type == "EXPENSE") && !it.isInternalTransfer && it.transactionType != "CARD_PAYMENT" && it.transactionType != "CREDIT_CARD_BILL_PAYMENT" }.sumOf { it.amount }
                    val paymentTotal = linkedTxs.filter { it.transactionType == "CARD_PAYMENT" || it.transactionType == "CREDIT_CARD_BILL_PAYMENT" || (it.direction == "CREDIT" && !it.isInternalTransfer) }.sumOf { it.amount }
                    val txOutstanding = (expenseTotal - paymentTotal).coerceAtLeast(0.0)
                    val effectiveOutstanding = if (txOutstanding > 0.0) txOutstanding else acc.outstandingAmount

                    if (dueDate in 1..31 && effectiveOutstanding > 0.0) {
                        scheduleRemindersForCard(
                            context = context,
                            bankName = bankName,
                            last4 = last4,
                            dueDate = dueDate,
                            accountId = acc.id,
                            outstandingAmount = effectiveOutstanding
                        )
                    }

                    if (last4.isNotEmpty()) processedKeys.add(cardKey)
                }

                Log.d(TAG, "Completed full reminders rescheduling for ${processedKeys.size} credit cards")
            } catch (e: Exception) {
                Log.e(TAG, "Error during rescheduleAllAsync: ${e.message}", e)
            }
        }
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for upcoming credit card bill payment due dates"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
