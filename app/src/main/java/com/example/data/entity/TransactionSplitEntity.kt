package com.example.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "transaction_splits",
    primaryKeys = ["userId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["userId", "id"],
            childColumns = ["userId", "transactionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["userId", "transactionId"])]
)
data class TransactionSplitEntity(
    val id: String,
    val transactionId: String,
    val categoryId: String,
    val subcategoryId: String = "",
    val amount: Double,
    val note: String = "",
    val createdAt: String,
    val updatedAt: String,
    val userId: String = ""
)
