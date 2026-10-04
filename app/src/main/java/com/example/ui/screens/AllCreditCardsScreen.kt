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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.CreditCardColorResolver
import com.example.utils.TransactionIngestionEngine
import com.example.viewmodel.KharchaViewModel
import java.text.NumberFormat
import java.util.Locale

data class UnifiedCreditCard(
    val id: String,
    val accountId: String,
    val cardId: String?,
    val bankName: String,
    val cardName: String,
    val last4Digits: String,
    val creditLimit: Double,
    val outstandingAmount: Double,
    val billingDate: Int,
    val dueDate: Int,
    val colour: String,
    val isAutoCreated: Boolean,
    val accountEntity: AccountEntity,
    val linkedTransactions: List<TransactionEntity>
)

// Credit Card Transaction helpers for accurate outstanding calculation
private fun isCreditCardPurchase(tx: TransactionEntity, cardId: String?, accountId: String, last4: String): Boolean {
    val isDebit = (tx.direction == "DEBIT" || tx.type == "EXPENSE") && !tx.isInternalTransfer && tx.transactionType != "INTERNAL_TRANSFER" && tx.transactionType != "CARD_PAYMENT" && tx.transactionType != "CREDIT_CARD_BILL_PAYMENT"
    if (!isDebit) return false

    if (cardId != null && tx.cardId == cardId) return true
    if (accountId.isNotEmpty() && tx.accountId == accountId) return true
    if (last4.length == 4 && tx.last4Digits == last4) {
        val text = "${tx.note} ${tx.paymentMethod} ${tx.merchant}".lowercase(Locale.ENGLISH)
        return text.contains("credit card") || text.contains("credit-card") || text.contains("cc ending") || text.contains("card ending") || tx.paymentMethod.contains("card", true)
    }
    return false
}

