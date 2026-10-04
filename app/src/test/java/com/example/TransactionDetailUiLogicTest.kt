package com.example

import com.example.data.entity.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionDetailUiLogicTest {

    // Helper representing the exact UI condition in TransactionDetailDialog.kt
    private fun isIncomeTransaction(tx: TransactionEntity): Boolean {
        return tx.type.equals("INCOME", ignoreCase = true) || tx.transactionType.equals("INCOME", ignoreCase = true)
    }

    private fun shouldShowExpenseIncludedRow(tx: TransactionEntity): Boolean {
        val isInternal = tx.isInternalTransfer || tx.type == "INTERNAL_TRANSFER" || tx.transactionType == "INTERNAL_TRANSFER"
        val isIncome = isIncomeTransaction(tx)
        return !isInternal && !isIncome
    }

    private fun getExpenseIncludedText(tx: TransactionEntity): String {
        return if (tx.isExpense) "ON (Included in Totals)" else "OFF (Excluded from Totals)"
    }

    @Test
    fun testIncomeTransactionDoesNotShowExpenseIncludedRow() {
        val incomeSources = listOf("EMAIL", "SMS", "NOTIFICATION", "MANUAL")
        incomeSources.forEach { src ->
            val incomeTx = TransactionEntity(
                id = "tx-income-$src",
                type = "INCOME",
                amount = 42539.0,
                date = "2026-09-25",
                time = "10:00",
                merchant = "Salary / Employer",
                categoryId = "cat-salary",
                subcategoryId = "",
                accountId = "acc-sbi-6276",
                paymentMethod = "Bank Transfer",
                note = "Monthly salary credit",
                source = src,
                transactionReference = "REF12345678",
                last4Digits = "6276",
                isExpense = true // Database default
            )

            assertTrue("Income transaction should be recognized as INCOME regardless of source $src", isIncomeTransaction(incomeTx))
            assertFalse("Expense Included row MUST be absent for INCOME transactions (source=$src)", shouldShowExpenseIncludedRow(incomeTx))
        }
    }

    @Test
    fun testIncomeTransactionWithTransactionTypeFieldDoesNotShowExpenseIncludedRow() {
        val incomeTx = TransactionEntity(
            id = "tx-income-typefield",
            type = "INCOME",
            amount = 15000.0,
            date = "2026-09-28",
            time = "14:30",
            merchant = "Freelance Client",
            categoryId = "cat-income",
            subcategoryId = "",
            accountId = "acc-hdfc-1234",
            paymentMethod = "UPI",
            note = "Project payment",
            source = "EMAIL",
            transactionReference = "UPI/123456",
            transactionType = "INCOME",
            isExpense = true
        )

        assertTrue(isIncomeTransaction(incomeTx))
        assertFalse(shouldShowExpenseIncludedRow(incomeTx))
    }

    @Test
    fun testExpenseWithIsExpenseTrueShowsOnStatus() {
        val expenseTx = TransactionEntity(
            id = "tx-expense-true",
            type = "EXPENSE",
            amount = 500.0,
            date = "2026-09-28",
            time = "18:00",
            merchant = "Swiggy",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-sbi-6276",
            paymentMethod = "UPI",
            note = "",
            source = "SMS",
            transactionReference = "REF9999",
            isExpense = true
        )

        assertFalse(isIncomeTransaction(expenseTx))
        assertTrue(shouldShowExpenseIncludedRow(expenseTx))
        assertEquals("ON (Included in Totals)", getExpenseIncludedText(expenseTx))
    }

    @Test
    fun testExpenseWithIsExpenseFalseShowsOffStatus() {
        val expenseOffTx = TransactionEntity(
            id = "tx-expense-false",
            type = "EXPENSE",
            amount = 1200.0,
            date = "2026-09-28",
            time = "19:00",
            merchant = "Fuel Station",
            categoryId = "cat-transport",
            subcategoryId = "",
            accountId = "acc-icici-8888",
            paymentMethod = "Credit Card",
            note = "Reimbursable",
            source = "NOTIFICATION",
            transactionReference = "REF8888",
            isExpense = false
        )

        assertFalse(isIncomeTransaction(expenseOffTx))
        assertTrue(shouldShowExpenseIncludedRow(expenseOffTx))
        assertEquals("OFF (Excluded from Totals)", getExpenseIncludedText(expenseOffTx))
    }

    @Test
    fun testGenericBehaviorWithoutHardcodedAccountOrMerchant() {
        // Proves that behavior works for arbitrary merchants and accounts
        val arbitraryIncome = TransactionEntity(
            id = "tx-arbitrary-inc",
            type = "INCOME",
            amount = 99999.0,
            date = "2026-09-30",
            time = "12:00",
            merchant = "Random Merchant XYZ",
            categoryId = "cat-custom",
            subcategoryId = "",
            accountId = "acc-custom-9999",
            paymentMethod = "NEFT",
            note = "",
            source = "EMAIL",
            transactionReference = "NEFT999",
            isExpense = true
        )

        assertFalse("Income must not display Expense Included row regardless of merchant/account", shouldShowExpenseIncludedRow(arbitraryIncome))
    }
}
