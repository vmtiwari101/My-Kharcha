package com.example.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.database.AppDatabase
import com.example.data.entity.TransactionEntity
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class SmsParseResult(
    val transactions: List<TransactionEntity>,
    val importedCount: Int,
    val updatedCount: Int,
    val skippedDuplicatesCount: Int,
    val ignoredCount: Int,
    val needsReviewCount: Int,
    val parsingFailedCount: Int
)

object SmsParser {

    private const val TAG = "SmsParser"
    private const val LEGACY_OWNER = "legacy:unassigned"

    @Volatile
    internal var liveAuthenticatedUidProvider: () -> String? = {
        try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (e: Exception) {
            Log.w(TAG, "Firebase authentication is unavailable", e)
            null
        }
    }

    internal fun liveAuthenticatedUid(): String? =
        liveAuthenticatedUidProvider()?.takeIf { it.isNotBlank() && it != LEGACY_OWNER }

    private fun emptyParseResult() = SmsParseResult(
        transactions = emptyList(),
        importedCount = 0,
        updatedCount = 0,
        skippedDuplicatesCount = 0,
        ignoredCount = 0,
        needsReviewCount = 0,
        parsingFailedCount = 0
    )

    data class RawSms(
        val id: String,
        val address: String,
        val body: String,
        val timestamp: Long
    )

    suspend fun processSmsList(
        context: Context,
        smsList: List<RawSms>,
        newerThanTimestamp: Long = 0,
        olderThanTimestamp: Long = 0,
        onProgress: ((scannedCount: Int, totalFound: Int, importedCount: Int, duplicatesCount: Int) -> Unit)? = null
    ): SmsParseResult = withContext(Dispatchers.IO) {
        val owner = liveAuthenticatedUid() ?: return@withContext emptyParseResult()
        processSmsListForOwner(context, smsList, newerThanTimestamp, olderThanTimestamp, owner, onProgress)
    }

    private suspend fun processSmsListForOwner(
        context: Context,
        smsList: List<RawSms>,
        newerThanTimestamp: Long,
        olderThanTimestamp: Long,
        owner: String,
        onProgress: ((scannedCount: Int, totalFound: Int, importedCount: Int, duplicatesCount: Int) -> Unit)?
    ): SmsParseResult = withContext(Dispatchers.IO) {
        if (liveAuthenticatedUid() != owner) return@withContext emptyParseResult()
        val parsedList = mutableListOf<TransactionEntity>()
        var ignored = 0
        var updated = 0
        var duplicates = 0
        var imported = 0
        var needsReview = 0
        var parsingFailed = 0

        // Filter messages by timestamp
        val filteredList = smsList.filter { sms ->
            val matchesNewer = newerThanTimestamp <= 0 || sms.timestamp >= newerThanTimestamp
            val matchesOlder = olderThanTimestamp <= 0 || sms.timestamp <= olderThanTimestamp
            matchesNewer && matchesOlder
        }

        val totalFound = filteredList.size
        Log.d(TAG, "SMS Scan Start: Total filtered SMS to process = $totalFound")
        
        val db = AppDatabase.getDatabase(context)
        if (liveAuthenticatedUid() != owner) return@withContext emptyParseResult()
        val initialRoomCount = db.kharchaDao().getAllTransactionsSyncForUser(owner)
            .count { it.userId == owner }
        Log.d(TAG, "Room count before SMS scan: $initialRoomCount")

        onProgress?.invoke(0, totalFound, 0, 0)
        var scanned = 0

        for (sms in filteredList) {
            if (liveAuthenticatedUid() != owner) break
            scanned++

            // Check if message is a Credit Card Bill / Statement message
            val combinedBody = "${sms.address} ${sms.body}"
            if (CreditCardBillIngestionEngine.isCreditCardBillMessage(combinedBody)) {
                val billInfo = CreditCardBillIngestionEngine.extractBillInfo(sms.body, sms.address, "SMS", sms.id, sms.timestamp)
                if (billInfo != null) {
                    if (liveAuthenticatedUid() != owner) break
                    val result = CreditCardBillIngestionEngine.ingestBillInfo(
                        context,
                        billInfo,
                        expectedOwnerUid = owner
                    )
                    when (result) {
                        is BillIngestionResult.Updated -> updated++
                        is BillIngestionResult.Duplicate -> duplicates++
                        else -> ignored++
                    }
                } else {
                    ignored++
                }
                if (scanned % 5 == 0 || scanned == totalFound) {
                    onProgress?.invoke(scanned, totalFound, imported, duplicates)
                }
                continue
            }

            val parseStatus = parseSms(sms.id, sms.address, sms.body, sms.timestamp)
            when (parseStatus) {
                is SmsParseStatus.Ignored -> ignored++
                is SmsParseStatus.ParsingFailed -> parsingFailed++
                is SmsParseStatus.NeedsReview -> needsReview++
                is SmsParseStatus.Success -> {
                    val tx = parseStatus.transaction
                    val rawText = "${sms.address} ${tx.merchant} ${tx.note} ${tx.last4Digits} ${sms.body}"

                    // Ingest directly via the centralized TransactionIngestionEngine
                    if (liveAuthenticatedUid() != owner) break
                    val (ingestedTx, status) = TransactionIngestionEngine.ingestTransaction(
                        context = context,
                        rawTx = tx.copy(userId = owner),
                        rawText = rawText,
                        expectedOwnerUid = owner
                    )

                    when (status) {
                        IngestionStatus.IMPORTED -> {
                            imported++
                            parsedList.add(ingestedTx ?: tx)
                        }
                        IngestionStatus.ENRICHED -> {
                            updated++
                            parsedList.add(ingestedTx ?: tx)
                        }
                        IngestionStatus.DUPLICATE -> {
                            duplicates++
                        }
                        IngestionStatus.NEEDS_REVIEW -> {
                            needsReview++
                            if (ingestedTx != null) parsedList.add(ingestedTx)
                        }
                        IngestionStatus.IGNORED -> {
                            ignored++
                        }
                        IngestionStatus.FAILED -> {
                            parsingFailed++
                        }
                    }
                }
            }

            if (scanned % 5 == 0 || scanned == totalFound) {
                onProgress?.invoke(scanned, totalFound, imported, duplicates)
            }
        }

        if (liveAuthenticatedUid() != owner) {
            return@withContext SmsParseResult(
                transactions = parsedList,
                importedCount = imported,
                updatedCount = updated,
                skippedDuplicatesCount = duplicates,
                ignoredCount = ignored,
                needsReviewCount = needsReview,
                parsingFailedCount = parsingFailed
            )
        }
        val finalRoomCount = db.kharchaDao().getAllTransactionsSyncForUser(owner)
            .count { it.userId == owner }
        Log.d(TAG, "Room count after SMS scan: $finalRoomCount (Imported: $imported, Duplicates: $duplicates)")
        Log.d(TAG, "SMS Scan Result: Imported=$imported, Updated=$updated, Duplicates=$duplicates, Ignored=$ignored, NeedsReview=$needsReview, Failed=$parsingFailed")

        SmsParseResult(
            transactions = parsedList,
            importedCount = imported,
            updatedCount = updated,
            skippedDuplicatesCount = duplicates,
            ignoredCount = ignored,
            needsReviewCount = needsReview,
            parsingFailedCount = parsingFailed
        )
    }

