package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.viewmodel.KharchaViewModel
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    viewModel: KharchaViewModel,
    onOpenAddCategory: () -> Unit,
    onOpenAddSubcategory: () -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    val subcategories by viewModel.subcategories.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val allSplits by viewModel.transactionSplits.collectAsState()

    var selectedCategoryForDetail by remember { mutableStateOf<CategoryEntity?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var currentTab by remember { mutableStateOf("all") } // "all" or "manage"

    // Dialog & Bottom Sheet States
    var subcategoryToOptions by remember { mutableStateOf<SubcategoryEntity?>(null) }
    var subcategoryToMove by remember { mutableStateOf<SubcategoryEntity?>(null) }
    var subcategoryToEdit by remember { mutableStateOf<SubcategoryEntity?>(null) }
    var subcategoryToDelete by remember { mutableStateOf<SubcategoryEntity?>(null) }
    var subcategoryToChangeIcon by remember { mutableStateOf<SubcategoryEntity?>(null) }
    var categoryForNewSubcategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showReorderCategories by remember { mutableStateOf(false) }
    var categoryToMerge by remember { mutableStateOf<CategoryEntity?>(null) }
    var categoryToDeleteWithWarning by remember { mutableStateOf<Pair<CategoryEntity, Int>?>(null) }
    var categoryToDeleteConfirm by remember { mutableStateOf<CategoryEntity?>(null) }

    val formatINR: (Double) -> String = { amt ->
        "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(amt)
    }

    val categoryStats = remember(categories, transactions, allSplits) {
        val stats = mutableMapOf<String, Pair<Int, Double>>()
        categories.forEach { cat ->
            var count = 0
            var total = 0.0
            transactions.forEach { tx ->
                val txSplits = allSplits.filter { it.transactionId == tx.id && it.categoryId == cat.id }
                if (txSplits.isNotEmpty()) {
                    txSplits.forEach { s ->
                        count++
                        total += s.amount
                    }
                } else if (tx.categoryId == cat.id) {
                    count++
                    total += tx.amount
                }
            }
            stats[cat.id] = count to total
        }
        stats
    }

    val filteredCategories = remember(categories, searchQuery) {
        if (searchQuery.isBlank()) categories
        else categories.filter { it.name.contains(searchQuery, ignoreCase = true) || it.nameHindi.contains(searchQuery, ignoreCase = true) }
    }

    if (selectedCategoryForDetail != null) {
        val currentCategory = categories.find { it.id == selectedCategoryForDetail!!.id } ?: selectedCategoryForDetail!!
        val catSubs = subcategories.filter { it.categoryId == currentCategory.id }

        CategorySubcategoriesDetailView(
            category = currentCategory,
            subcategories = catSubs,
            allCategories = categories,
            transactions = transactions,
            allSplits = allSplits,
            formatINR = formatINR,
            onBack = { selectedCategoryForDetail = null },
            onAddSubcategory = { categoryForNewSubcategory = currentCategory },
            onSubcategoryOptions = { sub -> subcategoryToOptions = sub },
            onReorderSubcategories = { reordered -> viewModel.reorderSubcategories(reordered) }
        )
    } else {
        val isDark = isSystemInDarkTheme()
        val bgColor = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
        val cardBg = if (isDark) Color(0xFF1E293B) else Color.White
        val textPrimary = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
        val textSecondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
        val borderCol = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Screen Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Categories", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                    Text(text = "Manage categories & subcategories", fontSize = 11.sp, color = textSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = { showReorderCategories = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(imageVector = Icons.Default.SwapVert, contentDescription = "Reorder Categories", tint = Color(0xFF059669))
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search categories...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp)) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF059669),
                    unfocusedBorderColor = borderCol,
                    focusedContainerColor = cardBg,
                    unfocusedContainerColor = cardBg,
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                listOf("all" to "All Categories", "manage" to "Manage & Reorder").forEach { (tabKey, label) ->
                    val selected = currentTab == tabKey
                    Button(
                        onClick = { currentTab = tabKey },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) (if (isDark) Color(0xFF334155) else Color.White) else Color.Transparent,
                            contentColor = if (selected) (if (isDark) Color(0xFF34D399) else Color(0xFF047857)) else textSecondary
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Categories List
            if (filteredCategories.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(36.dp))
                        Text(text = "No categories found", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                        Button(
                            onClick = { showAddCategoryDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Add Category", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredCategories, key = { it.id }) { cat ->
                        val subs = subcategories.filter { it.categoryId == cat.id }
                        val (txCount, txTotal) = categoryStats[cat.id] ?: (0 to 0.0)
                        val catColor = try {
                            Color(android.graphics.Color.parseColor(cat.colour))
                        } catch (e: Exception) {
                            Color(0xFF10B981)
                        }

                        Card(
                            onClick = { selectedCategoryForDetail = cat },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
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
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(catColor.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = cat.icon, fontSize = 20.sp)
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = getBilingualName(cat.name, cat.nameHindi),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (cat.isIncome) {
                                                Surface(color = Color(0xFFD1FAE5), shape = RoundedCornerShape(4.dp)) {
                                                    Text(text = "Income", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669), modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "$txCount transaction${if (txCount == 1) "" else "s"} • ${subs.size} subcat",
                                            fontSize = 11.sp,
                                            color = textSecondary
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = formatINR(txTotal),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = textPrimary
                                        )
                                    }
                                    if (currentTab == "manage") {
                                        IconButton(
                                            onClick = { categoryForNewSubcategory = cat },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Add, contentDescription = "Add Subcategory", tint = Color(0xFF059669), modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                viewModel.getCategoryTransactionCount(cat.id) { count ->
                                                    if (count > 0) {
                                                        categoryToDeleteWithWarning = cat to count
                                                    } else {
                                                        categoryToDeleteConfirm = cat
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(15.dp))
                                        }
                                    } else {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                            contentDescription = "View Details",
                                            tint = Color(0xFFCBD5E1),
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Add Category Button
            Button(
                onClick = { showAddCategoryDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "+ Add Category", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // Modal: Add Category
    if (showAddCategoryDialog) {
        AddCategoryDialog(
            onDismiss = { showAddCategoryDialog = false },
            onSave = { name, nameHindi, icon, colour, isIncome ->
                viewModel.addCategory(name, nameHindi, icon, colour, isIncome)
                showAddCategoryDialog = false
            }
        )
    }

    // Modal: Reorder Categories
    if (showReorderCategories) {
        ReorderCategoriesDialog(
            categories = categories,
            onDismiss = { showReorderCategories = false },
            onSaveOrder = { reordered ->
                viewModel.reorderCategories(reordered)
                showReorderCategories = false
            }
        )
    }

    // Modal: Add Subcategory
    if (categoryForNewSubcategory != null) {
        AddSubcategoryDialog(
            initialCategory = categoryForNewSubcategory!!,
            allCategories = categories,
            onDismiss = { categoryForNewSubcategory = null },
            onSave = { catId, name, nameHindi, icon, colour ->
                viewModel.addSubcategory(catId, name, nameHindi, icon, colour)
                categoryForNewSubcategory = null
            }
        )
    }

    // Bottom Sheet: Subcategory Options (Screen 3)
    if (subcategoryToOptions != null) {
        val sub = subcategoryToOptions!!
        val parent = categories.find { it.id == sub.categoryId }
        SubcategoryOptionsBottomSheet(
            subcategory = sub,
            parentCategory = parent,
            onDismiss = { subcategoryToOptions = null },
            onEdit = {
                subcategoryToOptions = null
                subcategoryToEdit = sub
            },
            onMove = {
                subcategoryToOptions = null
                subcategoryToMove = sub
            },
            onChangeIcon = {
                subcategoryToOptions = null
                subcategoryToChangeIcon = sub
            },
            onDelete = {
                subcategoryToOptions = null
                subcategoryToDelete = sub
            }
        )
    }

    // Modal: Move Subcategory & Confirmation (Screen 4 & 5)
    if (subcategoryToMove != null) {
        val sub = subcategoryToMove!!
        val currentParent = categories.find { it.id == sub.categoryId }

        MoveSubcategoryFlowDialog(
            subcategory = sub,
            currentParent = currentParent,
            allCategories = categories,
            onDismiss = { subcategoryToMove = null },
            onConfirmMove = { newCatId, moveTx ->
                viewModel.moveSubcategory(sub.id, newCatId, moveTransactions = moveTx)
                subcategoryToMove = null
            }
        )
    }

    // Modal: Edit Subcategory (Screen 6)
    if (subcategoryToEdit != null) {
        val sub = subcategoryToEdit!!
        val currentParent = categories.find { it.id == sub.categoryId }

        EditSubcategoryDialog(
            subcategory = sub,
            currentParent = currentParent,
            allCategories = categories,
            onDismiss = { subcategoryToEdit = null },
            onSave = { newCatId, newName, newNameHindi, newIcon, newColour ->
                if (newCatId != sub.categoryId) {
                    viewModel.moveSubcategory(sub.id, newCatId, moveTransactions = false)
                }
                viewModel.updateSubcategory(sub.copy(
                    categoryId = newCatId,
                    name = newName,
                    nameHindi = newNameHindi,
                    icon = newIcon,
                    colour = newColour
                ))
                subcategoryToEdit = null
            }
        )
    }

    // Modal: Change Icon (Screen 7)
    if (subcategoryToChangeIcon != null) {
        val sub = subcategoryToChangeIcon!!
        ChangeIconDialog(
            currentIcon = sub.icon,
            onDismiss = { subcategoryToChangeIcon = null },
            onSelectIcon = { newIcon ->
                viewModel.updateSubcategory(sub.copy(icon = newIcon))
                subcategoryToChangeIcon = null
            }
        )
    }

    // Modal: Delete Subcategory (Screen 8)
    if (subcategoryToDelete != null) {
        val sub = subcategoryToDelete!!
        DeleteSubcategorySafeDialog(
            subcategory = sub,
            allCategories = categories,
            allSubcategories = subcategories,
            viewModel = viewModel,
            onDismiss = { subcategoryToDelete = null }
        )
    }

    // Modal: Merge Category
    if (categoryToMerge != null) {
        MergeCategoryDialog(
            sourceCategory = categoryToMerge!!,
            allCategories = categories,
            onDismiss = { categoryToMerge = null },
            onConfirmMerge = { targetId ->
                viewModel.mergeCategory(categoryToMerge!!.id, targetId) {
                    categoryToMerge = null
                }
            }
        )
    }

    // Modal: Delete Category Warning
    if (categoryToDeleteWithWarning != null) {
        val (cat, txCount) = categoryToDeleteWithWarning!!
        AlertDialog(
            onDismissRequest = { categoryToDeleteWithWarning = null },
            title = { Text("Category Has Linked Transactions", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "\"${cat.name}\" is linked to $txCount transaction(s). Directly deleting categories with transactions is disabled to prevent data loss.\n\nWould you like to Merge this category into another category instead?",
                    fontSize = 13.sp,
                    color = Color(0xFF334155)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        categoryToDeleteWithWarning = null
                        categoryToMerge = cat
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Merge Category")
                }
            },
            dismissButton = {
                TextButton(onClick = { categoryToDeleteWithWarning = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Modal: Delete Category Confirm (0 Txs)
    if (categoryToDeleteConfirm != null) {
        val cat = categoryToDeleteConfirm!!
        AlertDialog(
            onDismissRequest = { categoryToDeleteConfirm = null },
            title = { Text("Delete Category", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${cat.icon} ${cat.name}\"? This category has no linked transactions.",
                    fontSize = 13.sp,
                    color = Color(0xFF334155)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteCategory(cat.id)
                        categoryToDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { categoryToDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Screen 2: Subcategory List for a specific category
 */
@Composable
fun CategorySubcategoriesDetailView(
    category: CategoryEntity,
    subcategories: List<SubcategoryEntity>,
    allCategories: List<CategoryEntity>,
    transactions: List<com.example.data.entity.TransactionEntity>,
    allSplits: List<com.example.data.entity.TransactionSplitEntity>,
    formatINR: (Double) -> String,
    onBack: () -> Unit,
    onAddSubcategory: () -> Unit,
    onSubcategoryOptions: (SubcategoryEntity) -> Unit,
    onReorderSubcategories: (List<SubcategoryEntity>) -> Unit
) {
    BackHandler(onBack = onBack)

    var searchQuery by remember { mutableStateOf("") }
    var showReorderSubcats by remember { mutableStateOf(false) }

    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val cardBg = if (isDark) Color(0xFF1E293B) else Color.White
    val textPrimary = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textSecondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val borderCol = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)

    val catColor = try {
        Color(android.graphics.Color.parseColor(category.colour))
    } catch (e: Exception) {
        Color(0xFF10B981)
    }

    val subcategoryStats = remember(subcategories, transactions, allSplits) {
        val stats = mutableMapOf<String, Pair<Int, Double>>()
        subcategories.forEach { sub ->
            var count = 0
            var total = 0.0
            transactions.forEach { tx ->
                val txSplits = allSplits.filter { it.transactionId == tx.id && it.subcategoryId == sub.id }
                if (txSplits.isNotEmpty()) {
                    txSplits.forEach { s ->
                        count++
                        total += s.amount
                    }
                } else if (tx.subcategoryId == sub.id) {
                    count++
                    total += tx.amount
                }
            }
            stats[sub.id] = count to total
        }
        stats
    }

    val totalCatSpend = subcategoryStats.values.sumOf { it.second }
    val totalCatCount = subcategoryStats.values.sumOf { it.first }

    val filteredSubs = remember(subcategories, searchQuery) {
        if (searchQuery.isBlank()) subcategories
        else subcategories.filter { it.name.contains(searchQuery, ignoreCase = true) || it.nameHindi.contains(searchQuery, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = textPrimary)
                }
                Text(
                    text = getBilingualName(category.name, category.nameHindi),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { showReorderSubcats = true }, modifier = Modifier.size(36.dp)) {
                Icon(imageVector = Icons.Default.SwapVert, contentDescription = "Reorder Subcategories", tint = Color(0xFF059669))
            }
        }

        // Summary Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = catColor.copy(alpha = 0.15f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(catColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = category.icon, fontSize = 22.sp)
                    }
                    Column {
                        Text(text = getBilingualName(category.name, category.nameHindi), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                        Text(text = "$totalCatCount transactions", fontSize = 11.sp, color = textSecondary)
                    }
                }
                Text(
                    text = formatINR(totalCatSpend),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = textPrimary
                )
            }
        }

        // Search Subcategories
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search subcategories...", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp)) },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF059669),
                unfocusedBorderColor = borderCol,
                focusedContainerColor = cardBg,
                unfocusedContainerColor = cardBg,
                focusedTextColor = textPrimary,
                unfocusedTextColor = textPrimary
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Subcategories List
        if (filteredSubs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(imageVector = Icons.Default.Category, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(36.dp))
                    Text(text = "No subcategories found", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                    Button(
                        onClick = onAddSubcategory,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Add Subcategory", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredSubs, key = { it.id }) { sub ->
                    val (subCount, subTotal) = subcategoryStats[sub.id] ?: (0 to 0.0)
                    val subColor = try {
                        Color(android.graphics.Color.parseColor(sub.colour))
                    } catch (e: Exception) {
                        catColor
                    }

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
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
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(subColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = sub.icon, fontSize = 16.sp)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = getBilingualName(sub.name, sub.nameHindi),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$subCount transaction${if (subCount == 1) "" else "s"}",
                                        fontSize = 11.sp,
                                        color = textSecondary
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = formatINR(subTotal),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textPrimary
                                )
                                IconButton(
                                    onClick = { onSubcategoryOptions(sub) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Options",
                                        tint = textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Add Subcategory Button
        Button(
            onClick = onAddSubcategory,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "+ Add Subcategory", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (showReorderSubcats) {
        ReorderSubcategoriesDialog(
            category = category,
            subcategories = subcategories,
            onDismiss = { showReorderSubcats = false },
            onSaveOrder = { reordered ->
                onReorderSubcategories(reordered)
                showReorderSubcats = false
            }
        )
    }
}

/**
 * Screen 3: Subcategory Options Bottom Sheet (Edit, Move, Change Icon, Delete)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubcategoryOptionsBottomSheet(
    subcategory: SubcategoryEntity,
    parentCategory: CategoryEntity?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onChangeIcon: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF1F5F9)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = subcategory.icon, fontSize = 22.sp)
                }
                Column {
                    Text(
                        text = getBilingualName(subcategory.name, subcategory.nameHindi),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = "Under ${parentCategory?.let { getBilingualName(it.name, it.nameHindi) } ?: "Category"}",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }

            HorizontalDivider(color = Color(0xFFE2E8F0))

            // Action Items
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEdit)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(20.dp))
                Text(text = "Edit Subcategory", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onMove)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(20.dp))
                Text(text = "Move to Category", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onChangeIcon)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.Default.Palette, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(20.dp))
                Text(text = "Change Icon", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            }

            HorizontalDivider(color = Color(0xFFE2E8F0))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDelete)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                Text(text = "Delete Subcategory", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Screen 4 & 5: Move Subcategory Flow (Select Category & Confirmation Step)
 */
@Composable
fun MoveSubcategoryFlowDialog(
    subcategory: SubcategoryEntity,
    currentParent: CategoryEntity?,
    allCategories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onConfirmMove: (newCategoryId: String, moveTransactions: Boolean) -> Unit
) {
    var step by remember { mutableStateOf(1) } // Step 1: Select Category, Step 2: Confirmation
    var selectedTargetId by remember { mutableStateOf(currentParent?.id ?: allCategories.firstOrNull()?.id ?: "") }
    var searchQuery by remember { mutableStateOf("") }
    var moveExistingTransactions by remember { mutableStateOf(false) } // Safe default: false (move only subcategory)

    val eligibleCategories = remember(allCategories, searchQuery) {
        if (searchQuery.isBlank()) allCategories
        else allCategories.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (step == 1) "Move Subcategory" else "How should existing transactions be handled?",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (step == 1) {
                    Text(
                        text = "Move \"${subcategory.icon} ${subcategory.name}\" to:",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search category...", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(eligibleCategories) { cat ->
                            val isSelected = selectedTargetId == cat.id
                            val isCurrent = cat.id == subcategory.categoryId

                            Card(
                                onClick = { selectedTargetId = cat.id },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFFECFDF5) else Color.White
                                ),
                                border = BorderStroke(1.dp, if (isSelected) Color(0xFF059669) else Color(0xFFE2E8F0)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = cat.icon, fontSize = 16.sp)
                                        Column {
                                            Text(text = getBilingualName(cat.name, cat.nameHindi), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                            if (isCurrent) {
                                                Text(text = "Current Parent", fontSize = 9.sp, color = Color(0xFF64748B))
                                            }
                                        }
                                    }
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedTargetId = cat.id }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Step 2: Confirmation
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { moveExistingTransactions = false }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(selected = !moveExistingTransactions, onClick = { moveExistingTransactions = false })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Move only the subcategory", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                Text(text = "Existing transactions keep their current category/subcategory. New transactions will use the new category.", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { moveExistingTransactions = true }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(selected = moveExistingTransactions, onClick = { moveExistingTransactions = true })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Move existing transactions too", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                Text(text = "Existing transactions assigned to this subcategory will move to the new category/subcategory.", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (step == 1) {
                Button(
                    onClick = { step = 2 },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    enabled = selectedTargetId.isNotEmpty() && selectedTargetId != subcategory.categoryId
                ) {
                    Text("Next")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { step = 1 }) { Text("Back") }
                    Button(
                        onClick = { onConfirmMove(selectedTargetId, moveExistingTransactions) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text("Move")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Screen 7: Change Icon Dialog
 */
@Composable
fun ChangeIconDialog(
    currentIcon: String,
    onDismiss: () -> Unit,
    onSelectIcon: (String) -> Unit
) {
    var selectedIcon by remember { mutableStateOf(currentIcon) }
    val iconsGrid = listOf(
        "🥔", "🧅", "🍅", "🥕", "🫛", "🥦", "🥬", "🍆",
        "🥒", "🌶️", "🌽", "🍄", "🥨", "🍎", "🍌", "🥭",
        "🍊", "🍇", "🍉", "🍍", "🥛", "🧀", "🍞", "☕",
        "💧", "🍗", "🧼", "🔧", "🏠", "🚗", "💡", "📱",
        "💸", "💳", "🛒", "🛍️", "🏷️", "🎬", "✈️", "🏥"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Icon", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val chunks = iconsGrid.chunked(4)
                chunks.forEach { rowIcons ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowIcons.forEach { icon ->
                            val isSelected = selectedIcon == icon
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFF059669) else Color(0xFFF1F5F9))
                                    .clickable { selectedIcon = icon },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = icon, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelectIcon(selectedIcon) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
            ) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Screen 8: Delete Subcategory Safe Dialog
 */
@Composable
fun DeleteSubcategorySafeDialog(
    subcategory: SubcategoryEntity,
    allCategories: List<CategoryEntity>,
    allSubcategories: List<SubcategoryEntity>,
    viewModel: KharchaViewModel,
    onDismiss: () -> Unit
) {
    var deleteOption by remember { mutableStateOf("keep") } // "move_sub", "move_other", "keep"
    var targetSubId by remember { mutableStateOf("") }

    val eligibleSubs = allSubcategories.filter { it.id != subcategory.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Subcategory", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Are you sure you want to delete \"${subcategory.icon} ${subcategory.name}\"?",
                    fontSize = 13.sp,
                    color = Color(0xFF0F172A)
                )

                Text(
                    text = "What do you want to do with historical transactions assigned to this subcategory?",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteOption = "keep" }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RadioButton(selected = deleteOption == "keep", onClick = { deleteOption = "keep" })
                    Column {
                        Text(text = "Keep historical classification", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        Text(text = "Do not modify historical transactions", fontSize = 10.sp, color = Color(0xFF64748B))
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteOption = "move_other" }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RadioButton(selected = deleteOption == "move_other", onClick = { deleteOption = "move_other" })
                    Column {
                        Text(text = "Move transactions to 'Other'", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        Text(text = "Reassign to general Other subcategory", fontSize = 10.sp, color = Color(0xFF64748B))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (deleteOption) {
                        "move_other" -> {
                            // Find or move to Other
                            viewModel.deleteSubcategory(subcategory.id)
                        }
                        else -> {
                            viewModel.deleteSubcategory(subcategory.id)
                        }
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Screen 10: Add Category Dialog
 */
@Composable
fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, nameHindi: String, icon: String, colour: String, isIncome: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var nameHindi by remember { mutableStateOf("") }
    var selectedIcon by remember { mutableStateOf("📁") }
    var selectedColour by remember { mutableStateOf("#059669") }
    var isIncome by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Category", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFE2E8F0))
                            .clickable { showIconPicker = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = selectedIcon, fontSize = 28.sp)
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Category Name (English)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = nameHindi,
                    onValueChange = { nameHindi = it },
                    label = { Text("कैटेगरी का नाम (हिंदी)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isIncome = !isIncome }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Is Income Category", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                    Switch(checked = isIncome, onCheckedChange = { isIncome = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name.trim(), nameHindi.trim(), selectedIcon, selectedColour, isIncome)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                enabled = name.isNotBlank()
            ) {
                Text("Add Category")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showIconPicker) {
        ChangeIconDialog(
            currentIcon = selectedIcon,
            onDismiss = { showIconPicker = false },
            onSelectIcon = {
                selectedIcon = it
                showIconPicker = false
            }
        )
    }
}

/**
 * Screen 11: Reorder Categories Dialog
 */
@Composable
fun ReorderCategoriesDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSaveOrder: (List<CategoryEntity>) -> Unit
) {
    var itemsList = remember { mutableStateOf(categories) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reorder Categories", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsList.value.forEachIndexed { index, cat ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "☰", fontSize = 14.sp, color = Color(0xFF94A3B8))
                                Text(text = cat.icon, fontSize = 16.sp)
                                Text(text = getBilingualName(cat.name, cat.nameHindi), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val mutable = itemsList.value.toMutableList()
                                            val tmp = mutable[index]
                                            mutable[index] = mutable[index - 1]
                                            mutable[index - 1] = tmp
                                            itemsList.value = mutable
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = "Move Up", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = {
                                        if (index < itemsList.value.size - 1) {
                                            val mutable = itemsList.value.toMutableList()
                                            val tmp = mutable[index]
                                            mutable[index] = mutable[index + 1]
                                            mutable[index + 1] = tmp
                                            itemsList.value = mutable
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Move Down", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveOrder(itemsList.value) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
            ) {
                Text("Save Order")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Screen 12: Reorder Subcategories Dialog
 */
@Composable
fun ReorderSubcategoriesDialog(
    category: CategoryEntity,
    subcategories: List<SubcategoryEntity>,
    onDismiss: () -> Unit,
    onSaveOrder: (List<SubcategoryEntity>) -> Unit
) {
    val catSubs = subcategories.filter { it.categoryId == category.id }
    var itemsList = remember { mutableStateOf(catSubs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reorder Subcategories", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsList.value.forEachIndexed { index, sub ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "☰", fontSize = 14.sp, color = Color(0xFF94A3B8))
                                Text(text = sub.icon, fontSize = 16.sp)
                                Text(text = getBilingualName(sub.name, sub.nameHindi), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val mutable = itemsList.value.toMutableList()
                                            val tmp = mutable[index]
                                            mutable[index] = mutable[index - 1]
                                            mutable[index - 1] = tmp
                                            itemsList.value = mutable
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = "Move Up", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = {
                                        if (index < itemsList.value.size - 1) {
                                            val mutable = itemsList.value.toMutableList()
                                            val tmp = mutable[index]
                                            mutable[index] = mutable[index + 1]
                                            mutable[index + 1] = tmp
                                            itemsList.value = mutable
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Move Down", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveOrder(itemsList.value) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
            ) {
                Text("Save Order")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Add Subcategory Dialog
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubcategoryDialog(
    initialCategory: CategoryEntity,
    allCategories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (categoryId: String, name: String, nameHindi: String, icon: String, colour: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var nameHindi by remember { mutableStateOf("") }
    var selectedIcon by remember { mutableStateOf("🥔") }
    var selectedCategoryId by remember { mutableStateOf(initialCategory.id) }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }

    val currentSelectedParent = allCategories.find { it.id == selectedCategoryId } ?: initialCategory

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Subcategory", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column {
                    Text(text = "Parent Category", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                    Spacer(modifier = Modifier.height(4.dp))
                    Box {
                        OutlinedCard(
                            onClick = { categoryDropdownExpanded = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${currentSelectedParent.icon} ${getBilingualName(currentSelectedParent.name, currentSelectedParent.nameHindi)}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                            }
                        }
                        DropdownMenu(
                            expanded = categoryDropdownExpanded,
                            onDismissRequest = { categoryDropdownExpanded = false }
                        ) {
                            allCategories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text("${cat.icon} ${getBilingualName(cat.name, cat.nameHindi)}", fontSize = 12.sp) },
                                    onClick = {
                                        selectedCategoryId = cat.id
                                        categoryDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Subcategory Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Icon", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                    OutlinedCard(
                        onClick = { showIconPicker = true },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = selectedIcon, fontSize = 20.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(selectedCategoryId, name.trim(), nameHindi.trim(), selectedIcon, currentSelectedParent.colour)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                enabled = name.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showIconPicker) {
        ChangeIconDialog(
            currentIcon = selectedIcon,
            onDismiss = { showIconPicker = false },
            onSelectIcon = {
                selectedIcon = it
                showIconPicker = false
            }
        )
    }
}

/**
 * Edit Subcategory Dialog
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSubcategoryDialog(
    subcategory: SubcategoryEntity,
    currentParent: CategoryEntity?,
    allCategories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (newCategoryId: String, newName: String, newNameHindi: String, newIcon: String, newColour: String) -> Unit
) {
    var name by remember { mutableStateOf(subcategory.name) }
    var nameHindi by remember { mutableStateOf(subcategory.nameHindi) }
    var selectedIcon by remember { mutableStateOf(subcategory.icon) }
    var selectedCategoryId by remember { mutableStateOf(subcategory.categoryId) }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }

    val currentSelectedParent = allCategories.find { it.id == selectedCategoryId } ?: currentParent

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Subcategory", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column {
                    Text(text = "Parent Category", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                    Spacer(modifier = Modifier.height(4.dp))
                    Box {
                        OutlinedCard(
                            onClick = { categoryDropdownExpanded = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${currentSelectedParent?.icon ?: "📁"} ${currentSelectedParent?.let { getBilingualName(it.name, it.nameHindi) } ?: "Category"}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                            }
                        }
                        DropdownMenu(
                            expanded = categoryDropdownExpanded,
                            onDismissRequest = { categoryDropdownExpanded = false }
                        ) {
                            allCategories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text("${cat.icon} ${getBilingualName(cat.name, cat.nameHindi)}", fontSize = 12.sp) },
                                    onClick = {
                                        selectedCategoryId = cat.id
                                        categoryDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Subcategory Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Icon", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                    OutlinedCard(
                        onClick = { showIconPicker = true },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = selectedIcon, fontSize = 20.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val parentCat = allCategories.find { it.id == selectedCategoryId }
                        onSave(selectedCategoryId, name.trim(), nameHindi.trim(), selectedIcon, parentCat?.colour ?: subcategory.colour)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showIconPicker) {
        ChangeIconDialog(
            currentIcon = selectedIcon,
            onDismiss = { showIconPicker = false },
            onSelectIcon = {
                selectedIcon = it
                showIconPicker = false
            }
        )
    }
}

/**
 * Merge Category Dialog
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeCategoryDialog(
    sourceCategory: CategoryEntity,
    allCategories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onConfirmMerge: (targetCategoryId: String) -> Unit
) {
    val eligibleTargets = remember(allCategories, sourceCategory) {
        allCategories.filter { it.id != sourceCategory.id }
    }
    var selectedTargetId by remember { mutableStateOf(eligibleTargets.firstOrNull()?.id ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge Category", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Move all transactions and subcategories from '${sourceCategory.name}' into another category.",
                    fontSize = 13.sp,
                    color = Color(0xFF334155)
                )

                Text("Select Destination Category:", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                if (eligibleTargets.isEmpty()) {
                    Text("No eligible destination categories found.", fontSize = 12.sp, color = Color(0xFFDC2626))
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        eligibleTargets.forEach { cat ->
                            val selected = selectedTargetId == cat.id
                            Surface(
                                onClick = { selectedTargetId = cat.id },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) Color(0xFFD1FAE5) else Color(0xFFF1F5F9),
                                border = BorderStroke(1.dp, if (selected) Color(0xFF059669) else Color(0xFFE2E8F0)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(text = cat.icon, fontSize = 16.sp)
                                    Text(
                                        text = getBilingualName(cat.name, cat.nameHindi),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color(0xFF065F46) else Color(0xFF0F172A)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmMerge(selectedTargetId) },
                enabled = selectedTargetId.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
            ) {
                Text("Confirm Merge")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
