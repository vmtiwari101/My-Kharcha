package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.AccountEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity

@Composable
fun SubcategoryDrillDownView(
    subcategoryName: String,
    totalSpend: Double,
    transactionCount: Int,
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    subcategories: List<SubcategoryEntity>,
    accounts: List<AccountEntity>,
    allSplits: List<TransactionSplitEntity>,
    formatINR: (Double) -> String,
    onBack: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit
) {
    BackHandler(onBack = onBack)

    val topMerchants = remember(transactions) {
        transactions.groupBy { it.merchant }
            .mapValues { it.value.sumOf { tx -> tx.amount } }
            .toList()
            .sortedByDescending { it.second }
            .take(5)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF0F172A))
                }
                val sub = subcategories.find { it.name == subcategoryName }
                val cat = categories.find { it.id == sub?.categoryId }
                val subIcon = sub?.icon ?: "🏷️"
                val subColor = try {
                    Color(android.graphics.Color.parseColor(sub?.colour ?: cat?.colour ?: "#10B981"))
                } catch (e: Exception) {
                    Color(0xFF10B981)
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(subColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = subIcon, fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = getBilingualName(subcategoryName, sub?.nameHindi ?: ""),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0F172A)
                    )
                    if (cat != null) {
                        Text(text = "in ${getBilingualName(cat.name, cat.nameHindi)}", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "SUB-CATEGORY SPEND", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    Text(text = formatINR(totalSpend), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                    Text(text = "$transactionCount transactions", fontSize = 12.sp, color = Color(0xFF64748B))
                }
            }
        }

        if (topMerchants.isNotEmpty()) {
            item {
                Text(text = "TOP MERCHANTS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
            }
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        topMerchants.forEachIndexed { idx, (merchant, amt) ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(merchant.ifEmpty { "Unknown" }, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(formatINR(amt), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            if (idx < topMerchants.size - 1) HorizontalDivider(color = Color(0xFFF1F5F9))
                        }
                    }
                }
            }
        }

        item {
            Text(text = "MATCHING TRANSACTIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        }

        items(transactions, key = { it.id }) { tx ->
            val sub = subcategories.find { it.id == tx.subcategoryId }
            val cat = categories.find { it.id == tx.categoryId }
            val itemColor = try {
                Color(android.graphics.Color.parseColor(sub?.colour ?: cat?.colour ?: "#10B981"))
            } catch (e: Exception) {
                Color(0xFF10B981)
            }

            Card(
                onClick = { onSelectTransaction(tx) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(itemColor.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when(tx.source) {
                                "SMS" -> Icons.Default.Sms
                                "NOTIFICATION" -> Icons.Default.Notifications
                                "EMAIL" -> Icons.Default.Email
                                else -> Icons.Default.Edit
                            },
                            contentDescription = null,
                            tint = itemColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tx.merchant.ifEmpty { "Unknown" }, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        val acc = accounts.find { it.id == tx.accountId }
                        Text("${tx.date} • ${acc?.name ?: "Unknown Account"}", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                    Text(formatINR(tx.amount), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                }
            }
        }
    }
}
