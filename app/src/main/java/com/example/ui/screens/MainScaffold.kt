package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.TransactionEntity
import com.example.viewmodel.KharchaViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(viewModel: KharchaViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.performAutoSync(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val currentTab by viewModel.currentTab.collectAsState()
    var showAddMenu by remember { mutableStateOf(false) }
    var addTxInitialType by remember { mutableStateOf("EXPENSE") }
    var showAddTxModal by remember { mutableStateOf(false) }
    var selectedTxDetail by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedTxToEdit by remember { mutableStateOf<TransactionEntity?>(null) }

    var showCreateCatDialog by remember { mutableStateOf(false) }
    var showCreateSubcatDialog by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = selectedTxToEdit != null) {
        selectedTxToEdit = null
    }
    androidx.activity.compose.BackHandler(enabled = selectedTxDetail != null && selectedTxToEdit == null) {
        selectedTxDetail = null
    }
    androidx.activity.compose.BackHandler(enabled = showAddTxModal) {
        showAddTxModal = false
    }
    androidx.activity.compose.BackHandler(enabled = showAddMenu) {
        showAddMenu = false
    }
    androidx.activity.compose.BackHandler(enabled = showCreateSubcatDialog) {
        showCreateSubcatDialog = false
    }
    androidx.activity.compose.BackHandler(enabled = showCreateCatDialog) {
        showCreateCatDialog = false
    }

    androidx.activity.compose.BackHandler(enabled = currentTab != "home" && selectedTxDetail == null && selectedTxToEdit == null && !showAddTxModal && !showAddMenu && !showCreateCatDialog && !showCreateSubcatDialog) {
        when (currentTab) {
            "subcategory_drill" -> viewModel.currentTab.value = "category_drill"
            "category_drill", "merchant_drill", "account_drill", "source_drill" -> viewModel.currentTab.value = viewModel.lastMainTab.value
            "reports", "accounts", "transactions", "categories" -> viewModel.currentTab.value = "home"
            "more" -> viewModel.currentTab.value = "home"
            else -> viewModel.currentTab.value = "home"
        }
    }

    Scaffold(
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomNavButton(
                        icon = Icons.Default.Dashboard,
                        label = "Home",
                        selected = currentTab == "home",
                        onClick = { viewModel.currentTab.value = "home" }
                    )
                    BottomNavButton(
                        icon = Icons.Default.Receipt,
                        label = "Transactions",
                        selected = currentTab == "transactions",
                        onClick = { viewModel.currentTab.value = "transactions" }
                    )

                    // Central FAB Add Button
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.offset(y = (-12).dp)
                    ) {
                        FloatingActionButton(
                            onClick = { showAddMenu = true },
                            containerColor = Color(0xFF059669),
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(28.dp))
                        }
                        Text(text = "Add", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669), modifier = Modifier.padding(top = 2.dp))
                    }

                    BottomNavButton(
                        icon = Icons.Default.Folder,
                        label = "Categories",
                        selected = currentTab == "categories",
                        onClick = { viewModel.currentTab.value = "categories" }
                    )
                    BottomNavButton(
                        icon = Icons.Default.MoreHoriz,
                        label = "More",
                        selected = currentTab == "more",
                        onClick = { viewModel.currentTab.value = "more" }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (currentTab) {
                "home" -> HomeScreen(
                    viewModel = viewModel,
                    onNavigateToTransactions = { viewModel.currentTab.value = "transactions" },
                    onNavigateToCategories = { viewModel.currentTab.value = "categories" },
                    onNavigateToMerchants = {
                        viewModel.currentTab.value = "transactions"
                        viewModel.transactionSubTab.value = "merchants"
                    },
                    onNavigateToAccounts = { viewModel.currentTab.value = "accounts" },
                    onNavigateToCategoryDrillDown = { catId ->
                        viewModel.drillDownCategoryId.value = catId
                        viewModel.lastMainTab.value = "home"
                        viewModel.currentTab.value = "category_drill"
                    },
                    onNavigateToMerchantDrillDown = { merchant ->
                        viewModel.drillDownMerchantName.value = merchant
                        viewModel.lastMainTab.value = "home"
                        viewModel.currentTab.value = "merchant_drill"
                    },
                    onNavigateToAccountDrillDown = { accId ->
                        viewModel.drillDownAccountId.value = accId
                        viewModel.lastMainTab.value = "home"
                        viewModel.currentTab.value = "account_drill"
                    },
                    onOpenAddExpense = {
                        addTxInitialType = "EXPENSE"
                        showAddTxModal = true
                    },
                    onOpenAddIncome = {
                        addTxInitialType = "INCOME"
                        showAddTxModal = true
                    },
                    onSelectTransaction = { tx -> selectedTxToEdit = tx }
                )
                "transactions" -> TransactionsScreen(
                    viewModel = viewModel,
                    onOpenAddExpense = {
                        addTxInitialType = "EXPENSE"
                        showAddTxModal = true
                    },
                    onSelectTransaction = { tx -> selectedTxToEdit = tx }
                )
                "categories" -> CategoriesScreen(
                    viewModel = viewModel,
                    onOpenAddCategory = { showCreateCatDialog = true },
                    onOpenAddSubcategory = { showCreateSubcatDialog = true }
                )
                "accounts" -> AccountsScreen(
                    viewModel = viewModel
                )
                "reports" -> ReportsScreen(
                    viewModel = viewModel,
                    onNavigateToCategoryDrillDown = { catId ->
                        viewModel.drillDownCategoryId.value = catId
                        viewModel.lastMainTab.value = "reports"
                        viewModel.currentTab.value = "category_drill"
                    },
                    onNavigateToMerchantDrillDown = { merchant ->
                        viewModel.drillDownMerchantName.value = merchant
                        viewModel.lastMainTab.value = "reports"
                        viewModel.currentTab.value = "merchant_drill"
                    },
                    onNavigateToAccountDrillDown = { accId ->
                        viewModel.drillDownAccountId.value = accId
                        viewModel.lastMainTab.value = "reports"
                        viewModel.currentTab.value = "account_drill"
                    },
                    onNavigateToSourceDrillDown = { srcKey ->
                        viewModel.drillDownSourceKey.value = srcKey
                        viewModel.lastMainTab.value = "reports"
                        viewModel.currentTab.value = "source_drill"
                    }
                )
                "more" -> MoreScreen(viewModel = viewModel)
                "category_drill" -> {
                    val catId = viewModel.drillDownCategoryId.collectAsState().value ?: ""
                    val categories by viewModel.categories.collectAsState()
                    val subcategories by viewModel.subcategories.collectAsState()
                    val accounts by viewModel.accounts.collectAsState()
                    val transactions by viewModel.transactions.collectAsState()
                    val allSplits by viewModel.transactionSplits.collectAsState()
                    val selectedRange by viewModel.selectedRange.collectAsState()
                    val customStart by viewModel.customStart.collectAsState()
                    val customEnd by viewModel.customEnd.collectAsState()
                    
                    val filteredTxs = com.example.utils.DateFilterUtils.filterByRange(transactions, selectedRange, customStart, customEnd)
                    val cat = categories.find { it.id == catId }
                    val catName = cat?.let { getBilingualName(it.name, it.nameHindi) } ?: "Category"
                    val catIcon = cat?.icon ?: "📁"
                    val catColor = try {
                        Color(android.graphics.Color.parseColor(cat?.colour ?: "#10B981"))
                    } catch (e: Exception) {
                        Color(0xFF10B981)
                    }

                    val categoryTxs = filteredTxs.filter { tx ->
                        (tx.categoryId == catId || allSplits.any { it.transactionId == tx.id && it.categoryId == catId }) &&
                        (if (cat?.isIncome == true) tx.type == "INCOME" else tx.type == "EXPENSE" && tx.isExpense && !tx.isInternalTransfer && tx.transactionType != "INTERNAL_TRANSFER")
                    }.sortedWith(compareByDescending<com.example.data.entity.TransactionEntity> { it.date }.thenByDescending { it.time })

                    val totalCatSpend = categoryTxs.sumOf { tx ->
                        val txSplits = allSplits.filter { it.transactionId == tx.id && it.categoryId == catId }
                        if (txSplits.isNotEmpty()) txSplits.sumOf { it.amount }
                        else if (tx.categoryId == catId) tx.amount
                        else 0.0
                    }

                    CategoryDrillDownView(
                        categoryName = catName,
                        categoryIcon = catIcon,
                        categoryColor = catColor,
                        totalSpend = totalCatSpend,
                        transactionCount = categoryTxs.size,
                        transactions = categoryTxs,
                        categories = categories,
                        subcategories = subcategories,
                        accounts = accounts,
                        allSplits = allSplits,
                        targetCategoryId = catId,
                        formatINR = { amt -> "₹" + java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN")).format(amt) },
                        onBack = { viewModel.currentTab.value = viewModel.lastMainTab.value },
                        onSelectTransaction = { tx -> selectedTxDetail = tx },
                        onSelectSubcategory = { sub ->
                            viewModel.drillDownSubcategoryId.value = sub.id
                            viewModel.currentTab.value = "subcategory_drill"
                        }
                    )
                }
                "subcategory_drill" -> {
                    val catId = viewModel.drillDownCategoryId.collectAsState().value ?: ""
                    val subId = viewModel.drillDownSubcategoryId.collectAsState().value ?: ""
                    val categories by viewModel.categories.collectAsState()
                    val subcategories by viewModel.subcategories.collectAsState()
                    val accounts by viewModel.accounts.collectAsState()
                    val transactions by viewModel.transactions.collectAsState()
                    val allSplits by viewModel.transactionSplits.collectAsState()
                    val selectedRange by viewModel.selectedRange.collectAsState()
                    val customStart by viewModel.customStart.collectAsState()
                    val customEnd by viewModel.customEnd.collectAsState()

                    val filteredTxs = com.example.utils.DateFilterUtils.filterByRange(transactions, selectedRange, customStart, customEnd)
                    val sub = subcategories.find { it.id == subId }
                    val subName = sub?.let { getBilingualName(it.name, it.nameHindi) } ?: "Subcategory"

                    val cat = categories.find { it.id == catId }
                    val subcatTxs = filteredTxs.filter { tx ->
                        ((tx.categoryId == catId && tx.subcategoryId == subId) || 
                        allSplits.any { it.transactionId == tx.id && it.categoryId == catId && it.subcategoryId == subId }) &&
                        (if (cat?.isIncome == true) tx.type == "INCOME" else tx.type == "EXPENSE" && tx.isExpense && !tx.isInternalTransfer && tx.transactionType != "INTERNAL_TRANSFER")
                    }.sortedWith(compareByDescending<com.example.data.entity.TransactionEntity> { it.date }.thenByDescending { it.time })

                    val totalSubSpend = subcatTxs.sumOf { tx ->
                        val txSplits = allSplits.filter { it.transactionId == tx.id && it.categoryId == catId && it.subcategoryId == subId }
                        if (txSplits.isNotEmpty()) txSplits.sumOf { it.amount }
                        else if (tx.categoryId == catId && tx.subcategoryId == subId) tx.amount
                        else 0.0
                    }

                    SubcategoryDrillDownView(
                        subcategoryName = subName,
                        totalSpend = totalSubSpend,
                        transactionCount = subcatTxs.size,
                        transactions = subcatTxs,
                        categories = categories,
                        subcategories = subcategories,
                        accounts = accounts,
                        allSplits = allSplits,
                        formatINR = { amt -> "₹" + java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN")).format(amt) },
                        onBack = { viewModel.currentTab.value = "category_drill" },
                        onSelectTransaction = { tx -> selectedTxDetail = tx }
                    )
                }
                "merchant_drill" -> {
                    val merchantName = viewModel.drillDownMerchantName.collectAsState().value ?: ""
                    val transactions by viewModel.transactions.collectAsState()
                    val categories by viewModel.categories.collectAsState()
                    val subcategories by viewModel.subcategories.collectAsState()
                    val accounts by viewModel.accounts.collectAsState()
                    val allSplits by viewModel.transactionSplits.collectAsState()
                    val selectedRange by viewModel.selectedRange.collectAsState()
                    val customStart by viewModel.customStart.collectAsState()
                    val customEnd by viewModel.customEnd.collectAsState()

                    val filteredTxs = com.example.utils.DateFilterUtils.filterByRange(transactions, selectedRange, customStart, customEnd)
                    val merchantTxs = filteredTxs.filter { it.merchant == merchantName }
                        .sortedWith(compareByDescending<com.example.data.entity.TransactionEntity> { it.date }.thenByDescending { it.time })

                    ReportsDrillDownView(
                        title = "$merchantName History",
                        drillDownType = "merchant",
                        transactions = merchantTxs,
                        totalIncome = merchantTxs.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount },
                        totalExpense = merchantTxs.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount },
                        netSavings = 0.0,
                        savingsRate = 0.0,
                        categories = categories,
                        subcategories = subcategories,
                        accounts = accounts,
                        allSplits = allSplits,
                        formatINR = { amt -> "₹" + java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN")).format(amt) },
                        onBack = { viewModel.currentTab.value = viewModel.lastMainTab.value },
                        onSelectTransaction = { tx -> selectedTxDetail = tx }
                    )
                }
                "account_drill" -> {
                    val accId = viewModel.drillDownAccountId.collectAsState().value ?: ""
                    val transactions by viewModel.transactions.collectAsState()
                    val categories by viewModel.categories.collectAsState()
                    val subcategories by viewModel.subcategories.collectAsState()
                    val accounts by viewModel.accounts.collectAsState()
                    val allSplits by viewModel.transactionSplits.collectAsState()
                    val selectedRange by viewModel.selectedRange.collectAsState()
                    val customStart by viewModel.customStart.collectAsState()
                    val customEnd by viewModel.customEnd.collectAsState()

                    val filteredTxs = com.example.utils.DateFilterUtils.filterByRange(transactions, selectedRange, customStart, customEnd)
                    val acc = accounts.find { it.id == accId }
                    val accName = acc?.name ?: "Account"
                    val accountTxs = filteredTxs.filter { it.accountId == accId && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }
                        .sortedWith(compareByDescending<com.example.data.entity.TransactionEntity> { it.date }.thenByDescending { it.time })

                    ReportsDrillDownView(
                        title = "$accName History",
                        drillDownType = "account",
                        transactions = accountTxs,
                        totalIncome = accountTxs.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount },
                        totalExpense = accountTxs.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount },
                        netSavings = 0.0,
                        savingsRate = 0.0,
                        categories = categories,
                        subcategories = subcategories,
                        accounts = accounts,
                        allSplits = allSplits,
                        formatINR = { amt -> "₹" + java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN")).format(amt) },
                        onBack = { viewModel.currentTab.value = viewModel.lastMainTab.value },
                        onSelectTransaction = { tx -> selectedTxDetail = tx }
                    )
                }
                "source_drill" -> {
                    val srcKey = viewModel.drillDownSourceKey.collectAsState().value ?: ""
                    val transactions by viewModel.transactions.collectAsState()
                    val categories by viewModel.categories.collectAsState()
                    val subcategories by viewModel.subcategories.collectAsState()
                    val accounts by viewModel.accounts.collectAsState()
                    val allSplits by viewModel.transactionSplits.collectAsState()
                    val selectedRange by viewModel.selectedRange.collectAsState()
                    val customStart by viewModel.customStart.collectAsState()
                    val customEnd by viewModel.customEnd.collectAsState()

                    val filteredTxs = com.example.utils.DateFilterUtils.filterByRange(transactions, selectedRange, customStart, customEnd)
                    val srcLabel = when (srcKey) {
                        "MANUAL" -> "Manual Entry"
                        "SMS" -> "SMS Auto-Tracking"
                        "NOTIFICATION" -> "Notification Auto-Tracking"
                        "EMAIL" -> "Email Auto-Tracking"
                        else -> srcKey
                    }
                    val sourceTxs = filteredTxs.filter { it.source == srcKey }
                        .sortedWith(compareByDescending<com.example.data.entity.TransactionEntity> { it.date }.thenByDescending { it.time })

                    ReportsDrillDownView(
                        title = srcLabel,
                        drillDownType = "source",
                        transactions = sourceTxs,
                        totalIncome = sourceTxs.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount },
                        totalExpense = sourceTxs.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount },
                        netSavings = 0.0,
                        savingsRate = 0.0,
                        categories = categories,
                        subcategories = subcategories,
                        accounts = accounts,
                        allSplits = allSplits,
                        formatINR = { amt -> "₹" + java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN")).format(amt) },
                        onBack = { viewModel.currentTab.value = viewModel.lastMainTab.value },
                        onSelectTransaction = { tx -> selectedTxDetail = tx }
                    )
                }
            }
        }
    }



    // Add Menu (Expense / Income choice)
    if (showAddMenu) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.4f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentHeight(Alignment.Bottom)
                    .clickable { showAddMenu = false }
            ) {
                Card(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "What would you like to record?", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                            IconButton(onClick = { showAddMenu = false }, modifier = Modifier.size(32.dp)) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color(0xFF64748B))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Add Expense
                            Card(
                                onClick = {
                                    showAddMenu = false
                                    addTxInitialType = "EXPENSE"
                                    showAddTxModal = true
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFDAD6)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFBA1A1A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(imageVector = Icons.Default.ArrowUpward, contentDescription = null, tint = Color.White)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(text = "Expense", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF410002))
                                    Text(text = "Money spent", fontSize = 10.sp, color = Color(0xFF93000A))
                                }
                            }

                            // Add Income
                            Card(
                                onClick = {
                                    showAddMenu = false
                                    addTxInitialType = "INCOME"
                                    showAddTxModal = true
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFD1FAE5)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF059669)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = null, tint = Color.White)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(text = "Income", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF002114))
                                    Text(text = "Money received", fontSize = 10.sp, color = Color(0xFF047857))
                                }
                            }

                            // Record Transfer
                            Card(
                                onClick = {
                                    showAddMenu = false
                                    addTxInitialType = "TRANSFER"
                                    showAddTxModal = true
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF2563EB)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = "🔄", fontSize = 18.sp)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(text = "Transfer", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A8A))
                                    Text(text = "Account/Friend", fontSize = 10.sp, color = Color(0xFF1D4ED8))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // Add Transaction Fullscreen Modal
    if (showAddTxModal) {
        AddTransactionDialog(
            initialType = addTxInitialType,
            viewModel = viewModel,
            onDismiss = { showAddTxModal = false }
        )
    }

    // Transaction Detail Modal
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

    if (showCreateCatDialog) {
        var catName by remember { mutableStateOf("") }
        var catNameHindi by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateCatDialog = false },
            title = { Text("Create Category", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = catName,
                        onValueChange = { catName = it },
                        label = { Text("Category Name (English)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = catNameHindi,
                        onValueChange = { catNameHindi = it },
                        label = { Text("कैटेगरी का नाम (हिंदी)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (catName.isNotBlank()) {
                            viewModel.addCategory(catName.trim(), catNameHindi.trim(), "🏷️", "#059669", false)
                            catName = ""
                            catNameHindi = ""
                            showCreateCatDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateCatDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showCreateSubcatDialog) {
        var subName by remember { mutableStateOf("") }
        var subNameHindi by remember { mutableStateOf("") }
        val categories by viewModel.categories.collectAsState()
        var parentId by remember { mutableStateOf(categories.firstOrNull()?.id ?: "") }

        AlertDialog(
            onDismissRequest = { showCreateSubcatDialog = false },
            title = { Text("Create Subcategory", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val currentParent = categories.find { it.id == parentId }
                    Text(text = "Parent: ${currentParent?.icon ?: ""} ${currentParent?.let { getBilingualName(it.name, it.nameHindi) } ?: "Select"}", fontSize = 12.sp, color = Color.Gray)
                    
                    OutlinedTextField(
                        value = subName,
                        onValueChange = { subName = it },
                        label = { Text("Subcategory Name (English)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = subNameHindi,
                        onValueChange = { subNameHindi = it },
                        label = { Text("सबकैटेगरी का नाम (हिंदी)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (subName.isNotBlank() && parentId.isNotBlank()) {
                            viewModel.addSubcategory(parentId, subName.trim(), subNameHindi.trim(), "🏷️", "#059669")
                            subName = ""
                            subNameHindi = ""
                            showCreateSubcatDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateSubcatDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun BottomNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color = if (selected) Color(0xFF059669) else Color(0xFF94A3B8)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = color)
    }
}
