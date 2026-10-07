package com.example.worker

import android.accounts.Account
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.database.AppDatabase
import com.example.data.repository.KharchaRepository
import com.example.utils.KharchaBackupManager
import com.example.utils.BillIngestionResult
import com.example.utils.CreditCardBillIngestionEngine
import com.example.utils.EmailParser
import com.example.utils.EmailParserStatus
import com.example.utils.IngestionStatus
import com.example.utils.TransactionIngestionEngine
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GmailMessageData(
    val id: String,
    val subject: String,
    val snippet: String,
    val internalDate: Long
)

data class GmailPageResult(
    val messages: List<GmailMessageData>,
    val nextPageToken: String? = null
)

interface GmailMessageSource {
    suspend fun getAuthorizationToken(context: Context, accountEmail: String): Result<String>
    suspend fun fetchMessagesPage(token: String, query: String, maxResults: Long, pageToken: String? = null): GmailPageResult
    suspend fun fetchMessages(token: String, query: String, maxResults: Long): List<GmailMessageData> {
        return fetchMessagesPage(token, query, maxResults, null).messages
    }
}

class DefaultGmailMessageSource : GmailMessageSource {
    override suspend fun getAuthorizationToken(context: Context, accountEmail: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val scope = "oauth2:https://www.googleapis.com/auth/gmail.readonly"
            val account = Account(accountEmail, "com.google")
            val token = GoogleAuthUtil.getToken(context, account, scope)
            Result.success(token)
        } catch (e: UserRecoverableAuthException) {
            // Background worker MUST NOT launch UI; return failure to indicate user re-authorization required
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchMessagesPage(token: String, query: String, maxResults: Long, pageToken: String?): GmailPageResult = withContext(Dispatchers.IO) {
        val transport = NetHttpTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val credential = GoogleCredential().setAccessToken(token)

        val gmailService = Gmail.Builder(transport, jsonFactory, credential)
            .setApplicationName("My Kharcha")
            .build()

        var messagesResponse: com.google.api.services.gmail.model.ListMessagesResponse? = null
        var attempt = 0
        val maxRetries = 3
        var success = false

        while (attempt < maxRetries && !success) {
            try {
                val listReq = gmailService.users().messages().list("me")
                    .setQ(query)
                    .setMaxResults(maxResults)
                if (!pageToken.isNullOrBlank()) {
                    listReq.pageToken = pageToken
                }
                messagesResponse = listReq.execute()
                success = true
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val isRateLimit = msg.contains("403") || msg.contains("RATE_LIMIT_EXCEEDED", true) || msg.contains("quotaExceeded", true)
                if (isRateLimit && attempt < maxRetries - 1) {
                    attempt++
                    delay((1L shl attempt) * 1000L)
                } else if (isRateLimit) {
                    throw IOException("Gmail rate limit exceeded")
                } else {
                    throw e
                }
            }
        }

        val summaries = messagesResponse?.messages ?: emptyList()
        val results = mutableListOf<GmailMessageData>()

        for (summary in summaries) {
            var msg: com.google.api.services.gmail.model.Message? = null
            var msgAttempt = 0
            var msgSuccess = false
            while (msgAttempt < maxRetries && !msgSuccess) {
                try {
                    msg = gmailService.users().messages().get("me", summary.id).execute()
                    msgSuccess = true
                } catch (e: Exception) {
                    val err = e.message ?: ""
                    val isRateLimit = err.contains("403") || err.contains("RATE_LIMIT_EXCEEDED", true) || err.contains("quotaExceeded", true)
                    if (isRateLimit && msgAttempt < maxRetries - 1) {
                        msgAttempt++
                        delay((1L shl msgAttempt) * 1000L)
                    } else {
                        break
                    }
                }
            }

            if (msg != null) {
                val subject = msg.payload?.headers?.find { it.name.equals("Subject", true) }?.value ?: ""
                val snippet = msg.snippet ?: ""
                val internalDate = msg.internalDate ?: System.currentTimeMillis()
                results.add(GmailMessageData(summary.id, subject, snippet, internalDate))
            }
        }

        GmailPageResult(results, messagesResponse?.nextPageToken)
    }

    override suspend fun fetchMessages(token: String, query: String, maxResults: Long): List<GmailMessageData> {
        return fetchMessagesPage(token, query, maxResults, null).messages
    }
}

sealed class SyncResult {
    data class Success(val imported: Int, val duplicates: Int, val ignored: Int) : SyncResult()
    data class Skipped(val reason: String) : SyncResult()
    data object AuthRequired : SyncResult()
    data class NetworkError(val message: String) : SyncResult()
    data class Error(val throwable: Throwable) : SyncResult()
}

object EmailSyncEngine {
    private const val TAG = "EmailSyncEngine"
    private const val PREFS_NAME = "kharcha_prefs"
    const val MAX_PAGES_PER_RUN = 4
    const val PAGE_SIZE = 25L
    private const val LEGACY_OWNER = "legacy:unassigned"

    // Swappable for tests
    var messageSource: GmailMessageSource = DefaultGmailMessageSource()

