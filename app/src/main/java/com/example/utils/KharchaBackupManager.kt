package com.example.utils

import android.content.Context
import android.util.Log
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class BackupMetadata(
    val backupVersion: Int = 1,
    val schemaVersion: Int = 8,
    val ownerEmail: String = "",
    val userId: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val transactionCount: Int = 0,
    val accountCount: Int = 0,
    val cardCount: Int = 0,
    val categoryCount: Int = 0,
    val fileSizeFormatted: String = "0 KB"
)

enum class BackupStatus {
    IDLE,
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    PENDING_OFFLINE
}

object KharchaBackupManager {

    private const val TAG = "KharchaBackupManager"
    private const val PREF_NAME = "kharcha_backup_prefs"
    private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
    private const val KEY_LAST_BACKUP_STATUS = "last_backup_status"
    private const val KEY_BACKUP_PENDING = "is_backup_pending"
    private const val KEY_LAST_RESTORE_TIME = "last_restore_time"

    private const val BACKUP_FILENAME = "my_kharcha_backup.json"
    private const val FOLDER_NAME = "My Kharcha Backup"

    private val _backupStatus = MutableStateFlow(BackupStatus.IDLE)
    val backupStatus: StateFlow<BackupStatus> = _backupStatus.asStateFlow()

    private val _lastBackupTime = MutableStateFlow<String>("")
    val lastBackupTime: StateFlow<String> = _lastBackupTime.asStateFlow()

