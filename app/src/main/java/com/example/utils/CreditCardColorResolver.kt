package com.example.utils

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.abs

/**
 * Presentation-layer Credit Card Color Theme Resolver.
 * Provides deterministic, visually distinguishable color families for credit card issuers
 * inspired by general branding palettes without using copyrighted assets or sensitive user data.
 */
data class CreditCardPalette(
    val familyName: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val darkBaseColor: Color = Color(0xFF0F172A),
    val gradientBrush: Brush = Brush.linearGradient(
        colors = listOf(primaryColor, secondaryColor, darkBaseColor)
    ),
    val contentColor: Color = Color.White
)

object CreditCardColorResolver {

    // Defined visual families for common issuers
    private val PALETTE_AU = CreditCardPalette(
        familyName = "AU_ORANGE",
        primaryColor = Color(0xFFEA580C),
        secondaryColor = Color(0xFFC2410C),
        darkBaseColor = Color(0xFF431407)
    )

    private val PALETTE_AU_ZENITH = CreditCardPalette(
        familyName = "AU_ZENITH_ONYX",
        primaryColor = Color(0xFF292524),
        secondaryColor = Color(0xFF44403C),
        darkBaseColor = Color(0xFF0C0A09)
    )

    private val PALETTE_AXIS = CreditCardPalette(
        familyName = "AXIS_BURGUNDY",
        primaryColor = Color(0xFF97144D),
        secondaryColor = Color(0xFF700D36),
        darkBaseColor = Color(0xFF38061B)
    )

    private val PALETTE_AXIS_FLIPKART = CreditCardPalette(
        familyName = "AXIS_FLIPKART_NAVY",
        primaryColor = Color(0xFF1E3A8A), // Flipkart Navy
        secondaryColor = Color(0xFF2563EB), // Cobalt Accent
        darkBaseColor = Color(0xFF090D1A)
    )

    private val PALETTE_AXIS_AIRTEL = CreditCardPalette(
        familyName = "AXIS_AIRTEL_CRIMSON",
        primaryColor = Color(0xFFE11D48),
        secondaryColor = Color(0xFF9F1239),
        darkBaseColor = Color(0xFF18040A)
    )

    private val PALETTE_ICICI = CreditCardPalette(
        familyName = "ICICI_RED_ORANGE",
        primaryColor = Color(0xFFC23616),
        secondaryColor = Color(0xFFE65100),
        darkBaseColor = Color(0xFF3E120A)
    )

    private val PALETTE_ICICI_AMAZON_PAY = CreditCardPalette(
        familyName = "ICICI_AMAZON_PAY",
        primaryColor = Color(0xFF232F3E), // Amazon Squiddink
        secondaryColor = Color(0xFF37475A),
        darkBaseColor = Color(0xFF131921)
    )

    private val PALETTE_ICICI_CORAL = CreditCardPalette(
        familyName = "ICICI_CORAL_AZURE",
        primaryColor = Color(0xFF0284C7),
        secondaryColor = Color(0xFF0369A1),
        darkBaseColor = Color(0xFF082F49)
    )

    private val PALETTE_IDFC_FIRST = CreditCardPalette(
        familyName = "IDFC_FIRST_MAROON",
        primaryColor = Color(0xFF881337),
        secondaryColor = Color(0xFF4C0519),
        darkBaseColor = Color(0xFF1F030B)
    )

    private val PALETTE_HDFC = CreditCardPalette(
        familyName = "HDFC_BLUE",
        primaryColor = Color(0xFF004C8F),
        secondaryColor = Color(0xFF1E3A8A),
        darkBaseColor = Color(0xFF0B192C)
    )

    private val PALETTE_HDFC_MILLENNIA = CreditCardPalette(
        familyName = "HDFC_MILLENNIA",
        primaryColor = Color(0xFF0284C7), // Electric Cyan / Blue Shimmer
        secondaryColor = Color(0xFF0369A1),
        darkBaseColor = Color(0xFF082F49)
    )

    private val PALETTE_HDFC_INFINIA = CreditCardPalette(
        familyName = "HDFC_INFINIA_BLACK",
        primaryColor = Color(0xFF27272A), // Black & Titanium
        secondaryColor = Color(0xFF3F3F46),
        darkBaseColor = Color(0xFF09090B)
    )

