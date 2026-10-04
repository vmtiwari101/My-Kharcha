package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import com.example.data.entity.CardEntity
import com.example.viewmodel.KharchaViewModel
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import java.util.Locale

@Composable
fun TransactionListItemCard(
    tx: TransactionEntity,
    categories: List<CategoryEntity>,
    subcategories: List<SubcategoryEntity>,
    accounts: List<AccountEntity>,
    allSplits: List<TransactionSplitEntity>,
    formatINR: (Double) -> String,
    onSelect: () -> Unit
) {
    val txSplits = allSplits.filter { it.transactionId == tx.id }
    val isSplit = txSplits.isNotEmpty()

    val cat = categories.find { it.id == tx.categoryId }
    val subcat = subcategories.find { it.id == tx.subcategoryId }
    val acc = accounts.find { it.id == tx.accountId }
    val isInternal = tx.isInternalTransfer || tx.type == "INTERNAL_TRANSFER" || tx.transactionType == "INTERNAL_TRANSFER"
    val isOutgoing = tx.type == "EXPENSE" || tx.direction == "DEBIT"
    val catColor = try {
        Color(android.graphics.Color.parseColor(if (isInternal) "#3B82F6" else if (isSplit) "#8B5CF6" else (cat?.colour ?: "#10B981")))
    } catch (e: Exception) {
        Color(0xFF3B82F6)
    }

    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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
                    Text(text = if (isInternal) "🔄" else if (isSplit) "🔀" else (cat?.icon ?: "💳"), fontSize = 18.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), 
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val displayName = if (isInternal) {
                            if (tx.merchant.startsWith("Friend: ", ignoreCase = true)) {
                                "Friend: " + tx.merchant.substringAfter("Friend: ").trim()
                            } else if (tx.merchant.startsWith("Transfer: ", ignoreCase = true)) {
                                tx.merchant.substringAfter("Transfer: ").trim()
                            } else {
                                val bankOrAccName = acc?.bankName?.ifEmpty { acc.name } ?: acc?.name ?: "Bank Account"
                                val last4 = if (acc != null && acc.last4Digits.isNotEmpty()) acc.last4Digits else tx.last4Digits
                                if (last4.isNotEmpty()) {
                                    "$bankOrAccName ending $last4"
                                } else {
                                    bankOrAccName
                                }
                            }
                        } else {
                            getCleanMerchantName(tx.merchant.ifEmpty { cat?.name ?: "General" }, cat?.name)
                        }
                        Text(
                            text = displayName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isSplit) {
                            Surface(
                                color = Color(0xFFEDE9FE),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = "SPLIT (${txSplits.size})",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF6D28D9),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        } else if (subcat != null && !isInternal) {
                            Text(text = "• ${getBilingualName(subcat.name, subcat.nameHindi)}", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), 
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isInternal) "Internal Transfer" else if (isSplit) "Multi-category" else (cat?.let { getBilingualName(it.name, it.nameHindi) } ?: ""),
                            fontSize = 10.sp,
                            color = Color(0xFF64748B),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(text = "•", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        Text(
                            text = "${formatDateNice(tx.date)}${if (tx.time.isNotEmpty()) " ${tx.time}" else ""}", 
                            fontSize = 10.sp, 
                            color = Color(0xFF64748B),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = (if (isOutgoing) "- " else "+ ") + formatINR(tx.amount),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isOutgoing) Color(0xFF0F172A) else Color(0xFF059669)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isInternal) {
                        Surface(
                            color = Color(0xFFEFF6FF),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "Internal Transfer",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1D4ED8),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                maxLines = 1
                            )
                        }
                    } else {
                        val sourceBg = when (tx.source.uppercase()) {
                            "SMS" -> Color(0xFFECFDF5)
                            "NOTIFICATION" -> Color(0xFFF3E8FF)
                            "EMAIL" -> Color(0xFFEFF6FF)
                            else -> Color(0xFFF1F5F9)
                        }
                        val sourceTextColor = when (tx.source.uppercase()) {
                            "SMS" -> Color(0xFF047857)
                            "NOTIFICATION" -> Color(0xFF7C3AED)
                            "EMAIL" -> Color(0xFF1D4ED8)
                            else -> Color(0xFF475569)
                        }
                        val sourceLabel = when (tx.source.uppercase()) {
                            "SMS" -> "SMS"
                            "NOTIFICATION" -> "Notif"
                            "EMAIL" -> "Email"
                            else -> "Manual"
                        }
                        Surface(
                            color = sourceBg,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = sourceLabel,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = sourceTextColor,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                maxLines = 1
                            )
                        }
                    }
                    val accLabel = if (acc != null) acc.name else tx.paymentMethod.ifEmpty { "Cash" }
                    Text(
                        text = accLabel,
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 100.dp)
                    )
                }
            }
        }
    }
}

