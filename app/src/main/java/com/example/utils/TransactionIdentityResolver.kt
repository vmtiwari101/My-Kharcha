package com.example.utils

import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import java.util.Locale

/**
 * Universal identity resolution engine for transaction accounts and cards.
 * Implements centralized matching rules, evidence priority, and ambiguity handling.
 */
object TransactionIdentityResolver {

    data class IdentityEvidence(
        val last4: String = "",
        val bankNameCandidate: String = "",
        val cardTypeCandidate: String = "",
        val paymentMethodCandidate: String = "",
        val rawText: String = "",
        val source: String = ""
    )

    data class ResolutionResult(
        val accountId: String = "",
        val cardId: String? = null,
        val last4Digits: String = "",
        val needsReview: Boolean = true,
        val confidence: Int = 0,
        val paymentMethod: String = ""
    )

    /**
     * Resolves the identity of the account/card used in a transaction.
     */
    fun resolveIdentity(
        evidence: IdentityEvidence,
        accounts: List<AccountEntity>,
        cards: List<CardEntity>
    ): ResolutionResult {
        val last4 = evidence.last4.trim()
        val textLower = evidence.rawText.lowercase(Locale.ENGLISH)
        val methodText = "${evidence.rawText} ${evidence.source}".trim()
        val bankNameCandidate = evidence.bankNameCandidate.ifEmpty { extractBankName(textLower, evidence.source, last4) }.trim()
        val bankName = sanitizeBankName(bankNameCandidate)

        // 1. High Confidence: EXACT Last-4 (exactly 4 digits) + Bank Name Match
        if (last4.length == 4 && bankName.isNotEmpty()) {
            // Check cards first
            val matchedCards = cards.filter { card ->
                card.last4Digits == last4 && 
                isBankNameMatch(card.name, bankName, accounts.find { it.id == card.accountId })
            }
            if (matchedCards.size == 1) {
                return ResolutionResult(
                    accountId = matchedCards[0].accountId,
                    cardId = matchedCards[0].id,
                    last4Digits = matchedCards[0].last4Digits,
                    needsReview = false,
                    confidence = 100,
                    paymentMethod = evidence.paymentMethodCandidate.ifEmpty { resolvePaymentMethod(methodText, "Credit Card") }
                )
            }

            // Check accounts
            val matchedAccs = accounts.filter { acc ->
                acc.last4Digits == last4 && 
                isBankNameMatch(acc.bankName.ifEmpty { acc.name }, bankName, null)
            }
            if (matchedAccs.size == 1) {
                return ResolutionResult(
                    accountId = matchedAccs[0].id,
                    cardId = null,
                    last4Digits = matchedAccs[0].last4Digits,
                    needsReview = false,
                    confidence = 95,
                    paymentMethod = evidence.paymentMethodCandidate.ifEmpty { resolvePaymentMethod(methodText, matchedAccs[0].type) }
                )
            }
        }

        // 2. Medium Confidence: Unique EXACT Last-4 Match (Safety first: only if no bank conflict)
        if (last4.length == 4) {
            val matchedCards = cards.filter { it.last4Digits == last4 }
            val matchedAccs = accounts.filter { it.last4Digits == last4 }

            // Deduplicate: If a card is matched, its account is also matched.
            val uniqueAccountIds = (matchedCards.map { it.accountId } + matchedAccs.map { it.id }).toSet()

            if (uniqueAccountIds.size == 1) {
                val matchedAccountId = uniqueAccountIds.first()
                val matchedAcc = accounts.find { it.id == matchedAccountId }
                val matchedCard = matchedCards.find { it.accountId == matchedAccountId }

                // If bank name was extracted and it CONFLICTS with the unique match, we should be cautious
                val potentialMatchBank = matchedAcc?.bankName?.ifEmpty { matchedAcc.name } ?: matchedCard?.name ?: ""

                if (bankName.isEmpty() || isBankNameMatch(potentialMatchBank, bankName, null)) {
                    return ResolutionResult(
                        accountId = matchedAccountId,
                        cardId = matchedCard?.id,
                        last4Digits = last4,
                        needsReview = false,
                        confidence = 95, // High confidence for exact 4-digit unique match
                        paymentMethod = evidence.paymentMethodCandidate.ifEmpty { resolvePaymentMethod(methodText, matchedCard?.type ?: matchedAcc?.type) }
                    )
                }
            } else if (uniqueAccountIds.size > 1) {
                // Ambiguity detected! Try to filter by bank name if available
                if (bankName.isNotEmpty()) {
                    val filteredCards = matchedCards.filter { card -> isBankNameMatch(card.name, bankName, accounts.find { acc -> acc.id == card.accountId }) }
                    val filteredAccs = matchedAccs.filter { acc -> isBankNameMatch(acc.bankName.ifEmpty { acc.name }, bankName, null) }
                    
                    val filteredAccountIds = (filteredCards.map { it.accountId } + filteredAccs.map { it.id }).toSet()
                    
                    if (filteredAccountIds.size == 1) {
                        val matchedAccountId = filteredAccountIds.first()
                        val matchedAcc = accounts.find { it.id == matchedAccountId }
                        val matchedCard = matchedCards.find { it.accountId == matchedAccountId }
                        
                        return ResolutionResult(
                            accountId = matchedAccountId,
                            cardId = matchedCard?.id,
                            last4Digits = last4,
                            needsReview = false,
                            confidence = 95,
                            paymentMethod = evidence.paymentMethodCandidate.ifEmpty { resolvePaymentMethod(methodText, matchedCard?.type ?: matchedAcc?.type) }
                        )
                    }
                }
                
                return ResolutionResult(
                    accountId = "",
                    cardId = null,
                    last4Digits = last4,
                    needsReview = true,
                    confidence = 30, // Low confidence due to ambiguity
                    paymentMethod = resolvePaymentMethod(methodText)
                )
            }
        }

        // 3. Partial last4 (2 or 3 digits) - NEVER high confidence
        if (last4.length in 2..3) {
            val matchedCards = cards.filter { it.last4Digits.endsWith(last4) }
            val matchedAccs = accounts.filter { it.last4Digits.endsWith(last4) }
            
            if (matchedCards.size + matchedAccs.size == 1 && bankName.isNotEmpty()) {
                 val potentialMatchBank = if (matchedCards.isNotEmpty()) {
                    val acc = accounts.find { it.id == matchedCards[0].accountId }
                    acc?.bankName?.ifEmpty { acc.name } ?: matchedCards[0].name
                } else {
                    matchedAccs[0].bankName.ifEmpty { matchedAccs[0].name }
                }
                
                if (isBankNameMatch(potentialMatchBank, bankName)) {
                    // Match found with partial last4 and bank, but confidence MUST NOT be high (90+)
                    return if (matchedCards.isNotEmpty()) {
                        ResolutionResult(
                            accountId = matchedCards[0].accountId,
                            cardId = matchedCards[0].id,
                            last4Digits = matchedCards[0].last4Digits,
                            needsReview = true, // Partial match requires review
                            confidence = 60,
                            paymentMethod = resolvePaymentMethod(methodText, "Credit Card")
                        )
                    } else {
                        ResolutionResult(
                            accountId = matchedAccs[0].id,
                            cardId = null,
                            last4Digits = matchedAccs[0].last4Digits,
                            needsReview = true,
                            confidence = 60,
                            paymentMethod = resolvePaymentMethod(methodText, matchedAccs[0].type)
                        )
                    }
                }
            }
        }

        // 3. Unknown account with last4 - Store it for review, DO NOT assign fallback
        if (last4.isNotEmpty()) {
            return ResolutionResult(
                accountId = "",
                cardId = null,
                last4Digits = last4,
                needsReview = true,
                confidence = 10
            )
        }

        // 4. Low Confidence: Bank Name Only (Contextual)
        if (bankName.isNotEmpty()) {
             val bankAccs = accounts.filter { isBankNameMatch(it.bankName.ifEmpty { it.name }, bankName, null) }
             if (bankAccs.size == 1 && bankAccs[0].isDefault) {
                 return ResolutionResult(
                     accountId = bankAccs[0].id,
                     cardId = null,
                     last4Digits = bankAccs[0].last4Digits,
                     needsReview = false,
                     confidence = 80,
                     paymentMethod = evidence.paymentMethodCandidate.ifEmpty { resolvePaymentMethod(methodText, bankAccs[0].type) }
                 )
             }
        }

        // 5. Absolute Fallback
        return ResolutionResult(
            accountId = "",
            cardId = null,
            last4Digits = "",
            needsReview = true,
            confidence = 0
        )
    }

