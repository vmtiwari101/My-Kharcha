package com.example.utils

import androidx.test.core.app.ApplicationProvider
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class IdentitySafetyTest {

    private lateinit var accounts: List<AccountEntity>
    private lateinit var cards: List<CardEntity>

    @Before
    fun setup() {
        val sbiAcc = AccountEntity(
            id = "acc-sbi",
            name = "SBI Credit Card •••• 2663",
            type = "Credit Card",
            bankName = "SBI",
            last4Digits = "2663",
            isOwnedByMe = true
        )
        val auAcc = AccountEntity(
            id = "acc-au",
            name = "AU Bank Card •••• 9546",
            type = "Credit Card",
            bankName = "AU Bank",
            last4Digits = "9546",
            isOwnedByMe = true
        )
        val hdfcAcc = AccountEntity(
            id = "acc-hdfc",
            name = "HDFC Credit Card •••• 1234",
            type = "Credit Card",
            bankName = "HDFC",
            last4Digits = "1234",
            isOwnedByMe = true
        )
        val sbiAcc2 = AccountEntity(
            id = "acc-sbi-1234",
            name = "SBI Credit Card •••• 1234",
            type = "Credit Card",
            bankName = "SBI",
            last4Digits = "1234",
            isOwnedByMe = true
        )

        accounts = listOf(sbiAcc, auAcc, hdfcAcc, sbiAcc2)
        cards = listOf(
            CardEntity(id = "card-sbi", accountId = "acc-sbi", name = "SBI Card", type = "Credit Card", last4Digits = "2663"),
            CardEntity(id = "card-au", accountId = "acc-au", name = "AU Card", type = "Credit Card", last4Digits = "9546"),
            CardEntity(id = "card-hdfc", accountId = "acc-hdfc", name = "HDFC Card", type = "Credit Card", last4Digits = "1234"),
            CardEntity(id = "card-sbi-1234", accountId = "acc-sbi-1234", name = "SBI Card", type = "Credit Card", last4Digits = "1234")
        )
    }

    @Test
    fun testA_Sbi2663_PhonePe_PoweredByAxis() {
        val text = "Paid Rs. 100 to Merchant. Debited from XXXX2663. Powered by AXIS BANK."
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "2663",
            rawText = text,
            source = "PhonePe"
        )
        val result = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        
        assertEquals("acc-sbi", result.accountId)
        assertEquals("card-sbi", result.cardId)
        assertEquals("2663", result.last4Digits)
        assertFalse("Should not need review", result.needsReview)
        assertTrue("Confidence should be high", result.confidence >= 95)
        assertEquals("UPI", result.paymentMethod)
    }

    @Test
    fun testB_Au9546_PhonePe() {
        val text = "Transaction of Rs. 500 successful at Shop. Card ending in 9546 used via PhonePe."
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "9546",
            rawText = text,
            source = "PhonePe"
        )
        val result = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        
        assertEquals("acc-au", result.accountId)
        assertEquals("card-au", result.cardId)
        assertEquals("9546", result.last4Digits)
        assertFalse("Should not need review", result.needsReview)
        assertEquals("UPI", result.paymentMethod)
    }

    @Test
    fun testC_AmbiguousLast4() {
        val text = "Paid Rs. 200. Account ending in 1234."
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "1234",
            rawText = text,
            source = "SMS"
        )
        val result = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        
        assertTrue("Ambiguous match should need review", result.needsReview)
        assertEquals("", result.accountId)
        assertEquals("1234", result.last4Digits)
    }

    @Test
    fun testD_UnknownBank_Exact4() {
        val text = "Debited from XXXX7788 at Store."
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "7788",
            rawText = text,
            source = "SMS"
        )
        val result = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        
        assertTrue("Unknown account should need review", result.needsReview)
        assertEquals("", result.accountId)
        assertEquals("7788", result.last4Digits)
    }

    @Test
    fun testE_PartialLast4_LowConfidence() {
        val text = "SBI Card ending in 63 debited."
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "63",
            rawText = text,
            source = "SMS"
        )
        val result = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        
        assertTrue("Partial last4 should need review", result.needsReview)
        assertTrue("Confidence should be low", result.confidence < 90)
    }

    @Test
    fun testF_NoLast4_BankOnly() {
        val text = "Transaction successful on your SBI card."
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "",
            rawText = text,
            source = "SMS"
        )
        val result = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        
        assertTrue("No last4 should need review", result.needsReview)
        assertEquals("", result.accountId)
    }

    @Test
    fun testG_SmsNotificationEmail_DuplicateMerge() {
        val date = "2026-09-26"
        val time = "10:30"
        val amount = 500.0

        val notifTx = TransactionEntity(
            id = "tx-notif",
            amount = amount,
            date = date,
            time = time,
            merchant = "Swiggy",
            categoryId = "cat-food",
            subcategoryId = "sub-swiggy",
            note = "Food order",
            type = "EXPENSE",
            direction = "DEBIT",
            source = "NOTIFICATION",
            paymentMethod = "UPI",
            accountId = "",
            cardId = null,
            last4Digits = "",
            transactionReference = "REF998811",
            originalReference = "REF998811",
            needsReview = true
        )

        val smsTx = TransactionEntity(
            id = "tx-sms",
            amount = amount,
            date = date,
            time = time,
            merchant = "Swiggy",
            categoryId = "cat-food",
            subcategoryId = "sub-swiggy",
            note = "Food order",
            type = "EXPENSE",
            direction = "DEBIT",
            source = "SMS",
            paymentMethod = "UPI",
            accountId = "acc-sbi",
            cardId = "card-sbi",
            last4Digits = "2663",
            transactionReference = "REF998811",
            originalReference = "REF998811",
            needsReview = false
        )

        val emailTx = TransactionEntity(
            id = "tx-email",
            amount = amount,
            date = date,
            time = time,
            merchant = "Swiggy",
            categoryId = "cat-food",
            subcategoryId = "sub-swiggy",
            note = "Food order",
            type = "EXPENSE",
            direction = "DEBIT",
            source = "EMAIL",
            paymentMethod = "UPI",
            accountId = "",
            cardId = null,
            last4Digits = "2663",
            transactionReference = "REF998811",
            originalReference = "REF998811",
            needsReview = false
        )

        // Verify duplicate detection between SMS, Notification, and Email
        assertTrue("SMS should be duplicate of Notification", TransactionIngestionEngine.isDuplicateTransaction(smsTx, notifTx))
        assertTrue("Email should be duplicate of SMS", TransactionIngestionEngine.isDuplicateTransaction(emailTx, smsTx))
        assertTrue("Email should be duplicate of Notification", TransactionIngestionEngine.isDuplicateTransaction(emailTx, notifTx))

        // Merge weaker notification with stronger SMS: stronger SMS identity (SBI Card) overrides empty notif identity
        val merged1 = TransactionIngestionEngine.mergeTransactionMetadata(notifTx, smsTx, accounts, cards)
        assertEquals("acc-sbi", merged1.accountId)
        assertEquals("card-sbi", merged1.cardId)
        assertEquals("2663", merged1.last4Digits)
        assertEquals("UPI", merged1.paymentMethod)
        assertFalse("Stronger identity clears needsReview", merged1.needsReview)

        // Merge with email
        val merged2 = TransactionIngestionEngine.mergeTransactionMetadata(merged1, emailTx, accounts, cards)
        assertEquals("acc-sbi", merged2.accountId)
        assertEquals("card-sbi", merged2.cardId)
        assertEquals("2663", merged2.last4Digits)
        assertEquals("UPI", merged2.paymentMethod)
        assertFalse(merged2.needsReview)
    }

    @Test
    fun testH_PoweredByAxis_WithSbiActual() {
        val text = "SBI Card XXXX2663 debited. Powered by Axis Bank UPI."
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "2663",
            rawText = text,
            source = "SMS"
        )
        val result = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        
        assertEquals("acc-sbi", result.accountId)
        assertFalse("Should be resolved correctly to SBI", result.needsReview)
        // Ensure Axis is NOT the bank candidate used for resolution (it was sanitized out)
        val sanitized = TransactionIdentityResolver.sanitizeTextForBankExtraction(text)
        assertFalse("Axis should be removed from bank candidates", sanitized.lowercase().contains("axis bank"))
    }
}
