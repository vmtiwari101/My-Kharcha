package com.example.utils

import androidx.compose.ui.graphics.Color
import java.util.Locale

/**
 * Presentation-layer Bank Account Color Theme Resolver.
 * Provides subtle, bank-specific tinted backgrounds and borders for bank account cards
 * to keep them clean, readable, professional, and visually distinguishable.
 */
data class BankAccountPalette(
    val familyName: String,
    val backgroundColor: Color,
    val borderColor: Color,
    val accentColor: Color
)

object BankAccountColorResolver {

    // Defined visual families for common issuers (visibly tinted light backgrounds with crisp borders)
    private val PALETTE_HDFC = BankAccountPalette(
        familyName = "HDFC_BLUE",
        backgroundColor = Color(0xFFDCEBFF),
        borderColor = Color(0xFFBAD7FE),
        accentColor = Color(0xFF004C8F)
    )

    private val PALETTE_IDFC_FIRST = BankAccountPalette(
        familyName = "IDFC_FIRST_MAROON",
        backgroundColor = Color(0xFFF8DCE2),
        borderColor = Color(0xFFF4B8C5),
        accentColor = Color(0xFF881337)
    )

    private val PALETTE_ICICI = BankAccountPalette(
        familyName = "ICICI_ORANGE",
        backgroundColor = Color(0xFFFFE8D6),
        borderColor = Color(0xFFFFCFA8),
        accentColor = Color(0xFFC23616)
    )

    private val PALETTE_AXIS = BankAccountPalette(
        familyName = "AXIS_BURGUNDY",
        backgroundColor = Color(0xFFF8DCEB),
        borderColor = Color(0xFFF3B8D8),
        accentColor = Color(0xFF97144D)
    )

    private val PALETTE_AU = BankAccountPalette(
        familyName = "AU_ORANGE",
        backgroundColor = Color(0xFFFFE8CF),
        borderColor = Color(0xFFFFCFA0),
        accentColor = Color(0xFFEA580C)
    )

    private val PALETTE_SBI = BankAccountPalette(
        familyName = "SBI_BLUE",
        backgroundColor = Color(0xFFE9E1FF),
        borderColor = Color(0xFFCFC0FE),
        accentColor = Color(0xFF4338CA)
    )

    private val PALETTE_KOTAK = BankAccountPalette(
        familyName = "KOTAK_RED",
        backgroundColor = Color(0xFFF8E0E0),
        borderColor = Color(0xFFF8BDBD),
        accentColor = Color(0xFFDC2626)
    )

    private val PALETTE_INDUSIND = BankAccountPalette(
        familyName = "INDUSIND_TEAL",
        backgroundColor = Color(0xFFDDF4EF),
        borderColor = Color(0xFFA6E5D7),
        accentColor = Color(0xFF0D9488)
    )

    private val PALETTE_BOB = BankAccountPalette(
        familyName = "BOB_VERMILION",
        backgroundColor = Color(0xFFFFEBD9),
        borderColor = Color(0xFFFFCCA3),
        accentColor = Color(0xFFF97316)
    )

    private val PALETTE_PNB = BankAccountPalette(
        familyName = "PNB_AMBER",
        backgroundColor = Color(0xFFFEF3D6),
        borderColor = Color(0xFFFDE08A),
        accentColor = Color(0xFFB45309)
    )

    private val PALETTE_CANARA = BankAccountPalette(
        familyName = "CANARA_BLUE",
        backgroundColor = Color(0xFFDDF0FD),
        borderColor = Color(0xFFA5DCFD),
        accentColor = Color(0xFF0284C7)
    )

    private val PALETTE_UNION = BankAccountPalette(
        familyName = "UNION_BLUE",
        backgroundColor = Color(0xFFDCEBFF),
        borderColor = Color(0xFFACCFFE),
        accentColor = Color(0xFF006CB7)
    )

    private val PALETTE_YES = BankAccountPalette(
        familyName = "YES_BLUE",
        backgroundColor = Color(0xFFDDEBFF),
        borderColor = Color(0xFFB8D6FE),
        accentColor = Color(0xFF2563EB)
    )

    private val PALETTE_CUB = BankAccountPalette(
        familyName = "CUB_INDIGO",
        backgroundColor = Color(0xFFE0E6FF),
        borderColor = Color(0xFFB8C5FE),
        accentColor = Color(0xFF2E3192)
    )

    private val PALETTE_FEDERAL = BankAccountPalette(
        familyName = "FEDERAL_BLUE",
        backgroundColor = Color(0xFFDEEBFF),
        borderColor = Color(0xFFB8D6FE),
        accentColor = Color(0xFF1D4ED8)
    )

