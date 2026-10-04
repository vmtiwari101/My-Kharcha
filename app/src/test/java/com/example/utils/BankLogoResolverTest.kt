package com.example.utils

import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BankLogoResolverTest {

    @Test
    fun testSbiLogoResolution() {
        assertEquals(R.drawable.ic_bank_sbi, BankLogoResolver.getLogo("SBI"))
        assertEquals(R.drawable.ic_bank_sbi, BankLogoResolver.getLogo("State Bank of India"))
        assertEquals(R.drawable.ic_bank_sbi, BankLogoResolver.getLogo("SBI Credit Card ••••2663"))
        assertEquals(R.drawable.ic_bank_sbi, BankLogoResolver.getLogo("SBICARD"))
    }

    @Test
    fun testHdfcLogoResolution() {
        assertEquals(R.drawable.ic_bank_hdfc, BankLogoResolver.getLogo("HDFC"))
        assertEquals(R.drawable.ic_bank_hdfc, BankLogoResolver.getLogo("HDFC Bank"))
        assertEquals(R.drawable.ic_bank_hdfc, BankLogoResolver.getLogo("HDFC Bank Ltd"))
        assertEquals(R.drawable.ic_bank_hdfc, BankLogoResolver.getLogo("HDFC Credit Card"))
    }

    @Test
    fun testIciciLogoResolution() {
        assertEquals(R.drawable.ic_bank_icici, BankLogoResolver.getLogo("ICICI"))
        assertEquals(R.drawable.ic_bank_icici, BankLogoResolver.getLogo("ICICI Bank"))
        assertEquals(R.drawable.ic_bank_icici, BankLogoResolver.getLogo("ICICI Bank Ltd"))
    }

    @Test
    fun testAxisLogoResolution() {
        assertEquals(R.drawable.ic_bank_axis, BankLogoResolver.getLogo("Axis"))
        assertEquals(R.drawable.ic_bank_axis, BankLogoResolver.getLogo("Axis Bank"))
    }

    @Test
    fun testAuLogoResolution() {
        assertEquals(R.drawable.ic_bank_au, BankLogoResolver.getLogo("AU"))
        assertEquals(R.drawable.ic_bank_au, BankLogoResolver.getLogo("AU Bank"))
        assertEquals(R.drawable.ic_bank_au, BankLogoResolver.getLogo("AU Small Finance Bank"))
        assertEquals(R.drawable.ic_bank_au, BankLogoResolver.getLogo("AU Credit Card ••••9546"))
    }

    @Test
    fun testIdfcFirstLogoResolution() {
        assertEquals(R.drawable.ic_bank_idfc, BankLogoResolver.getLogo("IDFC"))
        assertEquals(R.drawable.ic_bank_idfc, BankLogoResolver.getLogo("IDFC FIRST"))
        assertEquals(R.drawable.ic_bank_idfc, BankLogoResolver.getLogo("IDFC FIRST Bank"))
        assertEquals(R.drawable.ic_bank_idfc, BankLogoResolver.getLogo("IDFC First Bank"))
    }

    @Test
    fun testUnknownBankFallback() {
        assertNull(BankLogoResolver.getLogo("Unknown Bank 12345"))
        assertNull(BankLogoResolver.getLogo("Random Wallet X"))
        assertNull(BankLogoResolver.getLogo(""))
        assertNull(BankLogoResolver.getLogo(null))
    }

    @Test
    fun testPoweredByAxisProtection_WithActualSbiIssuer() {
        // "Powered by Axis Bank" with actual SBI issuer -> MUST be SBI logo, NEVER Axis logo
        val logoWithIssuer = BankLogoResolver.getLogo(bankName = "Powered by Axis Bank", actualIssuer = "SBI")
        assertEquals(R.drawable.ic_bank_sbi, logoWithIssuer)
        assertNotEquals(R.drawable.ic_bank_axis, logoWithIssuer)

        // Raw text containing actual SBI card ending 2663 + "Powered by Axis Bank" -> MUST be SBI logo, NEVER Axis logo
        val logoFromCombinedText = BankLogoResolver.getLogo("SBI Credit Card ••••2663 (Powered by Axis Bank)")
        assertEquals(R.drawable.ic_bank_sbi, logoFromCombinedText)
        assertNotEquals(R.drawable.ic_bank_axis, logoFromCombinedText)

        // Pure infrastructure text alone without an actual issuer must NOT match Axis
        val logoPureInfra = BankLogoResolver.getLogo("Powered by Axis Bank")
        assertNull(logoPureInfra)
        assertNotEquals(R.drawable.ic_bank_axis, logoPureInfra)
    }

    @Test
    fun testAllMandatoryIndianBanksResolution() {
        val testCases = mapOf(
            "Kotak Mahindra Bank" to R.drawable.ic_bank_kotak,
            "Punjab National Bank" to R.drawable.ic_bank_pnb,
            "Bank of Baroda" to R.drawable.ic_bank_bob,
            "Bank of India" to R.drawable.ic_bank_boi,
            "Canara Bank" to R.drawable.ic_bank_canara,
            "Central Bank of India" to R.drawable.ic_bank_cbi,
            "Indian Bank" to R.drawable.ic_bank_indian,
            "Indian Overseas Bank" to R.drawable.ic_bank_iob,
            "Union Bank of India" to R.drawable.ic_bank_union,
            "UCO Bank" to R.drawable.ic_bank_uco,
            "Bank of Maharashtra" to R.drawable.ic_bank_bom,
            "YES Bank" to R.drawable.ic_bank_yes,
            "IndusInd Bank" to R.drawable.ic_bank_indusind,
            "Federal Bank" to R.drawable.ic_bank_federal,
            "RBL Bank" to R.drawable.ic_bank_rbl,
            "Bandhan Bank" to R.drawable.ic_bank_bandhan,
            "IDBI Bank" to R.drawable.ic_bank_idbi,
            "South Indian Bank" to R.drawable.ic_bank_sib,
            "Karnataka Bank" to R.drawable.ic_bank_karnataka,
            "Jammu & Kashmir Bank" to R.drawable.ic_bank_jnk,
            "City Union Bank" to R.drawable.ic_bank_city,
            "DCB Bank" to R.drawable.ic_bank_dcb,
            "CSB Bank" to R.drawable.ic_bank_csb,
            "Dhanlaxmi Bank" to R.drawable.ic_bank_dhanlaxmi,
            "Punjab & Sind Bank" to R.drawable.ic_bank_psb,
            "Tamilnad Mercantile Bank" to R.drawable.ic_bank_tmb,
            "ESAF Small Finance Bank" to R.drawable.ic_bank_esaf,
            "Ujjivan Small Finance Bank" to R.drawable.ic_bank_ujjivan,
            "Fino Payments Bank" to R.drawable.ic_bank_fino,
            "Airtel Payments Bank" to R.drawable.ic_bank_airtel,
            "India Post Payments Bank" to R.drawable.ic_bank_ippb,
            "Jio Payments Bank" to R.drawable.ic_bank_jio,
            "Paytm Payments Bank" to R.drawable.ic_bank_paytm
        )

        for ((name, expectedDrawable) in testCases) {
            val resolved = BankLogoResolver.getLogo(name)
            assertNotNull("Expected non-null logo for $name", resolved)
            assertEquals("Mismatch for $name", expectedDrawable, resolved)
        }
    }

    @Test
    fun testUnionBankAndUbiResolution() {
        assertEquals(R.drawable.ic_bank_union, BankLogoResolver.getLogo("Union Bank"))
        assertEquals(R.drawable.ic_bank_union, BankLogoResolver.getLogo("Union Bank of India"))
        assertEquals(R.drawable.ic_bank_union, BankLogoResolver.getLogo("UBI"))
        assertEquals(R.drawable.ic_bank_union, BankLogoResolver.getLogo("UBI Bank"))
    }

    @Test
    fun testYesBankAndYesbankResolution() {
        assertEquals(R.drawable.ic_bank_yes, BankLogoResolver.getLogo("YES Bank"))
        assertEquals(R.drawable.ic_bank_yes, BankLogoResolver.getLogo("YESBANK"))
        assertEquals(R.drawable.ic_bank_yes, BankLogoResolver.getLogo("Yes Bank Ltd"))
        assertEquals(R.drawable.ic_bank_yes, BankLogoResolver.getLogo("YES"))
    }

    @Test
    fun testCityUnionBankAndCubResolution() {
        val cityUnionLogo = BankLogoResolver.getLogo("City Union Bank")
        val cubLogo = BankLogoResolver.getLogo("CUB")
        val cubBankLogo = BankLogoResolver.getLogo("CUB Bank")

        assertEquals(R.drawable.ic_bank_city, cityUnionLogo)
        assertEquals(R.drawable.ic_bank_city, cubLogo)
        assertEquals(R.drawable.ic_bank_city, cubBankLogo)

        // Must NEVER resolve to Union Bank of India
        assertNotEquals(R.drawable.ic_bank_union, cityUnionLogo)
        assertNotEquals(R.drawable.ic_bank_union, cubLogo)
        assertNotEquals(R.drawable.ic_bank_union, cubBankLogo)
    }

    @Test
    fun testResourceIntegrityForImportedLogos() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val resourceIds = listOf(
            R.drawable.ic_bank_union,
            R.drawable.ic_bank_yes,
            R.drawable.ic_bank_city,
            R.drawable.ic_bank_cub
        )

        for (resId in resourceIds) {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, resId)
            assertNotNull("Drawable resource $resId must be loadable and non-null", drawable)
            org.junit.Assert.assertTrue(
                "Drawable resource $resId must have positive intrinsic dimension",
                drawable!!.intrinsicWidth > 0 && drawable.intrinsicHeight > 0
            )
        }
    }
}
