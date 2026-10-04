package com.example.utils

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CreditCardColorResolverTest {

    @Test
    fun testAuAndAxisDoNotResolveToSamePalette() {
        val au = CreditCardColorResolver.resolvePalette("AU Small Finance Bank")
        val axis = CreditCardColorResolver.resolvePalette("Axis Bank")

        assertEquals("AU_ORANGE", au.familyName)
        assertEquals("AXIS_BURGUNDY", axis.familyName)

        assertNotEquals(au.primaryColor, axis.primaryColor)
        assertNotEquals(au.secondaryColor, axis.secondaryColor)
        assertNotEquals(au.familyName, axis.familyName)

        // Neither should be purple
        assertNotEquals(Color(0xFF7C3AED), au.primaryColor)
        assertNotEquals(Color(0xFF7C3AED), axis.primaryColor)
    }

    @Test
    fun testIciciAndIdfcFirstDoNotResolveToSamePalette() {
        val icici = CreditCardColorResolver.resolvePalette("ICICI Bank")
        val idfcFirst = CreditCardColorResolver.resolvePalette("IDFC FIRST Bank")

        assertEquals("ICICI_RED_ORANGE", icici.familyName)
        assertEquals("IDFC_FIRST_MAROON", idfcFirst.familyName)

        assertNotEquals(icici.primaryColor, idfcFirst.primaryColor)
        assertNotEquals(icici.secondaryColor, idfcFirst.secondaryColor)
        assertNotEquals(icici.familyName, idfcFirst.familyName)

        // Neither should be purple
        assertNotEquals(Color(0xFF7C3AED), icici.primaryColor)
        assertNotEquals(Color(0xFF7C3AED), idfcFirst.primaryColor)
    }

    @Test
    fun testKnownIssuersReturnValidColors() {
        val issuers = listOf(
            "AU Small Finance Bank" to "AU_ORANGE",
            "Axis Bank" to "AXIS_BURGUNDY",
            "ICICI Bank" to "ICICI_RED_ORANGE",
            "IDFC FIRST Bank" to "IDFC_FIRST_MAROON",
            "HDFC Bank" to "HDFC_BLUE",
            "IndusInd Bank" to "INDUSIND_TEAL",
            "State Bank of India" to "SBI_BLUE",
            "Kotak Mahindra Bank" to "KOTAK_RED",
            "Bank of Baroda" to "BOB_VERMILION",
            "Punjab National Bank" to "PNB_AMBER",
            "Canara Bank" to "CANARA_BLUE",
            "YES Bank" to "YES_BLUE",
            "Federal Bank" to "FEDERAL_BLUE",
            "RBL Bank" to "RBL_INDIGO",
            "Standard Chartered" to "SCB_EMERALD",
            "American Express" to "AMEX_STEEL",
            "Citi" to "CITI_BLUE",
            "HSBC" to "HSBC_CRIMSON",
            "DBS Bank" to "DBS_RUBY"
        )

        for ((bankName, expectedFamily) in issuers) {
            val palette = CreditCardColorResolver.resolvePalette(bankName)
            assertEquals("Expected family $expectedFamily for $bankName", expectedFamily, palette.familyName)

            // Primary color must be fully opaque and valid
            assertNotEquals(Color.Unspecified, palette.primaryColor)
            assertNotEquals(Color.Transparent, palette.primaryColor)
            assertTrue("Primary alpha >= 0.9f for $bankName", palette.primaryColor.alpha >= 0.9f)

            // Secondary color must be fully opaque and valid
            assertNotEquals(Color.Unspecified, palette.secondaryColor)
            assertNotEquals(Color.Transparent, palette.secondaryColor)
            assertTrue("Secondary alpha >= 0.9f for $bankName", palette.secondaryColor.alpha >= 0.9f)

            // Dark base must be valid
            assertNotEquals(Color.Unspecified, palette.darkBaseColor)
            assertNotEquals(Color.Transparent, palette.darkBaseColor)
            assertTrue("Dark base alpha >= 0.9f for $bankName", palette.darkBaseColor.alpha >= 0.9f)

            // Ensure not hardcoded fallback purple
            assertNotEquals("Should not be fallback purple for $bankName", Color(0xFF7C3AED), palette.primaryColor)
        }
    }

    @Test
    fun testUnknownIssuerReturnsValidFallback() {
        val unknown1 = CreditCardColorResolver.resolvePalette("Random FinTech 123", cardId = "card-abc")
        val unknown2 = CreditCardColorResolver.resolvePalette("Mysterious Bank Corp", cardId = "card-xyz")

        assertTrue(unknown1.familyName.endsWith("_NEUTRAL"))
        assertTrue(unknown2.familyName.endsWith("_NEUTRAL"))

        assertNotEquals(Color.Unspecified, unknown1.primaryColor)
        assertNotEquals(Color.Transparent, unknown1.primaryColor)
        assertTrue(unknown1.primaryColor.alpha >= 0.9f)
        assertNotEquals(Color(0xFF7C3AED), unknown1.primaryColor)

        assertNotEquals(Color.Unspecified, unknown2.primaryColor)
        assertNotEquals(Color.Transparent, unknown2.primaryColor)
        assertTrue(unknown2.primaryColor.alpha >= 0.9f)
        assertNotEquals(Color(0xFF7C3AED), unknown2.primaryColor)
    }

    @Test
    fun testSameIssuerIsDeterministic() {
        val au1 = CreditCardColorResolver.resolvePalette("AU Bank", cardName = "LIT")
        val au2 = CreditCardColorResolver.resolvePalette("AU Bank", cardName = "LIT")
        assertEquals(au1.primaryColor, au2.primaryColor)
        assertEquals(au1.familyName, au2.familyName)

        val unknown1 = CreditCardColorResolver.resolvePalette("Nonexistent Bank", cardId = "id-4455")
        val unknown2 = CreditCardColorResolver.resolvePalette("Nonexistent Bank", cardId = "id-4455")
        assertEquals(unknown1.primaryColor, unknown2.primaryColor)
        assertEquals(unknown1.familyName, unknown2.familyName)
    }

    @Test
    fun testNoTransparentOrInvalidColors() {
        val testInputs = listOf(
            "",
            "   ",
            null,
            "12345",
            "!!!@@@###",
            "AU",
            "Axis Ace",
            "HDFC Regalia",
            "ICICI Sapphiro",
            "IDFC Wealth",
            "SBI SimplyClick",
            "IndusInd Legend"
        )

        for (input in testInputs) {
            val palette = CreditCardColorResolver.resolvePalette(input)
            assertNotNull(palette)
            assertNotNull(palette.gradientBrush)

            assertNotEquals("Primary color must not be unspecified", Color.Unspecified, palette.primaryColor)
            assertNotEquals("Primary color must not be transparent", Color.Transparent, palette.primaryColor)
            assertTrue("Primary alpha > 0", palette.primaryColor.alpha > 0f)

            assertNotEquals("Secondary color must not be unspecified", Color.Unspecified, palette.secondaryColor)
            assertNotEquals("Secondary color must not be transparent", Color.Transparent, palette.secondaryColor)
            assertTrue("Secondary alpha > 0", palette.secondaryColor.alpha > 0f)

            assertNotEquals("Dark base must not be unspecified", Color.Unspecified, palette.darkBaseColor)
            assertNotEquals("Dark base must not be transparent", Color.Transparent, palette.darkBaseColor)
            assertTrue("Dark base alpha > 0", palette.darkBaseColor.alpha > 0f)

            assertNotEquals("Content color must not be unspecified", Color.Unspecified, palette.contentColor)
            assertTrue("Content alpha > 0", palette.contentColor.alpha > 0f)
        }
    }

    @Test
    fun testLegacyPurpleDoesNotForcePurple() {
        // Even when legacy rawColour is "#7C3AED", known issuer gets their proper family
        val hdfc = CreditCardColorResolver.resolvePalette("HDFC Bank", rawColour = "#7C3AED")
        assertEquals("HDFC_BLUE", hdfc.familyName)
        assertNotEquals(Color(0xFF7C3AED), hdfc.primaryColor)

        // Even for unknown bank, legacy "#7C3AED" does not force purple
        val unknown = CreditCardColorResolver.resolvePalette("Unknown Bank", rawColour = "#7C3AED")
        assertTrue(unknown.familyName.endsWith("_NEUTRAL"))
        assertNotEquals(Color(0xFF7C3AED), unknown.primaryColor)
    }

    @Test
    fun testAccountEntityPaletteResolutionPrefersIssuerBrandOverRawColour() {
        val testCases = listOf(
            Triple("HDFC Bank", "HDFC Regalia", "HDFC_BLUE"),
            Triple("Axis Bank", "Axis Flipkart", "AXIS_BURGUNDY"),
            Triple("ICICI Bank", "ICICI Coral", "ICICI_RED_ORANGE"),
            Triple("IDFC FIRST Bank", "IDFC Wealth", "IDFC_FIRST_MAROON"),
            Triple("AU Small Finance Bank", "AU LIT", "AU_ORANGE")
        )

        for ((bankName, cardName, expectedFamily) in testCases) {
            val palette = CreditCardColorResolver.resolvePalette(
                bankName = bankName,
                cardName = cardName,
                last4Digits = "1234",
                cardId = "acc-123",
                rawColour = "#0284C7" // Default or arbitrary raw colour
            )
            assertEquals("Palette for $bankName should resolve to $expectedFamily", expectedFamily, palette.familyName)
        }
    }
}