    private val PALETTE_RBL = BankAccountPalette(
        familyName = "RBL_INDIGO",
        backgroundColor = Color(0xFFE5E0FF),
        borderColor = Color(0xFFC7BDFF),
        accentColor = Color(0xFF4338CA)
    )

    private val PALETTE_SCB = BankAccountPalette(
        familyName = "SCB_EMERALD",
        backgroundColor = Color(0xFFD7F7E6),
        borderColor = Color(0xFF96ECC0),
        accentColor = Color(0xFF059669)
    )

    private val PALETTE_AMEX = BankAccountPalette(
        familyName = "AMEX_STEEL",
        backgroundColor = Color(0xFFDFE9F2),
        borderColor = Color(0xFFB5CADB),
        accentColor = Color(0xFF0369A1)
    )

    private val PALETTE_CITI = BankAccountPalette(
        familyName = "CITI_BLUE",
        backgroundColor = Color(0xFFDCECFD),
        borderColor = Color(0xFFA2D3FD),
        accentColor = Color(0xFF0284C7)
    )

    private val PALETTE_HSBC = BankAccountPalette(
        familyName = "HSBC_CRIMSON",
        backgroundColor = Color(0xFFFBDCE3),
        borderColor = Color(0xFFF8B4C2),
        accentColor = Color(0xFFBE123C)
    )

    private val PALETTE_DBS = BankAccountPalette(
        familyName = "DBS_RUBY",
        backgroundColor = Color(0xFFFBDDE5),
        borderColor = Color(0xFFF8B5C5),
        accentColor = Color(0xFFE11D48)
    )

    private val PALETTE_BOI = BankAccountPalette(
        familyName = "BOI_GOLD",
        backgroundColor = Color(0xFFFEF0D2),
        borderColor = Color(0xFFFDD980),
        accentColor = Color(0xFFD97706)
    )

    private val PALETTE_CBI = BankAccountPalette(
        familyName = "CBI_BLUE",
        backgroundColor = Color(0xFFDDEEFD),
        borderColor = Color(0xFFA7DAFD),
        accentColor = Color(0xFF0284C7)
    )

    private val PALETTE_INDIAN = BankAccountPalette(
        familyName = "INDIAN_BLUE",
        backgroundColor = Color(0xFFDDE6FF),
        borderColor = Color(0xFFACC0FE),
        accentColor = Color(0xFF2563EB)
    )

    private val PALETTE_UCO = BankAccountPalette(
        familyName = "UCO_BLUE",
        backgroundColor = Color(0xFFDCEEFD),
        borderColor = Color(0xFFA7DAFD),
        accentColor = Color(0xFF0284C7)
    )

    private val PALETTE_BOM = BankAccountPalette(
        familyName = "BOM_BLUE",
        backgroundColor = Color(0xFFE0EAFF),
        borderColor = Color(0xFFB3CCFE),
        accentColor = Color(0xFF1D4ED8)
    )

    private val PALETTE_AIRTEL = BankAccountPalette(
        familyName = "AIRTEL_RED",
        backgroundColor = Color(0xFFFCE0E0),
        borderColor = Color(0xFFF9B8B8),
        accentColor = Color(0xFFDC2626)
    )

    private val PALETTE_PAYTM = BankAccountPalette(
        familyName = "PAYTM_BLUE",
        backgroundColor = Color(0xFFD7F2FD),
        borderColor = Color(0xFF98E2FC),
        accentColor = Color(0xFF00B9F1)
    )

    private val PALETTE_JIO = BankAccountPalette(
        familyName = "JIO_BLUE",
        backgroundColor = Color(0xFFE0E9FF),
        borderColor = Color(0xFFB3CAFE),
        accentColor = Color(0xFF0A2885)
    )

    // Neutral light fallback for unknown / ambiguous banks
    private val NEUTRAL_FALLBACK = BankAccountPalette(
        familyName = "NEUTRAL_FALLBACK",
        backgroundColor = Color(0xFFF8FAFC),
        borderColor = Color(0xFFE2E8F0),
        accentColor = Color(0xFF64748B)
    )

