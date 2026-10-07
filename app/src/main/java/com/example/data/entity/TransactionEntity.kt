package com.example.data.entity

import androidx.room.Entity

@Entity(tableName = "transactions", primaryKeys = ["userId", "id"])
data class TransactionEntity(
    val id: String,
    val type: String, // "EXPENSE" or "INCOME"
    val amount: Double,
    val date: String, // "YYYY-MM-DD"
    val time: String, // "HH:mm"
    val merchant: String,
    val categoryId: String,
    val subcategoryId: String,
    val accountId: String,
    val paymentMethod: String,
    val note: String,
    val source: String, // "MANUAL", "SMS", etc.
    val transactionReference: String,
    val originalReference: String = "",
    val last4Digits: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",

    // New fields for Mapping & Transfers
    val transactionId: String? = null,
    val cardId: String? = null,
    val transactionType: String? = null, // e.g. "INTERNAL_TRANSFER", "CARD_PAYMENT", "CASH_WITHDRAWAL"
    val direction: String? = null, // "DEBIT" or "CREDIT"
    val referenceId: String? = null,
    val transferGroupId: String? = null,
    val counterpartyAccountId: String? = null,
    val last4: String? = null,
    val duplicateFingerprint: String? = null,
    val isInternalTransfer: Boolean = false,
    val needsReview: Boolean = false,
    val isExpense: Boolean = true,
    val cardPaymentBalanceApplied: Boolean = false,
    val userId: String = ""
)
