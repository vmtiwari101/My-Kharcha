package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.utils.BankAccountColorResolver
import com.example.utils.CreditCardColorResolver
import com.example.utils.CreditCardReminderManager
import com.example.utils.TransactionIdentityResolver
import com.example.viewmodel.KharchaViewModel
import java.text.NumberFormat
import java.util.Locale

@Composable
fun CreditCardPaymentReminderSection(
    reminderEnabled: Boolean,
    onReminderEnabledChange: (Boolean) -> Unit,
    selectedOffsets: Set<Int>,
    onToggleOffset: (Int) -> Unit,
    dueDate: Int
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Payment Reminder",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = if (dueDate in 1..31) "Payment Due Date: ${dueDate}th of every month" else "Set Payment Due Date to enable notifications",
                        fontSize = 10.sp,
                        color = Color(0xFF64748B)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (reminderEnabled) "ON" else "OFF",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (reminderEnabled) Color(0xFF059669) else Color(0xFF64748B)
                    )
                    Switch(
                        checked = reminderEnabled,
                        onCheckedChange = onReminderEnabledChange
                    )
                }
            }

            if (reminderEnabled) {
                Text(
                    text = "Remind me before due date:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF475569)
                )

                val offsetOptions = listOf(
                    7 to "7 days before",
                    3 to "3 days before",
                    1 to "1 day before",
                    0 to "Due date"
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    offsetOptions.forEach { (offset, label) ->
                        val isChecked = selectedOffsets.contains(offset)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onToggleOffset(offset) }
                                .padding(vertical = 2.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { onToggleOffset(offset) },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF059669))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                color = if (isChecked) Color(0xFF0F172A) else Color(0xFF64748B),
                                fontWeight = if (isChecked) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountsScreen(
    viewModel: KharchaViewModel
) {
    val accounts by viewModel.accounts.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val subcategories by viewModel.subcategories.collectAsState()
    val allSplits by viewModel.transactionSplits.collectAsState()
    val cards by viewModel.cards.collectAsState()

    var selectedAccountIdForHistory by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTypeFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("All") }
    var showEditDialog by remember { mutableStateOf<AccountEntity?>(null) }
    var showArchiveConfirm by remember { mutableStateOf<AccountEntity?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AccountEntity?>(null) }
    var showManageCardsDialog by remember { mutableStateOf(false) }
    var showEditCardDialog by remember { mutableStateOf<CardEntity?>(null) }
    var showAddAccountCustomDialog by remember { mutableStateOf(false) }
    var selectedTxDetail by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedTxToEdit by remember { mutableStateOf<TransactionEntity?>(null) }
    var showAllCreditCardsScreen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    val accountsListState = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }

    androidx.activity.compose.BackHandler(enabled = selectedTxToEdit != null) {
        selectedTxToEdit = null
    }
    androidx.activity.compose.BackHandler(enabled = selectedTxDetail != null && selectedTxToEdit == null) {
        selectedTxDetail = null
    }
    androidx.activity.compose.BackHandler(enabled = showEditCardDialog != null) {
        showEditCardDialog = null
    }
    androidx.activity.compose.BackHandler(enabled = showManageCardsDialog) {
        showManageCardsDialog = false
    }
    androidx.activity.compose.BackHandler(enabled = showAddAccountCustomDialog) {
        showAddAccountCustomDialog = false
    }
    androidx.activity.compose.BackHandler(enabled = showDeleteConfirm != null) {
        showDeleteConfirm = null
    }
    androidx.activity.compose.BackHandler(enabled = showArchiveConfirm != null) {
        showArchiveConfirm = null
    }
    androidx.activity.compose.BackHandler(enabled = showEditDialog != null) {
        showEditDialog = null
    }
    androidx.activity.compose.BackHandler(enabled = selectedAccountIdForHistory != null) {
        selectedAccountIdForHistory = null
    }
    androidx.activity.compose.BackHandler(enabled = showAllCreditCardsScreen) {
        showAllCreditCardsScreen = false
    }

    val formatINR: (Double) -> String = { amt ->
        "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(amt)
    }

    val activeAccounts = accounts.filter { acc ->
        if (!acc.isActive) return@filter false
        val isAutoCreated = acc.id.startsWith("acc-auto-") || acc.id.startsWith("acc-cc-") ||
                com.example.utils.TransactionIngestionEngine.isAutoCreatedCreditCardAccount(acc)
        if (isAutoCreated) {
            transactions.any { tx ->
                tx.accountId == acc.id || tx.counterpartyAccountId == acc.id ||
                (acc.last4Digits.length == 4 && tx.last4Digits == acc.last4Digits &&
                 com.example.utils.TransactionIngestionEngine.isBankNameMatch(tx.merchant, acc.bankName.ifEmpty { acc.name }))
            }
        } else {
            true
        }
    }
    val archivedAccounts = accounts.filter { !it.isActive }

    if (showAllCreditCardsScreen) {
        AllCreditCardsScreen(
            viewModel = viewModel,
            onBack = { showAllCreditCardsScreen = false },
            onEditCard = { acc -> showEditDialog = acc },
            onSelectCard = { card ->
                showAllCreditCardsScreen = false
                selectedAccountIdForHistory = card.accountId
            }
        )
        if (showEditDialog != null) {
            EditAccountDialog(
                acc = showEditDialog!!,
                viewModel = viewModel,
                onDismiss = { showEditDialog = null }
            )
        }
        return
    }

    // Account History Drill-Down Active View
    if (selectedAccountIdForHistory != null) {
        val accId = selectedAccountIdForHistory!!
        val acc = accounts.find { it.id == accId }
        val accName = acc?.name ?: "Account"
        val accType = acc?.type ?: "Account"
        val accLast4 = acc?.last4Digits ?: ""
        val accColor = try {
            Color(android.graphics.Color.parseColor(acc?.colour ?: "#0284C7"))
        } catch (e: Exception) {
            Color(0xFF0284C7)
        }

        AccountHistoryView(
            account = acc,
            accId = accId,
            accName = accName,
            accType = accType,
            accLast4 = accLast4,
            accColor = accColor,
            transactions = transactions,
            categories = categories,
            subcategories = subcategories,
            accounts = accounts,
            allSplits = allSplits,
            formatINR = formatINR,
            onBack = { selectedAccountIdForHistory = null },
            onSelectTransaction = { tx -> selectedTxDetail = tx }
        )

        if (selectedTxDetail != null) {
            TransactionDetailDialog(
                tx = selectedTxDetail!!,
                viewModel = viewModel,
                onDismiss = { selectedTxDetail = null },
                onEdit = { tx ->
                    selectedTxDetail = null
                    selectedTxToEdit = tx
                }
            )
        }

        if (selectedTxToEdit != null) {
            EditTransactionDialog(
                tx = selectedTxToEdit!!,
                viewModel = viewModel,
                onDismiss = { selectedTxToEdit = null }
            )
        }
        return
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(text = "Delete this account?") },
            text = { Text(text = "This will permanently remove the account. Transaction history will also be affected according to the existing app deletion rules.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAccount(showDeleteConfirm!!.id)
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showArchiveConfirm != null) {
        AlertDialog(
            onDismissRequest = { showArchiveConfirm = null },
            title = { Text(text = "Archive this account?") },
            text = { Text(text = "This will archive the account. You can restore it later from settings if needed.") },
            confirmButton = {
                Button(
                    onClick = {
                        val acc = showArchiveConfirm!!
                        viewModel.updateAccount(
                            id = acc.id,
                            name = acc.name,
                            type = acc.type,
                            bankName = acc.bankName,
                            last4Digits = acc.last4Digits,
                            icon = acc.icon,
                            colour = acc.colour,
                            isActive = false,
                            isDefault = acc.isDefault,
                            isOwnedByMe = acc.isOwnedByMe,
                            creditLimit = acc.creditLimit,
                            outstandingAmount = acc.outstandingAmount,
                            billingDate = acc.billingDate,
                            dueDate = acc.dueDate,
                            initialBalance = acc.initialBalance
                        )
                        showArchiveConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                ) {
                    Text("Archive")
                }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Screen Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Accounts & Payment Sources",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "Bank accounts, cards, UPI & cash tracking",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { showAllCreditCardsScreen = true },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(imageVector = Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(text = "All Cards", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { showManageCardsDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(imageVector = Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(text = "Link", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { showAddAccountCustomDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(text = "Add", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Summary Net Flow Card
        val totalActiveExpense = transactions.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
        val totalActiveIncome = transactions.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
        val totalNetBalance: Double = activeAccounts.sumOf { acc ->
            val aTxs = transactions.filter { 
                it.accountId == acc.id || 
                it.counterpartyAccountId == acc.id || 
                (acc.last4Digits.isNotEmpty() && it.last4Digits == acc.last4Digits) 
            }
            calculateAccountBalance(acc.id, aTxs, acc.initialBalance)
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "ALL PAYMENT SOURCES", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF94A3B8))
                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        Text(
                            text = "${activeAccounts.size} Active Accounts",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            maxLines = 1
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "Total Balance", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        val balColor = if (totalNetBalance >= 0) Color(0xFF38BDF8) else Color(0xFFF87171)
                        val balSign = if (totalNetBalance >= 0) "" else "- "
                        Text(text = balSign + formatINR(Math.abs(totalNetBalance)), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = balColor)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "Total Received", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        Text(text = formatINR(totalActiveIncome), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF34D399))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = "Total Spent", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        Text(text = formatINR(totalActiveExpense), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFF87171))
                    }
                }
            }
        }

        val creditCardEntities = cards.filter { it.type.equals("Credit Card", ignoreCase = true) }
        val standaloneCreditCards = remember(creditCardEntities, activeAccounts) {
            creditCardEntities.filter { card ->
                activeAccounts.none { acc ->
                    acc.type.equals("Credit Card", ignoreCase = true) &&
                    (acc.id == card.accountId || (acc.last4Digits.isNotEmpty() && acc.last4Digits == card.last4Digits))
                }
            }.map { card ->
                val parentAcc = activeAccounts.find { it.id == card.accountId }
                val derivedBankName = when {
                    !parentAcc?.bankName.isNullOrBlank() -> parentAcc!!.bankName
                    !parentAcc?.name.isNullOrBlank() && parentAcc!!.name != card.name -> parentAcc!!.name
                    card.name.contains("HDFC", true) -> "HDFC Bank"
                    card.name.contains("SBI", true) -> "SBI"
                    card.name.contains("ICICI", true) -> "ICICI Bank"
                    card.name.contains("Axis", true) -> "Axis Bank"
                    card.name.contains("Kotak", true) -> "Kotak Bank"
                    card.name.contains("IDFC", true) -> "IDFC FIRST Bank"
                    card.name.contains("AU", true) -> "AU Small Finance Bank"
                    card.name.contains("RBL", true) -> "RBL Bank"
                    card.name.contains("YES", true) -> "YES Bank"
                    else -> card.name
                }
                AccountEntity(
                    id = card.accountId.ifEmpty { card.id },
                    name = card.name,
                    type = "Credit Card",
                    bankName = derivedBankName,
                    last4Digits = card.last4Digits,
                    creditLimit = card.creditLimit,
                    outstandingAmount = card.outstandingAmount,
                    billingDate = card.billingDate,
                    dueDate = card.dueDate,
                    colour = parentAcc?.colour.orEmpty()
                )
            }
        }

        val typeFilters = listOf(
            "All",
            "Bank Account",
            "Credit Card",
            "Debit Card",
            "UPI",
            "Cash"
        )

        // Type Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            typeFilters.forEach { filterName ->
                val isSelected = selectedTypeFilter == filterName
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedTypeFilter = filterName },
                    label = {
                        Text(
                            text = when (filterName) {
                                "All" -> "All (${activeAccounts.size})"
                                "Credit Card" -> {
                                    val count = activeAccounts.count { it.type.equals("Credit Card", ignoreCase = true) } + standaloneCreditCards.size
                                    "Credit Card ($count)"
                                }
                                else -> {
                                    val count = activeAccounts.count { it.type.equals(filterName, ignoreCase = true) }
                                    "$filterName ($count)"
                                }
                            },
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF059669),
                        selectedLabelColor = Color.White
                    )
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Filtered Accounts
        val displayedAccounts = remember(selectedTypeFilter, activeAccounts, standaloneCreditCards) {
            if (selectedTypeFilter == "All") {
                activeAccounts
            } else if (selectedTypeFilter == "Credit Card") {
                val accList = activeAccounts.filter { it.type.equals("Credit Card", ignoreCase = true) }.toMutableList()
                standaloneCreditCards.forEach { cardAcc ->
                    if (accList.none { it.id == cardAcc.id || (it.last4Digits.isNotEmpty() && it.last4Digits == cardAcc.last4Digits) }) {
                        accList.add(cardAcc)
                    }
                }
                accList
            } else {
                activeAccounts.filter { it.type.equals(selectedTypeFilter, ignoreCase = true) }
            }
        }

        // Bank Grouping Logic: Group accounts by institution/bankName or common bank prefix
        val groupedByBank = remember(displayedAccounts) {
            displayedAccounts.groupBy { acc ->
                when {
                    acc.bankName.isNotBlank() -> acc.bankName.trim()
                    acc.name.contains("HDFC", ignoreCase = true) -> "HDFC Bank"
                    acc.name.contains("ICICI", ignoreCase = true) -> "ICICI Bank"
                    acc.name.contains("SBI", ignoreCase = true) || acc.name.contains("State Bank", ignoreCase = true) -> "State Bank of India"
                    acc.name.contains("Axis", ignoreCase = true) -> "Axis Bank"
                    acc.name.contains("Kotak", ignoreCase = true) -> "Kotak Mahindra Bank"
                    acc.name.contains("IDFC", ignoreCase = true) -> "IDFC FIRST Bank"
                    acc.name.contains("AU", ignoreCase = true) -> "AU Small Finance Bank"
                    acc.name.contains("RBL", ignoreCase = true) -> "RBL Bank"
                    acc.name.contains("YES", ignoreCase = true) -> "YES Bank"
                    acc.name.contains("IndusInd", ignoreCase = true) -> "IndusInd Bank"
                    acc.name.contains("PNB", ignoreCase = true) -> "Punjab National Bank"
                    acc.name.contains("Canara", ignoreCase = true) -> "Canara Bank"
                    acc.name.contains("Bank of Baroda", ignoreCase = true) || acc.name.contains("BOB", ignoreCase = true) -> "Bank of Baroda"
                    acc.name.contains("Paytm", ignoreCase = true) -> "Paytm Payments Bank"
                    acc.name.contains("Cash", ignoreCase = true) -> "Cash & Physical"
                    acc.name.contains("UPI", ignoreCase = true) || acc.name.contains("Google Pay", ignoreCase = true) || acc.name.contains("PhonePe", ignoreCase = true) -> "UPI & Digital Wallets"
                    else -> "Independent Accounts"
                }
            }
        }

        LazyColumn(
            state = accountsListState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            if (displayedAccounts.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.AccountBalance, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(32.dp))
                            Text(text = "No accounts found for '$selectedTypeFilter'", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                            Text(text = "Tap '+ Add' to create a bank account, credit card, UPI ID, or cash in hand.", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                    }
                }
            } else {
                groupedByBank.forEach { (bankName, bankAccounts) ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (groupedByBank.size > 1 && bankAccounts.size > 1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = bankName.uppercase(Locale.US),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF475569)
                                    )
                                    Text(
                                        text = "${bankAccounts.size} instruments",
                                        fontSize = 10.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }

                            bankAccounts.forEach { acc ->
                                val isCreditCardAcc = acc.type.equals("Credit Card", ignoreCase = true)
                                val accTxs = transactions.filter { tx ->
                                    if (tx.accountId == acc.id || tx.counterpartyAccountId == acc.id) return@filter true
                                    if (isCreditCardAcc && cards.any { c -> c.accountId == acc.id && c.id == tx.cardId }) return@filter true
                                    if (acc.last4Digits.isNotEmpty() && tx.last4Digits == acc.last4Digits) {
                                        val text = "${tx.note} ${tx.paymentMethod} ${tx.merchant}".lowercase(Locale.ENGLISH)
                                        if (isCreditCardAcc) {
                                            return@filter text.contains("credit card") || text.contains("cc ending") || text.contains("card ending") || tx.paymentMethod.contains("card", true)
                                        } else {
                                            return@filter !text.contains("credit card") && !text.contains("cc ending")
                                        }
                                    }
                                    false
                                }
                                val totalExpense = accTxs.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
                                val totalIncome = accTxs.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
                                val accountBalance = calculateAccountBalance(acc.id, accTxs, acc.initialBalance)

                                val color = try {
                                    Color(android.graphics.Color.parseColor(acc.colour))
                                } catch (e: Exception) {
                                    Color(0xFF0284C7)
                                }

                                val bankAccountPalette = BankAccountColorResolver.resolvePalette(
                                    bankName = acc.bankName.ifEmpty { acc.name },
                                    accountName = acc.name
                                )

                                if (isCreditCardAcc) {
                                    val hasCardBalanceActivity = accTxs.any { tx ->
                                        com.example.utils.TransactionIdentityResolver.isCreditCardPurchase(
                                            tx,
                                            null,
                                            acc.id,
                                            acc.last4Digits
                                        ) || com.example.utils.TransactionIdentityResolver.isCreditCardPaymentOrRefund(
                                            tx,
                                            null,
                                            acc.id,
                                            acc.last4Digits
                                        )
                                    }
                                    val effectiveOutstanding = if (
                                        hasCardBalanceActivity || acc.initialBalance != 0.0
                                    ) {
                                        com.example.utils.TransactionIdentityResolver.creditCardOutstandingFromAnchor(
                                            acc.initialBalance,
                                            accTxs,
                                            null,
                                            acc.id,
                                            acc.last4Digits
                                        )
                                    } else {
                                        acc.outstandingAmount
                                    }

                                    val latestTx = accTxs.maxByOrNull { it.date + it.time }
                                    CreditCardAccountManagementCard(
                                        account = acc,
                                        effectiveOutstanding = effectiveOutstanding,
                                        latestTransaction = latestTx,
                                        onCardClick = { selectedAccountIdForHistory = acc.id },
                                        onSetDefault = { viewModel.setDefaultAccount(acc.id) },
                                        onEdit = { showEditDialog = acc },
                                        onArchive = { showArchiveConfirm = acc },
                                        onDelete = { showDeleteConfirm = acc },
                                        transactionCount = accTxs.size
                                    )
                                } else {
                                    val cardContainerColor = bankAccountPalette.backgroundColor
                                    val cardBorder = BorderStroke(1.dp, bankAccountPalette.borderColor)

                                    Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = cardContainerColor),
                                    border = cardBorder,
                                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedAccountIdForHistory = acc.id }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                BankLogo(
                                                    bankName = acc.bankName.ifEmpty { acc.name },
                                                    size = 40.dp,
                                                    shapeRadius = 12.dp
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = acc.name,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF0F172A),
                                                            modifier = Modifier.weight(1f, fill = false),
                                                            maxLines = 1,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                        )
                                                        if (acc.isDefault) {
                                                            Surface(
                                                                color = Color(0xFFECFDF5),
                                                                shape = RoundedCornerShape(6.dp)
                                                            ) {
                                                                Text(text = "Default", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                            }
                                                        }
                                                        if (acc.isOwnedByMe) {
                                                            Surface(
                                                                color = Color(0xFFEFF6FF),
                                                                shape = RoundedCornerShape(6.dp)
                                                            ) {
                                                                Text(text = "Self", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3B82F6), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                            }
                                                        }
                                                    }
                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        val displayType = if (acc.type.equals("Savings", ignoreCase = true) || acc.type.equals("Current", ignoreCase = true) || acc.type.equals("Bank Account", ignoreCase = true)) "Bank Account" else acc.type
                                                        Text(
                                                            text = displayType,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Color(0xFF64748B),
                                                            maxLines = 1
                                                        )
                                                        val displayLast4 = if (acc.last4Digits.length == 4) acc.last4Digits else if (acc.last4Digits.isNotBlank()) acc.last4Digits.takeLast(4) else "----"
                                                        Text(
                                                            text = "• •••• $displayLast4",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF0F172A),
                                                            maxLines = 1
                                                        )
                                                        if (acc.bankName.isNotEmpty() && acc.bankName != acc.name) {
                                                            Text(
                                                                text = "• ${acc.bankName}",
                                                                fontSize = 11.sp,
                                                                color = Color(0xFF94A3B8),
                                                                maxLines = 1,
                                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (!acc.isDefault) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(24.dp)
                                                            .background(Color.White.copy(alpha = 0.6f), CircleShape)
                                                            .clickable(onClick = { viewModel.setDefaultAccount(acc.id) }),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(imageVector = Icons.Default.StarBorder, contentDescription = "Set Default", tint = Color(0xFF64748B), modifier = Modifier.size(13.dp))
                                                    }
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .background(Color.White.copy(alpha = 0.6f), CircleShape)
                                                        .clickable(onClick = { showEditDialog = acc }),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF64748B), modifier = Modifier.size(13.dp))
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .background(Color.White.copy(alpha = 0.6f), CircleShape)
                                                        .clickable(onClick = { showArchiveConfirm = acc }),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(imageVector = Icons.Default.Archive, contentDescription = "Archive", tint = Color(0xFFEF4444), modifier = Modifier.size(13.dp))
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .background(Color.White.copy(alpha = 0.6f), CircleShape)
                                                        .clickable(onClick = { showDeleteConfirm = acc }),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFDC2626), modifier = Modifier.size(13.dp))
                                                }
                                            }
                                        }

                                        // Linked Cards Listing
                                        val accountCards = cards.filter { card ->
                                            card.accountId == acc.id && card.type != "ORPHAN_CARD" &&
                                            (!com.example.utils.TransactionIngestionEngine.isAutoCreatedCard(card, acc) || transactions.any { tx -> tx.cardId == card.id })
                                        }
                                        if (accountCards.isNotEmpty()) {
                                            HorizontalDivider(color = Color(0xFFF1F5F9))
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(text = "LINKED CARDS / INSTRUMENTS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                                accountCards.forEach { card ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                                Icon(imageVector = Icons.Default.CreditCard, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(13.dp))
                                                                Text(text = "${card.name} (${card.type} • ${card.last4Digits.ifEmpty { "XXXX" }})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                                            }
                                                            if (card.type == "Credit Card" && (card.creditLimit > 0 || card.outstandingAmount > 0 || card.billingDate > 0 || card.dueDate > 0)) {
                                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 18.dp, top = 2.dp)) {
                                                                    if (card.creditLimit > 0) {
                                                                        Text(text = "Limit: ${formatINR(card.creditLimit)}", fontSize = 10.sp, color = Color(0xFF64748B))
                                                                        val cardAvail = Math.max(0.0, card.creditLimit - card.outstandingAmount)
                                                                        Text(text = "Avail: ${formatINR(cardAvail)}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                                                                    }
                                                                    if (card.outstandingAmount > 0) {
                                                                        Text(text = "Due: ${formatINR(card.outstandingAmount)}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                                                                    }
                                                                    if (card.billingDate in 1..31) {
                                                                        Text(text = "Bill: ${card.billingDate}th", fontSize = 10.sp, color = Color(0xFF64748B))
                                                                    }
                                                                    if (card.dueDate in 1..31) {
                                                                        Text(text = "Due: ${card.dueDate}th", fontSize = 10.sp, color = Color(0xFF64748B))
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                            IconButton(
                                                                onClick = { showEditCardDialog = card },
                                                                modifier = Modifier.size(24.dp)
                                                            ) {
                                                                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Card", tint = Color(0xFF64748B), modifier = Modifier.size(13.dp))
                                                            }
                                                            IconButton(
                                                                onClick = { viewModel.deleteCard(card.id) },
                                                                modifier = Modifier.size(24.dp)
                                                            ) {
                                                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Mapping", tint = Color(0xFFEF4444), modifier = Modifier.size(13.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        HorizontalDivider(color = bankAccountPalette.borderColor.copy(alpha = 0.7f))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column {
                                                    Text(text = "Balance", fontSize = 10.sp, color = Color(0xFF64748B))
                                                    val balColor = if (accountBalance >= 0) Color(0xFF059669) else Color(0xFFDC2626)
                                                    val balPrefix = if (accountBalance >= 0) "" else "- "
                                                    Text(text = balPrefix + formatINR(Math.abs(accountBalance)), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = balColor)
                                                }
                                                Column {
                                                    Text(text = "Total Spent", fontSize = 10.sp, color = Color(0xFF64748B))
                                                    Text(text = formatINR(totalExpense), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                                                }
                                                Column {
                                                    Text(text = "Total Received", fontSize = 10.sp, color = Color(0xFF64748B))
                                                    Text(text = formatINR(totalIncome), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                                                }
                                            }

                                            Surface(
                                                color = Color.White.copy(alpha = 0.85f),
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(0.5.dp, bankAccountPalette.borderColor)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        text = "${accTxs.size} txs",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF475569)
                                                    )
                                                    Icon(
                                                        imageVector = Icons.Default.ChevronRight,
                                                        contentDescription = "View History",
                                                        tint = Color(0xFF94A3B8),
                                                        modifier = Modifier.size(12.dp)
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

            // Archived Accounts Section
            if (archivedAccounts.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "ARCHIVED ACCOUNTS (${archivedAccounts.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                }
                items(archivedAccounts, key = { it.id }) { acc ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
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
                                Text(text = acc.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                                Text(text = "${acc.type} ${if (acc.last4Digits.isNotEmpty()) "• " + acc.last4Digits else ""} (Archived)", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            }
                            OutlinedButton(
                                onClick = { viewModel.updateAccount(acc.id, acc.name, acc.type, acc.bankName, acc.last4Digits, acc.icon, acc.colour, isActive = true, isDefault = acc.isDefault, isOwnedByMe = acc.isOwnedByMe) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(text = "Restore", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // MANAGE CARDS DIALOG
    if (showManageCardsDialog) {
        val context = LocalContext.current
        var cardName by remember { mutableStateOf("") }
        var cardType by remember { mutableStateOf("Credit Card") }
        var cardDigits by remember { mutableStateOf("") }
        var creditLimitStr by remember { mutableStateOf("") }
        var outstandingStr by remember { mutableStateOf("") }
        var billingDateStr by remember { mutableStateOf("") }
        var dueDateStr by remember { mutableStateOf("") }
        var selectedCardAccount by remember { mutableStateOf<AccountEntity?>(activeAccounts.firstOrNull()) }
        var cardExpanded by remember { mutableStateOf(false) }
        var validationError by remember { mutableStateOf<String?>(null) }
        var reminderEnabled by remember { mutableStateOf(true) }
        var selectedOffsets by remember { mutableStateOf(CreditCardReminderManager.DEFAULT_OFFSETS) }

        AlertDialog(
            onDismissRequest = { showManageCardsDialog = false },
            title = { Text(text = "Link Card to Bank Account", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(text = "Link a Credit or Debit Card to a parent bank account to automatically map incoming transactions.", fontSize = 11.sp, color = Color(0xFF64748B))

                    OutlinedTextField(
                        value = cardName,
                        onValueChange = { cardName = it },
                        label = { Text("Card Nickname (e.g. Millennia Card) *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { cardType = "Credit Card" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (cardType == "Credit Card") Color(0xFFECFDF5) else Color.Transparent
                            )
                        ) {
                            Text("Credit Card", color = if (cardType == "Credit Card") Color(0xFF059669) else Color(0xFF475569), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { cardType = "Debit Card" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (cardType == "Debit Card") Color(0xFFECFDF5) else Color.Transparent
                            )
                        ) {
                            Text("Debit Card", color = if (cardType == "Debit Card") Color(0xFF059669) else Color(0xFF475569), fontSize = 11.sp)
                        }
                    }

                    OutlinedTextField(
                        value = cardDigits,
                        onValueChange = { if (it.length <= 4) cardDigits = it.filter { c -> c.isDigit() } },
                        label = { Text("Last 4 Digits of Card *") },
                        placeholder = { Text("e.g. 4092") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (cardType == "Credit Card") {
                        OutlinedTextField(
                            value = creditLimitStr,
                            onValueChange = { creditLimitStr = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Total Credit Limit (₹)") },
                            placeholder = { Text("e.g. 100000") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = outstandingStr,
                            onValueChange = { outstandingStr = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Current Outstanding Amount (₹)") },
                            placeholder = { Text("e.g. 15000") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = billingDateStr,
                                onValueChange = { if (it.length <= 2) billingDateStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Billing Date (1-31)") },
                                placeholder = { Text("e.g. 15") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = dueDateStr,
                                onValueChange = { if (it.length <= 2) dueDateStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Due Date (1-31)") },
                                placeholder = { Text("e.g. 5") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        CreditCardPaymentReminderSection(
                            reminderEnabled = reminderEnabled,
                            onReminderEnabledChange = { reminderEnabled = it },
                            selectedOffsets = selectedOffsets,
                            onToggleOffset = { offset ->
                                selectedOffsets = if (selectedOffsets.contains(offset)) {
                                    if (selectedOffsets.size > 1) selectedOffsets - offset else selectedOffsets
                                } else {
                                    selectedOffsets + offset
                                }
                            },
                            dueDate = dueDateStr.toIntOrNull() ?: 0
                        )
                    }

                    if (activeAccounts.isNotEmpty()) {
                        Text(text = "Parent Bank Account *", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569))
                        Box {
                            OutlinedButton(
                                onClick = { cardExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                val acc = selectedCardAccount
                                val accLabel = if (acc != null) {
                                    "${acc.name}${if (acc.last4Digits.isNotEmpty()) " • ${acc.last4Digits}" else ""}"
                                } else "Select Account"
                                Text(text = accLabel, fontSize = 12.sp, color = Color(0xFF0F172A))
                            }
                            DropdownMenu(
                                expanded = cardExpanded,
                                onDismissRequest = { cardExpanded = false }
                            ) {
                                activeAccounts.forEach { acc ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                BankLogo(
                                                    bankName = acc.bankName.ifEmpty { acc.name },
                                                    size = 20.dp,
                                                    shapeRadius = 6.dp
                                                )
                                                Column {
                                                    Text(acc.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                    Text("${acc.bankName.ifEmpty { acc.type }}${if (acc.last4Digits.isNotEmpty()) " • ${acc.last4Digits}" else ""}", fontSize = 10.sp, color = Color(0xFF64748B))
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedCardAccount = acc
                                            cardExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (validationError != null) {
                        Text(text = validationError!!, fontSize = 11.sp, color = Color(0xFFDC2626))
                    }

                    Button(
                        onClick = {
                            if (cardName.isBlank()) {
                                validationError = "Please enter card nickname"
                                return@Button
                            }
                            if (cardDigits.length != 4) {
                                validationError = "Last 4 digits must be exactly 4 digits"
                                return@Button
                            }
                            if (selectedCardAccount == null) {
                                validationError = "Please select parent bank account"
                                return@Button
                            }
                            val limit = creditLimitStr.toDoubleOrNull() ?: 0.0
                            val outstanding = outstandingStr.toDoubleOrNull() ?: 0.0
                            val bDate = billingDateStr.toIntOrNull() ?: 0
                            val dDate = dueDateStr.toIntOrNull() ?: 0
                            if (bDate !in 0..31 || dDate !in 0..31) {
                                validationError = "Billing & due day must be between 1 and 31"
                                return@Button
                            }

                            viewModel.addCard(
                                accountId = selectedCardAccount!!.id,
                                name = cardName.trim(),
                                type = cardType,
                                last4Digits = cardDigits.trim(),
                                creditLimit = limit,
                                outstandingAmount = outstanding,
                                billingDate = bDate,
                                dueDate = dDate
                            )

                            val finalBank = selectedCardAccount!!.bankName.ifEmpty { selectedCardAccount!!.name }
                            val finalLast4 = cardDigits.trim()
                            val newCardKey = CreditCardReminderManager.getCardKey(finalBank, finalLast4)
                            CreditCardReminderManager.setReminderEnabledForCard(context, newCardKey, reminderEnabled)
                            CreditCardReminderManager.setEnabledOffsetsForCard(context, newCardKey, selectedOffsets)
                            if (cardType == "Credit Card" && reminderEnabled && dDate in 1..31 && outstanding > 0) {
                                CreditCardReminderManager.scheduleRemindersForCard(
                                    context = context,
                                    bankName = finalBank,
                                    last4 = finalLast4,
                                    dueDate = dDate,
                                    accountId = selectedCardAccount!!.id,
                                    outstandingAmount = outstanding
                                )
                            }

                            cardName = ""
                            cardDigits = ""
                            creditLimitStr = ""
                            outstandingStr = ""
                            billingDateStr = ""
                            dueDateStr = ""
                            validationError = null
                            showManageCardsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Card Mapping", fontSize = 12.sp)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(text = "EXISTING MAPPINGS (${cards.size})", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp)
                    ) {
                        items(cards) { card ->
                            val parentAcc = activeAccounts.find { it.id == card.accountId }
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = "${card.name} (${card.type} • ${card.last4Digits})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                        Text(text = "Parent: ${parentAcc?.name ?: "Unknown"}", fontSize = 10.sp, color = Color(0xFF64748B))
                                        if (card.type == "Credit Card" && (card.creditLimit > 0 || card.outstandingAmount > 0)) {
                                            Text(text = "Limit: ${formatINR(card.creditLimit)} • Due: ${formatINR(card.outstandingAmount)}", fontSize = 9.sp, color = Color(0xFF059669))
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(
                                            onClick = {
                                                showManageCardsDialog = false
                                                showEditCardDialog = card
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF64748B), modifier = Modifier.size(14.dp))
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteCard(card.id) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageCardsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // EDIT CARD DIALOG
    if (showEditCardDialog != null) {
        val editingCard = showEditCardDialog!!
        val context = LocalContext.current
        var cardName by remember { mutableStateOf(editingCard.name) }
        var cardType by remember { mutableStateOf(editingCard.type) }
        var cardDigits by remember { mutableStateOf(editingCard.last4Digits) }
        var creditLimitStr by remember { mutableStateOf(if (editingCard.creditLimit > 0) editingCard.creditLimit.toString() else "") }
        var outstandingStr by remember { mutableStateOf(if (editingCard.outstandingAmount > 0) editingCard.outstandingAmount.toString() else "") }
        var billingDateStr by remember { mutableStateOf(if (editingCard.billingDate in 1..31) editingCard.billingDate.toString() else "") }
        var dueDateStr by remember { mutableStateOf(if (editingCard.dueDate in 1..31) editingCard.dueDate.toString() else "") }
        var selectedCardAccount by remember { mutableStateOf<AccountEntity?>(activeAccounts.find { it.id == editingCard.accountId } ?: activeAccounts.firstOrNull()) }
        var cardExpanded by remember { mutableStateOf(false) }
        var validationError by remember { mutableStateOf<String?>(null) }

        val parentAcc = activeAccounts.find { it.id == editingCard.accountId }
        val initialBank = parentAcc?.bankName?.ifEmpty { parentAcc.name } ?: editingCard.name
        val initialCardKey = remember(editingCard) {
            CreditCardReminderManager.getCardKey(initialBank, editingCard.last4Digits)
        }
        var reminderEnabled by remember {
            mutableStateOf(CreditCardReminderManager.isReminderEnabledForCard(context, initialCardKey))
        }
        var selectedOffsets by remember {
            mutableStateOf(CreditCardReminderManager.getEnabledOffsetsForCard(context, initialCardKey))
        }

        AlertDialog(
            onDismissRequest = { showEditCardDialog = null },
            title = { Text(text = "Edit Linked Card", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = cardName,
                        onValueChange = { cardName = it },
                        label = { Text("Card Nickname *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { cardType = "Credit Card" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (cardType == "Credit Card") Color(0xFFECFDF5) else Color.Transparent
                            )
                        ) {
                            Text("Credit Card", color = if (cardType == "Credit Card") Color(0xFF059669) else Color(0xFF475569), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { cardType = "Debit Card" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (cardType == "Debit Card") Color(0xFFECFDF5) else Color.Transparent
                            )
                        ) {
                            Text("Debit Card", color = if (cardType == "Debit Card") Color(0xFF059669) else Color(0xFF475569), fontSize = 11.sp)
                        }
                    }

                    OutlinedTextField(
                        value = cardDigits,
                        onValueChange = { if (it.length <= 4) cardDigits = it.filter { c -> c.isDigit() } },
                        label = { Text("Last 4 Digits of Card *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (cardType == "Credit Card") {
                        OutlinedTextField(
                            value = creditLimitStr,
                            onValueChange = { creditLimitStr = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Total Credit Limit (₹)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = outstandingStr,
                            onValueChange = { outstandingStr = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Current Outstanding Amount (₹)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = billingDateStr,
                                onValueChange = { if (it.length <= 2) billingDateStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Billing Date (1-31)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = dueDateStr,
                                onValueChange = { if (it.length <= 2) dueDateStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Due Date (1-31)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        CreditCardPaymentReminderSection(
                            reminderEnabled = reminderEnabled,
                            onReminderEnabledChange = { reminderEnabled = it },
                            selectedOffsets = selectedOffsets,
                            onToggleOffset = { offset ->
                                selectedOffsets = if (selectedOffsets.contains(offset)) {
                                    if (selectedOffsets.size > 1) selectedOffsets - offset else selectedOffsets
                                } else {
                                    selectedOffsets + offset
                                }
                            },
                            dueDate = dueDateStr.toIntOrNull() ?: 0
                        )
                    }

                    if (activeAccounts.isNotEmpty()) {
                        Text(text = "Parent Bank Account *", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569))
                        Box {
                            OutlinedButton(
                                onClick = { cardExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                val acc = selectedCardAccount
                                val accLabel = if (acc != null) {
                                    "${acc.name}${if (acc.last4Digits.isNotEmpty()) " • ${acc.last4Digits}" else ""}"
                                } else "Select Account"
                                Text(text = accLabel, fontSize = 12.sp, color = Color(0xFF0F172A))
                            }
                            DropdownMenu(
                                expanded = cardExpanded,
                                onDismissRequest = { cardExpanded = false }
                            ) {
                                activeAccounts.forEach { acc ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                BankLogo(
                                                    bankName = acc.bankName.ifEmpty { acc.name },
                                                    size = 20.dp,
                                                    shapeRadius = 6.dp
                                                )
                                                Column {
                                                    Text(acc.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                    Text("${acc.bankName.ifEmpty { acc.type }}${if (acc.last4Digits.isNotEmpty()) " • ${acc.last4Digits}" else ""}", fontSize = 10.sp, color = Color(0xFF64748B))
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedCardAccount = acc
                                            cardExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (validationError != null) {
                        Text(text = validationError!!, fontSize = 11.sp, color = Color(0xFFDC2626))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (cardName.isBlank()) {
                            validationError = "Please enter card nickname"
                            return@Button
                        }
                        if (cardDigits.length != 4) {
                            validationError = "Last 4 digits must be exactly 4 digits"
                            return@Button
                        }
                        if (selectedCardAccount == null) {
                            validationError = "Please select parent bank account"
                            return@Button
                        }
                        val limit = creditLimitStr.toDoubleOrNull() ?: 0.0
                        val outstanding = outstandingStr.toDoubleOrNull() ?: 0.0
                        val bDate = billingDateStr.toIntOrNull() ?: 0
                        val dDate = dueDateStr.toIntOrNull() ?: 0
                        if (bDate !in 0..31 || dDate !in 0..31) {
                            validationError = "Billing & due day must be between 1 and 31"
                            return@Button
                        }

                        viewModel.updateCard(
                            id = editingCard.id,
                            accountId = selectedCardAccount!!.id,
                            name = cardName.trim(),
                            type = cardType,
                            last4Digits = cardDigits.trim(),
                            creditLimit = limit,
                            outstandingAmount = outstanding,
                            billingDate = bDate,
                            dueDate = dDate
                        )

                        val finalBank = selectedCardAccount?.bankName?.ifEmpty { selectedCardAccount?.name } ?: cardName.trim()
                        val finalLast4 = cardDigits.trim()
                        val newCardKey = CreditCardReminderManager.getCardKey(finalBank, finalLast4)
                        CreditCardReminderManager.setReminderEnabledForCard(context, newCardKey, reminderEnabled)
                        CreditCardReminderManager.setEnabledOffsetsForCard(context, newCardKey, selectedOffsets)

                        if (cardType == "Credit Card") {
                            if (reminderEnabled && dDate in 1..31 && outstanding > 0) {
                                CreditCardReminderManager.scheduleRemindersForCard(
                                    context = context,
                                    bankName = finalBank,
                                    last4 = finalLast4,
                                    dueDate = dDate,
                                    accountId = selectedCardAccount!!.id,
                                    outstandingAmount = outstanding
                                )
                            } else {
                                CreditCardReminderManager.cancelRemindersForCard(
                                    context = context,
                                    bankName = finalBank,
                                    last4 = finalLast4,
                                    dueDate = dDate
                                )
                            }
                        }

                        showEditCardDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    Text("Save Card")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditCardDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ADD ACCOUNT CUSTOM DIALOG
    if (showAddAccountCustomDialog) {
        val context = LocalContext.current
        var name by remember { mutableStateOf("") }
        var type by remember { mutableStateOf("Credit Card") }
        var bankName by remember { mutableStateOf("") }
        var last4Digits by remember { mutableStateOf("") }
        var creditLimitStr by remember { mutableStateOf("") }
        var outstandingStr by remember { mutableStateOf("") }
        var billingDateStr by remember { mutableStateOf("") }
        var dueDateStr by remember { mutableStateOf("") }
        var isDefault by remember { mutableStateOf(activeAccounts.isEmpty()) }
        var isOwnedByMe by remember { mutableStateOf(true) }
        var initialBalanceStr by remember { mutableStateOf("") }
        var validationError by remember { mutableStateOf<String?>(null) }
        var reminderEnabled by remember { mutableStateOf(true) }
        var selectedOffsets by remember { mutableStateOf(CreditCardReminderManager.DEFAULT_OFFSETS) }

        val typeOptions = listOf("Bank Account", "Credit Card", "Debit Card", "UPI", "Cash", "Other")

        AlertDialog(
            onDismissRequest = { showAddAccountCustomDialog = false },
            title = { Text(text = "Add Account / Payment Method", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Enter your bank, card, UPI or cash account details. Never enter PINs, passwords, CVV or OTPs.",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(if (type == "Credit Card") "Card / Account Display Name *" else "Account / Display Name *") },
                        placeholder = { Text(if (type == "Credit Card") "e.g. HDFC Millennia Credit Card" else "e.g. HDFC Salary Account") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text(text = "Account Type", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        typeOptions.forEach { t ->
                            FilterChip(
                                selected = type == t,
                                onClick = { type = t },
                                label = { Text(t, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF059669),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    OutlinedTextField(
                        value = bankName,
                        onValueChange = { bankName = it },
                        label = { Text(if (type == "Credit Card") "Bank / Issuer Name" else "Bank / Institution Name (optional)") },
                        placeholder = { Text("e.g. HDFC Bank, ICICI Bank, SBI") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = last4Digits,
                        onValueChange = { if (it.length <= 4) last4Digits = it.filter { char -> char.isDigit() } },
                        label = { Text(if (type == "Credit Card") "Last 4 Digits *" else "Last 4 Digits (optional)") },
                        placeholder = { Text("e.g. 4092") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (type == "Credit Card") {
                        OutlinedTextField(
                            value = creditLimitStr,
                            onValueChange = { creditLimitStr = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Total Credit Limit (₹)") },
                            placeholder = { Text("e.g. 150000") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = outstandingStr,
                            onValueChange = { outstandingStr = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Current Outstanding Amount (₹)") },
                            placeholder = { Text("e.g. 18500") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = billingDateStr,
                                onValueChange = { if (it.length <= 2) billingDateStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Billing Date (1-31)") },
                                placeholder = { Text("e.g. 15") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = dueDateStr,
                                onValueChange = { if (it.length <= 2) dueDateStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Payment Due Date (1-31)") },
                                placeholder = { Text("e.g. 5") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        CreditCardPaymentReminderSection(
                            reminderEnabled = reminderEnabled,
                            onReminderEnabledChange = { reminderEnabled = it },
                            selectedOffsets = selectedOffsets,
                            onToggleOffset = { offset ->
                                selectedOffsets = if (selectedOffsets.contains(offset)) {
                                    if (selectedOffsets.size > 1) selectedOffsets - offset else selectedOffsets
                                } else {
                                    selectedOffsets + offset
                                }
                            },
                            dueDate = dueDateStr.toIntOrNull() ?: 0
                        )
                    }

                    if (type == "Bank Account") {
                        OutlinedTextField(
                            value = initialBalanceStr,
                            onValueChange = { initialBalanceStr = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Current Balance / Opening Balance (₹)") },
                            placeholder = { Text("e.g. 10000") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isOwnedByMe = !isOwnedByMe }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Self-Owned Account", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Text("Supports internal transfers & reconciliation", fontSize = 10.sp, color = Color(0xFF64748B))
                        }
                        Switch(checked = isOwnedByMe, onCheckedChange = { isOwnedByMe = it })
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isDefault = !isDefault }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Set as Default Account", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Text("Primary source for new manual transactions", fontSize = 10.sp, color = Color(0xFF64748B))
                        }
                        Switch(checked = isDefault, onCheckedChange = { isDefault = it })
                    }

                    if (validationError != null) {
                        Text(text = validationError!!, fontSize = 11.sp, color = Color(0xFFDC2626))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isBlank()) {
                            validationError = "Please enter an account / display name"
                            return@Button
                        }
                        if (type == "Credit Card" && last4Digits.isNotEmpty() && last4Digits.length != 4) {
                            validationError = "Last 4 digits must be exactly 4 digits"
                            return@Button
                        }
                        val limit = creditLimitStr.toDoubleOrNull() ?: 0.0
                        val outstanding = outstandingStr.toDoubleOrNull() ?: 0.0
                        val bDate = billingDateStr.toIntOrNull() ?: 0
                        val dDate = dueDateStr.toIntOrNull() ?: 0
                        if (bDate !in 0..31 || dDate !in 0..31) {
                            validationError = "Billing and due day must be between 1 and 31"
                            return@Button
                        }

                        val icon = when (type) {
                            "Credit Card", "Debit Card" -> "credit_card"
                            "UPI" -> "smartphone"
                            "Cash" -> "payments"
                            else -> "landmark"
                        }
                        val colour = when (type) {
                            "Credit Card" -> "#7C3AED"
                            "Debit Card" -> "#2563EB"
                            "UPI" -> "#059669"
                            "Cash" -> "#D97706"
                            else -> "#0284C7"
                        }
                        val initialBalance = when (type) {
                            "Bank Account" -> initialBalanceStr.toDoubleOrNull() ?: 0.0
                            "Credit Card" -> outstanding
                            else -> 0.0
                        }
                        val finalBank = bankName.trim().ifEmpty { name.trim() }
                        val finalLast4 = last4Digits.trim()
                        val newCardKey = CreditCardReminderManager.getCardKey(finalBank, finalLast4)
                        CreditCardReminderManager.setReminderEnabledForCard(context, newCardKey, reminderEnabled)
                        CreditCardReminderManager.setEnabledOffsetsForCard(context, newCardKey, selectedOffsets)

                        viewModel.addAccount(
                            name = name.trim(),
                            type = type.trim(),
                            bankName = bankName.trim(),
                            last4Digits = last4Digits.trim(),
                            icon = icon,
                            colour = colour,
                            creditLimit = limit,
                            outstandingAmount = outstanding,
                            billingDate = bDate,
                            dueDate = dDate,
                            initialBalance = initialBalance,
                            isDefault = isDefault,
                            isOwnedByMe = isOwnedByMe
                        )

                        if (type == "Credit Card" && reminderEnabled && dDate in 1..31 && outstanding > 0) {
                            CreditCardReminderManager.scheduleRemindersForCard(
                                context = context,
                                bankName = finalBank,
                                last4 = finalLast4,
                                dueDate = dDate,
                                accountId = "temp-${System.currentTimeMillis()}",
                                outstandingAmount = outstanding
                            )
                        }

                        showAddAccountCustomDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    Text("Save Account")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAccountCustomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // EDIT ACCOUNT DIALOG
    if (showEditDialog != null) {
        EditAccountDialog(
            acc = showEditDialog!!,
            viewModel = viewModel,
            onDismiss = { showEditDialog = null }
        )
    }

    if (showArchiveConfirm != null) {
        val acc = showArchiveConfirm!!
        AlertDialog(
            onDismissRequest = { showArchiveConfirm = null },
            title = { Text(text = "Archive Account?", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = { Text(text = "Are you sure you want to archive ${acc.name}? Historical transaction data will remain completely intact.", fontSize = 12.sp, color = Color(0xFF64748B)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.archiveAccount(acc.id)
                        showArchiveConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Archive")
                }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Account History Screen View (Drill-Down)
 */
@Composable
fun AccountHistoryView(
    account: AccountEntity?,
    accId: String,
    accName: String,
    accType: String,
    accLast4: String,
    accColor: Color,
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    subcategories: List<SubcategoryEntity>,
    accounts: List<AccountEntity>,
    allSplits: List<TransactionSplitEntity>,
    formatINR: (Double) -> String,
    onBack: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit
) {
    BackHandler { onBack() }

    var selectedSourceFilter by remember { mutableStateOf("All") }

    val isCreditCard = accType.equals("Credit Card", ignoreCase = true) || account?.type.equals("Credit Card", ignoreCase = true)

    // All transactions belonging to this account from centralized transaction database
    val accTxs = transactions.filter { tx ->
        if (tx.accountId == accId || tx.counterpartyAccountId == accId) return@filter true
        if (accLast4.isNotEmpty() && tx.last4Digits == accLast4) {
            val text = "${tx.note} ${tx.paymentMethod} ${tx.merchant}".lowercase(Locale.ENGLISH)
            if (isCreditCard) {
                return@filter text.contains("credit card") || text.contains("cc ending") || text.contains("card ending") || tx.paymentMethod.contains("card", true)
            } else {
                return@filter !text.contains("credit card") && !text.contains("cc ending")
            }
        }
        false
    }

    val totalExpenses = if (isCreditCard) {
        accTxs.filter { TransactionIdentityResolver.isCreditCardPurchase(it, null, accId, accLast4) }.sumOf { it.amount }
    } else {
        accTxs.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
    }

    val totalIncome = if (isCreditCard) {
        accTxs.filter { TransactionIdentityResolver.isCreditCardPaymentOrRefund(it, null, accId, accLast4) }.sumOf { it.amount }
    } else {
        accTxs.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
    }

    val accountHistoryBalance = calculateAccountBalance(accId, accTxs, account?.initialBalance ?: 0.0)

    val txOutstanding = if (isCreditCard) Math.max(0.0, totalExpenses - totalIncome) else 0.0
    val effectiveOutstanding = if (txOutstanding > 0.0) txOutstanding else (account?.outstandingAmount ?: 0.0)
    val creditLimit = account?.creditLimit ?: 0.0
    val availableCredit = if (creditLimit > 0) Math.max(0.0, creditLimit - effectiveOutstanding) else 0.0
    val billingDate = account?.billingDate ?: 0
    val dueDate = account?.dueDate ?: 0

    val filteredTxs = accTxs.filter { tx ->
        when (selectedSourceFilter) {
            "Manual" -> tx.source == "MANUAL"
            "SMS" -> tx.source == "SMS"
            "Notification" -> tx.source == "NOTIFICATION"
            "Email" -> tx.source == "EMAIL"
            else -> true
        }
    }.sortedWith(compareByDescending<TransactionEntity> { it.date }.thenByDescending { it.time })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
    ) {
        // Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE2E8F0))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Accounts",
                    tint = Color(0xFF0F172A),
                    modifier = Modifier.size(20.dp)
                )
            }

            val displayLast4 = if (accLast4.length == 4) accLast4 else if (accLast4.isNotBlank()) accLast4.takeLast(4) else "----"
            val displayTypeLabel = if (isCreditCard) "Credit Card" else if (accType.equals("Savings", ignoreCase = true) || accType.equals("Current", ignoreCase = true) || accType.equals("Bank Account", ignoreCase = true)) "Bank Account" else accType

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = accName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "$displayTypeLabel • •••• $displayLast4",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
        }

        val historyListState = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }

        LazyColumn(
            state = historyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                // Hero Summary Card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isCreditCard) Color(0xFF1E293B) else Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BankLogo(
                                    bankName = account?.bankName?.ifEmpty { accName } ?: accName,
                                    size = 40.dp,
                                    shapeRadius = 12.dp
                                )
                                Column {
                                    Text(
                                        text = if (isCreditCard) (account?.bankName ?: accName) else "Account Activity",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCreditCard) Color.White else Color(0xFF0F172A)
                                    )
                                    Text(
                                        text = "${accTxs.size} transactions",
                                        fontSize = 11.sp,
                                        color = if (isCreditCard) Color(0xFF94A3B8) else Color(0xFF64748B)
                                    )
                                }
                            }

                            Surface(
                                color = if (isCreditCard) Color.White.copy(alpha = 0.15f) else Color(0xFFECFDF5),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (isCreditCard) "CREDIT CARD" else if (account?.isDefault == true) "DEFAULT" else accType.uppercase(Locale.US),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCreditCard) Color.White else Color(0xFF059669),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = if (isCreditCard) Color(0xFF334155) else Color(0xFFF1F5F9))

                        if (isCreditCard) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text = "Outstanding Due", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                    Text(
                                        text = formatINR(effectiveOutstanding),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFF87171)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = "Available Credit", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                    Text(
                                        text = if (creditLimit > 0) formatINR(availableCredit) else "N/A",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (creditLimit > 0) Color(0xFF34D399) else Color(0xFF94A3B8)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(text = "Total Limit", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                    Text(
                                        text = if (creditLimit > 0) formatINR(creditLimit) else "N/A",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (creditLimit > 0) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (billingDate in 1..31) "Billing: ${billingDate}th" else "Billing: N/A",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
                                Text(
                                    text = if (dueDate in 1..31) "Due: ${dueDate}th" else "Due Date: N/A",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
                                Text(
                                    text = "Spent: ${formatINR(totalExpenses)}",
                                    fontSize = 10.sp,
                                    color = Color(0xFFF87171),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text = "Current Balance", fontSize = 10.sp, color = Color(0xFF64748B))
                                    val balColor = if (accountHistoryBalance >= 0) Color(0xFF059669) else Color(0xFFDC2626)
                                    val balPrefix = if (accountHistoryBalance >= 0) "" else "- "
                                    Text(text = balPrefix + formatINR(Math.abs(accountHistoryBalance)), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = balColor)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = "Total Spent", fontSize = 10.sp, color = Color(0xFF64748B))
                                    Text(text = formatINR(totalExpenses), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(text = "Total Received", fontSize = 10.sp, color = Color(0xFF64748B))
                                    Text(text = formatINR(totalIncome), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                                }
                            }
                        }
                    }
                }
            }

            if (isCreditCard) {
                item {
                    val context = LocalContext.current
                    val bankNameForReminder = account?.bankName?.ifEmpty { accName } ?: accName
                    val cardKey = remember(bankNameForReminder, accLast4) {
                        CreditCardReminderManager.getCardKey(bankNameForReminder, accLast4)
                    }
                    var reminderEnabled by remember(cardKey) {
                        mutableStateOf(CreditCardReminderManager.isReminderEnabledForCard(context, cardKey))
                    }
                    var selectedOffsets by remember(cardKey) {
                        mutableStateOf(CreditCardReminderManager.getEnabledOffsetsForCard(context, cardKey))
                    }

                    CreditCardPaymentReminderSection(
                        reminderEnabled = reminderEnabled,
                        onReminderEnabledChange = { enabled ->
                            reminderEnabled = enabled
                            CreditCardReminderManager.setReminderEnabledForCard(context, cardKey, enabled)
                            if (enabled && dueDate in 1..31 && effectiveOutstanding > 0) {
                                CreditCardReminderManager.scheduleRemindersForCard(
                                    context = context,
                                    bankName = bankNameForReminder,
                                    last4 = accLast4,
                                    dueDate = dueDate,
                                    accountId = accId,
                                    outstandingAmount = effectiveOutstanding
                                )
                            } else {
                                CreditCardReminderManager.cancelRemindersForCard(
                                    context = context,
                                    bankName = bankNameForReminder,
                                    last4 = accLast4,
                                    dueDate = dueDate
                                )
                            }
                        },
                        selectedOffsets = selectedOffsets,
                        onToggleOffset = { offset ->
                            val newOffsets = if (selectedOffsets.contains(offset)) {
                                if (selectedOffsets.size > 1) selectedOffsets - offset else selectedOffsets
                            } else {
                                selectedOffsets + offset
                            }
                            selectedOffsets = newOffsets
                            CreditCardReminderManager.setEnabledOffsetsForCard(context, cardKey, newOffsets)
                            if (reminderEnabled && dueDate in 1..31 && effectiveOutstanding > 0) {
                                CreditCardReminderManager.scheduleRemindersForCard(
                                    context = context,
                                    bankName = bankNameForReminder,
                                    last4 = accLast4,
                                    dueDate = dueDate,
                                    accountId = accId,
                                    outstandingAmount = effectiveOutstanding
                                )
                            }
                        },
                        dueDate = dueDate
                    )
                }
            }

            item {
                // Source Filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("All", "Manual", "SMS", "Notification", "Email").forEach { source ->
                        val isSelected = selectedSourceFilter == source
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedSourceFilter = source },
                            label = { Text(source, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF059669),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            if (filteredTxs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.ReceiptLong, contentDescription = null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(40.dp))
                            Text(text = "No transactions found", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                            Text(text = "No activity recorded under this account with '$selectedSourceFilter' filter.", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }
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

@Composable
fun BankAccountManagementCard(
    account: AccountEntity,
    balance: Double,
    latestTransaction: TransactionEntity? = null,
    onCardClick: () -> Unit,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    val formatINR: (Double) -> String = { amt ->
        "₹" + java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN")).format(amt)
    }
    val bankName = account.bankName.ifEmpty { account.name }
    val palette = BankAccountColorResolver.resolvePalette(
        bankName = bankName,
        accountName = account.name
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    BankLogo(
                        bankName = bankName,
                        size = 40.dp,
                        shapeRadius = 10.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = bankName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            if (account.isDefault) {
                                Surface(
                                    color = Color(0xFFD1FAE5),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "DEFAULT",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF065F46),
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        val displayLast4 = if (account.last4Digits.length == 4) account.last4Digits else if (account.last4Digits.isNotBlank()) account.last4Digits.takeLast(4) else "****"
                        Text(
                            text = "${account.type} • •••• $displayLast4",
                            fontSize = 11.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatINR(balance),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (balance >= 0) Color(0xFF059669) else Color(0xFFDC2626)
                    )
                    Text(text = "Balance", fontSize = 9.sp, color = Color(0xFF94A3B8))
                }
            }

            if (latestTransaction != null) {
                Surface(
                    color = Color(0xFFF8FAFC),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.History, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(12.dp))
                            Text(
                                text = latestTransaction.merchant.ifEmpty { "Latest Activity" },
                                fontSize = 10.sp,
                                color = Color(0xFF475569),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = (if (latestTransaction.type == "EXPENSE") "- " else "+ ") + formatINR(latestTransaction.amount),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (latestTransaction.type == "EXPENSE") Color(0xFFDC2626) else Color(0xFF059669)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!account.isDefault) {
                        IconButton(onClick = onSetDefault, modifier = Modifier.size(24.dp)) {
                            Icon(imageVector = Icons.Default.StarOutline, contentDescription = "Set Default", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                        }
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onArchive, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Archive, contentDescription = "Archive", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color(0xFFFCA5A5), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun CreditCardAccountManagementCard(
    account: AccountEntity,
    effectiveOutstanding: Double = account.outstandingAmount,
    latestTransaction: TransactionEntity? = null,
    onCardClick: () -> Unit,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit = {},
    transactionCount: Int = 0
) {
    val formatINR: (Double) -> String = { amt ->
        "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(amt)
    }

    val derivedBankName = when {
        account.bankName.isNotBlank() -> account.bankName
        account.name.contains("HDFC", true) -> "HDFC Bank"
        account.name.contains("SBI", true) || account.name.contains("State Bank", true) -> "State Bank of India"
        account.name.contains("ICICI", true) -> "ICICI Bank"
        account.name.contains("Axis", true) -> "Axis Bank"
        account.name.contains("Kotak", true) -> "Kotak Mahindra Bank"
        account.name.contains("IDFC", true) -> "IDFC FIRST Bank"
        account.name.contains("AU", true) -> "AU Small Finance Bank"
        account.name.contains("RBL", true) -> "RBL Bank"
        account.name.contains("YES", true) -> "YES Bank"
        account.name.contains("IndusInd", true) -> "IndusInd Bank"
        account.name.contains("Bank of Baroda", true) || account.name.contains("BOB", true) -> "Bank of Baroda"
        account.name.contains("PNB", true) -> "Punjab National Bank"
        account.name.contains("Canara", true) -> "Canara Bank"
        else -> account.bankName.ifEmpty { "Credit Card" }
    }

    val displayCardName = if (account.name.isNotBlank() && !account.name.equals(derivedBankName, ignoreCase = true)) {
        account.name
    } else {
        "$derivedBankName Credit Card"
    }

    val palette = CreditCardColorResolver.resolvePalette(
        bankName = derivedBankName,
        cardName = displayCardName,
        last4Digits = account.last4Digits,
        cardId = account.id,
        rawColour = account.colour
    )
    val gradientBrush = palette.gradientBrush

    val creditLimit = account.creditLimit
    val availableLimit = if (creditLimit > 0) Math.max(0.0, creditLimit - effectiveOutstanding) else 0.0
    val utilization = if (creditLimit > 0) (effectiveOutstanding / creditLimit) * 100.0 else 0.0

    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
        ) {
            // Atmospheric background depth elements (sized proportionally for compact card)
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 40.dp, y = (-30).dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.12f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .align(Alignment.BottomStart)
                    .offset(x = (-30).dp, y = 40.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.08f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        BankLogoBadge(bankName = derivedBankName, actualIssuer = derivedBankName)
                        Column {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = derivedBankName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                if (account.isDefault) {
                                    Surface(
                                        color = Color.White.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "DEFAULT",
                                            fontSize = 7.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = displayCardName,
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "CREDIT CARD",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = formatINR(effectiveOutstanding),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "Outstanding Due",
                            fontSize = 8.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                // Masked Card Number
                val displayLast4 = if (account.last4Digits.length == 4) account.last4Digits else if (account.last4Digits.isNotBlank()) account.last4Digits.takeLast(4) else "****"
                Text(
                    text = "•••• •••• •••• $displayLast4",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f),
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )

                if (latestTransaction != null) {
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.History, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(11.dp))
                                Text(
                                    text = latestTransaction.merchant.ifEmpty { "Latest Activity" },
                                    fontSize = 9.sp,
                                    color = Color.White.copy(alpha = 0.9f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = (if (latestTransaction.type == "EXPENSE") "- " else "+ ") + formatINR(latestTransaction.amount),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Bottom Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = if (account.billingDate in 1..31) "Billing: ${account.billingDate}th" else "Billing: N/A",
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Text(
                            text = if (account.dueDate in 1..31) "Due Date: ${account.dueDate}th" else "Due Date: N/A",
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!account.isDefault) {
                            IconButton(onClick = onSetDefault, modifier = Modifier.size(24.dp)) {
                                Icon(imageVector = Icons.Default.StarOutline, contentDescription = "Set Default", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onArchive, modifier = Modifier.size(24.dp)) {
                            Icon(imageVector = Icons.Default.Archive, contentDescription = "Archive", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                            Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (creditLimit > 0) {
                    val utilRatio = (effectiveOutstanding / creditLimit).toFloat().coerceIn(0f, 1f)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Utilization: ${String.format(Locale.US, "%.0f", utilization)}%", fontSize = 8.sp, color = Color.White.copy(alpha = 0.7f))
                            Text(text = "Limit: ${formatINR(creditLimit)}", fontSize = 8.sp, color = Color.White.copy(alpha = 0.7f))
                        }
                        LinearProgressIndicator(
                            progress = { utilRatio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (utilRatio > 0.8f) Color(0xFFF87171) else Color.White.copy(alpha = 0.6f),
                            trackColor = Color.White.copy(alpha = 0.2f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EditAccountDialog(
    acc: AccountEntity,
    viewModel: KharchaViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val transactions by viewModel.transactions.collectAsState()
    var name by remember { mutableStateOf(acc.name) }
    var type by remember { mutableStateOf(acc.type) }
    var bankName by remember { mutableStateOf(acc.bankName) }
    var digits by remember { mutableStateOf(acc.last4Digits) }
    var creditLimitStr by remember { mutableStateOf(if (acc.creditLimit > 0) acc.creditLimit.toString() else "") }
    val trackedOutstanding = com.example.utils.TransactionIdentityResolver.creditCardOutstandingFromAnchor(
        acc.initialBalance,
        transactions,
        null,
        acc.id,
        acc.last4Digits
    )
    val hasCardBalanceActivity = transactions.any { tx ->
        com.example.utils.TransactionIdentityResolver.isCreditCardPurchase(tx, null, acc.id, acc.last4Digits) ||
            com.example.utils.TransactionIdentityResolver.isCreditCardPaymentOrRefund(tx, null, acc.id, acc.last4Digits)
    }
    val currentOutstanding = if (hasCardBalanceActivity || acc.initialBalance != 0.0) {
        trackedOutstanding
    } else {
        acc.outstandingAmount
    }
    var outstandingStr by remember(acc.id) {
        mutableStateOf(if (currentOutstanding > 0) currentOutstanding.toString() else "")
    }
    var billingDateStr by remember { mutableStateOf(if (acc.billingDate in 1..31) acc.billingDate.toString() else "") }
    var dueDateStr by remember { mutableStateOf(if (acc.dueDate in 1..31) acc.dueDate.toString() else "") }
    var isDefault by remember { mutableStateOf(acc.isDefault) }
    var isOwnedByMe by remember { mutableStateOf(acc.isOwnedByMe) }
    val currentAccountBalance = calculateAccountBalance(acc.id, transactions, acc.initialBalance)
    var initialBalanceStr by remember(acc.id) {
        mutableStateOf(if (currentAccountBalance != 0.0) currentAccountBalance.toString() else "")
    }
    var validationError by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val initialCardKey = remember(acc.bankName, acc.name, acc.last4Digits) {
        CreditCardReminderManager.getCardKey(acc.bankName.ifEmpty { acc.name }, acc.last4Digits)
    }
    var reminderEnabled by remember {
        mutableStateOf(CreditCardReminderManager.isReminderEnabledForCard(context, initialCardKey))
    }
    var selectedOffsets by remember {
        mutableStateOf(CreditCardReminderManager.getEnabledOffsetsForCard(context, initialCardKey))
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(text = "Delete this account?") },
            text = { Text(text = "Are you sure you want to delete this account?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAccount(acc.id)
                        showDeleteConfirm = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val typeOptions = listOf("Bank Account", "Credit Card", "Debit Card", "UPI", "Cash", "Other")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Card & Account Details", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account / Card Name *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(text = "Account Type", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    typeOptions.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF059669),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = bankName,
                    onValueChange = { bankName = it },
                    label = { Text("Bank / Issuer Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = digits,
                    onValueChange = { if (it.length <= 4) digits = it.filter { c -> c.isDigit() } },
                    label = { Text("Last 4 Digits") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (type == "Credit Card") {
                    OutlinedTextField(
                        value = creditLimitStr,
                        onValueChange = { creditLimitStr = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Total Credit Limit (₹)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = outstandingStr,
                        onValueChange = { outstandingStr = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Current Outstanding Amount (₹)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = billingDateStr,
                            onValueChange = { if (it.length <= 2) billingDateStr = it.filter { c -> c.isDigit() } },
                            label = { Text("Billing Date (1-31)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = dueDateStr,
                            onValueChange = { if (it.length <= 2) dueDateStr = it.filter { c -> c.isDigit() } },
                            label = { Text("Payment Due Date (1-31)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    CreditCardPaymentReminderSection(
                        reminderEnabled = reminderEnabled,
                        onReminderEnabledChange = { reminderEnabled = it },
                        selectedOffsets = selectedOffsets,
                        onToggleOffset = { offset ->
                            selectedOffsets = if (selectedOffsets.contains(offset)) {
                                if (selectedOffsets.size > 1) selectedOffsets - offset else selectedOffsets
                            } else {
                                selectedOffsets + offset
                            }
                        },
                        dueDate = dueDateStr.toIntOrNull() ?: 0
                    )
                }

                if (type == "Bank Account") {
                    OutlinedTextField(
                        value = initialBalanceStr,
                        onValueChange = { initialBalanceStr = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Current Balance / Opening Balance (₹)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isOwnedByMe = !isOwnedByMe }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Self-Owned Account", fontSize = 12.sp, color = Color(0xFF334155))
                    Switch(checked = isOwnedByMe, onCheckedChange = { isOwnedByMe = it })
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete Account", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                if (validationError != null) {
                    Text(text = validationError!!, fontSize = 11.sp, color = Color(0xFFDC2626))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        validationError = "Account name cannot be empty"
                        return@Button
                    }
                    if (type == "Credit Card" && digits.isNotEmpty() && digits.length != 4) {
                        validationError = "Last 4 digits must be 4 digits"
                        return@Button
                    }
                    val limit = creditLimitStr.toDoubleOrNull() ?: 0.0
                    val outstanding = outstandingStr.toDoubleOrNull() ?: 0.0
                    val bDate = billingDateStr.toIntOrNull() ?: 0
                    val dDate = dueDateStr.toIntOrNull() ?: 0
                    if (bDate !in 0..31 || dDate !in 0..31) {
                        validationError = "Billing and due day must be between 1 and 31"
                        return@Button
                    }

                    val initialBalance = when (type) {
                        "Bank Account" -> openingBalanceForCurrentBalance(
                            acc.id,
                            transactions,
                            initialBalanceStr.toDoubleOrNull() ?: 0.0
                        )
                        "Credit Card" -> com.example.utils.TransactionIdentityResolver.creditCardAnchorForOutstanding(
                            outstanding,
                            transactions,
                            null,
                            acc.id,
                            digits.trim()
                        )
                        else -> 0.0
                    }
                    viewModel.updateAccount(
                        id = acc.id,
                        name = name.trim(),
                        type = type.trim(),
                        bankName = bankName.trim(),
                        last4Digits = digits.trim(),
                        icon = acc.icon,
                        colour = acc.colour,
                        creditLimit = limit,
                        outstandingAmount = outstanding,
                        billingDate = bDate,
                        dueDate = dDate,
                        initialBalance = initialBalance,
                        isActive = acc.isActive,
                        isDefault = isDefault,
                        isOwnedByMe = isOwnedByMe
                    )

                    val finalBank = bankName.trim().ifEmpty { name.trim() }
                    val finalLast4 = digits.trim()
                    val newCardKey = CreditCardReminderManager.getCardKey(finalBank, finalLast4)
                    CreditCardReminderManager.setReminderEnabledForCard(context, newCardKey, reminderEnabled)
                    CreditCardReminderManager.setEnabledOffsetsForCard(context, newCardKey, selectedOffsets)

                    if (type == "Credit Card") {
                        if (reminderEnabled && dDate in 1..31 && outstanding > 0) {
                            CreditCardReminderManager.scheduleRemindersForCard(
                                context = context,
                                bankName = finalBank,
                                last4 = finalLast4,
                                dueDate = dDate,
                                accountId = acc.id,
                                outstandingAmount = outstanding
                            )
                        } else {
                            CreditCardReminderManager.cancelRemindersForCard(
                                context = context,
                                bankName = finalBank,
                                last4 = finalLast4,
                                dueDate = dDate
                            )
                        }
                    }

                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
            ) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun calculateAccountBalance(accountId: String, transactions: List<TransactionEntity>, initialBalance: Double): Double {
    var balance = initialBalance
    transactions.forEach { tx ->
        val transferEffect = TransactionIdentityResolver.internalTransferBalanceEffect(tx, accountId, transactions)
        if (transferEffect != null) {
            balance += transferEffect
        } else {
            val isDebit = tx.direction == "DEBIT" || tx.type == "EXPENSE"
            val isCredit = tx.direction == "CREDIT" || tx.type == "INCOME"
            if (tx.accountId == accountId) {
                if (isCredit) balance += tx.amount
                else if (isDebit) balance -= tx.amount
            } else if (tx.counterpartyAccountId == accountId) {
                balance += tx.amount
            }

        }
    }
    return balance
}

fun openingBalanceForCurrentBalance(
    accountId: String,
    transactions: List<TransactionEntity>,
    currentBalance: Double
): Double = currentBalance - calculateAccountBalance(accountId, transactions, 0.0)
