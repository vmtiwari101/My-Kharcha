package com.example.utils

import android.util.Log
import com.example.data.entity.TransactionEntity
import java.util.Locale
import java.util.UUID

data class NotificationParseResult(
    val transaction: TransactionEntity?,
    val status: NotificationStatus
)

enum class NotificationStatus {
    IMPORTED,
    UPDATED,
    DUPLICATE,
    IGNORED,
    NEEDS_REVIEW,
    PARSING_FAILED
}

object NotificationParser {

    fun parseNotification(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        subText: String,
        postTime: Long,
        existingTransactions: List<TransactionEntity>
    ): NotificationParseResult {
        val fullContent = "$title $text $bigText $subText".lowercase(Locale.ENGLISH)

        // 0. Ignore promotional and advertisement messages or bill statement messages
        val rawCombined = "$title $text $bigText $subText"
        if (SmsParser.isPromotionalOrAdvertisementMessage(rawCombined) || CreditCardBillIngestionEngine.isCreditCardBillMessage(rawCombined)) {
            Log.d("KharchaParser", "Result: IGNORED (Promotional/Advertisement or Bill Statement message)")
            return NotificationParseResult(null, NotificationStatus.IGNORED)
        }

        // 1. Ignore non-financial messages (OTP, promotional, login alerts, balance enquiry only)
        val ignoreKeywords = listOf(
            "otp", "one time password", "verification code", "verification link", "code to verify",
            "security code", "password reset", "login alert", "logged in", "sign-in", "promotional",
            "special offer", "discount of", "scratch card", "win up to", "won", "reward points",
            "cibil score", "credit score", "pre-approved", "emi reminder", "due date", "missed call",
            "friend request", "liked your", "statement generated", "monthly statement"
        )
        if (ignoreKeywords.any { fullContent.contains(it) }) {
            Log.d("KharchaParser", "Result: IGNORED (Matches ignore keywords)")
            return NotificationParseResult(null, NotificationStatus.IGNORED)
        }

        // Exclude pure balance notifications with no debit/credit transaction
        if ((fullContent.contains("balance") || fullContent.contains("bal")) &&
            !fullContent.contains("debited") && !fullContent.contains("debit") &&
            !fullContent.contains("credited") && !fullContent.contains("credit") &&
            !fullContent.contains("sent") && !fullContent.contains("paid") &&
            !fullContent.contains("received") && !fullContent.contains("transferred") &&
            !fullContent.contains("withdrawn") && !fullContent.contains("spent") &&
            !fullContent.contains("deducted") && !fullContent.contains("used") &&
            !fullContent.contains("charged") && !fullContent.contains("purchase") &&
            !fullContent.contains("withdrawal") && !fullContent.contains("refund")
        ) {
            Log.d("KharchaParser", "Result: IGNORED (Balance inquiry only)")
            return NotificationParseResult(null, NotificationStatus.IGNORED)
        }

        // 2. Detect Expense vs Income using centralized SmsParser logic
        val finalType = SmsParser.determineTransactionDirection(rawCombined)
        if (finalType == null) {
            Log.d("KharchaParser", "Result: IGNORED (No clear credit/debit keywords found)")
            return NotificationParseResult(null, NotificationStatus.IGNORED)
        }

        // 3. Extract Amount
        val amount = extractAmount(rawCombined)
        if (amount <= 0.0) {
            Log.d("KharchaParser", "Result: PARSING_FAILED (Invalid/missing amount)")
            return NotificationParseResult(null, NotificationStatus.PARSING_FAILED)
        }

        // 4. Extract Details for Metadata
        val cleanContent = SmsParser.removeUrls(rawCombined)
        val merchant = extractMerchant(cleanContent, packageName, finalType)
        val (dateStr, timeStr) = extractDateTime(cleanContent, postTime)
        val last4 = TransactionIdentityResolver.extractLast4(cleanContent)
        val txRef = extractTransactionReference(cleanContent)

        val originalRef = if (txRef.isNotEmpty()) "NOTIF-REF-$txRef" else "NOTIF-$last4-$amount-$dateStr-$finalType"
        val txId = "tx-notif-" + UUID.randomUUID().toString().substring(0, 8)

        val categoryId = determineCategory(merchant, finalType, cleanContent)
        val subcategoryId = SmsParser.determineSubcategory(categoryId, merchant, cleanContent)
        val accountId = determineAccountId(cleanContent, packageName)
        val paymentMethod = TransactionIdentityResolver.resolvePaymentMethod(cleanContent)

        val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

        val msgTimestampIso = TransactionIngestionEngine.formatLongToIso(postTime)

        val tx = TransactionEntity(
            id = txId,
            type = finalType,
            amount = amount,
            date = dateStr,
            time = timeStr,
            merchant = merchant,
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            accountId = accountId,
            paymentMethod = paymentMethod,
            note = "",
            source = "NOTIFICATION",
            transactionReference = txRef.ifEmpty { "NOTIF-TX-${System.currentTimeMillis()}-${(1000..9999).random()}" },
            originalReference = originalRef,
            last4Digits = last4,
            createdAt = nowIso,
            updatedAt = msgTimestampIso
        )

        // Check for duplicate against existingTransactions
        val isDuplicate = existingTransactions.any { existing ->
            TransactionIngestionEngine.isDuplicateTransaction(tx, existing)
        }

        if (isDuplicate) {
            return NotificationParseResult(tx, NotificationStatus.DUPLICATE)
        }

        return NotificationParseResult(tx, NotificationStatus.IMPORTED)
    }

