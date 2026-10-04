package com.example

import com.example.data.entity.AccountEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.firestore.FirestorePaths
import com.example.data.firestore.sanitizeFirestoreText
import com.example.data.firestore.toFirestoreMap
import com.example.data.firestore.toTransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreSyncPhase3Test {

    @Test
    fun testFirebaseUidScoping() {
        val uid1 = "user_alpha_123"
        val uid2 = "user_beta_456"
        val txId = "tx_987"

        val pathUser1 = FirestorePaths.transactionDocumentPath(uid1, txId)
        val pathUser2 = FirestorePaths.transactionDocumentPath(uid2, txId)

        assertEquals("users/user_alpha_123/transactions/tx_987", pathUser1)
        assertEquals("users/user_beta_456/transactions/tx_987", pathUser2)
        assertFalse("Paths for different users must be distinct", pathUser1 == pathUser2)
    }

    @Test
    fun testDeterministicDocumentIDsAndDuplicateProtection() {
        val tx = TransactionEntity(
            id = "tx_deterministic_555",
            type = "EXPENSE",
            amount = 1200.0,
            date = "2026-09-29",
            time = "10:00",
            merchant = "D-Mart",
            categoryId = "cat_groceries",
            subcategoryId = "sub_staples",
            accountId = "acc_bank_sbi",
            paymentMethod = "UPI",
            note = "Monthly supplies",
            source = "MANUAL",
            transactionReference = "REF9999",
            originalReference = "",
            last4Digits = "4321",
            createdAt = "2026-09-29T10:00:00Z",
            updatedAt = "2026-09-29T10:00:00Z",
            isExpense = true
        )

        val map1 = tx.toFirestoreMap()
        val path1 = FirestorePaths.transactionDocumentPath("uid_test", tx.id)

        val reSyncedTx = tx.copy(amount = 1250.0, updatedAt = "2026-09-29T10:05:00Z")
        val map2 = reSyncedTx.toFirestoreMap()
        val path2 = FirestorePaths.transactionDocumentPath("uid_test", reSyncedTx.id)

        // Document ID remains identical for same local record ID
        assertEquals("users/uid_test/transactions/tx_deterministic_555", path1)
        assertEquals(path1, path2)
        assertEquals("tx_deterministic_555", map1["id"])
        assertEquals("tx_deterministic_555", map2["id"])
    }

    @Test
    fun testExpenseIsExpensePreservation() {
        val expenseTx = TransactionEntity(
            id = "tx_expense_1",
            type = "EXPENSE",
            amount = 500.0,
            date = "2026-09-29",
            time = "12:00",
            merchant = "Restaurant",
            categoryId = "cat_food",
            subcategoryId = "",
            accountId = "acc_1",
            paymentMethod = "CARD",
            note = "",
            source = "MANUAL",
            transactionReference = "",
            originalReference = "",
            last4Digits = "",
            createdAt = "",
            updatedAt = "",
            isExpense = true
        )

        val map = expenseTx.toFirestoreMap()
        assertEquals(true, map["isExpense"])
        assertEquals("EXPENSE", map["type"])

        val restored = map.toTransactionEntity("tx_expense_1")
        assertTrue("Expense transaction must retain isExpense = true", restored.isExpense)
        assertEquals("EXPENSE", restored.type)
    }

    @Test
    fun testIncomePreservedAsIncomeCredit() {
        val incomeTx = TransactionEntity(
            id = "tx_income_1",
            type = "INCOME",
            amount = 75000.0,
            date = "2026-09-29",
            time = "09:00",
            merchant = "Employer Corp",
            categoryId = "cat_salary",
            subcategoryId = "",
            accountId = "acc_hdfc",
            paymentMethod = "BANK_TRANSFER",
            note = "Monthly salary",
            source = "MANUAL",
            transactionReference = "SAL202609",
            originalReference = "",
            last4Digits = "",
            createdAt = "",
            updatedAt = "",
            direction = "CREDIT",
            isExpense = false
        )

        val map = incomeTx.toFirestoreMap()
        assertEquals("INCOME", map["type"])
        assertEquals("CREDIT", map["direction"])
        assertEquals(false, map["isExpense"])

        val restored = map.toTransactionEntity("tx_income_1")
        assertEquals("INCOME", restored.type)
        assertEquals("CREDIT", restored.direction)
        assertFalse("Income transaction must have isExpense = false", restored.isExpense)
    }

    @Test
    fun testSensitiveDataNotMappedToFirestore() {
        val rawMessageWithOTP = "Your OTP is 987654 for transaction CVV 123 with password mysecret123"
        val sanitized = sanitizeFirestoreText(rawMessageWithOTP)

        assertFalse("Raw OTP must be redacted", sanitized.contains("987654"))
        assertFalse("Raw CVV must be redacted", sanitized.contains("123"))
        assertFalse("Raw password secret must be redacted", sanitized.contains("mysecret123"))
        assertTrue("Sanitized text must contain redaction placeholder", sanitized.contains("[REDACTED]"))
    }
}
