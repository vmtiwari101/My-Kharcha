package com.example

import com.example.utils.SmsParser
import com.example.ui.screens.getCleanMerchantName
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MerchantNormalizationTest {

    @Test
    fun testConcatenatedMerchantNormalization() {
        assertEquals("Bablu Chat Corner", SmsParser.cleanMerchantName("babluchatcorner"))
        assertEquals("Dharmendra Kirana Store", SmsParser.cleanMerchantName("dharmendrakiranastore"))
        assertEquals("Pradumya General Store", SmsParser.cleanMerchantName("pradumyageneralstore"))
    }

    @Test
    fun testAlreadySpacedNamesRemainUnchanged() {
        assertEquals("Aryan Kishan Seva", SmsParser.cleanMerchantName("Aryan Kishan Seva"))
        assertEquals("HDFC Bank", SmsParser.cleanMerchantName("HDFC Bank"))
        assertEquals("Amazon", SmsParser.cleanMerchantName("Amazon"))
        assertEquals("Jio", SmsParser.cleanMerchantName("Jio"))
    }

    @Test
    fun testMixedCaseNames() {
        assertEquals("Bablu Chat Corner", SmsParser.cleanMerchantName("BabluChatCorner"))
        assertEquals("Dharmendra Kirana Store", SmsParser.cleanMerchantName("dharmendraKiranaStore"))
    }

    @Test
    fun testAcronymsAndBrandNames() {
        assertEquals("HDFC Bank", SmsParser.cleanMerchantName("HDFC Bank"))
        assertEquals("Amazon", SmsParser.cleanMerchantName("Amazon"))
        assertEquals("Jio", SmsParser.cleanMerchantName("Jio"))
    }

    @Test
    fun testShortMerchantNames() {
        assertEquals("Jio", SmsParser.cleanMerchantName("jio"))
        assertEquals("Vi", SmsParser.cleanMerchantName("vi"))
    }

    @Test
    fun testAmbiguousStringsRemainUnchanged() {
        assertEquals("Randomstringxyz", SmsParser.cleanMerchantName("randomstringxyz"))
    }

    @Test
    fun testDisplayNormalizationConsistency() {
        val catName = "Groceries"
        assertEquals("Bablu Chat Corner", getCleanMerchantName("babluchatcorner", catName))
        assertEquals("Aryan Kishan Seva", getCleanMerchantName("Aryan Kishan Seva", catName))
    }

    @Test
    fun testPunctuationInMerchantNames() {
        assertEquals("Bablu Chat Corner", SmsParser.cleanMerchantName("Bablu.Chat.Corner"))
        assertEquals("Dharmendra Kirana Store", SmsParser.cleanMerchantName("Dharmendra-Kirana-Store"))
    }

    @Test
    fun testManualMerchantCorrectionPreserved() {
        // Manual merchant name correction (e.g. user entered custom name) is preserved and normalized correctly
        val manualName = "My Custom Store"
        assertEquals("My Custom Store", SmsParser.cleanMerchantName(manualName))
    }

    @Test
    fun testMerchantHistoryGroupingConsistency() {
        // Verify that different spelling/casing/concatenation variants map to the same normalized display name for grouping
        val variant1 = "babluchatcorner"
        val variant2 = "BabluChatCorner"
        val variant3 = "Bablu Chat Corner"
        val category = "Food"

        val clean1 = getCleanMerchantName(variant1, category)
        val clean2 = getCleanMerchantName(variant2, category)
        val clean3 = getCleanMerchantName(variant3, category)

        assertEquals(clean1, clean2)
        assertEquals(clean2, clean3)
        assertEquals("Bablu Chat Corner", clean1)
    }
}
