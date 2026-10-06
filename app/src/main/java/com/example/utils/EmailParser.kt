package com.example.utils

import com.example.data.entity.TransactionEntity
import java.util.Locale
import java.util.UUID

data class EmailParseOutput(
    val transaction: TransactionEntity?,
    val status: EmailParserStatus
)

enum class EmailParserStatus {
    IMPORTED,
    UPDATED,
    DUPLICATE,
    IGNORED,
    NEEDS_REVIEW,
    PARSING_FAILED
}

object EmailParser {

    fun parseEmail(
        messageId: String,
        subject: String,
        body: String,
        timestamp: Long,
        existingTransactions: List<TransactionEntity> = emptyList()
    ): EmailParseOutput {
        val rawCombined = "$subject $body"

        // 0. Ignore promotional, bill statements, payment due reminders, and credit card payment confirmations
        if (SmsParser.isPromotionalOrAdvertisementMessage(rawCombined) ||
            CreditCardBillIngestionEngine.isCreditCardBillMessage(rawCombined) ||
            isPaymentConfirmationMessage(rawCombined)
        ) {
            return EmailParseOutput(null, EmailParserStatus.IGNORED)
        }

        val fullContent = "$subject $body".lowercase(Locale.ENGLISH)

        // 1. Ignore OTP, pure promotional, newsletters, marketing, social
        val strictIgnoreKeywords = listOf(
            "otp", "one time password", "verification code", "verify your email",
            "verify your account", "reset password", "newsletter", "unsubscribed",
            "unsubscribe", "statement summary", "reward points balance"
        )
        if (strictIgnoreKeywords.any { fullContent.contains(it) }) {
            return EmailParseOutput(null, EmailParserStatus.IGNORED)
        }

        val promoKeywords = listOf("promotional", "advertisement", "marketing", "discount offer", "cashback offer", "win cash", "won cash", "offer")
        val hasGenuineTransaction = fullContent.contains("debited") || fullContent.contains("credited") ||
                fullContent.contains("paid") || fullContent.contains("spent") || fullContent.contains("withdrawn") ||
                fullContent.contains("received") || fullContent.contains("purchase") || fullContent.contains("money sent")

        if (promoKeywords.any { fullContent.contains(it) } && !hasGenuineTransaction) {
            return EmailParseOutput(null, EmailParserStatus.IGNORED)
        }

        // 2. Expense vs Income detection
        val finalType = SmsParser.determineTransactionDirection(rawCombined)
            ?: return EmailParseOutput(null, EmailParserStatus.IGNORED)

        // 3. Amount extraction
        val amount = extractAmount(rawCombined)
        if (amount <= 0.0) {
            return EmailParseOutput(null, EmailParserStatus.PARSING_FAILED)
        }

        // 4. Merchant extraction
        val cleanContent = SmsParser.removeUrls(rawCombined)
        val merchant = extractMerchant(subject, cleanContent)

        // 5. Date and time
        val (dateStr, timeStr) = extractDateTime(cleanContent, timestamp)

        // 6. Last 4 digits & reference
        val last4 = TransactionIdentityResolver.extractLast4(cleanContent)
        val txRef = extractTransactionReference(cleanContent)

        // 7. Category
        val categoryId = SmsParser.determineCategory(merchant, finalType, cleanContent)
        val subcategoryId = SmsParser.determineSubcategory(categoryId, merchant, cleanContent)

        // 8. Account & Payment method
        val accountId = determineAccountId(fullContent)
        val paymentMethod = TransactionIdentityResolver.resolvePaymentMethod(fullContent)

        val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        val originalRef = "EMAIL-${messageId}"
        val txId = "tx-email-" + UUID.randomUUID().toString().substring(0, 8)

        val tx = TransactionEntity(
            id = txId,
            type = finalType,
            direction = if (finalType == "INCOME") "CREDIT" else "DEBIT",
            amount = amount,
            date = dateStr,
            time = timeStr,
            merchant = merchant,
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            accountId = accountId,
            paymentMethod = paymentMethod,
            note = "",
            source = "EMAIL",
            transactionReference = txRef.ifEmpty { "EMAIL-TX-${System.currentTimeMillis()}-${(1000..9999).random()}" },
            originalReference = originalRef,
            last4Digits = last4,
            createdAt = nowIso,
            updatedAt = nowIso
        )

        val isDuplicate = existingTransactions.any { existing ->
            TransactionIngestionEngine.isDuplicateTransaction(tx, existing)
        }

        if (isDuplicate) {
            return EmailParseOutput(tx, EmailParserStatus.DUPLICATE)
        }

        return EmailParseOutput(tx, EmailParserStatus.IMPORTED)
    }

