package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun CategoryDrillDownView(
    categoryName: String,
    categoryIcon: String,
    categoryColor: Color,
    totalSpend: Double,
    transactionCount: Int,
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    subcategories: List<SubcategoryEntity>,
    accounts: List<AccountEntity>,
    allSplits: List<TransactionSplitEntity>,
    targetCategoryId: String,
    formatINR: (Double) -> String,
    onBack: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit,
    onSelectSubcategory: (SubcategoryEntity) -> Unit
) {
    BackHandler(onBack = onBack)
    
    val subcatBreakdown = remember(transactions, allSplits, subcategories) {
        val subTotals = mutableMapOf<String, Double>()
        transactions.forEach { tx ->
            val txSplits = allSplits.filter { it.transactionId == tx.id && it.categoryId == targetCategoryId }
            if (txSplits.isNotEmpty()) {
                txSplits.forEach { s ->
                    subTotals[s.subcategoryId] = (subTotals[s.subcategoryId] ?: 0.0) + s.amount
                }
            } else if (tx.categoryId == targetCategoryId && tx.subcategoryId.isNotEmpty()) {
                subTotals[tx.subcategoryId] = (subTotals[tx.subcategoryId] ?: 0.0) + tx.amount
            }
        }
        subTotals.mapNotNull { (subId, amt) ->
            val sub = subcategories.find { it.id == subId }
            if (sub != null) Triple(sub, amt, if (totalSpend > 0) amt / totalSpend else 0.0) else null
        }.sortedByDescending { it.second }
    }

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
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(categoryColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = categoryIcon, fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = categoryName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0F172A)
                )
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
                    Text(text = "TOTAL SPENT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), maxLines = 1, softWrap = false)
                    Text(text = formatINR(totalSpend), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A), maxLines = 1, softWrap = false)
                    Text(text = "$transactionCount transaction${if (transactionCount != 1) "s" else ""}", fontSize = 12.sp, color = Color(0xFF64748B), maxLines = 1, softWrap = false)
                }
            }
        }
        
        if (subcatBreakdown.isNotEmpty()) {
            item {
                Text(text = "SUB-CATEGORIES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
            }
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        subcatBreakdown.forEach { (sub, amt, pct) ->
                            ListItem(
                                modifier = Modifier.clickable { onSelectSubcategory(sub) },
                                headlineContent = { Text(getBilingualName(sub.name, sub.nameHindi), fontWeight = FontWeight.Bold, fontSize = 14.sp) },
                                supportingContent = { 
                                    LinearProgressIndicator(
                                        progress = { pct.toFloat() },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                                        color = categoryColor,
                                        trackColor = Color(0xFFF1F5F9)
                                    )
                                },
                                trailingContent = {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(formatINR(amt), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                                        Text("${String.format("%.1f", pct * 100)}%", fontSize = 11.sp, color = Color(0xFF64748B))
                                    }
                                },
                                leadingContent = {
                                    Box(
                                        modifier = Modifier.size(32.dp).clip(CircleShape).background(categoryColor.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(sub.icon, fontSize = 14.sp)
                                    }
                                }
                            )
                        }
                    }
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
            Text(text = "TRANSACTIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        }

        items(transactions, key = { it.id }) { tx ->
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
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(categoryColor.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val sub = subcategories.find { it.id == tx.subcategoryId }
                        Text(sub?.icon ?: categoryIcon, fontSize = 18.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tx.merchant.ifEmpty { "Unknown" }, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(tx.date, fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                    Text(formatINR(tx.amount), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                }
            }
        }
    }
}
