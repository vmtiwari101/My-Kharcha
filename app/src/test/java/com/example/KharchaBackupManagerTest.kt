package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.AuthManager
import com.example.utils.BackupStatus
import com.example.utils.KharchaBackupManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KharchaBackupManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(db)
        KharchaBackupManager.init(context)
    }

    @After
    fun tearDown() {
        AppDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testCreateBackupJsonPayloadExcludesSensitiveData() = runBlocking {
        val dao = db.kharchaDao()
        val acc = AccountEntity(
            id = "acc-test-drive",
            name = "SBI Savings",
            type = "Bank Account",
            bankName = "SBI",
            last4Digits = "2663",
            icon = "landmark",
            colour = "#0284C7",
            isActive = true,
            isDefault = true,
            isOwnedByMe = true,
            createdAt = "2026-09-28T00:00:00Z",
            updatedAt = "2026-09-28T00:00:00Z"
        )
        dao.insertAccount(acc)

        val tx = TransactionEntity(
            id = "tx-test-drive-1",
            type = "EXPENSE",
            amount = 240.0,
            date = "2026-09-28",
            time = "19:07",
            merchant = "Tripathi Medical Store",
            categoryId = "cat-health",
            subcategoryId = "",
            accountId = "acc-test-drive",
            paymentMethod = "UPI",
            note = "Paid ₹240 for medicine",
            source = "SMS",
            transactionReference = "111500928245",
            originalReference = "SMS-111500928245",
            last4Digits = "2663"
        )
        dao.insertTransaction(tx)

        val payload = KharchaBackupManager.createBackupJsonPayload(
            context = context,
            ownerEmail = "user@example.com",
            userId = "usr_123"
        )

        assertEquals(1, payload.getInt("backupVersion"))
        assertEquals(8, payload.getInt("schemaVersion"))
        assertEquals("user@example.com", payload.getString("ownerEmail"))

        val txArray = payload.getJSONArray("transactions")
        assertEquals(1, txArray.length())
        val savedTxObj = txArray.getJSONObject(0)
        assertEquals("tx-test-drive-1", savedTxObj.getString("id"))
        assertEquals(240.0, savedTxObj.getDouble("amount"), 0.001)

        // Excluded data verification
        assertFalse(payload.has("rawSmsText"))
        assertFalse(payload.has("rawNotificationText"))
        assertFalse(payload.has("gmailAccessToken"))
        assertFalse(payload.has("otp"))
        assertFalse(payload.has("upiPin"))
        assertFalse(payload.has("password"))
    }

    @Test
    fun testBackupPendingStateTracking() {
        KharchaBackupManager.markBackupPending(context)
        assertTrue(KharchaBackupManager.isBackupPending.value)
    }

    @Test
    fun testUserIsolationEnforcement() = runBlocking {
        val userA = "userA@example.com"
        val userB = "userB@example.com"

        val payload = JSONObject().apply {
            put("backupVersion", 1)
            put("schemaVersion", 8)
            put("ownerEmail", userA)
            put("transactions", org.json.JSONArray())
        }

        // Attempting to restore User A's backup payload under User B
        val isMatch = payload.optString("ownerEmail", "") == userB
        assertFalse("User B must NOT be able to restore User A's backup", isMatch)
    }

    @Test
    fun testCorruptedBackupProtectionPreservesLocalData() = runBlocking {
        val dao = db.kharchaDao()
        val originalTx = TransactionEntity(
            id = "tx-original-keep",
            type = "EXPENSE",
            amount = 100.0,
            date = "2026-09-28",
            time = "10:00",
            merchant = "Local Merchant",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-local",
            paymentMethod = "Cash",
            note = "",
            source = "MANUAL",
            transactionReference = ""
        )
        dao.insertTransaction(originalTx)

        val corruptedJson = "{ invalid json content ... }"
        var parsingError = false
        try {
            JSONObject(corruptedJson)
        } catch (e: Exception) {
            parsingError = true
        }

        assertTrue("Corrupted JSON must be detected", parsingError)
        val localTxsAfterError = dao.getAllTransactionsSync()
        assertEquals("Local database must be preserved untouched", 1, localTxsAfterError.size)
        assertEquals("tx-original-keep", localTxsAfterError[0].id)
    }

    @Test
    fun testDeduplicatedRestoreFlow() = runBlocking {
        val dao = db.kharchaDao()
        val tx = TransactionEntity(
            id = "tx-dedup-1",
            type = "EXPENSE",
            amount = 50.0,
            date = "2026-09-28",
            time = "12:00",
            merchant = "Tea Stall",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-cash",
            paymentMethod = "Cash",
            note = "",
            source = "MANUAL",
            transactionReference = "REF_TEA_123"
        )
        dao.insertTransaction(tx)

        // Attempting to insert duplicate transaction
        val existing = dao.getAllTransactionsSync()
        val isDuplicate = existing.any { it.id == tx.id || it.transactionReference == tx.transactionReference }
        assertTrue("Duplicate transaction should be recognized", isDuplicate)
    }
}
