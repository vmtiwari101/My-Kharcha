package com.example.data.firestore

import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.data.entity.UserEntity

/**
 * Sanitizes input text to guarantee raw OTP, password, PIN, CVV, or auth secrets are never written.
 */
fun sanitizeFirestoreText(text: String?): String {
    if (text.isNullOrBlank()) return ""
    val lower = text.lowercase()
    if (lower.contains("otp") || lower.contains("password") || lower.contains("passcode") ||
        lower.contains("secret") || lower.contains("cvv") || lower.contains("atm pin") ||
        lower.contains("login pin") || lower.contains("mpin")
    ) {
        return text.replace(Regex("(?i)\\b(otp|password|passcode|secret|cvv|pin|mpin)\\b(\\s+(?:is|was|code|no|number|num|[:=-])?\\s*\\w+)?"), "[REDACTED]").trim()
    }
    return text.trim()
}

/**
 * UserEntity <-> Firestore Map
 */
fun UserEntity.toFirestoreMap(): Map<String, Any?> {
    return mapOf(
        "id" to id,
        "phoneNumber" to sanitizeFirestoreText(phoneNumber),
        "name" to sanitizeFirestoreText(name),
        "email" to sanitizeFirestoreText(email),
        "createdAt" to createdAt,
        "lastLoginAt" to lastLoginAt,
        "isCloudSynced" to isCloudSynced
    )
}

fun Map<String, Any?>.toUserEntity(fallbackId: String): UserEntity {
    return UserEntity(
        id = (get("id") as? String) ?: fallbackId,
        phoneNumber = (get("phoneNumber") as? String) ?: "",
        name = (get("name") as? String) ?: "My Kharcha User",
        email = (get("email") as? String) ?: "",
        createdAt = (get("createdAt") as? String) ?: "",
        lastLoginAt = (get("lastLoginAt") as? String) ?: "",
        isCloudSynced = (get("isCloudSynced") as? Boolean) ?: true
    )
}

/**
 * AccountEntity <-> Firestore Map
 */
fun AccountEntity.toFirestoreMap(): Map<String, Any?> {
    return mapOf(
        "id" to id,
        "name" to sanitizeFirestoreText(name),
        "type" to type,
        "bankName" to sanitizeFirestoreText(bankName),
        "last4Digits" to last4Digits,
        "icon" to icon,
        "colour" to colour,
        "isActive" to isActive,
        "isDefault" to isDefault,
        "isOwnedByMe" to isOwnedByMe,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "creditLimit" to creditLimit,
        "outstandingAmount" to outstandingAmount,
        "billingDate" to billingDate,
        "dueDate" to dueDate,
        "initialBalance" to initialBalance,
        "minimumAmountDue" to minimumAmountDue,
        "paymentDueDate" to paymentDueDate,
        "statementDate" to statementDate,
        "lastBillSource" to lastBillSource,
        "lastBillMessageId" to lastBillMessageId,
        "lastBillUpdatedAt" to lastBillUpdatedAt
    )
}

fun Map<String, Any?>.toAccountEntity(fallbackId: String): AccountEntity {
    return AccountEntity(
        id = (get("id") as? String) ?: fallbackId,
        name = (get("name") as? String) ?: "",
        type = (get("type") as? String) ?: "Bank Account",
        bankName = (get("bankName") as? String) ?: "",
        last4Digits = (get("last4Digits") as? String) ?: "",
        icon = (get("icon") as? String) ?: "landmark",
        colour = (get("colour") as? String) ?: "#1E40AF",
        isActive = (get("isActive") as? Boolean) ?: true,
        isDefault = (get("isDefault") as? Boolean) ?: false,
        isOwnedByMe = (get("isOwnedByMe") as? Boolean) ?: true,
        createdAt = (get("createdAt") as? String) ?: "",
        updatedAt = (get("updatedAt") as? String) ?: "",
        creditLimit = ((get("creditLimit") as? Number)?.toDouble()) ?: 0.0,
        outstandingAmount = ((get("outstandingAmount") as? Number)?.toDouble()) ?: 0.0,
        billingDate = ((get("billingDate") as? Number)?.toInt()) ?: 0,
        dueDate = ((get("dueDate") as? Number)?.toInt()) ?: 0,
        initialBalance = ((get("initialBalance") as? Number)?.toDouble()) ?: 0.0,
        minimumAmountDue = ((get("minimumAmountDue") as? Number)?.toDouble()) ?: 0.0,
        paymentDueDate = (get("paymentDueDate") as? String) ?: "",
        statementDate = (get("statementDate") as? String) ?: "",
        lastBillSource = (get("lastBillSource") as? String) ?: "",
        lastBillMessageId = (get("lastBillMessageId") as? String) ?: "",
        lastBillUpdatedAt = (get("lastBillUpdatedAt") as? String) ?: ""
    )
}

