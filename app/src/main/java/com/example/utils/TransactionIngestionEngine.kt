package com.example.utils

import android.content.Context
import android.util.Log
import com.example.data.dao.KharchaDao
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

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
        return keys
    }

    /**
     * Calculates a source-independent fingerprint for a transaction when Reference ID is not available.
     */
    fun calculateFingerprint(amount: Double, date: String, last4: String, ref: String, direction: String = "DEBIT"): String {
        val cleanRef = extractNormalizedReference(ref)
        if (cleanRef.isNotEmpty()) {
            return "ref-$cleanRef"
        }
        val cleanLast4 = last4.trim().replace("[^0-9]".toRegex(), "")
        val roundedAmount = String.format(Locale.US, "%.2f", amount)
        return "dir-${direction}_amt-${roundedAmount}_date-${date}_last4-${cleanLast4}"
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
        val genericList = listOf("unknown merchant", "other", "general", "transaction", "payment", "bank transfer", "notification from app")
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
        // 1. Direction check
        val candIsDebit = candidate.direction?.equals("DEBIT", ignoreCase = true) == true ||
                (candidate.direction?.equals("CREDIT", ignoreCase = true) != true && candidate.type.equals("EXPENSE", ignoreCase = true))
        val existIsDebit = existing.direction?.equals("DEBIT", ignoreCase = true) == true ||
                (existing.direction?.equals("CREDIT", ignoreCase = true) != true && existing.type.equals("EXPENSE", ignoreCase = true))
        if (candIsDebit != existIsDebit) {
            return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Direction mismatch")
        }

        // 2. Direct originalReference check for identical source messages
        if (candidate.originalReference.isNotEmpty() && existing.originalReference.isNotEmpty() &&
            candidate.originalReference == existing.originalReference) {
            return MatchConfidence(100, isMatch = true, isHardMismatch = false, "Identical original reference string")
        }

        // 3. Normalized Reference ID check
        val candRefs = getTransactionReferenceKeys(candidate)
        val existRefs = getTransactionReferenceKeys(existing)
        if (candRefs.isNotEmpty() && existRefs.isNotEmpty()) {
            val commonRefs = candRefs.intersect(existRefs)
            if (commonRefs.isNotEmpty()) {
                return MatchConfidence(100, isMatch = true, isHardMismatch = false, "Matched reference ID: $commonRefs")
            } else {
                return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Conflicting reference IDs: $candRefs vs $existRefs")
            }
        }

        // 4. Amount check
        if (Math.abs(candidate.amount - existing.amount) >= 0.01) {
            return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Amount mismatch: ${candidate.amount} vs ${existing.amount}")
        }

        // 5. Date check (without matching reference ID, different dates cannot be merged)
        if (candidate.date != existing.date) {
            return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Date mismatch without reference ID: ${candidate.date} vs ${existing.date}")
        }

        // 5b. Time check (if times are present for both and differ by > 30 mins, treat as separate transactions)
        if (candidate.time.isNotBlank() && existing.time.isNotBlank()) {
            try {
                val candParts = candidate.time.split(":")
                val existParts = existing.time.split(":")
                if (candParts.size >= 2 && existParts.size >= 2) {
                    val candMins = candParts[0].toInt() * 60 + candParts[1].toInt()
                    val existMins = existParts[0].toInt() * 60 + existParts[1].toInt()
                    if (Math.abs(candMins - existMins) > 30) {
                        return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Time difference > 30 mins without reference ID: ${candidate.time} vs ${existing.time}")
                    }
                }
            } catch (e: Exception) {
                // Ignore time parsing errors
            }
        }

        // 6. Last 4 Digits conflict check
        val candLast4 = candidate.last4Digits.trim().replace("[^0-9]".toRegex(), "")
        val existLast4 = existing.last4Digits.trim().replace("[^0-9]".toRegex(), "")
        if (candLast4.length == 4 && existLast4.length == 4 && candLast4 != existLast4) {
            return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Last4 mismatch: $candLast4 vs $existLast4")
        }

        // 7. Merchant conflict check
        val candMerchant = candidate.merchant.trim()
        val existMerchant = existing.merchant.trim()
        if (!isMerchantCompatible(candMerchant, existMerchant)) {
            return MatchConfidence(0, isMatch = false, isHardMismatch = true, "Distinct merchant mismatch: '$candMerchant' vs '$existMerchant'")
        }

        // 8. Positive Score Calculation
        var score = 40 // Base score for same direction + same amount + same date

        // Merchant evidence
        val isExactMerchant = candMerchant.equals(existMerchant, ignoreCase = true) && candMerchant.isNotBlank() && !candMerchant.equals("Unknown Merchant", true)
        val isCompatibleMerchant = isMerchantCompatible(candMerchant, existMerchant) && candMerchant.isNotBlank() && existMerchant.isNotBlank()

        if (isExactMerchant) {
            score += 30
        } else if (isCompatibleMerchant) {
            score += 25
        } else {
            val noteOrRefMatch = candidate.note.lowercase(Locale.US).contains(existMerchant.lowercase(Locale.US)) ||
                    existing.note.lowercase(Locale.US).contains(candMerchant.lowercase(Locale.US)) ||
                    candidate.originalReference.lowercase(Locale.US).contains(existMerchant.lowercase(Locale.US)) ||
                    existing.originalReference.lowercase(Locale.US).contains(candMerchant.lowercase(Locale.US))
            if (noteOrRefMatch) score += 20
        }

        // Last4 evidence
        if (candLast4.length == 4 && existLast4.length == 4 && candLast4 == existLast4) {
            score += 20
        } else if (candLast4.length == 4 || existLast4.length == 4) {
            score += 10
        }

        // Payment method or Account evidence
        if (candidate.paymentMethod.isNotBlank() && existing.paymentMethod.isNotBlank() &&
            candidate.paymentMethod.equals(existing.paymentMethod, ignoreCase = true)) {
            score += 10
        }
        if (candidate.accountId.isNotBlank() && existing.accountId.isNotBlank() && candidate.accountId == existing.accountId) {
            score += 10
        }

        val isMatch = score >= 70
        return MatchConfidence(
            score = score,
            isMatch = isMatch,
            isHardMismatch = false,
            reasoning = "Score=$score, ExactMerchant=$isExactMerchant, CompatibleMerchant=$isCompatibleMerchant, SameLast4=${candLast4 == existLast4}"
        )
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

        val mergedAccountId = if (useIncomingIdentity && incoming.accountId.isNotEmpty()) {
            incoming.accountId
        } else {
            existing.accountId.ifEmpty { incoming.accountId }
        }

        val mergedCardId = if (useIncomingIdentity) {
            incoming.cardId ?: existing.cardId
        } else {
            existing.cardId ?: incoming.cardId
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

        val mergedNeedsReview = if (useIncomingIdentity) {
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
            merchant = mergedMerchant,
            categoryId = mergedCategoryId,
            subcategoryId = mergedSubcategoryId,
            accountId = mergedAccountId,
            cardId = mergedCardId,
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
        rawText: String
    ): Pair<TransactionEntity?, IngestionStatus> = withContext(Dispatchers.IO) {
        try {
            // Guard against promotional / marketing / advertisement messages
            if (SmsParser.isPromotionalOrAdvertisementMessage(rawText) || (rawTx.note.isNotBlank() && SmsParser.isPromotionalOrAdvertisementMessage(rawTx.note))) {
                Log.d(TAG, "Ingestion Skipped: Message detected as promotional/advertisement (${rawTx.amount})")
                return@withContext Pair(null, IngestionStatus.IGNORED)
            }

            val db = AppDatabase.getDatabase(context)
            val dao = db.kharchaDao()
            
            val countBefore = dao.getAllTransactionsSync().size
            Log.d(TAG, "Ingestion Start: Room count before = $countBefore")
            Log.d(TAG, "Processing SMS/Source: ${rawTx.source}, Amount: ${rawTx.amount}, Date: ${rawTx.date}")

            val existingTxs = dao.getAllTransactionsSync()
            val accounts = dao.getAllAccountsSync()
            val cards = dao.getAllCardsSync()

            val textLower = rawText.lowercase(Locale.ENGLISH)

            // Direction Determinations (Debit vs Credit)
            val isDebit = rawTx.type.uppercase(Locale.ENGLISH) == "EXPENSE"
            val direction = if (isDebit) "DEBIT" else "CREDIT"
            val rawTx = rawTx.copy(direction = direction)

            // 1. Calculate Source-Independent Fingerprint
            val fingerprint = calculateFingerprint(
                amount = rawTx.amount,
                date = rawTx.date,
                last4 = rawTx.last4Digits,
                ref = rawTx.transactionReference,
                direction = direction
            )

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

            // Prepare potential auto-creation ONLY when reliable bank/issuer + valid 4-digit last4 evidence exists.
            // CRITICAL: We DO NOT insert into Room immediately. We defer insertion until the transaction is successfully accepted!
            var pendingNewAccount: AccountEntity? = null
            var pendingNewCard: CardEntity? = null

            if (needsReview && isValid4DigitLast4 && bankName.isNotBlank()) {
                if (isCreditCard) {
                    val existingCard = cards.find { card ->
                        card.last4Digits == extractedLast4 && card.type.equals("Credit Card", ignoreCase = true) &&
                        (isBankNameMatch(card.name, bankName) || 
                         accounts.any { acc -> acc.id == card.accountId && isBankNameMatch(acc.bankName.ifEmpty { acc.name }, bankName) })
                    }
                    val existingCardAcc = accounts.find { acc ->
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
                            updatedAt = getNowIsoString()
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
                            updatedAt = getNowIsoString()
                        )
                        val newCard = CardEntity(
                            id = newCardId,
                            accountId = newAccId,
                            name = "$bankName Credit Card",
                            type = "Credit Card",
                            last4Digits = extractedLast4,
                            createdAt = getNowIsoString(),
                            updatedAt = getNowIsoString()
                        )
                        pendingNewAccount = newAcc
                        pendingNewCard = newCard
                        mappedAccountId = newAccId
                        mappedCardId = newCardId
                        mappedLast4 = extractedLast4
                        needsReview = false
                    }
                } else {
                    val existingAcc = accounts.find { acc ->
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
                            updatedAt = getNowIsoString()
                        )
                        pendingNewAccount = newAcc
                        mappedAccountId = newAccId
                        mappedLast4 = extractedLast4
                        needsReview = false
                    }
                }
            }

            // 3. Centralized Robust Deduplication Check (Rules 1-8)
            val duplicateTx = existingTxs.find { existing ->
                isDuplicateTransaction(rawTx, existing)
            }

            // Check if there is an auto-generated internal transfer counterpart waiting for confirmation
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
                val updatedCounterpart = pendingCounterpart.copy(
                    note = rawTx.note.ifEmpty { pendingCounterpart.note },
                    originalReference = rawTx.originalReference.ifEmpty { pendingCounterpart.originalReference },
                    transactionReference = rawTx.transactionReference.ifEmpty { pendingCounterpart.transactionReference },
                    updatedAt = getNowIsoString()
                )
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
                // If a new AccountEntity/CardEntity was discovered during ingestion and it is not needsReview,
                // persist it so it is not lost when merging metadata into an existing duplicate record.
                var effectiveAccounts = accounts
                var effectiveCards = cards

                // Automatically persist created AccountEntity/CardEntity so accountId/cardId always point to a valid Room record
                if (pendingNewAccount != null) {
                    dao.insertAccount(pendingNewAccount)
                    effectiveAccounts = if (accounts.none { it.id == pendingNewAccount.id }) {
                        accounts + pendingNewAccount
                    } else accounts
                    Log.d(TAG, "Auto-created AccountEntity persisted on duplicate merge: ${pendingNewAccount.id} (${pendingNewAccount.name})")
                }
                if (pendingNewCard != null) {
                    dao.insertCard(pendingNewCard)
                    effectiveCards = if (cards.none { it.id == pendingNewCard.id }) {
                        cards + pendingNewCard
                    } else cards
                    Log.d(TAG, "Auto-created CardEntity persisted on duplicate merge: ${pendingNewCard.id} (${pendingNewCard.name})")
                }

                // Prepare a metadata-enriched version of the incoming transaction for merging
                val enrichedIncoming = rawTx.copy(
                    accountId = mappedAccountId,
                    cardId = mappedCardId,
                    last4Digits = mappedLast4 ?: rawTx.last4Digits,
                    last4 = mappedLast4 ?: rawTx.last4Digits,
                    needsReview = needsReview,
                    transactionType = if (rawTx.type == "EXPENSE") "EXPENSE" else "INCOME"
                )
                
                // Intelligently merge metadata without creating duplicate records (Rule 5, 6)
                val mergedTx = mergeTransactionMetadata(duplicateTx, enrichedIncoming, effectiveAccounts, effectiveCards)
                val rowId = dao.insertTransaction(mergedTx)
                if (rowId != -1L) {
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
                (textLower.contains("bill payment") && (textLower.contains("credit card") || textLower.contains("card"))) ||
                Regex("(?:bill\\s+)?payment\\s+.*?\\s+(?:towards|for|to|of)\\s+.*?card", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                Regex("paid\\s+.*?\\s+(?:towards|for|to)?\\s*.*?credit\\s*card", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                Regex("payment\\s+received\\s+.*?\\s+(?:towards|for|to)\\s+.*?card", RegexOption.IGNORE_CASE).containsMatchIn(textLower) ||
                (textLower.contains("thank you") && textLower.contains("payment") && (textLower.contains("credit card") || textLower.contains("card"))) ||
                (textLower.contains("credit card") && (textLower.contains("payment received") || textLower.contains("bill paid") || textLower.contains("payment of") || textLower.contains("autopay"))) ||
                Regex("(?:paid|transferred)\\s+(?:rs\\.?|inr|₹)?\\s*[\\d,.]*\\s*(?:to|towards)\\s+.*?card", RegexOption.IGNORE_CASE).containsMatchIn(textLower)

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
                    val matchedCreditCard = if (effectiveCounterparty4.isNotEmpty()) {
                        cards.find { it.last4Digits == effectiveCounterparty4 && (targetBank.isEmpty() || isBankNameMatch(it.name, targetBank)) }
                    } else null
                    val matchedCreditAcc = if (effectiveCounterparty4.isNotEmpty()) {
                        accounts.find { it.isOwnedByMe && it.last4Digits == effectiveCounterparty4 && it.type.equals("Credit Card", ignoreCase = true) && (targetBank.isEmpty() || isBankNameMatch(it.bankName.ifEmpty { it.name }, targetBank)) }
                    } else null

                    val targetCreditAccId = matchedCreditAcc?.id ?: matchedCreditCard?.accountId
                    isInternal = true
                    finalTxType = "CREDIT_CARD_BILL_PAYMENT"

                    if (targetCreditAccId != null) {
                        if (mappedAccountId == targetCreditAccId) {
                            val bankSourceAcc = accounts.find { it.isOwnedByMe && it.id != targetCreditAccId && !it.type.equals("Credit Card", ignoreCase = true) }
                            if (bankSourceAcc != null) {
                                mappedAccountId = bankSourceAcc.id
                                mappedCardId = null
                            }
                        }
                        if (targetCreditAccId != mappedAccountId) {
                            transferGroupId = "tg-internal-" + UUID.randomUUID().toString().substring(0, 8)
                            counterpartyId = targetCreditAccId
                            needsReview = false
                        } else {
                            counterpartyId = null
                            needsReview = true
                        }
                    } else {
                        // Reliable target credit card could not be identified: keep transaction safe as CREDIT_CARD_BILL_PAYMENT, do not guess
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
                        finalTxType = if (targetAccount.type.lowercase().contains("card")) "CREDIT_CARD_BILL_PAYMENT" else "INTERNAL_TRANSFER"
                        needsReview = false
                    } else if (targetCard != null) {
                        isInternal = true
                        transferGroupId = "tg-internal-" + UUID.randomUUID().toString().substring(0, 8)
                        counterpartyId = targetCard.accountId
                        finalTxType = "CREDIT_CARD_BILL_PAYMENT"
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

            val resolvedMerchantName = if ((finalTxType == "CREDIT_CARD_BILL_PAYMENT" || finalTxType == "CARD_PAYMENT") && (rawTx.merchant.isBlank() || rawTx.merchant.equals("Unknown Merchant", true))) {
                "Credit Card Bill Payment"
            } else if (isInternal && (rawTx.merchant.isBlank() || rawTx.merchant.equals("Unknown Merchant", true) || rawTx.merchant.equals("Other", true))) {
                "Internal Transfer"
            } else rawTx.merchant

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
                type = if (isInternal) "INTERNAL_TRANSFER" else (if (isDebit) "EXPENSE" else "INCOME"),
                transactionType = finalTxType,
                direction = direction,
                duplicateFingerprint = fingerprint,
                isInternalTransfer = isInternal,
                transferGroupId = transferGroupId,
                counterpartyAccountId = counterpartyId,
                categoryId = categoryIdToUse,
                subcategoryId = subcategoryIdToUse,
                merchant = resolvedMerchantName,
                needsReview = finalNeedsReview,
                isExpense = if (isInternal) false else isDebit,
                updatedAt = getNowIsoString()
            )

            // D: Auto-Pairing with real counterpart transactions arriving from other sources
            if (sourceOwned) {
                val isIncomingDebit = direction == "DEBIT" || rawTx.type == "EXPENSE"
                val pairingMatch = existingTxs.find { other ->
                    other.id != rawTx.id &&
                    other.date == rawTx.date &&
                    Math.abs(other.amount - rawTx.amount) < 0.01 &&
                    ((isIncomingDebit && (other.direction == "CREDIT" || other.type == "INCOME")) ||
                     (!isIncomingDebit && (other.direction == "DEBIT" || other.type == "EXPENSE"))) &&
                    other.accountId != mappedAccountId &&
                    (accounts.find { it.id == other.accountId }?.isOwnedByMe == true) &&
                    (counterpartyId == null || other.accountId == counterpartyId)
                }

                if (pairingMatch != null) {
                    val groupToUse = pairingMatch.transferGroupId ?: transferGroupId ?: ("tg-paired-" + UUID.randomUUID().toString().substring(0, 8))
                    val isEitherCardPayment = finalTxType == "CREDIT_CARD_BILL_PAYMENT" || finalTxType == "CARD_PAYMENT" ||
                        pairingMatch.transactionType == "CREDIT_CARD_BILL_PAYMENT" || pairingMatch.transactionType == "CARD_PAYMENT" ||
                        accounts.find { it.id == mappedAccountId }?.type?.contains("Card", ignoreCase = true) == true ||
                        accounts.find { it.id == pairingMatch.accountId }?.type?.contains("Card", ignoreCase = true) == true
                    val pairedType = if (isEitherCardPayment) "CREDIT_CARD_BILL_PAYMENT" else "INTERNAL_TRANSFER"

                    dao.insertCategory(
                        CategoryEntity("cat-transfer", "Internal Transfer", "", "🔄", "#3B82F6", true, true, false, getNowIsoString(), getNowIsoString())
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
                    dao.insertTransaction(updatedOther)
                    Log.d(TAG, "Successfully paired real transactions into group: $groupToUse (Subtype: $pairedType)")
                }
            }

            // Insert primary transaction
            val rowId = dao.insertTransaction(finalTx)
            
            if (rowId != -1L) {
                val countAfter = dao.getAllTransactionsSync().size
                Log.d(TAG, "Room insert SUCCESS: ID=${finalTx.id}, RowId=$rowId, CountAfter=$countAfter")
                
                // Automatic Account or Card creation upon successful transaction insertion
                if (pendingNewAccount != null) {
                    dao.insertAccount(pendingNewAccount)
                    Log.d(TAG, "Auto-created AccountEntity inserted: ${pendingNewAccount.id} (${pendingNewAccount.name})")
                }
                if (pendingNewCard != null) {
                    dao.insertCard(pendingNewCard)
                    Log.d(TAG, "Auto-created CardEntity inserted: ${pendingNewCard.id} (${pendingNewCard.name})")
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

    /**
     * One-time cleanup / migration to remove duplicate transactions in the database,
     * merging useful metadata into a single preserved record per transaction (Rule 9, 10, 11).
     */
    suspend fun cleanupDuplicateTransactions(dao: KharchaDao): Int = withContext(Dispatchers.IO) {
        var removedCount = 0
        try {
            val allTxs = dao.getAllTransactionsSync()
            if (allTxs.size <= 1) return@withContext 0

            val accounts = dao.getAllAccountsSync()
            val cards = dao.getAllCardsSync()

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
                            merged = mergeTransactionMetadata(merged, dup, accounts, cards)
                            // Preserve splits
                            val splits = dao.getSplitsForTransactionSync(dup.id)
                            if (splits.isNotEmpty()) {
                                val remappedSplits = splits.map { it.copy(transactionId = primary.id) }
                                dao.insertSplits(remappedSplits)
                                dao.deleteSplitsForTransaction(dup.id)
                            }
                            dao.deleteTransaction(dup.id)
                            processedIds.add(dup.id)
                            removedCount++
                        }
                    }
                    dao.insertTransaction(merged)
                    processedIds.add(primary.id)
                }
            }

            // 2. Pass: Secondary conservative fingerprint deduplication for transactions without reference ID
            val remainingTxs = dao.getAllTransactionsSync()
            val remainingList = remainingTxs.filter { !processedIds.contains(it.id) }.toMutableList()

            var i = 0
            while (i < remainingList.size) {
                val current = remainingList[i]
                val candidateDups = remainingList.filterIndexed { index, other -> index > i && isDuplicateTransaction(current, other) }
                if (candidateDups.isNotEmpty()) {
                    var merged = current
                    for (dup in candidateDups) {
                        merged = mergeTransactionMetadata(
                            merged,
                            dup,
                            accounts,
                            cards
                        )
                        val splits = dao.getSplitsForTransactionSync(dup.id)
                        if (splits.isNotEmpty()) {
                            val remappedSplits = splits.map { it.copy(transactionId = current.id) }
                            dao.insertSplits(remappedSplits)
                            dao.deleteSplitsForTransaction(dup.id)
                        }
                        dao.deleteTransaction(dup.id)
                        remainingList.remove(dup)
                        processedIds.add(dup.id)
                        removedCount++
                    }
                    dao.insertTransaction(merged)
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

    suspend fun cleanupHistoricalIncorrectSmsTransactions(dao: KharchaDao): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        try {
            val allTxs = dao.getAllTransactionsSync()
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
                    dao.deleteTransaction(tx.id)
                    Log.d("DatabaseCleanup", "Deleted non-financial or legacy record: ID=${tx.id}, Merchant=${tx.merchant}, Amount=${tx.amount}")
                    deletedCount++
                }
            }
        } catch (e: Exception) {
            Log.e("DatabaseCleanup", "Error cleaning up incorrect records: ${e.message}", e)
        }
        return@withContext deletedCount
    }

    suspend fun normalizeExistingTransactions(dao: KharchaDao): Int = withContext(Dispatchers.IO) {
        // Run database cleanup of historical incorrect records first!
        cleanupHistoricalIncorrectSmsTransactions(dao)

        var fixedCount = 0
        try {
            val allTxs = dao.getAllTransactionsSync()
            for (tx in allTxs) {
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
            linkExistingInternalTransfers(dao)

            // Clean up orphan auto-created credit cards that have zero linked transactions
            cleanupOrphanCreditCards(dao)
        } catch (e: Exception) {
            Log.e(TAG, "Error normalizing existing transactions: ${e.message}", e)
        }
        return@withContext fixedCount
    }

    /**
     * Detects existing auto-created CardEntity and AccountEntity records with zero linked transactions
     * and marks them as inactive / orphan without deleting user data.
     */
    suspend fun cleanupOrphanCreditCards(dao: KharchaDao): Int = withContext(Dispatchers.IO) {
        var markedCount = 0
        try {
            val allTxs = dao.getAllTransactionsSync()
            val allAccounts = dao.getAllAccountsSync()
            val allCards = dao.getAllCardsSync()

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

    suspend fun linkExistingInternalTransfers(dao: KharchaDao): Int = withContext(Dispatchers.IO) {
        var linkedPairs = 0
        try {
            // 1. Ensure cat-transfer category exists in database
            dao.insertCategory(
                CategoryEntity("cat-transfer", "Internal Transfer", "", "🔄", "#3B82F6", true, true, false, getNowIsoString(), getNowIsoString())
            )

            // 2. Clean up any cross-source duplicates first
            cleanupDuplicateTransactions(dao)

            val accounts = dao.getAllAccountsSync()
            val ownedAccounts = accounts.filter { it.isOwnedByMe }
            val ownedAccountIds = ownedAccounts.map { it.id }.toSet()
            val ownedLast4s = ownedAccounts.map { it.last4Digits.trim() }.filter { it.isNotEmpty() }.toSet()

            val allTxs = dao.getAllTransactionsSync().toMutableList()
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
                            dao.deleteTransaction(other.id)
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
            return MatchedAccountResult(
                accountId = rawTx.accountId,
                cardId = rawTx.cardId,
                last4Digits = rawTx.last4Digits.ifEmpty { userAcc?.last4Digits ?: "" },
                needsReview = false
            )
        }

        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = extractedLast4,
            rawText = "$textLower $address ${rawTx.originalReference} ${rawTx.note}",
            source = address
        )
        
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
