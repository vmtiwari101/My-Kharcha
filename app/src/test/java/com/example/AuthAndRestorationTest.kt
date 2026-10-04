package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.TransactionEntity
import com.example.utils.AuthManager
import com.example.utils.KharchaBackupManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthAndRestorationTest {

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

    @org.junit.After
    fun tearDown() {
        AppDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testUserAuthSessionState() {
        val email = "user@example.com"
        val userId = "firebase_uid_12345"
        
        AuthManager.setAuthenticatedUser(context = context, userId = userId, email = email, name = "Test User")
        assertTrue(AuthManager.isLoggedIn.value)
        assertEquals("user@example.com", AuthManager.currentUser.value?.email)
        assertEquals("Test User", AuthManager.currentUser.value?.name)
        assertEquals(userId, AuthManager.currentUser.value?.id)

        AuthManager.logout(context)
        assertFalse(AuthManager.isLoggedIn.value)
        assertNull(AuthManager.currentUser.value)
    }

    @Test
    fun testGoogleDriveBackupJsonPayloadGeneration() = runBlocking {
        val userUid = "google_uid_user_one"
        val email = "user1@example.com"
        AuthManager.setAuthenticatedUser(context = context, userId = userUid, email = email, name = "User One")

        AppDatabase.prepopulateData(db.kharchaDao())

        val txId = "tx-restore-1"
        val tx = TransactionEntity(
            id = txId,
            type = "EXPENSE",
            amount = 150.0,
            date = "2026-09-23",
            time = "10:00",
            merchant = "Amazon",
            categoryId = "cat-shopping",
            subcategoryId = "",
            accountId = "acc-hdfc",
            paymentMethod = "UPI",
            note = "Shopping",
            source = "SMS",
            transactionReference = "",
            originalReference = "",
            last4Digits = "",
            createdAt = "",
            updatedAt = ""
        )
        db.kharchaDao().insertTransaction(tx)

        // Generate the real Google Drive backup JSON payload
        val backupJson = KharchaBackupManager.createBackupJsonPayload(context, email, userUid)
        assertNotNull(backupJson)
        assertEquals(1, backupJson.getInt("backupVersion"))
        assertEquals(8, backupJson.getInt("schemaVersion"))
        assertEquals(email, backupJson.getString("ownerEmail"))
        assertEquals(userUid, backupJson.getString("userId"))

        val txArray = backupJson.getJSONArray("transactions")
        assertTrue(txArray.length() > 0)

        var foundTx = false
        for (i in 0 until txArray.length()) {
            val tObj = txArray.getJSONObject(i)
            if (tObj.getString("id") == txId) {
                foundTx = true
                assertEquals(150.0, tObj.getDouble("amount"), 0.01)
                assertEquals("Amazon", tObj.getString("merchant"))
            }
        }
        assertTrue("Our transaction must be present in the generated backup payload", foundTx)
    }

    @Test
    fun testMultiUserSessionIsolation() = runBlocking {
        val uidA = "google_uid_user_a"
        val uidB = "google_uid_user_b"

        AuthManager.setAuthenticatedUser(context = context, userId = uidA, email = "usera@example.com", name = "User A")
        assertEquals("usera@example.com", AuthManager.currentUser.value?.email)

        AuthManager.setAuthenticatedUser(context = context, userId = uidB, email = "userb@example.com", name = "User B")
        assertEquals("userb@example.com", AuthManager.currentUser.value?.email)
        assertNotEquals(uidA, AuthManager.currentUser.value?.id)
    }

    @Test
    fun testEmailAuthSessionState() = runBlocking {
        val email = "rahul.sharma@example.com"
        val userId = "google_uid_rahul_123"

        val user = AuthManager.setAuthenticatedUser(
            context = context,
            userId = userId,
            email = email,
            name = "Rahul Sharma"
        )
        assertNotNull(user)
        assertEquals(email, user.email)
        assertEquals("Rahul Sharma", user.name)
        assertTrue(AuthManager.isLoggedIn.value)
        assertEquals(userId, AuthManager.currentUser.value?.id)
    }

    @Test
    fun testUninstallReinstallBackupPendingState() = runBlocking {
        val userUid = "google_uid_reinstall_test"
        AuthManager.setAuthenticatedUser(context = context, userId = userUid, email = "test@kharcha.in", name = "Test User")

        assertFalse(KharchaBackupManager.isBackupPending.value)

        // Record high-fidelity transactions
        val tx1 = TransactionEntity(
            id = "tx-cloud-1",
            amount = 450.0,
            type = "EXPENSE",
            date = "2026-09-24",
            time = "10:30",
            merchant = "Swiggy",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-cash",
            paymentMethod = "UPI",
            note = "Dinner",
            source = "MANUAL",
            transactionReference = "",
            originalReference = "",
            last4Digits = "",
            createdAt = "",
            updatedAt = ""
        )
        db.kharchaDao().insertTransaction(tx1)

        // Mark backup pending
        KharchaBackupManager.markBackupPending(context)
        assertTrue(KharchaBackupManager.isBackupPending.value)
    }

    @Test
    fun testFirebaseAuthUidAvailability() {
        val testUid = "firebase_auth_user_999"
        val testEmail = "kharcha.user@gmail.com"
        
        AuthManager.setAuthenticatedUser(
            context = context,
            userId = testUid,
            email = testEmail,
            name = "Firebase Test User"
        )
        
        assertEquals(testUid, AuthManager.getFirebaseUid())
        assertTrue(AuthManager.isLoggedIn.value)
        assertEquals(testEmail, AuthManager.currentUser.value?.email)
        
        AuthManager.logout(context)
        assertNull(AuthManager.currentUser.value)
        assertFalse(AuthManager.isLoggedIn.value)
    }
}
