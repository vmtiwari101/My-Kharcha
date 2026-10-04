package com.example.utils

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.EmailParser
import com.example.utils.EmailParserStatus
import com.example.utils.NotificationParser
import com.example.utils.SmsParser
import com.example.utils.TransactionIngestionEngine
import com.example.utils.IngestionStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MerchantExtractionFixTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.setTestInstance(db)
        runBlocking {
            com.example.data.DefaultCategoryData.restoreAndExpandCategoriesAndSubcategories(db.kharchaDao())
            
            // Insert standard test accounts
            db.kharchaDao().insertAccount(
                AccountEntity(
                    id = "acc-test-hdfc",
                    name = "HDFC Bank Account",
                    type = "Bank Account",
                    bankName = "HDFC",
                    last4Digits = "1234",
                    isDefault = true,
                    isOwnedByMe = true
                )
            )
            db.kharchaDao().insertAccount(
                AccountEntity(
                    id = "acc-test-idfc",
                    name = "IDFC Bank Account",
                    type = "Bank Account",
                    bankName = "IDFC",
                    last4Digits = "2345",
                    isDefault = false,
                    isOwnedByMe = true
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        AppDatabase.setTestInstance(null)
    }

    private fun createTx(
        id: String,
        type: String,
        amount: Double,
        date: String,
        merchant: String,
        categoryId: String = "cat-other",
        subcategoryId: String = "",
        accountId: String = "",
        paymentMethod: String = "Bank Account",
        source: String = "SMS",
        transactionReference: String = "",
        last4Digits: String = "",
        time: String = "10:00",
        note: String = ""
    ): TransactionEntity {
        return TransactionEntity(
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
            source = source,
            transactionReference = transactionReference,
            originalReference = "",
            last4Digits = last4Digits,
            createdAt = "2026-10-01T00:00:00Z",
            updatedAt = "2026-10-01T00:00:00Z",
            direction = if (type == "INCOME") "CREDIT" else "DEBIT"
        )
    }

    @Test
    fun testTimestampIsNotExtractedAsMerchant() {
        val smsText = "Your A/c XX1234 debited by Rs. 250 on 29/09/26 at 07 42 43 PM. RRN 142720788855."
        val merchant = SmsParser.extractMerchant(smsText, "VM-HDFCBK")
        assertNotEquals("07 42 43", merchant)
        assertNotEquals("07 42 43 PM", merchant)
        assertEquals("Unknown Merchant", merchant)
    }

    @Test
    fun testReferenceNumberIsNotExtractedAsMerchant() {
        val smsText = "Your A/c XX1234 debited for Rs. 500 at 142720788855. RRN 142720788855."
        val merchant = SmsParser.extractMerchant(smsText, "VM-HDFCBK")
        assertNotEquals("142720788855", merchant)
        assertEquals("Unknown Merchant", merchant)
    }

    @Test
    fun testPromotionalEmailIsNotExtractedAsMerchant() {
        val emailSubject = "Know the benefits of your credit card and shop now!"
        val emailBody = "Dear Customer, you can get cashback of Rs 100 on your next purchase."
        val output = EmailParser.parseEmail("msg-promo-1", emailSubject, emailBody, System.currentTimeMillis())
        assertEquals(EmailParserStatus.IGNORED, output.status)
        assertNull(output.transaction)
    }

    @Test
    fun testBankBoilerplateIsNotExtractedAsMerchant() {
        val smsText = "Your A/c XX1234 debited by Rs. 100. Available Balance Rs. 5000. Team HDFC Bank."
        val merchant = SmsParser.extractMerchant(smsText, "VM-HDFCBK")
        assertFalse(merchant.contains("Available Balance", ignoreCase = true))
        assertFalse(merchant.contains("Team HDFC", ignoreCase = true))
        assertEquals("Unknown Merchant", merchant)
    }

    @Test
    fun testValidMerchantIsExtractedWhenReliable() {
        val smsText = "Your A/c XX2345 debited by Rs. 454.38 on 29/09/26; ARYAN KISHAN SEVA credited."
        val merchant = SmsParser.extractMerchant(smsText, "VM-IDFCBK")
        assertEquals("Aryan Kishan Seva", merchant)
    }

    @Test
    fun testMissingMerchantBecomesUnknownMerchant() = runBlocking {
        val smsText = "Your account XX1234 has been debited for Rs 1500."
        val result = SmsParser.parseSms("msg-unk", "HDFCBK", smsText, System.currentTimeMillis())
        assertTrue(result is SmsParser.SmsParseStatus.Success)
        val tx = (result as SmsParser.SmsParseStatus.Success).transaction
        
        assertEquals("Merchant is not Unknown Merchant: ${tx.merchant}", "Unknown Merchant", tx.merchant)

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, tx, smsText)
        assertEquals("Status is not NEEDS_REVIEW: $status", IngestionStatus.NEEDS_REVIEW, status)
        assertEquals("Unknown Merchant", ingested?.merchant)
        assertTrue(ingested?.needsReview == true)
    }

    @Test
    fun testCategoryClassificationUsesExistingCanonicalCategories() {
        // Grocery signal
        val groceryCat = SmsParser.determineCategory("Aryan Groceries Store", "EXPENSE", "Spent Rs. 350")
        assertEquals("cat-groceries", groceryCat)

        // Food signal
        val foodCat = SmsParser.determineCategory("Bablu Sweets Corner", "EXPENSE", "Paid for dinner")
        assertEquals("cat-food", foodCat)

        // Repair signal
        val repairCat = SmsParser.determineCategory("Super Bike Repair Shop", "EXPENSE", "Puncture fix")
        assertEquals("cat-repair", repairCat)

        // Utility signal
        val utilityCat = SmsParser.determineCategory("Electricity Bill Desk", "EXPENSE", "Power bill payment")
        assertEquals("cat-bills", utilityCat)
    }

    @Test
    fun testLowConfidenceClassificationIsNotForced() {
        val category = SmsParser.determineCategory("Unknown Merchant", "EXPENSE", "Spent Rs 50")
        assertEquals("cat-other", category)
    }

    @Test
    fun testUserEditedCategoryIsPreserved() = runBlocking {
        val dao = db.kharchaDao()
        
        // Manual transaction with custom category
        val manualTx = createTx(
            id = "tx-manual-1",
            type = "EXPENSE",
            amount = 150.0,
            date = "2026-10-01",
            merchant = "Stationery Shop",
            categoryId = "cat-education"
        )
        dao.insertTransaction(manualTx)

        // Incoming duplicate transaction
        val incomingTx = createTx(
            id = "tx-auto-1",
            type = "EXPENSE",
            amount = 150.0,
            date = "2026-10-01",
            merchant = "Stationery Shop",
            categoryId = "cat-other",
            transactionReference = "REF-USER-1"
        )
        TransactionIngestionEngine.ingestTransaction(context, incomingTx, "Paid 150 REF-USER-1")

        val txInDb = dao.getTransactionByIdSync("tx-manual-1")
        assertNotNull(txInDb)
        assertEquals("cat-education", txInDb?.categoryId)
    }

    @Test
    fun testSameTransactionAcrossSmsNotificationEmailDeduplicates() = runBlocking {
        val dao = db.kharchaDao()

        // 1. Ingest via SMS
        val smsTx = createTx(
            id = "tx-sms-1",
            type = "EXPENSE",
            amount = 999.0,
            date = "2026-10-01",
            merchant = "Amazon",
            categoryId = "cat-shopping",
            accountId = "acc-test-hdfc",
            last4Digits = "1234",
            source = "SMS",
            transactionReference = "TXN-AMZN-99"
        )
        val (_, status1) = TransactionIngestionEngine.ingestTransaction(context, smsTx, "Spent Rs 999 on Amazon from card ending 1234 TXN-AMZN-99")
        assertEquals(IngestionStatus.IMPORTED, status1)

        // 2. Ingest via Notification
        val notifTx = createTx(
            id = "tx-notif-1",
            type = "EXPENSE",
            amount = 999.0,
            date = "2026-10-01",
            merchant = "Amazon",
            categoryId = "cat-shopping",
            accountId = "acc-test-hdfc",
            last4Digits = "1234",
            source = "NOTIFICATION",
            transactionReference = "TXN-AMZN-99"
        )
        val (_, status2) = TransactionIngestionEngine.ingestTransaction(context, notifTx, "Spent Rs 999 on Amazon from card ending 1234 TXN-AMZN-99")
        assertEquals(IngestionStatus.DUPLICATE, status2)

        // 3. Ingest via Email
        val emailTx = createTx(
            id = "tx-email-1",
            type = "EXPENSE",
            amount = 999.0,
            date = "2026-10-01",
            merchant = "Amazon",
            categoryId = "cat-shopping",
            accountId = "acc-test-hdfc",
            last4Digits = "1234",
            source = "EMAIL",
            transactionReference = "TXN-AMZN-99"
        )
        val (_, status3) = TransactionIngestionEngine.ingestTransaction(context, emailTx, "Spent Rs 999 on Amazon from card ending 1234 TXN-AMZN-99")
        assertEquals(IngestionStatus.DUPLICATE, status3)

        // Ensure there is only 1 transaction in DB
        val txs = dao.getAllTransactionsSync()
        assertEquals(1, txs.filter { it.transactionReference == "TXN-AMZN-99" }.size)
    }

    @Test
    fun testDifferentSameAmountTransactionsRemainSeparate() = runBlocking {
        val dao = db.kharchaDao()

        // First transaction (Swiggy)
        val tx1 = createTx(
            id = "tx-swiggy",
            type = "EXPENSE",
            amount = 250.0,
            date = "2026-10-01",
            merchant = "Swiggy",
            categoryId = "cat-food",
            transactionReference = "TXN-SWG-11"
        )
        TransactionIngestionEngine.ingestTransaction(context, tx1, "Spent 250 on Swiggy TXN-SWG-11")

        // Second transaction (Zomato, same amount, same date, same last4, but different ref and merchant)
        val tx2 = createTx(
            id = "tx-zomato",
            type = "EXPENSE",
            amount = 250.0,
            date = "2026-10-01",
            merchant = "Zomato",
            categoryId = "cat-food",
            transactionReference = "TXN-ZMT-22"
        )
        TransactionIngestionEngine.ingestTransaction(context, tx2, "Spent 250 on Zomato TXN-ZMT-22")

        val txs = dao.getAllTransactionsSync()
        assertEquals(2, txs.size)
    }

    @Test
    fun testUnknownBankCardIdentityNeverSilentlyMapsToUnrelatedAccount() = runBlocking {
        // Transaction from a completely unknown card/bank ending in 9999 (We only have 1234 and 2345 registered)
        val tx = createTx(
            id = "tx-unknown-acc",
            type = "EXPENSE",
            amount = 120.0,
            date = "2026-10-01",
            merchant = "Generic Merchant",
            categoryId = "cat-other",
            transactionReference = "TXN-UNK-88",
            last4Digits = "9999"
        )

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(context, tx, "Spent Rs 120 with Card ending 9999")
        println("DEBUG: UnknownAccount test - Actual status: '$status', Ingested Account: '${ingested?.accountId}'")
        assertEquals(IngestionStatus.NEEDS_REVIEW, status)
        assertNotEquals("acc-test-hdfc", ingested?.accountId)
        assertNotEquals("acc-test-idfc", ingested?.accountId)
    }
}
