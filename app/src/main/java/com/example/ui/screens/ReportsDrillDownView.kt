package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
fun ReportsDrillDownView(
    title: String,
    drillDownType: String,
    transactions: List<TransactionEntity>,
    totalIncome: Double,
    totalExpense: Double,
    netSavings: Double,
    savingsRate: Double,
    categories: List<CategoryEntity>,
    subcategories: List<SubcategoryEntity>,
    accounts: List<AccountEntity>,
    allSplits: List<TransactionSplitEntity>,
    formatINR: (Double) -> String,
    onBack: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top Navigation Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Reports",
                        tint = Color(0xFF0F172A),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                    Text(text = "${transactions.size} transaction${if (transactions.size != 1) "s" else ""}", fontSize = 11.sp, color = Color(0xFF64748B))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (drillDownType) {
            "net" -> {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "Cash Flow Summary", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Total Income", fontSize = 12.sp, color = Color(0xFF64748B))
                            Text(text = formatINR(totalIncome), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Total Expense", fontSize = 12.sp, color = Color(0xFF64748B))
                            Text(text = formatINR(totalExpense), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                        }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Net Cash Flow", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Text(
                                text = (if (netSavings >= 0) "+ " else "- ") + formatINR(Math.abs(netSavings)),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (netSavings >= 0) Color(0xFF059669) else Color(0xFFDC2626)
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Savings Percentage", fontSize = 12.sp, color = Color(0xFF64748B))
                            Text(
                                text = "${String.format("%.1f", savingsRate)}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (savingsRate >= 20) Color(0xFF047857) else Color(0xFFD97706)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
            "income" -> {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "Total Income", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF047857))
                        Text(text = formatINR(totalIncome), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF065F46))
                        Text(text = "${transactions.size} transaction${if (transactions.size != 1) "s" else ""}", fontSize = 11.sp, color = Color(0xFF059669))
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
            "expense" -> {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "Total Expense", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB91C1C))
                        Text(text = formatINR(totalExpense), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF991B1B))
                        Text(text = "${transactions.size} transaction${if (transactions.size != 1) "s" else ""}", fontSize = 11.sp, color = Color(0xFFDC2626))
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
            "account", "source", "merchant" -> {
                val totalSum = remember(transactions) { transactions.sumOf { it.amount } }
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "Total Amount", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                        Text(text = formatINR(totalSum), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                        Text(text = "${transactions.size} transaction${if (transactions.size != 1) "s" else ""}", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        if (transactions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "No transactions found for this selection.", fontSize = 12.sp, color = Color(0xFF64748B))
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(transactions, key = { it.id }) { tx ->
                    TransactionListItemCard(
                        tx = tx,
                        categories = categories,
                        subcategories = subcategories,
                        accounts = accounts,
                        allSplits = allSplits,
                        formatINR = formatINR,
                        onSelect = { onSelectTransaction(tx) }
                    )
                }
            }
        }
    }
}
