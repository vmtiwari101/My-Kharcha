package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.KharchaViewModel
import com.example.data.entity.TransactionEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.AccountEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.utils.DateFilterUtils
import java.text.NumberFormat
import java.util.Locale
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: KharchaViewModel,
    onNavigateToCategoryDrillDown: (String) -> Unit,
    onNavigateToMerchantDrillDown: (String) -> Unit,
    onNavigateToAccountDrillDown: (String) -> Unit,
    onNavigateToSourceDrillDown: (String) -> Unit
) {
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val subcategories by viewModel.subcategories.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val allSplits by viewModel.transactionSplits.collectAsState()
    
    val selectedRange by viewModel.selectedRange.collectAsState()
    val customStart by viewModel.customStart.collectAsState()
    val customEnd by viewModel.customEnd.collectAsState()
    
    val filteredTxs = remember(transactions, selectedRange, customStart, customEnd) {
        DateFilterUtils.filterByRange(transactions, selectedRange, customStart, customEnd)
    }

    val expenseTxs = remember(filteredTxs) { filteredTxs.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" } }
    val incomeTxs = remember(filteredTxs) { filteredTxs.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" } }
    
    val totalExpense = remember(expenseTxs) { expenseTxs.sumOf { it.amount } }
    val totalIncome = remember(incomeTxs) { incomeTxs.sumOf { it.amount } }
    val netSavings = totalIncome - totalExpense
    val savingsRate = if (totalIncome > 0) ((netSavings / totalIncome) * 100).coerceAtLeast(0.0) else 0.0

    val formatINR: (Double) -> String = { amt ->
        "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(amt)
    }

    // Category Breakdown with Split Support
    val catBreakdown = remember(expenseTxs, allSplits, categories) {
        val totals = mutableMapOf<String, Double>()
        expenseTxs.forEach { tx ->
            val txSplits = allSplits.filter { it.transactionId == tx.id }
            if (txSplits.isNotEmpty()) {
                txSplits.forEach { s ->
                    totals[s.categoryId] = (totals[s.categoryId] ?: 0.0) + s.amount
                }
            } else {
                totals[tx.categoryId] = (totals[tx.categoryId] ?: 0.0) + tx.amount
            }
        }
        totals.mapNotNull { (catId, amt) ->
            val cat = categories.find { it.id == catId }
            if (cat != null) {
                val pct = if (totalExpense > 0) (amt / totalExpense) else 0.0
                Triple(cat, amt, pct)
            } else null
        }.sortedByDescending { it.second }
    }

    // Account Breakdown
    val accountBreakdown = remember(expenseTxs, accounts) {
        expenseTxs.groupBy { it.accountId }.mapNotNull { (accId, txs) ->
            val acc = accounts.find { it.id == accId }
            val sum = txs.sumOf { it.amount }
            if (acc != null) Pair(acc, sum) else null
        }.sortedByDescending { it.second }
    }

    val monthLabel = remember(selectedRange) {
        val calendar = java.util.Calendar.getInstance()
        when (selectedRange) {
            "TODAY" -> "Today"
            "THIS_WEEK" -> "This Week"
            "THIS_MONTH" -> java.text.SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(Date())
            "LAST_MONTH" -> {
                calendar.add(java.util.Calendar.MONTH, -1)
                java.text.SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(calendar.time)
            }
            else -> "Custom Range"
        }
    }

    // Tracking Source Breakdown
    val sourceBreakdown = remember(filteredTxs) {
        filteredTxs.groupBy { it.source }.mapValues { entry ->
            Pair(entry.value.size, entry.value.sumOf { it.amount })
        }
    }

    var activeDrillDown by remember { mutableStateOf<String?>(null) } // "income", "expense", "net"
    var selectedTransactionForDetail by remember { mutableStateOf<TransactionEntity?>(null) }

    if (selectedTransactionForDetail != null) {
        TransactionDetailDialog(
            tx = selectedTransactionForDetail!!,
            viewModel = viewModel,
            onDismiss = { selectedTransactionForDetail = null }
        )
    }

    if (activeDrillDown != null) {
        val title = when (activeDrillDown) {
            "income" -> "Income Transactions ($monthLabel)"
            "expense" -> "Expense Transactions ($monthLabel)"
            "net" -> "Cash Flow Details ($monthLabel)"
            else -> "Transactions ($monthLabel)"
        }
        val drillDownTxs = when (activeDrillDown) {
            "income" -> incomeTxs
            "expense" -> expenseTxs
            "net" -> filteredTxs.filter { !it.isInternalTransfer }
            else -> emptyList()
        }

        ReportsDrillDownView(
            title = title,
            drillDownType = activeDrillDown!!,
            transactions = drillDownTxs,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            netSavings = netSavings,
            savingsRate = savingsRate,
            categories = categories,
            subcategories = subcategories,
            accounts = accounts,
            allSplits = allSplits,
            formatINR = formatINR,
            onBack = { activeDrillDown = null },
            onSelectTransaction = { tx -> selectedTransactionForDetail = tx }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Text(text = "Reports & Analytics", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
        Text(text = "Monthly expense analysis and cash flow", fontSize = 11.sp, color = Color(0xFF64748B))

        Spacer(modifier = Modifier.height(14.dp))

        // Period Selector
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedRange.replace("_", " "),
                    modifier = Modifier.padding(start = 12.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0F172A)
                )

                // Simple range changer
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(imageVector = Icons.Default.FilterList, contentDescription = "Filter", tint = Color(0xFF059669))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("TODAY", "THIS_WEEK", "THIS_MONTH", "LAST_MONTH").forEach { range ->
                            DropdownMenuItem(
                                text = { Text(text = range.replace("_", " "), fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                                onClick = {
                                    viewModel.selectedRange.value = range
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Financial Overview Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                onClick = { activeDrillDown = "income" },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(14.dp))
                        Text(text = "Income", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF047857))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = formatINR(totalIncome), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF065F46))
                }
            }

            Card(
                onClick = { activeDrillDown = "expense" },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(imageVector = Icons.Default.ArrowUpward, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(14.dp))
                        Text(text = "Expense", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB91C1C))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = formatINR(totalExpense), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF991B1B))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Savings / Net Cash Flow Card
        Card(
            onClick = { activeDrillDown = "net" },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Net Cash Flow / Savings", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                    Text(
                        text = (if (netSavings >= 0) "+ " else "- ") + formatINR(Math.abs(netSavings)),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (netSavings >= 0) Color(0xFF059669) else Color(0xFFDC2626)
                    )
                }

                Surface(
                    color = if (savingsRate >= 20) Color(0xFFD1FAE5) else Color(0xFFFEF3C7),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = "Savings: ${String.format("%.1f", savingsRate)}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (savingsRate >= 20) Color(0xFF047857) else Color(0xFFD97706),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Spending Trend
        Text(text = "SPENDING TREND (LAST 30 DAYS)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            val dailySpend = remember(expenseTxs) {
                val calendar = java.util.Calendar.getInstance()
                val last30Days = mutableMapOf<String, Double>()
                for (i in 29 downTo 0) {
                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
                    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(cal.time)
                    last30Days[dateStr] = 0.0
                }
                expenseTxs.forEach { tx ->
                    if (last30Days.containsKey(tx.date)) {
                        last30Days[tx.date] = (last30Days[tx.date] ?: 0.0) + tx.amount
                    }
                }
                last30Days.toList().sortedBy { it.first }
            }
            
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    val maxSpend = dailySpend.map { it.second }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                    dailySpend.forEach { (_, amt) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 1.dp)
                                .fillMaxHeight((amt / maxSpend).toFloat().coerceIn(0.1f, 1f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF2563EB))
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = dailySpend.first().first.substring(8), fontSize = 10.sp, color = Color(0xFF94A3B8))
                    Text(text = dailySpend.last().first.substring(8), fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Top Merchants
        Text(text = "TOP MERCHANTS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            val merchantBreakdown = remember(expenseTxs) {
                expenseTxs.groupBy { it.merchant }
                    .mapValues { entry -> Pair(entry.value.size, entry.value.sumOf { it.amount }) }
                    .toList()
                    .sortedByDescending { it.second.second }
                    .take(5)
            }
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                merchantBreakdown.forEachIndexed { idx, (merchant, data) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onNavigateToMerchantDrillDown(merchant) }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = merchant.ifEmpty { "Unknown" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Text(text = "${data.first} transaction${if (data.first != 1) "s" else ""}", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        }
                        Text(text = formatINR(data.second), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                    }
                    if (idx < merchantBreakdown.size - 1) HorizontalDivider(color = Color(0xFFF1F5F9))
                }
            }
        }

        if (catBreakdown.isEmpty()) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(text = "No expenses recorded for this month.", fontSize = 12.sp, color = Color(0xFF64748B))
                }
            }
        } else {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    catBreakdown.forEach { (cat, amt, pct) ->
                        val catColor = try {
                            Color(android.graphics.Color.parseColor(cat.colour))
                        } catch (e: Exception) {
                            Color(0xFF059669)
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onNavigateToCategoryDrillDown(cat.id) }
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = cat.icon, fontSize = 14.sp)
                                    Text(text = getBilingualName(cat.name, cat.nameHindi), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = "${String.format("%.1f", pct * 100)}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                                    Text(text = formatINR(amt), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                }
                            }

                            LinearProgressIndicator(
                                progress = { pct.toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(50)),
                                color = catColor,
                                trackColor = Color(0xFFF1F5F9)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Spending by Account
        Text(text = "SPENDING BY ACCOUNT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        Spacer(modifier = Modifier.height(8.dp))

        if (accountBreakdown.isEmpty()) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(text = "No account payments for this month.", fontSize = 12.sp, color = Color(0xFF64748B))
                }
            }
        } else {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    accountBreakdown.forEachIndexed { idx, (acc, amt) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onNavigateToAccountDrillDown(acc.id) }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFEFF6FF)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(imageVector = Icons.Default.AccountBalanceWallet, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(16.dp))
                                }
                                Column {
                                    Text(text = acc.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                    Text(
                                        text = acc.type + if (!acc.last4Digits.isNullOrEmpty()) " (••${acc.last4Digits})" else "",
                                        fontSize = 10.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                            Text(text = formatINR(amt), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                        }
                        if (idx < accountBreakdown.size - 1) {
                            HorizontalDivider(color = Color(0xFFF1F5F9))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tracking Source Analytics
        Text(text = "TRANSACTION AUTO-TRACKING SOURCES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(
                    "MANUAL" to "Manual Entry",
                    "SMS" to "SMS Auto-Tracking",
                    "NOTIFICATION" to "Notification Auto-Tracking",
                    "EMAIL" to "Email Auto-Tracking"
                ).forEachIndexed { idx, (srcKey, srcLabel) ->
                    val data = sourceBreakdown[srcKey] ?: Pair(0, 0.0)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onNavigateToSourceDrillDown(srcKey) }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = srcLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Text(text = "${data.first} transaction${if (data.first != 1) "s" else ""}", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        }
                        Text(text = formatINR(data.second), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                    }
                    if (idx < 3) {
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(96.dp))
    }
}
