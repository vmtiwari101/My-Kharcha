package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.TransactionEntity
import com.example.viewmodel.KharchaViewModel
import java.text.NumberFormat
import java.util.Locale

@Composable
fun TransactionDetailDialog(
    tx: TransactionEntity,
    viewModel: KharchaViewModel,
    onDismiss: () -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    val subcategories by viewModel.subcategories.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val allSplits by viewModel.transactionSplits.collectAsState()
    val allTransactions by viewModel.transactions.collectAsState()

    val splits = remember(allSplits, tx.id) {
        allSplits.filter { it.transactionId == tx.id }
    }
    val isSplit = splits.isNotEmpty()

    val cat = categories.find { it.id == tx.categoryId }
    val subcat = subcategories.find { it.id == tx.subcategoryId }
    val acc = accounts.find { it.id == tx.accountId }
    val isCardPayment = tx.transactionType == "CREDIT_CARD_BILL_PAYMENT" || tx.transactionType == "CARD_PAYMENT"
    val isInternal = tx.isInternalTransfer || tx.type == "INTERNAL_TRANSFER" || tx.transactionType == "INTERNAL_TRANSFER" || isCardPayment
    val isIncome = !isInternal && (tx.type.equals("INCOME", ignoreCase = true) || tx.transactionType.equals("INCOME", ignoreCase = true))
    val isOutgoing = !isIncome && (tx.type == "EXPENSE" || tx.direction == "DEBIT" || (isInternal && tx.direction != "CREDIT"))
    val isExpense = isOutgoing && !isInternal

    val counterpartyAccount = accounts.find { it.id == tx.counterpartyAccountId }
        ?: if (tx.transferGroupId != null) {
            allTransactions.find { it.transferGroupId == tx.transferGroupId && it.id != tx.id }?.let { pair ->
                accounts.find { it.id == pair.accountId || (pair.last4Digits.isNotEmpty() && it.last4Digits == pair.last4Digits) }
            }
        } else null
    val counterpartyCard = if (counterpartyAccount == null) {
        cards.find { it.id == tx.counterpartyAccountId || it.accountId == tx.counterpartyAccountId }
    } else null
    val counterpartyDisplay = counterpartyAccount?.name ?: counterpartyCard?.let { "${it.name} (•••• ${it.last4Digits})" }

    var showEditDialog by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val formatINR: (Double) -> String = { amt ->
        "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(amt)
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Transaction Details", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color(0xFF64748B))
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val badgeText = when {
                                isCardPayment -> "CREDIT CARD BILL PAYMENT"
                                isInternal -> "INTERNAL TRANSFER"
                                else -> tx.type
                            }
                            val badgeColor = when {
                                isCardPayment -> Color(0xFF7C3AED)
                                isInternal -> Color(0xFF1D4ED8)
                                isExpense -> Color(0xFF93000A)
                                else -> Color(0xFF059669)
                            }
                            val badgeBg = when {
                                isCardPayment -> Color(0xFFF3E8FF)
                                isInternal -> Color(0xFFEFF6FF)
                                isExpense -> Color(0xFFFFDAD6)
                                else -> Color(0xFFD1FAE5)
                            }
                            Surface(
                                color = badgeBg,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = badgeText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = badgeColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }

                            if (isSplit) {
                                Surface(
                                    color = Color(0xFFEDE9FE),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = "SPLIT (${splits.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF6D28D9),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = (if (isExpense) "- " else if (isInternal) "" else "+ ") + formatINR(tx.amount),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF0F172A)
                        )
                        val displayTitle = if (isCardPayment) {
                            "Credit Card Bill Payment"
                        } else if (isInternal) {
                            val bankOrAccName = acc?.bankName?.ifEmpty { acc.name } ?: acc?.name ?: "Bank Account"
                            val last4 = if (acc != null && acc.last4Digits.isNotEmpty()) acc.last4Digits else tx.last4Digits
                            if (last4.isNotEmpty()) "$bankOrAccName ending $last4" else bankOrAccName
                        } else tx.merchant.ifEmpty { "Transaction" }
                        Text(text = displayTitle, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155), modifier = Modifier.padding(top = 2.dp))
                    }

                    if (isInternal) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isCardPayment) Color(0xFFF5F3FF) else Color(0xFFEFF6FF)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (isCardPayment) "CREDIT CARD BILL PAYMENT" else "TRANSFER FLOW",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isCardPayment) Color(0xFF7C3AED) else Color(0xFF1D4ED8)
                                )
                                val thisAccName = acc?.name ?: "Account"
                                val otherName = counterpartyDisplay ?: if (isCardPayment) "Credit Card" else "Unresolved Account"
                                val flow = if (isOutgoing) "$thisAccName → $otherName" else "$otherName → $thisAccName"
                                Text(
                                    text = flow,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCardPayment) Color(0xFF5B21B6) else Color(0xFF1E3A8A)
                                )
                                Text(
                                    text = formatINR(tx.amount),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isCardPayment) Color(0xFF7C3AED) else (if (isOutgoing) Color(0xFFDC2626) else Color(0xFF059669))
                                )
                            }
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isInternal) {
                                DetailRow("Category", "Internal Transfer")
                                DetailRow("Transaction Type", if (isCardPayment) "Credit Card Bill Payment" else "Internal Transfer")
                                DetailRow("Source", when (tx.source) {
                                    "SMS" -> "SMS"
                                    "NOTIFICATION" -> "Notification"
                                    "EMAIL" -> "Email"
                                    else -> "Manual"
                                })
                                val thisAccName = acc?.name ?: "Account"
                                val thisAccDigits = if (tx.last4Digits.isNotEmpty()) tx.last4Digits else acc?.last4Digits ?: ""
                                val thisAccDisplay = if (thisAccDigits.isNotEmpty()) "$thisAccName (• $thisAccDigits)" else thisAccName
                                val thisBank = acc?.bankName?.ifEmpty { acc.name } ?: ""
                                DetailRow("Account") {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (thisBank.isNotEmpty() && thisBank != "Cash" && thisBank != "Manual") {
                                             BankLogo(bankName = thisBank, size = 18.dp, shapeRadius = 4.dp)
                                        }
                                        Text(text = thisAccDisplay, fontSize = 11.sp, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (isCardPayment) {
                                    val fromDisplay = if (isOutgoing) thisAccDisplay else (counterpartyDisplay ?: "Payment Account")
                                    val toDisplay = if (isOutgoing) (counterpartyDisplay ?: "Credit Card") else thisAccDisplay
                                    DetailRow("From Account", fromDisplay)
                                    DetailRow("To Credit Card", toDisplay)
                                } else if (counterpartyDisplay != null) {
                                    val fromDisplay = if (isOutgoing) thisAccDisplay else counterpartyDisplay
                                    val toDisplay = if (isOutgoing) counterpartyDisplay else thisAccDisplay
                                    DetailRow("From Account", fromDisplay)
                                    DetailRow("To Account", toDisplay)
                                } else {
                                    DetailRow("Counterparty", "Unresolved / Needs Review")
                                }
                            } else if (!isSplit) {
                                DetailRow("Category", "${cat?.icon ?: "📁"} ${cat?.let { getBilingualName(it.name, it.nameHindi) } ?: "General"}")
                                DetailRow("Transaction Type", if (isIncome) "Income" else "Expense")
                                if (!isIncome) {
                                    DetailRow("Expense Included", if (tx.isExpense) "ON (Included in Totals)" else "OFF (Excluded from Totals)")
                                }
                                if (subcat != null) {
                                    DetailRow("Subcategory", "${subcat.icon} ${getBilingualName(subcat.name, subcat.nameHindi)}")
                                }
                                DetailRow("Source", when (tx.source) {
                                    "SMS" -> "SMS"
                                    "NOTIFICATION" -> "Notification"
                                    "EMAIL" -> "Email"
                                    else -> "Manual"
                                })
                                val bankDisplayName = acc?.bankName?.ifEmpty { acc.name } ?: ""
                                DetailRow("Account") {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (bankDisplayName.isNotEmpty() && bankDisplayName != "Cash" && bankDisplayName != "Manual") {
                                            BankLogo(bankName = bankDisplayName, size = 18.dp, shapeRadius = 4.dp)
                                        }
                                        Text(text = acc?.name ?: "Manual", fontSize = 11.sp, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                DetailRow("Category", "Split Allocation (${splits.size} parts)")
                                DetailRow("Transaction Type", "Expense")
                                DetailRow("Source", when (tx.source) {
                                    "SMS" -> "SMS"
                                    "NOTIFICATION" -> "Notification"
                                    "EMAIL" -> "Email"
                                    else -> "Manual"
                                })
                                DetailRow("Account", acc?.name ?: "Manual")
                            }

                            DetailRow("Date & Time", "${formatDateNice(tx.date)} at ${tx.time}")
                            if (!isInternal) {
                                val digits = if (tx.last4Digits.isNotEmpty()) tx.last4Digits else acc?.last4Digits ?: ""
                                if (digits.isNotEmpty()) {
                                    DetailRow("Last 4 Digits", "• $digits")
                                }
                            }
                            DetailRow("Payment Method", tx.paymentMethod.ifEmpty { acc?.type ?: "Manual" })
                            if (tx.transactionReference.isNotEmpty()) {
                                DetailRow("Reference ID", tx.transactionReference)
                            }
                            if (tx.note.isNotEmpty()) {
                                DetailRow("Note", tx.note)
                            }
                        }
                    }

                    // Split Details Card if transaction is split
                    if (isSplit) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(text = "Split Breakdown", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                splits.forEachIndexed { idx, s ->
                                    val sCat = categories.find { it.id == s.categoryId }
                                    val sSub = subcategories.find { it.id == s.subcategoryId }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(text = "${sCat?.icon ?: "📁"} ${sCat?.let { getBilingualName(it.name, it.nameHindi) } ?: "Category"}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                            if (sSub != null || s.note.isNotEmpty()) {
                                                Text(
                                                    text = listOfNotNull(sSub?.let { "• ${getBilingualName(it.name, it.nameHindi)}" }, s.note.takeIf { it.isNotEmpty() }).joinToString(" "),
                                                    fontSize = 10.sp,
                                                    color = Color(0xFF64748B)
                                                )
                                            }
                                        }
                                        Text(text = formatINR(s.amount), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                    }
                                    if (idx < splits.size - 1) {
                                        HorizontalDivider(color = Color(0xFFCBD5E1))
                                    }
                                }
                            }
                        }
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showDeleteConfirmDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Delete", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { showEditDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Edit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        if (isExpense) {
                            Button(
                                onClick = { showSplitDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1.2f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                            ) {
                                Icon(imageVector = Icons.Default.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = if (isSplit) "Edit Split" else "Split", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Transaction", fontWeight = FontWeight.Bold) },
            text = { Text("Delete this transaction? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTransaction(tx.id)
                        showDeleteConfirmDialog = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showEditDialog) {
        EditTransactionDialog(
            tx = tx,
            viewModel = viewModel,
            onDismiss = { showEditDialog = false }
        )
    }

    if (showSplitDialog) {
        SplitExpenseDialog(
            tx = tx,
            viewModel = viewModel,
            onDismiss = { showSplitDialog = false }
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Medium)
        Text(text = value, fontSize = 11.sp, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DetailRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Medium)
        content()
    }
}