    private fun extractAmount(content: String): Double {
        val balanceKeywords = "available\\s*balance|avbl?\\s*bal|avl\\s*bal|current\\s*balance|curr\\s*bal|new\\s*balance|new\\s*bal|closing\\s*balance|ledger\\s*balance|account\\s*balance|credit\\s*limit|available\\s*limit|avbl?\\s*limit|outstanding\\s*amount|outstanding\\s*bal(?:ance)?|statement\\s*balance|balance|bal|limit|outstanding"
        val cleanContent = content.replace(Regex("(?:$balanceKeywords)\\s*(?:is|of|to|for|:)?\\s*(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("avbl\\s*bal[\\s\\S]*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("available\\s*balance[\\s\\S]*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("bal(?:ance)?:?\\s*(?:rs|inr|₹)?[\\s\\S]*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("limit\\s*left[\\s\\S]*", RegexOption.IGNORE_CASE), "")

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

        // Fallback pattern for numbers with decimals
        val generalRegex = Regex("([\\d,]+\\.\\d{2})")
        val genMatch = generalRegex.find(cleanContent)
        if (genMatch != null) {
            val numStr = genMatch.groupValues[1].replace(",", "")
            return numStr.toDoubleOrNull() ?: 0.0
        }

        return 0.0
    }

    private fun extractMerchant(cleanContent: String, packageName: String, finalType: String): String {
        val lower = cleanContent.lowercase(Locale.ENGLISH)

        // Rule 2: ATM check (similar to SmsParser for consistency)
        val atmPatterns = listOf(
            "cash withdrawal", "cash withdrawn", "atm withdrawal", "atm wdl", 
            "cash transaction at atm", "withdrawal from atm", "withdrawn at atm"
        )
        if (atmPatterns.any { lower.contains(it) }) {
            return "ATM"
        }

        // Rule 3: Removed COMMANDENT special case

        val knownMerchants = mapOf(
            "swiggy" to "Swiggy",
            "zomato" to "Zomato",
            "uber" to "Uber",
            "ola" to "Ola",
            "rapido" to "Rapido",
            "amazon" to "Amazon",
            "flipkart" to "Flipkart",
            "myntra" to "Myntra",
            "ajio" to "Ajio",
            "nykaa" to "Nykaa",
            "meesho" to "Meesho",
            "blinkit" to "Blinkit",
            "zepto" to "Zepto",
            "instamart" to "Instamart",
            "bigbasket" to "BigBasket",
            "dmart" to "DMart",
            "jio" to "Jio",
            "airtel" to "Airtel",
            "vi" to "Vi",
            "hpcl" to "HPCL",
            "bpcl" to "BPCL",
            "indian oil" to "Indian Oil",
            "iocl" to "Indian Oil",
            "netflix" to "Netflix",
            "spotify" to "Spotify",
            "hotstar" to "Disney+ Hotstar",
            "starbucks" to "Starbucks",
            "mcdonalds" to "McDonald's",
            "dominos" to "Domino's",
            "kfc" to "KFC",
            "burger king" to "Burger King",
            "cred" to "CRED",
            "irctc" to "IRCTC",
            "makemytrip" to "MakeMyTrip",
            "bookmyshow" to "BookMyShow",
            "apollo" to "Apollo Pharmacy",
            "pharmeasy" to "PharmEasy",
            "1mg" to "Tata 1mg",
            "urban company" to "Urban Company"
        )

        for ((key, name) in knownMerchants) {
            if (lower.contains("\\b$key\\b".toRegex())) return name
        }

        val toRegex = Regex("(?:paid to|transfer(?:red)? to|sent to|to vpa|paid at|spent at|spent on|at|to|from|;\\s*|;)\\s+([A-Za-z0-9_\\-\\.\\s]+?)(?:\\s+credited|\\s+debited|$)", RegexOption.IGNORE_CASE)
        val matches = toRegex.findAll(cleanContent).toList()
        for (m in matches) {
            var candidate = m.groupValues[1].trim()
            // Truncate at common terminators
            val stopWords = listOf(" using ", " for ", " balance ", " account ", " ref ", " txn ", " via ", " upi ")
            for (stop in stopWords) {
                if (candidate.lowercase().contains(stop)) {
                    candidate = candidate.substring(0, candidate.lowercase().indexOf(stop)).trim()
                }
            }
            
            if (isValidMerchantCandidate(candidate, packageName)) {
                val cleaned = SmsParser.cleanMerchantName(candidate)
                if (cleaned != "Unknown Merchant" && cleaned != "Other") {
                    return cleaned
                }
            }
        }

        return "Unknown Merchant"
    }

    private fun isValidMerchantCandidate(candidate: String, packageName: String): Boolean {
        val lower = candidate.lowercase(Locale.ENGLISH).trim()
        if (lower.length <= 1 || lower.length > 30) return false
        if (lower == "rs" || lower == "rs." || lower == "inr" || lower == "₹" || lower == "other" || lower == "unknown merchant") return false
        if (lower.startsWith("http") || lower.startsWith("www") || lower.contains(".com") || lower.contains(".in") || lower.contains("/")) return false
        if (lower.contains("<") || lower.contains(">") || lower.contains("&lt;") || lower.contains("&gt;")) return false
        if (lower.all { it.isDigit() || it == '.' || it == ',' || it == '-' || it == ' ' || it == ':' }) return false

        // Reject robust timestamp patterns like "07 42 43", "12:30:45", "10 15", "07-42-43"
        val timestampRegex = Regex("\\b\\d{1,2}[:\\s.-]\\d{2}(?:[:\\s.-]\\d{2})?\\s*(?:am|pm)?\\b", RegexOption.IGNORE_CASE)
        if (timestampRegex.containsMatchIn(lower)) return false

        // Reject date patterns like "29/09/26", "29-09-2026", "29 Sep 2026"
        val dateRegex1 = Regex("\\b\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}\\b")
        val dateRegex2 = Regex("\\b\\d{1,2}\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{2,4}\\b", RegexOption.IGNORE_CASE)
        val dateRegex3 = Regex("\\b\\d{1,2}\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b", RegexOption.IGNORE_CASE)
        if (dateRegex1.containsMatchIn(lower) || dateRegex2.containsMatchIn(lower) || dateRegex3.containsMatchIn(lower)) return false

        // Regex for phone numbers or long digit strings (usually reference numbers)
        if (Regex("\\b\\d{8,}\\b").containsMatchIn(lower)) return false

        // Reject candidates with more than 3 digits (reference numbers, OTPs, account/card numbers)
        val digitCount = lower.count { it.isDigit() }
        if (digitCount > 3) return false

        // Reject candidates with mixed letters and numbers of length >= 5 (transaction IDs, reference numbers)
        val mixedAlphanumericRegex = Regex("\\b(?=[a-zA-Z]*\\d)(?=\\d*[a-zA-Z])[a-zA-Z0-9]{5,}\\b")
        if (mixedAlphanumericRegex.containsMatchIn(lower)) return false

        // Reject common alphanumeric reference styles
        val alphaRefRegex1 = Regex("\\b(?:txn|ref|rrn|utr|otp|id|refno|txnid|val|auth|approval)\\d+\\b", RegexOption.IGNORE_CASE)
        val alphaRefRegex2 = Regex("\\b\\d+(?:txn|ref|rrn|utr|otp|id|refno|txnid|val|auth|approval)\\b", RegexOption.IGNORE_CASE)
        if (alphaRefRegex1.containsMatchIn(lower) || alphaRefRegex2.containsMatchIn(lower)) return false

        // Reject generic email domain styles
        if (Regex("\\b\\w+\\.(com|in|org|net|co|biz|info|html|htm|php|jsp|aspx)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) return false

        val invalidPhrases = listOf(
            "sharing this alert", "sharing alert", "share alert", "view details",
            "transaction details", "more details", "upi details", "bank alert",
            "insta alert", "instaalert", "instaalerts", "dear customer",
            "click here", "tap here", "email alert", "know the", "know more",
            "shop now", "download app", "terms and conditions", "terms & conditions",
            "get cashback", "enjoy offer", "offer details", "unsubscribed", "unsubscribe",
            "privacy policy", "all rights reserved", "customer support", "contact us",
            "helpdesk", "important notice", "security alert", "statement summary",
            "please note", "note that", "note:", "note -", "transaction alert", 
            "payment alert", "notification alert", "dear user", "hello", "hi", "greetings",
            "unrelated", "html", "text", "body", "subject", "sent from", "powered by", 
            "partner bank", "all rights", "copyright", "disclaimer", "confidentially",
            "statement of", "account statement", "summary of", "no reply", "do not reply", 
            "auto generated", "sincerely", "best regards", "warm regards", "thank you",
            "thanks & regards", "thanks and regards", "this email", "sent by", "reply to",
            "valued customer", "greetings of the day"
        )
        if (invalidPhrases.any { lower.contains(it) }) return false

        val invalidTokens = listOf(
            "a/c", "account", "bal", "balance", "vpa", "your", "dear", "customer", "card",
            "bank", "ending", "sbi", "hdfc", "icici", "axis", "kotak", "idfc", "pnb", "rbl",
            "info", "txn", "transaction", "ref", "reference", "notif", "upi", "imps",
            "neft", "rtgs", "avbl", "limit", "withdrawn", "debited", "credited", "spent",
            "paid", "payment", "received", "transfer", "transfered", "credited", "debited",
            "available", "outstanding", "statement", "amount", "total", "minimum",
            "commandent", "we", "team idfc", "bank alert", "new balance", "closing balance",
            "ledger balance", "available balance", "statement balance", "avbl bal", "avbl limit",
            "sms from", "sms", "from", "via", "to", "at", "utr", "rrn", "merchant", "vendor",
            "details", "view", "alert", "alerts", "sharing", "share", "this", "re", "fwd", "fw",
            "update", "message", "notification", "notifications", "subject", "know", "click", "here",
            "shop", "now",
            // New stop words
            "the", "a", "an", "is", "are", "am", "was", "were", "be", "been", "being", "have", "has", "had", 
            "do", "does", "did", "can", "could", "will", "would", "shall", "should", "may", "might", "must", 
            "of", "in", "on", "at", "to", "for", "with", "by", "about", "against", "between", "into", "through", 
            "during", "before", "after", "above", "below", "up", "down", "out", "over", "under", "again", 
            "further", "then", "once", "there", "when", "where", "why", "how", "all", "any", "both", "each", 
            "few", "more", "most", "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than", 
            "too", "very", "just", "please", "note", "greetings", "hello", "hi", "user"
        )
        val words = lower.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        
        // If ALL words consist solely of digits, reject
        if (words.all { w -> w.all { char -> char.isDigit() } }) return false

        // If any word is exactly one of the major bank names, or common invalid tokens
        val majorBankNames = listOf("hdfc", "sbi", "icici", "axis", "kotak", "idfc", "pnb", "rbl", "yesbank", "bob", "canara")
        if (words.any { majorBankNames.contains(it) }) return false

        val invalidCount = words.count { invalidTokens.contains(it) }
        if (invalidCount.toFloat() / words.size > 0.25f) return false
        
        if (packageName.lowercase(Locale.ENGLISH).contains(lower) && lower.length > 3) return false

        return true
    }

    private fun extractDateTime(content: String, postTime: Long): Pair<String, String> {
        val date = java.util.Date(postTime)
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
            // fallback to message timestamp
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

    fun isTelecomRecharge(merchant: String, body: String): Boolean {
        val mLower = merchant.lowercase(Locale.ENGLISH)
        val bLower = body.lowercase(Locale.ENGLISH)
        
        // Exclude generic bank/payments words that contain operator names as source/gateway
        // e.g., "Airtel Payments Bank", "Airtel UPI", "Airtel Pay", "Jio UPI", "JioPay", "Jio Payments Bank"
        val isAirtelPaymentGateway = mLower.contains("airtel payments") || mLower.contains("airtel upi") || mLower.contains("airtel bank") || bLower.contains("airtel upi") || bLower.contains("airtel payments bank") || bLower.contains("airtel bank") || bLower.contains("paid via airtel")
        val isJioPaymentGateway = mLower.contains("jio payments") || mLower.contains("jio upi") || mLower.contains("jiopay") || bLower.contains("jio upi") || bLower.contains("jio payments bank") || bLower.contains("jiopay") || bLower.contains("paid via jio")
        
        // Operators
        val isJio = mLower == "jio" || mLower.contains("jio ") || mLower.startsWith("jio") && !isJioPaymentGateway
        val isAirtel = mLower == "airtel" || mLower.contains("airtel ") || mLower.startsWith("airtel") && !isAirtelPaymentGateway
        val isVi = mLower == "vi" || mLower == "vodafone" || mLower == "idea" || mLower.startsWith("vi ") || mLower.startsWith("vodafone ") || mLower.startsWith("idea ")
        val isBsnl = mLower == "bsnl" || mLower.startsWith("bsnl ")
        
        val containsRechargeKeyword = bLower.contains("recharge") || bLower.contains("recharged") || bLower.contains("topup") || bLower.contains("prepaid") || bLower.contains("postpaid") || bLower.contains("dth") || bLower.contains("validity") || bLower.contains("data pack") || bLower.contains("plan")
        
        // High confidence rules
        if ((isJio || isAirtel || isVi || isBsnl) && containsRechargeKeyword) {
            return true
        }
        
        // Exact merchant name rules (e.g. Jio Recharge or Airtel Mobile)
        if (mLower == "jio" || mLower == "airtel" || mLower == "vi" || mLower == "bsnl" || mLower == "vodafone" || mLower == "idea") {
            // Unless it is a generic payment body with no recharge indicators
            if (bLower.contains("recharge") || bLower.contains("recharged") || bLower.contains("bill") || bLower.contains("plan") || bLower.contains("topup") || bLower.contains("dth")) {
                return true
            }
        }
        
        // Explicit text indicators like "mobile recharge of Rs. X" or "recharge successful"
        val hasExplicitText = bLower.contains("mobile recharge") || bLower.contains("prepaid recharge") || bLower.contains("postpaid recharge") || bLower.contains("dth recharge") || bLower.contains("data recharge") || bLower.contains("recharge of") || bLower.contains("recharged successfully")
        
        return hasExplicitText
    }

    private fun determineCategory(merchant: String, type: String, content: String): String {
        return SmsParser.determineCategory(merchant, type, content)
    }

    private fun determineAccountId(content: String, packageName: String): String {
        // Account/card matching is resolved dynamically by TransactionIngestionEngine from Room DB
        return ""
    }
}
