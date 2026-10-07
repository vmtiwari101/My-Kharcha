package com.example.data.entity

import androidx.room.Entity

@Entity(tableName = "subcategories", primaryKeys = ["userId", "id"])
data class SubcategoryEntity(
    val id: String,
    val categoryId: String,
    val name: String,
    val nameHindi: String = "",
    val icon: String,
    val colour: String,
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: String,
    val updatedAt: String,
    val userId: String = ""
)
