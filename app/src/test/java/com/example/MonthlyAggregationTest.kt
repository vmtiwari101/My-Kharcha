package com.example

import com.example.data.entity.CategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.viewmodel.KharchaViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyAggregationTest {

    @Test
    fun testFocused_DateFormats() {
        assertEquals("2026-09", KharchaViewModel.extractYearMonthKey("2026-09-02"))
        assertEquals("2026-09", KharchaViewModel.extractYearMonthKey("2026-09-07"))
        assertEquals("2026-09", KharchaViewModel.extractYearMonthKey("Sep '26"))
        assertEquals("2026-09", KharchaViewModel.extractYearMonthKey("2026-09"))
        assertEquals("2026-09", KharchaViewModel.extractYearMonthKey("2026-9"))
    }

    @Test
    fun test2_sameMonthAmountsAreSummedCorrectly() {
        val txs = listOf(
            TransactionEntity(id="1", type="EXPENSE", amount=500.0, date="2026-09-05", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="2", type="EXPENSE", amount=422.0, date="28-SEP-26", time="", merchant="B", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="3", type="EXPENSE", amount=190000.0, date="29-09-2026", time="", merchant="C", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true)
        )

        val aggregated = KharchaViewModel.aggregateMonthlySpendingForCategory(txs, "cat-food")
        assertEquals(1, aggregated.size)
        // 500 + 422 + 190000 = 190922.0
        assertEquals(190922.0, aggregated["2026-09"] ?: 0.0, 0.01)
    }

    @Test
    fun test3_transactionsFromDifferentMonthsProduceSeparateBars() {
        val txs = listOf(
            TransactionEntity(id="1", type="EXPENSE", amount=1000.0, date="2026-08-15", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="2", type="EXPENSE", amount=2500.0, date="2026-09-20", time="", merchant="B", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="3", type="EXPENSE", amount=3000.0, date="2026-10-05", time="", merchant="C", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true)
        )

        val aggregated = KharchaViewModel.aggregateMonthlySpendingForCategory(txs, "cat-food")
        assertEquals(3, aggregated.size)
        assertEquals(1000.0, aggregated["2026-08"] ?: 0.0, 0.01)
        assertEquals(2500.0, aggregated["2026-09"] ?: 0.0, 0.01)
        assertEquals(3000.0, aggregated["2026-10"] ?: 0.0, 0.01)
    }

    @Test
    fun test4_sameMonthInDifferentYearsProducesSeparateBars() {
        val txs = listOf(
            TransactionEntity(id="1", type="EXPENSE", amount=1200.0, date="2025-09-10", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="2", type="EXPENSE", amount=1800.0, date="2026-09-10", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true)
        )

        val aggregated = KharchaViewModel.aggregateMonthlySpendingForCategory(txs, "cat-food")
        assertEquals(2, aggregated.size)
        assertEquals(1200.0, aggregated["2025-09"] ?: 0.0, 0.01)
        assertEquals(1800.0, aggregated["2026-09"] ?: 0.0, 0.01)
    }

    @Test
    fun test5_categoryFilterIsAppliedBeforeAggregation() {
        val txs = listOf(
            TransactionEntity(id="1", type="EXPENSE", amount=500.0, date="2026-09-01", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="2", type="EXPENSE", amount=300.0, date="2026-09-02", time="", merchant="B", categoryId="cat-travel", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="3", type="EXPENSE", amount=200.0, date="2026-09-03", time="", merchant="C", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true)
        )

        val foodAggregated = KharchaViewModel.aggregateMonthlySpendingForCategory(txs, "cat-food")
        assertEquals(1, foodAggregated.size)
        assertEquals(700.0, foodAggregated["2026-09"] ?: 0.0, 0.01)

        val travelAggregated = KharchaViewModel.aggregateMonthlySpendingForCategory(txs, "cat-travel")
        assertEquals(1, travelAggregated.size)
        assertEquals(300.0, travelAggregated["2026-09"] ?: 0.0, 0.01)
    }

    @Test
    fun test6_merchantFilterIsAppliedBeforeAggregation() {
        val categories = listOf(
            CategoryEntity(
                id = "cat-food",
                name = "Food & Dining",
                nameHindi = "",
                icon = "🍔",
                colour = "#FF0000",
                isDefault = false,
                isActive = true,
                isIncome = false,
                createdAt = "",
                updatedAt = ""
            )
        )
        val txs = listOf(
            TransactionEntity(id="1", type="EXPENSE", amount=150.0, date="2026-09-01", time="", merchant="Swiggy", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="2", type="EXPENSE", amount=250.0, date="2026-09-05", time="", merchant="Zomato", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="3", type="EXPENSE", amount=350.0, date="2026-09-12", time="", merchant="Swiggy", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true)
        )

        val swiggyAggregated = KharchaViewModel.aggregateMonthlySpendingForMerchant(txs, "Swiggy", categories)
        assertEquals(1, swiggyAggregated.size)
        assertEquals(500.0, swiggyAggregated["2026-09"] ?: 0.0, 0.01)

        val zomatoAggregated = KharchaViewModel.aggregateMonthlySpendingForMerchant(txs, "Zomato", categories)
        assertEquals(1, zomatoAggregated.size)
        assertEquals(250.0, zomatoAggregated["2026-09"] ?: 0.0, 0.01)
    }

    @Test
    fun test7_expenseOffIsExcludedAccordingToExistingRules() {
        val txs = listOf(
            TransactionEntity(id="1", type="EXPENSE", amount=400.0, date="2026-09-01", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            // Expense OFF (isExpense = false)
            TransactionEntity(id="2", type="EXPENSE", amount=600.0, date="2026-09-05", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=false),
            // Internal Transfer
            TransactionEntity(id="3", type="INTERNAL_TRANSFER", amount=1000.0, date="2026-09-10", time="", merchant="Internal Transfer", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isInternalTransfer=true),
            // Income
            TransactionEntity(id="4", type="INCOME", amount=5000.0, date="2026-09-15", time="", merchant="Employer", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=false)
        )

        val aggregated = KharchaViewModel.aggregateMonthlySpendingForCategory(txs, "cat-food")
        assertEquals(1, aggregated.size)
        assertEquals(400.0, aggregated["2026-09"] ?: 0.0, 0.01)
    }

    @Test
    fun test8_selectingMonthFiltersTheExactYearMonth() {
        val txs = listOf(
            TransactionEntity(id="1", type="EXPENSE", amount=100.0, date="2025-09-10", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="2", type="EXPENSE", amount=200.0, date="2026-08-15", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="3", type="EXPENSE", amount=300.0, date="2026-09-01", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="4", type="EXPENSE", amount=400.0, date="28-SEP-26", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="5", type="EXPENSE", amount=500.0, date="2026-10-01", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true)
        )

        val selectedMonth = "2026-09"
        val filtered = txs.filter { KharchaViewModel.extractYearMonthKey(it.date) == selectedMonth }

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.id == "3" })
        assertTrue(filtered.any { it.id == "4" })
        assertFalse(filtered.any { it.id == "1" }) // 2025-09
        assertFalse(filtered.any { it.id == "2" }) // 2026-08
        assertFalse(filtered.any { it.id == "5" }) // 2026-10
    }

    @Test
    fun testAggregationKeyConsistencyWithDetailedDates() {
        // These should all return "2026-09"
        val key1 = KharchaViewModel.extractYearMonthKey("Sep 02")
        val key2 = KharchaViewModel.extractYearMonthKey("Sep 07")
        val key3 = KharchaViewModel.extractYearMonthKey("Sep '26")
        val key4 = KharchaViewModel.extractYearMonthKey("02-Sep-26")
        val key5 = KharchaViewModel.extractYearMonthKey("07-Sep-2026")
        
        System.out.println("Keys: $key1, $key2, $key3, $key4, $key5")
        
        assertEquals("2026-09", key1)
        assertEquals("2026-09", key2)
        assertEquals("2026-09", key3)
        assertEquals("2026-09", key4)
        assertEquals("2026-09", key5)
    }

    @Test
    fun test10_aggregateWithRealDeviceFormats() {
        val txs = listOf(
            TransactionEntity(id="1", type="EXPENSE", amount=500.0, date="Sep 02", time="", merchant="A", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="2", type="EXPENSE", amount=422.0, date="Sep 07", time="", merchant="B", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true),
            TransactionEntity(id="3", type="EXPENSE", amount=150000.0, date="Sep '26", time="", merchant="C", categoryId="cat-food", subcategoryId="", accountId="", paymentMethod="", note="", source="", transactionReference="", isExpense=true)
        )

        val aggregated = KharchaViewModel.aggregateMonthlySpendingForCategory(txs, "cat-food")
        System.out.println("DEBUG AGGREGATED MAP: $aggregated")
        
        assertEquals(1, aggregated.size)
        assertTrue(aggregated.containsKey("2026-09"))
        assertEquals(150922.0, aggregated["2026-09"] ?: 0.0, 0.01)
    }
}
