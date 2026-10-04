package com.example

import com.example.ui.screens.getCleanMerchantName
import com.example.utils.EmailParser
import com.example.utils.NotificationParser
import com.example.utils.SmsParser
import com.example.utils.TransactionIdentityResolver
import com.example.utils.TransactionIngestionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HdfcMerchantAndReferenceTest {

    @Test
    fun test1_hdfcValidReferenceIdIsExtractedCorrectly() {
        val hdfcText = "Dear Customer, Rs 340.00 debited from HDFC Bank A/c **1234 on 28-SEP-26 to Priyal Patel. UPI Ref No: 427012345678. Transaction Details: View Details."
        val refSms = SmsParser.extractTransactionReference(hdfcText)
        val refEmail = EmailParser.extractTransactionReference(hdfcText)
        val refNotif = NotificationParser.extractTransactionReference(hdfcText)

        assertEquals("427012345678", refSms)
        assertEquals("427012345678", refEmail)
        assertEquals("427012345678", refNotif)
    }

    @Test
    fun test2_detailsIsNeverAcceptedAsReferenceId() {
        assertFalse(TransactionIdentityResolver.isValidTransactionReference("Details"))
        assertFalse(TransactionIdentityResolver.isValidTransactionReference("details"))
        assertEquals("", TransactionIngestionEngine.extractNormalizedReference("Details"))

        val textWithDetailsLabelOnly = "Transaction Details: Details"
        val ref = SmsParser.extractTransactionReference(textWithDetailsLabelOnly)
        assertEquals("", ref)
    }

    @Test
    fun test3_viewDetailsIsNeverAcceptedAsReferenceId() {
        assertFalse(TransactionIdentityResolver.isValidTransactionReference("View Details"))
        assertFalse(TransactionIdentityResolver.isValidTransactionReference("ViewDetails"))
        assertEquals("", TransactionIngestionEngine.extractNormalizedReference("View Details"))

        val textWithViewDetailsOnly = "Transaction Details: View Details"
        val ref = EmailParser.extractTransactionReference(textWithViewDetailsOnly)
        assertEquals("", ref)
    }

    @Test
    fun test4_missingReferenceIdSafelyRemainsBlank() {
        val textWithoutReference = "Dear Customer, Rs 340.00 debited from HDFC Bank A/c **1234 on 28-SEP-26. Transaction Details: View Details."
        val ref = SmsParser.extractTransactionReference(textWithoutReference)
        assertEquals("", ref)

        val normalized = TransactionIngestionEngine.extractNormalizedReference(ref)
        assertEquals("", normalized)
    }

    @Test
    fun test5_merchantExtractionDoesNotUseUiLabelsAsMerchant() {
        val subject = "Re: Sharing this alert"
        val body = "Dear Customer, Rs 340.00 debited from HDFC Bank A/c **1234 on 28-SEP-26 to Priyal Patel. UPI Ref No: 427012345678. Transaction Details: View Details."
        
        val extractedFromEmail = EmailParser.extractMerchant(subject, body)
        assertNotEquals("RE Sharing This Alert", extractedFromEmail)
        assertNotEquals("Sharing This Alert", extractedFromEmail)
        assertNotEquals("Details", extractedFromEmail)
        assertNotEquals("View Details", extractedFromEmail)
        assertEquals("Priyal Patel", extractedFromEmail)

        val displayClean = getCleanMerchantName("RE Sharing This Alert", "Other")
        assertNotEquals("RE Sharing This Alert", displayClean)
        assertEquals("Other", displayClean)
    }

    @Test
    fun test6_existingAryanKishanSevaExtractionRemainsCorrect() {
        val body = "debited by Rs. 454.38 on 29/09/26; ARYAN KISHAN SEVA credited."
        val extracted = SmsParser.extractMerchant(body, "IDFCFB")
        assertEquals("Aryan Kishan Seva", extracted)
        assertEquals("Aryan Kishan Seva", SmsParser.cleanMerchantName("Aryan Kishan Seva"))
    }

    @Test
    fun test7_existingBabluDharmendraPradumyaPriyalMerchantNormalizationRemainsCorrect() {
        assertEquals("Bablu Chat Corner", SmsParser.cleanMerchantName("babluchatcorner"))
        assertEquals("Dharmendra Kirana Store", SmsParser.cleanMerchantName("dharmendrakiranastore"))
        assertEquals("Pradumya General Store", SmsParser.cleanMerchantName("pradumyageneralstore"))
        assertEquals("Priyal Patel", SmsParser.cleanMerchantName("Priyal Patel"))
    }
}