private fun isCreditCardPaymentOrRefund(tx: TransactionEntity, cardId: String?, accountId: String, last4: String): Boolean {
    // 1. Credit Card Bill Payment specifically directed to this credit card
    val isPaymentIdentifier = tx.transactionType == "CARD_PAYMENT" || tx.transactionType == "CREDIT_CARD_BILL_PAYMENT" || tx.merchant.contains("Credit Card Bill Payment", true) ||
            tx.note.contains("credit card payment", true) || tx.note.contains("cc payment", true) ||
            tx.note.contains("payment received towards your credit card", true) || tx.note.contains("paid towards credit card", true)

    if (isPaymentIdentifier) {
        if (accountId.isNotEmpty() && tx.counterpartyAccountId == accountId) return true
        if (cardId != null && tx.counterpartyAccountId == cardId) return true
        if (accountId.isNotEmpty() && tx.accountId == accountId) return true
        if (last4.length == 4 && tx.last4Digits == last4) return true
    }

    // 2. Direct credit/refund/cashback credited specifically to this credit card (not general bank income)
    val isCredit = (tx.direction == "CREDIT" || tx.type == "INCOME") && !tx.isInternalTransfer && tx.transactionType != "INTERNAL_TRANSFER" && tx.transactionType != "CREDIT_CARD_BILL_PAYMENT"
    if (isCredit) {
        if (cardId != null && tx.cardId == cardId) return true
        if (accountId.isNotEmpty() && tx.accountId == accountId) return true
        if (last4.length == 4 && tx.last4Digits == last4) {
            val text = "${tx.note} ${tx.paymentMethod} ${tx.merchant}".lowercase(Locale.ENGLISH)
            return text.contains("refund") || text.contains("cashback") || text.contains("reversal") || text.contains("credit card")
        }
    }

    return false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllCreditCardsScreen(
    viewModel: KharchaViewModel,
    onBack: () -> Unit,
    onEditCard: (AccountEntity) -> Unit,
    onSelectCard: (UnifiedCreditCard) -> Unit = { onEditCard(it.accountEntity) }
) {
    BackHandler { onBack() }

    val accounts by viewModel.accounts.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val transactions by viewModel.transactions.collectAsState()

    val formatINR: (Double) -> String = { amt ->
        "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(amt)
    }

    val activeAccounts = accounts.filter { it.isActive }
    val creditCardAccounts = activeAccounts.filter { it.type.equals("Credit Card", ignoreCase = true) }
    val creditCardEntities = cards.filter { it.type.equals("Credit Card", ignoreCase = true) }

    // Deduplication logic: Group AccountEntity and CardEntity representing the same physical card
    // Key rule: An AccountEntity and CardEntity representing the same bank + last4 are ONE physical card.
    val unifiedCards = remember(creditCardAccounts, creditCardEntities, transactions, activeAccounts) {
        val list = mutableListOf<UnifiedCreditCard>()
        val processedAccountIds = mutableSetOf<String>()
        val processedCardIds = mutableSetOf<String>()
        val processedBankLast4 = mutableSetOf<String>()

        // 1. Process CardEntity records
        for (card in creditCardEntities) {
            if (processedCardIds.contains(card.id)) continue

            // Find matching account by ID or by bank + last4
            val parentAcc = activeAccounts.find { it.id == card.accountId }
                ?: creditCardAccounts.find { acc ->
                    acc.last4Digits.isNotEmpty() && acc.last4Digits == card.last4Digits &&
                    TransactionIngestionEngine.isBankNameMatch(acc.bankName.ifEmpty { acc.name }, card.name)
                }

            val derivedBankName = when {
                !parentAcc?.bankName.isNullOrBlank() -> parentAcc!!.bankName
                !parentAcc?.name.isNullOrBlank() && parentAcc!!.name != card.name -> parentAcc!!.name
                card.name.contains("HDFC", true) -> "HDFC Bank"
                card.name.contains("SBI", true) -> "SBI"
                card.name.contains("ICICI", true) -> "ICICI Bank"
                card.name.contains("Axis", true) -> "Axis Bank"
                card.name.contains("Kotak", true) -> "Kotak Bank"
                card.name.contains("IDFC", true) -> "IDFC FIRST Bank"
                else -> card.name
            }

            val last4 = card.last4Digits.ifEmpty { parentAcc?.last4Digits ?: "" }
            val bankKey = "${derivedBankName.lowercase(Locale.ENGLISH).replace(" ", "")}_$last4"

            if (last4.isNotEmpty() && processedBankLast4.contains(bankKey)) {
                processedCardIds.add(card.id)
                continue
            }

            val isAuto = TransactionIngestionEngine.isAutoCreatedCard(card, parentAcc)
            val effectiveAccId = parentAcc?.id ?: card.accountId

            // Find all transactions linked to this physical credit card from centralized transactions
            val linkedTxs = transactions.filter { tx ->
                isCreditCardPurchase(tx, card.id, effectiveAccId, last4) || isCreditCardPaymentOrRefund(tx, card.id, effectiveAccId, last4)
            }

            // If auto-created and zero linked transactions -> ORPHAN! Exclude from active list.
            if (isAuto && linkedTxs.isEmpty()) {
                processedCardIds.add(card.id)
                if (parentAcc != null) processedAccountIds.add(parentAcc.id)
                continue
            }

            val expenseTotal = linkedTxs.filter { isCreditCardPurchase(it, card.id, effectiveAccId, last4) }.sumOf { it.amount }
            val paymentTotal = linkedTxs.filter { isCreditCardPaymentOrRefund(it, card.id, effectiveAccId, last4) }.sumOf { it.amount }
            val txCalculatedOutstanding = Math.max(0.0, expenseTotal - paymentTotal)
            val storedOutstanding = maxOf(parentAcc?.outstandingAmount ?: 0.0, card.outstandingAmount)
            val effectiveOutstanding = if (txCalculatedOutstanding > 0.0) txCalculatedOutstanding else storedOutstanding

            val effectiveLimit = maxOf(parentAcc?.creditLimit ?: 0.0, card.creditLimit)
            val billing = if (card.billingDate > 0) card.billingDate else (parentAcc?.billingDate ?: 0)
            val due = if (card.dueDate > 0) card.dueDate else (parentAcc?.dueDate ?: 0)

            val accEntity = parentAcc ?: AccountEntity(
                id = card.accountId.ifEmpty { card.id },
                name = card.name,
                type = "Credit Card",
                bankName = derivedBankName,
                last4Digits = last4,
                creditLimit = effectiveLimit,
                outstandingAmount = effectiveOutstanding,
                billingDate = billing,
                dueDate = due,
                colour = parentAcc?.colour.orEmpty()
            )

            list.add(
                UnifiedCreditCard(
                    id = "card-${card.id}",
                    accountId = accEntity.id,
                    cardId = card.id,
                    bankName = derivedBankName,
                    cardName = card.name,
                    last4Digits = last4,
                    creditLimit = effectiveLimit,
                    outstandingAmount = effectiveOutstanding,
                    billingDate = billing,
                    dueDate = due,
                    colour = parentAcc?.colour.orEmpty(),
                    isAutoCreated = isAuto,
                    accountEntity = accEntity,
                    linkedTransactions = linkedTxs
                )
            )

            processedCardIds.add(card.id)
            if (parentAcc != null) processedAccountIds.add(parentAcc.id)
            if (last4.isNotEmpty()) processedBankLast4.add(bankKey)
        }

        // 2. Process standalone credit card AccountEntity records that were not paired with CardEntity
        for (acc in creditCardAccounts) {
            if (processedAccountIds.contains(acc.id)) continue

            val last4 = acc.last4Digits
            val bankName = acc.bankName.ifEmpty { acc.name }
            val bankKey = "${bankName.lowercase(Locale.ENGLISH).replace(" ", "")}_$last4"

            if (last4.isNotEmpty() && processedBankLast4.contains(bankKey)) {
                processedAccountIds.add(acc.id)
                continue
            }

            val isAuto = TransactionIngestionEngine.isAutoCreatedCreditCardAccount(acc)

            // Find linked transactions
            val linkedTxs = transactions.filter { tx ->
                isCreditCardPurchase(tx, null, acc.id, last4) || isCreditCardPaymentOrRefund(tx, null, acc.id, last4)
            }

            // If auto-created and zero transactions -> ORPHAN! Exclude.
            if (isAuto && linkedTxs.isEmpty()) {
                processedAccountIds.add(acc.id)
                continue
            }

            val expenseTotal = linkedTxs.filter { isCreditCardPurchase(it, null, acc.id, last4) }.sumOf { it.amount }
            val paymentTotal = linkedTxs.filter { isCreditCardPaymentOrRefund(it, null, acc.id, last4) }.sumOf { it.amount }
            val txCalculatedOutstanding = Math.max(0.0, expenseTotal - paymentTotal)
            val effectiveOutstanding = if (txCalculatedOutstanding > 0.0) txCalculatedOutstanding else acc.outstandingAmount

            list.add(
                UnifiedCreditCard(
                    id = "acc-${acc.id}",
                    accountId = acc.id,
                    cardId = null,
                    bankName = bankName,
                    cardName = acc.name,
                    last4Digits = last4,
                    creditLimit = acc.creditLimit,
                    outstandingAmount = effectiveOutstanding,
                    billingDate = acc.billingDate,
                    dueDate = acc.dueDate,
                    colour = acc.colour,
                    isAutoCreated = isAuto,
                    accountEntity = acc,
                    linkedTransactions = linkedTxs
                )
            )

            processedAccountIds.add(acc.id)
            if (last4.isNotEmpty()) processedBankLast4.add(bankKey)
        }

        list
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "All Credit Cards",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFF0F172A)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            val totalCreditLimit = unifiedCards.filter { it.creditLimit > 0 }.sumOf { it.creditLimit }
            val totalOutstanding = unifiedCards.sumOf { it.outstandingAmount }
            val totalAvailable = if (totalCreditLimit > 0) Math.max(0.0, totalCreditLimit - totalOutstanding) else 0.0

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "CREDIT PORTFOLIO SUMMARY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF94A3B8)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(text = "Total Outstanding", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Text(
                                    text = formatINR(totalOutstanding),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFF87171)
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "Available Limit", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Text(
                                    text = if (totalCreditLimit > 0) formatINR(totalAvailable) else "N/A",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (totalCreditLimit > 0) Color(0xFF34D399) else Color(0xFF94A3B8)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(text = "Total Limit", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Text(
                                    text = if (totalCreditLimit > 0) formatINR(totalCreditLimit) else "N/A",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (totalCreditLimit > 0) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            }

            if (unifiedCards.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreditCard,
                                contentDescription = null,
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "No credit cards found",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF334155)
                            )
                            Text(
                                text = "Add a credit card from Accounts, or active cards will appear automatically when imported from your bank alerts.",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                }
            } else {
                items(unifiedCards, key = { it.id }) { card ->
                    CreditCardTile(
                        bankName = card.bankName,
                        cardName = card.cardName,
                        last4Digits = card.last4Digits,
                        creditLimit = card.creditLimit,
                        outstandingAmount = card.outstandingAmount,
                        billingDate = card.billingDate,
                        dueDate = card.dueDate,
                        colour = card.colour,
                        transactionCount = card.linkedTransactions.size,
                        onClick = { onSelectCard(card) },
                        onEdit = { onEditCard(card.accountEntity) }
                    )
                }
            }
        }
    }
}

