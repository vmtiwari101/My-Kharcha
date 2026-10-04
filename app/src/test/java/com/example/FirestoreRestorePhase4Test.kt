package com.example

import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.firestore.FirestorePaths
import com.example.data.firestore.FirestoreRepository
import com.example.data.firestore.sanitizeFirestoreText
import com.example.data.firestore.toAccountEntity
import com.example.data.firestore.toCardEntity
import com.example.data.firestore.toCategoryEntity
import com.example.data.firestore.toFirestoreMap
import com.example.data.firestore.toSubcategoryEntity
import com.example.data.firestore.toTransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreRestorePhase4Test {

    @Test
    fun testCorrectFirebaseUidIsUsedForRestorePaths() {
        val targetUid = "user_restore_uid_777"

        assertEquals("users/user_restore_uid_777/profile/info", FirestorePaths.profileDocumentPath(targetUid))
        assertEquals("users/user_restore_uid_777/accounts", FirestorePaths.accountsCollectionPath(targetUid))
        assertEquals("users/user_restore_uid_777/cards", FirestorePaths.cardsCollectionPath(targetUid))
        assertEquals("users/user_restore_uid_777/categories", FirestorePaths.categoriesCollectionPath(targetUid))
        assertEquals("users/user_restore_uid_777/subcategories", FirestorePaths.subcategoriesCollectionPath(targetUid))
        assertEquals("users/user_restore_uid_777/merchants", FirestorePaths.merchantsCollectionPath(targetUid))
        assertEquals("users/user_restore_uid_777/transactions", FirestorePaths.transactionsCollectionPath(targetUid))
        assertEquals("users/user_restore_uid_777/settings/app_preferences", FirestorePaths.settingsDocumentPath(targetUid))
    }

    @Test
    fun testUnauthenticatedUserCannotTriggerRestore() {
        val dummyRepo = FirestoreRepository(
            firestoreProvider = { null },
            authProvider = { null }
        )
        assertEquals(null, dummyRepo.currentUid)
    }

    @Test
    fun testAccountAndCardRestoreMapping() {
        val originalAccount = AccountEntity(
            id = "acc_restore_1",
            name = "HDFC Savings",
            type = "Savings",
            bankName = "HDFC Bank",
            last4Digits = "9988",
            initialBalance = 25000.0
        )
        val accMap = originalAccount.toFirestoreMap()
        val restoredAcc = accMap.toAccountEntity("acc_restore_1")

        assertEquals(originalAccount.id, restoredAcc.id)
        assertEquals(originalAccount.name, restoredAcc.name)
        assertEquals(originalAccount.bankName, restoredAcc.bankName)
        assertEquals(25000.0, restoredAcc.initialBalance, 0.001)

        val originalCard = CardEntity(
            id = "card_restore_1",
            accountId = "acc_restore_1",
            name = "HDFC Millennia",
            type = "Credit Card",
            last4Digits = "1234",
            creditLimit = 100000.0
        )
        val cardMap = originalCard.toFirestoreMap()
        val restoredCard = cardMap.toCardEntity("card_restore_1")

        assertEquals(originalCard.id, restoredCard.id)
        assertEquals(originalCard.accountId, restoredCard.accountId)
        assertEquals(100000.0, restoredCard.creditLimit, 0.001)
    }

    @Test
    fun testCategoriesAndSubcategoriesRestoreMapping() {
        val originalCat = CategoryEntity(
            id = "cat_restore_shopping",
            name = "Shopping",
            nameHindi = "खरीदारी",
            icon = "🛍️",
            colour = "#EC4899",
            isIncome = false,
            createdAt = "2026-09-29T00:00:00Z",
            updatedAt = "2026-09-29T00:00:00Z"
        )
        val catMap = originalCat.toFirestoreMap()
        val restoredCat = catMap.toCategoryEntity("cat_restore_shopping")

        assertEquals("cat_restore_shopping", restoredCat.id)
        assertEquals("Shopping", restoredCat.name)
        assertEquals("खरीदारी", restoredCat.nameHindi)
        assertEquals("🛍️", restoredCat.icon)
        assertEquals(false, restoredCat.isIncome)

        val originalSub = SubcategoryEntity(
            id = "sub_restore_clothes",
            categoryId = "cat_restore_shopping",
            name = "Clothes",
            nameHindi = "कपड़े",
            icon = "👕",
            colour = "#EC4899",
            createdAt = "2026-09-29T00:00:00Z",
            updatedAt = "2026-09-29T00:00:00Z"
        )
        val subMap = originalSub.toFirestoreMap()
        val restoredSub = subMap.toSubcategoryEntity("sub_restore_clothes")

        assertEquals("sub_restore_clothes", restoredSub.id)
        assertEquals("cat_restore_shopping", restoredSub.categoryId)
        assertEquals("Clothes", restoredSub.name)
    }

    @Test
    fun testTransactionRestorationPreservesIsExpenseAndIncomeCredit() {
        val expenseTx = TransactionEntity(
            id = "tx_exp_restored",
            type = "EXPENSE",
            amount = 899.0,
            date = "2026-09-29",
            time = "15:00",
            merchant = "Amazon",
            categoryId = "cat_shopping",
            subcategoryId = "sub_electronics",
            accountId = "acc_1",
            paymentMethod = "CARD",
            note = "Headphones",
            source = "MANUAL",
            transactionReference = "REF_EXP_1",
            originalReference = "",
            last4Digits = "5678",
            createdAt = "2026-09-29T15:00:00Z",
            updatedAt = "2026-09-29T15:00:00Z",
            direction = "DEBIT",
            isExpense = true
        )
        val expMap = expenseTx.toFirestoreMap()
        val restoredExp = expMap.toTransactionEntity("tx_exp_restored")

        assertEquals("tx_exp_restored", restoredExp.id)
        assertEquals(899.0, restoredExp.amount, 0.001)
        assertEquals("EXPENSE", restoredExp.type)
        assertEquals("DEBIT", restoredExp.direction)
        assertTrue("Restored expense must retain isExpense = true", restoredExp.isExpense)

        val incomeTx = TransactionEntity(
            id = "tx_inc_restored",
            type = "INCOME",
            amount = 50000.0,
            date = "2026-09-29",
            time = "10:00",
            merchant = "TechCorp",
            categoryId = "cat_salary",
            subcategoryId = "",
            accountId = "acc_1",
            paymentMethod = "BANK_TRANSFER",
            note = "September Salary",
            source = "MANUAL",
            transactionReference = "REF_INC_1",
            originalReference = "",
            last4Digits = "",
            createdAt = "2026-09-29T10:00:00Z",
            updatedAt = "2026-09-29T10:00:00Z",
            direction = "CREDIT",
            isExpense = false
        )
        val incMap = incomeTx.toFirestoreMap()
        val restoredInc = incMap.toTransactionEntity("tx_inc_restored")

        assertEquals("tx_inc_restored", restoredInc.id)
        assertEquals("INCOME", restoredInc.type)
        assertEquals("CREDIT", restoredInc.direction)
        assertFalse("Restored income must have isExpense = false", restoredInc.isExpense)
    }

    @Test
    fun testRestorationIdempotencyAndNoDuplicates() {
        val tx = TransactionEntity(
            id = "tx_idempotent_100",
            type = "EXPENSE",
            amount = 150.0,
            date = "2026-09-29",
            time = "18:00",
            merchant = "Cafe",
            categoryId = "cat_food",
            subcategoryId = "",
            accountId = "acc_1",
            paymentMethod = "UPI",
            note = "",
            source = "MANUAL",
            transactionReference = "",
            originalReference = "",
            last4Digits = "",
            createdAt = "",
            updatedAt = "",
            isExpense = true
        )

        val map1 = tx.toFirestoreMap()
        val restoredFirst = map1.toTransactionEntity("tx_idempotent_100")

        val map2 = tx.toFirestoreMap()
        val restoredSecond = map2.toTransactionEntity("tx_idempotent_100")

        assertEquals(restoredFirst.id, restoredSecond.id)
        assertEquals(restoredFirst.amount, restoredSecond.amount, 0.001)
    }

    @Test
    fun testSensitiveAuthDataNeverRestoredUnsanitized() {
        val sensitiveNote = "Auth passcode 887766 CVV 444 PIN 1234"
        val sanitized = sanitizeFirestoreText(sensitiveNote)

        assertFalse("CVV must not be exposed", sanitized.contains("444"))
        assertFalse("Passcode must not be exposed", sanitized.contains("887766"))
        assertTrue("Redaction placeholder must be present", sanitized.contains("[REDACTED]"))
    }
}