data class SelectionItem(
    val id: String, // accountId
    val cardId: String? = null,
    val name: String,
    val bankName: String,
    val last4Digits: String,
    val type: String, // "Bank Account", "Credit Card", "Debit Card", "UPI", "Other"
    val originalAccount: AccountEntity? = null,
    val originalCard: CardEntity? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupedAccountSelectorDialog(
    accounts: List<AccountEntity>,
    cards: List<CardEntity>,
    onDismiss: () -> Unit,
    onSelected: (String, String) -> Unit // returns accountId, determinedType/paymentMethod
) {
    var searchQuery by remember { mutableStateOf("") }

    // Map active accounts and cards to unified list
    val activeAccounts = accounts.filter { it.isActive }
    val itemsList = remember(accounts, cards) {
        val rawList = mutableListOf<SelectionItem>()
        for (acc in activeAccounts) {
            val finalType = when (acc.type) {
                "Credit Card" -> "Credit Card"
                "Bank Account" -> "Bank Account"
                "Debit Card" -> "Debit Card"
                "UPI" -> "UPI"
                else -> "Other"
            }
            rawList.add(
                SelectionItem(
                    id = acc.id,
                    name = acc.name,
                    bankName = acc.bankName.ifEmpty { acc.name },
                    last4Digits = acc.last4Digits,
                    type = finalType,
                    originalAccount = acc
                )
            )
        }
        for (card in cards) {
            val parentAcc = activeAccounts.find { it.id == card.accountId } ?: continue
            val cardType = if (card.type == "Credit Card") "Credit Card" else "Debit Card"

            val isDuplicate = rawList.any {
                it.id == card.accountId && it.last4Digits == card.last4Digits && it.type == cardType
            }
            if (isDuplicate) continue

            val duplicateAccount = activeAccounts.any {
                (it.id == card.accountId || it.last4Digits == card.last4Digits) && it.type == cardType
            }
            if (duplicateAccount) continue

            rawList.add(
                SelectionItem(
                    id = card.accountId,
                    cardId = card.id,
                    name = card.name,
                    bankName = parentAcc.bankName.ifEmpty { parentAcc.name },
                    last4Digits = card.last4Digits,
                    type = cardType,
                    originalCard = card
                )
            )
        }

        // Filter out incomplete duplicates
        val filtered = rawList.groupBy { it.bankName to it.type }.map { (_, items) ->
            val complete = items.filter { it.last4Digits.isNotEmpty() }
            if (complete.isNotEmpty()) {
                complete.distinctBy { it.last4Digits }
            } else {
                items.take(1)
            }
        }.flatten()

        filtered
    }

    // Filter items based on searchQuery
    val filteredItems = remember(itemsList, searchQuery) {
        if (searchQuery.isBlank()) {
            itemsList
        } else {
            val query = searchQuery.trim().lowercase(Locale.US)
            itemsList.filter { item ->
                item.name.lowercase(Locale.US).contains(query) ||
                        item.bankName.lowercase(Locale.US).contains(query) ||
                        item.last4Digits.contains(query)
            }
        }
    }

    // Group filtered items
    val bankAccounts = filteredItems.filter { it.type == "Bank Account" }
    val creditCards = filteredItems.filter { it.type == "Credit Card" }
    val debitCards = filteredItems.filter { it.type == "Debit Card" }
    val upiOther = filteredItems.filter { it.type == "UPI" || it.type == "Other" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Account or Card",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
            ) {
                // Search Field at the top
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search account or card", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = Color(0xFF64748B),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF059669),
                        unfocusedBorderColor = Color(0xFFE2E8F0)
                    )
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (bankAccounts.isNotEmpty()) {
                        item {
                            SelectorSectionHeader("BANK ACCOUNTS")
                        }
                        items(bankAccounts) { item ->
                            SelectorRowItem(item) {
                                onSelected(item.id, item.type)
                                onDismiss()
                            }
                        }
                    }

                    if (creditCards.isNotEmpty()) {
                        item {
                            SelectorSectionHeader("CREDIT CARDS")
                        }
                        items(creditCards) { item ->
                            SelectorRowItem(item) {
                                onSelected(item.id, item.type)
                                onDismiss()
                            }
                        }
                    }

                    if (debitCards.isNotEmpty()) {
                        item {
                            SelectorSectionHeader("DEBIT CARDS")
                        }
                        items(debitCards) { item ->
                            SelectorRowItem(item) {
                                onSelected(item.id, item.type)
                                onDismiss()
                            }
                        }
                    }

                    if (upiOther.isNotEmpty()) {
                        item {
                            SelectorSectionHeader("UPI / OTHER")
                        }
                        items(upiOther) { item ->
                            SelectorRowItem(item) {
                                onSelected(item.id, item.type)
                                onDismiss()
                            }
                        }
                    }

                    if (bankAccounts.isEmpty() && creditCards.isEmpty() && debitCards.isEmpty() && upiOther.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No accounts or cards found",
                                    fontSize = 13.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF059669))
            }
        }
    )
}

