package com.example

import com.example.data.entity.AccountEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.UserEntity
import com.example.data.firestore.FirestorePaths
import com.example.data.firestore.sanitizeFirestoreText
import com.example.data.firestore.toAccountEntity
import com.example.data.firestore.toCategoryEntity
import com.example.data.firestore.toFirestoreMap
import com.example.data.firestore.toTransactionEntity
import com.example.data.firestore.toUserEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreDataStructureTest {

    @Test
    fun testUserScopedFirestorePathsGeneration() {
        val testUid = "firebase_uid_test_123"

        assertEquals("users/firebase_uid_test_123", FirestorePaths.userDocumentPath(testUid))
        assertEquals("users/firebase_uid_test_123/profile/info", FirestorePaths.profileDocumentPath(testUid))
        assertEquals("users/firebase_uid_test_123/accounts", FirestorePaths.accountsCollectionPath(testUid))
        assertEquals("users/firebase_uid_test_123/accounts/acc_hdfc_1", FirestorePaths.accountDocumentPath(testUid, "acc_hdfc_1"))
        assertEquals("users/firebase_uid_test_123/transactions", FirestorePaths.transactionsCollectionPath(testUid))
        assertEquals("users/firebase_uid_test_123/transactions/tx_999", FirestorePaths.transactionDocumentPath(testUid, "tx_999"))
        assertEquals("users/firebase_uid_test_123/categories", FirestorePaths.categoriesCollectionPath(testUid))
        assertEquals("users/firebase_uid_test_123/categories/cat_food", FirestorePaths.categoryDocumentPath(testUid, "cat_food"))
        assertEquals("users/firebase_uid_test_123/subcategories", FirestorePaths.subcategoriesCollectionPath(testUid))
        assertEquals("users/firebase_uid_test_123/subcategories/sub_dining", FirestorePaths.subcategoryDocumentPath(testUid, "sub_dining"))
        assertEquals("users/firebase_uid_test_123/merchants", FirestorePaths.merchantsCollectionPath(testUid))
        assertEquals("users/firebase_uid_test_123/merchants/swiggy", FirestorePaths.merchantDocumentPath(testUid, "swiggy"))
        assertEquals("users/firebase_uid_test_123/settings", FirestorePaths.settingsCollectionPath(testUid))
        assertEquals("users/firebase_uid_test_123/settings/app_preferences", FirestorePaths.settingsDocumentPath(testUid))
    }

    @Test
    fun testUserEntityBidirectionalMapping() {
        val originalUser = UserEntity(
            id = "uid_456",
            phoneNumber = "+919876543210",
            name = "Ankit Sharma",
            email = "ankit@example.com",
            createdAt = "2026-09-29T00:00:00Z",
            lastLoginAt = "2026-09-29T00:00:00Z",
            isCloudSynced = true
        )

        val firestoreMap = originalUser.toFirestoreMap()
        assertEquals("uid_456", firestoreMap["id"])
        assertEquals("+919876543210", firestoreMap["phoneNumber"])
        assertEquals("Ankit Sharma", firestoreMap["name"])

        val mappedBack = firestoreMap.toUserEntity("fallback")
        assertEquals(originalUser.id, mappedBack.id)
        assertEquals(originalUser.name, mappedBack.name)
        assertEquals(originalUser.email, mappedBack.email)
    }

    @Test
    fun testTransactionEntityBidirectionalMapping() {
        val originalTx = TransactionEntity(
            id = "tx_789",
            type = "EXPENSE",
            amount = 350.0,
            date = "2026-09-29",
            time = "14:30",
            merchant = "Starbucks",
            categoryId = "cat-food",
            subcategoryId = "sub-coffee",
            accountId = "acc-sbi",
            paymentMethod = "UPI",
            note = "Coffee with team",
            source = "MANUAL",
            transactionReference = "TXN12345",
            originalReference = "",
            last4Digits = "1234",
            createdAt = "2026-09-29T14:30:00Z",
            updatedAt = "2026-09-29T14:30:00Z",
            isExpense = true,
            userId = "uid_test"
        )

        val firestoreMap = originalTx.toFirestoreMap("uid_test")
        assertEquals("tx_789", firestoreMap["id"])
        assertEquals(350.0, firestoreMap["amount"])
        assertEquals("EXPENSE", firestoreMap["type"])
        assertEquals(true, firestoreMap["isExpense"])

        val mappedBack = firestoreMap.toTransactionEntity("fallback")
        assertEquals(originalTx.id, mappedBack.id)
        assertEquals(originalTx.amount, mappedBack.amount, 0.001)
        assertEquals(originalTx.type, mappedBack.type)
        assertEquals(originalTx.isExpense, mappedBack.isExpense)
        assertEquals(originalTx.merchant, mappedBack.merchant)
    }

    @Test
    fun testAccountAndCategoryMapping() {
        val account = AccountEntity(
            id = "acc_1",
            name = "Salary Account",
            type = "Bank Account",
            bankName = "HDFC Bank",
            last4Digits = "5678",
            userId = "uid_test"
        )
        val accMap = account.toFirestoreMap("uid_test")
        val mappedAcc = accMap.toAccountEntity("acc_1")
        assertEquals("Salary Account", mappedAcc.name)
        assertEquals("HDFC Bank", mappedAcc.bankName)

        val category = CategoryEntity(
            id = "cat_1",
            name = "Groceries",
            nameHindi = "किराना",
            icon = "🛒",
            colour = "#10B981",
            createdAt = "",
            updatedAt = "",
            userId = "uid_test"
        )
        val catMap = category.toFirestoreMap("uid_test")
        val mappedCat = catMap.toCategoryEntity("cat_1")
        assertEquals("Groceries", mappedCat.name)
        assertEquals("🛒", mappedCat.icon)
    }

    @Test
    fun testSensitiveTextSanitization() {
        val sensitiveNote = "Payment OTP 123456 for CVV 999 and password secret"
        val sanitized = sanitizeFirestoreText(sensitiveNote)
        assertFalse("Raw secrets/OTP must not be present in sanitized text", sanitized.contains("123456") && sanitized.contains("999"))
        assertTrue("Sanitization must replace sensitive tokens", sanitized.contains("[REDACTED]"))
    }
}
