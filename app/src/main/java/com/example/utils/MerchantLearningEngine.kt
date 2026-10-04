package com.example.utils

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale

object MerchantLearningEngine {
    private const val PREFS_BASE_NAME = "kharcha_merchant_learning"

    private fun getPrefsName(): String {
        return try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (!uid.isNullOrBlank()) {
                "${PREFS_BASE_NAME}_$uid"
            } else {
                PREFS_BASE_NAME
            }
        } catch (t: Throwable) {
            PREFS_BASE_NAME
        }
    }

    /**
     * Safely normalizes merchant names to prevent cosmetic, casing, or spacing variations
     * from creating separate preference keys, while strictly avoiding over-normalization
     * that could merge genuinely different merchants.
     */
    fun normalizeMerchantName(merchant: String): String {
        val unconcatenated = SmsParser.unconcatenateMerchantName(merchant)
        val lower = unconcatenated.trim().lowercase(Locale.ENGLISH)
        // Remove duplicate spaces and common corporate suffix noise
        var cleaned = lower
            .replace(Regex("\\b(pvt|ltd|limited|llp|inc|co|corp|corporation|private limited|india)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        
        if (cleaned.isEmpty()) {
            return lower
        }
        return cleaned
    }

    fun saveMapping(context: Context, merchant: String, categoryId: String, subcategoryId: String) {
        val trimmed = merchant.trim()
        if (trimmed.isBlank() || 
            trimmed.equals("Unknown Merchant", ignoreCase = true) || 
            trimmed.equals("Other", ignoreCase = true) || 
            trimmed.equals("General", ignoreCase = true) || 
            trimmed.equals("Payment", ignoreCase = true) || 
            trimmed.equals("Transfer", ignoreCase = true) ||
            trimmed.equals("Bank Transfer", ignoreCase = true)) return
        val prefs = context.getSharedPreferences(getPrefsName(), Context.MODE_PRIVATE)
        val cleanKey = normalizeMerchantName(trimmed)
        if (cleanKey.isEmpty()) return
        
        prefs.edit()
            .putString("cat_$cleanKey", categoryId)
            .putString("sub_$cleanKey", subcategoryId)
            .apply()
    }

    fun getMapping(context: Context, merchant: String): Pair<String, String>? {
        val trimmed = merchant.trim()
        if (trimmed.isBlank() || 
            trimmed.equals("Unknown Merchant", ignoreCase = true) || 
            trimmed.equals("Other", ignoreCase = true) || 
            trimmed.equals("General", ignoreCase = true) || 
            trimmed.equals("Payment", ignoreCase = true) || 
            trimmed.equals("Transfer", ignoreCase = true) ||
            trimmed.equals("Bank Transfer", ignoreCase = true)) return null
        val prefs = context.getSharedPreferences(getPrefsName(), Context.MODE_PRIVATE)
        val cleanKey = normalizeMerchantName(trimmed)
        if (cleanKey.isEmpty()) return null
        
        val cat = prefs.getString("cat_$cleanKey", null)
        val sub = prefs.getString("sub_$cleanKey", null)
        if (!cat.isNullOrEmpty()) {
            return Pair(cat, sub ?: "")
        }
        return null
    }
    
    fun clearAllForUser(context: Context) {
        val prefs = context.getSharedPreferences(getPrefsName(), Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
