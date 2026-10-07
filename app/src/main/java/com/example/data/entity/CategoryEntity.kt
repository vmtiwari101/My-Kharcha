package com.example.data.entity

import androidx.room.Entity

@Entity(tableName = "categories", primaryKeys = ["userId", "id"])
data class CategoryEntity(
    val id: String,
    val name: String,
    val nameHindi: String = "",
    val icon: String,
    val colour: String,
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
    val isIncome: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val userId: String = ""
)
