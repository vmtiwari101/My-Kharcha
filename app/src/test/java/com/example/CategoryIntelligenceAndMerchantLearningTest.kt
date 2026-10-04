package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.*
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
class CategoryIntelligenceAndMerchantLearningTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.setTestInstance(db)
        runBlocking {
            com.example.data.DefaultCategoryData.restoreAndExpandCategoriesAndSubcategories(db.kharchaDao())
            db.kharchaDao().insertAccount(
                AccountEntity(
                    id = "acc-cash",
                    name = "Cash in Hand",
                    type = "Cash",
                    bankName = "",
                    last4Digits = "",
                    isDefault = true,
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

    @Test
    fun test1_manualMerchantMappingIsLearned() {
        MerchantLearningEngine.saveMapping(context, "Bablu Chat Corner", "cat-food", "sub-fast-food")
        val mapping = MerchantLearningEngine.getMapping(context, "Bablu Chat Corner")
        assertNotNull(mapping)
        assertEquals("cat-food", mapping?.first)
        assertEquals("sub-fast-food", mapping?.second)
    }

    @Test
    fun test2_learnedMappingAppliesToFutureSameMerchant() = runBlocking {
        MerchantLearningEngine.saveMapping(context, "Dharmendra Kirana Store", "cat-groceries", "sub-groceries-general")

        val tx = TransactionEntity(
            id = "tx-dharmendra-1",
            type = "EXPENSE",
            amount = 150.0,
            date = "2026-09-30",
            time = "10:00",
            merchant = "Dharmendra Kirana Store",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "acc-cash",
            paymentMethod = "Cash",
            note = "",
            source = "SMS",
            transactionReference = ""
        )

        val (ingested, status) = TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = tx,
            rawText = "Paid Rs 150 to Dharmendra Kirana Store"
        )

        assertNotNull(ingested)
        assertEquals("cat-groceries", ingested?.categoryId)
        assertEquals("sub-groceries-general", ingested?.subcategoryId)
    }

    @Test
    fun test3_normalizationAwareMatchingWorks() = runBlocking {
        MerchantLearningEngine.saveMapping(context, "Dharmendrakiranastore", "cat-groceries", "sub-groceries-general")

        val mapping = MerchantLearningEngine.getMapping(context, "Dharmendra Kirana Store")
        assertNotNull(mapping)
        assertEquals("cat-groceries", mapping?.first)
    }

    @Test
    fun test4_manualCorrectionOverridesPreviousLearnedCategory() = runBlocking {
        MerchantLearningEngine.saveMapping(context, "Bablu Chat Corner", "cat-food", "sub-fast-food")
        MerchantLearningEngine.saveMapping(context, "Bablu Chat Corner", "cat-shopping", "sub-shopping-general")

        val mapping = MerchantLearningEngine.getMapping(context, "Bablu Chat Corner")
        assertNotNull(mapping)
        assertEquals("cat-shopping", mapping?.first)
    }

    @Test
    fun test5_unknownMerchantSafelyRemainsOtherUncategorized() = runBlocking {
        val tx = TransactionEntity(
            id = "tx-unknown-1",
            type = "EXPENSE",
            amount = 100.0,
            date = "2026-09-30",
            time = "11:00",
            merchant = "Unknown Store XYZ",
            categoryId = "cat-other",
            subcategoryId = "",
            accountId = "acc-cash",
            paymentMethod = "Cash",
            note = "",
            source = "SMS",
            transactionReference = ""
        )

        val (ingested, _) = TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = tx,
            rawText = "Paid Rs 100 to Unknown Store XYZ"
        )

        assertNotNull(ingested)
        assertEquals("cat-other", ingested?.categoryId)
        assertEquals("", ingested?.subcategoryId)
    }

    @Test
    fun test6_lowConfidenceMerchantIsNotForceCategorized() = runBlocking {
        val tx = TransactionEntity(
            id = "tx-weak-1",
            type = "EXPENSE",
            amount = 50.0,
            date = "2026-09-30",
            time = "12:00",
            merchant = "Other",
            categoryId = "cat-recharge",
            subcategoryId = "",
            accountId = "acc-cash",
            paymentMethod = "UPI",
            note = "",
            source = "SMS",
            transactionReference = ""
        )

        val (ingested, _) = TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = tx,
            rawText = "Paid Rs 50 via Airtel UPI"
        )

        assertNotNull(ingested)
        assertEquals("cat-other", ingested?.categoryId)
    }

    @Test
    fun test7_differentUsersMappingsNeverMix() {
        MerchantLearningEngine.saveMapping(context, "Shop A", "cat-groceries", "")
        val mapping = MerchantLearningEngine.getMapping(context, "Shop A")
        assertNotNull(mapping)
    }

    @Test
    fun test8_incomeIsNeverAssignedAnExpenseCategory() = runBlocking {
        val tx = TransactionEntity(
            id = "tx-income-1",
            type = "INCOME",
            amount = 50000.0,
            date = "2026-09-30",
            time = "09:00",
            merchant = "Employer Corp",
            categoryId = "cat-salary",
            subcategoryId = "",
            accountId = "acc-cash",
            paymentMethod = "Bank Transfer",
            note = "",
            source = "SMS",
            transactionReference = ""
        )

        val (ingested, _) = TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = tx,
            rawText = "Salary credited of Rs 50000"
        )

        assertNotNull(ingested)
        assertEquals("INCOME", ingested?.type)
        assertEquals("cat-salary", ingested?.categoryId)
    }

    @Test
    fun test9_expenseOffBehaviorRemainsUnchanged() = runBlocking {
        val tx = TransactionEntity(
            id = "tx-off-1",
            type = "EXPENSE",
            amount = 1000.0,
            date = "2026-09-30",
            time = "10:00",
            merchant = "Transfer to Self",
            categoryId = "cat-transfer",
            subcategoryId = "",
            accountId = "acc-cash",
            paymentMethod = "UPI",
            note = "",
            source = "SMS",
            isInternalTransfer = true,
            transactionReference = ""
        )

        val (ingested, _) = TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = tx,
            rawText = "Transferred Rs 1000 to own account"
        )

        assertNotNull(ingested)
        assertEquals("cat-transfer", ingested?.categoryId)
    }

    @Test
    fun test10_existingCategoryManuallySelectedByUserIsNeverOverwritten() = runBlocking {
        val tx = TransactionEntity(
            id = "tx-manual-1",
            type = "EXPENSE",
            amount = 200.0,
            date = "2026-09-30",
            time = "13:00",
            merchant = "Custom Boutique",
            categoryId = "cat-shopping",
            subcategoryId = "sub-shopping-general",
            accountId = "acc-cash",
            paymentMethod = "Cash",
            note = "",
            source = "MANUAL",
            transactionReference = ""
        )

        val (ingested, _) = TransactionIngestionEngine.ingestTransaction(
            context = context,
            rawTx = tx,
            rawText = "Custom Boutique Rs 200 paid"
        )

        assertNotNull(ingested)
        assertEquals("cat-shopping", ingested?.categoryId)
        assertEquals("sub-shopping-general", ingested?.subcategoryId)
    }
}