    suspend fun scanInbox(
        context: Context,
        existingTransactions: List<TransactionEntity> = emptyList(),
        newerThanTimestamp: Long = 0,
        olderThanTimestamp: Long = 0,
        onProgress: ((scannedCount: Int, totalFound: Int, importedCount: Int, duplicatesCount: Int) -> Unit)? = null
    ): SmsParseResult = withContext(Dispatchers.IO) {
        val owner = liveAuthenticatedUid() ?: return@withContext emptyParseResult()
        val rawMessages = mutableListOf<RawSms>()
        try {
            val selection = if (newerThanTimestamp > 0 && olderThanTimestamp > 0) {
                "date >= ? AND date <= ?"
            } else if (newerThanTimestamp > 0) {
                "date >= ?"
            } else if (olderThanTimestamp > 0) {
                "date <= ?"
            } else {
                null
            }

            val selectionArgs = if (newerThanTimestamp > 0 && olderThanTimestamp > 0) {
                arrayOf(newerThanTimestamp.toString(), olderThanTimestamp.toString())
            } else if (newerThanTimestamp > 0) {
                arrayOf(newerThanTimestamp.toString())
            } else if (olderThanTimestamp > 0) {
                arrayOf(olderThanTimestamp.toString())
            } else {
                null
            }

            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                selection,
                selectionArgs,
                "date DESC"
            )

            cursor?.use {
                val idCol = it.getColumnIndex("_id")
                val addressCol = it.getColumnIndex("address")
                val bodyCol = it.getColumnIndex("body")
                val dateCol = it.getColumnIndex("date")

                while (it.moveToNext()) {
                    val smsId = if (idCol >= 0) it.getString(idCol) ?: "" else ""
                    val address = if (addressCol >= 0) it.getString(addressCol) ?: "" else ""
                    val body = if (bodyCol >= 0) it.getString(bodyCol) ?: "" else ""
                    val timestamp = if (dateCol >= 0) it.getLong(dateCol) else System.currentTimeMillis()
                    rawMessages.add(RawSms(smsId, address, body, timestamp))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying SMS inbox: ${e.message}", e)
        }

        if (liveAuthenticatedUid() != owner) return@withContext emptyParseResult()
        processSmsListForOwner(
            context = context,
            smsList = rawMessages,
            newerThanTimestamp = newerThanTimestamp,
            olderThanTimestamp = olderThanTimestamp,
            owner = owner,
            onProgress = onProgress
        )
    }

    sealed class SmsParseStatus {
        object Ignored : SmsParseStatus()
        object ParsingFailed : SmsParseStatus()
        object NeedsReview : SmsParseStatus()
        data class Success(val transaction: TransactionEntity) : SmsParseStatus()
    }

    /**
     * Validates whether [text] is a promotional, marketing, advertisement, or offer message
     * rather than an actual financial debit/credit transaction confirmation.
     * Amount alone (e.g. Rs.25, ₹500) is NEVER sufficient evidence of a transaction.
     */
    fun isPromotionalOrAdvertisementMessage(text: String): Boolean {
        if (text.isBlank()) return true
        val lower = text.lowercase(Locale.ENGLISH)

        // 1. Explicit genuine bank confirmation patterns (Overrides generic marketing words if genuine debit/credit exists)
        val hasGenuineAccountDebit = (
            lower.contains("debited") ||
            lower.contains("money sent") ||
            lower.contains("spent") ||
            lower.contains("paid") ||
            lower.contains("withdrawn") ||
            lower.contains("cash withdrawal") ||
            lower.contains("payment successful") ||
            lower.contains("payment of") ||
            Regex("(?:a/c|ac|account|card)\\s*(?:no\\.?)?\\s*(?:ending)?\\s*[:.-]?\\s*[x*]*\\d+\\s+(?:is|has been)?\\s*debited", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("sent\\s*[:.-]?\\s*(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        )

        val hasGenuineAccountCredit = (
            lower.contains("credited") ||
            lower.contains("salary") ||
            lower.contains("refund") ||
            lower.contains("money received") ||
            lower.contains("cashback received") ||
            lower.contains("cashback credited") ||
            Regex("(?:a/c|ac|account|card)\\s*(?:no\\.?)?\\s*(?:ending)?\\s*[:.-]?\\s*[x*]*\\d+\\s+(?:is|has been)?\\s*credited", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        )

        // 2. High-confidence promotional / marketing keywords and phrases
        val promoKeywords = listOf(
            "flash sale",
            "sale alert",
            "get flat",
            "flat cashback",
            "limited period offer",
            "limited time offer",
            "limited period",
            "use code",
            "promo code",
            "promocode",
            "coupon code",
            "apply coupon",
            "use coupon",
            "coupon",
            "bonus hour",
            "bonus cashback",
            "welcome bonus",
            "referral bonus",
            "bonus point",
            "bonus",
            "add now",
            "click now",
            "apply now",
            "avail now",
            "order now",
            "recharge now",
            "shop now",
            "claim now",
            "grab now",
            "join now",
            "upgrade now",
            "activate now",
            "register now",
            "loan offer",
            "credit card offer",
            "recharge offer",
            "wallet offer",
            "cashback offer",
            "exclusive offer",
            "special offer",
            "mega offer",
            "bumper offer",
            "festive offer",
            "super offer",
            "wallet load",
            "load wallet",
            "load your wallet",
            "add money to wallet",
            "win up to",
            "win flat",
            "stand a chance to win",
            "scratch card",
            "scratch & win",
            "spin and win",
            "spin & win",
            "chance to win",
            "you have won",
            "congratulations! you won",
            "hurry up",
            "don't miss out",
            "ending soon",
            "valid till",
            "valid today",
            "expires today",
            "expires soon",
            "offer valid",
            "offer expires",
            "save up to",
            "flat discount",
            "instant discount",
            "earn flat",
            "earn up to",
            "earn extra",
            "earn cashback",
            "pre-approved loan",
            "pre approved loan",
            "eligible for loan",
            "loan eligibility",
            "get instant loan",
            "apply for instant loan",
            "advisory: no transaction",
            "promotional offer",
            "offer alert",
            "discount offer"
        )

        val hasPromoKeyword = promoKeywords.any { lower.contains(it) } ||
            Regex("(?:get|avail|enjoy)\\s+(?:flat|up\\s+to|upto)\\s*(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("use\\s+code\\s*[:\\-]?\\s*[a-z0-9_-]+", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("code\\s*[:\\-]\\s*[a-z0-9_-]+", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("(?:discount|cashback|offer)\\s+of\\s+(?:up\\s+to|upto|flat)?\\s*(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("(?:flat|upto|up\\s+to)\\s*(?:rs\\.?|inr|₹)?\\s*[\\d,.]+\\s+(?:off|cashback|discount|bonus)", RegexOption.IGNORE_CASE).containsMatchIn(lower)

        if (hasPromoKeyword) {
            // If it has promo keywords and NO genuine account debit/credit confirmation, it is 100% promotional!
            if (!hasGenuineAccountDebit && !hasGenuineAccountCredit) {
                return true
            }
        }

        // 3. Marketing URL / Call-To-Action check without debit/credit confirmation
        val hasMarketingCta = (lower.contains("click here") || lower.contains("bit.ly") || lower.contains("tinyurl") ||
            lower.contains("m.p-y.tm") || lower.contains("rb.gy") || lower.contains("t.co") || lower.contains("http://") || lower.contains("https://")) &&
            (lower.contains("offer") || lower.contains("deal") || lower.contains("sale") || lower.contains("save") ||
             lower.contains("cashback") || lower.contains("win") || lower.contains("apply") || lower.contains("get flat") || lower.contains("coupon"))

        if (hasMarketingCta && !hasGenuineAccountDebit && !hasGenuineAccountCredit) {
            return true
        }

        return false
    }

    /**
     * Determines transaction direction (EXPENSE vs INCOME) strictly based on message content.
     * Core Rule:
     * - Money leaving the user's bank/card/account = EXPENSE.
     * - Money being credited/received into the user's bank/account = INCOME.
     * Priority Hierarchy:
     * - Explicit debit/credit wording takes highest priority over merchant names.
     * - Merchant names are NEVER used to reverse explicit debit/credit direction.
     * - A credit card purchase (e.g. "Credit Card XXXX used for Rs.899 at Jio") is an EXPENSE.
     * - Refunds/reversals ("Refund of Rs.899 credited to your account") are INCOME/REFUND.
     * - Cash/ATM withdrawal ("Cash withdrawal of Rs. 2000 from ATM") is EXPENSE.
     */
    fun determineTransactionDirection(body: String): String? {
        // Validate against promotional / marketing messages first
        if (isPromotionalOrAdvertisementMessage(body)) {
            return null
        }

        val lower = body.lowercase(Locale.ENGLISH)

        // 0. Credit Card Bill Payment Confirmation (Money leaving bank to pay card)
        val isCcBillPayment = lower.contains("payment towards your credit card") ||
            lower.contains("payment towards your card") ||
            lower.contains("credit card payment") ||
            lower.contains("payment received towards your credit card") ||
            (lower.contains("thank you") && lower.contains("payment") && (lower.contains("credit card") || lower.contains("towards your card"))) ||
            lower.contains("card bill payment") ||
            lower.contains("credit card bill paid") ||
            lower.contains("paid towards credit card") ||
            lower.contains("select credit card") && lower.contains("payment") ||
            Regex("payment\\s+.*?\\s+towards\\s+.*?card", RegexOption.IGNORE_CASE).containsMatchIn(lower)

        if (isCcBillPayment) {
            return "EXPENSE"
        }

        // 1. Non-transaction filter: Reject purely promotional, loan offers, application tracking, advisory
        val nonTransactionPhrases = listOf(
            "request for loan", "loan application", "loan request", "loan offer", "pre-approved loan",
            "pre approved loan", "eligible for loan", "loan eligibility", "loan sanctioned",
            "loan status", "apply for loan", "apply now", "loan sanction", "credit limit increased",
            "credit limit offer", "pre-approved credit", "pre approved credit", "check eligibility",
            "you are eligible", "get loan", "loan interest rate", "apply for instant loan",
            "advisory", "marketing", "promotional offer"
        )

        // 2. Strong debit / expense phrases (Money leaving user's account/card)
        val debitPhrases = listOf(
            "debited", "debit", "debited by", "debited from", "debited for", "debited with",
            "spent", "spent on", "spent at", "spent for",
            "paid", "paid to", "paid for", "paid at",
            "payment made", "payment sent", "payment of", "payment towards",
            "purchase", "purchased", "purchased at", "purchase of",
            "withdrawn", "withdrawal", "withdrawing", "cash withdrawal", "cash withdrawn",
            "atm withdrawal", "atm wdl", "atm transaction", "atm cash",
            "deducted", "deducted from", "deducted for",
            "charged", "charged to", "charge of",
            "sent rs", "sent inr", "sent ₹", "sent to", "money sent",
            "transferred from", "transferred to", "transfer from", "transfer to",
            "upi payment", "upi payment sent", "upi payment made", "upi payment of", "upi debit", "upi txn",
            "card payment", "card purchase", "card transaction", "card txn", "card used", "used for", "used at",
            "pos txn", "pos transaction", "pos purchase", "pos payment",
            "order placed", "bill paid", "bill payment",
            "recharge", "recharged", "recharge of",
            "auto-debit", "autodebit", "mandate debit", "nach debit",
            "fee charged", "fee of", "charges of",
            "txn of", "transaction of", "txn for", "transaction for"
        )

        // 3. Strong credit / income phrases (Money entering user's account)
        val creditPhrases = listOf(
            "credited", "credit to", "credited to", "credited by", "credited in", "credited into",
            "credited with", "credited towards", "amount credited",
            "received rs", "received inr", "received ₹", "money received",
            "received in your account", "received in your a/c", "received in account", "received in a/c",
            "received into", "received from", "received via", "received through", "received by", "payment received from",
            "salary credited", "salary of", "salary for", "payroll",
            "refund credited", "refund received", "refund of", "refunded",
            "cashback received", "cashback credited", "cashback of", "cashback for",
            "reversed", "reversal", "reversal credited", "reversal of",
            "deposited", "deposit to", "deposit of", "deposited in", "deposited into", "cash deposit",
            "inward transfer", "inward remittance", "bank transfer received",
            "interest credited", "dividend credited"
        )

        // 4. Sanitize text for income evaluation to avoid false positives from "credit card", "credit limit", etc.
        val sanitizedForIncome = lower
            .replace("credit card", "cc_card")
            .replace("credit-card", "cc_card")
            .replace("credit limit", "limit_val")
            .replace("credit line", "line_val")
            .replace("line of credit", "line_val")
            .replace("credit account", "card_acc")
            .replace(Regex("\\bcred\\b"), "cred_app")
            .replace("cash withdrawal", "atm_wdl")
            .replace("cash withdrawn", "atm_wdl")
            .replace("cash payment", "cash_pay")
            .replace("cashback scratch", "")
            .replace("application received", "app_rec")
            .replace("request received", "req_rec")
            .replace("track your application", "track_app")
            .replace("has been received", "status_rec")
            .replace("mandate received", "mandate_rec")
            .replace("otp received", "otp_rec")
            .replace("call received", "call_rec")

        // 5. Debit indicators check
        val hasDebitPhrase = debitPhrases.any { lower.contains(it) } ||
            Regex("(?:card|a/c|account|ending)\\s*\\D*\\d+\\s+used\\s+(?:for|at)", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("card\\s+transaction\\s+(?:of\\s+)?(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("debited\\s+(?:by|for|with)?\\s*(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("spent\\s+(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("paid\\s+(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("withdrawal\\s+(?:of\\s+)?(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower)

        // 6. Credit indicators check
        val hasCreditPhrase = creditPhrases.any { sanitizedForIncome.contains(it) } ||
            Regex("credited\\s+(?:by|to|with|for)?\\s*(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(sanitizedForIncome) ||
            Regex("(?:rs\\.?|inr|₹)\\s*[\\d,.]+\\s+(?:has\\s+been\\s+)?credited", RegexOption.IGNORE_CASE).containsMatchIn(sanitizedForIncome) ||
            Regex("(?:amount\\s+)?(?:rs\\.?|inr|₹)?\\s*[\\d,.]+\\s+(?:is\\s+|has\\s+been\\s+)?received", RegexOption.IGNORE_CASE).containsMatchIn(sanitizedForIncome) ||
            Regex("received\\s+(?:via|through|by|from|in|into|towards|for)", RegexOption.IGNORE_CASE).containsMatchIn(sanitizedForIncome) ||
            Regex("(?:rs\\.?|inr|₹)\\s*[\\d,.]+\\s+received\\s+in", RegexOption.IGNORE_CASE).containsMatchIn(sanitizedForIncome) ||
            Regex("received\\s+(?:rs\\.?|inr|₹)\\s*[\\d,.]+\\s+in", RegexOption.IGNORE_CASE).containsMatchIn(sanitizedForIncome) ||
            Regex("refund\\s+of\\s+(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(sanitizedForIncome) ||
            Regex("(?:rs\\.?|inr|₹)\\s*[\\d,.]+\\s+refunded", RegexOption.IGNORE_CASE).containsMatchIn(sanitizedForIncome)

        // 7. Check non-transaction filter
        val hasNonTxPhrase = nonTransactionPhrases.any { lower.contains(it) }
        if (hasNonTxPhrase && !hasDebitPhrase && !hasCreditPhrase) {
            return null
        }

        // 8. Sign / Prefix Direction Check (-Rs. 500 => EXPENSE, +Rs. 500 => INCOME)
        val hasExplicitMinus = Regex("(?:^|\\s)[-]\\s*(?:rs\\.?|inr|₹)\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("(?:rs\\.?|inr|₹)\\s*[-]\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        val hasExplicitPlus = Regex("(?:^|\\s)[+]\\s*(?:rs\\.?|inr|₹)\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("(?:rs\\.?|inr|₹)\\s*[+]\\s*[\\d,.]+", RegexOption.IGNORE_CASE).containsMatchIn(lower)

        // 9. Resolving direction with Strong Evidence Priority
        // Refund / Reversal / Cashback Rule (Rule 4): Must ALWAYS be INCOME / REFUND
        val isRefundOrReversal = lower.contains("refund") || lower.contains("refunded") ||
            lower.contains("reversed") || lower.contains("reversal") ||
            lower.contains("cashback received") || lower.contains("cashback credited")

        if (isRefundOrReversal && (hasCreditPhrase || lower.contains("credited") || lower.contains("received") || lower.contains("refund"))) {
            return "INCOME"
        }

        val isIncomingTransferOrUpi = Regex(
            """\b(?:upi\s+)?(?:payment|transfer)\b.{0,60}\b(?:received|credited|inward)\b|\b(?:received|credited)\b.{0,60}\b(?:via|through|as)\s+(?:upi|bank\s+transfer)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(lower)
        if (isIncomingTransferOrUpi) {
            return "INCOME"
        }

        // Credit Card Purchase Rule (Rule 3): Credit card used/spent/charged is EXPENSE
        val isCreditCardPurchase = (lower.contains("credit card") || lower.contains("credit-card") || lower.contains("card")) &&
            (lower.contains("used for") || lower.contains("used at") || lower.contains("charged") ||
             lower.contains("spent") || lower.contains("purchase") || lower.contains("txn") || lower.contains("transaction") ||
             lower.contains("paid") || lower.contains("debited")) &&
            !isRefundOrReversal &&
            !lower.contains("payment received towards your credit card") &&
            !lower.contains("payment towards credit card")

        if (isCreditCardPurchase) {
            return "EXPENSE"
        }

        // ATM / Cash Withdrawal Rule (Rule 5): Cash withdrawal from bank is EXPENSE / CASH_WITHDRAWAL, never income
        val isAtmCash = lower.contains("cash withdrawal") || lower.contains("cash withdrawn") ||
            lower.contains("withdrawn from atm") || lower.contains("atm withdrawal") ||
            (lower.contains("withdrawn") && lower.contains("atm")) ||
            (lower.contains("atm") && hasDebitPhrase)

        if (isAtmCash) {
            return "EXPENSE"
        }

        // Single match resolution
        if (hasDebitPhrase && !hasCreditPhrase) {
            return "EXPENSE"
        }
        if (hasCreditPhrase && !hasDebitPhrase) {
            return "INCOME"
        }

        // Both match resolution
        if (hasDebitPhrase && hasCreditPhrase) {
            if (hasExplicitMinus) return "EXPENSE"
            if (hasExplicitPlus) return "INCOME"

            // If salary or refund or deposit or received in account
            if (lower.contains("salary") || lower.contains("credited to your") ||
                lower.contains("credited into") || lower.contains("received in your account") ||
                lower.contains("received in your a/c") || lower.contains("credited by rs") ||
                lower.contains("credited for rs") || lower.contains("credited with rs")
            ) {
                return "INCOME"
            }

            // If debited from or transferred from or spent
            if (lower.contains("debited") || lower.contains("spent") || lower.contains("withdrawn") ||
                lower.contains("transferred to") || lower.contains("paid")
            ) {
                return "EXPENSE"
            }

            return "EXPENSE"
        }

        // Explicit Sign fallback
        if (hasExplicitMinus) return "EXPENSE"
        if (hasExplicitPlus) return "INCOME"

        return null
    }

    fun parseSms(smsId: String, address: String, body: String, timestamp: Long): SmsParseStatus {
        // Fast exit for promotional / marketing messages or credit card bill statement messages
        if (isPromotionalOrAdvertisementMessage(body) || CreditCardBillIngestionEngine.isCreditCardBillMessage("$address $body")) {
            return SmsParseStatus.Ignored
        }

        val lowerBody = body.lowercase(Locale.ENGLISH)

        // A. Ignore Bill Reminders (e.g. contains "Bill of Rs", "due tomorrow", "ignore if paid") (Rule C)
        val isBillReminder = lowerBody.contains("is due tomorrow") ||
            lowerBody.contains("due tomorrow") ||
            lowerBody.contains("due date") ||
            lowerBody.contains("bill of rs") ||
            lowerBody.contains("bill amount") ||
            lowerBody.contains("ignore if paid") ||
            lowerBody.contains("pay now") ||
            lowerBody.contains("bill generated") ||
            lowerBody.contains("statement generated") ||
            lowerBody.contains("minimum due") ||
            lowerBody.contains("total due") ||
            Regex("due\\s+on\\s+\\d+", RegexOption.IGNORE_CASE).containsMatchIn(lowerBody)

        if (isBillReminder) {
            return SmsParseStatus.Ignored
        }

        // 1. Transaction intent validation (Rule 4 & Rule 1: Ignore balance-only/status/limit-only messages)
        val strongTxActionKeywords = listOf(
            "spent", "debited", "debit", "withdrawn", "withdrawal", "paid", "purchased", "purchase",
            "received", "credited", "deposited", "transferred", "transfer", "recharge", "payment",
            "used", "refund", "refunded", "transaction", "txn", "charged", "deducted", "sent", "money sent"
        )
        val hasStrongTxAction = strongTxActionKeywords.any { lowerBody.contains(it) }

        // Also check balance-only/limit-only patterns
        val balanceOnlyIndicators = listOf(
            "new balance", "available balance", "avbl bal", "bal is", "balance is", "ledger balance", "account balance",
            "credit limit", "available limit", "avbl limit", "available credit limit",
            "outstanding amount", "outstanding balance", "statement balance", "outstanding is"
        )
        val containsBalanceInfo = balanceOnlyIndicators.any { lowerBody.contains(it) }

        if (containsBalanceInfo && !hasStrongTxAction) {
            return SmsParseStatus.Ignored
        }

        if (!hasStrongTxAction) {
            return SmsParseStatus.Ignored
        }

        // Ignore OTP, promotional, ads, verification, rewards, loan offers, application tracking, etc.
        val ignoreKeywords = listOf(
            "otp", "one time password", "verification", "verify", "password", "alert:",
            "promotional", "offer", "discount", "win", "won", "scratch card",
            "reward points", "cibil", "pre-approved", "emi reminder",
            "due date", "bill generated", "statement", "login alert", "security code",
            "click here", "missed call", "loan offer", "loan application", "track your application",
            "track application", "request for", "loan request", "loan eligibility", "eligible for loan",
            "loan advisory", "apply for loan", "credit limit offer"
        )
        if (ignoreKeywords.any { lowerBody.contains(it) } &&
            !lowerBody.contains("debited") && !lowerBody.contains("credited") &&
            !lowerBody.contains("paid") && !lowerBody.contains("spent") &&
            !lowerBody.contains("used") && !lowerBody.contains("charged") &&
            !lowerBody.contains("withdrawn") && !lowerBody.contains("withdrawal") &&
            !lowerBody.contains("received") && !lowerBody.contains("refund") &&
            !lowerBody.contains("purchase") && !lowerBody.contains("card transaction")
        ) {
            return SmsParseStatus.Ignored
        }

        // Exclude pure balance SMS with no debit/credit transaction
        if ((lowerBody.contains("balance") || lowerBody.contains("bal")) &&
            !lowerBody.contains("debited") && !lowerBody.contains("debit") &&
            !lowerBody.contains("credited") && !lowerBody.contains("credit") &&
            !lowerBody.contains("sent") && !lowerBody.contains("paid") &&
            !lowerBody.contains("received rs") && !lowerBody.contains("received inr") &&
            !lowerBody.contains("received ₹") && !lowerBody.contains("withdrawn") &&
            !lowerBody.contains("spent") && !lowerBody.contains("deducted") &&
            !lowerBody.contains("transferred") && !lowerBody.contains("used") &&
            !lowerBody.contains("charged") && !lowerBody.contains("purchase") &&
            !lowerBody.contains("withdrawal") && !lowerBody.contains("refund")
        ) {
            return SmsParseStatus.Ignored
        }

        // 2. Determine Transaction Type (EXPENSE vs INCOME)
        val finalType = determineTransactionDirection(body) ?: return SmsParseStatus.Ignored

        // 3. Extract Amount
        val amount = extractAmount(body)
        if (amount <= 0.0) {
            if (containsBalanceInfo || lowerBody.contains("balance") || lowerBody.contains("bal") || lowerBody.contains("limit") || lowerBody.contains("outstanding")) {
                return SmsParseStatus.Ignored
            }
            return SmsParseStatus.ParsingFailed
        }

        // 4. Clean Body from URLs before merchant extraction
        val cleanBodyForMerchant = removeUrls(body)

        // 5. Extract Merchant (Ensures URLs, sender IDs, and random numbers are NEVER used as merchant)
        val merchant = extractMerchant(cleanBodyForMerchant, address)

        // 6. Extract Date and Time
        val (dateStr, timeStr) = extractDateTime(body, timestamp)

        // 7. Extract Last 4 Digits
        val last4 = TransactionIdentityResolver.extractLast4(body)

        // 8. Extract Real Transaction Reference (UTR / UPI Ref / Ref No)
        val realTxRef = extractTransactionReference(body)

        // 9. Category & Subcategory determination
        val categoryId = determineCategory(merchant, finalType, body)
        val subcategoryId = determineSubcategory(categoryId, merchant, body)

        // 10. Account & Payment method matching
        val accountId = determineAccountId(body, address)
        val paymentMethod = TransactionIdentityResolver.resolvePaymentMethod(body)

        val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

        val stableRef = if (realTxRef.isNotEmpty()) realTxRef else "SMS-TX-${System.currentTimeMillis()}-${(1000..9999).random()}"
        val originalRef = if (realTxRef.isNotEmpty()) {
            "SMS-REF-$address-$realTxRef"
        } else {
            "SMS-EVENT-${smsEventFingerprint(address, body, timestamp)}"
        }
        val txId = "tx-sms-" + UUID.randomUUID().toString().substring(0, 8)

        // Support CC bill payment mapping
        val isCcBillPayment = lowerBody.contains("payment towards your credit card") ||
            lowerBody.contains("payment towards your card") ||
            lowerBody.contains("credit card payment") ||
            lowerBody.contains("payment received towards your credit card") ||
            (lowerBody.contains("thank you") && lowerBody.contains("payment") && (lowerBody.contains("credit card") || lowerBody.contains("towards your card"))) ||
            lowerBody.contains("card bill payment") ||
            lowerBody.contains("card bill paid") ||
            lowerBody.contains("credit card bill paid") ||
            lowerBody.contains("paid towards credit card") ||
            lowerBody.contains("select credit card") && lowerBody.contains("payment") ||
            Regex("payment\\s+.*?\\s+towards\\s+.*?card", RegexOption.IGNORE_CASE).containsMatchIn(lowerBody)

        val finalMerchant = if (isCcBillPayment) "Credit Card Bill Payment" else merchant
        val finalCategoryId = if (isCcBillPayment) "cat-cc-bill" else categoryId

        val msgTimestampIso = TransactionIngestionEngine.formatLongToIso(timestamp)

        val tx = TransactionEntity(
            id = txId,
            type = finalType,
            direction = if (finalType == "INCOME") "CREDIT" else "DEBIT",
            amount = amount,
            date = dateStr,
            time = timeStr,
            merchant = finalMerchant,
            categoryId = finalCategoryId,
            subcategoryId = subcategoryId,
            accountId = accountId,
            paymentMethod = paymentMethod,
            note = "",
            source = "SMS",
            transactionReference = stableRef,
            originalReference = originalRef,
            last4Digits = last4,
            createdAt = nowIso,
            updatedAt = msgTimestampIso
        )

        return SmsParseStatus.Success(tx)
    }

    private fun smsEventFingerprint(address: String, body: String, timestamp: Long): String {
        val eventData = "${address.trim().uppercase(Locale.US)}|$timestamp|${body.trim().replace(Regex("\\s+"), " ")}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(eventData.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
    }

    fun removeUrls(text: String): String {
        return text
            .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("www\\.\\S+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("bit\\.ly/\\S+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("goo\\.gl/\\S+", RegexOption.IGNORE_CASE), "")
    }

    private fun extractAmount(body: String): Double {
        // Remove balance, limit, and available figures to avoid false matching balance amounts
        val balanceKeywords = "available\\s*balance|avbl?\\s*bal|avl\\s*bal|current\\s*balance|curr\\s*bal|new\\s*balance|new\\s*bal|closing\\s*balance|ledger\\s*balance|account\\s*balance|credit\\s*limit|available\\s*limit|avbl?\\s*limit|outstanding\\s*amount|outstanding\\s*bal(?:ance)?|statement\\s*balance|balance|bal|limit|outstanding"
        val cleanBody = body
            .replace(Regex("(?:$balanceKeywords)\\s*(?:is|of|to|for|:)?\\s*(?:rs\\.?|inr|₹)?\\s*[\\d,.]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("avbl\\s*bal[\\s\\S]*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("available\\s*balance[\\s\\S]*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("bal(?:ance)?:?\\s*(?:rs|inr|₹)?[\\s\\S]*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("limit\\s*left[\\s\\S]*", RegexOption.IGNORE_CASE), "")

        val regex = Regex("(?:rs\\.?|inr|₹)\\s*([\\d,]+\\.?\\d*)", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(cleanBody).toList()

        for (match in matches) {
            val numStr = match.groupValues[1].replace(",", "")
            val value = numStr.toDoubleOrNull() ?: 0.0
            if (value > 0) {
                return value
            }
        }

        // Fallback pattern: "debited/credited for 135.00" or "spent 135"
        val verbRegex = Regex("(?:debited|credited|spent|paid|withdrawn|purchase(?: of)?|transfer(?: of)?)\\s+(?:for\\s+)?(?:rs\\.?|inr|₹)?\\s*([\\d,]+\\.?\\d*)", RegexOption.IGNORE_CASE)
        val verbMatch = verbRegex.find(cleanBody)
        if (verbMatch != null) {
            val numStr = verbMatch.groupValues[1].replace(",", "")
            val value = numStr.toDoubleOrNull() ?: 0.0
            if (value > 0) return value
        }

        // Fallback generic number with decimal if no Rs/₹ found
        val generalRegex = Regex("([\\d,]+\\.\\d{2})")
        val genMatch = generalRegex.find(cleanBody)
        if (genMatch != null) {
            val numStr = genMatch.groupValues[1].replace(",", "")
            return numStr.toDoubleOrNull() ?: 0.0
        }

        return 0.0
    }

    fun extractTransactionReference(body: String): String {
        val patterns = listOf(
            Regex("\\bupi\\s+ref\\s*(?:no|num|id|number)?\\s*[:.-]?\\s*([A-Za-z0-9]{6,25})\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:rrn|utr)\\s*[:.-]?\\s*([A-Za-z0-9]{6,25})\\b", RegexOption.IGNORE_CASE),
            Regex("(?:ref|reference|rrn|txn|transaction|utr|upi ref)(?:\\s*(?:no|num|id|number))?\\s*[:.-]?\\s*([A-Za-z0-9]{6,25})", RegexOption.IGNORE_CASE),
            Regex("upi/([A-Za-z0-9]{6,25})", RegexOption.IGNORE_CASE),
            Regex("imps/\\w+/([A-Za-z0-9]{6,25})", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val matches = pattern.findAll(body)
            for (match in matches) {
                val candidate = match.groupValues[1].trim()
                if (TransactionIdentityResolver.isValidTransactionReference(candidate)) {
                    return candidate
                }
            }
        }
        return ""
    }

    fun sanitizeMerchantName(merchant: String): String {
        val trimmed = merchant.trim()
        if (trimmed.isEmpty()) return "Unknown Merchant"
        val lower = trimmed.lowercase(Locale.ENGLISH)
        val hexRegex = Regex("^[0-9a-f]{8,24}$")
        val emailIdRegex = Regex("^email\\s+[0-9a-fA-Z]{8,24}$", RegexOption.IGNORE_CASE)
        val emailTxRegex = Regex("^(?:email-tx|tx-email|msg|email-ref)-\\S*$", RegexOption.IGNORE_CASE)

        if (lower.startsWith("email ") && (hexRegex.matches(lower.substring(6).trim()) || lower.substring(6).trim().length in 8..24)) {
            return "Unknown Merchant"
        }
        if (hexRegex.matches(lower) && lower.length >= 8) {
            return "Unknown Merchant"
        }
        if (emailIdRegex.matches(trimmed) || emailTxRegex.matches(trimmed)) {
            return "Unknown Merchant"
        }
        if (lower == "other" || lower == "unknown" || lower == "unknown merchant") {
            return "Unknown Merchant"
        }
        return trimmed
    }

    fun cleanMerchantName(rawMerchant: String, fullText: String = ""): String {
        val mSanitized = sanitizeMerchantName(rawMerchant)
        if (mSanitized == "Unknown Merchant") return "Unknown Merchant"
        var m = mSanitized
        
        // Remove common reference prefixes that aren't part of the merchant name
        val refPrefixes = listOf("UPI/", "VPA/", "RRN:", "UTR:", "REF:", "TXN:", "TRANSFER TO", "PAID TO", "SENT TO")
        for (prefix in refPrefixes) {
            if (m.uppercase(Locale.ENGLISH).startsWith(prefix)) {
                m = m.substring(prefix.length).trim()
            }
        }

        val urlRegex = Regex("https?://(?:www\\.)?([a-zA-Z0-9-]+)\\.(com|in|org|net|co|biz|info)\\b", RegexOption.IGNORE_CASE)
        val urlMatch = urlRegex.find(m)
        if (urlMatch != null) {
            m = urlMatch.groupValues[1]
        } else {
            m = m.replace(Regex("https?://\\S*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("www\\.\\S*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\.(com|in|org|net|co|biz|info)\\b/?\\S*", RegexOption.IGNORE_CASE), "")
        }

        if (m.contains("@") || m.contains("/")) {
            val parts = m.split(Regex("[/@]"))
            val nonTech = parts.filter { part ->
                val p = part.lowercase(Locale.ENGLISH).trim()
                p.length > 1 && p != "upi" && p != "paytm" && p != "gpay" && p != "phonepe" && !p.all { it.isDigit() }
            }
            if (nonTech.isNotEmpty()) {
                m = nonTech.last()
            }
        }

        val prefixesToStrip = listOf(
            "sms from", "sms from:", "sms from-", "sms from -", "paid to", "transfer to", "sent to",
            "at", "to", "from", "vm", "ad", "re:", "re", "fwd:", "fwd", "fw:", "fw",
            "sharing this alert", "sharing alert", "share alert", "view details", "details:", "details"
        )
        val suffixesToStrip = listOf("ke", "ko", "credited", "debited", "via", "upi", "gpay", "phonepe", "paytm", "pos")
        
        var words = m.split("\\s+".toRegex()).toMutableList()
        
        // Strip prefixes
        while (words.isNotEmpty() && prefixesToStrip.contains(words.first().lowercase(Locale.ENGLISH))) {
            words.removeAt(0)
        }
        
        // Strip suffixes (like Hindi template connectors 'ke', 'ko' or leftovers like 'credited')
        while (words.isNotEmpty() && suffixesToStrip.contains(words.last().lowercase(Locale.ENGLISH))) {
            words.removeAt(words.size - 1)
        }

        if (words.isNotEmpty()) {
            m = words.joinToString(" ")
        }

        m = m.replace(Regex("\\b[xX*]+\\d*\\b"), "") // Remove masked numbers like XX1234 or ****
            .replace(Regex("[._-]"), " ")
            .replace(Regex("[^A-Za-z0-9&'\\s-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        m = unconcatenateMerchantName(m)

        val lowerM = m.lowercase(Locale.ENGLISH)
        if (lowerM.isEmpty() || lowerM == "https" || lowerM == "http" || lowerM == "www" || lowerM == "unknown merchant" || lowerM == "other" || lowerM == "transaction" || lowerM == "payment" || lowerM == "bank transfer") {
            return "Unknown Merchant"
        }

        return m.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
            val w = word.lowercase(Locale.ENGLISH)
            when (w) {
                "jio" -> "Jio"
                "vi" -> "Vi"
                "irctc" -> "IRCTC"
                "iocl" -> "Indian Oil"
                "hpcl" -> "HPCL"
                "bpcl" -> "BPCL"
                "kfc" -> "KFC"
                "cred" -> "CRED"
                "upi" -> "UPI"
                else -> {
                    // Keep short acronyms in uppercase, otherwise titlecase
                    val knownAcronyms = listOf("HDFC", "ICICI", "SBI", "PNB", "IDFC", "RRN", "UTR", "UPI", "IMPS", "NEFT", "RTGS", "VPA")
                    if (knownAcronyms.contains(word.uppercase(Locale.ENGLISH))) {
                        word.uppercase(Locale.ENGLISH)
                    } else if (w.length <= 2 && word.all { it.isUpperCase() }) {
                        word.uppercase(Locale.ENGLISH)
                    } else if (w.length <= 2) {
                        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
                    } else {
                        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
                    }
                }
            }
        }
    }

    fun unconcatenateMerchantName(merchant: String): String {
        val trimmed = merchant.trim()
        if (trimmed.isEmpty()) return merchant
        
        if (trimmed.contains(" ")) {
            return trimmed
        }
        
        val commercialTokens = listOf(
            "store", "stores", "corner", "shop", "mart", "general", "kirana", "kiraana",
            "chat", "cafe", "food", "bakery", "sweets", "juice", "tea", "coffee",
            "stationery", "medical", "pharma", "chemist", "restaurant", "hotel", "dhaba",
            "bar", "wine", "books", "hardware", "electricals", "electronics", "mobile",
            "telecom", "fashion", "garments", "shoes", "footwear", "jewellers", "opticals",
            "traders", "enterprise", "enterprises", "agency", "agencies", "associates", "services"
        )
        
        val lower = trimmed.lowercase(Locale.ENGLISH)
        var remaining = lower
        val foundTokens = mutableListOf<String>()
        
        var matchedCount = 0
        while (matchedCount < 3) {
            var matchedToken: String? = null
            for (token in commercialTokens) {
                if (remaining.endsWith(token) && remaining.length > token.length) {
                    val prefix = remaining.substring(0, remaining.length - token.length)
                    if (prefix.length >= 2) {
                        matchedToken = token
                        break
                    }
                }
            }
            if (matchedToken != null) {
                foundTokens.add(matchedToken)
                remaining = remaining.substring(0, remaining.length - matchedToken.length)
                matchedCount++
            } else {
                break
            }
        }
        
        if (foundTokens.isNotEmpty()) {
            val parts = mutableListOf<String>()
            parts.add(remaining)
            parts.addAll(foundTokens.reversed())
            
            return parts.joinToString(" ") { word ->
                val w = word.lowercase(Locale.ENGLISH)
                if (w.length <= 2 && w != "tea") w.uppercase(Locale.ENGLISH)
                else w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
            }
        }
        
        return trimmed
    }

    fun extractMerchantAfterPrepositions(cleanBody: String, address: String): String? {
        val prepositions = listOf(
            Regex(";\\s*([^,.;\\n]+?)\\s+(?:credited|debited)", RegexOption.IGNORE_CASE),
            Regex(";\\s*([^,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("(?:spent|paid|purchased|used)\\s+at\\s+([^,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("spent\\s+on\\s+.*?\\s+at\\s+([^,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("\\bat\\s+([^,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("(?:paid|transfer(?:red)?|sent)\\s+to\\s+([^,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("\\bto\\s+([^,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("payment\\s+to\\s+([^,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("merchant\\s*[:\\-]?\\s*([^,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("\\binfo\\s*[:\\-]?\\s*([^,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("(?:salary|credited|received|transfer(?:red)?|payment|money)\\s+from\\s+([^,.;\\n]+)", RegexOption.IGNORE_CASE)
        )

        val terminalKeywords = listOf(
            "\\bon\\b", "\\bfor\\b", "\\busing\\b", "\\bvia\\b", "\\bthrough\\b", "\\bwith\\b",
            "\\bfrom\\b", "\\bin\\b", "\\bat\\b", "\\bto\\b", "\\bdebited\\b", "\\bcredited\\b",
            "\\bavbl\\b", "\\blimit\\b", "\\bavailable\\b", "\\bbalance\\b", "\\bbal\\b",
            "\\binr\\b", "\\brs\\b", "\\b₹\\b", "\\btowards\\b", "\\bupi\\b", "\\bcard\\b",
            "\\bref\\b", "\\breference\\b", "\\brrn\\b", "\\butr\\b", "\\btxn\\b", "\\btxnid\\b",
            "\\bxx+\\b", "\\bx\\d+\\b", "\\bending\\b", "\\bdated\\b", "\\bon\\s+\\d"
        )

        for (regex in prepositions) {
            val match = regex.find(cleanBody) ?: continue
            var candidate = match.groupValues[1].trim()

            // Truncate candidate at the first occurrence of any terminal keyword
            for (keyword in terminalKeywords) {
                val kwRegex = Regex(keyword, RegexOption.IGNORE_CASE)
                val m = kwRegex.find(candidate)
                if (m != null) {
                    candidate = candidate.substring(0, m.range.first).trim()
                }
            }

            // Remove trailing/leading punctuation/spaces
            candidate = candidate.replace(Regex("^[^A-Za-z0-9]+"), "").replace(Regex("[^A-Za-z0-9]+$"), "").trim()

            if (isValidMerchantCandidate(candidate, address)) {
                val cleaned = cleanMerchantName(candidate, address)
                if (cleaned != "Other" && cleaned != "Unknown Merchant" && cleaned.isNotEmpty()) {
                    return cleaned
                }
            }
        }
        return null
    }

    fun extractMerchant(cleanBody: String, address: String): String {
        val lower = cleanBody.lowercase(Locale.ENGLISH)

        // Rule 2: ATM logic - Strong context required
        val atmPatterns = listOf(
            "cash withdrawal", "cash withdrawn", "atm withdrawal", "atm wdl", 
            "cash transaction at atm", "withdrawal from atm", "withdrawn at atm"
        )
        if (atmPatterns.any { lower.contains(it) }) {
            return "ATM"
        }

        // Known merchants lookup table - CHECK THIS FIRST (with safe word-boundary patterns)
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
            "vodafone" to "Vi",
            "bsnl" to "BSNL",
            "hpcl" to "HPCL",
            "bpcl" to "BPCL",
            "indian oil" to "Indian Oil",
            "iocl" to "Indian Oil",
            "netflix" to "Netflix",
            "spotify" to "Spotify",
            "hotstar" to "Disney+ Hotstar",
            "starbucks" to "Starbucks",
            "mcdonalds" to "McDonald's",
            "mcdonald's" to "McDonald's",
            "dominos" to "Domino's",
            "domino's" to "Domino's",
            "kfc" to "KFC",
            "burger king" to "Burger King",
            "pizza hut" to "Pizza Hut",
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
            val pattern = if (key.all { it.isLetterOrDigit() }) {
                "\\b$key\\b"
            } else {
                "(?:^|\\W)${Regex.escape(key)}(?:$|\\W)"
            }
            if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
                return name
            }
        }

        // 2. Try extracting after explicit prepositions
        val idfcPattern = Regex("debited by (?:rs\\.?|inr|₹)\\s*[\\d,.]+\\s+on\\s+\\d{1,2}/\\d{1,2}/\\d{2,4};\\s*(.+?)\\s+credited", RegexOption.IGNORE_CASE)
        val idfcMatch = idfcPattern.find(cleanBody)
        if (idfcMatch != null) {
            val candidate = idfcMatch.groupValues[1].trim()
            if (isValidMerchantCandidate(candidate, address)) {
                val cleaned = cleanMerchantName(candidate, address)
                if (cleaned != "Other" && cleaned != "Unknown Merchant") {
                    return cleaned
                }
            }
        }

        val prepositionExtracted = extractMerchantAfterPrepositions(cleanBody, address)
        if (prepositionExtracted != null) {
            return prepositionExtracted
        }

        // Try extracting "to [Merchant]" or "at [Merchant]" or "paid to [Merchant]" or "spent at [Merchant]" or "purchase at [Merchant]"
        val toRegex = Regex("(?:paid to|transfer(?:red)? to|sent to|to vpa|at info|info|paid at|spent at|spent on|purchase at|purchase from|at|to|towards)\\s+([A-Za-z0-9_\\-\\.\\s]{2,30})", RegexOption.IGNORE_CASE)
        val matches = toRegex.findAll(cleanBody).toList()
        for (m in matches) {
            var candidate = m.groupValues[1].trim()
            // Truncate candidate at common boundaries
            val stopWords = listOf(" on ", " for ", " using ", " via ", " with ", " from ", " in ", " at ", " to ", " debited ", " credited ", " avbl ", " limit ", " balance ", " inr ", " rs ", " ₹ ", " upi ", " card ", " ending ", " dated ", " ref ", " ref: ", " reference ", " rrn ", " utr ", " txn ")
            for (sw in stopWords) {
                val idx = candidate.lowercase(Locale.ENGLISH).indexOf(sw)
                if (idx > 0) {
                    candidate = candidate.substring(0, idx).trim()
                }
            }
            if (isValidMerchantCandidate(candidate, address)) {
                val cleaned = cleanMerchantName(candidate, address)
                if (cleaned != "Other" && cleaned != "Unknown Merchant" && cleaned.isNotEmpty()) {
                    return cleaned
                }
            }
        }

        return "Unknown Merchant"
    }

    private fun isValidMerchantCandidate(candidate: String, senderAddress: String): Boolean {
        val lower = candidate.lowercase(Locale.ENGLISH).trim()
        val senderLower = senderAddress.lowercase(Locale.ENGLISH)

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

        // Filter out bank/system boilerplate words and specific bad values requested by user
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
        
        // Split candidate into word tokens
        val words = lower.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false

        // If ALL words consist solely of digits, reject
        if (words.all { w -> w.all { char -> char.isDigit() } }) return false
        
        // If any word is exactly one of the major bank names, or common invalid tokens
        val majorBankNames = listOf("hdfc", "sbi", "icici", "axis", "kotak", "idfc", "pnb", "rbl", "yesbank", "bob", "canara")
        if (words.any { majorBankNames.contains(it) }) return false

        // If MORE than 25% of words are invalid tokens, reject it as likely a sentence fragment
        val invalidCount = words.count { invalidTokens.contains(it) }
        if (invalidCount.toFloat() / words.size > 0.25f) return false
        
        // If it contains "commandent", reject it as requested
        if (lower.contains("commandent")) return false

        if (senderLower.contains(lower) && lower.length > 3) return false

        return true
    }

    private fun extractDateTime(body: String, timestamp: Long): Pair<String, String> {
        val date = java.util.Date(timestamp)
        val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

        var extractedDateStr: String? = null
        var extractedTimeStr: String? = null

        // 1. Numeric Date: dd-MM-yy / dd/MM/yyyy, including ISO yyyy-MM-dd.
        try {
            val isoMatch = Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b").find(body)
            if (isoMatch != null) {
                extractedDateStr = strictDate(
                    dateFmt,
                    isoMatch.groupValues[1].toInt(),
                    isoMatch.groupValues[2].toInt() - 1,
                    isoMatch.groupValues[3].toInt()
                )
            } else {
                val dateRegex = Regex("(?:on\\s+|dated\\s+)?(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2,4})", RegexOption.IGNORE_CASE)
                val match = dateRegex.find(body)
                if (match != null) {
                    val d = match.groupValues[1].toInt()
                    val m = match.groupValues[2].toInt()
                    var y = match.groupValues[3].toInt()
                    if (y < 100) y += 2000
                    extractedDateStr = strictDate(dateFmt, y, m - 1, d)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to parse numeric SMS transaction date", e)
        }

        // 2. Named Month Date: dd-MMM-yy / dd-MMM-yyyy / dd MMM yyyy / dd-MMM (e.g., 23-Aug-23, 23 Aug 2026, 23-Aug)
        if (extractedDateStr == null) {
            try {
                val monthRegex = Regex("(?:on\\s+|dated\\s+)?(\\d{1,2})[- ]([A-Za-z]{3})[- ]?(\\d{2,4})?", RegexOption.IGNORE_CASE)
                val monthMatch = monthRegex.find(body)
                if (monthMatch != null) {
                    val d = monthMatch.groupValues[1].toInt()
                    val mStr = monthMatch.groupValues[2].lowercase(Locale.US)
                    val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
                    val m = months.indexOf(mStr)
                    if (m >= 0) {
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
                        val currentYear = cal.get(java.util.Calendar.YEAR)
                        var y = monthMatch.groupValues[3].toIntOrNull() ?: currentYear
                        if (y < 100) y += 2000
                        extractedDateStr = strictDate(dateFmt, y, m, d)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to parse named-month SMS transaction date", e)
            }
        }

        // 3. Time: HH:mm:ss / HH:mm / hh:mm a (e.g. 10:08, 10:08:15, 10:08 AM)
        try {
            val timeRegex = Regex("(?:at\\s+)?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
            val timeMatch = timeRegex.find(body)
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toInt()
                val min = timeMatch.groupValues[2].toInt()
                val ampm = timeMatch.groupValues[4].lowercase(Locale.US)
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                if (hour in 0..23 && min in 0..59) {
                    extractedTimeStr = String.format(Locale.US, "%02d:%02d", hour, min)
                }
            }
        } catch (e: Exception) {
            // fallback
        }

        val finalDate = extractedDateStr ?: dateFmt.format(date)
        val finalTime = extractedTimeStr ?: timeFmt.format(date)
        return Pair(finalDate, finalTime)
    }

    private fun strictDate(
        formatter: java.text.SimpleDateFormat,
        year: Int,
        zeroBasedMonth: Int,
        day: Int
    ): String? {
        val calendar = java.util.Calendar.getInstance().apply {
            isLenient = false
            clear()
            set(year, zeroBasedMonth, day)
        }
        return try {
            formatter.format(calendar.time)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun determineCategory(merchant: String, type: String, body: String): String {
        val lower = (merchant + " " + body).lowercase(Locale.ENGLISH)

        if (type == "INCOME" || lower.contains("refund") || lower.contains("refunded") || lower.contains("reversed")) {
            if (lower.contains("salary") || lower.contains("payroll") || lower.contains("salary credited") || lower.contains("sal-")) return "cat-salary"
            if (lower.contains("refund") || lower.contains("refunded") || lower.contains("reversed")) return "cat-refund"
            if (lower.contains("interest") || lower.contains("fd") || lower.contains("dividend")) return "cat-interest"
            if (lower.contains("business") || lower.contains("client") || lower.contains("invoice")) return "cat-business"
            return "cat-income-other"
        }

        val isUnknownMerchant = merchant.equals("Unknown Merchant", ignoreCase = true) || merchant.isBlank()

        // If unknown merchant, only map to categories with very strong, explicit keyword evidence in the body.
        val targetText = if (isUnknownMerchant) body.lowercase(Locale.ENGLISH) else lower

        return when {
            targetText.contains("atm") || targetText.contains("cash withdrawn") || targetText.contains("cash withdrawal") -> "cat-cash"
            targetText.contains("emi") || targetText.contains("loan") || targetText.contains("bajaj finance") -> "cat-emi"
            
            // Food & Dining / Sweets
            targetText.contains("swiggy") || targetText.contains("zomato") || targetText.contains("mcdonald") || 
            targetText.contains("domino") || targetText.contains("starbuck") || targetText.contains("kfc") || 
            targetText.contains("food") || targetText.contains("restaurant") || targetText.contains("restro") || 
            targetText.contains("cafe") || targetText.contains("dining") || targetText.contains("dhaba") || 
            targetText.contains("sweet") || targetText.contains("sweets") || targetText.contains("pizzeria") || 
            targetText.contains("burger") || targetText.contains("pizza") || targetText.contains("chai") || 
            targetText.contains("coffee") || targetText.contains("tea") || targetText.contains("eat") || 
            targetText.contains("eats") || targetText.contains("kitchen") || targetText.contains("dining") ||
            targetText.contains("bistro") || targetText.contains("bakehouse") || targetText.contains("canteen") ||
            targetText.contains("grill") || targetText.contains("buffet") || targetText.contains("foodcourt") ||
            targetText.contains("confectionery") || targetText.contains("sweet house") || targetText.contains("sweets corner") -> "cat-food"
            
            // Vegetables
            targetText.contains("vegetable") || targetText.contains("vegetables") || targetText.contains("sabzi") || targetText.contains("sabji") -> "cat-vegetables"
            
            // Fruits
            targetText.contains("fruit") || targetText.contains("fruits") || targetText.contains("apple") || 
            targetText.contains("mango") || targetText.contains("banana") || targetText.contains("orange") || 
            targetText.contains("guava") || targetText.contains("coconut") -> "cat-fruits"
            
            // Dairy
            targetText.contains("dairy") || targetText.contains("milk") || targetText.contains("paneer") || 
            targetText.contains("cheese") || targetText.contains("curd") || targetText.contains("butter") || 
            targetText.contains("ghee") || targetText.contains("amul") || targetText.contains("mother dairy") -> "cat-dairy"
            
            // Bakery
            targetText.contains("bakery") || targetText.contains("bake") || targetText.contains("bakes") || 
            targetText.contains("bread") || targetText.contains("biscuit") || targetText.contains("rusk") -> "cat-bakery"
            
            // Groceries (General fallback)
            targetText.contains("blinkit") || targetText.contains("zepto") || targetText.contains("instamart") || 
            targetText.contains("dmart") || targetText.contains("bigbasket") || targetText.contains("big basket") || 
            targetText.contains("grocery") || targetText.contains("groceries") || targetText.contains("supermarket") || 
            targetText.contains("kirana") || targetText.contains("kiraana") || targetText.contains("ration") || 
            targetText.contains("provision") || targetText.contains("mart") || targetText.contains("store") ||
            targetText.contains("grocer") || targetText.contains("super store") || targetText.contains("general store") ||
            targetText.contains("departmental store") || targetText.contains("prov store") || targetText.contains("provisions") -> "cat-groceries"
            
            targetText.contains("uber") || targetText.contains("ola") || targetText.contains("rapido") || 
            targetText.contains("metro") || targetText.contains("taxi") || targetText.contains("auto") || 
            targetText.contains("bus") || targetText.contains("train") || targetText.contains("rail") || 
            targetText.contains("irctc") -> "cat-transport"
            
            targetText.contains("hpcl") || targetText.contains("bpcl") || targetText.contains("indian oil") || 
            targetText.contains("iocl") || targetText.contains("fuel") || targetText.contains("petrol") || 
            targetText.contains("diesel") || targetText.contains("cng") -> "cat-fuel"
            
            targetText.contains("amazon") || targetText.contains("flipkart") || targetText.contains("myntra") || 
            targetText.contains("ajio") || targetText.contains("nykaa") || targetText.contains("meesho") || 
            targetText.contains("shopping") || targetText.contains("clothes") || targetText.contains("electronics") -> "cat-shopping"
            
            NotificationParser.isTelecomRecharge(merchant, body) -> "cat-recharge"
            
            targetText.contains("electricity") || targetText.contains("power") || targetText.contains("bescom") || 
            targetText.contains("water") || targetText.contains("gas") || targetText.contains("bill") || 
            targetText.contains("utility") || targetText.contains("wifi") || targetText.contains("broadband") || 
            targetText.contains("internet") || targetText.contains("telecom") || targetText.contains("insurance") ||
            targetText.contains("recharge") || targetText.contains("premium") -> "cat-bills"
            
            // Repair & Maintenance
            targetText.contains("repair") || targetText.contains("service") || targetText.contains("maintenance") || 
            targetText.contains("mechanic") || targetText.contains("puncture") || targetText.contains("garage") || 
            targetText.contains("hardware") || targetText.contains("plumber") || targetText.contains("carpenter") || 
            targetText.contains("electrician") || targetText.contains("servicing") || targetText.contains("auto care") ||
            targetText.contains("bike care") || targetText.contains("car care") || targetText.contains("motors") ||
            targetText.contains("spares") || targetText.contains("spare parts") -> "cat-repair"
            
            targetText.contains("netflix") || targetText.contains("spotify") || targetText.contains("hotstar") || 
            targetText.contains("bookmyshow") || targetText.contains("pvr") || targetText.contains("inox") || 
            targetText.contains("cinema") || targetText.contains("movie") -> "cat-entertainment"
            
            targetText.contains("apollo") || targetText.contains("pharmeasy") || targetText.contains("1mg") || 
            targetText.contains("hospital") || targetText.contains("clinic") || targetText.contains("doctor") || 
            targetText.contains("med") || targetText.contains("health") || targetText.contains("pharmacy") -> "cat-health"
            
            targetText.contains("school") || targetText.contains("college") || targetText.contains("university") || 
            targetText.contains("fees") || targetText.contains("tuition") || targetText.contains("udemy") || 
            targetText.contains("coursera") || targetText.contains("education") -> "cat-education"
            
            targetText.contains("makemytrip") || targetText.contains("goibibo") || targetText.contains("flight") || 
            targetText.contains("hotel") || targetText.contains("airbnb") || targetText.contains("travel") || 
            targetText.contains("indigo") || targetText.contains("air india") -> "cat-travel"
            
            targetText.contains("rent") || targetText.contains("nobroker") || targetText.contains("house rent") -> "cat-rent"
            
            targetText.contains("salon") || targetText.contains("spa") || targetText.contains("urban company") || 
            targetText.contains("barber") || targetText.contains("personal care") -> "cat-personal"
            
            else -> "cat-other"
        }
    }

    fun determineSubcategory(categoryId: String, merchant: String, body: String): String {
        val lower = (merchant + " " + body).lowercase(Locale.ENGLISH)
        return when (categoryId) {
            "cat-food" -> when {
                lower.contains("swiggy") || lower.contains("zomato") || lower.contains("food delivery") -> "sub-f4"
                lower.contains("starbucks") || lower.contains("coffee") || lower.contains("tea") || lower.contains("cafe") -> "sub-f3"
                lower.contains("mcdonalds") || lower.contains("dominos") || lower.contains("kfc") || lower.contains("burger") || lower.contains("pizza") -> "sub-f2"
                else -> "sub-f1"
            }
            "cat-groceries" -> when {
                lower.contains("blinkit") || lower.contains("zepto") || lower.contains("instamart") || lower.contains("bigbasket") -> "sub-g1"
                lower.contains("vegetable") || lower.contains("sabzi") -> "sub-g2"
                lower.contains("fruit") -> "sub-g3"
                lower.contains("milk") || lower.contains("dairy") -> "sub-g4"
                else -> "sub-g1"
            }
            "cat-transport" -> when {
                lower.contains("metro") -> "sub-t5"
                lower.contains("uber") || lower.contains("ola") || lower.contains("taxi") -> "sub-t4"
                lower.contains("auto") || lower.contains("rapido") -> "sub-t3"
                lower.contains("train") || lower.contains("rail") || lower.contains("irctc") -> "sub-t2"
                else -> "sub-t1"
            }
            "cat-fuel" -> when {
                lower.contains("diesel") -> "sub-fl2"
                lower.contains("cng") -> "sub-fl3"
                else -> "sub-fl1"
            }
            "cat-shopping" -> when {
                lower.contains("amazon") || lower.contains("flipkart") || lower.contains("myntra") || lower.contains("ajio") -> "sub-s4"
                lower.contains("clothes") || lower.contains("wear") || lower.contains("apparel") -> "sub-s1"
                lower.contains("electronics") || lower.contains("gadget") -> "sub-s2"
                else -> "sub-s4"
            }
            "cat-bills" -> when {
                lower.contains("electricity") || lower.contains("power") || lower.contains("bescom") -> "sub-b1"
                lower.contains("water") -> "sub-b2"
                lower.contains("gas") -> "sub-b3"
                lower.contains("wifi") || lower.contains("broadband") || lower.contains("internet") -> "sub-b4"
                lower.contains("mobile") || lower.contains("bill") -> "sub-b5"
                else -> "sub-b5"
            }
            "cat-emi" -> when {
                lower.contains("home") -> "sub-emi1"
                lower.contains("car") || lower.contains("auto") -> "sub-emi2"
                else -> "sub-emi3"
            }
            "cat-cash" -> "sub-cash1"
            else -> ""
        }
    }

    private fun determineAccountId(body: String, address: String): String {
        // Account/card matching is resolved dynamically by TransactionIngestionEngine from Room DB
        return ""
    }
}
