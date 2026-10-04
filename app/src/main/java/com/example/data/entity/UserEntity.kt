package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val phoneNumber: String,
    val name: String = "My Kharcha User",
    val email: String = "",
    val createdAt: String,
    val lastLoginAt: String,
    val isCloudSynced: Boolean = true
)
