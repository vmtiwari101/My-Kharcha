package com.example.utils

import android.content.Context
import android.util.Log
import com.example.data.dao.KharchaDao
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.TransactionEntity
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class IngestionStatus {
    IMPORTED,
    ENRICHED,
    DUPLICATE,
    IGNORED,
    NEEDS_REVIEW,
    FAILED
}

object TransactionIngestionEngine {

    private const val TAG = "TransactionIngestion"
    private const val LEGACY_OWNER = "legacy:unassigned"
    private val ingestionMutex = Mutex()

    @Volatile
    internal var liveAuthenticatedUidProvider: () -> String? = {
        try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (e: Exception) {
            Log.w(TAG, "Firebase authentication is unavailable", e)
            null
        }
    }

    private fun authenticatedOwner(): String? =
        liveAuthenticatedUidProvider()?.takeIf { it.isNotBlank() && it != LEGACY_OWNER }

    private fun isCurrentOwner(owner: String): Boolean = authenticatedOwner() == owner

    data class ExtractedBalanceInfo(
        val accountBalance: Double? = null,
        val availableLimit: Double? = null,
        val outstandingAmount: Double? = null
    )

    fun parseIsoOrMillisToLong(str: String): Long {
        if (str.isBlank()) return 0L
        val longVal = str.toLongOrNull()
        if (longVal != null && longVal > 0L) return longVal
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(str)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun formatLongToIso(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis))
    }

    /**
     * Extracts balance figures (account balance, available limit, outstanding amount) from financial message text.
     */
    fun extractBalanceInfo(text: String): ExtractedBalanceInfo? {
        if (text.isBlank()) return null

        var accountBalance: Double? = null
        var availableLimit: Double? = null
        var outstandingAmount: Double? = null

        val filler = """(?:\s+(?:in|for|of|on|at|your|to|a/c)\s+[a-zA-Z0-9/#\*-]+(?:\s+(?:a/c|ac|account|card|ending|in|with|no\.?|your)\b)*)*"""
            // 1. Account Balance extraction patterns
        val balancePatterns = listOf(
            Regex("""(?i)\b(?:available\s+bal(?:ance)?|avbl?\s*bal(?:ance)?|avl\s*bal(?:ance)?)$filler\s*(?:is|:|:-|=)?\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)"""),
            Regex("""(?i)\b(?:current\s+bal(?:ance)?|curr\s*bal(?:ance)?)$filler\s*(?:is|:|:-|=)?\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)"""),
            Regex("""(?i)\b(?:new\s+bal(?:ance)?)$filler\s*(?:is|:|:-|=)?\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)"""),
            Regex("""(?i)\b(?:closing\s+bal(?:ance)?)$filler\s*(?:is|:|:-|=)?\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)"""),
            Regex("""(?i)\b(?:ledger\s+bal(?:ance)?)$filler\s*(?:is|:|:-|=)?\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)"""),
            Regex("""(?i)\b(?:account\s+bal(?:ance)?)$filler\s*(?:is|:|:-|=)?\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)"""),
            Regex("""(?i)\b(?:clear\s+bal(?:ance)?)$filler\s*(?:is|:|:-|=)?\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)"""),
            Regex("""(?i)\bbal(?:ance)?\.?(?:\s+(?:is|:|:-|=))?\s*(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d{1,2})?)"""),
            Regex("""(?i)\b(?:available|total|account|avl|avbl|curr|current|new|closing|ledger)\s+bal(?:ance)?\.?\s*[:=-]?\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)""")
        )

        for (p in balancePatterns) {
            val m = p.find(text)
            if (m != null) {
                val numStr = m.groupValues[1].replace(",", "")
                val parsed = numStr.toDoubleOrNull()
                if (parsed != null && parsed >= 0.0) {
                    accountBalance = parsed
                    break
                }
            }
        }

        // 2. Available Credit / Limit extraction patterns
        val limitPatterns = listOf(
            Regex("""(?i)\b(?:available\s+credit\s*limit|available\s*limit|avbl?\s*limit|avl\s*limit|available\s*credit)\s*(?:is|:|:-|=)?\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)"""),
            Regex("""(?i)\b(?:credit\s*limit)\s*(?:is|:|:-|=)?\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)""")
        )

        for (p in limitPatterns) {
            val m = p.find(text)
            if (m != null) {
                val numStr = m.groupValues[1].replace(",", "")
                val parsed = numStr.toDoubleOrNull()
                if (parsed != null && parsed >= 0.0) {
                    availableLimit = parsed
                    break
                }
            }
        }

        // 3. Outstanding Amount extraction patterns
        val outstandingPatterns = listOf(
            Regex("""(?i)\b(?:current\s+outstanding|total\s*outstanding|outstanding\s*bal(?:ance)?|outstanding)\s*(?:is|:|:-|=)?\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)""")
        )

        for (p in outstandingPatterns) {
            val m = p.find(text)
            if (m != null) {
                val numStr = m.groupValues[1].replace(",", "")
                val parsed = numStr.toDoubleOrNull()
                if (parsed != null && parsed >= 0.0) {
                    outstandingAmount = parsed
                    break
                }
            }
        }

        if (accountBalance == null && availableLimit == null && outstandingAmount == null) {
            return null
        }

        return ExtractedBalanceInfo(
            accountBalance = accountBalance,
            availableLimit = availableLimit,
            outstandingAmount = outstandingAmount
        )
    }

    /**
     * Safely applies extracted balance updates to the resolved AccountEntity/CardEntity.
     */
    suspend fun applyExtractedBalance(
        dao: KharchaDao,
        accountId: String?,
        cardId: String?,
        balanceInfo: ExtractedBalanceInfo,
        messageTimestamp: Long
    ) {
        val owner = authenticatedOwner() ?: return
        applyExtractedBalanceForOwner(dao, accountId, cardId, balanceInfo, messageTimestamp, owner)
    }

    private suspend fun applyExtractedBalanceForOwner(
        dao: KharchaDao,
        accountId: String?,
        cardId: String?,
        balanceInfo: ExtractedBalanceInfo,
        messageTimestamp: Long,
        owner: String
    ) = withContext(Dispatchers.IO) {
        if (accountId.isNullOrEmpty() || !isCurrentOwner(owner)) return@withContext

        val accounts = dao.getAllAccountsSyncForUser(owner)
        val targetAcc = accounts.find { it.id == accountId } ?: return@withContext
        if (targetAcc.userId != owner) return@withContext
        val targetCard = cardId?.let { id ->
            dao.getAllCardsSyncForUser(owner)
                .firstOrNull { it.id == id && it.accountId == targetAcc.id && it.userId == owner }
        }
        if (cardId != null && targetCard == null) {
            Log.w(TAG, "Balance update rejected because the card is not owned by the authenticated user or account")
            return@withContext
        }

        // CASH SAFETY: Bank/card SMS balance must NEVER update generic Cash account
        if (targetAcc.type.equals("Cash", ignoreCase = true) || targetAcc.name.equals("Cash", ignoreCase = true) || targetAcc.id == "acc-cash") {
            Log.d(TAG, "Balance update skipped: target account is Cash")
            return@withContext
        }

        // NEWER BALANCE MUST PROTECT AGAINST OLDER BALANCE
        val existingTime = parseIsoOrMillisToLong(targetAcc.updatedAt)
        if (messageTimestamp > 0 && existingTime > 0 && messageTimestamp < existingTime) {
            Log.d(TAG, "Balance update skipped: message timestamp ($messageTimestamp) is older than existing account balance timestamp ($existingTime)")
            return@withContext
        }

        val nowIso = if (messageTimestamp > 0) formatLongToIso(messageTimestamp) else getNowIsoString()

        val isCreditCardAcc = targetAcc.type.equals("Credit Card", ignoreCase = true) || cardId != null
        if (isCreditCardAcc) {
            var updatedOutstanding = targetAcc.outstandingAmount
            var updatedCreditLimit = targetAcc.creditLimit

            if (balanceInfo.outstandingAmount != null) {
                updatedOutstanding = balanceInfo.outstandingAmount
            } else if (balanceInfo.accountBalance != null) {
                updatedOutstanding = balanceInfo.accountBalance
            }

            if (balanceInfo.availableLimit != null) {
                if (updatedCreditLimit > 0.0) {
                    updatedOutstanding = maxOf(0.0, updatedCreditLimit - balanceInfo.availableLimit)
                } else {
                    updatedCreditLimit = balanceInfo.availableLimit
                }
            }

            // Calculate anchored initialBalance (representing Initial Debt) for Credit Cards.
            // This ensures consistency between the stored balance and the calculated balance shown in the UI.
            val allTransactions = dao.getAllTransactionsSyncForUser(owner)
            val accTxs = allTransactions.filter { tx ->
                TransactionIdentityResolver.isCreditCardPurchase(tx, cardId, targetAcc.id, targetAcc.last4Digits) ||
                TransactionIdentityResolver.isCreditCardPaymentOrRefund(tx, cardId, targetAcc.id, targetAcc.last4Digits)
            }
            val expenseTotal = accTxs.filter { TransactionIdentityResolver.isCreditCardPurchase(it, cardId, targetAcc.id, targetAcc.last4Digits) }.sumOf { it.amount }
            val paymentTotal = accTxs.filter { TransactionIdentityResolver.isCreditCardPaymentOrRefund(it, cardId, targetAcc.id, targetAcc.last4Digits) }.sumOf { it.amount }
            val netTxSum = expenseTotal - paymentTotal
            val newInitialBalance = updatedOutstanding - netTxSum

            val updatedAcc = targetAcc.copy(
                outstandingAmount = updatedOutstanding,
                creditLimit = updatedCreditLimit,
                initialBalance = newInitialBalance,
                updatedAt = nowIso
            )
            if (!isCurrentOwner(owner)) return@withContext
            val updatedCard = targetCard?.copy(
                    outstandingAmount = updatedOutstanding,
                    creditLimit = updatedCreditLimit,
                    updatedAt = nowIso
                )
            val includedPayments = accTxs.filter { transaction ->
                TransactionIdentityResolver.isCreditCardPaymentOrRefund(
                    transaction,
                    cardId,
                    targetAcc.id,
                    targetAcc.last4Digits
                )
            }.map { it.id }
            if (!dao.applyCardBillSnapshot(
                    userId = owner,
                    account = updatedAcc,
                    card = updatedCard,
                    includedPaymentIds = includedPayments,
                    targetIds = listOfNotNull(targetAcc.id, targetCard?.id)
                )
            ) {
                Log.w(TAG, "Card balance snapshot rejected because ownership changed")
                return@withContext
            }
            Log.d(TAG, "Updated Credit Card balance for ${targetAcc.name}: outstanding=$updatedOutstanding, limit=$updatedCreditLimit")
        } else {
            // Bank Account / Debit / UPI
            val targetBalance = balanceInfo.accountBalance
            if (targetBalance != null) {
                val allTransactions = dao.getAllTransactionsSyncForUser(owner)
                // Replicate filtering logic from AccountsScreen.kt to ensure consistency between balance anchor and UI display.
                // This includes both resolved transactions and those that match by last4 digits for this account.
                var netTxSum = 0.0
                allTransactions.forEach { tx ->
                    val transferEffect = TransactionIdentityResolver.internalTransferBalanceEffect(
                        tx,
                        accountId,
                        allTransactions
                    )
                    if (transferEffect != null) {
                        netTxSum += transferEffect
                    } else {
                        val isDebit = tx.direction == "DEBIT" || tx.type == "EXPENSE"
                        val isCredit = tx.direction == "CREDIT" || tx.type == "INCOME"
                        if (tx.accountId == accountId) {
                            if (isCredit) netTxSum += tx.amount
                            else if (isDebit) netTxSum -= tx.amount
                        } else if (tx.counterpartyAccountId == accountId) {
                            netTxSum += tx.amount
                        }
                    }
                }
                val newInitialBalance = targetBalance - netTxSum
                val updatedAcc = targetAcc.copy(
                    initialBalance = newInitialBalance,
                    updatedAt = nowIso
                )
                if (!isCurrentOwner(owner)) return@withContext
                dao.insertAccount(updatedAcc)
                Log.d(TAG, "Updated Bank Account balance for ${targetAcc.name}: targetBalance=$targetBalance, newInitialBalance=$newInitialBalance")
            }
        }
    }

    private fun getNowIsoString(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    /**
     * Normalizes a reference ID into a clean alphanumeric string.
     * Returns empty string if the reference is empty, blank, or an internal placeholder.
     */
    fun extractNormalizedReference(rawRef: String?): String {
        if (rawRef.isNullOrBlank()) return ""
        var ref = rawRef.trim().uppercase(Locale.US)

        // Filter out generated random placeholders
        if (ref.startsWith("SMS-TX-") ||
            ref.startsWith("NOTIF-TX-") ||
            ref.startsWith("EMAIL-TX-") ||
            (ref.startsWith("SMS-") && !ref.startsWith("SMS-REF-")) ||
            (ref.startsWith("NOTIF-") && !ref.startsWith("NOTIF-REF-")) ||
            (ref.startsWith("EMAIL-") && !ref.startsWith("EMAIL-REF-")) ||
            ref.startsWith("TX-TRANSFER-") ||
            ref.startsWith("COUNTERPART-") ||
            ref.startsWith("TG-") ||
            ref.startsWith("REC-") ||
            ref.startsWith("TX-")
        ) {
            return ""
        }

        // Strip prefixes: SMS-REF-, NOTIF-REF-, EMAIL-REF-, ORIG-, REF-, UTR, UPI, IMPS, RRN, TXN, etc.
        val prefixes = listOf(
            Regex("^SMS-REF-?", RegexOption.IGNORE_CASE),
            Regex("^NOTIF-REF-?", RegexOption.IGNORE_CASE),
            Regex("^EMAIL-REF-?", RegexOption.IGNORE_CASE),
            Regex("^ORIG-?", RegexOption.IGNORE_CASE),
            Regex("^REF[:-]?", RegexOption.IGNORE_CASE),
            Regex("^UTR[:/-]?", RegexOption.IGNORE_CASE),
            Regex("^RRN[:/-]?", RegexOption.IGNORE_CASE),
            Regex("^TXN[:/-]?", RegexOption.IGNORE_CASE),
            Regex("^UPI[:/-]?", RegexOption.IGNORE_CASE),
            Regex("^IMPS[:/-]?", RegexOption.IGNORE_CASE),
            Regex("^NEFT[:/-]?", RegexOption.IGNORE_CASE)
        )

        var changed = true
        while (changed) {
            val before = ref
            for (pattern in prefixes) {
                ref = ref.replace(pattern, "")
            }
            changed = (ref != before)
        }

        // Clean to alphanumeric
        val alphanumeric = ref.replace("[^A-Z0-9]".toRegex(), "")

        if (!TransactionIdentityResolver.isValidTransactionReference(alphanumeric)) {
            return ""
        }

        // Valid reference IDs in Indian banking/UPI/cards are usually 4 to 30 alphanumeric characters and not all zeroes
        if (alphanumeric.length >= 4 && !alphanumeric.matches("0+".toRegex())) {
            return alphanumeric
        }

        return ""
    }

    /**
     * Extracts all valid normalized reference keys from a transaction (checking both transactionReference and originalReference).
     */
    fun getTransactionReferenceKeys(tx: TransactionEntity): Set<String> {
        val keys = mutableSetOf<String>()
        val ref1 = extractNormalizedReference(tx.transactionReference)
        if (ref1.isNotEmpty()) keys.add(ref1)
        val ref2 = extractNormalizedReference(tx.originalReference)
        if (ref2.isNotEmpty()) keys.add(ref2)
        val ref3 = extractNormalizedReference(tx.referenceId)
        if (ref3.isNotEmpty()) keys.add(ref3)
        val ref4 = extractNormalizedReference(tx.transactionId)
        if (ref4.isNotEmpty()) keys.add(ref4)
        return keys
    }

    private fun sourceEventKey(tx: TransactionEntity): String? {
        val source = tx.source.trim().uppercase(Locale.US)
        val original = tx.originalReference.trim()
        val eventId = when (source) {
            "SMS" -> if (original.startsWith("SMS-EVENT-", ignoreCase = true)) {
                original.substringAfter("SMS-EVENT-").takeIf { it.isNotBlank() }
            } else {
                Regex("^SMS-(?!REF-)([^-]+)-", RegexOption.IGNORE_CASE)
                    .find(original)?.groupValues?.getOrNull(1)
            }
            "EMAIL" -> original.takeIf { it.startsWith("EMAIL-", ignoreCase = true) }
                ?.substringAfter('-', "")
            "NOTIFICATION" -> original.takeIf { it.startsWith("NOTIF-EVENT-", ignoreCase = true) }
                ?.substringAfter("NOTIF-EVENT-", "")
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedId = eventId.uppercase(Locale.US).replace("[^A-Z0-9]".toRegex(), "")
        return normalizedId.takeIf { it.isNotEmpty() }?.let { "$source:$it" }
    }

    /**
     * Calculates a fingerprint only from transaction identity evidence, never from amount/date/card details.
     */
    fun calculateFingerprint(amount: Double, date: String, last4: String, ref: String, direction: String = "DEBIT"): String {
        val cleanRef = extractNormalizedReference(ref)
        if (cleanRef.isNotEmpty()) {
            return "ref-$cleanRef"
        }
        return ""
    }

    /**
     * Represents the confidence score and decision for matching two transactions as duplicates.
     */
    data class MatchConfidence(
        val score: Int,
        val isMatch: Boolean,
        val isHardMismatch: Boolean,
        val reasoning: String
    )

    /**
     * Checks if two merchant names are compatible across different sources or worder variants.
     */
    fun isMerchantCompatible(m1: String, m2: String): Boolean {
        val c1 = m1.trim().lowercase(Locale.US)
        val c2 = m2.trim().lowercase(Locale.US)

        if (c1.isEmpty() || c2.isEmpty()) return true
        val genericList = listOf(
            "unknown merchant", "other", "general", "transaction", "payment", 
            "bank transfer", "notification from app", "credit card bill payment", 
            "internal transfer", "card payment"
        )
        if (genericList.contains(c1) || genericList.contains(c2)) return true
        if (c1 == c2) return true

        // Strip business suffixes
        val norm1 = c1.replace(Regex("\\b(india|pvt|ltd|limited|online|store|app|pay|pos|official|services)\\b"), "").trim()
        val norm2 = c2.replace(Regex("\\b(india|pvt|ltd|limited|online|store|app|pay|pos|official|services)\\b"), "").trim()
        if (norm1.isNotEmpty() && norm2.isNotEmpty() && (norm1 == norm2 || norm1.contains(norm2) || norm2.contains(norm1))) {
            return true
        }

        return false
    }

    /**
     * Evaluates a confidence score (0 to 100) for whether [candidate] is a duplicate of [existing].
     */
    fun evaluateMatchConfidence(candidate: TransactionEntity, existing: TransactionEntity): MatchConfidence {
        if (candidate.userId.isNotBlank() && existing.userId.isNotBlank() && candidate.userId != existing.userId) {
            return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Different transaction owners")
        }
        val candIsDebit = candidate.direction?.equals("DEBIT", ignoreCase = true) == true ||
                (candidate.direction?.equals("CREDIT", ignoreCase = true) != true && candidate.type.equals("EXPENSE", ignoreCase = true))
        val existIsDebit = existing.direction?.equals("DEBIT", ignoreCase = true) == true ||
                (existing.direction?.equals("CREDIT", ignoreCase = true) != true && existing.type.equals("EXPENSE", ignoreCase = true))

        if (candIsDebit != existIsDebit) {
            return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Direction mismatch")
        }

        if (Math.abs(candidate.amount - existing.amount) >= 0.01) {
            return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Amount mismatch")
        }

        val candidateKind = transactionIdentityKind(candidate)
        val existingKind = transactionIdentityKind(existing)
        if (hasConflictingSpecificKinds(candidateKind, existingKind)) {
            return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Transaction type mismatch")
        }

        // A source event ID is useful for retry idempotency, but is deliberately source-qualified.
        val candidateEvent = sourceEventKey(candidate)
        if (candidateEvent != null && candidateEvent == sourceEventKey(existing)) {
            return MatchConfidence(100, isMatch = true, isHardMismatch = false, "Same source event ID")
        }

        // Normalized payment references are source-independent evidence for cross-source merging.
        val candRefs = getTransactionReferenceKeys(candidate)
        val existRefs = getTransactionReferenceKeys(existing)
        if (candRefs.isNotEmpty() && existRefs.isNotEmpty()) {
            val commonRefs = candRefs.intersect(existRefs)
            if (commonRefs.isNotEmpty()) {
                return MatchConfidence(100, isMatch = true, isHardMismatch = false, "Matched transaction reference: $commonRefs")
            } else {
                return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Conflicting reference IDs: $candRefs vs $existRefs")
            }
        }

        return MatchConfidence(
            score = 0,
            isMatch = false,
            isHardMismatch = false,
            reasoning = "No shared transaction identity evidence"
        )
    }

    private fun transactionIdentityKind(tx: TransactionEntity): String {
        val type = (tx.transactionType ?: tx.type).uppercase(Locale.US)
        return when {
            type.contains("CARD_PAYMENT") || type.contains("CREDIT_CARD_BILL") -> "CARD_PAYMENT"
            tx.isInternalTransfer || type.contains("TRANSFER") -> "TRANSFER"
            type.contains("CASH_WITHDRAWAL") -> "CASH_WITHDRAWAL"
            else -> if (tx.direction.equals("CREDIT", ignoreCase = true) || tx.type.equals("INCOME", ignoreCase = true)) {
                "INCOME"
            } else {
                "EXPENSE"
            }
        }
    }

    private fun hasConflictingSpecificKinds(first: String, second: String): Boolean {
        val genericKinds = setOf("EXPENSE", "INCOME")
        return first != second && first !in genericKinds && second !in genericKinds
    }

    /**
     * Determines whether [candidate] is a duplicate of [existing].
     * Reference ID is the highest priority key across all sources.
     */
    fun isDuplicateTransaction(candidate: TransactionEntity, existing: TransactionEntity): Boolean {
        return evaluateMatchConfidence(candidate, existing).isMatch
    }

    /**
     * Calculates the priority score of a transaction's account/card mapping evidence
     * based on real database records (AccountEntity / CardEntity) to ensure strong card evidence
     * always overrides generic fallback accounts during cross-source deduplication.
     */
    fun calculateIdentityPriority(
        tx: TransactionEntity,
        accounts: List<AccountEntity>,
        cards: List<CardEntity>
    ): Int {
        // 1. Check if linked cardId exists in cards table
        if (!tx.cardId.isNullOrEmpty()) {
            val card = cards.find { it.id == tx.cardId }
            if (card != null) {
                val parentAcc = accounts.find { it.id == card.accountId }
                val isCc = card.type.contains("Credit", true) || parentAcc?.type.equals("Credit Card", true)
                if (isCc && card.last4Digits.length == 4) return 100 // Priority 1/2: Registered CardEntity
            }
        }

        // 2. Check if linked accountId exists in accounts table
        if (tx.accountId.isNotEmpty()) {
            val acc = accounts.find { it.id == tx.accountId }
            if (acc != null) {
                val isCc = acc.type.equals("Credit Card", true) || acc.name.contains("Card", true)
                val has4DigitLast4 = acc.last4Digits.length == 4 && acc.last4Digits.all { it.isDigit() }
                if (isCc && has4DigitLast4) return 90 // Priority 3: Credit Card Account in DB with 4-digit last4
                if (!isCc && has4DigitLast4 && !acc.isDefault) return 70 // Priority 4/5: Specific Bank Account in DB
                if (!isCc && acc.isDefault && !tx.needsReview) return 30 // Explicit default bank account match
                if (!isCc && acc.isDefault) return 10 // Fallback default account assignment
            }
        }

        // 3. Check if transaction itself has a valid 4-digit last4
        val txLast4 = tx.last4Digits.trim()
        val isValid4Last4 = txLast4.length == 4 && txLast4.all { it.isDigit() }
        if (isValid4Last4) {
            val noteLower = "${tx.note} ${tx.paymentMethod} ${tx.merchant}".lowercase(Locale.ENGLISH)
            if (noteLower.contains("credit card") || noteLower.contains("card") || tx.paymentMethod.equals("Credit Card", true)) {
                return 80 // Explicit Credit Card evidence on transaction
            }
            return 50 // Specific last4 evidence on transaction
        }

        return 0 // Generic / unknown
    }

    /**
     * Merges metadata from [incoming] into [existing], preserving the most complete record.
     * Uses real database entity scores to ensure stronger credit card evidence overrides old fallback accounts.
     */
    fun mergeTransactionMetadata(
        existing: TransactionEntity,
        incoming: TransactionEntity,
        accounts: List<AccountEntity> = emptyList(),
        cards: List<CardEntity> = emptyList()
    ): TransactionEntity {
        // Merchant
        val mergedMerchant = when {
            incoming.merchant.isNotEmpty() && !incoming.merchant.equals("Unknown Merchant", ignoreCase = true) &&
                    (existing.merchant.isEmpty() || existing.merchant.equals("Unknown Merchant", ignoreCase = true) || 
                     existing.merchant.equals("Stationarycafe", ignoreCase = true) || 
                     (incoming.merchant.contains("&") && !existing.merchant.contains("&"))) -> incoming.merchant
            existing.merchant.isNotEmpty() && !existing.merchant.equals("Unknown Merchant", ignoreCase = true) -> existing.merchant
            incoming.merchant.isNotEmpty() -> incoming.merchant
            else -> existing.merchant
        }

        // Category & Subcategory
        val mergedCategoryId = when {
            existing.categoryId.isNotEmpty() && existing.categoryId != "cat-other" -> existing.categoryId
            incoming.categoryId.isNotEmpty() && incoming.categoryId != "cat-other" -> incoming.categoryId
            existing.categoryId.isNotEmpty() -> existing.categoryId
            else -> incoming.categoryId
        }
        val mergedSubcategoryId = when {
            existing.subcategoryId.isNotEmpty() -> existing.subcategoryId
            else -> incoming.subcategoryId
        }

        // Calculate account identity evidence scores using real database data
        val existingPriority = calculateIdentityPriority(existing, accounts, cards)
        val incomingPriority = calculateIdentityPriority(incoming, accounts, cards)
        val useIncomingIdentity = incomingPriority > existingPriority

        // Intelligently merge Type and Direction: Priority for INTERNAL_TRANSFER and CREDIT_CARD_BILL_PAYMENT
        val isIncomingInternal = incoming.isInternalTransfer || incoming.type == "INTERNAL_TRANSFER" || incoming.transactionType == "INTERNAL_TRANSFER"
        val isExistingInternal = existing.isInternalTransfer || existing.type == "INTERNAL_TRANSFER" || existing.transactionType == "INTERNAL_TRANSFER"
        val isIncomingCcBill = incoming.transactionType == "CREDIT_CARD_BILL_PAYMENT" || incoming.transactionType == "CARD_PAYMENT"
        val isExistingCcBill = existing.transactionType == "CREDIT_CARD_BILL_PAYMENT" || existing.transactionType == "CARD_PAYMENT"

        val mergedType = when {
            isIncomingCcBill || isExistingCcBill -> "INTERNAL_TRANSFER"
            isIncomingInternal && !isExistingInternal -> incoming.type
            isExistingInternal -> existing.type
            else -> if (useIncomingIdentity) incoming.type else existing.type
        }

        val mergedTransactionType = when {
            isIncomingCcBill || isExistingCcBill -> "CARD_PAYMENT"
            isIncomingInternal && !isExistingInternal -> incoming.transactionType
            isExistingInternal -> existing.transactionType
            else -> if (useIncomingIdentity) incoming.transactionType else existing.transactionType
        }

        val mergedIsInternal = isIncomingInternal || isExistingInternal || isIncomingCcBill || isExistingCcBill
        val mergedDirection = if (mergedIsInternal) {
            if (incoming.direction == "DEBIT" || existing.direction == "DEBIT") "DEBIT"
            else incoming.direction ?: existing.direction
        } else {
            if (useIncomingIdentity) incoming.direction ?: existing.direction else existing.direction ?: incoming.direction
        }

        var mergedAccountId = if (useIncomingIdentity && incoming.accountId.isNotEmpty()) {
            incoming.accountId
        } else {
            existing.accountId.ifEmpty { incoming.accountId }
        }

        var mergedCardId = if (useIncomingIdentity) {
            incoming.cardId ?: existing.cardId
        } else {
            existing.cardId ?: incoming.cardId
        }

        var mergedCounterpartyId = existing.counterpartyAccountId ?: incoming.counterpartyAccountId
        var mergedTransferGroupId = existing.transferGroupId ?: incoming.transferGroupId

        // For internal transfers and credit card bill payments, resolve source bank account vs destination credit card
        if (mergedIsInternal || mergedTransactionType == "CREDIT_CARD_BILL_PAYMENT") {
            val accA = accounts.find { it.id == existing.accountId }
            val accB = accounts.find { it.id == incoming.accountId }
            val isAccACreditCard = accA?.type?.contains("Card", ignoreCase = true) == true
            val isAccBCreditCard = accB?.type?.contains("Card", ignoreCase = true) == true

            if (isAccACreditCard && accB != null && !isAccBCreditCard) {
                mergedAccountId = accB.id
                mergedCounterpartyId = accA.id
                mergedCardId = cards.find { it.accountId == accA.id }?.id ?: existing.cardId ?: incoming.cardId
            } else if (isAccBCreditCard && accA != null && !isAccACreditCard) {
                mergedAccountId = accA.id
                mergedCounterpartyId = accB.id
                mergedCardId = cards.find { it.accountId == accB.id }?.id ?: incoming.cardId ?: existing.cardId
            }
            if (mergedTransferGroupId == null) {
                mergedTransferGroupId = "tg-internal-" + UUID.randomUUID().toString().substring(0, 8)
            }
        }

        val finalMergedMerchant = if (mergedIsInternal || mergedTransactionType == "CARD_PAYMENT" || mergedTransactionType == "CREDIT_CARD_BILL_PAYMENT") {
            if (mergedTransactionType == "CARD_PAYMENT" || mergedTransactionType == "CREDIT_CARD_BILL_PAYMENT" || isIncomingCcBill || isExistingCcBill) {
                "Credit Card Bill Payment"
            } else {
                "Internal Transfer"
            }
        } else {
            mergedMerchant
        }

        val mergedLast4 = if (useIncomingIdentity && incoming.last4Digits.isNotEmpty()) {
            incoming.last4Digits
        } else {
            existing.last4Digits.ifEmpty { incoming.last4Digits }
        }

        val mergedPaymentMethod = if (useIncomingIdentity && incoming.paymentMethod.isNotEmpty()) {
            incoming.paymentMethod
        } else {
            val existingPayment = existing.paymentMethod
            val incomingPayment = incoming.paymentMethod
            
            // Priority for UPI if both exist
            if (incomingPayment.equals("UPI", true) || existingPayment.equals("UPI", true)) "UPI"
            else existingPayment.ifEmpty { incomingPayment }
        }

        val mergedNeedsReview = if (mergedIsInternal && mergedCounterpartyId != null) {
            false
        } else if (useIncomingIdentity) {
            incoming.needsReview
        } else {
            existing.needsReview && incoming.needsReview
        }

        // Reference IDs
        val existingRefNorm = extractNormalizedReference(existing.transactionReference)
        val incomingRefNorm = extractNormalizedReference(incoming.transactionReference)
        val bestRef = when {
            existingRefNorm.isNotEmpty() -> existing.transactionReference
            incomingRefNorm.isNotEmpty() -> incoming.transactionReference
            existing.transactionReference.isNotEmpty() -> existing.transactionReference
            else -> incoming.transactionReference
        }

        val existingOrigNorm = extractNormalizedReference(existing.originalReference)
        val incomingOrigNorm = extractNormalizedReference(incoming.originalReference)
        val bestOrigRef = when {
            existingOrigNorm.isNotEmpty() -> existing.originalReference
            incomingOrigNorm.isNotEmpty() -> incoming.originalReference
            existing.originalReference.isNotEmpty() -> existing.originalReference
            else -> incoming.originalReference
        }

        // Notes
        val mergedNote = when {
            existing.note.isEmpty() -> incoming.note
            incoming.note.isEmpty() || existing.note.contains(incoming.note, ignoreCase = true) -> existing.note
            else -> "${existing.note} • ${incoming.note}"
        }

        return existing.copy(
            type = mergedType,
            transactionType = mergedTransactionType,
            direction = mergedDirection,
            isInternalTransfer = mergedIsInternal,
            isExpense = if (mergedIsInternal) false else (mergedType == "EXPENSE"),
            merchant = finalMergedMerchant,
            categoryId = if (mergedIsInternal) "cat-transfer" else mergedCategoryId,
            subcategoryId = mergedSubcategoryId,
            accountId = mergedAccountId,
            cardId = mergedCardId,
            counterpartyAccountId = mergedCounterpartyId,
            transferGroupId = mergedTransferGroupId,
            last4Digits = mergedLast4,
            last4 = mergedLast4,
            paymentMethod = mergedPaymentMethod,
            transactionReference = bestRef,
            originalReference = bestOrigRef,
            note = mergedNote,
            needsReview = mergedNeedsReview,
            updatedAt = getNowIsoString()
        )
    }

    /**
     * Ingests a parsed transaction from any source (SMS, Notification, Email, or Manual).
     * Prevents duplicates across all sources and merges missing metadata.
     */
    suspend fun ingestTransaction(
        context: Context,
        rawTx: TransactionEntity,
        rawText: String,
        expectedOwnerUid: String? = null
    ): Pair<TransactionEntity?, IngestionStatus> = ingestionMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
            val owner = authenticatedOwner()
            if (owner == null) {
                Log.w(TAG, "Ingestion rejected because no live authenticated Firebase user is available")
                return@withContext Pair(null, IngestionStatus.FAILED)
            }
            if (expectedOwnerUid != null &&
                (expectedOwnerUid.isBlank() || expectedOwnerUid == LEGACY_OWNER || expectedOwnerUid != owner)
            ) {
                Log.w(TAG, "Ingestion rejected because the live owner does not match the expected SMS owner")
                return@withContext Pair(null, IngestionStatus.FAILED)
            }
            if (rawTx.userId.isNotBlank() && rawTx.userId != owner) {
                Log.w(TAG, "Ingestion rejected because the transaction belongs to a different user")
                return@withContext Pair(null, IngestionStatus.FAILED)
            }

            // Guard against promotional / marketing / advertisement messages
            if (SmsParser.isPromotionalOrAdvertisementMessage(rawText) || (rawTx.note.isNotBlank() && SmsParser.isPromotionalOrAdvertisementMessage(rawTx.note))) {
                Log.d(TAG, "Ingestion Skipped: Message detected as promotional/advertisement (${rawTx.amount})")
                return@withContext Pair(null, IngestionStatus.IGNORED)
            }

            val db = AppDatabase.getDatabase(context)
            val dao = db.kharchaDao()
            
            val countBefore = dao.getAllTransactionsSyncForUser(owner).size
            Log.d(TAG, "Ingestion Start: Room count before = $countBefore")
            Log.d(TAG, "Processing SMS/Source: ${rawTx.source}, Amount: ${rawTx.amount}, Date: ${rawTx.date}")

            val existingTxs = dao.getAllTransactionsSyncForUser(owner).filter { it.userId == owner }
            existingTxs.firstOrNull { it.id == rawTx.id }?.let { existing ->
                if (!isCurrentOwner(owner)) return@withContext Pair(null, IngestionStatus.FAILED)
                return@withContext Pair(existing, IngestionStatus.DUPLICATE)
            }
            val accounts = dao.getAllAccountsSyncForUser(owner).filter { it.userId == owner }
            val cards = dao.getAllCardsSyncForUser(owner).filter { it.userId == owner }

            val textLower = rawText.lowercase(Locale.ENGLISH)

            // Direction Determinations (Debit vs Credit)
            val direction = rawTx.direction
                ?.takeIf { it.equals("DEBIT", ignoreCase = true) || it.equals("CREDIT", ignoreCase = true) }
                ?.uppercase(Locale.ENGLISH)
                ?: when {
                    rawTx.type.equals("EXPENSE", ignoreCase = true) -> "DEBIT"
                    rawTx.type.equals("INCOME", ignoreCase = true) -> "CREDIT"
                    rawTx.isExpense -> "DEBIT"
                    else -> "CREDIT"
                }
            val isDebit = direction == "DEBIT"
            val rawTx = rawTx.copy(direction = direction, userId = owner)

            // 1. Calculate Source-Independent Fingerprint
            val fingerprint = calculateFingerprint(
                amount = rawTx.amount,
                date = rawTx.date,
                last4 = rawTx.last4Digits,
                ref = rawTx.transactionReference,
                direction = direction
            ).ifEmpty { sourceEventKey(rawTx)?.let { "event-$it" }.orEmpty() }

            // 2. Card / Account Mapping: Create or reuse an account/card only when reliable bank/issuer + valid last4 evidence is available.
            val extractedLast4 = rawTx.last4Digits.ifEmpty { TransactionIdentityResolver.extractLast4(textLower) }
            val targetLast4 = extractTargetLast4(textLower)
            val counterpartyLast4 = extractCounterpartyLast4(textLower)

            val matchResult = matchExistingAccountOrCard(
                accounts = accounts,
                cards = cards,
                rawTx = rawTx,
                textLower = textLower,
                address = rawText,
                extractedLast4 = extractedLast4
            )

            var mappedAccountId = matchResult.accountId
            var mappedCardId: String? = matchResult.cardId
            var mappedLast4: String? = matchResult.last4Digits.ifEmpty { null }
            var needsReview = matchResult.needsReview || mappedAccountId.isEmpty()

            val fullTextForBank = "$textLower $rawText ${rawTx.originalReference} ${rawTx.note}".lowercase(Locale.ENGLISH)
            val bankName = extractBankName(fullTextForBank, rawText, extractedLast4)
            val isValid4DigitLast4 = extractedLast4.length == 4 && extractedLast4.all { it.isDigit() }
            val isCreditCard = (textLower.contains("credit card") || textLower.contains("credit-card") || textLower.contains("cc ending")) ||
                    ((textLower.contains("card ending") || textLower.contains("card xx") || textLower.contains("card no") || textLower.contains("card ending in")) && !textLower.contains("debit card"))
            val smsIdentityAmbiguous = rawTx.source.equals("SMS", ignoreCase = true) &&
                if (isCreditCard) {
                    val matchingCards = cards.filter { card ->
                        card.last4Digits == extractedLast4 &&
                            card.type.equals("Credit Card", ignoreCase = true) &&
                            (isBankNameMatch(card.name, bankName) ||
                                accounts.any { acc ->
                                    acc.id == card.accountId &&
                                        isBankNameMatch(acc.bankName.ifEmpty { acc.name }, bankName)
                                })
                    }
                    val matchingCardAccounts = accounts.filter { acc ->
                        acc.last4Digits == extractedLast4 &&
                            (acc.type.equals("Credit Card", ignoreCase = true) ||
                                acc.name.contains("Card", ignoreCase = true)) &&
                            isBankNameMatch(acc.bankName.ifEmpty { acc.name }, bankName)
                    }
                    matchingCards.size > 1 || matchingCardAccounts.size > 1
                } else {
                    accounts.count { acc ->
                        acc.last4Digits == extractedLast4 &&
                            !acc.type.equals("Credit Card", ignoreCase = true) &&
                            isBankNameMatch(acc.bankName.ifEmpty { acc.name }, bankName)
                    } > 1
                }

            // Prepare potential auto-creation ONLY when reliable bank/issuer + valid 4-digit last4 evidence exists.
            // CRITICAL: We DO NOT insert into Room immediately. We defer insertion until the transaction is successfully accepted!
            var pendingNewAccount: AccountEntity? = null
            var pendingNewCard: CardEntity? = null

            if (needsReview && isValid4DigitLast4 && bankName.isNotBlank() &&
                !smsIdentityAmbiguous && !rawTx.source.equals("SMS", ignoreCase = true)
            ) {
                if (isCreditCard) {
                    val existingCard = cards.singleOrNull { card ->
                        card.last4Digits == extractedLast4 && card.type.equals("Credit Card", ignoreCase = true) &&
                        (isBankNameMatch(card.name, bankName) || 
                         accounts.any { acc -> acc.id == card.accountId && isBankNameMatch(acc.bankName.ifEmpty { acc.name }, bankName) })
                    }
                    val existingCardAcc = accounts.singleOrNull { acc ->
                        acc.last4Digits == extractedLast4 && 
                        (acc.type.equals("Credit Card", ignoreCase = true) || acc.name.contains("Card", ignoreCase = true)) &&
                        isBankNameMatch(acc.bankName.ifEmpty { acc.name }, bankName)
                    }

                    if (existingCard != null) {
                        mappedCardId = existingCard.id
                        mappedAccountId = existingCard.accountId
                        mappedLast4 = existingCard.last4Digits
                        needsReview = false
                    } else if (existingCardAcc != null) {
                        val newCardId = "card-auto-" + UUID.randomUUID().toString().substring(0, 8)
                        val newCard = CardEntity(
                            id = newCardId,
                            accountId = existingCardAcc.id,
                            name = "$bankName Credit Card",
                            type = "Credit Card",
                            last4Digits = extractedLast4,
                            createdAt = getNowIsoString(),
                            updatedAt = getNowIsoString(),
                            userId = owner
                        )
                        pendingNewCard = newCard
                        mappedAccountId = existingCardAcc.id
                        mappedCardId = newCardId
                        mappedLast4 = extractedLast4
                        needsReview = false
                    } else {
                        val newAccId = "acc-auto-cc-" + UUID.randomUUID().toString().substring(0, 8)
                        val newCardId = "card-auto-" + UUID.randomUUID().toString().substring(0, 8)
                        val newAcc = AccountEntity(
                            id = newAccId,
                            name = "$bankName Credit Card •••• $extractedLast4",
                            type = "Credit Card",
                            bankName = bankName,
                            last4Digits = extractedLast4,
                            icon = "credit-card",
                            colour = "#7C3AED",
                            isActive = true,
                            isDefault = false,
                            isOwnedByMe = true,
                            createdAt = getNowIsoString(),
                            updatedAt = getNowIsoString(),
                            userId = owner
                        )
                        val newCard = CardEntity(
                            id = newCardId,
                            accountId = newAccId,
                            name = "$bankName Credit Card",
                            type = "Credit Card",
                            last4Digits = extractedLast4,
                            createdAt = getNowIsoString(),
                            updatedAt = getNowIsoString(),
                            userId = owner
                        )
                        pendingNewAccount = newAcc
                        pendingNewCard = newCard
                        mappedAccountId = newAccId
                        mappedCardId = newCardId
                        mappedLast4 = extractedLast4
                        needsReview = false
                    }
                } else {
                    val existingAcc = accounts.singleOrNull { acc ->
                        acc.last4Digits == extractedLast4 && 
                        !acc.type.equals("Credit Card", ignoreCase = true) &&
                        isBankNameMatch(acc.bankName.ifEmpty { acc.name }, bankName)
                    }
                    if (existingAcc != null) {
                        mappedAccountId = existingAcc.id
                        mappedLast4 = existingAcc.last4Digits
                        needsReview = false
                    } else {
                        val newAccId = "acc-auto-bank-" + UUID.randomUUID().toString().substring(0, 8)
                        val newAcc = AccountEntity(
                            id = newAccId,
                            name = "$bankName Account •••• $extractedLast4",
                            type = "Bank Account",
                            bankName = bankName,
                            last4Digits = extractedLast4,
                            icon = "landmark",
                            colour = "#0284C7",
                            isActive = true,
                            isDefault = false,
                            isOwnedByMe = true,
                            createdAt = getNowIsoString(),
                            updatedAt = getNowIsoString(),
                            userId = owner
                        )
                        pendingNewAccount = newAcc
                        mappedAccountId = newAccId
                        mappedLast4 = extractedLast4
                        needsReview = false
                    }
                }
            }

            val resolvedRawTx = rawTx.copy(
                accountId = mappedAccountId,
                cardId = mappedCardId,
                last4Digits = mappedLast4 ?: rawTx.last4Digits,
                last4 = mappedLast4 ?: rawTx.last4Digits
            )
            // 3. Centralized Robust Deduplication Check (Rules 1-8)
            val duplicateTx = existingTxs.find { existing ->
                isDuplicateTransaction(resolvedRawTx, existing)
            }

            // Check for an existing auto-generated internal transfer counterpart to enrich.
            val pendingCounterpart = if (duplicateTx == null) {
                existingTxs.find { existing ->
                    existing.isInternalTransfer &&
                    existing.transferGroupId != null &&
                    existing.direction == direction &&
                    Math.abs(existing.amount - rawTx.amount) < 0.01 &&
                    existing.date == rawTx.date &&
                    existing.accountId == mappedAccountId &&
                    existing.id.startsWith("tx-transfer-")
                }
            } else null

            if (pendingCounterpart != null) {
                if (pendingCounterpart.userId != owner) {
                    Log.w(TAG, "Pending transaction update rejected because ownership is inconsistent")
                    return@withContext Pair(null, IngestionStatus.FAILED)
                }
                val updatedCounterpart = pendingCounterpart.copy(
                    note = rawTx.note.ifEmpty { pendingCounterpart.note },
                    originalReference = rawTx.originalReference.ifEmpty { pendingCounterpart.originalReference },
                    transactionReference = rawTx.transactionReference.ifEmpty { pendingCounterpart.transactionReference },
                    updatedAt = getNowIsoString()
                )
                if (!isCurrentOwner(owner)) return@withContext Pair(null, IngestionStatus.FAILED)
                val rowId = dao.insertTransaction(updatedCounterpart)
                if (rowId != -1L) {
                    Log.d(TAG, "Successfully updated pending counterpart: ${updatedCounterpart.id}")
                    return@withContext Pair(updatedCounterpart, IngestionStatus.IMPORTED)
                } else {
                    Log.e(TAG, "Failed to update pending counterpart: ${updatedCounterpart.id}")
                    return@withContext Pair(null, IngestionStatus.FAILED)
                }
            }

            if (duplicateTx != null) {
                if (dao.getSplitsForTransactionSync(owner, duplicateTx.id).isNotEmpty()) {
                    return@withContext Pair(duplicateTx, IngestionStatus.DUPLICATE)
                }

                // If a new AccountEntity/CardEntity was discovered during ingestion and it is not needsReview,
                // persist it so it is not lost when merging metadata into an existing duplicate record.
                var effectiveAccounts = accounts
                var effectiveCards = cards

                // Automatically persist created AccountEntity/CardEntity so accountId/cardId always point to a valid Room record
                if (pendingNewAccount != null) {
                    if (pendingNewAccount.userId != owner) {
                        return@withContext Pair(null, IngestionStatus.FAILED)
                    }
                    if (!isCurrentOwner(owner)) return@withContext Pair(null, IngestionStatus.FAILED)
                    dao.insertAccount(pendingNewAccount)
                    effectiveAccounts = if (accounts.none { it.id == pendingNewAccount.id }) {
                        accounts + pendingNewAccount
                    } else accounts
                    Log.d(TAG, "Auto-created AccountEntity persisted on duplicate merge: ${pendingNewAccount.id} (${pendingNewAccount.name})")
                }
                if (pendingNewCard != null) {
                    if (pendingNewCard.userId != owner) {
                        return@withContext Pair(null, IngestionStatus.FAILED)
                    }
                    if (!isCurrentOwner(owner)) return@withContext Pair(null, IngestionStatus.FAILED)
                    dao.insertCard(pendingNewCard)
                    effectiveCards = if (cards.none { it.id == pendingNewCard.id }) {
                        cards + pendingNewCard
                    } else cards
                    Log.d(TAG, "Auto-created CardEntity persisted on duplicate merge: ${pendingNewCard.id} (${pendingNewCard.name})")
                }

                // Check if incoming text is a credit card bill payment or transfer
                val isIncomingCardBill = textLower.contains("credit card payment") ||
                    textLower.contains("cc payment") ||
                    textLower.contains("card bill") ||
                    textLower.contains("cred club") ||
                    textLower.contains("cred.club") ||
                    textLower.contains("cred club credited") ||
                    (Regex("\\bcred\\b", RegexOption.IGNORE_CASE).containsMatchIn(textLower) && (textLower.contains("credited") || textLower.contains("payment") || textLower.contains("club") || textLower.contains("paid") || textLower.contains("bill"))) ||
                    (textLower.contains("bill payment") && (textLower.contains("credit card") || textLower.contains("card"))) ||
                    Regex("(?:bill\\s+)?payment\\s+.*?\\s+(?:towards|for|to|of)\\s+.*?card", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                    Regex("paid\\s+.*?\\s+(?:towards|for|to)?\\s*.*?credit\\s*card", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                    Regex("payment\\s+received\\s+.*?\\s+(?:towards|for|to)\\s+.*?card", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                    (textLower.contains("thank you") && textLower.contains("payment") && (textLower.contains("credit card") || textLower.contains("card"))) ||
                    (textLower.contains("credit card") && (textLower.contains("payment received") || textLower.contains("bill paid") || textLower.contains("payment of") || textLower.contains("autopay"))) ||
                    Regex("(?:paid|transferred)\\s+(?:rs\\.?|inr|₹)?\\s*[\\d,.]*\\s*(?:to|towards)\\s+.*?card", RegexOption.IGNORE_CASE).containsMatchIn(textLower)

                val incomingTxType = if (isIncomingCardBill || rawTx.transactionType == "CARD_PAYMENT" || rawTx.transactionType == "CREDIT_CARD_BILL_PAYMENT") {
                    "CARD_PAYMENT"
                } else if (rawTx.isInternalTransfer || rawTx.type == "INTERNAL_TRANSFER") {
                    "INTERNAL_TRANSFER"
                } else if (rawTx.type == "EXPENSE") "EXPENSE" else "INCOME"

                // Prepare a metadata-enriched version of the incoming transaction for merging
                val enrichedIncoming = rawTx.copy(
                    accountId = mappedAccountId,
                    cardId = mappedCardId,
                    last4Digits = mappedLast4 ?: rawTx.last4Digits,
                    last4 = mappedLast4 ?: rawTx.last4Digits,
                    needsReview = needsReview,
                    type = if (isIncomingCardBill) "INTERNAL_TRANSFER" else rawTx.type,
                    transactionType = incomingTxType,
                    isInternalTransfer = isIncomingCardBill || rawTx.isInternalTransfer,
                    isExpense = if (isIncomingCardBill) false else rawTx.isExpense
                )
                
                // Intelligently merge metadata without creating duplicate records (Rule 5, 6)
                val mergedTx = mergeTransactionMetadata(duplicateTx, enrichedIncoming, effectiveAccounts, effectiveCards)
                if (mergedTx.userId != owner || duplicateTx.userId != owner) {
                    Log.w(TAG, "Duplicate merge rejected because transaction ownership is inconsistent")
                    return@withContext Pair(null, IngestionStatus.FAILED)
                }
                if (!isCurrentOwner(owner)) return@withContext Pair(null, IngestionStatus.FAILED)
                val mergedRows = dao.upsertTransactionAndLinkedTransfer(owner, mergedTx)
                if (mergedRows != null) {
                    Log.d(TAG, "Duplicate prevented and metadata merged for transaction: ${mergedTx.id} (Ref: ${mergedTx.transactionReference})")

                    return@withContext Pair(mergedTx, IngestionStatus.DUPLICATE)
                } else {
                    Log.e(TAG, "Failed to merge metadata for duplicate transaction: ${mergedTx.id}")
                    return@withContext Pair(null, IngestionStatus.FAILED)
                }
            }

            // 5. Internal Transfer Detection
            val sourceAccount = accounts.find { it.id == mappedAccountId }
            val sourceOwned = sourceAccount?.isOwnedByMe ?: true

            var finalTxType = if (isDebit) "EXPENSE" else "INCOME"
            var isInternal = false
            var transferGroupId: String? = null
            var counterpartyId: String? = null

            // A: ATM Cash Withdrawal
            val isAtm = textLower.contains("atm") || textLower.contains("cash withdrawn") ||
                textLower.contains("withdrawn from atm") || textLower.contains("cash withdrawal") ||
                textLower.contains("atm withdrawal")
            if (isDebit && isAtm && sourceOwned) {
                finalTxType = "CASH_WITHDRAWAL"
                val cashAccount = accounts.find { it.type.lowercase() == "cash" || it.name.lowercase().contains("cash") }
                if (cashAccount != null) {
                    isInternal = true
                    transferGroupId = "tg-internal-" + UUID.randomUUID().toString().substring(0, 8)
                    counterpartyId = cashAccount.id
                    needsReview = false
                }
            }

            // B: Credit Card Bill Payment / Transfer between Owned accounts
            val isCardBillPayment = textLower.contains("credit card payment") ||
                textLower.contains("cc payment") ||
                textLower.contains("card bill") ||
                textLower.contains("cred club") ||
                textLower.contains("cred.club") ||
                textLower.contains("cred club credited") ||
                (Regex("\\bcred\\b", RegexOption.IGNORE_CASE).containsMatchIn(textLower) && (textLower.contains("credited") || textLower.contains("payment") || textLower.contains("club") || textLower.contains("paid") || textLower.contains("bill"))) ||
                (textLower.contains("bill payment") && (textLower.contains("credit card") || textLower.contains("card"))) ||
                Regex("(?:bill\\s+)?payment\\s+.*?\\s+(?:towards|for|to|of)\\s+.*?card", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                Regex("paid\\s+.*?\\s+(?:towards|for|to)?\\s*.*?credit\\s*card", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                Regex("payment\\s+received\\s+.*?\\s+(?:towards|for|to)\\s+.*?card", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                (textLower.contains("thank you") && textLower.contains("payment") && (textLower.contains("credit card") || textLower.contains("card"))) ||
                (textLower.contains("credit card") && (textLower.contains("payment received") || textLower.contains("bill paid") || textLower.contains("payment of") || textLower.contains("autopay"))) ||
                Regex("(?:paid|transferred)\\s+(?:rs\\.?|inr|₹)?\\s*[\\d,.]*\\s*(?:to|towards)\\s+.*?card", RegexOption.IGNORE_CASE).containsMatchIn(textLower)

            // SPECIAL DIRECTION FIX: If it's a bill payment and contains 'debited', it's always a DEBIT from the source account
            val finalDirection = if (isCardBillPayment && textLower.contains("debited")) "DEBIT" else direction
            val finalRawType = if (isCardBillPayment && textLower.contains("debited")) "EXPENSE" else rawTx.type

            val isGeneralTransfer = textLower.contains("self transfer") ||
                textLower.contains("transfer to self") ||
                textLower.contains("transfer between own") ||
                textLower.contains("own a/c") ||
                textLower.contains("own account") ||
                textLower.contains("internal transfer") ||
                Regex("(?:credited|received|debited|sent|transferred).*?\\b(?:by|via|through)?\\s*(?:fund\\s+)?transfer", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                Regex("(?:fund\\s+)?transfer\\s+.*?\\b(?:from|to)\\b", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                Regex("\\b(?:upi|imps|neft|rtgs)\\s*(?:/|-)?\\s*(?:fund\\s+)?transfer", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                Regex("\\b(?:transfer(?:red)?|trf)\\b.*?\\b(?:to|from)\\s+(?:a/c|account|card)?", RegexOption.IGNORE_CASE).containsMatchIn(textLower)

            val otherOwnedAccounts = accounts.filter { it.isOwnedByMe && it.id != mappedAccountId }
            val otherOwnedCards = cards.filter { it.accountId != mappedAccountId }
            val effectiveCounterparty4 = if (counterpartyLast4.length == 4 && counterpartyLast4.all { it.isDigit() }) {
                counterpartyLast4
            } else if (targetLast4.length == 4 && targetLast4.all { it.isDigit() }) {
                targetLast4
            } else ""

            if (!isInternal && sourceOwned) {
                if (isCardBillPayment) {
                    val targetBank = extractBankName(textLower, rawTx.merchant)
                    val matchedCreditCardCandidates = if (effectiveCounterparty4.isNotEmpty()) {
                        cards.filter { it.last4Digits == effectiveCounterparty4 && (targetBank.isEmpty() || isBankNameMatch(it.name, targetBank)) }
                    } else null
                    val matchedCreditAccountCandidates = if (effectiveCounterparty4.isNotEmpty()) {
                        accounts.filter { it.isOwnedByMe && it.last4Digits == effectiveCounterparty4 && it.type.equals("Credit Card", ignoreCase = true) && (targetBank.isEmpty() || isBankNameMatch(it.bankName.ifEmpty { it.name }, targetBank)) }
                    } else null
                    val matchedCreditCard = if (rawTx.source.equals("SMS", ignoreCase = true)) {
                        matchedCreditCardCandidates?.singleOrNull()
                    } else {
                        matchedCreditCardCandidates?.firstOrNull()
                    }
                    val matchedCreditAcc = if (rawTx.source.equals("SMS", ignoreCase = true)) {
                        matchedCreditAccountCandidates?.singleOrNull()
                    } else {
                        matchedCreditAccountCandidates?.firstOrNull()
                    }

                    val targetCreditAccId = matchedCreditAcc?.id ?: matchedCreditCard?.accountId
                    isInternal = true
                    finalTxType = "CARD_PAYMENT"

                    if (targetCreditAccId != null) {
                        if (mappedAccountId == targetCreditAccId) {
                            val bankSourceAcc = if (rawTx.source.equals("SMS", ignoreCase = true)) {
                                null
                            } else {
                                accounts.firstOrNull {
                                    it.isOwnedByMe && it.id != targetCreditAccId &&
                                        !it.type.equals("Credit Card", ignoreCase = true)
                                }
                            }
                            if (bankSourceAcc != null) {
                                mappedAccountId = bankSourceAcc.id
                                mappedCardId = null
                            } else if (rawTx.source.equals("SMS", ignoreCase = true)) {
                                mappedAccountId = ""
                                mappedCardId = null
                            }
                        }
                        if (mappedAccountId.isNotBlank() && targetCreditAccId != mappedAccountId) {
                            transferGroupId = "tg-internal-" + UUID.randomUUID().toString().substring(0, 8)
                            counterpartyId = targetCreditAccId
                            needsReview = false
                        } else {
                            counterpartyId = null
                            needsReview = true
                        }
                    } else {
                        // Reliable target credit card could not be identified: keep transaction safe as CARD_PAYMENT, do not guess
                        counterpartyId = null
                        needsReview = true
                    }
                } else if (effectiveCounterparty4.isNotEmpty()) {
                    val targetAccount = otherOwnedAccounts.find { it.last4Digits == effectiveCounterparty4 }
                    val targetCard = otherOwnedCards.find { it.last4Digits == effectiveCounterparty4 }
                    if (targetAccount != null) {
                        isInternal = true
                        transferGroupId = "tg-internal-" + UUID.randomUUID().toString().substring(0, 8)
                        counterpartyId = targetAccount.id
                        finalTxType = if (targetAccount.type.lowercase().contains("card")) "CARD_PAYMENT" else "INTERNAL_TRANSFER"
                        needsReview = false
                    } else if (targetCard != null) {
                        isInternal = true
                        transferGroupId = "tg-internal-" + UUID.randomUUID().toString().substring(0, 8)
                        counterpartyId = targetCard.accountId
                        finalTxType = "CARD_PAYMENT"
                        needsReview = false
                    } else if (isGeneralTransfer) {
                        isInternal = true
                        finalTxType = "INTERNAL_TRANSFER"
                        counterpartyId = null
                        needsReview = true
                    }
                } else if (isGeneralTransfer) {
                    val matchingOwnedByBank = otherOwnedAccounts.filter { acc ->
                        val bName = acc.bankName.ifEmpty { acc.name }.lowercase(Locale.ENGLISH).trim()
                        bName.length >= 3 && bName != "bank" && bName != "account" && bName != "cash" && textLower.contains(bName)
                    }
                    if (matchingOwnedByBank.size == 1) {
                        isInternal = true
                        transferGroupId = "tg-internal-" + UUID.randomUUID().toString().substring(0, 8)
                        counterpartyId = matchingOwnedByBank.first().id
                        finalTxType = "INTERNAL_TRANSFER"
                        needsReview = false
                    } else if (otherOwnedAccounts.size == 1) {
                        isInternal = true
                        transferGroupId = "tg-internal-" + UUID.randomUUID().toString().substring(0, 8)
                        counterpartyId = otherOwnedAccounts.first().id
                        finalTxType = "INTERNAL_TRANSFER"
                        needsReview = false
                    } else {
                        // Ambiguous or unresolved counterpart: preserve safe INTERNAL_TRANSFER, do NOT guess, needsReview=true
                        isInternal = true
                        finalTxType = "INTERNAL_TRANSFER"
                        counterpartyId = null
                        needsReview = true
                    }
                }
            }

            val resolvedMerchantName = if (finalTxType == "CREDIT_CARD_BILL_PAYMENT" || finalTxType == "CARD_PAYMENT") {
                "Credit Card Bill Payment"
            } else if (isInternal && (rawTx.merchant.isBlank() || rawTx.merchant.equals("Unknown Merchant", true) || rawTx.merchant.equals("Other", true))) {
                "Internal Transfer"
            } else com.example.utils.SmsParser.sanitizeMerchantName(rawTx.merchant)

            val savedPref = if (isInternal || !isDebit) null else com.example.utils.MerchantLearningEngine.getMapping(context, resolvedMerchantName)
            
            val categoryIdToUse = when {
                isInternal -> "cat-transfer"
                !isDebit -> rawTx.categoryId // Income transactions keep income categories
                savedPref != null && !savedPref.first.isNullOrBlank() -> savedPref.first
                rawTx.categoryId.isNotBlank() && rawTx.categoryId != "cat-other" -> {
                    if (rawTx.categoryId == "cat-recharge" && !NotificationParser.isTelecomRecharge(resolvedMerchantName, rawText)) {
                        "cat-other"
                    } else if (resolvedMerchantName.equals("Other", true) || resolvedMerchantName.equals("General", true) || resolvedMerchantName.equals("Unknown Merchant", true)) {
                        "cat-other"
                    } else {
                        rawTx.categoryId
                    }
                }
                else -> "cat-other"
            }

            val subcategoryIdToUse = when {
                isInternal || !isDebit -> if (!isDebit) rawTx.subcategoryId else ""
                savedPref != null && !savedPref.first.isNullOrBlank() -> savedPref.second
                categoryIdToUse == "cat-other" -> ""
                else -> rawTx.subcategoryId
            }

            var finalNeedsReview = needsReview
            if (!isInternal && resolvedMerchantName.equals("Unknown Merchant", ignoreCase = true)) {
                finalNeedsReview = true
            }

            // Build primary transaction object
            var finalTx = rawTx.copy(
                accountId = mappedAccountId,
                cardId = mappedCardId,
                last4Digits = mappedLast4 ?: rawTx.last4Digits,
                last4 = mappedLast4 ?: rawTx.last4Digits,
                transactionId = rawTx.transactionId ?: rawTx.id,
                type = if (isInternal) "INTERNAL_TRANSFER" else (if (finalDirection == "DEBIT") "EXPENSE" else "INCOME"),
                transactionType = finalTxType,
                direction = finalDirection,
                duplicateFingerprint = fingerprint,
                isInternalTransfer = isInternal,
                transferGroupId = transferGroupId,
                counterpartyAccountId = counterpartyId,
                categoryId = categoryIdToUse,
                subcategoryId = subcategoryIdToUse,
                merchant = resolvedMerchantName,
                needsReview = finalNeedsReview,
                isExpense = if (isInternal) false else (finalDirection == "DEBIT"),
                updatedAt = getNowIsoString()
            )

            // D: Auto-Pairing with real counterpart transactions arriving from other sources
            var linkedTransferPair: Pair<TransactionEntity, TransactionEntity>? = null
            if (sourceOwned) {
                val isIncomingDebit = finalDirection == "DEBIT"
                val incomingReferences = getTransactionReferenceKeys(finalTx)
                val isTransferOrCardPayment = isInternal ||
                    finalTxType == "INTERNAL_TRANSFER" ||
                    finalTxType == "CARD_PAYMENT" ||
                    finalTxType == "CREDIT_CARD_BILL_PAYMENT"
                val pairingMatch = existingTxs.find { other ->
                    isTransferOrCardPayment &&
                    incomingReferences.isNotEmpty() &&
                    incomingReferences.intersect(getTransactionReferenceKeys(other)).isNotEmpty() &&
                    other.id != finalTx.id &&
                    other.userId == owner &&
                    other.date == rawTx.date &&
                    Math.abs(other.amount - rawTx.amount) < 0.01 &&
                    ((isIncomingDebit && (other.direction == "CREDIT" || other.type == "INCOME")) ||
                     (!isIncomingDebit && (other.direction == "DEBIT" || other.type == "EXPENSE"))) &&
                    other.accountId != mappedAccountId &&
                    (accounts.find { it.id == other.accountId }?.isOwnedByMe == true) &&
                    (counterpartyId == null || other.accountId == counterpartyId) &&
                    (other.isInternalTransfer || other.type == "INTERNAL_TRANSFER" ||
                        other.transactionType == "INTERNAL_TRANSFER" ||
                        other.transactionType == "CARD_PAYMENT" ||
                        other.transactionType == "CREDIT_CARD_BILL_PAYMENT" ||
                        isGeneralTransfer || isCardBillPayment)
                }

                if (pairingMatch != null) {
                    val groupToUse = pairingMatch.transferGroupId ?: transferGroupId ?: ("tg-paired-" + UUID.randomUUID().toString().substring(0, 8))
                    val isEitherCardPayment = finalTxType == "CREDIT_CARD_BILL_PAYMENT" || finalTxType == "CARD_PAYMENT" ||
                        pairingMatch.transactionType == "CREDIT_CARD_BILL_PAYMENT" || pairingMatch.transactionType == "CARD_PAYMENT" ||
                        accounts.find { it.id == mappedAccountId }?.type?.contains("Card", ignoreCase = true) == true ||
                        accounts.find { it.id == pairingMatch.accountId }?.type?.contains("Card", ignoreCase = true) == true
                    val pairedType = if (isEitherCardPayment) "CREDIT_CARD_BILL_PAYMENT" else "INTERNAL_TRANSFER"

                    if (!isCurrentOwner(owner)) return@withContext Pair(null, IngestionStatus.FAILED)
                    dao.insertCategory(
                        CategoryEntity(
                            id = "cat-transfer",
                            name = "Internal Transfer",
                            nameHindi = "",
                            icon = "🔄",
                            colour = "#3B82F6",
                            isDefault = true,
                            isActive = true,
                            isIncome = false,
                            createdAt = getNowIsoString(),
                            updatedAt = getNowIsoString(),
                            userId = owner
                        )
                    )

                    finalTx = finalTx.copy(
                        type = "INTERNAL_TRANSFER",
                        transactionType = pairedType,
                        isInternalTransfer = true,
                        isExpense = false,
                        transferGroupId = groupToUse,
                        counterpartyAccountId = pairingMatch.accountId,
                        categoryId = "cat-transfer",
                        merchant = if (isEitherCardPayment) "Credit Card Bill Payment" else "Internal Transfer",
                        needsReview = false
                    )
                    
                    val updatedOther = pairingMatch.copy(
                        type = "INTERNAL_TRANSFER",
                        transactionType = pairedType,
                        isInternalTransfer = true,
                        isExpense = false,
                        transferGroupId = groupToUse,
                        counterpartyAccountId = mappedAccountId,
                        categoryId = "cat-transfer",
                        merchant = if (isEitherCardPayment) "Credit Card Bill Payment" else "Internal Transfer",
                        needsReview = false,
                        updatedAt = getNowIsoString()
                    )
                    if (updatedOther.userId != owner || finalTx.userId != owner) {
                        Log.w(TAG, "Transfer pairing rejected because transaction ownership is inconsistent")
                        return@withContext Pair(null, IngestionStatus.FAILED)
                    }
                    linkedTransferPair = finalTx to updatedOther
                    Log.d(TAG, "Successfully paired real transactions into group: $groupToUse (Subtype: $pairedType)")
                }
            }

            // Insert primary transaction
            if (finalTx.userId != owner) {
                Log.w(TAG, "Transaction write rejected because transaction ownership is inconsistent")
                return@withContext Pair(null, IngestionStatus.FAILED)
            }
            if (pendingNewAccount?.userId?.let { it != owner } == true ||
                pendingNewCard?.userId?.let { it != owner } == true
            ) {
                Log.w(TAG, "Account or card write rejected because ownership is inconsistent")
                return@withContext Pair(null, IngestionStatus.FAILED)
            }
            if (!isCurrentOwner(owner)) return@withContext Pair(null, IngestionStatus.FAILED)
            val rowId = if (linkedTransferPair != null) {
                if (!dao.saveLinkedTransferPair(owner, linkedTransferPair.first, linkedTransferPair.second)) -1L
                else dao.getTransactionRowId(owner, finalTx.id) ?: -1L
            } else {
                if (dao.upsertTransactionAndLinkedTransfer(owner, finalTx) == null) -1L
                else dao.getTransactionRowId(owner, finalTx.id) ?: -1L
            }
            
            if (rowId != -1L) {
                val countAfter = dao.getAllTransactionsSyncForUser(owner).size
                Log.d(TAG, "Room insert SUCCESS: ID=${finalTx.id}, RowId=$rowId, CountAfter=$countAfter")
                
                // Automatic Account or Card creation upon successful transaction insertion
                if (pendingNewAccount != null) {
                    if (!isCurrentOwner(owner)) return@withContext Pair(null, IngestionStatus.FAILED)
                    dao.insertAccount(pendingNewAccount)
                    Log.d(TAG, "Auto-created AccountEntity inserted: ${pendingNewAccount.id} (${pendingNewAccount.name})")
                }
                if (pendingNewCard != null) {
                    if (!isCurrentOwner(owner)) return@withContext Pair(null, IngestionStatus.FAILED)
                    dao.insertCard(pendingNewCard)
                    Log.d(TAG, "Auto-created CardEntity inserted: ${pendingNewCard.id} (${pendingNewCard.name})")
                }

                // Extract and apply balance updates safely if account identity is known
                if (mappedAccountId.isNotEmpty()) {
                    val balInfo = extractBalanceInfo(rawText)
                    if (balInfo != null) {
                        val msgTime = if (rawTx.updatedAt.isNotEmpty()) {
                            parseIsoOrMillisToLong(rawTx.updatedAt)
                        } else {
                            parseIsoOrMillisToLong(finalTx.updatedAt)
                        }
                        val validTime = if (msgTime > 0) msgTime else System.currentTimeMillis()
                        applyExtractedBalanceForOwner(
                            dao,
                            mappedAccountId,
                            mappedCardId,
                            balInfo,
                            validTime,
                            owner
                        )
                    }
                }
            } else {
                Log.e(TAG, "Room insert FAILED: ID=${finalTx.id}")
                return@withContext Pair(null, IngestionStatus.FAILED)
            }

            val status = if (finalNeedsReview) IngestionStatus.NEEDS_REVIEW else IngestionStatus.IMPORTED
            Log.d(TAG, "Ingestion COMPLETE: Final Status=$status")
            return@withContext Pair(finalTx, status)

        } catch (e: Exception) {
            Log.e(TAG, "Error ingesting transaction: ${e.message}", e)
            return@withContext Pair(null, IngestionStatus.FAILED)
        }
    }
 }
    /**
     * One-time cleanup / migration to remove duplicate transactions in the database,
     * merging useful metadata into a single preserved record per transaction (Rule 9, 10, 11).
     */
    suspend fun cleanupDuplicateTransactions(dao: KharchaDao): Int {
        val owner = authenticatedOwner() ?: return 0
        return cleanupDuplicateTransactionsForOwner(dao, owner)
    }

    private suspend fun cleanupDuplicateTransactionsForOwner(dao: KharchaDao, owner: String): Int =
        withContext(Dispatchers.IO) {
        var removedCount = 0
        try {
            val allTxs = dao.getAllTransactionsSyncForUser(owner).filter { it.userId == owner }
            if (allTxs.size <= 1) return@withContext 0

            val accounts = dao.getAllAccountsSyncForUser(owner).filter { it.userId == owner }
            val cards = dao.getAllCardsSyncForUser(owner).filter { it.userId == owner }

            val processedIds = mutableSetOf<String>()

            // 1. Pass: Group by normalized reference ID (Rule 1, 2, 9, 10)
            val refGroups = mutableMapOf<String, MutableList<TransactionEntity>>()
            for (tx in allTxs) {
                val refKeys = getTransactionReferenceKeys(tx)
                val dir = if (tx.type.equals("EXPENSE", ignoreCase = true) || tx.direction?.equals("DEBIT", ignoreCase = true) == true) "DEBIT" else "CREDIT"
                for (key in refKeys) {
                    refGroups.getOrPut("${dir}_${key}") { mutableListOf() }.add(tx)
                }
            }

            for ((_, txList) in refGroups) {
                val distinctTxs = txList.distinctBy { it.id }.filter { !processedIds.contains(it.id) }
                if (distinctTxs.size > 1) {
                    // Pick the best primary record to preserve
                    val primary = distinctTxs.maxByOrNull { tx ->
                        calculateIdentityPriority(tx, accounts, cards)
                    } ?: distinctTxs.first()

                    var merged = primary
                    for (dup in distinctTxs) {
                        if (dup.id != primary.id) {
                            val splits = dao.getSplitsForTransactionSync(owner, dup.id)
                            if (splits.isNotEmpty() || splits.any { it.userId != owner }) {
                                processedIds.add(dup.id)
                                continue
                            }
                            merged = mergeTransactionMetadata(merged, dup, accounts, cards)
                            dao.deleteTransactionAndSplits(owner, dup.id)
                            processedIds.add(dup.id)
                            removedCount++
                        }
                    }
                    if (merged.userId == owner) dao.insertTransaction(merged)
                    processedIds.add(primary.id)
                }
            }

            // 2. Pass: Secondary conservative fingerprint deduplication for transactions without reference ID
            val remainingTxs = dao.getAllTransactionsSyncForUser(owner).filter { it.userId == owner }
            val remainingList = remainingTxs.filter { !processedIds.contains(it.id) }.toMutableList()

            var i = 0
            while (i < remainingList.size) {
                val current = remainingList[i]
                val candidateDups = remainingList.filterIndexed { index, other -> index > i && isDuplicateTransaction(current, other) }
                if (candidateDups.isNotEmpty()) {
                    var merged = current
                    for (dup in candidateDups) {
                        val splits = dao.getSplitsForTransactionSync(owner, dup.id)
                        if (splits.isNotEmpty() || splits.any { it.userId != owner }) {
                            processedIds.add(dup.id)
                            continue
                        }
                        merged = mergeTransactionMetadata(
                            merged,
                            dup,
                            accounts,
                            cards
                        )
                        dao.deleteTransactionAndSplits(owner, dup.id)
                        remainingList.remove(dup)
                        processedIds.add(dup.id)
                        removedCount++
                    }
                    if (merged.userId == owner) dao.insertTransaction(merged)
                    remainingList[i] = merged
                }
                i++
            }

            Log.d(TAG, "Duplicate cleanup complete: removed $removedCount duplicates, merged into primary records.")
        } catch (e: Exception) {
            Log.e(TAG, "Error in cleanupDuplicateTransactions: ${e.message}", e)
        }
        return@withContext removedCount
    }

    suspend fun cleanupHistoricalIncorrectSmsTransactions(dao: KharchaDao): Int {
        val owner = authenticatedOwner() ?: return 0
        return cleanupHistoricalIncorrectSmsTransactionsForOwner(dao, owner)
    }

    private suspend fun cleanupHistoricalIncorrectSmsTransactionsForOwner(
        dao: KharchaDao,
        owner: String
    ): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        try {
            val allTxs = dao.getAllTransactionsSyncForUser(owner).filter { it.userId == owner }
            for (tx in allTxs) {
                val merchantLower = tx.merchant.lowercase(Locale.ENGLISH)
                val noteLower = tx.note.lowercase(Locale.ENGLISH)
                val refLower = tx.originalReference.lowercase(Locale.ENGLISH)
                val txRefLower = tx.transactionReference.lowercase(Locale.ENGLISH)

                // 1. Full-SMS-as-merchant records OR Balance-only SMS records (REFINED)
                // Do NOT delete merely because merchant is long or has newline or .com/.in
                val isFullSmsAsMerchant = (tx.merchant.length > 100 && tx.merchant.contains(" ")) || 
                    merchantLower.contains("available balance") || 
                    merchantLower.contains("avbl bal") ||
                    merchantLower.contains("closing balance") ||
                    merchantLower.contains("current balance") ||
                    merchantLower.contains("new balance") ||
                    merchantLower.contains("ledger balance") ||
                    merchantLower.contains("available limit") ||
                    merchantLower.contains("avbl limit") ||
                    merchantLower.contains("outstanding amount") ||
                    merchantLower.contains("statement balance")

                val isBalanceOrLimitOnly = (merchantLower.contains("available balance") || 
                    merchantLower.contains("avbl bal") || 
                    merchantLower.contains("ledger balance") || 
                    merchantLower.contains("account balance") || 
                    merchantLower.contains("credit limit") || 
                    merchantLower.contains("available limit") || 
                    merchantLower.contains("avbl limit") || 
                    merchantLower.contains("outstanding amount") || 
                    merchantLower.contains("statement balance")) && tx.amount == 0.0

                // 2. Bill reminders (Rule E.3)
                val isBillReminder = (merchantLower.contains("due tomorrow") || 
                    merchantLower.contains("bill of") || 
                    merchantLower.contains("bill amount") || 
                    merchantLower.contains("ignore if paid") || 
                    merchantLower.contains("pay now")) && tx.amount == 0.0

                // 3. Credit Card Bill Payment confirmations (REFINED: Do NOT delete valid transactions)
                // Only delete if it's CLEARLY a duplicate of a bank debit that is already captured
                // For now, let's just NOT delete them unless they are clearly non-financial
                val isCcBillPaymentConfirmation = false 

                // 4. Incorrect CRED transactions (Rule E.5)
                val isIncorrectCred = tx.merchant.uppercase(Locale.ENGLISH) == "CRED" && 
                    !noteLower.contains(Regex("\\bcred\\b")) && 
                    !refLower.contains(Regex("\\bcred\\b")) && 
                    !txRefLower.contains("cred")

                // 5. Delete legacy synthetic counterparts
                val isLegacyCounterpart = tx.id.startsWith("tx-transfer-")

                if (isBalanceOrLimitOnly || isBillReminder || isIncorrectCred || isLegacyCounterpart) {
                    if (dao.getSplitsForTransactionSync(owner, tx.id).isNotEmpty()) continue
                    dao.deleteTransactionAndSplits(owner, tx.id)
                    Log.d("DatabaseCleanup", "Deleted non-financial or legacy record: ID=${tx.id}, Merchant=${tx.merchant}, Amount=${tx.amount}")
                    deletedCount++
                }
            }
        } catch (e: Exception) {
            Log.e("DatabaseCleanup", "Error cleaning up incorrect records: ${e.message}", e)
        }
        return@withContext deletedCount
    }

    suspend fun normalizeExistingTransactions(dao: KharchaDao): Int {
        val owner = authenticatedOwner() ?: return 0
        return normalizeExistingTransactionsForOwner(dao, owner)
    }

    private suspend fun normalizeExistingTransactionsForOwner(dao: KharchaDao, owner: String): Int =
        withContext(Dispatchers.IO) {
        // Run database cleanup of historical incorrect records first!
        cleanupHistoricalIncorrectSmsTransactionsForOwner(dao, owner)

        var fixedCount = 0
        try {
            val allTxs = dao.getAllTransactionsSyncForUser(owner).filter { it.userId == owner }
            for (tx in allTxs) {
                if (tx.userId != owner) continue
                var merchant = tx.merchant.trim()
                val lower = merchant.lowercase(Locale.ENGLISH)
                val isBadMerchant = lower.isEmpty() ||
                    lower == "https" ||
                    lower == "http" ||
                    lower == "www" ||
                    lower.startsWith("http://") ||
                    lower.startsWith("https://") ||
                    lower.startsWith("www.") ||
                    lower.contains(".com") ||
                    lower.contains(".in") ||
                    lower == "transaction" ||
                    lower == "payment" ||
                    lower == "bank transfer" ||
                    lower == "other" ||
                    lower == "unknown merchant" ||
                    lower.startsWith("vm-") ||
                    lower.startsWith("ad-") ||
                    lower.startsWith("sms from") ||
                    Regex("^[a-z]{2}-[a-z]{4,8}-[a-z]$").containsMatchIn(lower) || // Sender ID pattern: VM-IDFCFB-S
                    lower.contains("commandent") ||
                    lower.contains("available balance") ||
                    lower.contains("avbl bal") ||
                    lower.length > 32 ||
                    lower.contains("\n")

                if (isBadMerchant) {
                    val cleaned = SmsParser.cleanMerchantName(tx.note.ifEmpty { tx.originalReference })
                    if (cleaned != "Unknown Merchant" && cleaned.isNotEmpty() && !cleaned.lowercase(Locale.ENGLISH).startsWith("sms from")) {
                        val updated = tx.copy(merchant = cleaned, updatedAt = getNowIsoString())
                        dao.insertTransaction(updated)
                        fixedCount++
                    } else if (lower != "unknown merchant") {
                        val updated = tx.copy(merchant = "Unknown Merchant", updatedAt = getNowIsoString())
                        dao.insertTransaction(updated)
                        fixedCount++
                    }
                } else {
                    val cleaned = SmsParser.cleanMerchantName(merchant)
                    if (cleaned.isNotEmpty() && cleaned != merchant) {
                        val updated = tx.copy(merchant = cleaned, updatedAt = getNowIsoString())
                        dao.insertTransaction(updated)
                        fixedCount++
                    }
                }

                // Direction and classification verification/healing for existing non-manual transactions
                if (tx.source != "MANUAL" && !tx.isInternalTransfer && tx.type != "INTERNAL_TRANSFER" && tx.transactionType != "INTERNAL_TRANSFER") {
                    val evidence = "${tx.note} ${tx.originalReference} ${tx.transactionReference}".lowercase(Locale.ENGLISH)
                    val expectedDirection = SmsParser.determineTransactionDirection(evidence)
                    if (expectedDirection != null && expectedDirection != tx.type) {
                        val newDir = if (expectedDirection == "EXPENSE") "DEBIT" else "CREDIT"
                        val newCategory = if (expectedDirection == "INCOME") {
                            if (evidence.contains("refund") || evidence.contains("reversed")) "cat-refund"
                            else if (evidence.contains("salary")) "cat-salary"
                            else if (tx.categoryId.startsWith("cat-") && tx.categoryId != "cat-other") tx.categoryId
                            else "cat-income-other"
                        } else {
                            if (evidence.contains("atm") || evidence.contains("withdrawn")) "cat-cash"
                            else tx.categoryId
                        }
                        val healedTx = tx.copy(
                            type = expectedDirection,
                            direction = newDir,
                            categoryId = newCategory,
                            updatedAt = getNowIsoString()
                        )
                        dao.insertTransaction(healedTx)
                        fixedCount++
                    }
                }
            }

            // Link existing self transfers and clean up any 3rd duplicate records
            linkExistingInternalTransfersForOwner(dao, owner)

            // Clean up orphan auto-created credit cards that have zero linked transactions
            cleanupOrphanCreditCardsForOwner(dao, owner)
        } catch (e: Exception) {
            Log.e(TAG, "Error normalizing existing transactions: ${e.message}", e)
        }
        return@withContext fixedCount
    }

    /**
     * Detects existing auto-created CardEntity and AccountEntity records with zero linked transactions
     * and marks them as inactive / orphan without deleting user data.
     */
    suspend fun cleanupOrphanCreditCards(dao: KharchaDao): Int {
        val owner = authenticatedOwner() ?: return 0
        return cleanupOrphanCreditCardsForOwner(dao, owner)
    }

    private suspend fun cleanupOrphanCreditCardsForOwner(dao: KharchaDao, owner: String): Int =
        withContext(Dispatchers.IO) {
        var markedCount = 0
        try {
            val allTxs = dao.getAllTransactionsSyncForUser(owner).filter { it.userId == owner }
            val allAccounts = dao.getAllAccountsSyncForUser(owner).filter { it.userId == owner }
            val allCards = dao.getAllCardsSyncForUser(owner).filter { it.userId == owner }

            for (acc in allAccounts) {
                if (acc.type.equals("Credit Card", ignoreCase = true) && isAutoCreatedCreditCardAccount(acc) && acc.isActive) {
                    val hasLinkedTx = allTxs.any { tx ->
                        tx.accountId == acc.id || tx.counterpartyAccountId == acc.id ||
                        (acc.last4Digits.length == 4 && tx.last4Digits == acc.last4Digits &&
                         (tx.paymentMethod.contains("card", true) || tx.note.contains("card", true)))
                    }
                    if (!hasLinkedTx) {
                        dao.insertAccount(acc.copy(isActive = false, updatedAt = getNowIsoString()))
                        markedCount++
                        Log.d(TAG, "Cleaned up orphan auto-created credit card account: ${acc.id} (${acc.name})")
                    }
                }
            }

            for (card in allCards) {
                val parentAcc = allAccounts.find { it.id == card.accountId }
                if (card.type.equals("Credit Card", ignoreCase = true) && isAutoCreatedCard(card, parentAcc)) {
                    val hasLinkedTx = allTxs.any { tx ->
                        tx.cardId == card.id || tx.accountId == card.accountId ||
                        (card.last4Digits.length == 4 && tx.last4Digits == card.last4Digits &&
                         (tx.paymentMethod.contains("card", true) || tx.note.contains("card", true)))
                    }
                    if (!hasLinkedTx && card.type != "ORPHAN_CARD") {
                        dao.insertCard(card.copy(type = "ORPHAN_CARD", updatedAt = getNowIsoString()))
                        markedCount++
                        Log.d(TAG, "Cleaned up orphan auto-created card entity: ${card.id} (${card.name})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in cleanupOrphanCreditCards: ${e.message}", e)
        }
        return@withContext markedCount
    }

    fun isAutoCreatedCreditCardAccount(account: AccountEntity): Boolean {
        if (account.id.startsWith("acc-auto-") || account.id.startsWith("acc-cc-")) return true
        if (account.id.startsWith("acc-manual-")) return false
        val isAutoPattern = account.name.contains("••••") && account.creditLimit == 0.0 && account.billingDate == 0 && account.dueDate == 0
        return isAutoPattern
    }

    fun isAutoCreatedCard(card: CardEntity, parentAccount: AccountEntity?): Boolean {
        if (card.id.startsWith("card-auto-")) return true
        if (card.id.startsWith("card-manual-")) return false
        if (parentAccount != null && parentAccount.id.startsWith("acc-manual-")) return false
        if (parentAccount != null && (parentAccount.id.startsWith("acc-cc-") || parentAccount.id.startsWith("acc-auto-"))) return true
        val isDefaultCardPattern = card.name.endsWith(" Credit Card") && card.creditLimit == 0.0 && card.billingDate == 0 && card.dueDate == 0
        val isAutoId = card.id.startsWith("card-") && card.id.length == 13 && !card.id.substring(5).all { it.isDigit() }
        return isDefaultCardPattern || isAutoId
    }

    suspend fun linkExistingInternalTransfers(dao: KharchaDao): Int {
        val owner = authenticatedOwner() ?: return 0
        return linkExistingInternalTransfersForOwner(dao, owner)
    }

    private suspend fun linkExistingInternalTransfersForOwner(dao: KharchaDao, owner: String): Int =
        withContext(Dispatchers.IO) {
        var linkedPairs = 0
        try {
            // 1. Ensure cat-transfer category exists in database
            dao.insertCategory(
                CategoryEntity(
                    id = "cat-transfer",
                    name = "Internal Transfer",
                    nameHindi = "",
                    icon = "🔄",
                    colour = "#3B82F6",
                    isDefault = true,
                    isActive = true,
                    isIncome = false,
                    createdAt = getNowIsoString(),
                    updatedAt = getNowIsoString(),
                    userId = owner
                )
            )

            // 2. Clean up any cross-source duplicates first
            cleanupDuplicateTransactionsForOwner(dao, owner)

            val accounts = dao.getAllAccountsSyncForUser(owner).filter { it.userId == owner }
            val ownedAccounts = accounts.filter { it.isOwnedByMe }
            val ownedAccountIds = ownedAccounts.map { it.id }.toSet()
            val ownedLast4s = ownedAccounts.map { it.last4Digits.trim() }.filter { it.isNotEmpty() }.toSet()

            val allTxs = dao.getAllTransactionsSyncForUser(owner)
                .filter { it.userId == owner }
                .toMutableList()
            val processedIds = mutableSetOf<String>()

            // 3. Find paired transfers between owned accounts
            for (i in 0 until allTxs.size) {
                val txA = allTxs[i]
                if (processedIds.contains(txA.id)) continue

                val isAccAOwned = txA.accountId in ownedAccountIds || 
                    (txA.last4Digits.isNotEmpty() && txA.last4Digits in ownedLast4s) ||
                    (txA.last4?.isNotEmpty() == true && txA.last4 in ownedLast4s)
                if (!isAccAOwned) continue

                val isDebA = txA.direction == "DEBIT" || txA.type == "EXPENSE" || (txA.isInternalTransfer && txA.direction != "CREDIT")

                for (j in (i + 1) until allTxs.size) {
                    val txB = allTxs[j]
                    if (processedIds.contains(txB.id)) continue

                    val isAccBOwned = txB.accountId in ownedAccountIds || 
                        (txB.last4Digits.isNotEmpty() && txB.last4Digits in ownedLast4s) ||
                        (txB.last4?.isNotEmpty() == true && txB.last4 in ownedLast4s)
                    if (!isAccBOwned) continue

                    // Amount must match
                    if (Math.abs(txA.amount - txB.amount) >= 0.01) continue

                    // Date must match
                    if (txA.date != txB.date) continue

                    // Must be different accounts
                    val diffAccount = (txA.accountId.isNotEmpty() && txB.accountId.isNotEmpty() && txA.accountId != txB.accountId) ||
                            (txA.last4Digits.isNotEmpty() && txB.last4Digits.isNotEmpty() && txA.last4Digits != txB.last4Digits)
                    if (!diffAccount) continue

                    // Must have opposite directions
                    val isDebB = txB.direction == "DEBIT" || txB.type == "EXPENSE" || (txB.isInternalTransfer && txB.direction != "CREDIT")
                    if (isDebA == isDebB) continue

                    // Pair them!
                    val debitTx = if (isDebA) txA else txB
                    val creditTx = if (isDebA) txB else txA

                    val groupId = debitTx.transferGroupId ?: creditTx.transferGroupId ?: ("tg-paired-" + UUID.randomUUID().toString().substring(0, 8))

                    val isEitherCardPayment = debitTx.transactionType == "CREDIT_CARD_BILL_PAYMENT" || debitTx.transactionType == "CARD_PAYMENT" ||
                        creditTx.transactionType == "CREDIT_CARD_BILL_PAYMENT" || creditTx.transactionType == "CARD_PAYMENT" ||
                        accounts.find { it.id == debitTx.accountId }?.type?.contains("Card", ignoreCase = true) == true ||
                        accounts.find { it.id == creditTx.accountId }?.type?.contains("Card", ignoreCase = true) == true
                    val pairedSubtype = if (isEitherCardPayment) "CREDIT_CARD_BILL_PAYMENT" else "INTERNAL_TRANSFER"
                    val pairedMerchant = if (isEitherCardPayment) "Credit Card Bill Payment" else "Internal Transfer"

                    val updatedDebit = debitTx.copy(
                        type = "INTERNAL_TRANSFER",
                        transactionType = pairedSubtype,
                        direction = "DEBIT",
                        isInternalTransfer = true,
                        isExpense = false,
                        transferGroupId = groupId,
                        counterpartyAccountId = creditTx.accountId.ifEmpty { creditTx.id },
                        categoryId = "cat-transfer",
                        merchant = pairedMerchant,
                        needsReview = false,
                        updatedAt = getNowIsoString()
                    )

                    val updatedCredit = creditTx.copy(
                        type = "INTERNAL_TRANSFER",
                        transactionType = pairedSubtype,
                        direction = "CREDIT",
                        isInternalTransfer = true,
                        isExpense = false,
                        transferGroupId = groupId,
                        counterpartyAccountId = debitTx.accountId.ifEmpty { debitTx.id },
                        categoryId = "cat-transfer",
                        merchant = pairedMerchant,
                        needsReview = false,
                        updatedAt = getNowIsoString()
                    )

                    dao.insertTransaction(updatedDebit)
                    dao.insertTransaction(updatedCredit)
                    processedIds.add(debitTx.id)
                    processedIds.add(creditTx.id)
                    linkedPairs++
                    Log.d(TAG, "Linked self-transfer pair: ${debitTx.id} (-${debitTx.amount}) and ${creditTx.id} (+${creditTx.amount}) into group $groupId")

                    // Clean up any remaining 3rd duplicate transaction that was created for the same transfer debit/credit
                    for (k in 0 until allTxs.size) {
                        val other = allTxs[k]
                        if (processedIds.contains(other.id)) continue
                        if (other.id == debitTx.id || other.id == creditTx.id) continue

                        val sameAmount = Math.abs(other.amount - debitTx.amount) < 0.01
                        val sameDate = other.date == debitTx.date
                        val isDebit = other.direction == "DEBIT" || other.type == "EXPENSE"
                        val isRedundantDuplicate = other.source == "NOTIFICATION" ||
                                other.merchant.lowercase().contains("notification") ||
                                other.id.startsWith("tx-transfer-") ||
                                (other.last4Digits.isEmpty() && isDebit)

                        if (sameAmount && sameDate && isDebit && isRedundantDuplicate) {
                            val duplicateSplits = dao.getSplitsForTransactionSync(owner, other.id)
                            if (duplicateSplits.isNotEmpty()) continue
                            dao.deleteTransactionAndSplits(owner, other.id)
                            processedIds.add(other.id)
                            Log.d(TAG, "Removed redundant 3rd transaction for self-transfer: ID=${other.id}, Merchant=${other.merchant}")
                        }
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in linkExistingInternalTransfers: ${e.message}", e)
        }
        return@withContext linkedPairs
    }

    private fun extractCounterpartyLast4(text: String): String {
        val patterns = listOf(
            Regex("(?:to|towards)\\s+[^.]*?card\\s+ending\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:to|towards)\\s+[^.]*?a/c\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:to|towards)\\s+[^.]*?account\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:to|towards)\\s+[^.]*?ending(?: in)?\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("transfer(?:red)?\\s+(?:to\\s+)?a/c\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("transfer(?:red)?\\s+to\\s+[^.]*?(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("sent\\s+(?:rs\\.?|inr|₹)?\\s*[\\d,.]*\\s*to\\s+[^.]*?ending\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("paid\\s+(?:rs\\.?|inr|₹)?\\s*[\\d,.]*\\s*to\\s+[^.]*?ending\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("transfer(?:red)?\\s+from\\s+[^.]*?\\s+to\\s+[^.]*?(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:from|by)\\s+[^.]*?a/c\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:from|by)\\s+[^.]*?account\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:from|by)\\s+[^.]*?ending(?: in)?\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("transfer(?:red)?\\s+from\\s+[^.]*?a/c\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("transfer(?:red)?\\s+from\\s+[^.]*?(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("received\\s+from\\s+[^.]*?ending\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:credited|received).*?\\bfrom\\s+[^.]*?([x*]*\\d{4,})", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val digits = match.groupValues[1].filter { it.isDigit() }
                if (digits.length >= 4) {
                    return digits.takeLast(4)
                }
            }
        }
        return extractTargetLast4(text)
    }

    private fun extractTargetLast4(text: String): String {
        val patterns = listOf(
            Regex("(?:to|towards)\\s+[^.]*?card\\s+ending\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:to|towards)\\s+[^.]*?a/c\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:to|towards)\\s+[^.]*?account\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:to|towards)\\s+[^.]*?ending(?: in)?\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("transfer(?:red)?\\s+(?:to\\s+)?a/c\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("transfer(?:red)?\\s+to\\s+[^.]*?(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("sent\\s+(?:rs\\.?|inr|₹)?\\s*[\\d,.]*\\s*to\\s+[^.]*?ending\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("paid\\s+(?:rs\\.?|inr|₹)?\\s*[\\d,.]*\\s*to\\s+[^.]*?ending\\s*[:.-]?\\s*[x*]*(\\d{2,})", RegexOption.IGNORE_CASE),
            Regex("transfer(?:red)?\\s+from\\s+[^.]*?\\s+to\\s+[^.]*?(\\d{2,})", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].takeLast(4)
            }
        }
        return ""
    }

    data class MatchedAccountResult(
        val accountId: String,
        val cardId: String?,
        val last4Digits: String,
        val needsReview: Boolean
    )

    fun matchExistingAccountOrCard(
        accounts: List<AccountEntity>,
        cards: List<CardEntity>,
        rawTx: TransactionEntity,
        textLower: String,
        address: String,
        extractedLast4: String
    ): MatchedAccountResult {
        // 1. If manual and user explicitly assigned accountId, keep it strictly
        if (rawTx.source == "MANUAL" && rawTx.accountId.isNotBlank()) {
            val userAcc = accounts.find { it.id == rawTx.accountId }
            val userCard = rawTx.cardId?.let { cardId ->
                cards.find { it.id == cardId && it.accountId == rawTx.accountId }
            }
            if (rawTx.userId.isBlank() || rawTx.userId == LEGACY_OWNER ||
                userAcc?.userId != rawTx.userId ||
                (rawTx.cardId != null && userCard?.userId != rawTx.userId)
            ) {
                return MatchedAccountResult(
                    accountId = "",
                    cardId = null,
                    last4Digits = "",
                    needsReview = true
                )
            }
            return MatchedAccountResult(
                accountId = rawTx.accountId,
                cardId = userCard?.id,
                last4Digits = rawTx.last4Digits.ifEmpty { userCard?.last4Digits ?: userAcc?.last4Digits.orEmpty() },
                needsReview = false
            )
        }

        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = extractedLast4,
            rawText = "$textLower $address ${rawTx.originalReference} ${rawTx.note}",
            source = address
        )

        if (rawTx.source.equals("SMS", ignoreCase = true) && extractedLast4.isNotBlank() &&
            TransactionIdentityResolver.extractBankName(textLower, address, extractedLast4).isBlank()
        ) {
            return MatchedAccountResult(
                accountId = "",
                cardId = null,
                last4Digits = extractedLast4.takeIf { it.length == 4 }.orEmpty(),
                needsReview = true
            )
        }
        
        val resolution = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        
        if (resolution.confidence >= 75) {
            return MatchedAccountResult(
                accountId = resolution.accountId,
                cardId = resolution.cardId,
                last4Digits = resolution.last4Digits,
                needsReview = resolution.needsReview
            )
        }

        val last4 = extractedLast4.trim()
        val isValid4DigitLast4 = last4.length == 4 && last4.all { it.isDigit() }
        val sanitizedTextLower = TransactionIdentityResolver.sanitizeTextForBankExtraction(textLower).lowercase(Locale.ENGLISH)

        // 4. Match Cash / UPI account if explicitly evident and no conflicting bank/card
        val isCash = sanitizedTextLower.contains("cash withdrawn") || sanitizedTextLower.contains("atm")
        if (isCash) {
            val cashAcc = accounts.find { it.isActive && (it.type.equals("Cash", ignoreCase = true) || it.name.contains("Cash", ignoreCase = true)) }
            if (cashAcc != null) {
                return MatchedAccountResult(
                    accountId = cashAcc.id,
                    cardId = null,
                    last4Digits = "",
                    needsReview = false
                )
            }
        }

        val isUpi = sanitizedTextLower.contains("upi") || sanitizedTextLower.contains("gpay") || sanitizedTextLower.contains("phonepe") || sanitizedTextLower.contains("paytm")
        if (isUpi && last4.isEmpty()) {
            val upiAcc = accounts.find { it.isActive && (it.type.equals("UPI", ignoreCase = true) || it.name.contains("UPI", ignoreCase = true)) }
            if (upiAcc != null) {
                return MatchedAccountResult(
                    accountId = upiAcc.id,
                    cardId = null,
                    last4Digits = upiAcc.last4Digits,
                    needsReview = false
                )
            }
        }

        // 5. NO SILENT FALLBACK: If no match is found, do NOT assign a random account.
        // Keep it for review so the user can map it later.
        return MatchedAccountResult(
            accountId = "",
            cardId = null,
            last4Digits = if (isValid4DigitLast4) last4 else "",
            needsReview = true
        )
    }

    fun normalizeBankName(name: String): String = TransactionIdentityResolver.normalizeBankName(name)

    fun isBankNameMatch(nameA: String, nameB: String): Boolean = TransactionIdentityResolver.isBankNameMatch(nameA, nameB)

    private fun extractBankName(textLower: String, address: String, last4: String = ""): String = TransactionIdentityResolver.extractBankName(textLower, address, last4)
}