    @Volatile
    internal var liveAuthenticatedUidProvider: () -> String? = {
        try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (e: Exception) {
            Log.w(TAG, "Firebase authentication is unavailable", e)
            null
        }
    }

    internal fun liveAuthenticatedUid(): String? =
        liveAuthenticatedUidProvider()?.takeIf { it.isNotBlank() && it != LEGACY_OWNER }

    private fun isCurrentOwner(owner: String): Boolean = liveAuthenticatedUid() == owner

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun updateScanStatus(context: Context, status: String) {
        getPrefs(context).edit().putString("last_email_scan", status).apply()
    }

    private fun updateScanStatusForOwner(context: Context, owner: String, status: String) {
        if (isCurrentOwner(owner)) updateScanStatus(context, status)
    }

    suspend fun syncEmails(
        context: Context,
        isBackground: Boolean = true,
        expectedOwnerUid: String? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        val owner = liveAuthenticatedUid()
            ?: return@withContext SyncResult.Skipped("No authenticated Firebase user")
        if (expectedOwnerUid != null && expectedOwnerUid != owner) {
            Log.w(TAG, "Email sync rejected because scheduled owner does not match live Firebase user")
            return@withContext SyncResult.Skipped("Authenticated user changed")
        }

        val prefs = getPrefs(context)
        if (prefs.getString(EmailTrackingScheduler.PREFS_OWNER_UID, null) != owner) {
            Log.w(TAG, "Email sync skipped because Gmail settings belong to another or unknown Firebase user")
            return@withContext SyncResult.Skipped("Gmail account is not bound to the authenticated user")
        }
        val isEnabled = prefs.getBoolean("email_tracking_enabled", false)
        if (!isEnabled) {
            Log.d(TAG, "Email tracking disabled in preferences. Skipping sync.")
            return@withContext SyncResult.Skipped("Email tracking is disabled")
        }

        val isConnected = prefs.getBoolean("gmail_connected", false)
        val accountEmail = prefs.getString("gmail_account", "") ?: ""
        if (!isConnected || accountEmail.isBlank() || accountEmail.equals("Not connected", ignoreCase = true)) {
            Log.d(TAG, "Gmail account not connected. Skipping sync.")
            return@withContext SyncResult.Skipped("Gmail is not connected")
        }

        Log.d(TAG, "Starting ${if (isBackground) "background" else "manual"} email sync for $accountEmail")

        // 1. Obtain authorization token silently
        val tokenResult = messageSource.getAuthorizationToken(context, accountEmail)
        if (tokenResult.isFailure) {
            val ex = tokenResult.exceptionOrNull()
            Log.e(TAG, "Failed to get authorization token: ${ex?.message}")
            if (ex is UserRecoverableAuthException || ex?.message?.contains("User intervention required", true) == true) {
                updateScanStatusForOwner(context, owner, "Authorization required")
                return@withContext SyncResult.AuthRequired
            }
            if (ex is IOException || ex?.message?.contains("network", true) == true) {
                updateScanStatusForOwner(context, owner, "Waiting for network")
                return@withContext SyncResult.NetworkError(ex.message ?: "Network error")
            }
            updateScanStatusForOwner(context, owner, "Authorization required")
            return@withContext SyncResult.AuthRequired
        }

        val token = tokenResult.getOrThrow()

        // 2. Determine incremental query
        val checkpointKey = "last_email_scan_checkpoint_$owner"
        val lastCheckpoint = prefs.getLong(checkpointKey, 0L)
        val query = if (lastCheckpoint > 0L) {
            // 5 minute safety overlap window (300 seconds)
            val afterSec = maxOf(0L, (lastCheckpoint / 1000L) - 300L)
            "label:INBOX after:$afterSec"
        } else {
            "label:INBOX"
        }

        // 3. Ingest messages using central engines with safe pagination
        if (!isCurrentOwner(owner)) {
            return@withContext SyncResult.Skipped("Authenticated user changed")
        }
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        if (!isCurrentOwner(owner)) {
            return@withContext SyncResult.Skipped("Authenticated user changed")
        }
        val existingTxs = dao.getAllTransactionsSyncForUser(owner).filter { it.userId == owner }
        val processedRefs = existingTxs.mapNotNull { it.originalReference }.toMutableSet()

        var imported = 0
        var duplicates = 0
        var ignored = 0
        var maxInternalDateInRun = lastCheckpoint
        var totalMessagesFetched = 0

        var currentPageToken: String? = null
        var pageCount = 0
        var hasMoreUnfetchedPages = false

        while (pageCount < MAX_PAGES_PER_RUN) {
            pageCount++
            val pageResult = try {
                messageSource.fetchMessagesPage(token, query, maxResults = PAGE_SIZE, pageToken = currentPageToken)
            } catch (e: IOException) {
                Log.w(TAG, "Network failure while fetching messages: ${e.message}")
                updateScanStatusForOwner(context, owner, "Waiting for network")
                return@withContext SyncResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                Log.e(TAG, "Failed fetching messages: ${e.message}", e)
                return@withContext SyncResult.Error(e)
            }

            val messages = pageResult.messages
            totalMessagesFetched += messages.size

            for (msg in messages) {
                if (!isCurrentOwner(owner)) {
                    Log.w(TAG, "Email sync stopped because the authenticated user changed")
                    return@withContext SyncResult.Skipped("Authenticated user changed")
                }
                if (msg.internalDate > maxInternalDateInRun) {
                    maxInternalDateInRun = msg.internalDate
                }

                val expectedRef = "EMAIL-${msg.id}"
                if (processedRefs.contains(expectedRef)) {
                    duplicates++
                    continue
                }

                val fullEmailText = "${msg.subject} ${msg.snippet}"

                // Check if credit card bill email
                if (CreditCardBillIngestionEngine.isCreditCardBillMessage(fullEmailText)) {
                    val billInfo = CreditCardBillIngestionEngine.extractBillInfo(
                        msg.snippet,
                        msg.subject,
                        "EMAIL",
                        msg.id,
                        msg.internalDate
                    )
                    if (billInfo != null) {
                        if (liveAuthenticatedUid() != owner) {
                            Log.w(TAG, "Email bill skipped because the authenticated user changed")
                            ignored++
                            continue
                        }
                        val billResult = CreditCardBillIngestionEngine.ingestBillInfo(
                            context,
                            billInfo,
                            expectedOwnerUid = owner
                        )
                        when (billResult) {
                            is BillIngestionResult.Updated,
                            is BillIngestionResult.UnlinkedReminder -> {
                                imported++
                                processedRefs.add(expectedRef)
                            }
                            is BillIngestionResult.Duplicate -> duplicates++
                            else -> ignored++
                        }
                    } else {
                        ignored++
                    }
                    continue
                }

                // Standard transaction parsing via existing EmailParser
                if (!isCurrentOwner(owner)) {
                    return@withContext SyncResult.Skipped("Authenticated user changed")
                }
                val ownerTransactions = dao.getAllTransactionsSyncForUser(owner)
                    .filter { it.userId == owner }
                if (!isCurrentOwner(owner)) {
                    return@withContext SyncResult.Skipped("Authenticated user changed")
                }
                val parseResult = EmailParser.parseEmail(
                    messageId = msg.id,
                    subject = msg.subject,
                    body = msg.snippet,
                    timestamp = msg.internalDate,
                    existingTransactions = ownerTransactions
                )

                if (parseResult.transaction != null) {
                    if (!isCurrentOwner(owner)) {
                        return@withContext SyncResult.Skipped("Authenticated user changed")
                    }
                    val emailTransaction = parseResult.transaction.copy(userId = owner)
                    val ingestionResult = TransactionIngestionEngine.ingestTransaction(
                        context,
                        emailTransaction,
                        fullEmailText
                    )
                    when (ingestionResult.second) {
                        IngestionStatus.IMPORTED,
                        IngestionStatus.NEEDS_REVIEW,
                        IngestionStatus.ENRICHED -> {
                            imported++
                            processedRefs.add(expectedRef)
                        }
                        IngestionStatus.DUPLICATE -> duplicates++
                        else -> ignored++
                    }
                } else {
                    if (parseResult.status == EmailParserStatus.IGNORED) {
                        ignored++
                    } else {
                        duplicates++
                    }
                }
            }

            currentPageToken = pageResult.nextPageToken
            if (currentPageToken.isNullOrBlank()) {
                hasMoreUnfetchedPages = false
                break
            } else {
                hasMoreUnfetchedPages = true
            }
        }

        val nowFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())