    private val _isBackupPending = MutableStateFlow(false)
    val isBackupPending: StateFlow<Boolean> = _isBackupPending.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _lastBackupTime.value = prefs.getString(KEY_LAST_BACKUP_TIME, "Never") ?: "Never"
        _isBackupPending.value = prefs.getBoolean(KEY_BACKUP_PENDING, false)
        val savedStatusStr = prefs.getString(KEY_LAST_BACKUP_STATUS, "IDLE") ?: "IDLE"
        _backupStatus.value = try { BackupStatus.valueOf(savedStatusStr) } catch (e: Exception) { BackupStatus.IDLE }
    }

    fun markBackupPending(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BACKUP_PENDING, true).apply()
        _isBackupPending.value = true
    }

    private fun getNowIso(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private fun authenticatedUserId(): String? = try {
        FirebaseAuth.getInstance().currentUser?.uid?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to verify authenticated user for backup: ${e.message}", e)
        null
    }

    private fun backupRecordBelongsTo(userId: String, record: JSONObject): Boolean {
        if (!record.has("userId")) return true
        val recordUserId = record.opt("userId") as? String
        return recordUserId == userId
    }

    /**
     * Serializes all structured My Kharcha database entities into a versioned JSON string.
     * EXCLUDES raw SMS body text, raw notifications, email tokens, PINs, or credentials.
     */
    suspend fun createBackupJsonPayload(
        context: Context,
        ownerEmail: String,
        userId: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val authenticatedUserId = authenticatedUserId()
            ?: throw IllegalStateException("An authenticated user is required to create a backup.")
        require(userId == authenticatedUserId) {
            "Backup user ID does not match the authenticated user."
        }

        val db = AppDatabase.getDatabase(context)
        val dao = db.kharchaDao()

        val accounts = dao.getAllAccountsSyncForUser(authenticatedUserId)
            .filter { it.userId == authenticatedUserId }
        val accountIds = accounts.mapTo(mutableSetOf()) { it.id }
        val cards = dao.getAllCardsSyncForUser(authenticatedUserId)
            .filter { it.userId == authenticatedUserId && it.accountId in accountIds }
        val cardIds = cards.mapTo(mutableSetOf()) { it.id }
        val categories = dao.getAllCategoriesSyncForUser(authenticatedUserId)
            .filter { it.userId == authenticatedUserId }
        val categoryIds = categories.mapTo(mutableSetOf()) { it.id }
        val subcategories = dao.getAllSubcategoriesSyncForUser(authenticatedUserId)
            .filter { it.userId == authenticatedUserId && it.categoryId in categoryIds }
        val subcategoriesById = subcategories.associateBy { it.id }
        val transactions = dao.getAllTransactionsSyncForUser(authenticatedUserId)
            .filter { transaction ->
                transaction.userId == authenticatedUserId &&
                    (transaction.accountId.isBlank() || transaction.accountId in accountIds) &&
                    (transaction.categoryId.isBlank() || transaction.categoryId in categoryIds) &&
                    (transaction.subcategoryId.isBlank() ||
                        subcategoriesById[transaction.subcategoryId]?.categoryId == transaction.categoryId) &&
                    (transaction.cardId.isNullOrBlank() || transaction.cardId in cardIds) &&
                    (transaction.counterpartyAccountId.isNullOrBlank() ||
                        transaction.counterpartyAccountId in accountIds ||
                        transaction.counterpartyAccountId in cardIds)
            }
        val transactionIds = transactions.mapTo(mutableSetOf()) { it.id }
        val splits = dao.getAllSplitsSyncForUser(authenticatedUserId)
            .filter { it.userId == authenticatedUserId && it.transactionId in transactionIds }

        val nowIso = getNowIso()

        val root = JSONObject()
        root.put("backupVersion", 1)
        root.put("schemaVersion", 8)
        root.put("ownerEmail", ownerEmail)
        root.put("userId", userId)
        root.put("createdAt", nowIso)
        root.put("updatedAt", nowIso)

        // Transactions Array
        val txArray = JSONArray()
        for (tx in transactions) {
            val txObj = JSONObject()
            txObj.put("id", tx.id)
            txObj.put("type", tx.type)
            txObj.put("amount", tx.amount)
            txObj.put("date", tx.date)
            txObj.put("time", tx.time)
            txObj.put("merchant", tx.merchant)
            txObj.put("categoryId", tx.categoryId)
            txObj.put("subcategoryId", tx.subcategoryId)
            txObj.put("accountId", tx.accountId)
            txObj.put("paymentMethod", tx.paymentMethod)
            txObj.put("note", tx.note)
            txObj.put("source", tx.source)
            txObj.put("transactionReference", tx.transactionReference)
            txObj.put("originalReference", tx.originalReference)
            txObj.put("last4Digits", tx.last4Digits)
            txObj.put("createdAt", tx.createdAt.ifEmpty { nowIso })
            txObj.put("updatedAt", tx.updatedAt.ifEmpty { nowIso })
            txObj.put("cardId", tx.cardId ?: JSONObject.NULL)
            txObj.put("transactionType", tx.transactionType ?: JSONObject.NULL)
            txObj.put("direction", tx.direction ?: JSONObject.NULL)
            txObj.put("transferGroupId", tx.transferGroupId ?: JSONObject.NULL)
            txObj.put("counterpartyAccountId", tx.counterpartyAccountId ?: JSONObject.NULL)
            txObj.put("isInternalTransfer", tx.isInternalTransfer)
            txObj.put("needsReview", tx.needsReview)
            txObj.put("isExpense", tx.isExpense)
            txObj.put("cardPaymentBalanceApplied", tx.cardPaymentBalanceApplied)
            txArray.put(txObj)
        }
        root.put("transactions", txArray)

        // Accounts Array
        val accArray = JSONArray()
        for (acc in accounts) {
            val accObj = JSONObject()
            accObj.put("id", acc.id)
            accObj.put("name", acc.name)
            accObj.put("type", acc.type)
            accObj.put("bankName", acc.bankName)
            accObj.put("last4Digits", acc.last4Digits)
            accObj.put("icon", acc.icon)
            accObj.put("colour", acc.colour)
            accObj.put("isActive", acc.isActive)
            accObj.put("isDefault", acc.isDefault)
            accObj.put("isOwnedByMe", acc.isOwnedByMe)
            accObj.put("initialBalance", acc.initialBalance)
            accObj.put("creditLimit", acc.creditLimit)
            accObj.put("outstandingAmount", acc.outstandingAmount)
            accObj.put("billingDate", acc.billingDate)
            accObj.put("dueDate", acc.dueDate)
            accObj.put("minimumAmountDue", acc.minimumAmountDue)
            accObj.put("paymentDueDate", acc.paymentDueDate)
            accObj.put("statementDate", acc.statementDate)
            accObj.put("createdAt", acc.createdAt.ifEmpty { nowIso })
            accObj.put("updatedAt", acc.updatedAt.ifEmpty { nowIso })
            accArray.put(accObj)
        }
        root.put("accounts", accArray)

        // Cards Array
        val cardArray = JSONArray()
        for (card in cards) {
            val cardObj = JSONObject()
            cardObj.put("id", card.id)
            cardObj.put("accountId", card.accountId)
            cardObj.put("name", card.name)
            cardObj.put("type", card.type)
            cardObj.put("last4Digits", card.last4Digits)
            cardObj.put("creditLimit", card.creditLimit)
            cardObj.put("outstandingAmount", card.outstandingAmount)
            cardObj.put("billingDate", card.billingDate)
            cardObj.put("dueDate", card.dueDate)
            cardObj.put("minimumAmountDue", card.minimumAmountDue)
            cardObj.put("paymentDueDate", card.paymentDueDate)
            cardObj.put("statementDate", card.statementDate)
            cardObj.put("createdAt", card.createdAt.ifEmpty { nowIso })
            cardObj.put("updatedAt", card.updatedAt.ifEmpty { nowIso })
            cardArray.put(cardObj)
        }
        root.put("cards", cardArray)

        // Categories Array
        val catArray = JSONArray()
        for (cat in categories) {
            val catObj = JSONObject()
            catObj.put("id", cat.id)
            catObj.put("name", cat.name)
            catObj.put("nameHindi", cat.nameHindi)
            catObj.put("icon", cat.icon)
            catObj.put("colour", cat.colour)
            catObj.put("isDefault", cat.isDefault)
            catObj.put("isActive", cat.isActive)
            catObj.put("isIncome", cat.isIncome)
            catObj.put("createdAt", cat.createdAt.ifEmpty { nowIso })
            catObj.put("updatedAt", cat.updatedAt.ifEmpty { nowIso })
            catArray.put(catObj)
        }
        root.put("categories", catArray)

        // Subcategories Array
        val subArray = JSONArray()
        for (sub in subcategories) {
            val subObj = JSONObject()
            subObj.put("id", sub.id)
            subObj.put("categoryId", sub.categoryId)
            subObj.put("name", sub.name)
            subObj.put("nameHindi", sub.nameHindi)
            subObj.put("isDefault", sub.isDefault)
            subObj.put("isActive", sub.isActive)
            subObj.put("createdAt", sub.createdAt.ifEmpty { nowIso })
            subObj.put("updatedAt", sub.updatedAt.ifEmpty { nowIso })
            subArray.put(subObj)
        }
        root.put("subcategories", subArray)

        // Splits Array
        val splitArray = JSONArray()
        for (split in splits) {
            val splitObj = JSONObject()
            splitObj.put("id", split.id)
            splitObj.put("transactionId", split.transactionId)
            splitObj.put("categoryId", split.categoryId)
            splitObj.put("subcategoryId", split.subcategoryId)
            splitObj.put("amount", split.amount)
            splitObj.put("note", split.note)
            splitObj.put("createdAt", split.createdAt.ifEmpty { nowIso })
            splitObj.put("updatedAt", split.updatedAt.ifEmpty { nowIso })
            splitArray.put(splitObj)
        }
        root.put("splits", splitArray)

        root
    }

    /**
     * Uploads or updates the backup payload on the user's Google Drive via standard OAuth REST API.
     */
    suspend fun performGoogleDriveBackup(
        context: Context,
        accessToken: String,
        ownerEmail: String,
        userId: String
    ): Result<BackupMetadata> = withContext(Dispatchers.IO) {
        val authenticatedUserId = authenticatedUserId()
            ?: return@withContext Result.failure(Exception("An authenticated user is required to create a backup."))
        if (userId != authenticatedUserId) {
            return@withContext Result.failure(Exception("Backup user ID does not match the authenticated user."))
        }
        _backupStatus.value = BackupStatus.IN_PROGRESS
        try {
            val jsonPayload = createBackupJsonPayload(context, ownerEmail, userId)
            val jsonBytes = jsonPayload.toString(2).toByteArray(Charsets.UTF_8)
            val sizeKb = (jsonBytes.size / 1024).coerceAtLeast(1)

            // 1. Search for existing backup file on user's Google Drive
            val existingFileId = findBackupFileIdOnDrive(accessToken)

            val fileId = if (existingFileId != null) {
                // Update existing backup file via HTTP PATCH / MULTIPART upload
                updateDriveFile(accessToken, existingFileId, jsonBytes)
                existingFileId
            } else {
                // Create new backup file on user's Google Drive
                createDriveFile(accessToken, BACKUP_FILENAME, jsonBytes)
            }

            val nowStr = SimpleDateFormat("dd MMM, h:mm a", Locale.US).format(Date())
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LAST_BACKUP_TIME, nowStr)
                .putString(KEY_LAST_BACKUP_STATUS, "SUCCESS")
                .putBoolean(KEY_BACKUP_PENDING, false)
                .apply()

            _lastBackupTime.value = nowStr
            _isBackupPending.value = false
            _backupStatus.value = BackupStatus.SUCCESS

            val metadata = BackupMetadata(
                backupVersion = 1,
                schemaVersion = 8,
                ownerEmail = ownerEmail,
                userId = userId,
                createdAt = getNowIso(),
                updatedAt = getNowIso(),
                transactionCount = jsonPayload.getJSONArray("transactions").length(),
                accountCount = jsonPayload.getJSONArray("accounts").length(),
                cardCount = jsonPayload.getJSONArray("cards").length(),
                categoryCount = jsonPayload.getJSONArray("categories").length(),
                fileSizeFormatted = "$sizeKb KB"
            )

            Log.d(TAG, "Google Drive backup successful: File ID $fileId, size $sizeKb KB")
            Result.success(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Google Drive backup failed: ${e.message}", e)
            _backupStatus.value = BackupStatus.PENDING_OFFLINE
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LAST_BACKUP_STATUS, "PENDING_OFFLINE")
                .putBoolean(KEY_BACKUP_PENDING, true)
                .apply()

            Result.failure(Exception("Backup failed (${e.localizedMessage}). Local data remains safe and backup is marked pending."))
        }
    }

    /**
     * Downloads and restores the user's My Kharcha backup from Google Drive into the local database with conflict-free deduplication.
     */
    suspend fun performGoogleDriveRestore(
        context: Context,
        accessToken: String,
        currentOwnerEmail: String,
        overwriteLocal: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val authenticatedUser = try {
                FirebaseAuth.getInstance().currentUser
            } catch (e: Exception) {
                Log.w(TAG, "Unable to verify authenticated user for restore: ${e.message}", e)
                null
            } ?: return@withContext Result.failure(Exception("An authenticated user is required to restore a backup."))
            val uid = authenticatedUser.uid.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(Exception("An authenticated user is required to restore a backup."))
            val authenticatedEmail = authenticatedUser.email.orEmpty()
            if (currentOwnerEmail.isBlank() ||
                (authenticatedEmail.isNotBlank() && !currentOwnerEmail.equals(authenticatedEmail, ignoreCase = true))
            ) {
                return@withContext Result.failure(Exception("Restore account does not match the authenticated user."))
            }

            val fileId = findBackupFileIdOnDrive(accessToken)
                ?: return@withContext Result.failure(Exception("No 'My Kharcha' backup file found on Google Drive for this account."))

            val jsonContent = downloadDriveFile(accessToken, fileId)
            val root = try {
                JSONObject(jsonContent)
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("Backup file is corrupted or invalid. Existing local data was preserved."))
            }

            val backupUserId = root.optString("userId", "")
            if (backupUserId != uid) {
                return@withContext Result.failure(Exception("Security Error: The backup does not belong to the authenticated user."))
            }

            val backupOwner = root.optString("ownerEmail", "")
            if (backupOwner.isNotEmpty() && !backupOwner.equals(currentOwnerEmail, ignoreCase = true)) {
                return@withContext Result.failure(Exception("Security Error: The backup belongs to '$backupOwner' and cannot be restored under '$currentOwnerEmail'."))
            }

            val db = AppDatabase.getDatabase(context)
            val dao = db.kharchaDao()

            var restoredTxCount = 0
            var restoredAccCount = 0
            var restoredCardCount = 0
            var restoredCatCount = 0

            // 1. Categories & Subcategories
            val categoriesArr = root.optJSONArray("categories") ?: JSONArray()
            val restoredCategoryIds = dao.getAllCategoriesSyncForUser(uid)
                .mapTo(mutableSetOf()) { it.id }
            for (i in 0 until categoriesArr.length()) {
                val catObj = categoriesArr.getJSONObject(i)
                if (!backupRecordBelongsTo(uid, catObj)) {
                    Log.w(TAG, "Skipping category with mismatched backup owner")
                    continue
                }
                val cat = CategoryEntity(
                    id = catObj.getString("id"),
                    name = catObj.getString("name"),
                    nameHindi = catObj.optString("nameHindi", ""),
                    icon = catObj.optString("icon", "grid"),
                    colour = catObj.optString("colour", "#0284C7"),
                    isDefault = catObj.optBoolean("isDefault", false),
                    isActive = catObj.optBoolean("isActive", true),
                    isIncome = catObj.optBoolean("isIncome", false),
                    createdAt = catObj.optString("createdAt", getNowIso()),
                    updatedAt = catObj.optString("updatedAt", getNowIso()),
                    userId = uid
                )
                dao.insertCategory(cat)
                restoredCategoryIds.add(cat.id)
                restoredCatCount++
            }

            val subcategoriesArr = root.optJSONArray("subcategories") ?: JSONArray()
            for (i in 0 until subcategoriesArr.length()) {
                val subObj = subcategoriesArr.getJSONObject(i)
                if (!backupRecordBelongsTo(uid, subObj)) {
                    Log.w(TAG, "Skipping subcategory with mismatched backup owner")
                    continue
                }
                val categoryId = subObj.optString("categoryId", "")
                if (categoryId !in restoredCategoryIds) {
                    Log.w(TAG, "Skipping subcategory with missing owner-scoped category")
                    continue
                }
                val sub = SubcategoryEntity(
                    id = subObj.getString("id"),
                    categoryId = categoryId,
                    name = subObj.getString("name"),
                    nameHindi = subObj.optString("nameHindi", ""),
                    icon = subObj.optString("icon", "grid"),
                    colour = subObj.optString("colour", "#64748B"),
                    isDefault = subObj.optBoolean("isDefault", false),
                    isActive = subObj.optBoolean("isActive", true),
                    createdAt = subObj.optString("createdAt", getNowIso()),
                    updatedAt = subObj.optString("updatedAt", getNowIso()),
                    userId = uid
                )
                dao.insertSubcategory(sub)
            }

            // 2. Accounts & Cards
            val accountsArr = root.optJSONArray("accounts") ?: JSONArray()
            val existingAccounts = dao.getAllAccountsSyncForUser(uid).toMutableList()
            val accountIdMap = mutableMapOf<String, String>()
            for (i in 0 until accountsArr.length()) {
                val accObj = accountsArr.getJSONObject(i)
                if (!backupRecordBelongsTo(uid, accObj)) {
                    Log.w(TAG, "Skipping account with mismatched backup owner")
                    continue
                }
                val accId = accObj.getString("id")
                val acc = AccountEntity(
                    id = accId,
                    name = accObj.getString("name"),
                    type = accObj.optString("type", "Bank Account"),
                    bankName = accObj.optString("bankName", ""),
                    last4Digits = accObj.optString("last4Digits", ""),
                    icon = accObj.optString("icon", "landmark"),
                    colour = accObj.optString("colour", "#0284C7"),
                    isActive = accObj.optBoolean("isActive", true),
                    isDefault = accObj.optBoolean("isDefault", false),
                    isOwnedByMe = accObj.optBoolean("isOwnedByMe", true),
                    initialBalance = accObj.optDouble("initialBalance", 0.0),
                    creditLimit = accObj.optDouble("creditLimit", 0.0),
                    outstandingAmount = accObj.optDouble("outstandingAmount", 0.0),
                    billingDate = accObj.optInt("billingDate", 0),
                    dueDate = accObj.optInt("dueDate", 0),
                    minimumAmountDue = accObj.optDouble("minimumAmountDue", 0.0),
                    paymentDueDate = accObj.optString("paymentDueDate", ""),
                    statementDate = accObj.optString("statementDate", ""),
                    createdAt = accObj.optString("createdAt", getNowIso()),
                    updatedAt = accObj.optString("updatedAt", getNowIso()),
                    userId = uid
                )
                
                val existingById = existingAccounts.find { it.id == accId }
                val duplicateAccount = existingById ?: existingAccounts.find {
                    it.last4Digits == acc.last4Digits &&
                        it.last4Digits.length == 4 &&
                        it.bankName == acc.bankName &&
                        acc.bankName.isNotEmpty()
                }
                if (duplicateAccount == null || overwriteLocal) {
                    dao.insertAccount(acc)
                    existingAccounts.removeAll { it.id == acc.id }
                    existingAccounts.add(acc)
                    accountIdMap[accId] = acc.id
                    restoredAccCount++
                } else {
                    accountIdMap[accId] = duplicateAccount.id
                }
            }

            val cardsArr = root.optJSONArray("cards") ?: JSONArray()
            val accountIds = dao.getAllAccountsSyncForUser(uid).mapTo(mutableSetOf()) { it.id }
            val existingCards = dao.getAllCardsSyncForUser(uid).toMutableList()
            val cardIdMap = mutableMapOf<String, String>()
            for (i in 0 until cardsArr.length()) {
                val cardObj = cardsArr.getJSONObject(i)
                if (!backupRecordBelongsTo(uid, cardObj)) {
                    Log.w(TAG, "Skipping card with mismatched backup owner")
                    continue
                }
                val backupAccountId = cardObj.optString("accountId", "")
                val accountId = accountIdMap[backupAccountId] ?: backupAccountId
                if (accountId !in accountIds || accountId == "legacy:unassigned") {
                    Log.w(TAG, "Skipping card with missing owner-scoped account")
                    continue
                }
                val cardId = cardObj.getString("id")
                val card = CardEntity(
                    id = cardId,
                    accountId = accountId,
                    name = cardObj.getString("name"),
                    type = cardObj.optString("type", "Credit Card"),
                    last4Digits = cardObj.optString("last4Digits", ""),
                    creditLimit = cardObj.optDouble("creditLimit", 0.0),
                    outstandingAmount = cardObj.optDouble("outstandingAmount", 0.0),
                    billingDate = cardObj.optInt("billingDate", 0),
                    dueDate = cardObj.optInt("dueDate", 0),
                    minimumAmountDue = cardObj.optDouble("minimumAmountDue", 0.0),
                    paymentDueDate = cardObj.optString("paymentDueDate", ""),
                    statementDate = cardObj.optString("statementDate", ""),
                    createdAt = cardObj.optString("createdAt", getNowIso()),
                    updatedAt = cardObj.optString("updatedAt", getNowIso()),
                    userId = uid
                )

                val existingById = existingCards.find { it.id == cardId }
                val duplicateCard = existingById ?: existingCards.find {
                    it.last4Digits == card.last4Digits && it.last4Digits.length == 4
                }
                if (duplicateCard == null || overwriteLocal) {
                    dao.insertCard(card)
                    existingCards.removeAll { it.id == card.id }
                    existingCards.add(card)
                    cardIdMap[cardId] = card.id
                    restoredCardCount++
                } else {
                    cardIdMap[cardId] = duplicateCard.id
                }
            }

            // 3. Transactions with Deduplication
            val txArr = root.optJSONArray("transactions") ?: JSONArray()
            val existingTxs = dao.getAllTransactionsSyncForUser(uid).toMutableList()
            val ownedAccountIds = dao.getAllAccountsSyncForUser(uid).mapTo(mutableSetOf()) { it.id }
            val ownedCardIds = dao.getAllCardsSyncForUser(uid).mapTo(mutableSetOf()) { it.id }
            val ownedCategoryIds = dao.getAllCategoriesSyncForUser(uid).mapTo(mutableSetOf()) { it.id }
            val ownedSubcategories = dao.getAllSubcategoriesSyncForUser(uid).associateBy { it.id }
            val transactionIdMap = mutableMapOf<String, String>()
            for (i in 0 until txArr.length()) {
                val txObj = txArr.getJSONObject(i)
                if (!backupRecordBelongsTo(uid, txObj)) {
                    Log.w(TAG, "Skipping transaction with mismatched backup owner")
                    continue
                }
                val txId = txObj.getString("id")
                val backupAccountId = txObj.optString("accountId", "")
                val accountId = accountIdMap[backupAccountId] ?: backupAccountId
                val categoryId = txObj.optString("categoryId", "cat-other")
                val subcategoryId = txObj.optString("subcategoryId", "")
                val backupCardId = if (txObj.isNull("cardId")) null else txObj.optString("cardId")
                val cardId = backupCardId?.let { cardIdMap[it] ?: it }
                val backupCounterpartyId = if (txObj.isNull("counterpartyAccountId")) null else txObj.optString("counterpartyAccountId")
                val counterpartyId = backupCounterpartyId?.let { accountIdMap[it] ?: cardIdMap[it] ?: it }
                if ((accountId.isNotBlank() && (accountId == "legacy:unassigned" || accountId !in ownedAccountIds)) ||
                    (categoryId.isNotBlank() && categoryId !in ownedCategoryIds) ||
                    (subcategoryId.isNotBlank() &&
                        ownedSubcategories[subcategoryId]?.categoryId != categoryId) ||
                    (!cardId.isNullOrBlank() && cardId !in ownedCardIds) ||
                    (!counterpartyId.isNullOrBlank() &&
                        counterpartyId !in ownedAccountIds && counterpartyId !in ownedCardIds)
                ) {
                    Log.w(TAG, "Skipping transaction $txId with missing owner-scoped relationship")
                    continue
                }
                val tx = TransactionEntity(
                    id = txId,
                    type = txObj.getString("type"),
                    amount = txObj.getDouble("amount"),
                    date = txObj.getString("date"),
                    time = txObj.optString("time", "12:00"),
                    merchant = txObj.optString("merchant", "Unknown Merchant"),
                    categoryId = categoryId,
                    subcategoryId = subcategoryId,
                    accountId = accountId,
                    paymentMethod = txObj.optString("paymentMethod", "Other"),
                    note = txObj.optString("note", ""),
                    source = txObj.optString("source", "RESTORE"),
                    transactionReference = txObj.optString("transactionReference", ""),
                    originalReference = txObj.optString("originalReference", ""),
                    last4Digits = txObj.optString("last4Digits", ""),
                    createdAt = txObj.optString("createdAt", getNowIso()),
                    updatedAt = txObj.optString("updatedAt", getNowIso()),
                    cardId = cardId,
                    transactionType = if (txObj.isNull("transactionType")) null else txObj.optString("transactionType"),
                    direction = if (txObj.isNull("direction")) null else txObj.optString("direction"),
                    transferGroupId = if (txObj.isNull("transferGroupId")) null else txObj.optString("transferGroupId"),
                    counterpartyAccountId = counterpartyId,
                    isInternalTransfer = txObj.optBoolean("isInternalTransfer", false),
                    needsReview = txObj.optBoolean("needsReview", false),
                    isExpense = txObj.optBoolean("isExpense", true),
                    cardPaymentBalanceApplied = txObj.optBoolean("cardPaymentBalanceApplied", false),
                    userId = uid
                )

                val isDuplicate = existingTxs.any { existing ->
                    existing.id == txId || TransactionIngestionEngine.isDuplicateTransaction(tx, existing)
                }

                if (!isDuplicate || overwriteLocal) {
                    dao.insertTransaction(tx)
                    existingTxs.removeAll { it.id == tx.id }
                    existingTxs.add(tx)
                    transactionIdMap[txId] = tx.id
                    restoredTxCount++
                } else {
                    transactionIdMap[txId] = existingTxs.first {
                        it.id == txId || TransactionIngestionEngine.isDuplicateTransaction(tx, it)
                    }.id
                }
            }

            val splitArr = root.optJSONArray("splits") ?: JSONArray()
            val ownedTransactions = dao.getAllTransactionsSyncForUser(uid).associateBy { it.id }
            val existingSplits = dao.getAllSplitsSyncForUser(uid).mapTo(mutableSetOf()) { it.id }
            for (i in 0 until splitArr.length()) {
                val splitObj = splitArr.getJSONObject(i)
                if (!backupRecordBelongsTo(uid, splitObj)) {
                    Log.w(TAG, "Skipping transaction split with mismatched backup owner")
                    continue
                }
                val backupParentId = splitObj.optString("transactionId", "")
                val categoryId = splitObj.optString("categoryId", "")
                val subcategoryId = splitObj.optString("subcategoryId", "")
                val parentId = transactionIdMap[backupParentId]
                val parent = parentId?.let { ownedTransactions[it] }
                if (parent == null || parent.userId != uid ||
                    (categoryId.isNotBlank() && categoryId !in ownedCategoryIds) ||
                    (subcategoryId.isNotBlank() &&
                        ownedSubcategories[subcategoryId]?.categoryId != categoryId)
                ) {
                    Log.w(TAG, "Skipping transaction split with missing owner-scoped relationship")
                    continue
                }

                val split = TransactionSplitEntity(
                    id = splitObj.getString("id"),
                    transactionId = parent.id,
                    categoryId = categoryId,
                    subcategoryId = subcategoryId,
                    amount = splitObj.getDouble("amount"),
                    note = splitObj.optString("note", ""),
                    createdAt = splitObj.optString("createdAt", getNowIso()),
                    updatedAt = splitObj.optString("updatedAt", getNowIso()),
                    userId = uid
                )
                if (overwriteLocal || split.id !in existingSplits) {
                    dao.insertSplits(listOf(split))
                    existingSplits.add(split.id)
                }
            }

            val nowStr = SimpleDateFormat("dd MMM, h:mm a", Locale.US).format(Date())
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_LAST_RESTORE_TIME, nowStr).apply()

            val msg = "Restore Complete: $restoredTxCount new transactions, $restoredAccCount accounts, $restoredCardCount cards restored from Google Drive."
            Log.d(TAG, msg)
            Result.success(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Google Drive restore failed: ${e.message}", e)
            Result.failure(Exception("Restore failed (${e.localizedMessage}). Existing local database remains safe."))
        }
    }

    // --- Google Drive REST API Helper Functions ---

    private fun findBackupFileIdOnDrive(accessToken: String): String? {
        val query = "name = '$BACKUP_FILENAME' and trashed = false"
        val urlStr = "https://www.googleapis.com/drive/v3/files?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        if (conn.responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()
            val resObj = JSONObject(sb.toString())
            val files = resObj.optJSONArray("files")
            if (files != null && files.length() > 0) {
                return files.getJSONObject(0).getString("id")
            }
        }
        return null
    }

    private fun createDriveFile(accessToken: String, fileName: String, contentBytes: ByteArray): String {
        val boundary = "----KharchaBackupBoundary" + System.currentTimeMillis()
        val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        val metadata = JSONObject()
        metadata.put("name", fileName)
        metadata.put("mimeType", "application/json")

        val out = conn.outputStream
        val writer = OutputStreamWriter(out, "UTF-8")

        writer.write("--$boundary\r\n")
        writer.write("Content-Type: application/json; charset=UTF-8\r\n\r\n")
        writer.write(metadata.toString())
        writer.write("\r\n--$boundary\r\n")
        writer.write("Content-Type: application/json\r\n\r\n")
        writer.flush()

        out.write(contentBytes)
        out.flush()

        writer.write("\r\n--$boundary--\r\n")
        writer.flush()
        writer.close()

        val respCode = conn.responseCode
        if (respCode == 200 || respCode == 201) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()
            return JSONObject(sb.toString()).getString("id")
        } else {
            throw Exception("Google Drive file creation HTTP error $respCode")
        }
    }

    private fun updateDriveFile(accessToken: String, fileId: String, contentBytes: ByteArray) {
        val url = URL("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        val out = conn.outputStream
        out.write(contentBytes)
        out.flush()
        out.close()

        val respCode = conn.responseCode
        if (respCode != 200 && respCode != 204) {
            throw Exception("Google Drive file update HTTP error $respCode")
        }
    }

    private fun downloadDriveFile(accessToken: String, fileId: String): String {
        val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        if (conn.responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
            val sb = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()
            return sb.toString()
        } else {
            throw Exception("Google Drive download HTTP error ${conn.responseCode}")
        }
    }
}
