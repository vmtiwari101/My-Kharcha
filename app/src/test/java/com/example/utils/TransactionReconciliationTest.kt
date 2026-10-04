package com.example.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.TransactionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransactionReconciliationTest {

    private lateinit var context: Context

    private fun createTx(
        id: String,
        amount: Double,
        type: String = "EXPENSE",
        direction: String = "DEBIT",
        date: String = "2026-09-27",
        time: String = "10:30",
        merchant: String = "Unknown Merchant",
        categoryId: String = "",
        subcategoryId: String = "",
        accountId: String = "",
        paymentMethod: String = "",
        note: String = "",
        source: String = "SMS",
        transactionReference: String = "",
        last4Digits: String = ""
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
            last4Digits = last4Digits,
            direction = direction
        )
    }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        dao.getAllTransactionsSync().forEach { dao.deleteTransaction(it.id) }
        dao.getAllAccountsSync().forEach { dao.deleteAccount(it.id) }
        dao.getAllCardsSync().forEach { dao.deleteCard(it.id) }
    }

    @After
    fun tearDown() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        dao.getAllTransactionsSync().forEach { dao.deleteTransaction(it.id) }
        dao.getAllAccountsSync().forEach { dao.deleteAccount(it.id) }
        dao.getAllCardsSync().forEach { dao.deleteCard(it.id) }
    }

    @Test
    fun testCaseA_SameUpiPaymentCrossSource_ResultsInOneTransaction() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val account = AccountEntity(id = "acc-bank-1", name = "HDFC Bank", bankName = "HDFC Bank", type = "Bank Account", last4Digits = "1234", colour = "#0284C7")
        dao.insertAccount(account)

        val txSms = createTx(
            id = "tx-sms-1",
            amount = 100.0,
            merchant = "Merchant A",
            paymentMethod = "UPI",
            last4Digits = "1234",
            transactionReference = "UTR123456",
            source = "SMS",
            accountId = "acc-bank-1"
        )
        val txNotif = createTx(
            id = "tx-notif-1",
            amount = 100.0,
            merchant = "Merchant A",
            paymentMethod = "UPI",
            last4Digits = "1234",
            transactionReference = "UTR123456",
            source = "NOTIFICATION",
            accountId = "acc-bank-1"
        )
        val txEmail = createTx(
            id = "tx-email-1",
            amount = 100.0,
            merchant = "Merchant A",
            paymentMethod = "UPI",
            last4Digits = "1234",
            transactionReference = "UTR123456",
            source = "EMAIL",
            accountId = "acc-bank-1"
        )

        val res1 = TransactionIngestionEngine.ingestTransaction(context, txSms, "Debited Rs 100 via UPI UTR123456")
        val res2 = TransactionIngestionEngine.ingestTransaction(context, txNotif, "Paid Rs 100 via UPI Ref UTR123456")
        val res3 = TransactionIngestionEngine.ingestTransaction(context, txEmail, "Transaction Alert Rs 100 UTR123456")

        assertEquals(IngestionStatus.IMPORTED, res1.second)
        assertEquals(IngestionStatus.DUPLICATE, res2.second)
        assertEquals(IngestionStatus.DUPLICATE, res3.second)

        val allTxs = dao.getAllTransactionsSync()
        assertEquals(1, allTxs.size)
        assertEquals(100.0, allTxs.first().amount, 0.01)
    }

    @Test
    fun testCaseB_SameCreditCardPurchaseSmsAndEmail_ResultsInOneTransaction() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val account = AccountEntity(id = "acc-cc-1", name = "HDFC Credit Card", bankName = "HDFC Bank", type = "Credit Card", last4Digits = "6926", colour = "#7C3AED")
        val card = CardEntity(id = "card-1", accountId = "acc-cc-1", name = "HDFC Credit Card", type = "Credit Card", last4Digits = "6926")
        dao.insertAccount(account)
        dao.insertCard(card)

        val txSms = createTx(
            id = "tx-cc-sms",
            amount = 500.0,
            merchant = "Amazon",
            paymentMethod = "Credit Card",
            last4Digits = "6926",
            source = "SMS",
            accountId = "acc-cc-1"
        )
        val txEmail = createTx(
            id = "tx-cc-email",
            amount = 500.0,
            merchant = "Amazon",
            paymentMethod = "Credit Card",
            last4Digits = "6926",
            source = "EMAIL",
            accountId = "acc-cc-1"
        )

        val res1 = TransactionIngestionEngine.ingestTransaction(context, txSms, "INR 500 spent on HDFC Card xx6926 at Amazon")
        val res2 = TransactionIngestionEngine.ingestTransaction(context, txEmail, "Your HDFC Bank Credit Card XX6926 was used for INR 500 at Amazon")

        assertEquals(IngestionStatus.IMPORTED, res1.second)
        assertEquals(IngestionStatus.DUPLICATE, res2.second)

        val allTxs = dao.getAllTransactionsSync()
        assertEquals(1, allTxs.size)
        assertEquals(500.0, allTxs.first().amount, 0.01)
    }

    @Test
    fun testCaseC_DifferentWordingAcrossSources_ResultsInOneTransaction() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val account = AccountEntity(id = "acc-bank-1", name = "HDFC Credit Card", bankName = "HDFC Bank", type = "Credit Card", last4Digits = "1234", colour = "#0284C7")
        val card = CardEntity(id = "card-bank-1", accountId = "acc-bank-1", name = "HDFC Credit Card", type = "Credit Card", last4Digits = "1234")
        dao.insertAccount(account)
        dao.insertCard(card)

        val txSms = createTx(
            id = "tx-word-1",
            amount = 250.0,
            merchant = "Swiggy India",
            paymentMethod = "Credit Card",
            last4Digits = "1234",
            source = "SMS",
            accountId = "acc-bank-1"
        )
        val txEmail = createTx(
            id = "tx-word-2",
            amount = 250.0,
            merchant = "Swiggy",
            paymentMethod = "Credit Card",
            last4Digits = "1234",
            source = "EMAIL",
            accountId = "acc-bank-1"
        )

        val res1 = TransactionIngestionEngine.ingestTransaction(context, txSms, "Rs 250 spent on Swiggy India on HDFC Card 1234")
        val res2 = TransactionIngestionEngine.ingestTransaction(context, txEmail, "Transaction of INR 250.00 at Swiggy on HDFC Bank Credit Card XX1234")

        assertEquals(IngestionStatus.IMPORTED, res1.second)
        assertEquals(IngestionStatus.DUPLICATE, res2.second)

        val allTxs = dao.getAllTransactionsSync()
        assertEquals(1, allTxs.size)
    }

    @Test
    fun testCaseD_SameAmountDifferentMerchants_ResultsInTwoTransactions() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val account = AccountEntity(id = "acc-bank-1", name = "HDFC Bank", bankName = "HDFC Bank", type = "Bank Account", last4Digits = "1234", colour = "#0284C7")
        dao.insertAccount(account)

        val tx1 = createTx(id = "tx-m1", amount = 100.0, merchant = "Uber", accountId = "acc-bank-1", source = "MANUAL")
        val tx2 = createTx(id = "tx-m2", amount = 100.0, merchant = "Ola", accountId = "acc-bank-1", source = "MANUAL")

        val res1 = TransactionIngestionEngine.ingestTransaction(context, tx1, "Rs 100 spent at Uber")
        val res2 = TransactionIngestionEngine.ingestTransaction(context, tx2, "Rs 100 spent at Ola")

        assertEquals(IngestionStatus.IMPORTED, res1.second)
        assertEquals(IngestionStatus.IMPORTED, res2.second)

        val allTxs = dao.getAllTransactionsSync()
        assertEquals(2, allTxs.size)
    }

    @Test
    fun testCaseE_SameMerchantDifferentAmounts_ResultsInTwoTransactions() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val account = AccountEntity(id = "acc-bank-1", name = "HDFC Bank", bankName = "HDFC Bank", type = "Bank Account", last4Digits = "1234", colour = "#0284C7")
        dao.insertAccount(account)

        val tx1 = createTx(id = "tx-amt-1", amount = 100.0, merchant = "Swiggy", accountId = "acc-bank-1", source = "MANUAL")
        val tx2 = createTx(id = "tx-amt-2", amount = 150.0, merchant = "Swiggy", accountId = "acc-bank-1", source = "MANUAL")

        val res1 = TransactionIngestionEngine.ingestTransaction(context, tx1, "Rs 100 spent at Swiggy")
        val res2 = TransactionIngestionEngine.ingestTransaction(context, tx2, "Rs 150 spent at Swiggy")

        assertEquals(IngestionStatus.IMPORTED, res1.second)
        assertEquals(IngestionStatus.IMPORTED, res2.second)

        val allTxs = dao.getAllTransactionsSync()
        assertEquals(2, allTxs.size)
    }

    @Test
    fun testCaseF_SameAmountAndMerchantDifferentDates_ResultsInTwoTransactionsUnlessRefMatches() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()

        val tx1 = createTx(id = "tx-date-1", amount = 200.0, date = "2026-09-26", merchant = "Starbucks")
        val tx2 = createTx(id = "tx-date-2", amount = 200.0, date = "2026-09-27", merchant = "Starbucks")

        TransactionIngestionEngine.ingestTransaction(context, tx1, "Rs 200 spent at Starbucks on 26-Sep")
        TransactionIngestionEngine.ingestTransaction(context, tx2, "Rs 200 spent at Starbucks on 27-Sep")

        val allTxs = dao.getAllTransactionsSync()
        assertEquals(2, allTxs.size)
    }

    @Test
    fun testCaseG_UnknownOrAmbiguousAccount_NeverAssignedToUnrelatedAccount() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()
        val userCardAcc = AccountEntity(id = "acc-user-card", name = "My HDFC Card", bankName = "HDFC Bank", type = "Credit Card", last4Digits = "1234", colour = "#7C3AED")
        dao.insertAccount(userCardAcc)

        val unknownTx = createTx(
            id = "tx-unknown-card",
            amount = 300.0,
            merchant = "Store X",
            last4Digits = "9999"
        )

        val res = TransactionIngestionEngine.ingestTransaction(context, unknownTx, "Debited Rs 300 from Unknown Bank Card XX9999 at Store X")

        val savedTx = res.first
        assertNotNull(savedTx)
        assertFalse(savedTx!!.accountId == "acc-user-card")
    }

    @Test
    fun testCaseH_FuturePaymentDueReminder_DoesNotEnterReconciliationAsTransaction() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()

        val isBill = CreditCardBillIngestionEngine.isCreditCardBillMessage("Payment Due Reminder - YES BANK: Rs 50.00 due on 02 Oct 2026.")
        assertTrue(isBill)

        val parseOutput = EmailParser.parseEmail("msg-1", "Payment Due Reminder - YES BANK", "Rs 50.00 due on 02 Oct 2026.", System.currentTimeMillis())
        assertEquals(EmailParserStatus.IGNORED, parseOutput.status)

        val allTxs = dao.getAllTransactionsSync()
        assertEquals(0, allTxs.size)
    }

    @Test
    fun testCaseI_PromotionalEmail_DoesNotCreateTransaction() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()

        val parseOutput = EmailParser.parseEmail("msg-promo", "Special Offer - Upgrade your card", "Convert purchases to EMI & get 5% cashback!", System.currentTimeMillis())
        assertEquals(EmailParserStatus.IGNORED, parseOutput.status)

        val allTxs = dao.getAllTransactionsSync()
        assertEquals(0, allTxs.size)
    }

    @Test
    fun testCaseJ_ActualCompletedTransaction_IsPreserved() = runBlocking {
        val dao = AppDatabase.getDatabase(context).kharchaDao()

        val parseOutput = EmailParser.parseEmail("msg-tx-1", "Transaction Alert", "INR 150.00 spent on your SBI Credit Card ending 2663 at BigBasket on 27-Sep-26.", System.currentTimeMillis())
        assertEquals(EmailParserStatus.IMPORTED, parseOutput.status)
        assertNotNull(parseOutput.transaction)

        val res = TransactionIngestionEngine.ingestTransaction(context, parseOutput.transaction!!, "INR 150.00 spent on your SBI Credit Card ending 2663 at BigBasket")
        assertEquals(IngestionStatus.IMPORTED, res.second)

        val allTxs = dao.getAllTransactionsSync()
        assertEquals(1, allTxs.size)
        assertEquals(150.0, allTxs.first().amount, 0.01)
    }
}