    private fun extractAmount(content: String): Double {
        val balanceKeywords = "available\\s*balance|avbl?\\s*bal|avl\\s*bal|current\\s*balance|curr\\s*bal|new\\s*balance|new\\s*bal|closing\\s*balance|ledger\\s*balance|account\\s*balance|credit\\s*limit|available\\s*limit|avbl?\\s*limit|outstanding\\s*amount|outstanding\\s*bal(?:ance)?|statement\\s*balance|balance|bal|limit|outstanding"
        val cleanContent = content.replace(Regex("(?:$balanceKeywords)\\s*(?:is|of|to|for|:)?\\s*(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("avbl\\s*bal[\\s\\S]*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("available\\s*balance[\\s\\S]*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("balance[\\s\\S]*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("limit[\\s\\S]*", RegexOption.IGNORE_CASE), "")

        val patterns = listOf(
            Regex("(?:rs\\.?|inr|₹)\\s*([\\d,]+\\.?\\d*)", RegexOption.IGNORE_CASE),
            Regex("([\\d,]+\\.?\\d*)\\s*(?:rs\\.?|inr|₹)", RegexOption.IGNORE_CASE),
            Regex("(?:debited|credited|withdrawn|spent|paid|received|sent)\\s+(?:of\\s+|for\\s+)?(?:rs\\.?|inr|₹)?\\s*([\\d,]+\\.?\\d*)", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(cleanContent)
            if (match != null) {
                val numStr = match.groupValues[1].replace(",", "")
                val value = numStr.toDoubleOrNull() ?: 0.0
                if (value > 0) return value
            }
        }

        val generalRegex = Regex("([\\d,]+\\.\\d{2})")
        val genMatch = generalRegex.find(cleanContent)
        if (genMatch != null) {
            val numStr = genMatch.groupValues[1].replace(",", "")
            return numStr.toDoubleOrNull() ?: 0.0
        }

        return 0.0
    }

    fun extractMerchant(subject: String, cleanBody: String, senderAddress: String = "Email"): String {
        // Strip common email prefixes and alert wrapper subjects
        val cleanSubject = subject
            .replace(Regex("^(?:re|fwd|fw)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:sharing this alert|sharing alert|instaalerts?|bank alert|alert)\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        val fullText = "$cleanSubject $cleanBody".trim()
        val extracted = SmsParser.extractMerchant(fullText, senderAddress)
        return if (extracted == "Other") "Unknown Merchant" else extracted
    }

    private fun extractDateTime(content: String, timestamp: Long): Pair<String, String> {
        val date = java.util.Date(timestamp)
        val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

        try {
            val dateRegex = Regex("(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2,4})")
            val match = dateRegex.find(content)
            if (match != null) {
                val d = match.groupValues[1].toInt()
                val m = match.groupValues[2].toInt()
                var y = match.groupValues[3].toInt()
                if (y < 100) y += 2000

                val cal = java.util.Calendar.getInstance()
                cal.set(y, m - 1, d)
                return Pair(dateFmt.format(cal.time), timeFmt.format(date))
            }
        } catch (e: Exception) {
            // fallback
        }

        return Pair(dateFmt.format(date), timeFmt.format(date))
    }

    fun extractTransactionReference(content: String): String {
        val patterns = listOf(
            Regex("\\bupi\\s+ref\\s*(?:no|num|id|number)?\\s*[:.-]?\\s*([A-Za-z0-9]{6,25})\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:rrn|utr)\\s*[:.-]?\\s*([A-Za-z0-9]{6,25})\\b", RegexOption.IGNORE_CASE),
            Regex("(?:ref|reference|rrn|txn|transaction|utr|upi ref)(?:\\s*(?:no|num|id|number))?\\s*[:.-]?\\s*([A-Za-z0-9]{6,25})", RegexOption.IGNORE_CASE),
            Regex("upi/([A-Za-z0-9]{6,25})", RegexOption.IGNORE_CASE),
            Regex("imps/\\w+/([A-Za-z0-9]{6,25})", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val matches = pattern.findAll(content)
            for (match in matches) {
                val candidate = match.groupValues[1].trim()
                if (TransactionIdentityResolver.isValidTransactionReference(candidate)) {
                    return candidate
                }
            }
        }
        return ""
    }

    private fun determineAccountId(content: String): String {
        // Account/card matching is resolved dynamically by TransactionIngestionEngine from Room DB
        return ""
    }

    fun isPaymentConfirmationMessage(content: String): Boolean {
        val lower = content.lowercase(Locale.ENGLISH)
        return lower.contains("payment received") ||
                lower.contains("payment towards your credit card") ||
                lower.contains("payment towards your card") ||
                lower.contains("received towards your credit card") ||
                (lower.contains("thank you") && lower.contains("payment") && (lower.contains("credit card") || lower.contains("card"))) ||
                lower.contains("credit card bill paid") ||
                (lower.contains("bill payment of") && lower.contains("successful"))
    }
}