    private fun isLast4Match(stored: String, incoming: String): Boolean {
        if (stored.isEmpty() || incoming.isEmpty()) return false
        return if (incoming.length >= 4) {
            stored.endsWith(incoming.takeLast(4))
        } else {
            stored.endsWith(incoming)
        }
    }

    fun sanitizeTextForBankExtraction(rawText: String): String {
        return rawText
            .replace(Regex("(?i)(?:powered|provided|supported)\\s*[:\\-]?\\s*by\\s*[:\\-]?\\s*[a-z0-9\\s/]{1,40}(?:bank)?"), " ")
            .replace(Regex("(?i)upi\\s*[:\\-]?\\s*psp\\s*[a-z0-9\\s/]{1,40}"), " ")
            .replace(Regex("(?i)partner\\s*[:\\-]?\\s*bank\\s*[a-z0-9\\s/]{1,40}"), " ")
    }

    private fun sanitizeBankName(name: String): String {
        return name.trim().lowercase(Locale.ENGLISH)
            .replace("bank", "")
            .replace("limited", "")
            .replace("ltd", "")
            .replace("corp", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isBankNameMatch(storedName: String, candidateName: String, parentAcc: AccountEntity? = null): Boolean {
        if (storedName.isBlank() || candidateName.isBlank()) return false
        
        val normStored = sanitizeBankName(storedName)
        val normCandidate = sanitizeBankName(candidateName)
        
        if (normStored == normCandidate) return true
        if (normStored.contains(normCandidate) || normCandidate.contains(normStored)) return true
        
        // Try normalized identifiers (HDFC, SBI, etc.)
        val idStored = normalizeBankName(storedName)
        val idCandidate = normalizeBankName(candidateName)
        if (idStored.isNotEmpty() && idCandidate.isNotEmpty() && idStored == idCandidate) return true
        
        if (parentAcc != null) {
            val parentBankName = parentAcc.bankName.ifEmpty { parentAcc.name }
            if (isBankNameMatch(parentBankName, candidateName, null)) return true
        }
        
        return false
    }

    fun normalizeBankName(name: String): String {
        val lower = name.lowercase(Locale.ENGLISH).replace("[^a-z0-9]".toRegex(), "")
        
        // Reject common transaction/alert keywords that aren't bank/issuer names
        val ignoredWords = setOf(
            "debit", "debited", "credit", "credited", "paid", "payment", "spent", "received", 
            "transfer", "transferred", "withdrawn", "withdrawal", "your", "dear", "customer", 
            "ending", "for", "account", "card", "alert", "notice", "at", "to", "from", "in", 
            "on", "amount", "rs", "inr", "rupees", "balance", "limit", "available", "avbl", 
            "bal", "ref", "reference", "txn", "transaction", "using", "via", "through", 
            "with", "dated", "date", "statement", "total", "minimum", "outstanding", "close", 
            "closing", "pay", "payee", "bank", "sms", "alert", "alerts", "vpa", "upi"
        )
        
        if (ignoredWords.contains(lower)) {
            return ""
        }
        
        return when {
            lower.contains("hdfc") -> "hdfc"
            lower.contains("icici") -> "icici"
            lower.contains("sbi") || lower.contains("statebank") -> "sbi"
            lower.contains("axis") -> "axis"
            lower.contains("kotak") -> "kotak"
            lower.contains("pnb") || lower.contains("punjabnational") -> "pnb"
            lower.contains("baroda") || lower.contains("bob") -> "bob"
            lower.contains("union") -> "union"
            lower.contains("indusind") -> "indusind"
            lower.contains("yesbank") || lower.contains("yesbk") || (lower.contains("yes") && !lower.contains("eyes")) -> "yes"
            lower.contains("idfc") -> "idfc"
            lower.contains("canara") -> "canara"
            lower.contains("aubank") || lower.contains("ausmall") || lower.startsWith("au") -> "au"
            lower.contains("rbl") -> "rbl"
            lower.contains("federal") -> "federal"
            lower.contains("standardchartered") || lower.contains("scb") -> "scb"
            else -> {
                val cleanWord = lower.replace("[^a-z0-9]".toRegex(), "")
                if (cleanWord.contains("bank") || cleanWord.contains("card") || (cleanWord.length >= 3 && !ignoredWords.any { cleanWord.contains(it) })) {
                    cleanWord
                } else {
                    ""
                }
            }
        }
    }

    fun extractLast4(text: String): String {
        val patterns = listOf(
            Regex("(?:debited\\s+from|paid\\s+from|a/c|ac|account|card)\\s*(?:no\\.?)?\\s*\\(?\\s*(?:ending(?:\\s*(?:in|with))?)?\\s*[:.-]?\\s*[xX*]*\\s*\\(?(\\d{2,})\\)?", RegexOption.IGNORE_CASE),
            Regex("\\b(?:ending(?:\\s*(?:in|with))?)\\s*[:.-]?\\s*[xX*]*\\s*\\(?(\\d{2,})\\)?", RegexOption.IGNORE_CASE),
            Regex("[xX*]{2,}\\s*\\(?(\\d{2,})\\)?", RegexOption.IGNORE_CASE),
            Regex("\\b[xX*]\\s*\\(?(\\d{4})\\)?\\b"),
            Regex("(?:a/c|ac|account|card)\\s*[:.-]?\\s*[xX*]*\\s*\\(?(\\d{2,})\\)?", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val digits = match.groupValues[1]
                if (digits.length >= 2) {
                    return digits.takeLast(4)
                }
            }
        }
        return ""
    }

    fun extractBankName(textLower: String, address: String = "", last4: String = ""): String {
        val cleanAddr = sanitizeTextForBankExtraction(address).uppercase(Locale.ENGLISH)
        if (cleanAddr.length in 3..15 && !cleanAddr.contains(" ") && !cleanAddr.contains("RS") && !cleanAddr.contains("DEBITED") && !cleanAddr.contains("CREDITED")) {
            val bankFromAddr = when {
                cleanAddr.contains("HDFC") -> "HDFC Bank"
                cleanAddr.contains("SBI") -> "SBI"
                cleanAddr.contains("ICICI") -> "ICICI Bank"
                cleanAddr.contains("AXIS") -> "Axis Bank"
                cleanAddr.contains("IDFC") -> "IDFC FIRST Bank"
                cleanAddr.contains("KOTAK") -> "Kotak Bank"
                cleanAddr.contains("PNB") -> "PNB"
                cleanAddr.contains("BARODA") || cleanAddr.contains("BOB") -> "Bank of Baroda"
                cleanAddr.contains("UNION") -> "Union Bank"
                cleanAddr.contains("INDUS") -> "IndusInd Bank"
                cleanAddr.contains("YES") -> "YES Bank"
                cleanAddr.contains("CANARA") -> "Canara Bank"
                cleanAddr.contains("AUBANK") || cleanAddr.contains("AU-") -> "AU Bank"
                cleanAddr.contains("RBL") -> "RBL Bank"
                cleanAddr.contains("FED") -> "Federal Bank"
                cleanAddr.contains("SCB") || cleanAddr.contains("STAN") -> "Standard Chartered"
                else -> ""
            }
            if (bankFromAddr.isNotEmpty()) return bankFromAddr
        }

        val sanitizedText = sanitizeTextForBankExtraction(textLower).lowercase(Locale.ENGLISH)

        // If a specific 4-digit last4 is present, look for the bank name in close proximity to that account/card number
        if (last4.length >= 2) {
            val nearPatterns = listOf(
                Regex("([a-z0-9\\s]{2,20}?)(?:bank)?\\s+(?:a/c|ac|account|card)\\s*\\(?\\s*(?:ending(?:\\s*(?:in|with))?)?\\s*[:.-]?\\s*[xX*]*\\s*\\(?$last4\\)?", RegexOption.IGNORE_CASE),
                Regex("(?:from|to|in|at)\\s+([a-z0-9\\s]{2,20}?)(?:bank)?\\s+[^.]*?\\(?$last4\\)?", RegexOption.IGNORE_CASE)
            )
            for (pat in nearPatterns) {
                val m = pat.find(sanitizedText)
                if (m != null) {
                    val candidate = m.groupValues[1].trim()
                    val norm = normalizeBankName(candidate)
                    if (norm.isNotEmpty()) {
                        return when (norm) {
                            "hdfc" -> "HDFC Bank"
                            "icici" -> "ICICI Bank"
                            "sbi" -> "SBI"
                            "axis" -> "Axis Bank"
                            "kotak" -> "Kotak Bank"
                            "pnb" -> "PNB"
                            "bob" -> "Bank of Baroda"
                            "union" -> "Union Bank"
                            "indusind" -> "IndusInd Bank"
                            "yes" -> "YES Bank"
                            "idfc" -> "IDFC FIRST Bank"
                            "canara" -> "Canara Bank"
                            "au" -> "AU Bank"
                            "rbl" -> "RBL Bank"
                            "federal" -> "Federal Bank"
                            "scb" -> "Standard Chartered"
                            else -> candidate
                        }
                    }
                }
            }
        }

        return when {
            sanitizedText.contains("hdfc") -> "HDFC Bank"
            sanitizedText.contains("icici") -> "ICICI Bank"
            sanitizedText.contains("sbi") || sanitizedText.contains("state bank") -> "SBI"
            sanitizedText.contains("axis") -> "Axis Bank"
            sanitizedText.contains("kotak") -> "Kotak Bank"
            sanitizedText.contains("idfc") -> "IDFC FIRST Bank"
            sanitizedText.contains("pnb") || sanitizedText.contains("punjab national") -> "PNB"
            sanitizedText.contains("bank of baroda") || sanitizedText.contains("bob") -> "Bank of Baroda"
            sanitizedText.contains("union bank") -> "Union Bank"
            sanitizedText.contains("indusind") -> "IndusInd Bank"
            sanitizedText.contains("yes bank") || sanitizedText.contains("yesbank") -> "YES Bank"
            sanitizedText.contains("canara") -> "Canara Bank"
            sanitizedText.contains("au bank") || sanitizedText.contains("aubank") || sanitizedText.contains("au small") -> "AU Bank"
            sanitizedText.contains("rbl") -> "RBL Bank"
            sanitizedText.contains("federal bank") -> "Federal Bank"
            sanitizedText.contains("scb") || sanitizedText.contains("standard chartered") -> "Standard Chartered"
            else -> {
                val dynamicBankRegex = Regex("\\b([a-zA-Z]{3,15}bank)\\b", RegexOption.IGNORE_CASE)
                val bankMatch = dynamicBankRegex.find(sanitizedText)
                if (bankMatch != null && normalizeBankName(bankMatch.groupValues[1]).isNotEmpty()) {
                    bankMatch.groupValues[1].replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
                } else {
                    val dynamicCardRegex = Regex("\\b([a-zA-Z]{3,15})\\s+card\\b", RegexOption.IGNORE_CASE)
                    val cardMatch = dynamicCardRegex.find(sanitizedText)
                    if (cardMatch != null && normalizeBankName(cardMatch.groupValues[1]).isNotEmpty()) {
                        cardMatch.groupValues[1].replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() } + " Card"
                    } else {
                        ""
                    }
                }
            }
        }
    }

    /**
     * Resolves payment method independently of account identity.
     */
    fun resolvePaymentMethod(text: String, accountType: String? = null): String {
        val lower = text.lowercase(Locale.ENGLISH)
        return when {
            lower.contains("upi") || lower.contains("vpa") || lower.contains("gpay") || lower.contains("phonepe") || lower.contains("paytm") -> "UPI"
            lower.contains("atm") || lower.contains("cash withdrawn") || lower.contains("cash withdrawal") -> "ATM"
            lower.contains("credit card") || lower.contains("credit-card") -> "Credit Card"
            lower.contains("debit card") || lower.contains("debit-card") -> "Debit Card"
            lower.contains("card") -> if (accountType?.contains("Credit", true) == true) "Credit Card" else "Debit Card"
            lower.contains("cheque") -> "Cheque"
            lower.contains("net banking") || lower.contains("bank transfer") || lower.contains("imps") || lower.contains("neft") || lower.contains("rtgs") -> "Bank Transfer"
            else -> "Other"
        }
    }

    /**
     * Validates whether a string is a genuine banking / UPI transaction reference identifier.
     * Rejects generic UI and email labels like "Details", "View Details", "Transaction Details",
     * pure alphabetic text with no digits, or invalid placeholders.
     */
    fun isValidTransactionReference(ref: String?): Boolean {
        if (ref.isNullOrBlank()) return false
        val clean = ref.trim()
        if (clean.length < 4 || clean.length > 30) return false
        val lower = clean.lowercase(Locale.ENGLISH)
        val invalidLabels = setOf(
            "details", "view details", "transaction details", "more details", "upi details",
            "view", "details:", "info", "description", "summary", "history", "receipt",
            "statement", "notification", "alert", "update", "status", "success", "failed",
            "pending", "completed", "click", "here", "click here", "unknown", "other",
            "null", "none", "true", "false", "undefined"
        )
        if (invalidLabels.contains(lower)) return false
        // Bank references must contain numeric digits (UPI RRN, UTR, IMPS, Txn IDs always contain numbers)
        if (clean.none { it.isDigit() }) return false
        // Must not be all zeroes or non-alphanumeric
        val alphanumeric = clean.replace("[^A-Za-z0-9]".toRegex(), "")
        if (alphanumeric.length < 4 || alphanumeric.matches("0+".toRegex())) return false
        return true
    }
}