    /**
     * Resolves the subtle tinted palette for a bank account card.
     *
     * @param bankName Bank name or institution identifier
     * @param accountName Account display name
     */
    fun resolvePalette(bankName: String?, accountName: String? = null): BankAccountPalette {
        val combined = "${bankName.orEmpty()} ${accountName.orEmpty()}"
        var clean = combined.lowercase(Locale.ENGLISH)

        // Protect against payment infrastructure phrases like "Powered by Axis Bank"
        clean = clean.replace(Regex("(?i)powered\\s+by.*"), " ")
        clean = clean.replace(Regex("(?i)banking\\s+partner.*"), " ")
        clean = clean.replace(Regex("(?i)psp\\b.*"), " ")
        clean = clean.replace(Regex("[^a-z0-9\\s]"), " ")
        clean = clean.replace(Regex("\\s+"), " ").trim()

        val tokens = clean.split(" ").filter { it.isNotEmpty() }

        return when {
            // City Union Bank (CUB) - evaluated before Union Bank
            clean.contains("city union") || tokens.contains("cub") || clean == "cub" || clean.contains("cub bank") -> PALETTE_CUB

            // Union Bank of India (UBI)
            (clean.contains("union bank") && !clean.contains("city")) || (tokens.contains("union") && !clean.contains("city") && !clean.contains("credit")) || tokens.contains("ubi") -> PALETTE_UNION

            // AU Small Finance Bank
            clean.contains("au bank") || clean.contains("au small") || clean.contains("au sfb") ||
                    clean.contains("ausfb") || clean.contains("au finance") || tokens.contains("au") -> PALETTE_AU

            // Axis Bank
            clean.contains("axis") || clean.contains("uti bank") -> PALETTE_AXIS

            // ICICI Bank
            clean.contains("icici") -> PALETTE_ICICI

            // IDFC FIRST Bank
            clean.contains("idfc") -> PALETTE_IDFC_FIRST

            // HDFC Bank
            clean.contains("hdfc") -> PALETTE_HDFC

            // IndusInd Bank
            clean.contains("indusind") || clean.contains("indus ind") || clean.contains("indus") -> PALETTE_INDUSIND

            // State Bank of India (SBI)
            clean.contains("sbi") || clean.contains("sbicard") ||
                    (clean.contains("state bank") && !clean.contains("united")) -> PALETTE_SBI

            // Kotak Mahindra Bank
            clean.contains("kotak") || tokens.contains("811") -> PALETTE_KOTAK

            // Bank of Baroda (BOB)
            clean.contains("baroda") || tokens.contains("bob") -> PALETTE_BOB

            // Punjab National Bank (PNB)
            clean.contains("pnb") || (clean.contains("punjab") && clean.contains("national")) -> PALETTE_PNB

            // Canara Bank
            clean.contains("canara") -> PALETTE_CANARA

            // YES Bank
            clean.contains("yes bank") || clean.contains("yesbank") || tokens.contains("yes") -> PALETTE_YES

            // Federal Bank
            clean.contains("federal bank") || clean.contains("fedbank") || (tokens.contains("federal") && !clean.contains("credit")) -> PALETTE_FEDERAL

            // RBL Bank
            clean.contains("rbl") || clean.contains("ratnakar") -> PALETTE_RBL

            // Standard Chartered
            clean.contains("standard chartered") || clean.contains("stan chart") || clean.contains("scb") -> PALETTE_SCB

            // American Express
            clean.contains("amex") || clean.contains("american express") -> PALETTE_AMEX

            // Citi
            clean.contains("citi") -> PALETTE_CITI

            // HSBC
            clean.contains("hsbc") || clean.contains("hongkong") -> PALETTE_HSBC

            // DBS Bank
            clean.contains("dbs") || clean.contains("digibank") -> PALETTE_DBS

            // Bank of India
            clean.contains("bank of india") || tokens.contains("boi") -> PALETTE_BOI

            // Central Bank of India
            clean.contains("central bank") || tokens.contains("cbi") -> PALETTE_CBI

            // Indian Bank
            (clean.contains("indian bank") && !clean.contains("south") && !clean.contains("overseas")) || (clean.contains("indian") && !clean.contains("overseas") && !clean.contains("south")) -> PALETTE_INDIAN

            // UCO Bank
            clean.contains("uco") -> PALETTE_UCO

            // Bank of Maharashtra
            clean.contains("maharashtra") || tokens.contains("bom") -> PALETTE_BOM

            // Airtel Payments Bank
            clean.contains("airtel") -> PALETTE_AIRTEL

            // Paytm Payments Bank
            clean.contains("paytm") -> PALETTE_PAYTM

            // Jio Payments Bank
            clean.contains("jio") -> PALETTE_JIO

            // Fallback for unknown / ambiguous banks
            else -> NEUTRAL_FALLBACK
        }
    }
}