        if (totalMessagesFetched == 0) {
            val status = if (isBackground) {
                "Automatic tracking active: No new transactions ($nowFmt)"
            } else {
                "Last scanned: $nowFmt (0 imported)"
            }
            updateScanStatusForOwner(context, owner, status)
            return@withContext SyncResult.Success(imported = 0, duplicates = 0, ignored = 0)
        }

        // 4. Update incremental checkpoint ONLY if all pages in the window were completely retrieved
        if (!hasMoreUnfetchedPages && maxInternalDateInRun > lastCheckpoint && isCurrentOwner(owner)) {
            prefs.edit().putLong(checkpointKey, maxInternalDateInRun).apply()
        }

        // 5. Update user-visible status
        val statusText = if (isBackground) {
            if (imported > 0) {
                "Automatic tracking active: $imported new transactions imported ($nowFmt)"
            } else {
                "Automatic tracking active: No new transactions ($nowFmt)"
            }
        } else {
            "Last scanned: $nowFmt ($imported imported)"
        }
        updateScanStatusForOwner(context, owner, statusText)

        // 6. Mark backup pending for Google Drive if new transactions were imported
        if (isCurrentOwner(owner) && imported > 0) {
            try {
                KharchaBackupManager.markBackupPending(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mark Google Drive backup pending following email sync: ${e.message}")
            }
        }

        Log.d(TAG, "Email sync completed: $imported imported, $duplicates duplicates, $ignored ignored (hasMore=$hasMoreUnfetchedPages)")
        SyncResult.Success(imported, duplicates, ignored)
    }
}
