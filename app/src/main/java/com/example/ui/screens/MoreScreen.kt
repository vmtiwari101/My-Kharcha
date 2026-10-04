package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.utils.AuthManager
import com.example.utils.KharchaBackupManager
import com.example.utils.BackupStatus
import kotlinx.coroutines.launch
import com.example.viewmodel.KharchaViewModel

@Composable
fun MoreScreen(viewModel: KharchaViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kharcha_prefs", android.content.Context.MODE_PRIVATE) }
    val accounts by viewModel.accounts.collectAsState()
    val smsEnabled by viewModel.smsTrackingEnabled.collectAsState()
    val smsScanStatus by viewModel.smsScanStatus.collectAsState()
    val notifEnabled by viewModel.notificationTrackingEnabled.collectAsState()
    val notifScanStatus by viewModel.notificationScanStatus.collectAsState()
    val emailEnabled by viewModel.emailTrackingEnabled.collectAsState()
    val gmailConnected by viewModel.gmailConnected.collectAsState()
    val connectedEmail by viewModel.connectedEmailAddress.collectAsState()
    val emailScanStatus by viewModel.emailScanStatus.collectAsState()

    var showConnectGmailDialog by remember { mutableStateOf(false) }
    var inputEmail by remember { mutableStateOf("user@gmail.com") }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var showInitialSmsScanDialog by remember { mutableStateOf(false) }
    var showScanRangeDialog by remember { mutableStateOf(false) }
    var scanRangeMonths by remember { mutableStateOf(viewModel.getHistoricalScanRangeMonths()) }
    val historicalScanState by viewModel.historicalScanState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.setSmsTrackingEnabled(true, context)
            if (!viewModel.isSmsInitialScanCompleted()) {
                viewModel.startHistoricalSmsScan(context)
                snackbarMessage = "Scanning historical SMS..."
            } else {
                snackbarMessage = "SMS Permission granted."
            }
        } else {
            viewModel.setSmsTrackingEnabled(false, context)
            snackbarMessage = "SMS Permission denied."
        }
    }

    var isNotifListenerActive by remember { mutableStateOf(false) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val hasAccess = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName) == true
                isNotifListenerActive = hasAccess
                if (hasAccess) {
                    if (!notifEnabled) {
                        viewModel.setNotificationTrackingEnabled(true, context)
                    }
                } else {
                    if (notifEnabled) {
                        viewModel.setNotificationTrackingEnabled(false, context)
                    }
                }

                val hasSmsPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_SMS
                ) == PackageManager.PERMISSION_GRANTED
                if (hasSmsPermission) {
                    if (!smsEnabled) {
                        viewModel.setSmsTrackingEnabled(true, context)
                    }
                    if (!viewModel.isSmsInitialScanCompleted()) {
                        viewModel.startHistoricalSmsScan(context)
                    }
                } else {
                    if (smsEnabled) {
                        viewModel.setSmsTrackingEnabled(false, context)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "Accounts & More", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                Text(text = "Payment sources and preferences", fontSize = 11.sp, color = Color(0xFF64748B))
            }
            Button(
                onClick = { viewModel.currentTab.value = "accounts" },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD1FAE5), contentColor = Color(0xFF047857)),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Add Account", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                Card(
                    onClick = { viewModel.currentTab.value = "reports" },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF059669)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = Icons.Default.BarChart, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Column {
                                Text(text = "Reports & Analytics", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(text = "Monthly income, expenses & cash flow", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }
                        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = Color.White)
                    }
                }
            }

            item {
                Card(
                    onClick = { viewModel.currentTab.value = "accounts" },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF059669)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = Icons.Default.AccountBalance, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Column {
                                Text(text = "Manage All Accounts & Cards", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(text = "Edit, archive, and set default payment sources", fontSize = 11.sp, color = Color(0xFFECFDF5))
                            }
                        }
                        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = Color.White)
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "AUTOMATIC BACKGROUND SYNC STATUS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
            }

            item {
                val bgSyncEnabled = smsEnabled || (notifEnabled && isNotifListenerActive) || emailEnabled
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(if (bgSyncEnabled) Color(0xFF10B981) else Color(0xFF94A3B8))
                                )
                                Text(
                                    text = "Automatic Tracking",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                            }
                            Surface(
                                color = if (bgSyncEnabled) Color(0xFFECFDF5) else Color(0xFFF1F5F9),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (bgSyncEnabled) "ON" else "OFF",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (bgSyncEnabled) Color(0xFF059669) else Color(0xFF64748B),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        val lastAutoSyncTime = prefs.getString("last_auto_sync_time", "23 Sep, 4:30 PM") ?: "23 Sep, 4:30 PM"
                        val lastAutoSyncResult = prefs.getString("last_auto_sync_result", "Imported: 2, Updated: 0, Duplicates: 1, Ignored: 3, Needs Review: 0") ?: "Imported: 2, Updated: 0, Duplicates: 1, Ignored: 3, Needs Review: 0"

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Last automatic sync:", fontSize = 11.sp, color = Color(0xFF64748B))
                            Text(text = lastAutoSyncTime, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                        }

                        HorizontalDivider(color = Color(0xFFF1F5F9))

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(text = "Last result:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                            Text(
                                text = lastAutoSyncResult,
                                fontSize = 10.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "GOOGLE DRIVE DATA BACKUP & RESTORE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
            }

            item {
                val currentUser by AuthManager.currentUser.collectAsState()
                val backupTime by KharchaBackupManager.lastBackupTime.collectAsState()
                val backupPending by KharchaBackupManager.isBackupPending.collectAsState()
                val currentStatus by KharchaBackupManager.backupStatus.collectAsState()
                val coroutineScope = rememberCoroutineScope()

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFE0F2FE)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(18.dp))
                                }
                                Column {
                                    Text(text = "Google Drive Cloud Backup", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                    Text(text = if (currentUser?.email?.isNotBlank() == true) currentUser?.email ?: "" else "Google Account required", fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                            }
                            Surface(
                                color = if (backupPending) Color(0xFFFEF3C7) else Color(0xFFECFDF5),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (backupPending) "PENDING" else "SYNCED",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (backupPending) Color(0xFFD97706) else Color(0xFF059669),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0xFFF1F5F9))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Last Google Drive Backup:", fontSize = 11.sp, color = Color(0xFF64748B))
                            Text(text = backupTime, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val activity = context as? Activity
                                    val email = currentUser?.email?.ifBlank { null }
                                    if (activity != null && email != null) {
                                        snackbarMessage = "Authorizing Google Drive..."
                                        AuthManager.requestGoogleDriveAuthorization(activity, email, { token ->
                                            coroutineScope.launch {
                                                snackbarMessage = "Preparing Google Drive backup..."
                                                val userId = currentUser?.id ?: email
                                                val res = KharchaBackupManager.performGoogleDriveBackup(context, token, email, userId)
                                                if (res.isSuccess) {
                                                    val meta = res.getOrNull()
                                                    snackbarMessage = "Backup Successful!\n${meta?.transactionCount} txs, ${meta?.fileSizeFormatted} saved to Google Drive"
                                                } else {
                                                    snackbarMessage = res.exceptionOrNull()?.message ?: "Backup failed"
                                                }
                                            }
                                        }, { err ->
                                            snackbarMessage = err
                                        })
                                    } else {
                                        snackbarMessage = "Please sign in with a Google account to backup data."
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = "Backup Now", fontSize = 11.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    val activity = context as? Activity
                                    val email = currentUser?.email?.ifBlank { null }
                                    if (activity != null && email != null) {
                                        snackbarMessage = "Authorizing Google Drive..."
                                        AuthManager.requestGoogleDriveAuthorization(activity, email, { token ->
                                            coroutineScope.launch {
                                                snackbarMessage = "Restoring from Google Drive..."
                                                val res = KharchaBackupManager.performGoogleDriveRestore(context, token, email, overwriteLocal = false)
                                                if (res.isSuccess) {
                                                    snackbarMessage = res.getOrNull()
                                                } else {
                                                    snackbarMessage = res.exceptionOrNull()?.message ?: "Restore failed"
                                                }
                                            }
                                        }, { err ->
                                            snackbarMessage = err
                                        })
                                    } else {
                                        snackbarMessage = "Please sign in with a Google account to restore data."
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = "Restore", fontSize = 11.sp, color = Color(0xFF0284C7))
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "ACCOUNT & GOOGLE PROFILE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
            }

            item {
                val currentUser by AuthManager.currentUser.collectAsState()
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Color(0xFFE2E8F0)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Color(0xFF475569),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = currentUser?.name?.ifBlank { "Google User" } ?: "Google User",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    text = currentUser?.email?.ifBlank { "Signed in with Google" } ?: "Signed in with Google",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.logout(context)
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626))
                        ) {
                            Icon(imageVector = Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Sign Out", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }



            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "AUTOMATIC SMS TRACKING", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFFEF3C7)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(imageVector = Icons.Default.Message, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                                }
                                Column {
                                    Text(text = "Automatic SMS Tracking", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                    Text(text = "Scan bank & payment SMS automatically", fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                            }
                            Switch(
                                checked = smsEnabled,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.READ_SMS
                                        ) == PackageManager.PERMISSION_GRANTED

                                        if (hasPermission) {
                                            viewModel.setSmsTrackingEnabled(true, context)
                                            if (!viewModel.isSmsInitialScanCompleted()) {
                                                viewModel.startHistoricalSmsScan(context)
                                                snackbarMessage = "Scanning historical SMS..."
                                            }
                                        } else {
                                            permissionLauncher.launch(Manifest.permission.READ_SMS)
                                        }
                                    } else {
                                        viewModel.setSmsTrackingEnabled(false, context)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF059669))
                            )
                        }

                        HorizontalDivider(color = Color(0xFFF1F5F9))

                        // Scan Range Configuration
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Scan Range", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                val rangeLabel = if (viewModel.isScanRangeCustom()) {
                                    val start = viewModel.getScanRangeCustomStart()
                                    val end = viewModel.getScanRangeCustomEnd()
                                    val fmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
                                    "${fmt.format(java.util.Date(start))} - ${fmt.format(java.util.Date(end))}"
                                } else {
                                    when (scanRangeMonths) {
                                        1 -> "Last 1 Month"
                                        3 -> "Last 3 Months"
                                        6 -> "Last 6 Months"
                                        12 -> "Last 12 Months (Default)"
                                        0 -> "All Time"
                                        else -> "Last $scanRangeMonths Months"
                                    }
                                }
                                Text(text = rangeLabel, fontSize = 10.sp, color = Color(0xFF64748B))
                            }
                            OutlinedButton(
                                onClick = { showScanRangeDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Change", fontSize = 11.sp, color = Color(0xFF059669))
                            }
                        }

                        HorizontalDivider(color = Color(0xFFF1F5F9))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Last SMS Scan", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                Text(text = smsScanStatus, fontSize = 10.sp, color = Color(0xFF64748B))
                            }
                            val isScanning = historicalScanState is com.example.viewmodel.HistoricalScanState.Scanning
                            Button(
                                onClick = {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.READ_SMS
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (hasPermission) {
                                        viewModel.startHistoricalSmsScan(context, months = scanRangeMonths) { result ->
                                            snackbarMessage = "SMS Scan Complete\n${result.importedCount} new, ${result.skippedDuplicatesCount} duplicates skipped"
                                        }
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.READ_SMS)
                                    }
                                },
                                enabled = !isScanning,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                if (isScanning) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = "Scanning...", fontSize = 11.sp)
                                } else {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "Scan SMS Now", fontSize = 11.sp)
                                }
                            }
                        }

                        if (historicalScanState is com.example.viewmodel.HistoricalScanState.Scanning) {
                            val scanState = historicalScanState as com.example.viewmodel.HistoricalScanState.Scanning
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text(
                                    text = "Scanning SMS: ${scanState.scannedCount}/${scanState.totalFound} (Imported: ${scanState.importedCount}, Duplicates: ${scanState.duplicatesCount})",
                                    fontSize = 10.sp,
                                    color = Color(0xFF059669)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { if (scanState.totalFound > 0) (scanState.scannedCount.toFloat() / scanState.totalFound).coerceIn(0f, 1f) else 0f },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = Color(0xFF059669),
                                    trackColor = Color(0xFFE2E8F0)
                                )
                            }
                        }

                        if (snackbarMessage != null) {
                            Surface(
                                color = Color(0xFFECFDF5),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = snackbarMessage ?: "",
                                    fontSize = 11.sp,
                                    color = Color(0xFF047857),
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "AUTOMATIC NOTIFICATION TRACKING", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFEDE9FE)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(imageVector = Icons.Default.Notifications, contentDescription = null, tint = Color(0xFF7C3AED), modifier = Modifier.size(18.dp))
                                }
                                Column {
                                    Text(text = "Automatic Notification Tracking", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                    Text(text = "Monitor payment & banking notifications", fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                            }
                            Switch(
                                checked = notifEnabled && isNotifListenerActive,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        val active = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName) == true
                                        if (active) {
                                            viewModel.setNotificationTrackingEnabled(true, context)
                                        } else {
                                            snackbarMessage = "Please enable Notification Access for My Kharcha in system settings."
                                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                            context.startActivity(intent)
                                        }
                                    } else {
                                        viewModel.setNotificationTrackingEnabled(false, context)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF7C3AED))
                            )
                        }

                        HorizontalDivider(color = Color(0xFFF1F5F9))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "Notification Access & Scan Status", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                    Text(text = if (isNotifListenerActive) "Access Granted • $notifScanStatus" else "Access Required • Tap to Grant", fontSize = 10.sp, color = if (isNotifListenerActive) Color(0xFF059669) else Color(0xFFD97706))
                                }
                                if (isNotifListenerActive) {
                                    Button(
                                        onClick = {
                                            viewModel.scanNotificationsNow(context) { msg ->
                                                snackbarMessage = msg
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(text = "Scan Now", fontSize = 11.sp, color = Color.White)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                            context.startActivity(intent)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(text = "Grant Access", fontSize = 11.sp)
                                    }
                                }
                            }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "AUTOMATIC EMAIL TRACKING", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFFEE2E2)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(imageVector = Icons.Default.Email, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(18.dp))
                                }
                                Column {
                                    Text(text = "Automatic Email Tracking", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                    Text(text = "Monitor bank & payment emails (Gmail API)", fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                            }
                            Switch(
                                checked = emailEnabled,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (gmailConnected) {
                                            viewModel.setEmailTrackingEnabled(true, context)
                                        } else {
                                            showConnectGmailDialog = true
                                        }
                                    } else {
                                        viewModel.setEmailTrackingEnabled(false, context)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFDC2626))
                            )
                        }

                        HorizontalDivider(color = Color(0xFFF1F5F9))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Email Account", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                Text(text = if (gmailConnected) "Connected • $connectedEmail" else "Not connected", fontSize = 10.sp, color = if (gmailConnected) Color(0xFF059669) else Color(0xFFDC2626))
                            }
                            if (gmailConnected) {
                                OutlinedButton(
                                    onClick = { viewModel.disconnectGmail(context) },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(text = "Disconnect", fontSize = 11.sp, color = Color(0xFFDC2626))
                                }
                            } else {
                                Button(
                                    onClick = { showConnectGmailDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(text = "Connect Gmail", fontSize = 11.sp)
                                }
                            }
                        }

                        HorizontalDivider(color = Color(0xFFF1F5F9))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Last Email Scan & Result", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                                Text(text = emailScanStatus, fontSize = 10.sp, color = Color(0xFF64748B))
                            }
                            Button(
                                onClick = {
                                    if (gmailConnected) {
                                        viewModel.scanEmailsNow(context as Activity) { result ->
                                            snackbarMessage = "Email Scan Complete\n$result"
                                        }
                                    } else {
                                        showConnectGmailDialog = true
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = "Scan Emails Now", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConnectGmailDialog) {
        AlertDialog(
            onDismissRequest = { showConnectGmailDialog = false },
            title = { Text(text = "Connect Gmail Account", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Authorize secure read-only access to transaction emails using Google OAuth.", fontSize = 12.sp, color = Color(0xFF64748B))
                    OutlinedTextField(
                        value = inputEmail,
                        onValueChange = { inputEmail = it },
                        label = { Text("Gmail Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.connectGmail(inputEmail, context)
                        viewModel.setEmailTrackingEnabled(true, context)
                        showConnectGmailDialog = false
                        snackbarMessage = "Gmail Connected successfully ($inputEmail)"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Authorize & Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConnectGmailDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showScanRangeDialog) {
        var isCustom by remember { mutableStateOf(viewModel.isScanRangeCustom()) }
        var selectedMonths by remember { mutableStateOf(viewModel.getHistoricalScanRangeMonths()) }
        var customStart by remember { mutableStateOf(viewModel.getScanRangeCustomStart()) }
        var customEnd by remember { mutableStateOf(viewModel.getScanRangeCustomEnd()) }

        val dateFmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)

        AlertDialog(
            onDismissRequest = { showScanRangeDialog = false },
            title = { Text(text = "Configure SMS Scan Range", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Choose the range of SMS messages to analyze during tracking and manual scans.",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !isCustom,
                            onClick = { isCustom = false },
                            label = { Text("Standard Presets", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF059669),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = isCustom,
                            onClick = { isCustom = true },
                            label = { Text("Custom Range", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF059669),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (!isCustom) {
                        val presetOptions = listOf(
                            1 to "Last 1 Month",
                            3 to "Last 3 Months",
                            6 to "Last 6 Months",
                            12 to "Last 12 Months (Default)",
                            0 to "All Time"
                        )

                        presetOptions.forEach { (m, label) ->
                            val isSel = selectedMonths == m
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) Color(0xFFECFDF5) else Color.Transparent)
                                    .clickable { selectedMonths = m }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSel,
                                    onClick = { selectedMonths = m },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF059669))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) Color(0xFF047857) else Color(0xFF334155)
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column {
                                Text("Start Date", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569))
                                OutlinedButton(
                                    onClick = {
                                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = customStart }
                                        android.app.DatePickerDialog(
                                            context,
                                            { _, year, month, dayOfMonth ->
                                                val newCal = java.util.Calendar.getInstance().apply {
                                                    set(java.util.Calendar.YEAR, year)
                                                    set(java.util.Calendar.MONTH, month)
                                                    set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                                                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                                                    set(java.util.Calendar.MINUTE, 0)
                                                    set(java.util.Calendar.SECOND, 0)
                                                    set(java.util.Calendar.MILLISECOND, 0)
                                                }
                                                customStart = newCal.timeInMillis
                                            },
                                            cal.get(java.util.Calendar.YEAR),
                                            cal.get(java.util.Calendar.MONTH),
                                            cal.get(java.util.Calendar.DAY_OF_MONTH)
                                        ).show()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF059669))
                                ) {
                                    Icon(imageVector = Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = dateFmt.format(java.util.Date(customStart)), fontSize = 12.sp)
                                }
                            }

                            Column {
                                Text("End Date", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569))
                                OutlinedButton(
                                    onClick = {
                                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = customEnd }
                                        android.app.DatePickerDialog(
                                            context,
                                            { _, year, month, dayOfMonth ->
                                                val newCal = java.util.Calendar.getInstance().apply {
                                                    set(java.util.Calendar.YEAR, year)
                                                    set(java.util.Calendar.MONTH, month)
                                                    set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                                                    set(java.util.Calendar.HOUR_OF_DAY, 23)
                                                    set(java.util.Calendar.MINUTE, 59)
                                                    set(java.util.Calendar.SECOND, 59)
                                                    set(java.util.Calendar.MILLISECOND, 999)
                                                }
                                                customEnd = newCal.timeInMillis
                                            },
                                            cal.get(java.util.Calendar.YEAR),
                                            cal.get(java.util.Calendar.MONTH),
                                            cal.get(java.util.Calendar.DAY_OF_MONTH)
                                        ).show()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF059669))
                                ) {
                                    Icon(imageVector = Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = dateFmt.format(java.util.Date(customEnd)), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setScanRangeCustom(isCustom)
                        if (isCustom) {
                            viewModel.setScanRangeCustomStart(customStart)
                            viewModel.setScanRangeCustomEnd(customEnd)
                        } else {
                            viewModel.setHistoricalScanRangeMonths(selectedMonths)
                        }
                        scanRangeMonths = viewModel.getHistoricalScanRangeMonths()
                        showScanRangeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save Range", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showScanRangeDialog = false }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        )
    }

    if (showInitialSmsScanDialog) {
        InitialSmsScanDialog(
            context = context,
            onStartScan = { startTimestamp ->
                showInitialSmsScanDialog = false
                viewModel.markSmsInitialScanCompleted()
                viewModel.scanSmsNow(context, newerThanTimestamp = startTimestamp) { result ->
                    snackbarMessage = "SMS Scan Complete\n$result"
                }
            },
            onSkip = {
                showInitialSmsScanDialog = false
                viewModel.markSmsInitialScanCompleted()
                snackbarMessage = "Initial scan skipped. Automatic tracking is active."
            }
        )
    }
}

enum class SmsScanRangeOption(val label: String) {
    TODAY("Today"),
    LAST_7_DAYS("Last 7 Days"),
    LAST_30_DAYS("Last 30 Days"),
    LAST_3_MONTHS("Last 3 Months"),
    LAST_6_MONTHS("Last 6 Months"),
    LAST_1_YEAR("Last 1 Year"),
    CUSTOM_DATE("Custom Date")
}

@Composable
fun InitialSmsScanDialog(
    context: android.content.Context,
    onStartScan: (Long) -> Unit,
    onSkip: () -> Unit
) {
    var selectedOption by remember { mutableStateOf(SmsScanRangeOption.LAST_30_DAYS) }
    var customTimestamp by remember {
        mutableStateOf(
            java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, -30)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        )
    }

    val startTimestamp = remember(selectedOption, customTimestamp) {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)

        when (selectedOption) {
            SmsScanRangeOption.TODAY -> cal.timeInMillis
            SmsScanRangeOption.LAST_7_DAYS -> {
                cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
                cal.timeInMillis
            }
            SmsScanRangeOption.LAST_30_DAYS -> {
                cal.add(java.util.Calendar.DAY_OF_YEAR, -30)
                cal.timeInMillis
            }
            SmsScanRangeOption.LAST_3_MONTHS -> {
                cal.add(java.util.Calendar.MONTH, -3)
                cal.timeInMillis
            }
            SmsScanRangeOption.LAST_6_MONTHS -> {
                cal.add(java.util.Calendar.MONTH, -6)
                cal.timeInMillis
            }
            SmsScanRangeOption.LAST_1_YEAR -> {
                cal.add(java.util.Calendar.YEAR, -1)
                cal.timeInMillis
            }
            SmsScanRangeOption.CUSTOM_DATE -> customTimestamp
        }
    }

    val dateFmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
    val formattedDate = dateFmt.format(java.util.Date(startTimestamp))

    AlertDialog(
        onDismissRequest = onSkip,
        icon = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFEF3C7)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Message,
                    contentDescription = null,
                    tint = Color(0xFFD97706),
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = "How far back should My Kharcha scan your SMS?",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SmsScanRangeOption.values().forEach { option ->
                    val isSelected = selectedOption == option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) Color(0xFFECFDF5) else Color.Transparent)
                            .clickable { selectedOption = option }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { selectedOption = option },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF059669))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = option.label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color(0xFF047857) else Color(0xFF334155)
                        )
                    }
                }

                if (selectedOption == SmsScanRangeOption.CUSTOM_DATE) {
                    OutlinedButton(
                        onClick = {
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = customTimestamp }
                            android.app.DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    val newCal = java.util.Calendar.getInstance().apply {
                                        set(java.util.Calendar.YEAR, year)
                                        set(java.util.Calendar.MONTH, month)
                                        set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                                        set(java.util.Calendar.MINUTE, 0)
                                        set(java.util.Calendar.SECOND, 0)
                                        set(java.util.Calendar.MILLISECOND, 0)
                                    }
                                    customTimestamp = newCal.timeInMillis
                                },
                                cal.get(java.util.Calendar.YEAR),
                                cal.get(java.util.Calendar.MONTH),
                                cal.get(java.util.Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF059669))
                    ) {
                        Icon(imageVector = Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Select Starting Date: $formattedDate", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    color = Color(0xFFECFDF5),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Transactions from $formattedDate onward will be scanned.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF047857),
                        modifier = Modifier.padding(10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStartScan(startTimestamp) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(text = "Start Scan", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onSkip,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(text = "Skip for Now", fontSize = 13.sp, color = Color(0xFF64748B))
            }
        }
    )
}