/**
 * CardEntity <-> Firestore Map
 */
fun CardEntity.toFirestoreMap(): Map<String, Any?> {
    return mapOf(
        "id" to id,
        "accountId" to accountId,
        "name" to sanitizeFirestoreText(name),
        "type" to type,
        "last4Digits" to last4Digits,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "creditLimit" to creditLimit,
        "outstandingAmount" to outstandingAmount,
        "billingDate" to billingDate,
        "dueDate" to dueDate,
        "minimumAmountDue" to minimumAmountDue,
        "paymentDueDate" to paymentDueDate,
        "statementDate" to statementDate,
        "lastBillSource" to lastBillSource,
        "lastBillMessageId" to lastBillMessageId,
        "lastBillUpdatedAt" to lastBillUpdatedAt
    )
}

fun Map<String, Any?>.toCardEntity(fallbackId: String): CardEntity {
    return CardEntity(
        id = (get("id") as? String) ?: fallbackId,
        accountId = (get("accountId") as? String) ?: "",
        name = (get("name") as? String) ?: "",
        type = (get("type") as? String) ?: "Credit Card",
        last4Digits = (get("last4Digits") as? String) ?: "",
        createdAt = (get("createdAt") as? String) ?: "",
        updatedAt = (get("updatedAt") as? String) ?: "",
        creditLimit = ((get("creditLimit") as? Number)?.toDouble()) ?: 0.0,
        outstandingAmount = ((get("outstandingAmount") as? Number)?.toDouble()) ?: 0.0,
        billingDate = ((get("billingDate") as? Number)?.toInt()) ?: 0,
        dueDate = ((get("dueDate") as? Number)?.toInt()) ?: 0,
        minimumAmountDue = ((get("minimumAmountDue") as? Number)?.toDouble()) ?: 0.0,
        paymentDueDate = (get("paymentDueDate") as? String) ?: "",
        statementDate = (get("statementDate") as? String) ?: "",
        lastBillSource = (get("lastBillSource") as? String) ?: "",
        lastBillMessageId = (get("lastBillMessageId") as? String) ?: "",
        lastBillUpdatedAt = (get("lastBillUpdatedAt") as? String) ?: ""
    )
}

/**
 * TransactionEntity <-> Firestore Map
 */
fun TransactionEntity.toFirestoreMap(): Map<String, Any?> {
    return mapOf(
        "id" to id,
        "type" to type,
        "amount" to amount,
        "date" to date,
        "time" to time,
        "merchant" to sanitizeFirestoreText(merchant),
        "categoryId" to categoryId,
        "subcategoryId" to subcategoryId,
        "accountId" to accountId,
        "paymentMethod" to paymentMethod,
        "note" to sanitizeFirestoreText(note),
        "source" to source,
        "transactionReference" to sanitizeFirestoreText(transactionReference),
        "originalReference" to sanitizeFirestoreText(originalReference),
        "last4Digits" to last4Digits,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "transactionId" to transactionId,
        "cardId" to cardId,
        "transactionType" to transactionType,
        "direction" to direction,
        "referenceId" to referenceId,
        "transferGroupId" to transferGroupId,
        "counterpartyAccountId" to counterpartyAccountId,
        "last4" to last4,
        "duplicateFingerprint" to duplicateFingerprint,
        "isInternalTransfer" to isInternalTransfer,
        "needsReview" to needsReview,
        "isExpense" to isExpense
    )
}

