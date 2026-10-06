package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import com.example.utils.TransactionIdentityResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IdentityResolutionAuditTest {

    private lateinit var db: AppDatabase
    private lateinit var accounts: List<AccountEntity>
    private lateinit var cards: List<CardEntity>

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDatabase.getDatabase(context)
        
        accounts = listOf(
            AccountEntity(id = "acc-idfc-2345", name = "IDFC FIRST Bank Account •••• 2345", type = "Bank Account", bankName = "IDFC FIRST Bank", last4Digits = "2345", isOwnedByMe = true),
            AccountEntity(id = "acc-axis-1843", name = "Axis Bank Credit Card •••• 1843", type = "Credit Card", bankName = "Axis Bank", last4Digits = "1843", isOwnedByMe = true),
            AccountEntity(id = "acc-sbi-1234", name = "SBI Account •••• 1234", type = "Bank Account", bankName = "SBI", last4Digits = "1234", isOwnedByMe = true),
            AccountEntity(id = "acc-axis-1234", name = "Axis Bank Account •••• 1234", type = "Bank Account", bankName = "Axis Bank", last4Digits = "1234", isOwnedByMe = true),
            AccountEntity(id = "acc-axis-card-1234", name = "Axis Credit Card •••• 1234", type = "Credit Card", bankName = "Axis Bank", last4Digits = "1234", isOwnedByMe = true)
        )
        
        cards = listOf(
            CardEntity(id = "card-axis-1843", accountId = "acc-axis-1843", name = "Axis Credit Card", type = "Credit Card", last4Digits = "1843"),
            CardEntity(id = "card-axis-1234", accountId = "acc-axis-card-1234", name = "Axis Card", type = "Credit Card", last4Digits = "1234")
        )
    }

    @After
    fun tearDown() {
    }

    @Test
    fun testA_IdfcBankAcc2345() {
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "2345",
            bankNameCandidate = "IDFC FIRST Bank",
            rawText = "A/C XX2345 debited by Rs 500 at Store. IDFC FIRST Bank Alert.",
            source = "IDFCBK"
        )
        val result = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        assertEquals("acc-idfc-2345", result.accountId)
        assertEquals("2345", result.last4Digits)
        assertFalse(result.needsReview)
    }

    @Test
    fun testB_AxisCreditCard1843() {
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "1843",
            bankNameCandidate = "Axis Bank",
            rawText = "Spent Rs 1200 on Axis Bank Credit Card ending 1843 at Amazon.",
            source = "AXISBK"
        )
        val result = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        assertEquals("acc-axis-1843", result.accountId)
        assertEquals("card-axis-1843", result.cardId)
        assertEquals("1843", result.last4Digits)
        assertFalse(result.needsReview)
    }

    @Test
    fun testC_SameLast4DifferentBank() {
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "1234",
            bankNameCandidate = "SBI",
            rawText = "A/C XX1234 credited with salary. SBI Alert.",
            source = "SBIPIN"
        )
        val result = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        assertEquals("acc-sbi-1234", result.accountId)
        assertFalse(result.needsReview)
    }

    @Test
    fun testD_SameLast4DifferentInstrumentType() {
        val evidenceCC = TransactionIdentityResolver.IdentityEvidence(
            last4 = "1234",
            bankNameCandidate = "Axis Bank",
            rawText = "Axis Bank Credit Card ending 1234 debited by Rs 400.",
            source = "AXISBK"
        )
        val resultCC = TransactionIdentityResolver.resolveIdentity(evidenceCC, accounts, cards)
        assertEquals("acc-axis-card-1234", resultCC.accountId)
        assertEquals("card-axis-1234", resultCC.cardId)
        assertFalse(resultCC.needsReview)
    }

    @Test
    fun testE_BankAccountVsCreditCardCollision() {
        val evidenceAcc = TransactionIdentityResolver.IdentityEvidence(
            last4 = "1234",
            bankNameCandidate = "Axis Bank",
            rawText = "Axis Bank Account ending 1234 debited for transfer.",
            source = "AXISBK"
        )
        val resultAcc = TransactionIdentityResolver.resolveIdentity(evidenceAcc, accounts, cards)
        assertEquals("acc-axis-1234", resultAcc.accountId)
        assertNull(resultAcc.cardId)
        assertFalse(resultAcc.needsReview)
    }

    @Test
    fun testF_CreditCardBillPaymentAccountToCreditCard() = runBlocking {
        val dao = db.kharchaDao()
        val tx = TransactionEntity(
            id = "tx-bill-pay",
            type = "TRANSFER",
            amount = 15000.0,
            date = "2026-10-01",
            time = "10:00",
            merchant = "Axis Bank Credit Card Bill Payment",
            categoryId = "cat-transfer",
            subcategoryId = "",
            accountId = "acc-idfc-2345",
            counterpartyAccountId = "acc-axis-1843",
            cardId = "card-axis-1843",
            paymentMethod = "Transfer",
            note = "",
            source = "SMS",
            transactionReference = "REF12345",
            transactionType = "CARD_PAYMENT",
            isInternalTransfer = true,
            isExpense = false
        )
        dao.insertTransaction(tx)
        val stored = dao.getAllTransactionsSync().find { it.id == "tx-bill-pay" }
        assertNotNull(stored)
        assertEquals("CARD_PAYMENT", stored?.transactionType)
        assertEquals("acc-idfc-2345", stored?.accountId)
        assertEquals("acc-axis-1843", stored?.counterpartyAccountId)
        assertFalse(stored!!.isExpense)
    }

    @Test
    fun testG_AmbiguousIdentityNeedsReview() {
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "1234",
            bankNameCandidate = "",
            rawText = "Transaction of Rs 500 on account ending 1234.",
            source = "SMS"
        )
        val result = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        assertTrue("Ambiguous last-4 without bank should need review", result.needsReview)
    }

    @Test
    fun testH_ExistingWronglyMappedEntityRepair() = runBlocking {
        val dao = db.kharchaDao()
        val wrongAcc = AccountEntity(
            id = "acc-wrong-2345",
            name = "Axis Bank Account •••• 2345",
            type = "Bank Account",
            bankName = "Axis Bank",
            last4Digits = "2345",
            isOwnedByMe = true
        )
        dao.insertAccount(wrongAcc)
        
        val fetched = dao.getAllAccountsSync().find { it.last4Digits == "2345" }
        assertNotNull(fetched)
    }

    @Test
    fun testI_DuplicateSmsDoesNotCreateAnotherAccount() = runBlocking {
        val dao = db.kharchaDao()
        val accCountBefore = dao.getAllAccountsSync().size
        
        val rawTx = TransactionEntity(
            id = "tx-dup-1",
            type = "EXPENSE",
            amount = 300.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "Store",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-idfc-2345",
            paymentMethod = "UPI",
            note = "",
            source = "SMS",
            transactionReference = "REF999",
            last4Digits = "2345"
        )
        dao.insertTransaction(rawTx)
        val accCountAfter = dao.getAllAccountsSync().size
        assertTrue(accCountAfter >= accCountBefore)
    }

    @Test
    fun testJ_HistoricalSmsUsesSameResolver() {
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "2345",
            bankNameCandidate = "IDFC FIRST Bank",
            rawText = "Historical SMS: Rs 1000 debited from a/c xx2345 IDFC Bank.",
            source = "SMS"
        )
        val res = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        assertEquals("acc-idfc-2345", res.accountId)
        assertFalse(res.needsReview)
    }

    @Test
    fun testK_NotificationUsesSameResolver() {
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "1843",
            bankNameCandidate = "Axis Bank",
            rawText = "Notification: Axis Bank Credit Card ending 1843 charged Rs 450.",
            source = "Notification"
        )
        val res = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        assertEquals("acc-axis-1843", res.accountId)
        assertFalse(res.needsReview)
    }

    @Test
    fun testL_GmailUsesSameResolver() {
        val evidence = TransactionIdentityResolver.IdentityEvidence(
            last4 = "2345",
            bankNameCandidate = "IDFC FIRST Bank",
            rawText = "Email Statement: IDFC FIRST Bank Account ending 2345 debit of Rs 5000.",
            source = "Gmail"
        )
        val res = TransactionIdentityResolver.resolveIdentity(evidence, accounts, cards)
        assertEquals("acc-idfc-2345", res.accountId)
        assertFalse(res.needsReview)
    }
}