    private val PALETTE_SBI_CASHBACK = CreditCardPalette(
        familyName = "SBI_CASHBACK_COBALT",
        primaryColor = Color(0xFF034694),
        secondaryColor = Color(0xFF002244),
        darkBaseColor = Color(0xFF021024)
    )

    private val PALETTE_INDUSIND = CreditCardPalette(
        familyName = "INDUSIND_TEAL",
        primaryColor = Color(0xFF0D9488),
        secondaryColor = Color(0xFF0F766E),
        darkBaseColor = Color(0xFF042F2E)
    )

    private val PALETTE_SBI = CreditCardPalette(
        familyName = "SBI_BLUE",
        primaryColor = Color(0xFF0284C7),
        secondaryColor = Color(0xFF0369A1),
        darkBaseColor = Color(0xFF082F49)
    )

    private val PALETTE_KOTAK = CreditCardPalette(
        familyName = "KOTAK_RED",
        primaryColor = Color(0xFFDC2626),
        secondaryColor = Color(0xFF991B1B),
        darkBaseColor = Color(0xFF450A0A)
    )

    private val PALETTE_BOB = CreditCardPalette(
        familyName = "BOB_VERMILION",
        primaryColor = Color(0xFFF97316),
        secondaryColor = Color(0xFFEA580C),
        darkBaseColor = Color(0xFF431407)
    )

    private val PALETTE_PNB = CreditCardPalette(
        familyName = "PNB_AMBER",
        primaryColor = Color(0xFFB45309),
        secondaryColor = Color(0xFF854D0E),
        darkBaseColor = Color(0xFF3F2005)
    )

    private val PALETTE_CANARA = CreditCardPalette(
        familyName = "CANARA_BLUE",
        primaryColor = Color(0xFF0284C7),
        secondaryColor = Color(0xFF075985),
        darkBaseColor = Color(0xFF082F49)
    )

    private val PALETTE_YES = CreditCardPalette(
        familyName = "YES_BLUE",
        primaryColor = Color(0xFF2563EB),
        secondaryColor = Color(0xFF1D4ED8),
        darkBaseColor = Color(0xFF1E1B4B)
    )

    private val PALETTE_FEDERAL = CreditCardPalette(
        familyName = "FEDERAL_BLUE",
        primaryColor = Color(0xFF1D4ED8),
        secondaryColor = Color(0xFF1E3A8A),
        darkBaseColor = Color(0xFF0F172A)
    )

    private val PALETTE_RBL = CreditCardPalette(
        familyName = "RBL_INDIGO",
        primaryColor = Color(0xFF4338CA),
        secondaryColor = Color(0xFF3730A3),
        darkBaseColor = Color(0xFF1E1B4B)
    )

    private val PALETTE_SCB = CreditCardPalette(
        familyName = "SCB_EMERALD",
        primaryColor = Color(0xFF059669),
        secondaryColor = Color(0xFF047857),
        darkBaseColor = Color(0xFF064E3B)
    )

    private val PALETTE_AMEX = CreditCardPalette(
        familyName = "AMEX_STEEL",
        primaryColor = Color(0xFF0369A1),
        secondaryColor = Color(0xFF334155),
        darkBaseColor = Color(0xFF0F172A)
    )

    private val PALETTE_CITI = CreditCardPalette(
        familyName = "CITI_BLUE",
        primaryColor = Color(0xFF0284C7),
        secondaryColor = Color(0xFF1E40AF),
        darkBaseColor = Color(0xFF0F172A)
    )

    private val PALETTE_HSBC = CreditCardPalette(
        familyName = "HSBC_CRIMSON",
        primaryColor = Color(0xFFBE123C),
        secondaryColor = Color(0xFF881337),
        darkBaseColor = Color(0xFF1C1917)
    )

    private val PALETTE_DBS = CreditCardPalette(
        familyName = "DBS_RUBY",
        primaryColor = Color(0xFFE11D48),
        secondaryColor = Color(0xFF9F1239),
        darkBaseColor = Color(0xFF18181B)
    )

