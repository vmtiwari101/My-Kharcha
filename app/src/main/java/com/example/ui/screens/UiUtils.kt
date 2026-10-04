package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun EmptyStateBox(title: String, subtitle: String, onAction: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, fontSize = 12.sp, color = Color(0xFF94A3B8), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Add Transaction", fontSize = 12.sp)
            }
        }
    }
}

fun getBilingualName(name: String, nameHindi: String): String {
    return if (nameHindi.isNotEmpty()) "$name ($nameHindi)" else name
}

fun formatDateNice(dateStr: String): String {
    try {
        val parts = dateStr.split("-")
        if (parts.size == 3) {
            val day = parts[2].toInt().toString() // remove leading zero
            val monthInt = parts[1].toInt()
            val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            if (monthInt in 1..12) {
                return "$day ${months[monthInt - 1]}"
            }
        }
    } catch (e: Exception) {
        // fallback
    }
    return dateStr
}

fun getCleanMerchantName(merchant: String, categoryName: String?): String {
    val clean = com.example.utils.SmsParser.unconcatenateMerchantName(merchant.trim())
    
    // 1. Basic length filter & Newline filter
    if (clean.isBlank() || clean.length <= 1 || clean.contains("\n")) return categoryName ?: "Unknown Merchant"
    
    // 2. Filter out raw SMS body markers or excessively long text that isn't a merchant
    if (clean.length > 32) return categoryName ?: "Unknown Merchant"
    
    // 3. Filter out phone numbers or long digit strings (reference IDs)
    if (Regex("\\b\\d{8,}\\b").containsMatchIn(clean)) return categoryName ?: "Unknown Merchant"
    
    // 4. Reject words indicating boilerplate/fragments or bank specific info
    val rejectPatterns = listOf(
        "Available Balance", "Avbl Bal", "Account Balance", "Credit Limit", 
        "RRN", "UTR", "Transaction", "Credited", "Debited", "Spent", "Paid",
        "Team IDFC", "Team SBI", "Bank Alert", "Dear Customer", "Your New Balance",
        "Closing Balance", "Outstanding", "Statement", "Mini Statement", "OTP",
        "Verification", "Ref No", "Ref Num", "Payment Of", "Transfer Of",
        "Credit", "Debit", "Sms From", "Sms From:", "Sms From-", "Sms From -",
        "Bank", "A/c", "Account", "Sharing This Alert", "Sharing Alert", "Share Alert",
        "View Details", "Transaction Details", "More Details", "UPI Details"
    )
    if (rejectPatterns.any { clean.contains(it, ignoreCase = true) }) {
        return categoryName ?: "Unknown Merchant"
    }

    // 5. If it's just a generic word, return category default
    val genericWords = listOf(
        "Transaction", "Other", "Unknown Merchant", "Payment", "Transfer", "Bank",
        "Sms From", "Sms", "Details", "View Details", "Transaction Details", "Alert",
        "Sharing This Alert", "Re Sharing This Alert"
    )
    if (genericWords.any { clean.equals(it, ignoreCase = true) }) {
        return categoryName ?: "Unknown Merchant"
    }
    
    // 6. Basic titlecase for consistency (Rule 7)
    return clean.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
        val w = word.lowercase(Locale.ENGLISH)
        if (w.length <= 2) w.uppercase(Locale.ENGLISH)
        else w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
    }
}
