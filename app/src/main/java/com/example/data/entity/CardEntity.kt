package com.example.data.entity

import androidx.room.Entity

@Entity(tableName = "cards", primaryKeys = ["userId", "id"])
data class CardEntity(
    val id: String,
    val accountId: String,
    val name: String,
    val type: String, // "Debit Card", "Credit Card"
    val last4Digits: String,
    val createdAt: String = "",
    val updatedAt: String = "",
    val creditLimit: Double = 0.0,
    val outstandingAmount: Double = 0.0,
    val billingDate: Int = 0,
    val dueDate: Int = 0,
    val minimumAmountDue: Double = 0.0,
    val paymentDueDate: String = "",
    val statementDate: String = "",
    val lastBillSource: String = "",
    val lastBillMessageId: String = "",
    val lastBillUpdatedAt: String = "",
    val userId: String = ""
)