    // Sleek neutral fallback palettes for unrecognized banks
    private val NEUTRAL_FALLBACKS = listOf(
        CreditCardPalette(
            familyName = "SLATE_NEUTRAL",
            primaryColor = Color(0xFF475569),
            secondaryColor = Color(0xFF334155),
            darkBaseColor = Color(0xFF0F172A)
        ),
        CreditCardPalette(
            familyName = "CHARCOAL_NEUTRAL",
            primaryColor = Color(0xFF3F3F46),
            secondaryColor = Color(0xFF27272A),
            darkBaseColor = Color(0xFF09090B)
        ),
        CreditCardPalette(
            familyName = "DARK_BRONZE_NEUTRAL",
            primaryColor = Color(0xFF57534E),
            secondaryColor = Color(0xFF44403C),
            darkBaseColor = Color(0xFF1C1917)
        ),
        CreditCardPalette(
            familyName = "MIDNIGHT_STEEL_NEUTRAL",
            primaryColor = Color(0xFF334155),
            secondaryColor = Color(0xFF1E293B),
            darkBaseColor = Color(0xFF0B132B)
        ),
        CreditCardPalette(
            familyName = "OBSIDIAN_NEUTRAL",
            primaryColor = Color(0xFF374151),
            secondaryColor = Color(0xFF1F2937),
            darkBaseColor = Color(0xFF111827)
        )
    )

