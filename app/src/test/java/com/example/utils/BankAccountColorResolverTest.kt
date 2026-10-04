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
class BankAccountColorResolverTest {

    @Test
    fun testSameBankHasSamePaletteAcrossAccounts() {
        val hdfcSalary = BankAccountColorResolver.resolvePalette("HDFC Bank", "Salary Account")
        val hdfcSavings = BankAccountColorResolver.resolvePalette("HDFC Bank", "Savings Account")
        val hdfcShort = BankAccountColorResolver.resolvePalette("HDFC")

        assertEquals(hdfcSalary.familyName, hdfcSavings.familyName)
        assertEquals(hdfcSalary.backgroundColor, hdfcSavings.backgroundColor)
        assertEquals(hdfcSalary.borderColor, hdfcSavings.borderColor)
        assertEquals("HDFC_BLUE", hdfcShort.familyName)
    }

    @Test
    fun testDifferentBanksDoNotResolveToSameColor() {
        val hdfc = BankAccountColorResolver.resolvePalette("HDFC Bank")
        val idfcFirst = BankAccountColorResolver.resolvePalette("IDFC FIRST Bank")
        val icici = BankAccountColorResolver.resolvePalette("ICICI Bank")
        val axis = BankAccountColorResolver.resolvePalette("Axis Bank")
        val au = BankAccountColorResolver.resolvePalette("AU Small Finance Bank")
        val sbi = BankAccountColorResolver.resolvePalette("State Bank of India")
        val kotak = BankAccountColorResolver.resolvePalette("Kotak Mahindra Bank")
        val indusind = BankAccountColorResolver.resolvePalette("IndusInd Bank")

        // Family names must be distinct
        assertEquals("HDFC_BLUE", hdfc.familyName)
        assertEquals("IDFC_FIRST_MAROON", idfcFirst.familyName)
        assertEquals("ICICI_ORANGE", icici.familyName)
        assertEquals("AXIS_BURGUNDY", axis.familyName)
        assertEquals("AU_ORANGE", au.familyName)
        assertEquals("SBI_BLUE", sbi.familyName)
        assertEquals("KOTAK_RED", kotak.familyName)
        assertEquals("INDUSIND_TEAL", indusind.familyName)

        // Colors must be distinct between major banks
        assertNotEquals(hdfc.backgroundColor, idfcFirst.backgroundColor)
        assertNotEquals(idfcFirst.backgroundColor, icici.backgroundColor)
        assertNotEquals(icici.backgroundColor, axis.backgroundColor)
        assertNotEquals(axis.backgroundColor, au.backgroundColor)
        assertNotEquals(au.backgroundColor, sbi.backgroundColor)
        assertNotEquals(sbi.backgroundColor, kotak.backgroundColor)
        assertNotEquals(kotak.backgroundColor, indusind.backgroundColor)
    }

    @Test
    fun testUnknownBankReturnsNeutralFallback() {
        val unknown1 = BankAccountColorResolver.resolvePalette("Random FinTech Bank")
        val unknown2 = BankAccountColorResolver.resolvePalette("XYZ Credit Union")
        val nullBank = BankAccountColorResolver.resolvePalette(null)
        val blankBank = BankAccountColorResolver.resolvePalette("   ")

        assertEquals("NEUTRAL_FALLBACK", unknown1.familyName)
        assertEquals("NEUTRAL_FALLBACK", unknown2.familyName)
        assertEquals("NEUTRAL_FALLBACK", nullBank.familyName)
        assertEquals("NEUTRAL_FALLBACK", blankBank.familyName)

        assertEquals(Color(0xFFF8FAFC), unknown1.backgroundColor)
        assertEquals(Color(0xFFE2E8F0), unknown1.borderColor)
    }

    @Test
    fun testBackgroundColorsAreSubtleLightTints() {
        val banks = listOf(
            "HDFC Bank",
            "IDFC FIRST Bank",
            "ICICI Bank",
            "Axis Bank",
            "AU Small Finance Bank",
            "SBI",
            "Kotak Bank",
            "IndusInd Bank",
            "Bank of Baroda",
            "Punjab National Bank",
            "Canara Bank",
            "Union Bank of India",
            "YES Bank",
            "City Union Bank",
            "Federal Bank",
            "RBL Bank",
            "Standard Chartered"
        )

        for (bank in banks) {
            val palette = BankAccountColorResolver.resolvePalette(bank)
            assertNotNull(palette)
            assertNotEquals("Color must not be transparent", Color.Transparent, palette.backgroundColor)
            assertNotEquals("Color must not be unspecified", Color.Unspecified, palette.backgroundColor)

            // Must be light tints: red, green, blue channels should all be >= 0.80 (light pastel)
            // ensuring strong contrast with dark slate text (#0F172A)
            assertTrue(
                "Background red channel >= 0.80 for $bank",
                palette.backgroundColor.red >= 0.80f
            )
            assertTrue(
                "Background green channel >= 0.80 for $bank",
                palette.backgroundColor.green >= 0.80f
            )
            assertTrue(
                "Background blue channel >= 0.80 for $bank",
                palette.backgroundColor.blue >= 0.80f
            )
            assertEquals("Background alpha must be 1.0f", 1f, palette.backgroundColor.alpha, 0.001f)
        }
    }

    @Test
    fun testPoweredByInfrastructureSafety() {
        // "Powered by Axis Bank" alone must not resolve to Axis Bank
        val poweredByAlone = BankAccountColorResolver.resolvePalette("Powered by Axis Bank")
        assertEquals("NEUTRAL_FALLBACK", poweredByAlone.familyName)

        // "SBI + Powered by Axis Bank" must resolve to SBI
        val sbiWithPoweredBy = BankAccountColorResolver.resolvePalette("SBI Bank", "Powered by Axis Bank")
        assertEquals("SBI_BLUE", sbiWithPoweredBy.familyName)
    }
}
