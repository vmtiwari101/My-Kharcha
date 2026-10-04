package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.example.data.entity.AccountEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.viewmodel.KharchaViewModel
import com.example.utils.DateFilterUtils
import java.text.NumberFormat
import java.util.Locale
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: KharchaViewModel,
    onNavigateToTransactions: () -> Unit,
    onNavigateToCategories: () -> Unit,
    onNavigateToMerchants: () -> Unit,
    onNavigateToAccounts: () -> Unit,
    onNavigateToCategoryDrillDown: (String) -> Unit,
    onNavigateToMerchantDrillDown: (String) -> Unit,
    onNavigateToAccountDrillDown: (String) -> Unit,
    onOpenAddExpense: () -> Unit,
    onOpenAddIncome: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit
) {
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val subcategories by viewModel.subcategories.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val allSplits by viewModel.transactionSplits.collectAsState()
    
    val selectedRange by viewModel.selectedRange.collectAsState()
    val customStart by viewModel.customStart.collectAsState()
    val customEnd by viewModel.customEnd.collectAsState()
    
    val filteredTxs = remember(transactions, selectedRange, customStart, customEnd) {
        DateFilterUtils.filterByRange(transactions, selectedRange, customStart, customEnd)
    }
    
    val expenseTxs = filteredTxs.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }
    val incomeTxs = filteredTxs.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }

    val totalExpense = expenseTxs.sumOf { it.amount }
    val totalIncome = incomeTxs.sumOf { it.amount }

    val budgetsVersion by viewModel.monthlyBudgetsVersion.collectAsState()
    
    val currentMonthKey = remember {
        java.text.SimpleDateFormat("yyyy-MM", Locale.ENGLISH).format(Date())
    }
    
    val monthlyBudget = remember(currentMonthKey, budgetsVersion) { viewModel.getMonthlyBudget(currentMonthKey) }
    val safeBalance = maxOf(0.0, monthlyBudget - totalExpense)

    var showEditBudgetDialog by remember { mutableStateOf(false) }
    var monthExpanded by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val historicalScanState by viewModel.historicalScanState.collectAsState()

    var hasSmsPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_SMS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    var permissionDismissed by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasSmsPermission = isGranted
        if (isGranted) {
            viewModel.setSmsTrackingEnabled(true, context)
            viewModel.startHistoricalSmsScan(context)
        } else {
            permissionDismissed = true
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_SMS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                hasSmsPermission = granted
                if (granted && !viewModel.isSmsInitialScanCompleted()) {
                    viewModel.setSmsTrackingEnabled(true, context)
                    viewModel.startHistoricalSmsScan(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val formatINR: (Double) -> String = { amt ->
        "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(amt)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        // Top App Bar / Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFD1FAE5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wallet,
                            contentDescription = null,
                            tint = Color(0xFF059669),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "My Kharcha",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = "Personal Tracker",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF059669)
                        )
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { monthExpanded = true },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White)
                    ) {
                        Text(text = selectedRange.replace("_", " "), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = monthExpanded,
                        onDismissRequest = { monthExpanded = false }
                    ) {
                        listOf("TODAY", "THIS_WEEK", "THIS_MONTH", "LAST_MONTH").forEach { range ->
                            DropdownMenuItem(
                                text = { Text(text = range.replace("_", " "), fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                                onClick = {
                                    viewModel.selectedRange.value = range
                                    monthExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Historical SMS Scan Progress or Permission Prompt
        if (historicalScanState is com.example.viewmodel.HistoricalScanState.Scanning ||
            historicalScanState is com.example.viewmodel.HistoricalScanState.Completed
        ) {
            item {
                HistoricalScanStatusCard(
                    state = historicalScanState,
                    onDismiss = { viewModel.dismissHistoricalScanState() }
                )
            }
        } else if (!hasSmsPermission && !viewModel.isSmsInitialScanCompleted() && !permissionDismissed) {
            item {
                SmsAutoScanPromptCard(
                    onGrant = { permissionLauncher.launch(android.Manifest.permission.READ_SMS) },
                    onDismiss = { permissionDismissed = true }
                )
            }
        }

        // Monthly Overview Donut Card
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Hero Image
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFFECFDF5))
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.kharcha_hero_banner_1790314028015),
                        contentDescription = "Hero Banner",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f))
                            ))
                    )
                    
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Welcome back!",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "You've saved ${formatINR(safeBalance)} so far this month.",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.currentTab.value = "reports" },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(text = "Monthly Overview", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                            }
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (totalExpense <= monthlyBudget) Color(0xFFECFDF5) else Color(0xFFFEF2F2),
                                modifier = Modifier.clickable { showEditBudgetDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (totalExpense <= monthlyBudget) Icons.Default.Verified else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (totalExpense <= monthlyBudget) Color(0xFF059669) else Color(0xFFDC2626),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = if (totalExpense <= monthlyBudget) "On Budget" else "Over Budget",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (totalExpense <= monthlyBudget) Color(0xFF059669) else Color(0xFFDC2626)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit Budget",
                                        tint = if (totalExpense <= monthlyBudget) Color(0xFF059669) else Color(0xFFDC2626),
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Box(
                            modifier = Modifier.size(170.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 36f
                                if (totalExpense <= 0.0) {
                                    drawCircle(
                                        color = Color(0xFFE2E8F0),
                                        style = Stroke(width = strokeWidth)
                                    )
                                } else {
                                    var startAngle = -90f
                                    val catMap = mutableMapOf<String, Double>()
                                    expenseTxs.forEach { tx ->
                                        val txSplits = allSplits.filter { it.transactionId == tx.id }
                                        if (txSplits.isNotEmpty()) {
                                            txSplits.forEach { s ->
                                                catMap[s.categoryId] = (catMap[s.categoryId] ?: 0.0) + s.amount
                                            }
                                        } else {
                                            catMap[tx.categoryId] = (catMap[tx.categoryId] ?: 0.0) + tx.amount
                                        }
                                    }
                                    catMap.forEach { (catId, catSum) ->
                                        val sweep = (catSum / totalExpense).toFloat() * 360f
                                        val catColor = try {
                                            Color(android.graphics.Color.parseColor(categories.find { it.id == catId }?.colour ?: "#10B981"))
                                        } catch (e: Exception) {
                                            Color(0xFF10B981)
                                        }
                                        drawArc(
                                            color = catColor,
                                            startAngle = startAngle,
                                            sweepAngle = sweep,
                                            useCenter = false,
                                            style = Stroke(width = strokeWidth)
                                        )
                                        startAngle += sweep
                                    }
                                }
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "TOTAL EXPENSE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                Text(text = formatINR(totalExpense), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                Text(text = "${expenseTxs.size} expenses", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "INCOME", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(text = formatINR(totalIncome), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF059669))
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showEditBudgetDialog = true }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(text = "BUDGET", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit Budget",
                                        tint = Color(0xFF059669),
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(text = formatINR(monthlyBudget), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF334155))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "SAFE TO SPEND", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(text = formatINR(safeBalance), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2563EB))
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Today Summary Card
                val todayDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date())
                val todayTxs = transactions.filter { it.date == todayDateStr }
                val todayExpense = todayTxs.filter { it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
                val todayIncome = todayTxs.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
                
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Today's Summary", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(text = "Expense", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                Text(text = formatINR(todayExpense), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFDC2626))
                            }
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(text = "Income", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                Text(text = formatINR(todayIncome), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF059669))
                            }
                            Column(
                                horizontalAlignment = Alignment.Start,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.selectedRange.value = "TODAY"
                                        onNavigateToTransactions()
                                    }
                                    .padding(4.dp)
                            ) {
                                Text(text = "Transactions", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(text = todayTxs.size.toString(), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Real Action Buttons: Categories & Merchants
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    onClick = onNavigateToCategories,
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF059669)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.PieChart, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(text = "Categories", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Text(text = "Spending breakdown", fontSize = 10.sp, color = Color(0xFF047857))
                        }
                    }
                }

                Card(
                    onClick = onNavigateToMerchants,
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF2563EB)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.Store, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(text = "Merchants", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Text(text = "Vendor spend", fontSize = 10.sp, color = Color(0xFF1D4ED8))
                        }
                    }
                }
            }
        }

        // Recent Transactions Section Header & List
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Recent Transactions", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onOpenAddExpense,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(text = "Add", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                    }
                    TextButton(
                        onClick = onNavigateToTransactions,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(text = "View All", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64748B))
                    }
                }
            }
        }

        val sortedTxsList = transactions.sortedWith(compareByDescending<TransactionEntity> { it.date }.thenByDescending { it.time }).take(5)

        if (sortedTxsList.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(imageVector = Icons.Default.Inbox, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "No transactions yet", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                        Text(text = "Add your first expense or income to start tracking.", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onOpenAddExpense,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(text = "Add Expense", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = onOpenAddIncome,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(text = "Add Income", fontSize = 11.sp, color = Color(0xFF059669))
                            }
                        }
                    }
                }
            }
        } else {
            items(sortedTxsList, key = { it.id }) { tx ->
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

        // Payment Sources Section (Compact Redesign)
        item {
            val activeAccounts = remember(accounts, transactions) {
                accounts.filter { it.isActive }.take(4)
            }
            
            if (activeAccounts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "PAYMENT SOURCES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    TextButton(onClick = onNavigateToAccounts) {
                        Text(text = "View All", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    activeAccounts.forEach { acc ->
                        val accTxs = transactions.filter {
                            it.accountId == acc.id || it.counterpartyAccountId == acc.id || (acc.last4Digits.isNotEmpty() && it.last4Digits == acc.last4Digits)
                        }
                        val totalExp = accTxs.filter { it.type == "EXPENSE" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
                        val totalInc = accTxs.filter { it.type == "INCOME" && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }.sumOf { it.amount }
                        val displayAmt = if (acc.type == "Credit Card") {
                            if (acc.outstandingAmount > 0.0) acc.outstandingAmount else Math.max(0.0, totalExp - totalInc)
                        } else {
                            acc.initialBalance + totalInc - totalExp
                        }

                        Surface(
                            onClick = { onNavigateToAccountDrillDown(acc.id) },
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            shadowElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 70.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    BankLogo(
                                        bankName = acc.bankName.ifEmpty { acc.name },
                                        size = 38.dp,
                                        shapeRadius = 10.dp,
                                        actualIssuer = acc.name
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = acc.name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF0F172A),
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(weight = 1f, fill = false)
                                            )
                                            if (acc.last4Digits.isNotBlank()) {
                                                Text(
                                                    text = "••••${acc.last4Digits}",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF64748B)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = acc.type,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF059669)
                                        )
                                    }
                                }

                                Column(
                                    horizontalAlignment = Alignment.End,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = formatINR(displayAmt),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF0F172A)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Category Spending Section
        item {
            val catBreakdown = remember(expenseTxs, allSplits, categories) {
                val totals = mutableMapOf<String, Double>()
                expenseTxs.forEach { tx ->
                    val txSplits = allSplits.filter { it.transactionId == tx.id }
                    if (txSplits.isNotEmpty()) {
                        txSplits.forEach { s ->
                            totals[s.categoryId] = (totals[s.categoryId] ?: 0.0) + s.amount
                        }
                    } else {
                        totals[tx.categoryId] = (totals[tx.categoryId] ?: 0.0) + tx.amount
                    }
                }
                totals.mapNotNull { (catId, amt) ->
                    val cat = categories.find { it.id == catId }
                    if (cat != null) {
                        val pct = if (totalExpense > 0) (amt / totalExpense) else 0.0
                        Triple(cat, amt, pct)
                    } else null
                }.sortedByDescending { it.second }.take(5)
            }

            if (catBreakdown.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "CATEGORY SPENDING", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    TextButton(onClick = { viewModel.currentTab.value = "reports" }) {
                        Text(text = "View All", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        catBreakdown.forEach { (cat, amt, pct) ->
                            val catColor = try {
                                Color(android.graphics.Color.parseColor(cat.colour))
                            } catch (e: Exception) {
                                Color(0xFF059669)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onNavigateToCategoryDrillDown(cat.id) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(catColor), contentAlignment = Alignment.Center) {
                                        Text(text = cat.icon, fontSize = 14.sp)
                                    }
                                    Text(text = getBilingualName(cat.name, cat.nameHindi), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(text = formatINR(amt), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                    Text(text = "${String.format("%.1f", pct * 100)}%", fontSize = 10.sp, color = Color(0xFF64748B))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Top Merchants Section
        item {
            val merchantBreakdown = remember(expenseTxs) {
                expenseTxs.groupBy { it.merchant }
                    .mapValues { entry ->
                        Pair(entry.value.size, entry.value.sumOf { it.amount })
                    }
                    .toList()
                    .sortedByDescending { it.second.second }
                    .take(5)
            }

            if (merchantBreakdown.isNotEmpty()) {
                Text(text = "TOP MERCHANTS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), modifier = Modifier.padding(top = 8.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        merchantBreakdown.forEachIndexed { idx, (merchant, data) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToMerchantDrillDown(merchant) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text = merchant.ifEmpty { "Unknown" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                    Text(text = "${data.first} transaction${if (data.first != 1) "s" else ""}", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                }
                                Text(text = formatINR(data.second), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                            }
                            if (idx < merchantBreakdown.size - 1) {
                                HorizontalDivider(color = Color(0xFFF1F5F9))
                            }
                        }
                    }
                }
            }
        }

    }

    if (showEditBudgetDialog) {
        val currentMonthName = remember {
            java.text.SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(Date())
        }

        EditMonthlyBudgetDialog(
            monthLabel = currentMonthName,
            currentBudget = monthlyBudget,
            formatINR = formatINR,
            onDismiss = { showEditBudgetDialog = false },
            onSave = { newBudget ->
                viewModel.setMonthlyBudget(currentMonthKey, newBudget)
                showEditBudgetDialog = false
            }
        )
    }
}

@Composable
fun EditMonthlyBudgetDialog(
    monthLabel: String,
    currentBudget: Double,
    formatINR: (Double) -> String,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var newBudgetInput by remember {
        mutableStateOf(if (currentBudget % 1 == 0.0) currentBudget.toLong().toString() else currentBudget.toString())
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White,
        title = {
            Column {
                Text(
                    text = "Edit Monthly Budget",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = monthLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF64748B)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF8FAFC),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Current Budget",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = formatINR(currentBudget),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF0F172A)
                        )
                    }
                }

                OutlinedTextField(
                    value = newBudgetInput,
                    onValueChange = {
                        newBudgetInput = it
                        errorMessage = null
                    },
                    label = { Text("New Budget Amount") },
                    placeholder = { Text("Enter amount in ₹") },
                    prefix = { Text("₹ ", fontWeight = FontWeight.Bold, color = Color(0xFF0F172A)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = errorMessage != null,
                    supportingText = {
                        if (errorMessage != null) {
                            Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanText = newBudgetInput.trim().replace(",", "").replace("₹", "").trim()
                    if (cleanText.isEmpty()) {
                        errorMessage = "Please enter a budget amount"
                        return@Button
                    }
                    val amount = cleanText.toDoubleOrNull()
                    if (amount == null || amount <= 0.0) {
                        errorMessage = "Budget must be greater than zero"
                        return@Button
                    }
                    onSave(amount)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Cancel", color = Color(0xFF64748B), fontWeight = FontWeight.Medium)
            }
        }
    )
}

@Composable
fun HistoricalScanStatusCard(
    state: com.example.viewmodel.HistoricalScanState,
    onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (state) {
                is com.example.viewmodel.HistoricalScanState.Scanning -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Color(0xFF059669),
                            strokeWidth = 3.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Scanning SMS...",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val progressText = if (state.totalFound > 0) {
                                "Found ${state.totalFound} messages • Imported ${state.importedCount} transactions"
                            } else {
                                "Reading SMS inbox..."
                            }
                            Text(
                                text = progressText,
                                fontSize = 12.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                    if (state.totalFound > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (state.scannedCount.toFloat() / state.totalFound.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFF059669),
                            trackColor = Color(0xFFE2E8F0)
                        )
                    }
                }
                is com.example.viewmodel.HistoricalScanState.Completed -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFD1FAE5)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF059669),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SMS scan complete",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val msg = if (state.importedCount > 0) {
                                "${state.importedCount} new transactions imported\n${state.duplicatesCount} duplicates skipped • ${state.ignoredCount} non-financial ignored"
                            } else {
                                "0 new transactions imported\n${state.duplicatesCount} duplicates skipped • ${state.ignoredCount} non-financial ignored"
                            }
                            Text(
                                text = msg,
                                fontSize = 12.sp,
                                color = Color(0xFF64748B),
                                lineHeight = 16.sp
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
fun SmsAutoScanPromptCard(
    onGrant: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF059669)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Sms,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Track from SMS",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF065F46)
                    )
                    Text(
                        text = "Scan past 12 months SMS to import your financial transactions.",
                        fontSize = 12.sp,
                        color = Color(0xFF047857)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Not Now", fontSize = 12.sp, color = Color(0xFF64748B))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Allow SMS Access", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
