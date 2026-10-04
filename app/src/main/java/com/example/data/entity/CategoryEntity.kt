package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameHindi: String = "",
    val icon: String,
    val colour: String,
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
    val isIncome: Boolean = false,
    val createdAt: String,
    val updatedAt: String
)
