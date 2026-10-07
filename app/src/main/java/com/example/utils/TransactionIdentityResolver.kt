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

        val isBillPayment = textLower.contains("bill payment") || textLower.contains("towards payment") || 
            textLower.contains("paid towards") || textLower.contains("cc payment") || textLower.contains("credit card payment")

        val isIncomingCreditCard = (textLower.contains("credit card") || textLower.contains("credit-card") || 
            textLower.contains("cc ending") || evidence.cardTypeCandidate.contains("credit", true)) && !isBillPayment

        val isIncomingBankAccount = (textLower.contains("a/c") || textLower.contains("ac ") || 
            textLower.contains("account") || textLower.contains("savings") || textLower.contains("current") || 
            textLower.contains("bank account")) && !isIncomingCreditCard

        // 1. High Confidence: EXACT Last-4 (4 digits)
        if (last4.length == 4) {
            val allCardsWithLast4 = cards.filter { it.last4Digits == last4 }
            val allAccsWithLast4 = accounts.filter { it.last4Digits == last4 }

            // Check if multiple distinct banks share this last4
            val distinctBanks = (allCardsWithLast4.map { card ->
                val acc = accounts.find { it.id == card.accountId }
                normalizeBankName(acc?.bankName?.ifEmpty { acc.name } ?: card.name)
            } + allAccsWithLast4.map { acc ->
                normalizeBankName(acc.bankName.ifEmpty { acc.name })
            }).filter { it.isNotEmpty() }.toSet()

            // If bank name is missing in evidence AND multiple distinct banks exist with this last4, it's ambiguous!
            if (bankName.isEmpty() && distinctBanks.size > 1) {
                return ResolutionResult(
                    accountId = "",
                    cardId = null,
                    last4Digits = last4,
                    needsReview = true,
                    confidence = 20,
                    paymentMethod = resolvePaymentMethod(methodText)
                )
            }

            // Filter cards matching bank (if bankName given)
            val matchedCards = allCardsWithLast4.filter { card ->
                bankName.isEmpty() || isBankNameMatch(card.name, bankName, accounts.find { it.id == card.accountId })
            }

            // Filter accounts matching bank (if bankName given)
            val matchedAccs = allAccsWithLast4.filter { acc ->
                bankName.isEmpty() || isBankNameMatch(acc.bankName.ifEmpty { acc.name }, bankName, null)
            }

            // If incoming is specifically Credit Card
            if (isIncomingCreditCard) {
                if (matchedCards.size == 1) {
                    val card = matchedCards[0]
                    return ResolutionResult(
                        accountId = card.accountId,
                        cardId = card.id,
                        last4Digits = card.last4Digits,
                        needsReview = false,
                        confidence = 100,
                        paymentMethod = evidence.paymentMethodCandidate.ifEmpty { resolvePaymentMethod(methodText, "Credit Card") }
                    )
                }
                val ccAccs = matchedAccs.filter { it.type.equals("Credit Card", true) }
                if (ccAccs.size == 1) {
                    val acc = ccAccs[0]
                    return ResolutionResult(
                        accountId = acc.id,
                        cardId = cards.find { it.accountId == acc.id }?.id,
                        last4Digits = acc.last4Digits,
                        needsReview = false,
                        confidence = 95,
                        paymentMethod = evidence.paymentMethodCandidate.ifEmpty { resolvePaymentMethod(methodText, "Credit Card") }
                    )
                }
            }

            // If incoming is specifically Bank Account (or not explicitly credit card)
            val bankAccs = matchedAccs.filter { !it.type.equals("Credit Card", true) }
            if (bankAccs.size == 1 && (isIncomingBankAccount || matchedCards.isEmpty())) {
                val acc = bankAccs[0]
                return ResolutionResult(
                    accountId = acc.id,
                    cardId = null,
                    last4Digits = acc.last4Digits,
                    needsReview = false,
                    confidence = 95,
                    paymentMethod = evidence.paymentMethodCandidate.ifEmpty { resolvePaymentMethod(methodText, acc.type) }
                )
            }

            // If only 1 card matched and no bank account matched
            if (matchedCards.size == 1 && bankAccs.isEmpty()) {
                val card = matchedCards[0]
                return ResolutionResult(
                    accountId = card.accountId,
                    cardId = card.id,
                    last4Digits = card.last4Digits,
                    needsReview = false,
                    confidence = 100,
                    paymentMethod = evidence.paymentMethodCandidate.ifEmpty { resolvePaymentMethod(methodText, "Credit Card") }
                )
            }

            // If 1 account matched overall (e.g. standalone credit card account)
            if (matchedAccs.size == 1 && matchedCards.isEmpty()) {
                val acc = matchedAccs[0]
                return ResolutionResult(
                    accountId = acc.id,
                    cardId = cards.find { it.accountId == acc.id }?.id,
                    last4Digits = acc.last4Digits,
                    needsReview = false,
                    confidence = 95,
                    paymentMethod = evidence.paymentMethodCandidate.ifEmpty { resolvePaymentMethod(methodText, acc.type) }
                )
            }

            // If bank name conflict (bankName specified, but no matching entities)
            if (bankName.isNotEmpty() && matchedCards.isEmpty() && matchedAccs.isEmpty()) {
                return ResolutionResult(
                    accountId = "",
                    cardId = null,
                    last4Digits = last4,
                    needsReview = true,
                    confidence = 20,
                    paymentMethod = resolvePaymentMethod(methodText)
                )
            }

            // Ambiguity fallback for last4
            return ResolutionResult(
                accountId = "",
                cardId = null,
                last4Digits = last4,
                needsReview = true,
                confidence = 30,
                paymentMethod = resolvePaymentMethod(methodText)
            )
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
            .replace(Regex("(?i)powered\\s+by\\s+[a-z0-9\\s]*axis(?:\\s*bank)?"), " ")
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
     * Checks if a transaction is a credit card purchase/expense.
     */
    fun isCreditCardPurchase(tx: TransactionEntity, cardId: String?, accountId: String, last4: String): Boolean {
        val isDebit = (tx.direction == "DEBIT" || tx.type == "EXPENSE") && !tx.isInternalTransfer && tx.transactionType != "INTERNAL_TRANSFER" && tx.transactionType != "CARD_PAYMENT"
        if (!isDebit) return false

        if (cardId != null && tx.cardId == cardId) return true
        if (accountId.isNotEmpty() && tx.accountId == accountId) return true
        if (last4.length == 4 && tx.last4Digits == last4) {
            val text = "${tx.note} ${tx.paymentMethod} ${tx.merchant}".lowercase(Locale.ENGLISH)
            return text.contains("credit card") || text.contains("credit-card") || text.contains("cc ending") || text.contains("card ending") || tx.paymentMethod.contains("card", true)
        }
        return false
    }

    /**
     * Checks if a transaction is a credit card bill payment or refund.
     */
    fun isCreditCardPaymentOrRefund(tx: TransactionEntity, cardId: String?, accountId: String, last4: String): Boolean {
        val isPaymentIdentifier = tx.transactionType == "CARD_PAYMENT" ||
                tx.transactionType == "CREDIT_CARD_BILL_PAYMENT" ||
                tx.merchant.contains("Credit Card Bill Payment", true) ||
                tx.note.contains("credit card payment", true) || tx.note.contains("cc payment", true) ||
                tx.note.contains("payment received towards your credit card", true) || tx.note.contains("paid towards credit card", true)

        if (isPaymentIdentifier) {
            if (accountId.isNotEmpty() && tx.counterpartyAccountId == accountId) return true
            if (cardId != null && tx.counterpartyAccountId == cardId) return true
            if (tx.isInternalTransfer || tx.transactionType == "CARD_PAYMENT" ||
                tx.transactionType == "CREDIT_CARD_BILL_PAYMENT"
            ) return false
            if (accountId.isNotEmpty() && tx.accountId == accountId) return true
            if (last4.length == 4 && tx.last4Digits == last4) return true
        }

        val isCredit = (tx.direction == "CREDIT" || tx.type == "INCOME") && !tx.isInternalTransfer && tx.transactionType != "INTERNAL_TRANSFER"
        if (isCredit) {
            if (cardId != null && tx.cardId == cardId) return true
            if (accountId.isNotEmpty() && tx.accountId == accountId) return true
            if (last4.length == 4 && tx.last4Digits == last4) {
                val text = "${tx.note} ${tx.paymentMethod} ${tx.merchant}".lowercase(Locale.ENGLISH)
                return text.contains("refund") || text.contains("cashback") || text.contains("reversal") || text.contains("credit card")
            }
        }
        return false
    }

    fun creditCardTransactionNet(
        transactions: List<TransactionEntity>,
        cardId: String?,
        accountId: String,
        last4: String
    ): Double {
        val purchases = transactions
            .filter { isCreditCardPurchase(it, cardId, accountId, last4) }
            .sumOf { it.amount }
        val paymentsAndRefunds = transactions
            .filter { isCreditCardPaymentOrRefund(it, cardId, accountId, last4) }
            .sumOf { it.amount }
        return purchases - paymentsAndRefunds
    }

    fun creditCardOutstandingFromAnchor(
        initialOutstanding: Double,
        transactions: List<TransactionEntity>,
        cardId: String?,
        accountId: String,
        last4: String
    ): Double = (initialOutstanding + creditCardTransactionNet(transactions, cardId, accountId, last4))
        .coerceAtLeast(0.0)

    fun creditCardAnchorForOutstanding(
        currentOutstanding: Double,
        transactions: List<TransactionEntity>,
        cardId: String?,
        accountId: String,
        last4: String
    ): Double = currentOutstanding - creditCardTransactionNet(transactions, cardId, accountId, last4)

    fun internalTransferBalanceEffect(
        tx: TransactionEntity,
        accountId: String,
        transactions: List<TransactionEntity>
    ): Double? {
        val isInternal = tx.isInternalTransfer ||
            tx.type == "INTERNAL_TRANSFER" ||
            tx.transactionType == "INTERNAL_TRANSFER" ||
            tx.transactionType == "CARD_PAYMENT" ||
            tx.transactionType == "CREDIT_CARD_BILL_PAYMENT"
        if (!isInternal) return null

        val hasLinkedSide = !tx.transferGroupId.isNullOrBlank() &&
            !tx.counterpartyAccountId.isNullOrBlank() &&
            transactions.any { peer ->
                peer.id != tx.id &&
                    peer.userId == tx.userId &&
                    peer.transferGroupId == tx.transferGroupId &&
                    peer.accountId == tx.counterpartyAccountId &&
                    peer.counterpartyAccountId == tx.accountId &&
                    peer.direction != tx.direction &&
                    kotlin.math.abs(peer.amount - tx.amount) < 0.01
            }
        if (hasLinkedSide) {
            if (tx.accountId != accountId) return 0.0
            return if (tx.direction.equals("CREDIT", true)) tx.amount else -tx.amount
        }
        return when {
            tx.accountId == accountId ->
                if (tx.direction.equals("CREDIT", true) || tx.type == "INCOME") tx.amount else -tx.amount
            tx.counterpartyAccountId == accountId -> tx.amount
            else -> 0.0
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
