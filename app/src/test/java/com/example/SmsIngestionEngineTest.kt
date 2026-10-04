package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.IngestionStatus
import com.example.utils.SmsParser
import com.example.utils.TransactionIngestionEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmsIngestionEngineTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(db)
    }

    @After
    fun tearDown() {
        AppDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testFullIngestionFlowForDebitSms() = runBlocking {
        val dao = db.kharchaDao()
        
        // 1. Setup - create a bank account that matches the SMS last4
        val account = AccountEntity(
            id = "acc-test-1",
            name = "Test Bank",
            type = "Savings Account",
            bankName = "HDFC",
            last4Digits = "4092",
            icon = "landmark",
            colour = "#1E40AF",
            isActive = true,
            isDefault = true,
            isOwnedByMe = true,
            createdAt = "2026-09-25T00:00:00Z",
            updatedAt = "2026-09-25T00:00:00Z"
        )
        dao.insertAccount(account)

        // 2. The Test SMS
        val smsBody = "Your A/c XX4092 is debited for Rs.5.00 on 25-09-26 at AMAZON. Ref: 12345678."
        val smsAddress = "HDFCBK"
        val timestamp = System.currentTimeMillis()

        // 3. Parse the SMS
        val parseStatus = SmsParser.parseSms("msg-1", smsAddress, smsBody, timestamp)
        assertTrue("Parsing should succeed", parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction
        
        assertEquals("Should be classified as EXPENSE", "EXPENSE", tx.type)
        assertEquals("Amount should be 5.0", 5.0, tx.amount, 0.001)
        assertEquals("Merchant should be extracted", "Amazon", tx.merchant)

        // 4. Ingest via Engine
        val rawText = "$smsAddress ${tx.merchant} ${tx.note} ${tx.last4Digits} $smsBody"
        val (ingestedTx, status) = TransactionIngestionEngine.ingestTransaction(context, tx, rawText)
        
        assertEquals("Ingestion should be IMPORTED", IngestionStatus.IMPORTED, status)
        assertNotNull("Ingested transaction should not be null", ingestedTx)

        // 5. Verify Database State
        val allTransactions = dao.getAllTransactionsSync()
        assertEquals("Database should contain exactly 1 transaction", 1, allTransactions.size)
        val dbTx = allTransactions[0]
        assertEquals(5.0, dbTx.amount, 0.001)
        assertEquals("acc-test-1", dbTx.accountId)

        // 6. Test Duplicate Prevention
        val (dupTx, dupStatus) = TransactionIngestionEngine.ingestTransaction(context, tx, rawText)
        assertEquals("Second ingestion of same SMS should be DUPLICATE", IngestionStatus.DUPLICATE, dupStatus)
        assertEquals("Database should still contain only 1 transaction", 1, dao.getAllTransactionsSync().size)

        // 7. Test Flow Observation
        val flowTransactions = dao.getAllTransactions().first()
        assertEquals("Database count via Flow should be 1", 1, flowTransactions.size)
        assertEquals("Flow emitted transaction amount mismatch", 5.0, flowTransactions[0].amount, 0.001)
    }
}
