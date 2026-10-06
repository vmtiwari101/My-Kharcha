package com.example.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.viewmodel.KharchaViewModel
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(
    initialType: String, // "EXPENSE" or "INCOME"
    viewModel: KharchaViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val categories by viewModel.categories.collectAsState()
    val subcategories by viewModel.subcategories.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val cards by viewModel.cards.collectAsState()

    var txType by remember { mutableStateOf(when { initialType.equals("TRANSFER", ignoreCase = true) -> "TRANSFER"; initialType.equals("INCOME", ignoreCase = true) -> "INCOME"; else -> "EXPENSE" }) }
    var isExpense by remember { mutableStateOf(true) }
    var amountStr by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    // Transfer Specific States
    var transferDestType by remember { mutableStateOf("BANK") } // "BANK", "CARD", "UPI", "FRIEND", "OTHER"
    var targetAccountId by remember { mutableStateOf("") }
    var friendName by remember { mutableStateOf("") }
    var otherDescription by remember { mutableStateOf("") }

    val allTransactions by viewModel.transactions.collectAsState()
    val recentFriends = remember(allTransactions) {
        allTransactions
            .mapNotNull { tx ->
                if (tx.merchant.startsWith("Friend: ", ignoreCase = true)) {
                    tx.merchant.substringAfter("Friend: ").trim()
                } else null
            }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(6)
    }

    val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
    val nowTime = java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date())

    var dateStr by remember { mutableStateOf(todayDate) }
    var timeStr by remember { mutableStateOf(nowTime) }

    val filteredCats = categories.filter { if (txType == "INCOME") it.isIncome && it.id != "cat-transfer" else !it.isIncome && it.id != "cat-transfer" }
    var selectedCategoryId by remember { mutableStateOf(filteredCats.firstOrNull()?.id ?: "") }
    var selectedSubcategoryId by remember { mutableStateOf("") }
    var selectedAccountId by remember { mutableStateOf(accounts.firstOrNull()?.id ?: "") }

    LaunchedEffect(accounts) {
        if (selectedAccountId.isEmpty() && accounts.isNotEmpty()) {
            selectedAccountId = accounts.first().id
        }
    }

    LaunchedEffect(txType, categories) {
        val newFiltered = categories.filter { if (txType == "INCOME") it.isIncome && it.id != "cat-transfer" else !it.isIncome && it.id != "cat-transfer" }
        if (newFiltered.isNotEmpty() && !newFiltered.any { it.id == selectedCategoryId }) {
            selectedCategoryId = newFiltered.first().id
            selectedSubcategoryId = ""
        }
    }

    var showCatModal by remember { mutableStateOf(false) }
    var showSubcatModal by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = showCatModal) {
        showCatModal = false
    }
    androidx.activity.compose.BackHandler(enabled = showSubcatModal) {
        showSubcatModal = false
    }
    androidx.activity.compose.BackHandler(enabled = !showCatModal && !showSubcatModal) {
        onDismiss()
    }

    val emojis = listOf("🍔", "🛒", "🚗", "⛽", "🛍️", "💡", "🏠", "❤️", "📚", "🎬", "📱", "🧴", "✈️", "💼", "💰", "☕", "🍕", "💊", "🎓", "🎁", "🏋️", "⚡", "📶", "🎟️", "🩺", "📦", "💳", "⭐")
    val colors = listOf("#EF4444", "#F97316", "#F59E0B", "#10B981", "#059669", "#06B6D4", "#3B82F6", "#6366F1", "#8B5CF6", "#EC4899", "#64748B", "#14B8A6")

    var newCatName by remember { mutableStateOf("") }
    var newCatNameHindi by remember { mutableStateOf("") }
    var newCatIcon by remember { mutableStateOf("🍔") }
    var newCatColor by remember { mutableStateOf("#10B981") }

    var newSubcatName by remember { mutableStateOf("") }
    var newSubcatNameHindi by remember { mutableStateOf("") }
    var newSubcatIcon by remember { mutableStateOf("🏷️") }

    // Date & Time Picker Dialogs Chain
    fun openDateTimePicker() {
        val cal = Calendar.getInstance()
        val dateParts = dateStr.split("-")
        val initialYear = dateParts.getOrNull(0)?.toIntOrNull() ?: cal.get(Calendar.YEAR)
        val initialMonth = (dateParts.getOrNull(1)?.toIntOrNull()?.minus(1)) ?: cal.get(Calendar.MONTH)
        val initialDay = dateParts.getOrNull(2)?.toIntOrNull() ?: cal.get(Calendar.DAY_OF_MONTH)

        val timeParts = timeStr.split(":")
        val initialHour = timeParts.getOrNull(0)?.toIntOrNull() ?: cal.get(Calendar.HOUR_OF_DAY)
        val initialMinute = timeParts.getOrNull(1)?.toIntOrNull() ?: cal.get(Calendar.MINUTE)

        val timePickerDialog = TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                timeStr = String.format(Locale.ROOT, "%02d:%02d", hourOfDay, minute)
            },
            initialHour,
            initialMinute,
            true
        )

        val datePickerDialog = DatePickerDialog(
            context,
            { _, year, monthOfYear, dayOfMonth ->
                dateStr = String.format(Locale.ROOT, "%04d-%02d-%02d", year, monthOfYear + 1, dayOfMonth)
                timePickerDialog.show()
            },
            initialYear,
            initialMonth,
            initialDay
        )

        datePickerDialog.show()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.5f)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            val maxAvailableHeight = maxHeight
            Card(
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxAvailableHeight - 16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Fixed Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (txType) {
                                "EXPENSE" -> "New Expense"
                                "INCOME" -> "New Income"
                                else -> "New Transfer"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF0F172A)
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF64748B))
                        }
                    }

                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    // Scrollable Body Content
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. Transaction Type Selector Tabs (Expense / Income / Transfer)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFFF1F5F9))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Surface(
                                onClick = {
                                    txType = "EXPENSE"
                                    val newFiltered = categories.filter { !it.isIncome && it.id != "cat-transfer" }
                                    selectedCategoryId = newFiltered.firstOrNull()?.id ?: ""
                                    selectedSubcategoryId = ""
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (txType == "EXPENSE") Color(0xFFDC2626) else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "Expense",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (txType == "EXPENSE") Color.White else Color(0xFF64748B)
                                    )
                                }
                            }

                            Surface(
                                onClick = {
                                    txType = "INCOME"
                                    val newFiltered = categories.filter { it.isIncome && it.id != "cat-transfer" }
                                    selectedCategoryId = newFiltered.firstOrNull()?.id ?: ""
                                    selectedSubcategoryId = ""
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (txType == "INCOME") Color(0xFF059669) else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "Income",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (txType == "INCOME") Color.White else Color(0xFF64748B)
                                    )
                                }
                            }

                            Surface(
                                onClick = {
                                    txType = "TRANSFER"
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (txType == "TRANSFER") Color(0xFF2563EB) else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "Transfer",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (txType == "TRANSFER") Color.White else Color(0xFF64748B)
                                    )
                                }
                            }
                        }

                        // Transfer Destination Section (Visible only in Transfer mode)
                        if (txType == "TRANSFER") {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("Transfer Destination Type", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1E3A8A))

                                    val destOptions = listOf(
                                        "BANK" to "Bank Account",
                                        "CARD" to "Credit Card",
                                        "UPI" to "UPI / Payment",
                                        "FRIEND" to "Friend",
                                        "OTHER" to "Other"
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        destOptions.forEach { (typeKey, label) ->
                                            val selected = transferDestType == typeKey
                                            FilterChip(
                                                selected = selected,
                                                onClick = { transferDestType = typeKey },
                                                label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = Color(0xFF2563EB),
                                                    selectedLabelColor = Color.White
                                                )
                                            )
                                        }
                                    }

                                    when (transferDestType) {
                                        "BANK" -> {
                                            val bankAccounts = accounts.filter { it.id != selectedAccountId }
                                            Text("Select Target Bank Account:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                            if (bankAccounts.isEmpty()) {
                                                Text("No other accounts available.", fontSize = 11.sp, color = Color(0xFF64748B))
                                            } else {
                                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    items(bankAccounts) { acc ->
                                                        val selected = targetAccountId == acc.id
                                                        Surface(
                                                            onClick = { targetAccountId = acc.id },
                                                            shape = RoundedCornerShape(12.dp),
                                                            color = if (selected) Color(0xFF2563EB) else Color.White,
                                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Color(0xFF1D4ED8) else Color(0xFFCBD5E1))
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                BankLogo(bankName = acc.bankName.ifEmpty { acc.name }, size = 18.dp)
                                                                Text(acc.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Color(0xFF0F172A))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        "CARD" -> {
                                            val cardAccounts = accounts.filter { it.id != selectedAccountId && (it.type == "CREDIT_CARD" || cards.any { c -> c.accountId == it.id }) }
                                            Text("Select Target Credit Card / Account:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                            if (cardAccounts.isEmpty()) {
                                                Text("No credit card accounts available.", fontSize = 11.sp, color = Color(0xFF64748B))
                                            } else {
                                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    items(cardAccounts) { acc ->
                                                        val selected = targetAccountId == acc.id
                                                        Surface(
                                                            onClick = { targetAccountId = acc.id },
                                                            shape = RoundedCornerShape(12.dp),
                                                            color = if (selected) Color(0xFF2563EB) else Color.White,
                                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Color(0xFF1D4ED8) else Color(0xFFCBD5E1))
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                Text("💳", fontSize = 14.sp)
                                                                Text(acc.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Color(0xFF0F172A))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        "UPI" -> {
                                            val upiAccounts = accounts.filter { it.id != selectedAccountId }
                                            Text("Select Target UPI / Payment Account:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                            if (upiAccounts.isEmpty()) {
                                                Text("No other payment accounts available.", fontSize = 11.sp, color = Color(0xFF64748B))
                                            } else {
                                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    items(upiAccounts) { acc ->
                                                        val selected = targetAccountId == acc.id
                                                        Surface(
                                                            onClick = { targetAccountId = acc.id },
                                                            shape = RoundedCornerShape(12.dp),
                                                            color = if (selected) Color(0xFF2563EB) else Color.White,
                                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Color(0xFF1D4ED8) else Color(0xFFCBD5E1))
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                Text("📱", fontSize = 14.sp)
                                                                Text(acc.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Color(0xFF0F172A))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        "FRIEND" -> {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text("Friend's Name:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                                OutlinedTextField(
                                                    value = friendName,
                                                    onValueChange = { friendName = it },
                                                    placeholder = { Text("Enter friend's name (e.g. Rahul, Priya)...") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true,
                                                    textStyle = TextStyle(fontSize = 13.sp)
                                                )
                                                if (recentFriends.isNotEmpty()) {
                                                    Text("Recent Friends:", fontSize = 10.sp, color = Color(0xFF64748B))
                                                    Row(
                                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        recentFriends.forEach { name ->
                                                            FilterChip(
                                                                selected = friendName.equals(name, ignoreCase = true),
                                                                onClick = { friendName = name },
                                                                label = { Text("👤 $name", fontSize = 11.sp) }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        "OTHER" -> {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text("Transfer Description / Name:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                                OutlinedTextField(
                                                    value = otherDescription,
                                                    onValueChange = { otherDescription = it },
                                                    placeholder = { Text("e.g. Security Deposit, Vendor Advance...") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true,
                                                    textStyle = TextStyle(fontSize = 13.sp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Expense ON/OFF Toggle (Visible only in Expense mode)
                        if (txType == "EXPENSE") {
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isExpense) Color(0xFFFEF2F2) else Color(0xFFF1F5F9)
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isExpense) Color(0xFFFECACA) else Color(0xFFE2E8F0)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "Expense",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isExpense) Color(0xFFB91C1C) else Color(0xFF334155)
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isExpense) Color(0xFFDC2626) else Color(0xFF64748B)
                                            ) {
                                                Text(
                                                    text = if (isExpense) "ON" else "OFF",
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (isExpense)
                                                "Included in expense totals, categories & budgets"
                                            else
                                                "Excluded from expense totals (balance impact preserved)",
                                            fontSize = 11.sp,
                                            color = if (isExpense) Color(0xFFDC2626) else Color(0xFF64748B),
                                            lineHeight = 15.sp
                                        )
                                    }
                                    Switch(
                                        checked = isExpense,
                                        onCheckedChange = { isExpense = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFFDC2626),
                                            uncheckedThumbColor = Color.White,
                                            uncheckedTrackColor = Color(0xFF94A3B8)
                                        )
                                    )
                                }
                            }
                        }

                        // Amount Input Card
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (txType == "EXPENSE") "ENTER EXPENSE AMOUNT" else "ENTER INCOME AMOUNT",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF94A3B8)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(text = "₹", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    OutlinedTextField(
                                        value = amountStr,
                                        onValueChange = { amountStr = it },
                                        placeholder = { Text("0", fontSize = 28.sp, color = Color(0xFFCBD5E1)) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color.Transparent,
                                            unfocusedBorderColor = Color.Transparent,
                                            cursorColor = if (txType == "EXPENSE") Color(0xFFDC2626) else Color(0xFF059669)
                                        ),
                                        textStyle = LocalTextStyle.current.copy(
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF0F172A),
                                            textAlign = TextAlign.Center
                                        ),
                                        modifier = Modifier.width(180.dp)
                                    )
                                }
                            }
                        }

                        // 2. Combined Date & Time Field
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openDateTimePicker() }
                        ) {
                            OutlinedTextField(
                                value = if (timeStr.isNotEmpty()) "$dateStr • $timeStr" else dateStr,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Date & Time") },
                                trailingIcon = {
                                    IconButton(onClick = { openDateTimePicker() }) {
                                        Icon(
                                            imageVector = Icons.Default.DateRange,
                                            contentDescription = "Pick Date and Time",
                                            tint = if (txType == "EXPENSE") Color(0xFFDC2626) else Color(0xFF059669)
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = false,
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = Color(0xFF0F172A),
                                    disabledBorderColor = Color(0xFFCBD5E1),
                                    disabledLabelColor = Color(0xFF64748B)
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable { openDateTimePicker() }
                            )
                        }

                        // Merchant / Paid to / Source
                        Column {
                            Text(
                                text = if (txType == "EXPENSE") "Paid to / Merchant" else "Income Source / From",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF334155)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = merchant,
                                onValueChange = { merchant = it },
                                placeholder = { Text(if (txType == "EXPENSE") "e.g. Swiggy, DMart, Amazon" else "e.g. Salary, Client, Interest") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 12.sp)
                            )
                        }

                        // Account
                        Column {
                            Text(text = "Account / Payment Method", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                            Spacer(modifier = Modifier.height(4.dp))
                            var showSelectorDialog by remember { mutableStateOf(false) }
                            val selectedAcc = accounts.find { it.id == selectedAccountId }

                            OutlinedButton(
                                onClick = { showSelectorDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (selectedAcc != null) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            BankLogo(
                                                bankName = selectedAcc.bankName.ifEmpty { selectedAcc.name },
                                                size = 24.dp,
                                                shapeRadius = 6.dp
                                            )
                                            Column {
                                                Text(
                                                    text = selectedAcc.name,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF0F172A)
                                                )
                                                if (selectedAcc.last4Digits.isNotEmpty()) {
                                                    Text(
                                                        text = "•••• ${selectedAcc.last4Digits}",
                                                        fontSize = 10.sp,
                                                        color = Color(0xFF64748B)
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Text(text = "Select Account", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                    }
                                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF64748B))
                                }
                            }

                            if (showSelectorDialog) {
                                GroupedAccountSelectorDialog(
                                    accounts = accounts,
                                    cards = cards,
                                    onDismiss = { showSelectorDialog = false },
                                    onSelected = { accId, paymentType ->
                                        selectedAccountId = accId
                                    }
                                )
                            }
                        }

                        // Category Field with Add+ inside
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Category *", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                TextButton(
                                    onClick = { showCatModal = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(text = "Add +", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            if (filteredCats.isEmpty()) {
                                Text(
                                    text = if (txType == "INCOME") "No income categories. Tap Add + to create one." else "No expense categories.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(filteredCats, key = { it.id }) { cat ->
                                        val isSelected = cat.id == selectedCategoryId
                                        Surface(
                                            onClick = {
                                                selectedCategoryId = cat.id
                                                selectedSubcategoryId = ""
                                            },
                                            shape = RoundedCornerShape(14.dp),
                                            color = if (isSelected) (if (txType == "EXPENSE") Color(0xFFDC2626) else Color(0xFF059669)) else Color(0xFFF1F5F9),
                                            modifier = Modifier.height(38.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(text = cat.icon, fontSize = 14.sp)
                                                Text(
                                                    text = getBilingualName(cat.name, cat.nameHindi),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) Color.White else Color(0xFF334155)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Subcategory Field with Subcategory + Add inside (Expense only)
                        if (txType == "EXPENSE") {
                            val relevantSubs = subcategories.filter { it.categoryId == selectedCategoryId }
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "Subcategory", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                    TextButton(
                                        onClick = { showSubcatModal = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(text = "Subcategory + Add", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))

                                if (relevantSubs.isEmpty()) {
                                    Text(text = "No subcategories. Tap Subcategory + Add.", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                } else {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(relevantSubs, key = { it.id }) { sub ->
                                            val isSelected = sub.id == selectedSubcategoryId
                                            Surface(
                                                onClick = { selectedSubcategoryId = if (isSelected) "" else sub.id },
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (isSelected) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(text = sub.icon, fontSize = 12.sp)
                                                    Text(
                                                        text = getBilingualName(sub.name, sub.nameHindi),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) Color.White else Color(0xFF334155)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Note
                        Column {
                            Text(text = "Note (Optional)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = note,
                                onValueChange = { note = it },
                                placeholder = { Text("Add details or tags...") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 12.sp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Actions Footer
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val amt = amountStr.toDoubleOrNull()
                                    if (amt == null || amt <= 0.0) return@OutlinedButton
                                    if (txType == "TRANSFER") {
                                        val (finalMerchant, counterpartyId) = when (transferDestType) {
                                            "FRIEND" -> {
                                                if (friendName.isBlank()) return@OutlinedButton
                                                "Friend: ${friendName.trim()}" to null
                                            }
                                            "OTHER" -> {
                                                if (otherDescription.isBlank()) return@OutlinedButton
                                                "Transfer: ${otherDescription.trim()}" to null
                                            }
                                            else -> {
                                                val destAcc = accounts.find { it.id == targetAccountId }
                                                if (destAcc == null) return@OutlinedButton
                                                "Transfer to ${destAcc.name}" to destAcc.id
                                            }
                                        }
                                        viewModel.addTransferTransaction(
                                            amount = amt,
                                            date = dateStr,
                                            time = timeStr,
                                            merchant = finalMerchant,
                                            accountId = selectedAccountId,
                                            counterpartyAccountId = counterpartyId,
                                            note = note.trim()
                                        )
                                    } else {
                                        val finalMerchant = merchant.ifEmpty { if (txType == "EXPENSE") "General" else "Income" }
                                        viewModel.addTransaction(
                                            type = txType,
                                            amount = amt,
                                            date = dateStr,
                                            time = timeStr,
                                            merchant = finalMerchant,
                                            categoryId = selectedCategoryId,
                                            subcategoryId = selectedSubcategoryId,
                                            accountId = selectedAccountId,
                                            paymentMethod = accounts.find { it.id == selectedAccountId }?.type ?: "Manual",
                                            note = note.trim(),
                                            isExpense = if (txType == "EXPENSE") isExpense else false
                                        )
                                    }
                                    amountStr = ""
                                    merchant = ""
                                    note = ""
                                    friendName = ""
                                    otherDescription = ""
                                    isExpense = true
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = when (txType) {
                                        "EXPENSE" -> Color(0xFFDC2626)
                                        "INCOME" -> Color(0xFF059669)
                                        else -> Color(0xFF2563EB)
                                    }
                                )
                            ) {
                                Text(text = "Save & Add Another", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    val amt = amountStr.toDoubleOrNull()
                                    if (amt == null || amt <= 0.0) return@Button
                                    if (txType == "TRANSFER") {
                                        val (finalMerchant, counterpartyId) = when (transferDestType) {
                                            "FRIEND" -> {
                                                if (friendName.isBlank()) return@Button
                                                "Friend: ${friendName.trim()}" to null
                                            }
                                            "OTHER" -> {
                                                if (otherDescription.isBlank()) return@Button
                                                "Transfer: ${otherDescription.trim()}" to null
                                            }
                                            else -> {
                                                val destAcc = accounts.find { it.id == targetAccountId }
                                                if (destAcc == null) return@Button
                                                "Transfer to ${destAcc.name}" to destAcc.id
                                            }
                                        }
                                        viewModel.addTransferTransaction(
                                            amount = amt,
                                            date = dateStr,
                                            time = timeStr,
                                            merchant = finalMerchant,
                                            accountId = selectedAccountId,
                                            counterpartyAccountId = counterpartyId,
                                            note = note.trim()
                                        )
                                    } else {
                                        val finalMerchant = merchant.ifEmpty { if (txType == "EXPENSE") "General" else "Income" }
                                        viewModel.addTransaction(
                                            type = txType,
                                            amount = amt,
                                            date = dateStr,
                                            time = timeStr,
                                            merchant = finalMerchant,
                                            categoryId = selectedCategoryId,
                                            subcategoryId = selectedSubcategoryId,
                                            accountId = selectedAccountId,
                                            paymentMethod = accounts.find { it.id == selectedAccountId }?.type ?: "Manual",
                                            note = note.trim(),
                                            isExpense = if (txType == "EXPENSE") isExpense else false
                                        )
                                    }
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when (txType) {
                                        "EXPENSE" -> Color(0xFFDC2626)
                                        "INCOME" -> Color(0xFF059669)
                                        else -> Color(0xFF2563EB)
                                    }
                                )
                            ) {
                                Text(
                                    text = if (txType == "EXPENSE") "Save Expense" else if (txType == "INCOME") "Save Income" else "Save Transfer",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    // Inline Create Category Dialog
    if (showCatModal) {
        AlertDialog(
            onDismissRequest = { showCatModal = false },
            title = { Text(text = "Create New Category", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newCatName,
                        onValueChange = { newCatName = it },
                        label = { Text("Category Name (English)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newCatNameHindi,
                        onValueChange = { newCatNameHindi = it },
                        label = { Text("कैटेगरी का नाम (हिंदी)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(text = "Select Icon", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.height(110.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(emojis) { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (newCatIcon == emoji) Color(0xFFD1FAE5) else Color(0xFFF1F5F9))
                                    .clickable { newCatIcon = emoji },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = emoji, fontSize = 16.sp)
                            }
                        }
                    }
                    Text(text = "Select Colour", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(colors) { hex ->
                            val c = Color(android.graphics.Color.parseColor(hex))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .clickable { newCatColor = hex }
                                    .border(if (newCatColor == hex) 2.dp else 0.dp, Color.Black, CircleShape)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCatName.isNotBlank()) {
                            val newId = viewModel.addCategory(newCatName.trim(), newCatNameHindi.trim(), newCatIcon, newCatColor, txType == "INCOME")
                            selectedCategoryId = newId
                            selectedSubcategoryId = ""
                            newCatName = ""
                            newCatNameHindi = ""
                            showCatModal = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (txType == "EXPENSE") Color(0xFFDC2626) else Color(0xFF059669)
                    )
                ) {
                    Text("Save Category")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCatModal = false }) { Text("Cancel") }
            }
        )
    }

    // Inline Create Subcategory Dialog
    if (showSubcatModal) {
        AlertDialog(
            onDismissRequest = { showSubcatModal = false },
            title = { Text(text = "Create New Subcategory", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newSubcatName,
                        onValueChange = { newSubcatName = it },
                        label = { Text("Subcategory Name (English)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newSubcatNameHindi,
                        onValueChange = { newSubcatNameHindi = it },
                        label = { Text("सबकैटेगरी का नाम (हिंदी)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(text = "Select Icon", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.height(100.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(emojis) { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (newSubcatIcon == emoji) Color(0xFFD1FAE5) else Color(0xFFF1F5F9))
                                    .clickable { newSubcatIcon = emoji },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = emoji, fontSize = 16.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newSubcatName.isNotBlank() && selectedCategoryId.isNotBlank()) {
                            val newSubId = viewModel.addSubcategory(selectedCategoryId, newSubcatName.trim(), newSubcatNameHindi.trim(), newSubcatIcon, newCatColor)
                            selectedSubcategoryId = newSubId
                            newSubcatName = ""
                            newSubcatNameHindi = ""
                            showSubcatModal = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (txType == "EXPENSE") Color(0xFFDC2626) else Color(0xFF059669)
                    )
                ) {
                    Text("Save Subcategory")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubcatModal = false }) { Text("Cancel") }
            }
        )
    }
}
