package com.example.utils

import android.content.Context
import android.util.Log
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

data class CreditCardBillInfo(
    val bankName: String,
    val last4Digits: String,
    val totalAmountDue: Double,
    val minimumAmountDue: Double = 0.0,
    val dueDate: Int = 0, // Day of month (1-31)
    val paymentDueDateStr: String = "",
    val statementDateStr: String = "",
    val source: String, // "SMS", "NOTIFICATION", "EMAIL"
    val messageId: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

sealed class BillIngestionResult {
    data class Updated(val billInfo: CreditCardBillInfo, val accountId: String, val cardId: String?) : BillIngestionResult()
    data class Duplicate(val billInfo: CreditCardBillInfo) : BillIngestionResult()
    data class UnlinkedReminder(val billInfo: CreditCardBillInfo) : BillIngestionResult()
    object NoMatchingCard : BillIngestionResult()
    object InvalidData : BillIngestionResult()
    object NotABillMessage : BillIngestionResult()
}

object CreditCardBillIngestionEngine {

    private const val TAG = "CCBillIngestion"

    private fun getNowIsoString(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    /**
     * Determines whether [rawText] is a genuine credit card bill / statement alert or payment due reminder,
     * as opposed to an ordinary purchase debit or payment receipt.
     */
    fun isCreditCardBillMessage(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val lower = rawText.lowercase(Locale.ENGLISH)

        // 1. Exclude payment receipt confirmations (e.g. "Payment received towards credit card", "Thank you for payment")
        val isPaymentReceived = lower.contains("payment received") ||
                lower.contains("payment towards your credit card") ||
                lower.contains("payment towards your card") ||
                lower.contains("received towards your credit card") ||
                (lower.contains("thank you") && lower.contains("payment") && (lower.contains("credit card") || lower.contains("card"))) ||
                lower.contains("credit card bill paid") ||
                (lower.contains("bill payment of") && lower.contains("successful"))

        if (isPaymentReceived) return false

        // Explicit reminder / due subject or content check
        val isExplicitReminder = lower.contains("payment due reminder") ||
                lower.contains("payment due") ||
                lower.contains("credit card payment due") ||
                lower.contains("your payment is due") ||
                lower.contains("payment is due") ||
                lower.contains("credit card bill due") ||
                lower.contains("due date reminder") ||
                lower.contains("due date")

        // 2. Exclude ordinary purchase transactions (e.g. "debited for Rs 500 at Amazon", "spent on Card XX1234 at Swiggy")
        val isPurchaseDebit = !isExplicitReminder &&
                (lower.contains("debited") || lower.contains("spent") || lower.contains("purchase") || lower.contains("used for") || lower.contains("used at") || lower.contains("txn of")) &&
                (lower.contains(" at ") || lower.contains(" on ") || lower.contains("to vpa") || lower.contains("pos") || lower.contains("e-com") || lower.contains("info:")) &&
                !lower.contains("total amount due") && !lower.contains("total due") && !lower.contains("statement amount") && !lower.contains("bill of rs") && !lower.contains("bill is rs") && !lower.contains("due reminder")

        if (isPurchaseDebit) return false

        // 3. Genuine Bill / Statement / Reminder Indicators
        val hasBillOrReminderKeyword = isExplicitReminder ||
                lower.contains("total amount due") ||
                lower.contains("total due") ||
                lower.contains("tot due") ||
                lower.contains("tot amt due") ||
                lower.contains("statement amount") ||
                lower.contains("stmt amt") ||
                lower.contains("minimum amount due") ||
                lower.contains("min amount due") ||
                lower.contains("minimum due") ||
                lower.contains("min due") ||
                lower.contains("min amt due") ||
                lower.contains("payment due date") ||
                lower.contains("pay by") ||
                lower.contains("statement due") ||
                lower.contains("statement generated") ||
                lower.contains("card statement") ||
                lower.contains("credit card statement") ||
                lower.contains("bill generated") ||
                lower.contains("credit card bill") ||
                (lower.contains("bill") && (lower.contains("is rs") || lower.contains("of rs") || lower.contains("for rs") || lower.contains("is inr") || lower.contains("is ₹"))) ||
                (lower.contains("statement") && (lower.contains("due on") || lower.contains("due by") || lower.contains("pay by")))

        return hasBillOrReminderKeyword
    }

    /**
     * Extracts structured credit card bill information from the message text.
     */
    fun extractBillInfo(
        text: String,
        senderOrSubject: String = "",
        source: String = "SMS",
        messageId: String = "",
        timestamp: Long = System.currentTimeMillis()
    ): CreditCardBillInfo? {
        if (!isCreditCardBillMessage("$senderOrSubject $text")) return null

        val combined = "$senderOrSubject $text"

        // 1. Extract Bank Name
        val bankName = extractBankName(combined, senderOrSubject)
        if (bankName.isBlank()) {
            Log.d(TAG, "Skipping bill parsing: bank name could not be reliably identified")
            return null
        }

        // 2. Extract Card Last 4 Digits (or fallback to empty string for unlinked reminders)
        val last4 = extractCardLast4Digits(combined)

        // 3. Extract Total Amount Due
        val totalDue = extractTotalAmountDue(combined)

        // 4. Extract Minimum Amount Due
        val minDue = extractMinimumAmountDue(combined)

        // 5. Extract Payment Due Date
        val (dueDateInt, dueDateStr) = extractPaymentDueDate(combined)

        // 6. Extract Statement Date
        val statementDateStr = extractStatementDate(combined)

        return CreditCardBillInfo(
            bankName = bankName,
            last4Digits = last4,
            totalAmountDue = totalDue,
            minimumAmountDue = minDue,
            dueDate = dueDateInt,
            paymentDueDateStr = dueDateStr,
            statementDateStr = statementDateStr,
            source = source,
            messageId = messageId,
            timestamp = timestamp
        )
    }

    /**
     * Ingests the credit card bill / reminder information into the Room database or unlinked store
     * without creating any transaction.
     */
    suspend fun ingestBillInfo(
        context: Context,
        billInfo: CreditCardBillInfo
    ): BillIngestionResult = withContext(Dispatchers.IO) {
        if (billInfo.bankName.isBlank()) {
            return@withContext BillIngestionResult.InvalidData
        }

        val db = AppDatabase.getDatabase(context)
        val dao = db.kharchaDao()

        val accounts = dao.getAllAccountsSync()
        val cards = dao.getAllCardsSync()
        val transactions = dao.getAllTransactionsSync()

        // Strict Matching: Match ONLY by normalized bank name AND 4-digit last4.
        val matchedCard = if (billInfo.last4Digits.length == 4) {
            cards.find { card ->
                card.last4Digits == billInfo.last4Digits &&
                        TransactionIngestionEngine.isBankNameMatch(card.name, billInfo.bankName)
            }
        } else null

        val matchedAccount = if (billInfo.last4Digits.length == 4) {
            accounts.find { acc ->
                acc.last4Digits == billInfo.last4Digits &&
                        (acc.type.equals("Credit Card", ignoreCase = true) || matchedCard != null) &&
                        TransactionIngestionEngine.isBankNameMatch(acc.bankName.ifEmpty { acc.name }, billInfo.bankName)
            } ?: if (matchedCard != null) accounts.find { it.id == matchedCard.accountId } else null
        } else null

        if (matchedCard == null && matchedAccount == null) {
            Log.d(TAG, "No matching existing Credit Card found for ${billInfo.bankName} •••• ${billInfo.last4Digits}. Preserving as Identified but Unlinked reminder.")
            saveUnlinkedReminder(context, billInfo)
            return@withContext BillIngestionResult.UnlinkedReminder(billInfo)
        }

        // Deterministic fingerprint for duplicate bill detection across SMS / Notification / Email
        val fingerprint = "${billInfo.bankName.lowercase(Locale.ENGLISH).replace(" ", "")}_${billInfo.last4Digits}_${billInfo.dueDate}_${String.format(Locale.US, "%.2f", billInfo.totalAmountDue)}_${billInfo.statementDateStr}"

        // Check if identical bill has already been ingested recently
        val existingLastBillId = matchedCard?.lastBillMessageId ?: matchedAccount?.lastBillMessageId ?: ""
        if (existingLastBillId.isNotEmpty() && (existingLastBillId == billInfo.messageId || existingLastBillId == fingerprint)) {
            Log.d(TAG, "Duplicate bill detected (fingerprint=$fingerprint), skipping redundant update")
            return@withContext BillIngestionResult.Duplicate(billInfo)
        }

        // Payment-aware behavior: Calculate payments made since or towards this bill
        val cardId = matchedCard?.id
        val accId = matchedAccount?.id ?: matchedCard?.accountId ?: ""

        val recentPayments = transactions.filter { tx ->
            val isPayment = tx.transactionType == "CARD_PAYMENT" || tx.merchant.contains("Credit Card Bill Payment", true) ||
                    tx.note.contains("credit card payment", true) || tx.note.contains("paid towards credit card", true)
            if (!isPayment) return@filter false

            val isLinkedToThisCard = (cardId != null && tx.counterpartyAccountId == cardId) ||
                    (accId.isNotEmpty() && tx.counterpartyAccountId == accId) ||
                    (accId.isNotEmpty() && tx.accountId == accId) ||
                    (tx.last4Digits == billInfo.last4Digits)

            isLinkedToThisCard
        }

        val totalPayments = recentPayments.sumOf { it.amount }
        // Note: The new statement Total Amount Due establishes the bill cycle's baseline outstanding.
        val effectiveOutstanding = billInfo.totalAmountDue

        val nowIso = getNowIsoString()
        var updatedAccount: AccountEntity? = null
        var updatedCard: CardEntity? = null

        if (matchedAccount != null) {
            val dueDay = if (billInfo.dueDate in 1..31) billInfo.dueDate else matchedAccount.dueDate
            updatedAccount = matchedAccount.copy(
                outstandingAmount = effectiveOutstanding,
                minimumAmountDue = if (billInfo.minimumAmountDue > 0.0) billInfo.minimumAmountDue else matchedAccount.minimumAmountDue,
                dueDate = dueDay,
                paymentDueDate = billInfo.paymentDueDateStr.ifEmpty { matchedAccount.paymentDueDate },
                statementDate = billInfo.statementDateStr.ifEmpty { matchedAccount.statementDate },
                lastBillSource = billInfo.source,
                lastBillMessageId = billInfo.messageId.ifEmpty { fingerprint },
                lastBillUpdatedAt = nowIso,
                updatedAt = nowIso
            )
            dao.insertAccount(updatedAccount)
            Log.d(TAG, "Updated AccountEntity bill info for ${updatedAccount.name} (${billInfo.bankName} •••• ${billInfo.last4Digits}): Outstanding=₹$effectiveOutstanding, MinDue=₹${updatedAccount.minimumAmountDue}, DueDate=${updatedAccount.dueDate}")
        }

        if (matchedCard != null) {
            val dueDay = if (billInfo.dueDate in 1..31) billInfo.dueDate else matchedCard.dueDate
            updatedCard = matchedCard.copy(
                outstandingAmount = effectiveOutstanding,
                minimumAmountDue = if (billInfo.minimumAmountDue > 0.0) billInfo.minimumAmountDue else matchedCard.minimumAmountDue,
                dueDate = dueDay,
                paymentDueDate = billInfo.paymentDueDateStr.ifEmpty { matchedCard.paymentDueDate },
                statementDate = billInfo.statementDateStr.ifEmpty { matchedCard.statementDate },
                lastBillSource = billInfo.source,
                lastBillMessageId = billInfo.messageId.ifEmpty { fingerprint },
                lastBillUpdatedAt = nowIso,
                updatedAt = nowIso
            )
            dao.insertCard(updatedCard)
            Log.d(TAG, "Updated CardEntity bill info for ${updatedCard.name} (${billInfo.bankName} •••• ${billInfo.last4Digits}): Outstanding=₹$effectiveOutstanding, MinDue=₹${updatedCard.minimumAmountDue}, DueDate=${updatedCard.dueDate}")
        }

        // Trigger / Reschedule Payment Due Reminders with the updated bill information
        val effectiveDueDay = billInfo.dueDate.takeIf { it in 1..31 }
            ?: updatedAccount?.dueDate?.takeIf { it in 1..31 }
            ?: updatedCard?.dueDate?.takeIf { it in 1..31 }
            ?: 0

        if (effectiveDueDay in 1..31 && effectiveOutstanding > 0.0) {
            CreditCardReminderManager.scheduleRemindersForCard(
                context = context,
                bankName = billInfo.bankName,
                last4 = billInfo.last4Digits,
                dueDate = effectiveDueDay,
                accountId = accId.ifEmpty { cardId ?: "" },
                outstandingAmount = effectiveOutstanding
            )
            Log.d(TAG, "Rescheduled credit card reminder alarms for ${billInfo.bankName} •••• ${billInfo.last4Digits} (Due day: $effectiveDueDay)")
        }

        return@withContext BillIngestionResult.Updated(
            billInfo = billInfo,
            accountId = accId,
            cardId = cardId
        )
    }

    // ==========================================
    // EXTRACTION HELPERS
    // ==========================================

    fun extractBankName(content: String, sender: String): String {
        val cleanSender = sender.uppercase(Locale.ENGLISH)
        val lowerContent = content.lowercase(Locale.ENGLISH)

        return when {
            cleanSender.contains("HDFC") || lowerContent.contains("hdfc") -> "HDFC Bank"
            cleanSender.contains("ICICI") || lowerContent.contains("icici") -> "ICICI Bank"
            cleanSender.contains("SBI") || cleanSender.contains("SBIN") || lowerContent.contains("sbi card") || lowerContent.contains("state bank") -> "SBI"
            cleanSender.contains("AXIS") || lowerContent.contains("axis bank") || lowerContent.contains("axis card") -> "Axis Bank"
            cleanSender.contains("KOTAK") || lowerContent.contains("kotak") -> "Kotak Bank"
            cleanSender.contains("IDFC") || lowerContent.contains("idfc") -> "IDFC FIRST Bank"
            cleanSender.contains("INDUS") || lowerContent.contains("indusind") -> "IndusInd Bank"
            cleanSender.contains("RBL") || lowerContent.contains("rbl") -> "RBL Bank"
            cleanSender.contains("AMEX") || lowerContent.contains("american express") -> "American Express"
            cleanSender.contains("CITI") || lowerContent.contains("citibank") -> "Citibank"
            cleanSender.contains("HSBC") || lowerContent.contains("hsbc") -> "HSBC"
            cleanSender.contains("YES") || lowerContent.contains("yes bank") -> "Yes Bank"
            cleanSender.contains("PNB") || lowerContent.contains("punjab national") -> "PNB"
            cleanSender.contains("BOB") || lowerContent.contains("bank of baroda") -> "Bank of Baroda"
            cleanSender.contains("CANARA") || lowerContent.contains("canara") -> "Canara Bank"
            cleanSender.contains("FEDERAL") || lowerContent.contains("federal bank") -> "Federal Bank"
            cleanSender.contains("AU") || lowerContent.contains("au small finance") || lowerContent.contains("au bank") -> "AU Small Finance Bank"
            cleanSender.contains("ONE") || lowerContent.contains("onecard") || lowerContent.contains("one card") -> "OneCard"
            cleanSender.contains("SC") || lowerContent.contains("standard chartered") -> "Standard Chartered"
            else -> ""
        }
    }

    fun extractCardLast4Digits(content: String): String {
        val patterns = listOf(
            Regex("(?:credit\\s*card|card|cc)\\s*(?:no\\.?)?\\s*(?:ending|ending\\s*with|ending\\s*in|xx|x|[*]+)\\s*[:.-]?\\s*([0-9]{4})", RegexOption.IGNORE_CASE),
            Regex("(?:ending|ending\\s*with|ending\\s*in)\\s*[:.-]?\\s*([0-9]{4})", RegexOption.IGNORE_CASE),
            Regex("(?:xx|[*]{2,})([0-9]{4})", RegexOption.IGNORE_CASE),
            Regex("card\\s*([0-9]{4})", RegexOption.IGNORE_CASE),
            Regex("(?:a/c|account)\\s*(?:ending)?\\s*([0-9]{4})", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                val digits = match.groupValues[1]
                if (digits.length == 4) return digits
            }
        }
        return ""
    }

    fun extractTotalAmountDue(content: String): Double {
        val patterns = listOf(
            Regex("total\\s*(?:amount)?\\s*due\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("total\\s*due\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("payment\\s*(?:of)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(?:is)?\\s*due", RegexOption.IGNORE_CASE),
            Regex("(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(?:is)?\\s*due", RegexOption.IGNORE_CASE),
            Regex("due\\s*(?:amount|amt)?\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("tot\\s*amt\\s*due\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("tot\\s*due\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("statement\\s*(?:amount|amt|balance|bal)\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("bill\\s*(?:amount|amt|is)?\\s*(?:of)?\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("bill\\s*for\\s*your\\s*(?:credit)?\\s*card\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("outstanding\\s*(?:amount|due|bal|balance)?\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                val numStr = match.groupValues[1].replace(",", "")
                val amt = numStr.toDoubleOrNull() ?: 0.0
                if (amt > 0) return amt
            }
        }
        return 0.0
    }

    fun extractMinimumAmountDue(content: String): Double {
        val patterns = listOf(
            Regex("min(?:imum)?\\s*(?:amount|amt)?\\s*due\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("min(?:imum)?\\s*due\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("mad\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("min\\s*amt\\s*due\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                val numStr = match.groupValues[1].replace(",", "")
                val amt = numStr.toDoubleOrNull() ?: 0.0
                if (amt > 0) return amt
            }
        }
        return 0.0
    }

    fun extractPaymentDueDate(content: String): Pair<Int, String> {
        val patterns = listOf(
            Regex("(?:payment\\s*due\\s*date|due\\s*date|due\\s*on|due\\s*by|pay\\s*by|pay\\s*before|pay\\s*on\\s*or\\s*before)\\s*(?:is|:|-)?\\s*([0-9]{1,2}[/-][0-9]{1,2}[/-][0-9]{2,4}|[0-9]{1,2}(?:st|nd|rd|th)?\\s+[a-zA-Z]{3,9}(?:\\s+[0-9]{2,4})?)", RegexOption.IGNORE_CASE),
            Regex("pay\\s*by\\s*([0-9]{1,2}[/-][0-9]{1,2}[/-][0-9]{2,4}|[0-9]{1,2}(?:st|nd|rd|th)?\\s+[a-zA-Z]{3,9}(?:\\s+[0-9]{2,4})?)", RegexOption.IGNORE_CASE),
            Regex("due\\s*([0-9]{1,2}[/-][0-9]{1,2}[/-][0-9]{2,4}|[0-9]{1,2}(?:st|nd|rd|th)?\\s+[a-zA-Z]{3,9}(?:\\s+[0-9]{2,4})?)", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                val rawDate = match.groupValues[1].trim()
                val dayInt = parseDayOfMonth(rawDate)
                return Pair(dayInt, rawDate)
            }
        }
        return Pair(0, "")
    }

    fun extractStatementDate(content: String): String {
        val patterns = listOf(
            Regex("(?:statement\\s*date|stmt\\s*date|bill\\s*date)\\s*(?:is|:|-)?\\s*([0-9]{1,2}[/-][0-9]{1,2}[/-][0-9]{2,4}|[0-9]{1,2}(?:st|nd|rd|th)?\\s+[a-zA-Z]{3,9}(?:\\s+[0-9]{2,4})?)", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return ""
    }

    private fun parseDayOfMonth(dateStr: String): Int {
        // e.g. "15/10/2026", "15-10-2026", "15 Oct", "15th Oct 2026", "5 Oct"
        val dayMatch = Regex("^([0-9]{1,2})").find(dateStr)
        if (dayMatch != null) {
            val day = dayMatch.groupValues[1].toIntOrNull() ?: 0
            if (day in 1..31) return day
        }

        // Try date formats
        val formats = listOf("dd/MM/yyyy", "dd-MM-yyyy", "dd/MM/yy", "dd-MM-yy", "dd MMM yyyy", "dd MMM", "d MMM yyyy", "d MMM")
        for (fmt in formats) {
            try {
                val parsed = SimpleDateFormat(fmt, Locale.ENGLISH).parse(dateStr)
                if (parsed != null) {
                    val cal = java.util.Calendar.getInstance().apply { time = parsed }
                    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    if (day in 1..31) return day
                }
            } catch (e: Exception) {
                // Try next
            }
        }
        return 0
    }

    private fun saveUnlinkedReminder(context: Context, billInfo: CreditCardBillInfo) {
        try {
            val prefs = context.getSharedPreferences("unlinked_reminders_pref", Context.MODE_PRIVATE)
            val key = "unlinked_${billInfo.bankName.lowercase(Locale.ENGLISH).replace(" ", "")}_${billInfo.last4Digits.ifEmpty { "0000" }}_${billInfo.messageId}"
            val json = """
                {
                    "bankName": "${billInfo.bankName}",
                    "last4Digits": "${billInfo.last4Digits}",
                    "totalAmountDue": ${billInfo.totalAmountDue},
                    "minimumAmountDue": ${billInfo.minimumAmountDue},
                    "dueDate": ${billInfo.dueDate},
                    "paymentDueDateStr": "${billInfo.paymentDueDateStr}",
                    "source": "${billInfo.source}",
                    "messageId": "${billInfo.messageId}",
                    "timestamp": ${billInfo.timestamp}
                }
            """.trimIndent()
            prefs.edit().putString(key, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save unlinked reminder: ${e.message}")
        }
    }
}
