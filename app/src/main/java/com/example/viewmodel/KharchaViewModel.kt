package com.example.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.data.repository.KharchaRepository
import com.example.utils.EmailParser
import com.example.utils.EmailParserStatus
import com.example.utils.SmsParseResult
import com.example.utils.SmsParser
import com.example.utils.TransactionIngestionEngine
import com.example.utils.MerchantLearningEngine
import com.example.utils.AuthManager
import com.example.utils.KharchaBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail

sealed class HistoricalScanState {
    object Idle : HistoricalScanState()
    data class Scanning(
        val scannedCount: Int,
        val totalFound: Int,
        val importedCount: Int,
        val duplicatesCount: Int
    ) : HistoricalScanState()
    data class Completed(
        val totalFound: Int,
        val importedCount: Int,
        val updatedCount: Int,
        val duplicatesCount: Int,
        val ignoredCount: Int
    ) : HistoricalScanState()
    data class Error(val message: String) : HistoricalScanState()
}

class KharchaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: KharchaRepository

    val transactions: StateFlow<List<TransactionEntity>>
    val categories: StateFlow<List<CategoryEntity>>
    val subcategories: StateFlow<List<SubcategoryEntity>>
    val accounts: StateFlow<List<AccountEntity>>
    val transactionSplits: StateFlow<List<TransactionSplitEntity>>
    val cards: StateFlow<List<CardEntity>>

    val smsTrackingEnabled = MutableStateFlow(false)
    val smsScanStatus = MutableStateFlow("Never scanned")
    val historicalScanState = MutableStateFlow<HistoricalScanState>(HistoricalScanState.Idle)
    val notificationTrackingEnabled = MutableStateFlow(false)
    val notificationScanStatus = MutableStateFlow("Never scanned")
    val emailTrackingEnabled = MutableStateFlow(false)
    val gmailConnected = MutableStateFlow(false)
    val connectedEmailAddress = MutableStateFlow("Not connected")
    val emailScanStatus = MutableStateFlow("Never scanned")
    private val prefs = application.getSharedPreferences("kharcha_prefs", Context.MODE_PRIVATE)

    var selectedMonth = MutableStateFlow("2026-09")

    private fun getNowIsoString(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
    }
    var currentTab = MutableStateFlow("home") // "home", "transactions", "categories", "more", "reports", "accounts"
    var transactionSubTab = MutableStateFlow("txs") // "txs", "cats", "merchants"

    // Date range for Dashboard/Reports/DrillDown
    var selectedRange = MutableStateFlow("THIS_MONTH")
    var customStart = MutableStateFlow<java.util.Date?>(null)
    var customEnd = MutableStateFlow<java.util.Date?>(null)

    // Drill down states
    var drillDownCategoryId = MutableStateFlow<String?>(null)
    var drillDownSubcategoryId = MutableStateFlow<String?>(null)
    var drillDownAccountId = MutableStateFlow<String?>(null)
    var drillDownMerchantName = MutableStateFlow<String?>(null)
    var drillDownSourceKey = MutableStateFlow<String?>(null)
    var lastMainTab = MutableStateFlow("home")

    init {
        val dao = AppDatabase.getDatabase(application).kharchaDao()
        repository = KharchaRepository(dao)

        val hasSms = androidx.core.content.ContextCompat.checkSelfPermission(
            application,
            android.Manifest.permission.READ_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        smsTrackingEnabled.value = hasSms
        prefs.edit().putBoolean("sms_tracking_enabled", hasSms).apply()

        val hasNotif = android.provider.Settings.Secure.getString(
            application.contentResolver,
            "enabled_notification_listeners"
        )?.contains(application.packageName) == true
        notificationTrackingEnabled.value = hasNotif
        prefs.edit().putBoolean("notification_tracking_enabled", hasNotif).apply()
        emailTrackingEnabled.value = prefs.getBoolean("email_tracking_enabled", false)
        gmailConnected.value = prefs.getBoolean("gmail_connected", false)
        connectedEmailAddress.value = prefs.getString("gmail_account", "Not connected") ?: "Not connected"
        emailScanStatus.value = prefs.getString("last_email_scan", "Never scanned") ?: "Never scanned"

        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "last_email_scan" -> {
                    emailScanStatus.value = prefs.getString("last_email_scan", "Never scanned") ?: "Never scanned"
                }
                "email_tracking_enabled" -> {
                    emailTrackingEnabled.value = prefs.getBoolean("email_tracking_enabled", false)
                }
                "gmail_connected" -> {
                    gmailConnected.value = prefs.getBoolean("gmail_connected", false)
                }
                "gmail_account" -> {
                    connectedEmailAddress.value = prefs.getString("gmail_account", "Not connected") ?: "Not connected"
                }
            }
        }

        transactions = repository.allTransactions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        categories = repository.allCategories.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        subcategories = repository.allSubcategories.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        accounts = repository.allAccounts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        transactionSplits = repository.allSplits.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        cards = repository.allCards.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Perform one-time duplicate cleanup, category expansion, and merchant normalization on startup
        viewModelScope.launch(Dispatchers.IO) {
            com.example.data.DefaultCategoryData.restoreAndExpandCategoriesAndSubcategories(dao)
            repository.cleanupDuplicateTransactions()
            TransactionIngestionEngine.normalizeExistingTransactions(dao)
            com.example.utils.CreditCardReminderManager.rescheduleAllAsync(application)
            repairIdentityMappingIfNeeded(dao)
        }
    }

    private suspend fun repairIdentityMappingIfNeeded(dao: com.example.data.dao.KharchaDao) {
        try {
            val accounts = dao.getAllAccountsSync()
            val transactions = dao.getAllTransactionsSync()
            
            val acc2345 = accounts.find { it.last4Digits == "2345" && (it.bankName.contains("Axis", true) || it.name.contains("Axis", true)) }
            if (acc2345 != null) {
                val linkedTx = transactions.filter { it.accountId == acc2345.id }
                val isIdfcEvidence = linkedTx.any { tx -> 
                    val text = "${tx.note} ${tx.merchant} ${tx.originalReference}".lowercase()
                    text.contains("idfc") || tx.source.contains("idfc", true)
                } || linkedTx.isEmpty()

                if (isIdfcEvidence) {
                    val repairedAcc = acc2345.copy(
                        name = "IDFC FIRST Bank Account •••• 2345",
                        bankName = "IDFC FIRST Bank",
                        type = "Bank Account",
                        updatedAt = java.time.Instant.now().toString()
                    )
                    dao.insertAccount(repairedAcc)
                    android.util.Log.d("KharchaRepair", "Safely repaired account 2345 from Axis Bank to IDFC FIRST Bank")
                }
            }

            val card1843 = accounts.find { it.last4Digits == "1843" }
            if (card1843 == null) {
                val newAxisCardAcc = AccountEntity(
                    id = "acc-auto-axis-1843",
                    name = "Axis Bank Credit Card •••• 1843",
                    type = "Credit Card",
                    bankName = "Axis Bank",
                    last4Digits = "1843",
                    icon = "credit-card",
                    colour = "#7C3AED",
                    isActive = true,
                    isOwnedByMe = true,
                    createdAt = java.time.Instant.now().toString(),
                    updatedAt = java.time.Instant.now().toString()
                )
                dao.insertAccount(newAxisCardAcc)
            }
        } catch (e: Exception) {
            android.util.Log.e("KharchaRepair", "Error repairing identity mapping", e)
        }
    }

    fun cleanupDuplicates(onComplete: ((Int) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = repository.cleanupDuplicateTransactions()
            withContext(Dispatchers.Main) {
                onComplete?.invoke(removed)
            }
        }
    }

    // Auto-sync & Restore
    fun performAutoSync(context: Context) {
        KharchaBackupManager.markBackupPending(context)
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.example.data.firestore.RoomToFirestoreSyncEngine().syncAllLocalDataToFirestore(context)
            }
        }
    }

    fun restoreFromCloud(context: Context, onResult: ((com.example.data.firestore.RestoreResult) -> Unit)? = null) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val res = com.example.data.firestore.FirestoreToRoomRestoreEngine().restoreCloudDataToRoom(context)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onResult?.invoke(res)
            }
        }
    }

    // SMS Tracking
    fun setSmsTracking(enabled: Boolean) {
        smsTrackingEnabled.value = enabled
        prefs.edit().putBoolean("sms_tracking_enabled", enabled).apply()
    }

    fun setSmsTrackingEnabled(enabled: Boolean, context: Context) {
        smsTrackingEnabled.value = enabled
        prefs.edit().putBoolean("sms_tracking_enabled", enabled).apply()
    }

    fun updateSmsScanStatus(status: String) {
        smsScanStatus.value = status
        prefs.edit().putString("sms_scan_status", status).apply()
    }

    fun markSmsInitialScanCompleted() {
        prefs.edit().putBoolean("sms_initial_scan_completed", true).apply()
    }

    fun isSmsInitialScanCompleted(): Boolean {
        return prefs.getBoolean("sms_initial_scan_completed", false)
    }

    fun getHistoricalScanRangeMonths(): Int {
        return prefs.getInt("historical_sms_scan_range_months", 12)
    }

    fun setHistoricalScanRangeMonths(months: Int) {
        prefs.edit().putInt("historical_sms_scan_range_months", months).apply()
    }

    fun isScanRangeCustom(): Boolean {
        return prefs.getString("historical_sms_scan_range_type", "PRESET") == "CUSTOM"
    }

    fun setScanRangeCustom(isCustom: Boolean) {
        prefs.edit().putString("historical_sms_scan_range_type", if (isCustom) "CUSTOM" else "PRESET").apply()
    }

    fun getScanRangeCustomStart(): Long {
        val defaultStart = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.MONTH, -12)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return prefs.getLong("historical_sms_scan_custom_start", defaultStart)
    }

    fun setScanRangeCustomStart(timestamp: Long) {
        prefs.edit().putLong("historical_sms_scan_custom_start", timestamp).apply()
    }

    fun getScanRangeCustomEnd(): Long {
        return prefs.getLong("historical_sms_scan_custom_end", System.currentTimeMillis())
    }

    fun setScanRangeCustomEnd(timestamp: Long) {
        prefs.edit().putLong("historical_sms_scan_custom_end", timestamp).apply()
    }

    fun getHistoricalScanStartTimestamp(months: Int = getHistoricalScanRangeMonths()): Long {
        if (isScanRangeCustom()) {
            return getScanRangeCustomStart()
        }
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MONTH, -months)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun getHistoricalScanEndTimestamp(): Long {
        if (isScanRangeCustom()) {
            return getScanRangeCustomEnd()
        }
        return System.currentTimeMillis()
    }

    fun dismissHistoricalScanState() {
        historicalScanState.value = HistoricalScanState.Idle
    }

    fun startHistoricalSmsScan(
        context: Context,
        months: Int = getHistoricalScanRangeMonths(),
        onComplete: ((SmsParseResult) -> Unit)? = null
    ) {
        viewModelScope.launch {
            markSmsInitialScanCompleted()
            setSmsTrackingEnabled(true, context)
            val startTimestamp = getHistoricalScanStartTimestamp(months)
            val endTimestamp = getHistoricalScanEndTimestamp()
            historicalScanState.value = HistoricalScanState.Scanning(0, 0, 0, 0)

            val result = SmsParser.scanInbox(
                context = context,
                newerThanTimestamp = startTimestamp,
                olderThanTimestamp = endTimestamp,
                onProgress = { scanned, total, imported, dups ->
                    historicalScanState.value = HistoricalScanState.Scanning(scanned, total, imported, dups)
                }
            )

            val count = result.importedCount + result.updatedCount
            val nowFmt = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.US).format(java.util.Date())
            val status = "Last scanned: $nowFmt ($count imported)"
            updateSmsScanStatus(status)

            // Persist scan counters in SharedPreferences for accurate UI display
            prefs.edit()
                .putInt("sms_imported_count", result.importedCount)
                .putInt("sms_updated_count", result.updatedCount)
                .putInt("sms_duplicate_count", result.skippedDuplicatesCount)
                .putInt("sms_ignored_count", result.ignoredCount)
                .putInt("sms_needs_review_count", result.needsReviewCount)
                .apply()

            // Mark cloud backup pending for currently logged in user
            com.example.utils.KharchaBackupManager.markBackupPending(context)

            val totalFound = result.importedCount + result.updatedCount + result.skippedDuplicatesCount + result.ignoredCount + result.parsingFailedCount + result.needsReviewCount
            historicalScanState.value = HistoricalScanState.Completed(
                totalFound = totalFound,
                importedCount = result.importedCount,
                updatedCount = result.updatedCount,
                duplicatesCount = result.skippedDuplicatesCount,
                ignoredCount = result.ignoredCount + result.parsingFailedCount
            )

            onComplete?.invoke(result)
        }
    }

    fun scanSmsNow(context: Context, newerThanTimestamp: Long = 0, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = SmsParser.scanInbox(context, newerThanTimestamp = newerThanTimestamp)
            val count = result.importedCount + result.updatedCount
            val nowFmt = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.US).format(java.util.Date())
            val status = "Last scanned: $nowFmt ($count imported)"
            updateSmsScanStatus(status)

            // Persist scan counters in SharedPreferences for accurate UI display
            prefs.edit()
                .putInt("sms_imported_count", result.importedCount)
                .putInt("sms_updated_count", result.updatedCount)
                .putInt("sms_duplicate_count", result.skippedDuplicatesCount)
                .putInt("sms_ignored_count", result.ignoredCount)
                .putInt("sms_needs_review_count", result.needsReviewCount)
                .apply()

            com.example.utils.KharchaBackupManager.markBackupPending(context)

            onResult("${result.importedCount} new, ${result.updatedCount} updated, ${result.skippedDuplicatesCount} duplicates skipped")
        }
    }

    // Notification Tracking
    fun setNotificationTracking(enabled: Boolean) {
        notificationTrackingEnabled.value = enabled
        prefs.edit().putBoolean("notification_tracking_enabled", enabled).apply()
    }

    fun setNotificationTrackingEnabled(enabled: Boolean, context: Context) {
        val hasNotif = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )?.contains(context.packageName) == true
        val state = enabled && hasNotif
        notificationTrackingEnabled.value = state
        prefs.edit().putBoolean("notification_tracking_enabled", state).apply()
        if (state) {
            viewModelScope.launch {
                val (imported, duplicates) = com.example.service.KharchaNotificationListenerService.scanActiveNotifications(context)
                val nowFmt = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.US).format(java.util.Date())
                val status = "Last scanned: $nowFmt ($imported imported)"
                updateNotificationScanStatus(status)
            }
        }
    }

    fun updateNotificationScanStatus(status: String) {
        notificationScanStatus.value = status
        prefs.edit().putString("last_notif_scan", status).apply()
    }

    fun scanNotificationsNow(context: Context, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val (imported, duplicates) = com.example.service.KharchaNotificationListenerService.scanActiveNotifications(context)
            val nowFmt = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.US).format(java.util.Date())
            val status = "Last scanned: $nowFmt ($imported imported)"
            updateNotificationScanStatus(status)
            com.example.utils.KharchaBackupManager.markBackupPending(context)
            onResult("Notification scan complete. $imported new imported, $duplicates duplicates skipped.")
        }
    }

    // Email Tracking
    fun setEmailTracking(enabled: Boolean, context: Context? = null) {
        emailTrackingEnabled.value = enabled
        prefs.edit().putBoolean("email_tracking_enabled", enabled).apply()
        val ctx = context ?: getApplication<Application>()
        if (enabled && gmailConnected.value) {
            com.example.worker.EmailTrackingScheduler.schedule(ctx)
            if (emailScanStatus.value == "Never scanned" || emailScanStatus.value.contains("paused", true)) {
                updateEmailScanStatus("Automatic tracking active")
            }
        } else {
            com.example.worker.EmailTrackingScheduler.cancel(ctx)
            if (!enabled) {
                updateEmailScanStatus("Automatic tracking paused")
            }
        }
    }

    fun setEmailTrackingEnabled(enabled: Boolean, context: Context) {
        setEmailTracking(enabled, context)
    }

    fun connectGmail(account: String, context: Context? = null) {
        gmailConnected.value = true
        connectedEmailAddress.value = account
        prefs.edit()
            .putBoolean("gmail_connected", true)
            .putString("gmail_account", account)
            .apply()
        val ctx = context ?: getApplication<Application>()
        if (emailTrackingEnabled.value) {
            com.example.worker.EmailTrackingScheduler.schedule(ctx)
            updateEmailScanStatus("Automatic tracking active")
        }
    }

    fun disconnectGmail(context: Context? = null) {
        gmailConnected.value = false
        connectedEmailAddress.value = "Not connected"
        prefs.edit()
            .putBoolean("gmail_connected", false)
            .putString("gmail_account", "Not connected")
            .apply()
        val ctx = context ?: getApplication<Application>()
        com.example.worker.EmailTrackingScheduler.cancel(ctx)
        updateEmailScanStatus("Gmail disconnected")
    }

    fun updateEmailScanStatus(status: String) {
        emailScanStatus.value = status
        prefs.edit().putString("last_email_scan", status).apply()
    }

    private val isScanningEmails = AtomicBoolean(false)

    fun scanEmailsNow(activity: Activity, onResult: (String) -> Unit) {
        val email = connectedEmailAddress.value
        if (email == "Not connected") {
            onResult("Gmail not connected.")
            return
        }

        if (!isScanningEmails.compareAndSet(false, true)) {
            onResult("Email scan is already in progress.")
            return
        }

        AuthManager.requestGmailAuthorization(activity, email, { token ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val nowFmt = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.US).format(java.util.Date())
                    val transport = NetHttpTransport()
                    val jsonFactory = com.google.api.client.json.gson.GsonFactory.getDefaultInstance()
                    val credential = com.google.api.client.googleapis.auth.oauth2.GoogleCredential().setAccessToken(token)
                    
                    val gmailService = com.google.api.services.gmail.Gmail.Builder(transport, jsonFactory, credential)
                        .setApplicationName("My Kharcha")
                        .build()

                    var messagesResponse: com.google.api.services.gmail.model.ListMessagesResponse? = null
                    var attempt = 0
                    val maxRetries = 3
                    var success = false

                    while (attempt < maxRetries && !success) {
                        try {
                            messagesResponse = gmailService.users().messages().list("me")
                                .setQ("label:INBOX")
                                .setMaxResults(20)
                                .execute()
                            success = true
                        } catch (e: Exception) {
                            val msg = e.message ?: ""
                            val isRateLimit = msg.contains("403") || msg.contains("RATE_LIMIT_EXCEEDED", true) || msg.contains("quotaExceeded", true)
                            if (isRateLimit && attempt < maxRetries - 1) {
                                attempt++
                                val delayMs = (1L shl attempt) * 1000L
                                kotlinx.coroutines.delay(delayMs)
                            } else if (isRateLimit) {
                                onResult("Gmail rate limit reached. Please try again later.")
                                return@launch
                            } else {
                                throw e
                            }
                        }
                    }

                    val messages = messagesResponse?.messages ?: emptyList()
                    val dao = AppDatabase.getDatabase(getApplication()).kharchaDao()
                    val existingTxs = dao.getAllTransactionsSync()
                    val processedRefs = existingTxs.mapNotNull { it.originalReference }.toSet()

                    var imported = 0
                    var duplicates = 0
                    var failed = 0
                    var ignored = 0

                    for (message in messages) {
                        val expectedRef = "EMAIL-${message.id}"
                        if (processedRefs.contains(expectedRef)) {
                            duplicates++
                            continue
                        }

                        var msg: com.google.api.services.gmail.model.Message? = null
                        var msgAttempt = 0
                        var msgSuccess = false
                        while (msgAttempt < maxRetries && !msgSuccess) {
                            try {
                                msg = gmailService.users().messages().get("me", message.id).execute()
                                msgSuccess = true
                            } catch (e: Exception) {
                                val err = e.message ?: ""
                                val isRateLimit = err.contains("403") || err.contains("RATE_LIMIT_EXCEEDED", true) || err.contains("quotaExceeded", true)
                                if (isRateLimit && msgAttempt < maxRetries - 1) {
                                    msgAttempt++
                                    kotlinx.coroutines.delay((1L shl msgAttempt) * 1000L)
                                } else if (isRateLimit) {
                                    break
                                } else {
                                    throw e
                                }
                            }
                        }

                        if (msg == null) continue

                        val subject = msg.payload?.headers?.find { it.name.equals("Subject", true) }?.value ?: ""
                        val body = msg.snippet ?: ""
                        val internalDate = msg.internalDate ?: System.currentTimeMillis()

                        val fullEmailText = "$subject $body"
                        if (com.example.utils.CreditCardBillIngestionEngine.isCreditCardBillMessage(fullEmailText)) {
                            val billInfo = com.example.utils.CreditCardBillIngestionEngine.extractBillInfo(body, subject, "EMAIL", message.id, internalDate)
                            if (billInfo != null) {
                                val result = com.example.utils.CreditCardBillIngestionEngine.ingestBillInfo(getApplication(), billInfo)
                                when (result) {
                                    is com.example.utils.BillIngestionResult.Updated -> imported++
                                    is com.example.utils.BillIngestionResult.Duplicate -> duplicates++
                                    else -> ignored++
                                }
                            } else {
                                ignored++
                            }
                            continue
                        }

                        val parseResult = EmailParser.parseEmail(message.id, subject, body, internalDate, existingTxs)

                        if (parseResult.transaction != null) {
                            val ingestionResult = TransactionIngestionEngine.ingestTransaction(
                                getApplication(), parseResult.transaction, "$subject $body"
                            )
                            when (ingestionResult.second) {
                                com.example.utils.IngestionStatus.IMPORTED -> imported++
                                com.example.utils.IngestionStatus.DUPLICATE -> duplicates++
                                else -> failed++
                            }
                        } else {
                            if (parseResult.status == com.example.utils.EmailParserStatus.IGNORED) ignored++
                            else failed++
                        }
                    }

                    val status = "Last scanned: $nowFmt ($imported imported)"
                    updateEmailScanStatus(status)
                    onResult("Scan complete: $imported imported, $duplicates duplicates, $ignored ignored.")

                } catch (e: Exception) {
                    Log.e("GmailScan", "Error scanning emails: ${e.message}", e)
                    val errMsg = e.message ?: ""
                    if (errMsg.contains("403") || errMsg.contains("RATE_LIMIT_EXCEEDED", true) || errMsg.contains("quotaExceeded", true)) {
                        onResult("Gmail rate limit reached. Please try again later.")
                    } else {
                        onResult("Gmail scanning failed: ${e.message}")
                    }
                } finally {
                    isScanningEmails.set(false)
                }
            }
        }, { error ->
            isScanningEmails.set(false)
            onResult("Gmail authorization failed: $error")
        })
    }

    // Transaction CRUD
    fun addTransferTransaction(
        amount: Double,
        date: String,
        time: String,
        merchant: String,
        accountId: String,
        counterpartyAccountId: String? = null,
        note: String = ""
    ) {
        val now = getNowIsoString()
        val id = "tx-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        val tx = TransactionEntity(
            id = id,
            type = "INTERNAL_TRANSFER",
            transactionType = "INTERNAL_TRANSFER",
            isInternalTransfer = true,
            amount = amount,
            date = date,
            time = time,
            merchant = merchant,
            categoryId = "cat-transfer",
            subcategoryId = "",
            accountId = accountId,
            counterpartyAccountId = counterpartyAccountId,
            paymentMethod = "TRANSFER",
            note = note,
            source = "MANUAL",
            transactionReference = "TXN-${System.currentTimeMillis()}",
            originalReference = "",
            last4Digits = "",
            createdAt = now,
            updatedAt = now,
            direction = "DEBIT",
            isExpense = false
        )
        viewModelScope.launch {
            repository.insertTransaction(tx)
        }
    }

    fun addTransaction(
        type: String,
        amount: Double,
        date: String,
        time: String,
        merchant: String,
        categoryId: String,
        subcategoryId: String,
        accountId: String,
        paymentMethod: String,
        note: String,
        isExpense: Boolean = (type == "EXPENSE")
    ) {
        val now = getNowIsoString()
        val id = "tx-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        val isDebit = type == "EXPENSE"
        val tx = TransactionEntity(
            id = id,
            type = type,
            amount = amount,
            date = date,
            time = time,
            merchant = merchant,
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            accountId = accountId,
            paymentMethod = paymentMethod,
            note = note,
            source = "MANUAL",
            transactionReference = "TXN-${System.currentTimeMillis()}",
            originalReference = "",
            last4Digits = "",
            createdAt = now,
            updatedAt = now,
            direction = if (isDebit) "DEBIT" else "CREDIT",
            isExpense = if (isDebit) isExpense else false
        )
        viewModelScope.launch {
            repository.insertTransaction(tx)
            MerchantLearningEngine.saveMapping(getApplication(), tx.merchant, tx.categoryId, tx.subcategoryId)
        }
    }

    fun updateTransaction(
        id: String,
        type: String,
        amount: Double,
        date: String,
        time: String,
        merchant: String,
        categoryId: String,
        subcategoryId: String,
        accountId: String,
        paymentMethod: String,
        note: String,
        isExpense: Boolean = true
    ) {
        viewModelScope.launch {
            val existing = transactions.value.find { it.id == id }
                ?: repository.allTransactions.first().find { it.id == id }
            val now = getNowIsoString()
            val isTransfer = type == "INTERNAL_TRANSFER"
            
            val finalIsExpense = if (isTransfer || type == "INCOME") false else isExpense
            val finalIsInternalTransfer = isTransfer
            val finalTransactionType = if (isTransfer) {
                if (existing?.transactionType != null && existing.transactionType != "INTERNAL_TRANSFER") existing.transactionType else "INTERNAL_TRANSFER"
            } else null
            val finalDirection = if (isTransfer) {
                existing?.direction ?: "DEBIT"
            } else {
                if (type == "INCOME") "CREDIT" else "DEBIT"
            }
            val finalCounterpartyAccountId = if (isTransfer) existing?.counterpartyAccountId else null
            val finalTransferGroupId = if (isTransfer) existing?.transferGroupId else null
            val finalNeedsReview = if (isTransfer) (existing?.needsReview ?: true) else false

            val updated = (existing?.copy(
                type = type,
                amount = amount,
                date = date,
                time = time,
                merchant = merchant,
                categoryId = categoryId,
                subcategoryId = subcategoryId,
                accountId = accountId,
                paymentMethod = paymentMethod,
                note = note,
                isExpense = finalIsExpense,
                isInternalTransfer = finalIsInternalTransfer,
                transactionType = finalTransactionType,
                direction = finalDirection,
                counterpartyAccountId = finalCounterpartyAccountId,
                transferGroupId = finalTransferGroupId,
                needsReview = finalNeedsReview,
                updatedAt = now
            )) ?: TransactionEntity(
                id = id,
                type = type,
                amount = amount,
                date = date,
                time = time,
                merchant = merchant,
                categoryId = categoryId,
                subcategoryId = subcategoryId,
                accountId = accountId,
                paymentMethod = paymentMethod,
                note = note,
                source = "MANUAL",
                transactionReference = "",
                originalReference = "",
                last4Digits = "",
                createdAt = now,
                updatedAt = now,
                isExpense = finalIsExpense,
                isInternalTransfer = finalIsInternalTransfer,
                transactionType = finalTransactionType,
                direction = finalDirection,
                counterpartyAccountId = finalCounterpartyAccountId,
                transferGroupId = finalTransferGroupId,
                needsReview = finalNeedsReview
            )
            repository.insertTransaction(updated)
            MerchantLearningEngine.saveMapping(getApplication(), updated.merchant, updated.categoryId, updated.subcategoryId)
        }
    }

    fun updateTransaction(tx: TransactionEntity) {
        viewModelScope.launch {
            repository.insertTransaction(tx.copy(updatedAt = getNowIsoString()))
            MerchantLearningEngine.saveMapping(getApplication(), tx.merchant, tx.categoryId, tx.subcategoryId)
        }
    }

    fun saveTransactionSplits(transactionId: String, splits: List<TransactionSplitEntity>) {
        viewModelScope.launch {
            repository.insertSplitsForTransaction(transactionId, splits)
        }
    }

    fun removeTransactionSplits(transactionId: String) {
        viewModelScope.launch {
            repository.deleteSplitsForTransaction(transactionId)
        }
    }

    suspend fun getSplitsForTransaction(transactionId: String): List<TransactionSplitEntity> {
        return transactionSplits.value.filter { it.transactionId == transactionId }
    }

    fun deleteTransaction(id: String) {
        viewModelScope.launch {
            repository.deleteTransaction(id)
        }
    }

    fun getCategorySpending(month: String): List<Triple<CategoryEntity, Double, Double>> {
        val allTxs = transactions.value
        val allSplits = transactionSplits.value
        val catList = categories.value
        
        val filtered = allTxs.filter { it.date.startsWith(month) && it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }
        val totalExpense = filtered.sumOf { it.amount }
        
        val totals = mutableMapOf<String, Double>()
        filtered.forEach { tx ->
            val txSplits = allSplits.filter { it.transactionId == tx.id }
            if (txSplits.isNotEmpty()) {
                txSplits.forEach { s ->
                    totals[s.categoryId] = (totals[s.categoryId] ?: 0.0) + s.amount
                }
            } else {
                totals[tx.categoryId] = (totals[tx.categoryId] ?: 0.0) + tx.amount
            }
        }
        
        return totals.mapNotNull { (catId, amt) ->
            val cat = catList.find { it.id == catId }
            if (cat != null) {
                val pct = if (totalExpense > 0) (amt / totalExpense) else 0.0
                Triple(cat, amt, pct)
            } else null
        }.sortedByDescending { it.second }
    }
    
    fun getSubcategoryBreakdown(categoryId: String, month: String): List<Triple<SubcategoryEntity, Double, Double>> {
        val allTxs = transactions.value
        val allSplits = transactionSplits.value
        val subcatList = subcategories.value
        
        val filtered = allTxs.filter { it.date.startsWith(month) && it.type == "EXPENSE" && it.isExpense && !it.isInternalTransfer && it.transactionType != "INTERNAL_TRANSFER" }
        
        // Find all transactions/splits that fall under this category
        val relevantTxs = filtered.filter { tx -> tx.categoryId == categoryId || allSplits.any { it.transactionId == tx.id && it.categoryId == categoryId } }
        val totalCatSpend = relevantTxs.sumOf { tx ->
            val txSplits = allSplits.filter { it.transactionId == tx.id && it.categoryId == categoryId }
            if (txSplits.isNotEmpty()) txSplits.sumOf { it.amount }
            else if (tx.categoryId == categoryId) tx.amount
            else 0.0
        }
        
        val totals = mutableMapOf<String, Double>()
        relevantTxs.forEach { tx ->
            val txSplits = allSplits.filter { it.transactionId == tx.id && it.categoryId == categoryId }
            if (txSplits.isNotEmpty()) {
                txSplits.forEach { s ->
                    totals[s.subcategoryId] = (totals[s.subcategoryId] ?: 0.0) + s.amount
                }
            } else if (tx.subcategoryId.isNotEmpty()) {
                totals[tx.subcategoryId] = (totals[tx.subcategoryId] ?: 0.0) + tx.amount
            }
        }
        
        return totals.mapNotNull { (subId, amt) ->
            val sub = subcatList.find { it.id == subId }
            if (sub != null) {
                val pct = if (totalCatSpend > 0) (amt / totalCatSpend) else 0.0
                Triple(sub, amt, pct)
            } else null
        }.sortedByDescending { it.second }
    }

    // Category CRUD
    fun addCategory(name: String, nameHindi: String = "", icon: String, colour: String, isIncome: Boolean): String {
        val id = "cat-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        val now = getNowIsoString()
        val cat = CategoryEntity(
            id = id,
            name = name,
            nameHindi = nameHindi,
            icon = icon,
            colour = colour,
            isDefault = false,
            isActive = true,
            isIncome = isIncome,
            createdAt = now,
            updatedAt = now
        )
        viewModelScope.launch {
            repository.insertCategory(cat)
        }
        return id
    }

    fun updateCategory(cat: CategoryEntity) {
        viewModelScope.launch {
            repository.insertCategory(cat.copy(updatedAt = getNowIsoString()))
        }
    }

    fun deleteCategory(id: String) {
        val now = getNowIsoString()
        viewModelScope.launch {
            repository.deleteCategory(id, now)
        }
    }

    fun getCategoryTransactionCount(categoryId: String, callback: (Int) -> Unit) {
        viewModelScope.launch {
            val count = repository.getTransactionCountForCategory(categoryId)
            callback(count)
        }
    }

    fun mergeCategory(sourceCategoryId: String, targetCategoryId: String, onComplete: () -> Unit = {}) {
        val now = getNowIsoString()
        viewModelScope.launch {
            repository.mergeCategory(sourceCategoryId, targetCategoryId, now)
            onComplete()
        }
    }

    fun safeDeleteCategory(categoryId: String, onHasTransactions: (Int) -> Unit, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val count = repository.getTransactionCountForCategory(categoryId)
            if (count > 0) {
                onHasTransactions(count)
            } else {
                val now = getNowIsoString()
                repository.deleteCategory(categoryId, now)
                onSuccess()
            }
        }
    }

    // Subcategory CRUD
    fun addSubcategory(categoryId: String, name: String, nameHindi: String = "", icon: String, colour: String): String {
        val trimmedName = name.trim()
        val existing = subcategories.value.find {
            it.categoryId == categoryId && it.name.equals(trimmedName, ignoreCase = true)
        }
        if (existing != null) {
            return existing.id
        }

        val id = "sub-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        val now = getNowIsoString()
        val subcat = SubcategoryEntity(
            id = id,
            categoryId = categoryId,
            name = trimmedName,
            nameHindi = nameHindi,
            icon = icon,
            colour = colour,
            isDefault = false,
            isActive = true,
            createdAt = now,
            updatedAt = now
        )
        viewModelScope.launch {
            repository.insertSubcategory(subcat)
        }
        return id
    }

    fun updateSubcategory(subcat: SubcategoryEntity) {
        val trimmedName = subcat.name.trim()
        val duplicate = subcategories.value.find {
            it.id != subcat.id && it.categoryId == subcat.categoryId && it.name.equals(trimmedName, ignoreCase = true)
        }
        if (duplicate != null) {
            // Prevent rename to duplicate within same category
            return
        }
        viewModelScope.launch {
            repository.insertSubcategory(subcat.copy(name = trimmedName, updatedAt = getNowIsoString()))
        }
    }

    fun moveSubcategory(subcategoryId: String, newCategoryId: String, moveTransactions: Boolean = true) {
        val now = getNowIsoString()
        viewModelScope.launch {
            repository.moveSubcategory(subcategoryId, newCategoryId, now, moveTransactions)
        }
    }

    fun deleteSubcategory(id: String) {
        val now = getNowIsoString()
        viewModelScope.launch {
            repository.deleteSubcategory(id, now)
        }
    }

    fun reorderSubcategories(reorderedSubcategories: List<SubcategoryEntity>) {
        viewModelScope.launch {
            val now = getNowIsoString()
            reorderedSubcategories.forEachIndexed { index, sub ->
                repository.insertSubcategory(sub.copy(updatedAt = now))
            }
        }
    }

    // Budget Configuration
    private val _monthlyBudgetsVersion = MutableStateFlow(0)
    val monthlyBudgetsVersion: StateFlow<Int> = _monthlyBudgetsVersion.asStateFlow()

    fun getMonthlyBudget(month: String = selectedMonth.value): Double {
        val key = "budget_month_$month"
        if (prefs.contains(key)) {
            val valFloat = prefs.getFloat(key, 45000.0f)
            if (valFloat > 0f) return valFloat.toDouble()
        }
        val legacy = prefs.getFloat("budget_overall_monthly", 0f)
        return if (legacy > 0f) legacy.toDouble() else 45000.0
    }

    fun setMonthlyBudget(month: String, amount: Double) {
        if (amount <= 0.0) return
        prefs.edit().putFloat("budget_month_$month", amount.toFloat()).apply()
        _monthlyBudgetsVersion.value += 1
    }

    fun setCategoryBudget(categoryId: String, amount: Double) {
        prefs.edit().putFloat("budget_cat_$categoryId", amount.toFloat()).apply()
    }

    fun getCategoryBudget(categoryId: String): Double {
        return prefs.getFloat("budget_cat_$categoryId", 0.0f).toDouble()
    }

    fun setOverallMonthlyBudget(amount: Double) {
        setMonthlyBudget(selectedMonth.value, amount)
    }

    fun getOverallMonthlyBudget(): Double {
        return getMonthlyBudget(selectedMonth.value)
    }

    // Accounts CRUD
    fun addAccount(
        name: String,
        type: String,
        bankName: String,
        last4Digits: String,
        icon: String,
        colour: String,
        isDefault: Boolean = false,
        isOwnedByMe: Boolean = true,
        creditLimit: Double = 0.0,
        outstandingAmount: Double = 0.0,
        billingDate: Int = 0,
        dueDate: Int = 0,
        initialBalance: Double = 0.0
    ) {
        val id = if (type.equals("Credit Card", ignoreCase = true)) {
            "acc-manual-cc-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        } else {
            "acc-manual-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        }
        val now = getNowIsoString()
        val account = AccountEntity(
            id = id,
            name = name,
            type = type,
            bankName = bankName,
            last4Digits = last4Digits,
            creditLimit = creditLimit,
            outstandingAmount = outstandingAmount,
            billingDate = billingDate,
            dueDate = dueDate,
            initialBalance = initialBalance,
            icon = icon,
            colour = colour,
            isActive = true,
            isDefault = isDefault,
            isOwnedByMe = isOwnedByMe,
            createdAt = now,
            updatedAt = now
        )
        viewModelScope.launch {
            repository.insertAccount(account)
        }
    }

    fun updateAccount(
        id: String,
        name: String,
        type: String,
        bankName: String,
        last4Digits: String,
        icon: String,
        colour: String,
        isActive: Boolean = true,
        isDefault: Boolean = false,
        isOwnedByMe: Boolean = true,
        creditLimit: Double = 0.0,
        outstandingAmount: Double = 0.0,
        billingDate: Int = 0,
        dueDate: Int = 0,
        initialBalance: Double = 0.0
    ) {
        val now = getNowIsoString()
        val account = AccountEntity(
            id = id,
            name = name,
            type = type,
            bankName = bankName,
            last4Digits = last4Digits,
            creditLimit = creditLimit,
            outstandingAmount = outstandingAmount,
            billingDate = billingDate,
            dueDate = dueDate,
            initialBalance = initialBalance,
            icon = icon,
            colour = colour,
            isActive = isActive,
            isDefault = isDefault,
            isOwnedByMe = isOwnedByMe,
            createdAt = now,
            updatedAt = now
        )
        viewModelScope.launch {
            repository.insertAccount(account)
        }
    }

    fun archiveAccount(id: String) {
        val now = getNowIsoString()
        viewModelScope.launch {
            val existing = accounts.value.find { it.id == id }
            if (existing != null) {
                repository.insertAccount(existing.copy(isActive = false, updatedAt = now))
            }
        }
    }

    fun deleteAccount(id: String) {
        viewModelScope.launch {
            repository.deleteAccount(id)
            val context = getApplication<Application>()
            com.example.utils.KharchaBackupManager.markBackupPending(context)
        }
    }

    fun setDefaultAccount(id: String) {
        val now = getNowIsoString()
        viewModelScope.launch {
            accounts.value.forEach { acc ->
                val shouldBeDefault = acc.id == id
                if (acc.isDefault != shouldBeDefault) {
                    repository.insertAccount(acc.copy(isDefault = shouldBeDefault, updatedAt = now))
                }
            }
        }
    }

    // Card Mapping CRUD Operations
    fun addCard(
        accountId: String,
        name: String,
        type: String,
        last4Digits: String,
        creditLimit: Double = 0.0,
        outstandingAmount: Double = 0.0,
        billingDate: Int = 0,
        dueDate: Int = 0
    ) {
        val id = "card-manual-" + System.currentTimeMillis()
        val now = getNowIsoString()
        viewModelScope.launch {
            val card = CardEntity(
                id = id,
                accountId = accountId,
                name = name,
                type = type,
                last4Digits = last4Digits,
                creditLimit = creditLimit,
                outstandingAmount = outstandingAmount,
                billingDate = billingDate,
                dueDate = dueDate,
                createdAt = now,
                updatedAt = now
            )
            repository.insertCard(card)
        }
    }

    fun updateCard(
        id: String,
        accountId: String,
        name: String,
        type: String,
        last4Digits: String,
        creditLimit: Double = 0.0,
        outstandingAmount: Double = 0.0,
        billingDate: Int = 0,
        dueDate: Int = 0
    ) {
        val now = getNowIsoString()
        viewModelScope.launch {
            val card = CardEntity(
                id = id,
                accountId = accountId,
                name = name,
                type = type,
                last4Digits = last4Digits,
                creditLimit = creditLimit,
                outstandingAmount = outstandingAmount,
                billingDate = billingDate,
                dueDate = dueDate,
                createdAt = now,
                updatedAt = now
            )
            repository.insertCard(card)
        }
    }

    fun deleteCard(id: String) {
        viewModelScope.launch {
            repository.deleteCard(id)
        }
    }

    // Google Sign-In Method
    fun loginWithGoogle(
        activity: Activity,
        onResult: (Boolean, Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val result = AuthManager.signInWithGoogle(activity)
            result.fold(
                onSuccess = { user ->
                    AuthManager.setOnboardingCompleted(activity, true)
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        com.example.data.firestore.RoomToFirestoreSyncEngine().syncAllLocalDataToFirestore(activity)
                    }
                    onResult(true, false, null)
                },
                onFailure = { error ->
                    onResult(false, false, error.message ?: "Google Sign-In failed")
                }
            )
        }
    }

    fun completeOnboarding(context: Context) {
        AuthManager.setOnboardingCompleted(context, true)
    }

    fun logout(context: Context) {
        AuthManager.logout(context)
    }

    fun reorderCategories(reorderedCategories: List<CategoryEntity>) {
        viewModelScope.launch {
            val now = getNowIsoString()
            reorderedCategories.forEachIndexed { index, cat ->
                repository.insertCategory(cat.copy(updatedAt = now))
            }
        }
    }

    companion object {
        /**
         * Universal Year-Month extraction helper.
         * Normalizes all supported date formats to canonical "YYYY-MM" (e.g., "2026-09").
         */
        fun extractYearMonthKey(date: String): String {
            val clean = date.trim()
            if (clean.isEmpty()) return ""

            // 1. ISO or standard YYYY-MM-DD or YYYY-MM or YYYY/MM/DD
            val ymdMatch = Regex("""\b(20\d{2})[-/](1[0-2]|0?[1-9])""").find(clean)
            if (ymdMatch != null) {
                val y = ymdMatch.groupValues[1]
                val m = ymdMatch.groupValues[2].padStart(2, '0')
                return "$y-$m"
            }

            // 2. Numeric DD-MM-YYYY or DD/MM/YYYY or DD.MM.YYYY (4-digit year)
            val dmy4Match = Regex("""\b(0?[1-9]|[12][0-9]|3[01])[-/.](1[0-2]|0?[1-9])[-/.](20\d{2})\b""").find(clean)
            if (dmy4Match != null) {
                val y = dmy4Match.groupValues[3]
                val m = dmy4Match.groupValues[2].padStart(2, '0')
                return "$y-$m"
            }

            // 3. Numeric DD-MM-YY or DD/MM/YY (2-digit year)
            val dmy2Match = Regex("""\b(0?[1-9]|[12][0-9]|3[01])[-/.](1[0-2]|0?[1-9])[-/.](\d{2})\b""").find(clean)
            if (dmy2Match != null) {
                val y = "20${dmy2Match.groupValues[3]}"
                val m = dmy2Match.groupValues[2].padStart(2, '0')
                return "$y-$m"
            }

            // 4. 3-part named month with year:
            // e.g. "28-SEP-26", "02-Sep-2026", "28 Sep 2026", "2-September-2026"
            val dmyNamed3Match = Regex("""\b(0?[1-9]|[12][0-9]|3[01])[-/.\s]+([A-Za-z]{3,9})[-/.\s]+(20\d{2}|\d{2})\b""").find(clean)
            if (dmyNamed3Match != null) {
                val monthNum = parseMonthNameToNumber(dmyNamed3Match.groupValues[2])
                if (monthNum != null) {
                    var y = dmyNamed3Match.groupValues[3]
                    if (y.length == 2) y = "20$y"
                    return "$y-${monthNum.padStart(2, '0')}"
                }
            }

            // e.g. "Sep 02, 2026", "September 28, 26", "Sep 28 2026"
            val mdyNamed3Match = Regex("""\b([A-Za-z]{3,9})[-/.\s]+(0?[1-9]|[12][0-9]|3[01]),?[-/.\s]+(20\d{2}|\d{2})\b""").find(clean)
            if (mdyNamed3Match != null) {
                val monthNum = parseMonthNameToNumber(mdyNamed3Match.groupValues[1])
                if (monthNum != null) {
                    var y = mdyNamed3Match.groupValues[3]
                    if (y.length == 2) y = "20$y"
                    return "$y-${monthNum.padStart(2, '0')}"
                }
            }

            // e.g. "Sep 2026", "September 2026"
            val myNamedMatch = Regex("""\b([A-Za-z]{3,9})[-/.\s]+(20\d{2})\b""").find(clean)
            if (myNamedMatch != null) {
                val monthNum = parseMonthNameToNumber(myNamedMatch.groupValues[1])
                if (monthNum != null) {
                    val y = myNamedMatch.groupValues[2]
                    return "$y-${monthNum.padStart(2, '0')}"
                }
            }

            // e.g. "Sep '26", "September '26"
            val myNamed2Match = Regex("""\b([A-Za-z]{3,9})\s*'(\d{2})\b""").find(clean)
            if (myNamed2Match != null) {
                val monthNum = parseMonthNameToNumber(myNamed2Match.groupValues[1])
                if (monthNum != null) {
                    val y = "20${myNamed2Match.groupValues[2]}"
                    return "$y-${monthNum.padStart(2, '0')}"
                }
            }

            // 5. 2-part named month with day (WITHOUT year):
            // e.g. "Sep 02", "Sep 2", "02-Sep", "07-SEP", "September 07", "Sep 02, 10:30 AM"
            val namedAnyMatch = Regex("""\b([A-Za-z]{3,9})\b""").find(clean)
            if (namedAnyMatch != null) {
                val monthNum = parseMonthNameToNumber(namedAnyMatch.groupValues[1])
                if (monthNum != null) {
                    val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                    return "$currentYear-${monthNum.padStart(2, '0')}"
                }
            }

            // 6. Numeric DD-MM or MM-DD without year (e.g. "02-09", "07/09")
            val dmyNoYear = Regex("""\b(0?[1-9]|[12][0-9]|3[01])[-/.](1[0-2]|0?[1-9])\b""").find(clean)
            if (dmyNoYear != null) {
                val m = dmyNoYear.groupValues[2].padStart(2, '0')
                val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                return "$currentYear-$m"
            }

            // 7. Fallback: return standard YYYY-MM
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val currentMonth = (java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
            return "$currentYear-$currentMonth"
        }

        private fun parseMonthNameToNumber(name: String): String? {
            val lower = name.lowercase(java.util.Locale.ENGLISH)
            return when {
                lower.startsWith("jan") -> "01"
                lower.startsWith("feb") -> "02"
                lower.startsWith("mar") -> "03"
                lower.startsWith("apr") -> "04"
                lower.startsWith("may") -> "05"
                lower.startsWith("jun") -> "06"
                lower.startsWith("jul") -> "07"
                lower.startsWith("aug") -> "08"
                lower.startsWith("sep") -> "09"
                lower.startsWith("oct") -> "10"
                lower.startsWith("nov") -> "11"
                lower.startsWith("dec") -> "12"
                else -> null
            }
        }

        /**
         * Aggregates monthly spending for Category History.
         * 1. Filters only eligible expense transactions (type == "EXPENSE", isExpense == true, not internal transfer).
         * 2. Filters by targetCategoryId (checking both primary category and splits).
         * 3. Groups by normalized Year-Month key (e.g., "2026-09").
         * 4. Sums all amounts strictly per Year-Month so each calendar month produces exactly one aggregated bar.
         */
        fun aggregateMonthlySpendingForCategory(
            transactions: List<TransactionEntity>,
            targetCategoryId: String,
            allSplits: List<TransactionSplitEntity> = emptyList()
        ): Map<String, Double> {
            val eligibleExpenses = transactions.filter { tx ->
                tx.type == "EXPENSE" && tx.isExpense && !tx.isInternalTransfer && tx.transactionType != "INTERNAL_TRANSFER"
            }

            val categoryTxs = eligibleExpenses.filter { tx ->
                val txSplits = allSplits.filter { it.transactionId == tx.id && it.categoryId == targetCategoryId }
                txSplits.isNotEmpty() || tx.categoryId == targetCategoryId
            }

            return categoryTxs
                .groupBy { extractYearMonthKey(it.date) }
                .filterKeys { it.isNotBlank() }
                .mapValues { entry ->
                    entry.value.sumOf { tx ->
                        val txSplits = allSplits.filter { it.transactionId == tx.id && it.categoryId == targetCategoryId }
                        if (txSplits.isNotEmpty()) {
                            txSplits.sumOf { it.amount }
                        } else if (tx.categoryId == targetCategoryId) {
                            tx.amount
                        } else {
                            0.0
                        }
                    }
                }
                .toSortedMap()
        }

        /**
         * Aggregates monthly spending for Merchant History.
         * 1. Filters only eligible expense transactions.
         * 2. Filters by selected merchant name.
         * 3. Groups by normalized Year-Month key.
         * 4. Sums all amounts per Year-Month into a single aggregated bar.
         */
        fun aggregateMonthlySpendingForMerchant(
            transactions: List<TransactionEntity>,
            targetMerchantName: String,
            categories: List<CategoryEntity> = emptyList()
        ): Map<String, Double> {
            val eligibleExpenses = transactions.filter { tx ->
                tx.type == "EXPENSE" && tx.isExpense && !tx.isInternalTransfer && tx.transactionType != "INTERNAL_TRANSFER"
            }

            val merchantTxs = eligibleExpenses.filter { tx ->
                val cat = categories.find { it.id == tx.categoryId }
                val cleanName = com.example.ui.screens.getCleanMerchantName(tx.merchant, cat?.name)
                cleanName.equals(targetMerchantName, ignoreCase = true) || tx.merchant.equals(targetMerchantName, ignoreCase = true)
            }

            return merchantTxs
                .groupBy { extractYearMonthKey(it.date) }
                .filterKeys { it.isNotBlank() }
                .mapValues { entry -> entry.value.sumOf { it.amount } }
                .toSortedMap()
        }
    }
}