@Composable
fun CreditCardTile(
    bankName: String,
    cardName: String,
    last4Digits: String,
    creditLimit: Double,
    outstandingAmount: Double,
    billingDate: Int,
    dueDate: Int,
    colour: String,
    transactionCount: Int,
    onClick: () -> Unit,
    onEdit: () -> Unit = onClick
) {
    val formatINR: (Double) -> String = { amt ->
        "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(amt)
    }

    val palette = CreditCardColorResolver.resolvePalette(
        bankName = bankName,
        cardName = cardName,
        last4Digits = last4Digits,
        rawColour = colour
    )
    val gradientBrush = palette.gradientBrush

    val availableLimit = if (creditLimit > 0) Math.max(0.0, creditLimit - outstandingAmount) else 0.0
    val utilization = if (creditLimit > 0) (outstandingAmount / creditLimit) * 100.0 else 0.0

    Card(
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top Header Row: Bank Logo, Bank Name, Card Name, Badge & Outstanding Due
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
                        BankLogoBadge(bankName = bankName)
                        Column {
                            Text(
                                text = bankName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = cardName,
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Surface(
                            color = Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "CREDIT CARD",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatINR(outstandingAmount),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "Outstanding Due",
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                // Middle Row: Last 4 Card Number & Transaction Count Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayLast4 = if (last4Digits.length == 4) last4Digits else if (last4Digits.isNotBlank()) last4Digits.takeLast(4) else "----"
                    Text(
                        text = "•••• •••• •••• $displayLast4",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f),
                        letterSpacing = 1.sp
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "$transactionCount transactions",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Card",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Bottom Row: Billing & Due Dates, Available Credit & Total Limit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = if (billingDate in 1..31) "Billing: ${billingDate}th of month" else "Billing: N/A",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Text(
                            text = if (dueDate in 1..31) "Due Date: ${dueDate}th" else "Due Date: N/A",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        if (creditLimit > 0) {
                            Text(
                                text = "Avail: ${formatINR(availableLimit)}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399)
                            )
                            Text(
                                text = "Limit: ${formatINR(creditLimit)} (${String.format(Locale.US, "%.0f", utilization)}% used)",
                                fontSize = 9.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        } else {
                            Text(
                                text = "Total Limit: N/A",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "Tap to view/manage",
                                fontSize = 9.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}
