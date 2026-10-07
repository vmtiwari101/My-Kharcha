package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.data.dao.SplitOperationResult
import com.example.viewmodel.KharchaViewModel
import java.text.NumberFormat
import java.util.Locale

private data class SplitDraft(
    val id: String,
    var amountText: String,
    var categoryId: String,
    var subcategoryId: String,
    var note: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitExpenseDialog(
    tx: TransactionEntity,
    viewModel: KharchaViewModel,
    onDismiss: () -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    val subcategories by viewModel.subcategories.collectAsState()
    val allSplits by viewModel.transactionSplits.collectAsState()

    val existingSplits = remember(allSplits, tx.id) {
        allSplits.filter { it.transactionId == tx.id }
    }

    val splitCategories = when {
        tx.isInternalTransfer || tx.type == "INTERNAL_TRANSFER" ||
            tx.transactionType in setOf(
                "INTERNAL_TRANSFER",
                "CARD_PAYMENT",
                "CREDIT_CARD_PAYMENT",
                "CREDIT_CARD_BILL_PAYMENT"
            ) -> categories
        tx.type == "INCOME" -> categories.filter { it.isIncome }
        else -> categories.filter { !it.isIncome }
    }

    // Initialize drafts
    val drafts = remember {
        mutableStateListOf<SplitDraft>().apply {
            if (existingSplits.isNotEmpty()) {
                existingSplits.forEach { s ->
                    add(
                        SplitDraft(
                            id = s.id,
                            amountText = if (s.amount % 1 == 0.0) s.amount.toLong().toString() else s.amount.toString(),
                            categoryId = s.categoryId,
                            subcategoryId = s.subcategoryId,
                            note = s.note
                        )
                    )
                }
            } else {
                val defaultCat = splitCategories.firstOrNull { it.id == tx.categoryId }?.id
                    ?: splitCategories.firstOrNull()?.id ?: tx.categoryId
                val defaultSub = subcategories.firstOrNull { it.categoryId == defaultCat }?.id ?: tx.subcategoryId
                val halfAmt = tx.amount / 2.0
                add(
                    SplitDraft(
                        id = "split-1",
                        amountText = if (halfAmt % 1 == 0.0) halfAmt.toLong().toString() else halfAmt.toString(),
                        categoryId = defaultCat,
                        subcategoryId = defaultSub,
                        note = ""
                    )
                )
                add(
                    SplitDraft(
                        id = "split-2",
                        amountText = if (halfAmt % 1 == 0.0) halfAmt.toLong().toString() else halfAmt.toString(),
                        categoryId = splitCategories.getOrNull(1)?.id ?: defaultCat,
                        subcategoryId = "",
                        note = ""
                    )
                )
            }
        }
    }

    val totalAmount = kotlin.math.abs(tx.amount)
    val allocatedAmount = drafts.sumOf {
        it.amountText.toDoubleOrNull()?.takeIf { amount -> amount.isFinite() } ?: 0.0
    }
    val remainingAmount = totalAmount - allocatedAmount
    val hasOnlyPositiveAmounts = drafts.all { draft ->
        draft.amountText.toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } == true
    }
    val isValidSplit = drafts.size >= 2 && hasOnlyPositiveAmounts &&
        totalAmount > 0.0 && Math.abs(remainingAmount) < 0.01
    var splitSaveError by remember(tx.id) { mutableStateOf<String?>(null) }

    val formatINR: (Double) -> String = { amt ->
        "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(amt)
    }

    var activeCatModalTargetDraftIndex by remember { mutableStateOf<Int?>(null) }
    var activeSubcatModalTargetDraftIndex by remember { mutableStateOf<Int?>(null) }

    androidx.activity.compose.BackHandler(enabled = activeSubcatModalTargetDraftIndex != null) {
        activeSubcatModalTargetDraftIndex = null
    }
    androidx.activity.compose.BackHandler(enabled = activeCatModalTargetDraftIndex != null) {
        activeCatModalTargetDraftIndex = null
    }
    androidx.activity.compose.BackHandler(enabled = activeSubcatModalTargetDraftIndex == null && activeCatModalTargetDraftIndex == null) {
        onDismiss()
    }
    var newCatName by remember { mutableStateOf("") }
    var newCatNameHindi by remember { mutableStateOf("") }
    var newSubcatName by remember { mutableStateOf("") }
    var newSubcatNameHindi by remember { mutableStateOf("") }

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
                        Column {
                            Text(text = "Split Transaction", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                            Text(text = "Merchant: ${tx.merchant}", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color(0xFF64748B))
                        }
                    }

                    if (splitSaveError != null) {
                        Text(
                            text = splitSaveError!!,
                            color = Color(0xFFB91C1C),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (!hasOnlyPositiveAmounts) {
                        Text(
                            text = "Each split amount must be a valid number greater than zero.",
                            color = Color(0xFFB91C1C),
                            fontSize = 12.sp
                        )
                    }

                    // Calculation Banner
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                Math.abs(remainingAmount) < 0.01 -> Color(0xFFECFDF5)
                                remainingAmount < 0 -> Color(0xFFFEF2F2)
                                else -> Color(0xFFFFFBEB)
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Original Total:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                Text(text = formatINR(totalAmount), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Allocated:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                Text(text = formatINR(allocatedAmount), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2563EB))
                            }
                            HorizontalDivider(color = Color.Black.copy(alpha = 0.08f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                when {
                                    Math.abs(remainingAmount) < 0.01 -> {
                                        Text(text = "Status: Balanced ✓", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF059669))
                                        Text(text = "Ready to Save", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                                    }
                                    remainingAmount < 0 -> {
                                        Text(text = "Overallocated by:", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFDC2626))
                                        Text(text = formatINR(-remainingAmount), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFDC2626))
                                    }
                                    else -> {
                                        Text(text = "Remaining to Allocate:", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFD97706))
                                        Text(text = formatINR(remainingAmount), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFD97706))
                                    }
                                }
                            }
                        }
                    }

                    // Draft Split Items List
                    drafts.forEachIndexed { index, draft ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "Split ${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                    if (drafts.size > 2) {
                                        IconButton(
                                            onClick = { drafts.removeAt(index) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Remove Split", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = draft.amountText,
                                        onValueChange = { newAmt ->
                                            splitSaveError = null
                                            drafts[index] = draft.copy(amountText = newAmt)
                                        },
                                        label = { Text("Amount (₹)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )

                                    OutlinedTextField(
                                        value = draft.note,
                                        onValueChange = { newNote ->
                                            splitSaveError = null
                                            drafts[index] = draft.copy(note = newNote)
                                        },
                                        label = { Text("Note (Optional)") },
                                        modifier = Modifier.weight(1.2f),
                                        singleLine = true
                                    )
                                }

                                // Category Dropdown
                                var catExpanded by remember { mutableStateOf(false) }
                                val selectedCat = categories.find { it.id == draft.categoryId }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = selectedCat?.let { "${it.icon} ${getBilingualName(it.name, it.nameHindi)}" } ?: "Select Category",
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Category") },
                                        trailingIcon = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = { activeCatModalTargetDraftIndex = index },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Category", tint = Color(0xFF059669), modifier = Modifier.size(16.dp))
                                                }
                                                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.clickable { catExpanded = true })
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { catExpanded = true },
                                        enabled = false,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            disabledTextColor = Color(0xFF0F172A),
                                            disabledBorderColor = Color(0xFFCBD5E1),
                                            disabledLabelColor = Color(0xFF64748B)
                                        )
                                    )

                                    DropdownMenu(
                                        expanded = catExpanded,
                                        onDismissRequest = { catExpanded = false }
                                    ) {
                                        splitCategories.forEach { c ->
                                            DropdownMenuItem(
                                                text = { Text("${c.icon} ${getBilingualName(c.name, c.nameHindi)}") },
                                                onClick = {
                                                    splitSaveError = null
                                                    drafts[index] = draft.copy(categoryId = c.id, subcategoryId = "")
                                                    catExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // Subcategory Dropdown
                                var subcatExpanded by remember { mutableStateOf(false) }
                                val filteredSubs = subcategories.filter { it.categoryId == draft.categoryId }
                                val selectedSub = subcategories.find { it.id == draft.subcategoryId }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = selectedSub?.let { "${it.icon} ${getBilingualName(it.name, it.nameHindi)}" } ?: "Select Subcategory (Optional)",
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Subcategory") },
                                        trailingIcon = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = { activeSubcatModalTargetDraftIndex = index },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Subcategory", tint = Color(0xFF059669), modifier = Modifier.size(16.dp))
                                                }
                                                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.clickable { subcatExpanded = true })
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { subcatExpanded = true },
                                        enabled = false,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            disabledTextColor = Color(0xFF0F172A),
                                            disabledBorderColor = Color(0xFFCBD5E1),
                                            disabledLabelColor = Color(0xFF64748B)
                                        )
                                    )

                                    DropdownMenu(
                                        expanded = subcatExpanded,
                                        onDismissRequest = { subcatExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("None") },
                                            onClick = {
                                                drafts[index] = draft.copy(subcategoryId = "")
                                                subcatExpanded = false
                                            }
                                        )
                                        filteredSubs.forEach { sub ->
                                            DropdownMenuItem(
                                                text = { Text("${sub.icon} ${getBilingualName(sub.name, sub.nameHindi)}") },
                                                onClick = {
                                                    splitSaveError = null
                                                    drafts[index] = draft.copy(subcategoryId = sub.id)
                                                    subcatExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Add Split Button
                    OutlinedButton(
                        onClick = {
                            val nextCat = splitCategories.firstOrNull()?.id ?: tx.categoryId
                            val fillAmt = if (remainingAmount > 0) remainingAmount else 0.0
                            drafts.add(
                                SplitDraft(
                                    id = "split-${System.currentTimeMillis()}",
                                    amountText = if (fillAmt % 1 == 0.0) fillAmt.toLong().toString() else fillAmt.toString(),
                                    categoryId = nextCat,
                                    subcategoryId = "",
                                    note = ""
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Add Another Split Item", fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (existingSplits.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.removeTransactionSplits(tx.id) { result ->
                                        if (result == SplitOperationResult.SAVED) {
                                            onDismiss()
                                        } else {
                                            splitSaveError = "Could not remove the split. The transaction was not changed."
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                            ) {
                                Text(text = "Unsplit", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = {
                                if (isValidSplit) {
                                    val now = System.currentTimeMillis().toString()
                                    val splitEntities = drafts.mapIndexed { idx, d ->
                                        TransactionSplitEntity(
                                            id = "split-${tx.id}-$idx-${System.currentTimeMillis()}",
                                            transactionId = tx.id,
                                            categoryId = d.categoryId,
                                            subcategoryId = d.subcategoryId,
                                            amount = d.amountText.toDoubleOrNull() ?: 0.0,
                                            note = d.note,
                                            createdAt = now,
                                            updatedAt = now
                                        )
                                    }
                                    viewModel.saveTransactionSplits(tx.id, splitEntities) { result ->
                                        if (result == SplitOperationResult.SAVED) {
                                            onDismiss()
                                        } else {
                                            splitSaveError = when (result) {
                                                SplitOperationResult.INVALID_AMOUNT ->
                                                    "Each split amount must be greater than zero."
                                                SplitOperationResult.INVALID_TOTAL ->
                                                    "Split amounts must equal the transaction total."
                                                SplitOperationResult.INVALID_REFERENCE ->
                                                    "A selected category or subcategory is no longer available to this user."
                                                SplitOperationResult.NOT_FOUND ->
                                                    "This transaction is no longer available."
                                                SplitOperationResult.NOT_AUTHENTICATED ->
                                                    "Sign in again before saving transaction splits."
                                                else -> "Could not save the split. The transaction was not changed."
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = isValidSplit,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF059669),
                                disabledContainerColor = Color(0xFFCBD5E1)
                            )
                        ) {
                            Text(text = "Save Split", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Modal to create new Category for a specific split item
    if (activeCatModalTargetDraftIndex != null) {
        val targetIdx = activeCatModalTargetDraftIndex!!
        AlertDialog(
            onDismissRequest = { activeCatModalTargetDraftIndex = null },
            title = { Text("Add Category for Split ${targetIdx + 1}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            val newCatId = viewModel.addCategory(newCatName.trim(), newCatNameHindi.trim(), "📁", "#10B981", false)
                            if (targetIdx in drafts.indices) {
                                drafts[targetIdx] = drafts[targetIdx].copy(categoryId = newCatId, subcategoryId = "")
                            }
                            newCatName = ""
                            newCatNameHindi = ""
                            activeCatModalTargetDraftIndex = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) { Text("Save & Select") }
            },
            dismissButton = {
                TextButton(onClick = { activeCatModalTargetDraftIndex = null }) { Text("Cancel") }
            }
        )
    }

    // Modal to create new Subcategory for a specific split item
    if (activeSubcatModalTargetDraftIndex != null) {
        val targetIdx = activeSubcatModalTargetDraftIndex!!
        val parentCatId = drafts.getOrNull(targetIdx)?.categoryId ?: tx.categoryId
        AlertDialog(
            onDismissRequest = { activeSubcatModalTargetDraftIndex = null },
            title = { Text("Add Subcategory for Split ${targetIdx + 1}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        if (newSubcatName.isNotBlank() && parentCatId.isNotEmpty()) {
                            val newSubId = viewModel.addSubcategory(parentCatId, newSubcatName.trim(), newSubcatNameHindi.trim(), "📌", "#059669")
                            if (targetIdx in drafts.indices) {
                                drafts[targetIdx] = drafts[targetIdx].copy(subcategoryId = newSubId)
                            }
                            newSubcatName = ""
                            newSubcatNameHindi = ""
                            activeSubcatModalTargetDraftIndex = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) { Text("Save & Select") }
            },
            dismissButton = {
                TextButton(onClick = { activeSubcatModalTargetDraftIndex = null }) { Text("Cancel") }
            }
        )
    }
}
