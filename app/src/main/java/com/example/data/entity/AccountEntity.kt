package com.example.data.entity

import androidx.room.Entity

@Entity(tableName = "accounts", primaryKeys = ["userId", "id"])
data class AccountEntity(
    val id: String,
    val name: String,
    val type: String, // Bank Account, Credit Card, Debit Card, UPI, Cash
    val bankName: String = "",
    val last4Digits: String = "",
    val icon: String = "landmark",
    val colour: String = "#1E40AF",
    val isActive: Boolean = true,
    val isDefault: Boolean = false,
    val isOwnedByMe: Boolean = true,
    val createdAt: String = "",
    val updatedAt: String = "",
    val creditLimit: Double = 0.0,
    val outstandingAmount: Double = 0.0,
    val billingDate: Int = 0,
    val dueDate: Int = 0,
    val initialBalance: Double = 0.0,
    val minimumAmountDue: Double = 0.0,
    val paymentDueDate: String = "",
    val statementDate: String = "",
    val lastBillSource: String = "",
    val lastBillMessageId: String = "",
    val lastBillUpdatedAt: String = "",
    val userId: String = ""
)