    /**
     * Resolves the visual color palette for a credit card.
     *
     * @param bankName Issuer or bank name
     * @param cardName Card product or display name (e.g. "Millennia", "Coral", "Flipkart")
     * @param last4Digits Card ending digits for stable fallback seed
     * @param cardId Stable identifier of the card for deterministic fallback
     * @param rawColour Optional stored colour string (never forces #7C3AED fallback)
     */
    fun resolvePalette(
        bankName: String?,
        cardName: String? = null,
        last4Digits: String? = null,
        cardId: String? = null,
        rawColour: String? = null
    ): CreditCardPalette {
        val combined = "${bankName.orEmpty()} ${cardName.orEmpty()}"
        var clean = combined.lowercase(Locale.ENGLISH)

        // Clean out noise and payment channel text
        clean = clean.replace(Regex("(?i)powered\\s+by.*"), " ")
        clean = clean.replace(Regex("(?i)banking\\s+partner.*"), " ")
        clean = clean.replace(Regex("(?i)psp\\b.*"), " ")
        clean = clean.replace(Regex("[^a-z0-9\\s]"), " ")
        clean = clean.replace(Regex("\\s+"), " ").trim()

        val tokens = clean.split(" ").filter { it.isNotEmpty() }

        // 1. Check known issuer families first (ensures core bank identity is preserved)
        val issuerPalette = when {
            // AU Small Finance Bank -> AU Orange family
            clean.contains("au bank") || clean.contains("au small") || clean.contains("au sfb") ||
                    clean.contains("ausfb") || clean.contains("au finance") || tokens.contains("au") -> PALETTE_AU

            // Axis Bank -> Axis Burgundy family
            clean.contains("axis") || clean.contains("uti bank") -> PALETTE_AXIS

            // ICICI Bank -> ICICI Red/Orange family
            clean.contains("icici") -> PALETTE_ICICI

            // IDFC FIRST Bank -> Maroon/Red family
            clean.contains("idfc") -> PALETTE_IDFC_FIRST

            // HDFC Bank -> Royal Blue / Red accent family
            clean.contains("hdfc") -> PALETTE_HDFC

            // IndusInd Bank -> Blue / Teal family
            clean.contains("indusind") || clean.contains("indus ind") || clean.contains("indus") -> PALETTE_INDUSIND

            // State Bank of India (SBI) -> Blue family
            clean.contains("sbi") || clean.contains("sbicard") ||
                    (clean.contains("state") && clean.contains("bank") && !clean.contains("united")) -> PALETTE_SBI

            // Kotak Mahindra Bank -> Red / Maroon family
            clean.contains("kotak") || tokens.contains("811") -> PALETTE_KOTAK

            // Bank of Baroda (BOB) -> Vermilion / Orange family
            clean.contains("baroda") || tokens.contains("bob") -> PALETTE_BOB

            // Punjab National Bank (PNB) -> Amber family
            clean.contains("pnb") || (clean.contains("punjab") && clean.contains("national")) -> PALETTE_PNB

            // Canara Bank -> Blue family
            clean.contains("canara") -> PALETTE_CANARA

            // YES Bank -> Blue family
            clean.contains("yes bank") || clean.contains("yesbank") || tokens.contains("yes") -> PALETTE_YES

            // Federal Bank -> Blue family
            clean.contains("federal") || clean.contains("fedbank") -> PALETTE_FEDERAL

            // RBL Bank -> Indigo family
            clean.contains("rbl") || clean.contains("ratnakar") -> PALETTE_RBL

            // Standard Chartered -> Emerald family
            clean.contains("standard chartered") || clean.contains("stan chart") || clean.contains("scb") -> PALETTE_SCB

            // American Express -> Steel Blue family
            clean.contains("amex") || clean.contains("american express") -> PALETTE_AMEX

            // Citi -> Blue family
            clean.contains("citi") -> PALETTE_CITI

            // HSBC -> Crimson family
            clean.contains("hsbc") || clean.contains("hongkong") -> PALETTE_HSBC

            // DBS Bank -> Ruby family
            clean.contains("dbs") || clean.contains("digibank") -> PALETTE_DBS

            else -> null
        }

        if (issuerPalette != null) {
            return issuerPalette
        }

        // 2. Check specific co-branded/product identities when issuer is not directly matched
        val productPalette = when {
            clean.contains("flipkart") -> PALETTE_AXIS_FLIPKART
            clean.contains("amazon") -> PALETTE_ICICI_AMAZON_PAY
            clean.contains("millennia") -> PALETTE_HDFC_MILLENNIA
            clean.contains("infinia") || clean.contains("regalia") || clean.contains("diners") -> PALETTE_HDFC_INFINIA
            clean.contains("zenith") -> PALETTE_AU_ZENITH
            clean.contains("cashback") || clean.contains("simplyclick") -> PALETTE_SBI_CASHBACK
            clean.contains("coral") || clean.contains("rubyx") || clean.contains("sapphiro") -> PALETTE_ICICI_CORAL
            clean.contains("airtel") -> PALETTE_AXIS_AIRTEL
            else -> null
        }
        if (productPalette != null) {
            return productPalette
        }

        // 2. Check if a non-default custom color was explicitly stored
        val trimmedColour = rawColour?.trim()
        if (!trimmedColour.isNullOrBlank() && !trimmedColour.equals("#7C3AED", ignoreCase = true)) {
            try {
                val parsed = Color(android.graphics.Color.parseColor(trimmedColour))
                return CreditCardPalette(
                    familyName = "CUSTOM",
                    primaryColor = parsed,
                    secondaryColor = parsed.copy(alpha = 0.8f),
                    darkBaseColor = Color(0xFF0F172A)
                )
            } catch (_: Exception) {
                // Ignore parse errors and fall through to deterministic fallback
            }
        }

        // 3. Fallback: Deterministic neutral palette based on stable identity
        val seed = sequenceOf(cardId, bankName, cardName, last4Digits)
            .filter { !it.isNullOrBlank() }
            .joinToString(separator = ":")
            .ifEmpty { "default_credit_card" }

        val index = abs(seed.hashCode()) % NEUTRAL_FALLBACKS.size
        return NEUTRAL_FALLBACKS[index]
    }

    /**
     * Resolves the card payment network (e.g. Visa, Mastercard, RuPay, Amex, Diners Club)
     * from card product or display name. Returns null if network is unspecified.
     */
    fun resolveNetwork(cardName: String?, rawText: String? = null): String? {
        val text = "${cardName.orEmpty()} ${rawText.orEmpty()}".lowercase(Locale.ENGLISH)
        return when {
            text.contains("visa") -> "VISA"
            text.contains("mastercard") || text.contains("master card") -> "MASTERCARD"
            text.contains("rupay") -> "RUPAY"
            text.contains("amex") || text.contains("american express") -> "AMEX"
            text.contains("diners") -> "DINERS CLUB"
            else -> null
        }
    }
}