@Composable
fun SelectorSectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF64748B),
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        HorizontalDivider(color = Color(0xFFF1F5F9))
    }
}

@Composable
fun SelectorRowItem(item: SelectionItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BankLogo(
                bankName = item.bankName,
                size = 32.dp,
                shapeRadius = 8.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.bankName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "•••• ${item.last4Digits.ifEmpty { "XXXX" }}",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}

@Composable
fun MonthlyBarChart(
    data: Map<String, Double>,
    selectedMonth: String,
    onMonthSelected: (String) -> Unit,
    formatINR: (Double) -> String
) {
    // Defensive canonicalization step immediately before the chart receives/renders data
    val canonicalData = remember(data) {
        val canonicalMap = mutableMapOf<String, Double>()
        data.forEach { (rawKey, amount) ->
            val cKey = KharchaViewModel.extractYearMonthKey(rawKey)
            if (cKey.isNotBlank()) {
                canonicalMap[cKey] = (canonicalMap[cKey] ?: 0.0) + amount
            }
        }
        canonicalMap.toSortedMap()
    }

    val months = canonicalData.keys.toList().sorted()
    val maxAmount = canonicalData.values.maxOrNull() ?: 1.0
    val scrollState = rememberScrollState()

    // Internal helper for compact currency display in chart
    val formatCompact: (Double) -> String = { amt ->
        when {
            amt >= 10000000 -> String.format("₹%.1fCr", amt / 10000000)
            amt >= 100000 -> String.format("₹%.1fL", amt / 100000)
            amt >= 1000 -> String.format("₹%.1fK", amt / 1000)
            else -> "₹${amt.toInt()}"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            months.forEach { month ->
                val amount = canonicalData[month] ?: 0.0
                val isSelected = month == selectedMonth
                
                // Calculate height proportionally (min 4dp if > 0)
                val barHeight = if (amount > 0) {
                    (amount / maxAmount * 80.dp.value).coerceAtLeast(4.0).dp
                } else 0.dp

                val monthParts = month.split("-")
                val monthLabel = if (monthParts.size >= 2) {
                    try {
                        val m = monthParts[1].toInt()
                        val labels = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                        val mName = if (m in 1..12) labels[m - 1] else month
                        val hasMultipleYears = canonicalData.keys.mapNotNull { it.split("-").firstOrNull() }.distinct().size > 1
                        if (hasMultipleYears && monthParts[0].length >= 2) {
                            "$mName '${monthParts[0].takeLast(2)}"
                        } else {
                            mName
                        }
                    } catch (e: Exception) {
                        month
                    }
                } else month

                Column(
                    modifier = Modifier
                        .width(36.dp)
                        .clickable { onMonthSelected(month) }
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFFF1F5F9) else Color.Transparent)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (amount > 0) {
                        Text(
                            text = formatCompact(amount),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color(0xFF2563EB) else Color(0xFF64748B),
                            maxLines = 1
                        )
                    } else {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (isSelected) Color(0xFF2563EB) else Color(0xFFCBD5E1))
                    )
                    
                    Text(
                        text = monthLabel,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color(0xFF0F172A) else Color(0xFF94A3B8)
                    )
                }
            }
        }
    }
}
