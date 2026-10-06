package com.example.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.TransactionEntity
import com.example.viewmodel.KharchaViewModel
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionDialog(
    tx: TransactionEntity,
    viewModel: KharchaViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val categories by viewModel.categories.collectAsState()
    val subcategories by viewModel.subcategories.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val cards by viewModel.cards.collectAsState()

    var type by remember {
        mutableStateOf(
            if (tx.isInternalTransfer || tx.type == "INTERNAL_TRANSFER" || tx.transactionType == "INTERNAL_TRANSFER") "INTERNAL_TRANSFER"
            else tx.type
        )
    }
    var isExpense by remember { mutableStateOf(tx.isExpense) }
    var amountText by remember { mutableStateOf(if (tx.amount % 1 == 0.0) tx.amount.toLong().toString() else tx.amount.toString()) }
    var merchant by remember { mutableStateOf(tx.merchant) }
    var selectedCategoryId by remember {
        mutableStateOf(
            if (type == "INTERNAL_TRANSFER") "cat-transfer" else tx.categoryId
        )
    }
    var selectedSubcategoryId by remember {
        mutableStateOf(
            if (type == "INTERNAL_TRANSFER") {
                if (tx.subcategoryId.startsWith("sub-tf-")) tx.subcategoryId else ""
            } else tx.subcategoryId
        )
    }
    var selectedAccountId by remember { mutableStateOf(tx.accountId) }
    var paymentMethod by remember { mutableStateOf(tx.paymentMethod) }
    var date by remember { mutableStateOf(tx.date) }
    var time by remember { mutableStateOf(tx.time) }

    // Check if original note is an auto-generated import text
    val isAutoImportNote = remember(tx.note) {
        val trimmed = tx.note.trim()
        trimmed.startsWith("SMS from", ignoreCase = true) ||
        trimmed.startsWith("Notification from", ignoreCase = true) ||
        trimmed.startsWith("Email:", ignoreCase = true) ||
        trimmed.startsWith("Imported from", ignoreCase = true)
    }

    // "Note / Remarks" should remain empty for existing imported transactions unless the user explicitly entered a note
    var note by remember { mutableStateOf(if (isAutoImportNote) "" else tx.note) }

    var catDropdownExpanded by remember { mutableStateOf(false) }
    var subcatDropdownExpanded by remember { mutableStateOf(false) }
    var showSelectorDialog by remember { mutableStateOf(false) }

    var showCreateCatDialog by remember { mutableStateOf(false) }
    var showCreateSubcatDialog by remember { mutableStateOf(false) }

    var newCatName by remember { mutableStateOf("") }
    var newCatNameHindi by remember { mutableStateOf("") }
    var newSubcatName by remember { mutableStateOf("") }
    var newSubcatNameHindi by remember { mutableStateOf("") }

    androidx.activity.compose.BackHandler(enabled = showCreateSubcatDialog) {
        showCreateSubcatDialog = false
    }
    androidx.activity.compose.BackHandler(enabled = showCreateCatDialog) {
        showCreateCatDialog = false
    }
    androidx.activity.compose.BackHandler(enabled = showSelectorDialog) {
        showSelectorDialog = false
    }
    androidx.activity.compose.BackHandler(enabled = subcatDropdownExpanded) {
        subcatDropdownExpanded = false
    }
    androidx.activity.compose.BackHandler(enabled = catDropdownExpanded) {
        catDropdownExpanded = false
    }
    androidx.activity.compose.BackHandler(enabled = !showCreateSubcatDialog && !showCreateCatDialog && !showSelectorDialog && !subcatDropdownExpanded && !catDropdownExpanded) {
        onDismiss()
    }

    val filteredCategories = categories.filter { 
        if (type == "INCOME") it.isIncome && it.id != "cat-transfer"
        else if (type == "INTERNAL_TRANSFER") it.id == "cat-transfer"
        else !it.isIncome && it.id != "cat-transfer"
    }
    val filteredSubcategories = subcategories.filter { it.categoryId == selectedCategoryId }

    // Safe Source & Metadata extraction (strictly filtering out OTP, PIN, Passwords, CVV, secrets)
    fun sanitizeMetadata(text: String): String {
        val lower = text.lowercase()
        if (lower.contains("otp") || lower.contains("password") || lower.contains("passcode") ||
            lower.contains("secret") || lower.contains("cvv") || lower.contains("atm pin") ||
            lower.contains("login pin") || lower.contains("mpin")) {
            return ""
        }
        return text.trim()
    }

    val safeSourceLabel = when (tx.source.uppercase()) {
        "NOTIFICATION" -> "Imported from Notification"
        "SMS" -> "Imported from SMS"
        "EMAIL", "GMAIL" -> "Imported from Gmail"
        "MANUAL" -> if (tx.transactionReference.isNotEmpty()) "Imported" else "Manual Entry"
        else -> "Imported from ${tx.source.lowercase().replaceFirstChar { it.uppercase() }}"
    }

    val safeRef = sanitizeMetadata(tx.transactionReference.ifEmpty { tx.originalReference })
    val safeSenderOrDetails = if (isAutoImportNote) {
        val safeCleaned = tx.note.replace(Regex("(?i)(otp|password|pin|cvv|secret)[^,\\s]*"), "").trim()
        sanitizeMetadata(safeCleaned)
    } else ""

    // Date & Time Picker Dialogs Chain
    fun openDateTimePicker() {
        val cal = Calendar.getInstance()
        val dateParts = date.split("-")
        val initialYear = dateParts.getOrNull(0)?.toIntOrNull() ?: cal.get(Calendar.YEAR)
        val initialMonth = (dateParts.getOrNull(1)?.toIntOrNull()?.minus(1)) ?: cal.get(Calendar.MONTH)
        val initialDay = dateParts.getOrNull(2)?.toIntOrNull() ?: cal.get(Calendar.DAY_OF_MONTH)

        val timeParts = time.split(":")
        val initialHour = timeParts.getOrNull(0)?.toIntOrNull() ?: cal.get(Calendar.HOUR_OF_DAY)
        val initialMinute = timeParts.getOrNull(1)?.toIntOrNull() ?: cal.get(Calendar.MINUTE)

        val timePickerDialog = TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                time = String.format(Locale.ROOT, "%02d:%02d", hourOfDay, minute)
            },
            initialHour,
            initialMinute,
            true
        )

        val datePickerDialog = DatePickerDialog(
            context,
            { _, year, monthOfYear, dayOfMonth ->
                date = String.format(Locale.ROOT, "%04d-%02d-%02d", year, monthOfYear + 1, dayOfMonth)
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .wrapContentHeight(Alignment.Bottom)
        ) {
            Card(
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Edit Transaction", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color(0xFF64748B))
                        }
                    }

                    // Type Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = type == "EXPENSE",
                            onClick = {
                                type = "EXPENSE"
                                selectedCategoryId = categories.firstOrNull { !it.isIncome && it.id != "cat-transfer" }?.id ?: ""
                                selectedSubcategoryId = ""
                            },
                            label = { Text("Expense", fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFDC2626),
                                selectedLabelColor = Color.White
                            )
                        )
                        FilterChip(
                            selected = type == "INCOME",
                            onClick = {
                                type = "INCOME"
                                selectedCategoryId = categories.firstOrNull { it.isIncome && it.id != "cat-transfer" }?.id ?: ""
                                selectedSubcategoryId = ""
                            },
                            label = { Text("Income", fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF059669),
                                selectedLabelColor = Color.White
                            )
                        )
                        FilterChip(
                            selected = type == "INTERNAL_TRANSFER",
                            onClick = {
                                type = "INTERNAL_TRANSFER"
                                selectedCategoryId = "cat-transfer"
                                selectedSubcategoryId = ""
                            },
                            label = { Text("Transfer", fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF2563EB),
                                selectedLabelColor = Color.White
                            )
                        )
                    }

                    // 1. EXPENSE ON/OFF TOGGLE (Visible only in Expense mode)
                    if (type == "EXPENSE") {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isExpense) Color(0xFFFEF2F2) else Color(0xFFF1F5F9)
                            ),
                            border = BorderStroke(1.dp, if (isExpense) Color(0xFFFECACA) else Color(0xFFE2E8F0)),
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

                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (₹)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = merchant,
                        onValueChange = { merchant = it },
                        label = { Text("Merchant / Payee") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Category Selector
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val currentCat = categories.find { it.id == selectedCategoryId }
                        OutlinedTextField(
                            value = currentCat?.let { "${it.icon} ${it.name}" } ?: "Select Category",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category") },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { showCreateCatDialog = true }, modifier = Modifier.size(28.dp)) {
                                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add Category", tint = Color(0xFF059669), modifier = Modifier.size(18.dp))
                                    }
                                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.clickable { catDropdownExpanded = true })
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { catDropdownExpanded = true },
                            enabled = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = Color(0xFF0F172A),
                                disabledBorderColor = Color(0xFFCBD5E1),
                                disabledLabelColor = Color(0xFF64748B)
                            )
                        )

                        DropdownMenu(
                            expanded = catDropdownExpanded,
                            onDismissRequest = { catDropdownExpanded = false }
                        ) {
                            filteredCategories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text("${cat.icon} ${getBilingualName(cat.name, cat.nameHindi)}") },
                                    onClick = {
                                        selectedCategoryId = cat.id
                                        selectedSubcategoryId = ""
                                        catDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Subcategory Selector
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val currentSubcat = subcategories.find { it.id == selectedSubcategoryId }
                        OutlinedTextField(
                            value = currentSubcat?.let { "${it.icon} ${it.name}" } ?: "Select Subcategory (Optional)",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Subcategory") },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { showCreateSubcatDialog = true }, modifier = Modifier.size(28.dp)) {
                                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add Subcategory", tint = Color(0xFF059669), modifier = Modifier.size(18.dp))
                                    }
                                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.clickable { subcatDropdownExpanded = true })
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { subcatDropdownExpanded = true },
                            enabled = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = Color(0xFF0F172A),
                                disabledBorderColor = Color(0xFFCBD5E1),
                                disabledLabelColor = Color(0xFF64748B)
                            )
                        )

                        DropdownMenu(
                            expanded = subcatDropdownExpanded,
                            onDismissRequest = { subcatDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("None") },
                                onClick = {
                                    selectedSubcategoryId = ""
                                    subcatDropdownExpanded = false
                                }
                            )
                            filteredSubcategories.forEach { sub ->
                                DropdownMenuItem(
                                    text = { Text("${sub.icon} ${getBilingualName(sub.name, sub.nameHindi)}") },
                                    onClick = {
                                        selectedSubcategoryId = sub.id
                                        subcatDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Account Selector
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val currentAcc = accounts.find { it.id == selectedAccountId }
                        OutlinedTextField(
                            value = currentAcc?.let { "${it.name} ${if (it.last4Digits.isNotEmpty()) "• " + it.last4Digits else ""}" } ?: "Select Account",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Account") },
                            trailingIcon = {
                                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.clickable { showSelectorDialog = true })
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showSelectorDialog = true },
                            enabled = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = Color(0xFF0F172A),
                                disabledBorderColor = Color(0xFFCBD5E1),
                                disabledLabelColor = Color(0xFF64748B)
                            )
                        )

                        if (showSelectorDialog) {
                            GroupedAccountSelectorDialog(
                                accounts = accounts,
                                cards = cards,
                                onDismiss = { showSelectorDialog = false },
                                onSelected = { accId, paymentType ->
                                    selectedAccountId = accId
                                    paymentMethod = paymentType
                                }
                            )
                        }
                    }

                    // 3. COMBINED DATE & TIME FIELD
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openDateTimePicker() }
                    ) {
                        OutlinedTextField(
                            value = if (time.isNotEmpty()) "$date • $time" else date,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Date & Time") },
                            trailingIcon = {
                                IconButton(onClick = { openDateTimePicker() }) {
                                    Icon(
                                        imageVector = Icons.Default.DateRange,
                                        contentDescription = "Pick Date and Time",
                                        tint = Color(0xFF059669)
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
                    }

                    // 2. NOTE / REMARKS & OTHER INFORMATION
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note / Remarks (Optional)") },
                        placeholder = { Text("Add personal note or memo...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3
                    )

                    // OTHER INFORMATION BOX
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Color(0xFF64748B),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Other Information",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF334155)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Source", fontSize = 11.sp, color = Color(0xFF64748B))
                                Text(text = safeSourceLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            }

                            if (safeRef.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Reference / UTR", fontSize = 11.sp, color = Color(0xFF64748B))
                                    Text(
                                        text = safeRef.take(30),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF0F172A)
                                    )
                                }
                            }

                            if (safeSenderOrDetails.isNotEmpty() && safeSenderOrDetails != safeSourceLabel) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Import Details", fontSize = 11.sp, color = Color(0xFF64748B))
                                    Text(
                                        text = safeSenderOrDetails.take(35),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF0F172A)
                                    )
                                }
                            }

                            if (paymentMethod.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Payment Mode", fontSize = 11.sp, color = Color(0xFF64748B))
                                    Text(text = paymentMethod, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF0F172A))
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val parsedAmount = amountText.toDoubleOrNull() ?: tx.amount
                            viewModel.updateTransaction(
                                id = tx.id,
                                type = type,
                                amount = parsedAmount,
                                date = date.trim(),
                                time = time.trim(),
                                merchant = merchant.trim(),
                                categoryId = selectedCategoryId,
                                subcategoryId = selectedSubcategoryId,
                                accountId = selectedAccountId,
                                paymentMethod = paymentMethod,
                                note = note.trim(),
                                isExpense = if (type == "EXPENSE") isExpense else false
                            )
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text(text = "Save Changes", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showCreateCatDialog) {
        AlertDialog(
            onDismissRequest = { showCreateCatDialog = false },
            title = { Text("Add Category", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newCatName,
                        onValueChange = { newCatName = it },
                        label = { Text("Category Name (English)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newCatNameHindi,
                        onValueChange = { newCatNameHindi = it },
                        label = { Text("कैटेगरी का नाम (हिंदी)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCatName.isNotBlank()) {
                            val newId = viewModel.addCategory(newCatName.trim(), newCatNameHindi.trim(), "📁", "#10B981", type == "INCOME")
                            selectedCategoryId = newId
                            newCatName = ""
                            newCatNameHindi = ""
                            showCreateCatDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateCatDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showCreateSubcatDialog) {
        AlertDialog(
            onDismissRequest = { showCreateSubcatDialog = false },
            title = { Text("Add Subcategory", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newSubcatName,
                        onValueChange = { newSubcatName = it },
                        label = { Text("Subcategory Name (English)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newSubcatNameHindi,
                        onValueChange = { newSubcatNameHindi = it },
                        label = { Text("सबकैटेगरी का नाम (हिंदी)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newSubcatName.isNotBlank() && selectedCategoryId.isNotEmpty()) {
                            val newSubId = viewModel.addSubcategory(selectedCategoryId, newSubcatName.trim(), newSubcatNameHindi.trim(), "📌", "#059669")
                            selectedSubcategoryId = newSubId
                            newSubcatName = ""
                            newSubcatNameHindi = ""
                            showCreateSubcatDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateSubcatDialog = false }) { Text("Cancel") }
            }
        )
    }
}
