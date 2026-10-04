package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
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
import com.example.viewmodel.KharchaViewModel
import java.text.NumberFormat
import java.util.Locale

@Composable
fun TransactionsScreen(
    viewModel: KharchaViewModel,
    onOpenAddExpense: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit
) {
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val subcategories by viewModel.subcategories.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val allSplits by viewModel.transactionSplits.collectAsState()
    val subTab by viewModel.transactionSubTab.collectAsState()

    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var selectedMerchantName by remember { mutableStateOf<String?>(null) }

    val formatINR: (Double) -> String = { amt ->
        "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(amt)
    }

    // Category Drill-Down Active View
    if (selectedCategoryId != null) {
        val catId = selectedCategoryId!!
        val category = categories.find { it.id == catId }
        val categoryName = category?.let { getBilingualName(it.name, it.nameHindi) } ?: if (catId.equals("other", ignoreCase = true)) "Other" else "Category"
        val categoryIcon = category?.icon ?: "📁"
        val categoryColor = try {
            Color(android.graphics.Color.parseColor(category?.colour ?: "#10B981"))
        } catch (e: Exception) {
            Color(0xFF10B981)
        }

        // All transactions matching this category (including split allocations)
        val expenseTxs = transactions.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }
        val categoryTxs = expenseTxs.filter { tx ->
            tx.categoryId == catId || allSplits.any { it.transactionId == tx.id && it.categoryId == catId }
        }.sortedWith(compareByDescending<TransactionEntity> { it.date }.thenByDescending { it.time })

        // Calculate exact total spend for this category
        val totalCatSpend = categoryTxs.sumOf { tx ->
            val txSplits = allSplits.filter { it.transactionId == tx.id && it.categoryId == catId }
            if (txSplits.isNotEmpty()) {
                txSplits.sumOf { it.amount }
            } else if (tx.categoryId == catId) {
                tx.amount
            } else {
                0.0
            }
        }

        CategoryDrillDownView(
            categoryName = categoryName,
            categoryIcon = categoryIcon,
            categoryColor = categoryColor,
            totalSpend = totalCatSpend,
            transactionCount = categoryTxs.size,
            transactions = categoryTxs,
            categories = categories,
            subcategories = subcategories,
            accounts = accounts,
            allSplits = allSplits,
            targetCategoryId = catId,
            formatINR = formatINR,
            onBack = { selectedCategoryId = null },
            onSelectTransaction = onSelectTransaction,
            onOpenAddExpense = onOpenAddExpense
        )
        return
    }

    // Merchant Drill-Down Active View
    if (selectedMerchantName != null) {
        val merchantName = selectedMerchantName!!
        val expenseTxs = transactions.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }
        val merchantTxs = expenseTxs.filter { tx ->
            val cat = categories.find { it.id == tx.categoryId }
            getCleanMerchantName(tx.merchant, cat?.name).equals(merchantName, ignoreCase = true)
        }.sortedWith(compareByDescending<TransactionEntity> { it.date }.thenByDescending { it.time })

        val totalMerchantSpend = merchantTxs.sumOf { it.amount }

        MerchantDrillDownView(
            merchantName = merchantName,
            totalSpend = totalMerchantSpend,
            transactionCount = merchantTxs.size,
            transactions = merchantTxs,
            categories = categories,
            subcategories = subcategories,
            accounts = accounts,
            allSplits = allSplits,
            formatINR = formatINR,
            onBack = { selectedMerchantName = null },
            onSelectTransaction = onSelectTransaction,
            onOpenAddExpense = onOpenAddExpense
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Transactions", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
            IconButton(
                onClick = onOpenAddExpense,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF059669))
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Transaction", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3 Segmented Sub-tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFE2E8F0))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            listOf("txs" to "Transactions", "cats" to "Categories", "merchants" to "Merchants").forEach { (tab, label) ->
                val selected = subTab == tab
                Button(
                    onClick = {
                        selectedCategoryId = null
                        selectedMerchantName = null
                        viewModel.transactionSubTab.value = tab
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color.White else Color.Transparent,
                        contentColor = if (selected) Color(0xFF047857) else Color(0xFF475569)
                    ),
                    elevation = if (selected) ButtonDefaults.buttonElevation(defaultElevation = 1.dp) else ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (subTab) {
            "txs" -> {
                var selectedSourceFilter by remember { mutableStateOf("All") }
                var selectedAccountIdFilter by remember { mutableStateOf("ALL") }

                val filteredTxs = transactions.filter { tx ->
                    val matchesSource = when (selectedSourceFilter) {
                        "Manual" -> tx.source == "MANUAL"
                        "SMS" -> tx.source == "SMS"
                        "Notification" -> tx.source == "NOTIFICATION"
                        "Email" -> tx.source == "EMAIL"
                        else -> true
                    }
                    val matchesAccount = if (selectedAccountIdFilter == "ALL") {
                        true
                    } else {
                        val acc = accounts.find { it.id == selectedAccountIdFilter }
                        tx.accountId == selectedAccountIdFilter || (acc != null && acc.last4Digits.isNotEmpty() && tx.last4Digits == acc.last4Digits)
                    }
                    matchesSource && matchesAccount
                }

                val sortedTxs = filteredTxs.sortedWith(compareByDescending<TransactionEntity> { it.date }.thenByDescending { it.time })

                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("All", "Manual", "SMS", "Notification", "Email").forEach { source ->
                            val selected = selectedSourceFilter == source
                            FilterChip(
                                selected = selected,
                                onClick = { selectedSourceFilter = source },
                                label = { Text(source, fontSize = 9.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF059669),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val isAllSelected = selectedAccountIdFilter == "ALL"
                        FilterChip(
                            selected = isAllSelected,
                            onClick = { selectedAccountIdFilter = "ALL" },
                            label = { Text("All Accounts", fontSize = 9.sp, fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Normal) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF0284C7),
                                selectedLabelColor = Color.White
                            )
                        )
                        accounts.filter { it.isActive }.forEach { acc ->
                            val selected = selectedAccountIdFilter == acc.id
                            val label = if (acc.last4Digits.isNotEmpty()) "${acc.name} • ${acc.last4Digits}" else acc.name
                            FilterChip(
                                selected = selected,
                                onClick = { selectedAccountIdFilter = acc.id },
                                label = { Text(label, fontSize = 9.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF0284C7),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }

                if (sortedTxs.isEmpty()) {
                    EmptyStateBox("No transactions yet", "Tap the + button to add your first transaction.", onOpenAddExpense)
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(sortedTxs, key = { it.id }) { tx ->
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
            "cats" -> {
                val expenseTxs = transactions.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }
                if (expenseTxs.isEmpty()) {
                    EmptyStateBox("No category spend yet", "Add expense transactions to see category breakdowns.", onOpenAddExpense)
                } else {
                    val catTotals = mutableMapOf<String, Double>()
                    val catCounts = mutableMapOf<String, Int>()

                    expenseTxs.forEach { tx ->
                        val txSplits = allSplits.filter { it.transactionId == tx.id }
                        if (txSplits.isNotEmpty()) {
                            txSplits.forEach { s ->
                                catTotals[s.categoryId] = (catTotals[s.categoryId] ?: 0.0) + s.amount
                                catCounts[s.categoryId] = (catCounts[s.categoryId] ?: 0) + 1
                            }
                        } else {
                            catTotals[tx.categoryId] = (catTotals[tx.categoryId] ?: 0.0) + tx.amount
                            catCounts[tx.categoryId] = (catCounts[tx.categoryId] ?: 0) + 1
                        }
                    }

                    val catAgg = catTotals.map { (catId, total) ->
                        val count = catCounts[catId] ?: 0
                        catId to Pair(total, count)
                    }.sortedByDescending { it.second.first }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(catAgg) { (catId, data) ->
                            val cat = categories.find { it.id == catId }
                            val catColor = try {
                                Color(android.graphics.Color.parseColor(cat?.colour ?: "#10B981"))
                            } catch (e: Exception) {
                                Color(0xFF10B981)
                            }
                            Card(
                                onClick = { selectedCategoryId = catId },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(catColor),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(text = cat?.icon ?: "📁", fontSize = 18.sp)
                                        }
                                        Column {
                                            Text(
                                                text = cat?.let { getBilingualName(it.name, it.nameHindi) } ?: if (catId.equals("other", ignoreCase = true)) "Other" else "General",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF0F172A),
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${data.second} transaction${if (data.second > 1) "s" else ""}",
                                                fontSize = 11.sp,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = formatINR(data.first),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF0F172A)
                                        )
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                            contentDescription = "View Category History",
                                            tint = Color(0xFFCBD5E1),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "merchants" -> {
                val expenseTxs = transactions.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }
                if (expenseTxs.isEmpty()) {
                    EmptyStateBox("No merchant spend yet", "Add expense transactions to see merchant breakdowns.", onOpenAddExpense)
                } else {
                    val merchantAgg = expenseTxs.groupBy { tx ->
                        val cat = categories.find { it.id == tx.categoryId }
                        getCleanMerchantName(tx.merchant, cat?.name)
                    }.map { (merchantName, txsForMerchant) ->
                        val sortedTxs = txsForMerchant.sortedWith(compareByDescending<TransactionEntity> { it.date }.thenByDescending { it.time })
                        val lastTx = sortedTxs.first()
                        val lastDate = lastTx.date
                        
                        val totalSpend = txsForMerchant.sumOf { it.amount }
                        val txCount = txsForMerchant.size
                        
                        val lastCat = categories.find { it.id == lastTx.categoryId }
                        val lastSubcat = subcategories.find { it.id == lastTx.subcategoryId }
                        
                        MerchantListItemData(
                            name = merchantName,
                            categoryName = lastCat?.let { getBilingualName(it.name, it.nameHindi) } ?: "Other",
                            categoryIcon = lastCat?.icon ?: "🏷️",
                            subcategoryName = lastSubcat?.let { getBilingualName(it.name, it.nameHindi) },
                            subcategoryIcon = lastSubcat?.icon,
                            transactionCount = txCount,
                            totalSpending = totalSpend,
                            lastTransactionDate = lastDate
                        )
                    }.sortedByDescending { it.totalSpending }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(merchantAgg) { merchant ->
                            Card(
                                onClick = { selectedMerchantName = merchant.name },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(42.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(Color(0xFFEFF6FF)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = merchant.name.take(1).uppercase(),
                                                    fontSize = 18.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF2563EB)
                                                )
                                            }
                                            Column {
                                                Text(
                                                    text = merchant.name,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF0F172A),
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Surface(
                                                        color = Color(0xFFF1F5F9),
                                                        shape = RoundedCornerShape(6.dp)
                                                    ) {
                                                        Text(
                                                            text = "${merchant.categoryIcon} ${merchant.categoryName}",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF475569),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                    if (!merchant.subcategoryName.isNullOrEmpty()) {
                                                        Surface(
                                                            color = Color(0xFFEFF6FF),
                                                            shape = RoundedCornerShape(6.dp)
                                                        ) {
                                                            Text(
                                                                text = "${merchant.subcategoryIcon ?: "🏷️"} ${merchant.subcategoryName}",
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color(0xFF1D4ED8),
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = formatINR(merchant.totalSpending),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF0F172A)
                                            )
                                            Text(
                                                text = "${merchant.transactionCount} txs",
                                                fontSize = 10.sp,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(color = Color(0xFFF1F5F9))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Last active: ${formatDateNice(merchant.lastTransactionDate)}",
                                            fontSize = 10.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(text = "View History", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                                contentDescription = null,
                                                tint = Color(0xFF059669),
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Category Drill-Down Screen: Shows category details, total spent, count, and all transactions
 */
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
    onOpenAddExpense: () -> Unit
) {
    BackHandler(onBack = onBack)

    var selectedSourceFilter by remember { mutableStateOf("All") }

    // Group transactions by month for the chart
    val monthlyTotals = remember(transactions, targetCategoryId, allSplits) {
        val rawMap = KharchaViewModel.aggregateMonthlySpendingForCategory(transactions, targetCategoryId, allSplits)
        val canonicalMap = mutableMapOf<String, Double>()
        rawMap.forEach { (key, amount) ->
            val cKey = KharchaViewModel.extractYearMonthKey(key)
            if (cKey.isNotBlank()) {
                canonicalMap[cKey] = (canonicalMap[cKey] ?: 0.0) + amount
            }
        }
        canonicalMap.toSortedMap()
    }
    
    var selectedMonth by remember { mutableStateOf(monthlyTotals.keys.maxOrNull() ?: "") }

    val filteredTxs = transactions.filter { tx ->
        val sourceMatch = when (selectedSourceFilter) {
            "Manual" -> tx.source == "MANUAL"
            "SMS" -> tx.source == "SMS"
            "Notification" -> tx.source == "NOTIFICATION"
            "Email" -> tx.source == "EMAIL"
            else -> true
        }
        val monthMatch = selectedMonth.isEmpty() || KharchaViewModel.extractYearMonthKey(tx.date) == selectedMonth
        sourceMatch && monthMatch
    }

    // 1. Subcategory breakdown
    val subcatSpendMap = remember(transactions, targetCategoryId, allSplits) {
        val map = mutableMapOf<String, Double>()
        transactions.forEach { tx ->
            val txSplits = allSplits.filter { it.transactionId == tx.id && it.categoryId == targetCategoryId }
            if (txSplits.isNotEmpty()) {
                txSplits.forEach { s ->
                    val subId = s.subcategoryId
                    map[subId] = (map[subId] ?: 0.0) + s.amount
                }
            } else if (tx.categoryId == targetCategoryId) {
                val subId = tx.subcategoryId
                map[subId] = (map[subId] ?: 0.0) + tx.amount
            }
        }
        map
    }

    val subcatBreakdown = remember(subcatSpendMap, subcategories) {
        subcatSpendMap.map { (subId, spend) ->
            val sub = subcategories.find { it.id == subId }
            val name = sub?.let { getBilingualName(it.name, it.nameHindi) } ?: "Uncategorized"
            val icon = sub?.icon ?: "🏷️"
            Triple(name, icon, spend)
        }.sortedByDescending { it.third }
    }

    // 2. Merchant breakdown
    val merchantSpendMap = remember(transactions, targetCategoryId, allSplits) {
        val map = mutableMapOf<String, Double>()
        transactions.forEach { tx ->
            val txSplits = allSplits.filter { it.transactionId == tx.id && it.categoryId == targetCategoryId }
            val cleanName = getCleanMerchantName(tx.merchant, categoryName)
            val spendAmount = if (txSplits.isNotEmpty()) {
                txSplits.sumOf { it.amount }
            } else {
                tx.amount
            }
            map[cleanName] = (map[cleanName] ?: 0.0) + spendAmount
        }
        map
    }

    val merchantBreakdown = remember(merchantSpendMap) {
        merchantSpendMap.map { (mName, spend) ->
            Pair(mName, spend)
        }.sortedByDescending { it.second }
    }

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
                        contentDescription = "Back to Categories",
                        tint = Color(0xFF0F172A),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(text = "Category History", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                    Text(text = "All transactions in $categoryName", fontSize = 11.sp, color = Color(0xFF64748B))
                }
            }

            IconButton(
                onClick = onOpenAddExpense,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF059669))
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Expense", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Hero Summary Card
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(categoryColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = categoryIcon, fontSize = 24.sp)
                            }
                            Column {
                                Text(
                                    text = categoryName,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Surface(
                                    color = Color(0xFFF1F5F9),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = "$transactionCount Transaction${if (transactionCount != 1) "s" else ""}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF475569),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(text = "TOTAL SPENT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), maxLines = 1, softWrap = false)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formatINR(totalSpend),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF0F172A),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
            
            // Monthly Spending Chart
            if (monthlyTotals.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Monthly Spending", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), modifier = Modifier.padding(horizontal = 4.dp))
                    MonthlyBarChart(
                        data = monthlyTotals,
                        selectedMonth = selectedMonth,
                        onMonthSelected = { selectedMonth = if (selectedMonth == it) "" else it },
                        formatINR = formatINR
                    )
                }
            }

            // Subcategory Breakdown Card
            if (subcatBreakdown.isNotEmpty()) {
                item {
                    Text(
                        text = "Subcategory Breakdown",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    )
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            subcatBreakdown.forEach { (name, icon, spend) ->
                                val pct = if (totalSpend > 0) spend / totalSpend else 0.0
                                val pctText = String.format("%.1f%%", pct * 100)
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(text = icon, fontSize = 14.sp)
                                            Text(text = name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(text = formatINR(spend), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                            Text(text = "($pctText)", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { pct.toFloat().coerceIn(0f, 1f) },
                                        color = categoryColor,
                                        trackColor = Color(0xFFF1F5F9),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Merchant Breakdown Card
            if (merchantBreakdown.isNotEmpty()) {
                item {
                    Text(
                        text = "Merchant Breakdown",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    )
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            merchantBreakdown.take(5).forEachIndexed { index, (mName, spend) ->
                                val pct = if (totalSpend > 0) spend / totalSpend else 0.0
                                val pctText = String.format("%.1f%%", pct * 100)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFFEFF6FF)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(text = mName.take(1).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
                                        }
                                        Text(text = mName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF334155))
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = formatINR(spend), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                        Text(text = "($pctText)", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                    }
                                }
                                if (index < merchantBreakdown.take(5).lastIndex) {
                                    HorizontalDivider(color = Color(0xFFF1F5F9))
                                }
                            }
                        }
                    }
                }
            }

            // Source Filter Chips Header inside List
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Transactions List", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("All", "Manual", "SMS", "Notif", "Email").forEach { source ->
                            val isSelected = when (source) {
                                "All" -> selectedSourceFilter == "All"
                                "Notif" -> selectedSourceFilter == "Notification"
                                else -> selectedSourceFilter == source
                            }
                            val label = source
                            Surface(
                                onClick = {
                                    selectedSourceFilter = when (source) {
                                        "Notif" -> "Notification"
                                        else -> source
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFF059669) else Color(0xFFF1F5F9),
                                modifier = Modifier.padding(horizontal = 2.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else Color(0xFF64748B),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Transactions List
            if (filteredTxs.isEmpty()) {
                item {
                    EmptyStateBox("No transactions in $categoryName", "Tap Add to record a new transaction under $categoryName.", onOpenAddExpense)
                }
            } else {
                items(filteredTxs, key = { it.id }) { tx ->
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

/**
 * Merchant Drill-Down Screen: Shows merchant details, total amount, count, and all transactions
 */
@Composable
fun MerchantDrillDownView(
    merchantName: String,
    totalSpend: Double,
    transactionCount: Int,
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    subcategories: List<SubcategoryEntity>,
    accounts: List<AccountEntity>,
    allSplits: List<TransactionSplitEntity>,
    formatINR: (Double) -> String,
    onBack: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit,
    onOpenAddExpense: () -> Unit
) {
    BackHandler(onBack = onBack)

    var selectedSourceFilter by remember { mutableStateOf("All") }
    
    // Group transactions by month for the chart
    val monthlyTotals = remember(transactions, merchantName, categories) {
        val rawMap = KharchaViewModel.aggregateMonthlySpendingForMerchant(transactions, merchantName, categories)
        val canonicalMap = mutableMapOf<String, Double>()
        rawMap.forEach { (key, amount) ->
            val cKey = KharchaViewModel.extractYearMonthKey(key)
            if (cKey.isNotBlank()) {
                canonicalMap[cKey] = (canonicalMap[cKey] ?: 0.0) + amount
            }
        }
        canonicalMap.toSortedMap()
    }
    
    var selectedMonth by remember { mutableStateOf(monthlyTotals.keys.maxOrNull() ?: "") }

    val filteredTxs = transactions.filter { tx ->
        val sourceMatch = when (selectedSourceFilter) {
            "Manual" -> tx.source == "MANUAL"
            "SMS" -> tx.source == "SMS"
            "Notification" -> tx.source == "NOTIFICATION"
            "Email" -> tx.source == "EMAIL"
            else -> true
        }
        val monthMatch = selectedMonth.isEmpty() || KharchaViewModel.extractYearMonthKey(tx.date) == selectedMonth
        sourceMatch && monthMatch
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top Navigation Bar (Stays fixed)
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
                        contentDescription = "Back to Merchants",
                        tint = Color(0xFF0F172A),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(text = "Merchant History", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                    Text(text = "All activity with $merchantName", fontSize = 11.sp, color = Color(0xFF64748B))
                }
            }

            IconButton(
                onClick = onOpenAddExpense,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF059669))
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Expense", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Hero Summary Card
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color(0xFFEFF6FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = merchantName.take(1).uppercase(),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2563EB)
                                )
                            }
                            Column {
                                Text(
                                    text = merchantName,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Surface(
                                    color = Color(0xFFF1F5F9),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = "$transactionCount Transaction${if (transactionCount != 1) "s" else ""}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF475569),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(text = "TOTAL SPENT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), maxLines = 1, softWrap = false)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formatINR(totalSpend),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF0F172A),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // Monthly Spending Chart
            if (monthlyTotals.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Monthly Spending",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF475569),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    MonthlyBarChart(
                        data = monthlyTotals,
                        selectedMonth = selectedMonth,
                        onMonthSelected = { selectedMonth = if (selectedMonth == it) "" else it },
                        formatINR = formatINR
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Source Filter Chips
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("All", "Manual", "SMS", "Notification", "Email").forEach { source ->
                        val selected = selectedSourceFilter == source
                        FilterChip(
                            selected = selected,
                            onClick = { selectedSourceFilter = source },
                            label = { Text(source, fontSize = 9.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF059669),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Transactions List
            if (filteredTxs.isEmpty()) {
                item {
                    EmptyStateBox("No transactions for $merchantName", "Tap Add to record a new transaction with $merchantName.", onOpenAddExpense)
                }
            } else {
                items(filteredTxs, key = { it.id }) { tx ->
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

data class MerchantListItemData(
    val name: String,
    val categoryName: String,
    val categoryIcon: String,
    val subcategoryName: String?,
    val subcategoryIcon: String?,
    val transactionCount: Int,
    val totalSpending: Double,
    val lastTransactionDate: String
)