fun Map<String, Any?>.toTransactionEntity(fallbackId: String): TransactionEntity {
    return TransactionEntity(
        id = (get("id") as? String) ?: fallbackId,
        type = (get("type") as? String) ?: "EXPENSE",
        amount = ((get("amount") as? Number)?.toDouble()) ?: 0.0,
        date = (get("date") as? String) ?: "",
        time = (get("time") as? String) ?: "",
        merchant = (get("merchant") as? String) ?: "",
        categoryId = (get("categoryId") as? String) ?: "",
        subcategoryId = (get("subcategoryId") as? String) ?: "",
        accountId = (get("accountId") as? String) ?: "",
        paymentMethod = (get("paymentMethod") as? String) ?: "",
        note = (get("note") as? String) ?: "",
        source = (get("source") as? String) ?: "MANUAL",
        transactionReference = (get("transactionReference") as? String) ?: "",
        originalReference = (get("originalReference") as? String) ?: "",
        last4Digits = (get("last4Digits") as? String) ?: "",
        createdAt = (get("createdAt") as? String) ?: "",
        updatedAt = (get("updatedAt") as? String) ?: "",
        transactionId = get("transactionId") as? String,
        cardId = get("cardId") as? String,
        transactionType = get("transactionType") as? String,
        direction = get("direction") as? String,
        referenceId = get("referenceId") as? String,
        transferGroupId = get("transferGroupId") as? String,
        counterpartyAccountId = get("counterpartyAccountId") as? String,
        last4 = get("last4") as? String,
        duplicateFingerprint = get("duplicateFingerprint") as? String,
        isInternalTransfer = (get("isInternalTransfer") as? Boolean) ?: false,
        needsReview = (get("needsReview") as? Boolean) ?: false,
        isExpense = (get("isExpense") as? Boolean) ?: true
    )
}

/**
 * CategoryEntity <-> Firestore Map
 */
fun CategoryEntity.toFirestoreMap(): Map<String, Any?> {
    return mapOf(
        "id" to id,
        "name" to sanitizeFirestoreText(name),
        "nameHindi" to sanitizeFirestoreText(nameHindi),
        "icon" to icon,
        "colour" to colour,
        "isDefault" to isDefault,
        "isActive" to isActive,
        "isIncome" to isIncome,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )
}

fun Map<String, Any?>.toCategoryEntity(fallbackId: String): CategoryEntity {
    return CategoryEntity(
        id = (get("id") as? String) ?: fallbackId,
        name = (get("name") as? String) ?: "",
        nameHindi = (get("nameHindi") as? String) ?: "",
        icon = (get("icon") as? String) ?: "📁",
        colour = (get("colour") as? String) ?: "#10B981",
        isDefault = (get("isDefault") as? Boolean) ?: false,
        isActive = (get("isActive") as? Boolean) ?: true,
        isIncome = (get("isIncome") as? Boolean) ?: false,
        createdAt = (get("createdAt") as? String) ?: "",
        updatedAt = (get("updatedAt") as? String) ?: ""
    )
}

/**
 * SubcategoryEntity <-> Firestore Map
 */
fun SubcategoryEntity.toFirestoreMap(): Map<String, Any?> {
    return mapOf(
        "id" to id,
        "categoryId" to categoryId,
        "name" to sanitizeFirestoreText(name),
        "nameHindi" to sanitizeFirestoreText(nameHindi),
        "icon" to icon,
        "colour" to colour,
        "isDefault" to isDefault,
        "isActive" to isActive,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )
}

fun Map<String, Any?>.toSubcategoryEntity(fallbackId: String): SubcategoryEntity {
    return SubcategoryEntity(
        id = (get("id") as? String) ?: fallbackId,
        categoryId = (get("categoryId") as? String) ?: "",
        name = (get("name") as? String) ?: "",
        nameHindi = (get("nameHindi") as? String) ?: "",
        icon = (get("icon") as? String) ?: "🏷️",
        colour = (get("colour") as? String) ?: "#10B981",
        isDefault = (get("isDefault") as? Boolean) ?: false,
        isActive = (get("isActive") as? Boolean) ?: true,
        createdAt = (get("createdAt") as? String) ?: "",
        updatedAt = (get("updatedAt") as? String) ?: ""
    )
}

/**
 * TransactionSplitEntity <-> Firestore Map
 */
fun TransactionSplitEntity.toFirestoreMap(): Map<String, Any?> {
    return mapOf(
        "id" to id,
        "transactionId" to transactionId,
        "categoryId" to categoryId,
        "subcategoryId" to subcategoryId,
        "amount" to amount,
        "note" to sanitizeFirestoreText(note),
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )
}

fun Map<String, Any?>.toTransactionSplitEntity(fallbackId: String): TransactionSplitEntity {
    return TransactionSplitEntity(
        id = (get("id") as? String) ?: fallbackId,
        transactionId = (get("transactionId") as? String) ?: "",
        categoryId = (get("categoryId") as? String) ?: "",
        subcategoryId = (get("subcategoryId") as? String) ?: "",
        amount = ((get("amount") as? Number)?.toDouble()) ?: 0.0,
        note = (get("note") as? String) ?: "",
        createdAt = (get("createdAt") as? String) ?: "",
        updatedAt = (get("updatedAt") as? String) ?: ""
    )
}
