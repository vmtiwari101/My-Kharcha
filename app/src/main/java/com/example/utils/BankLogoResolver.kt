package com.example.utils

import com.example.R
import java.util.Locale

/**
 * Universal Central Bank Logo Registry & Resolver.
 * Normalizes all variations of bank names, account names, and credit card issuers
 * to their real official vector drawable resources.
 */
object BankLogoResolver {

    /**
     * Resolves the official vector drawable resource ID for a given bank or issuer name.
     * Returns null for genuinely unknown banks so that the UI can display a clean initials fallback.
     *
     * @param bankName Name of the bank or account (e.g. "State Bank of India", "HDFC Bank Ltd", "AU Small Finance Bank")
     * @param actualIssuer Explicit issuer identity if already resolved (e.g., from AccountEntity/CardEntity)
     */
    fun getLogo(bankName: String?, actualIssuer: String? = null): Int? {
        val rawInput = actualIssuer?.trim()?.ifEmpty { null }
            ?: bankName?.trim()?.ifEmpty { null }
            ?: return null

        var clean = rawInput.lowercase(Locale.US)

        // Strict Rule: Do NOT identify a bank from "Powered by Axis Bank" or payment infrastructure
        clean = clean.replace(Regex("(?i)powered\\s+by.*"), " ")
        clean = clean.replace(Regex("(?i)banking\\s+partner.*"), " ")
        clean = clean.replace(Regex("(?i)psp\\b.*"), " ")
        clean = clean.replace(Regex("[^a-z0-9\\s]"), " ")
        clean = clean.replace(Regex("\\s+"), " ").trim()

        if (clean.isEmpty()) return null

        val tokens = clean.split(" ").filter { it.isNotEmpty() }

        return when {
            // State Bank of India (SBI)
            clean.contains("sbi") || (clean.contains("state") && clean.contains("bank") && !clean.contains("united")) -> R.drawable.ic_bank_sbi

            // HDFC Bank
            clean.contains("hdfc") -> R.drawable.ic_bank_hdfc

            // ICICI Bank
            clean.contains("icici") -> R.drawable.ic_bank_icici

            // AU Small Finance Bank
            clean.contains("au bank") || clean.contains("au small") || clean.contains("au sfb") ||
                    clean.contains("ausfb") || clean.contains("au finance") || tokens.contains("au") -> R.drawable.ic_bank_au

            // Axis Bank (UTI Bank) - only when Axis is the actual bank, not from "Powered by Axis"
            clean.contains("axis") || clean.contains("uti bank") -> R.drawable.ic_bank_axis

            // IDFC FIRST Bank
            clean.contains("idfc") -> R.drawable.ic_bank_idfc

            // Kotak Mahindra Bank
            clean.contains("kotak") || tokens.contains("811") -> R.drawable.ic_bank_kotak

            // Punjab National Bank (PNB)
            clean.contains("pnb") || (clean.contains("punjab") && clean.contains("national")) -> R.drawable.ic_bank_pnb

            // Bank of Baroda (BOB)
            clean.contains("baroda") || tokens.contains("bob") -> R.drawable.ic_bank_bob

            // Bank of India (BOI) - not state/central/union
            (clean.contains("bank of india") || tokens.contains("boi")) &&
                    !clean.contains("state") && !clean.contains("central") && !clean.contains("union") -> R.drawable.ic_bank_boi

            // Canara Bank
            clean.contains("canara") -> R.drawable.ic_bank_canara

            // Central Bank of India (CBI)
            clean.contains("central bank") || (tokens.contains("cbi") && !clean.contains("investigation")) -> R.drawable.ic_bank_cbi

            // Indian Overseas Bank (IOB)
            clean.contains("indian overseas") || clean.contains("overseas") || tokens.contains("iob") -> R.drawable.ic_bank_iob

            // Indian Bank
            (clean.contains("indian bank") && !clean.contains("south") && !clean.contains("overseas")) || (clean.contains("indian") && !clean.contains("overseas") && !clean.contains("south")) -> R.drawable.ic_bank_indian

            // City Union Bank (CUB) - checked before Union Bank
            clean.contains("city union") || tokens.contains("cub") || clean == "cub" || clean.contains("cub bank") -> R.drawable.ic_bank_city

            // Union Bank of India (UBI)
            (clean.contains("union bank") && !clean.contains("city")) || (tokens.contains("union") && !clean.contains("city")) || tokens.contains("ubi") -> R.drawable.ic_bank_union

            // UCO Bank
            clean.contains("uco") -> R.drawable.ic_bank_uco

            // Bank of Maharashtra (BOM)
            clean.contains("maharashtra") || tokens.contains("bom") -> R.drawable.ic_bank_bom

            // YES Bank
            clean.contains("yes bank") || clean.contains("yesbank") || tokens.contains("yes") -> R.drawable.ic_bank_yes

            // IndusInd Bank
            clean.contains("indusind") || clean.contains("indus ind") || clean.contains("indus") -> R.drawable.ic_bank_indusind

            // Federal Bank
            clean.contains("federal") || clean.contains("fedbank") -> R.drawable.ic_bank_federal

            // RBL Bank (Ratnakar Bank)
            clean.contains("rbl") || clean.contains("ratnakar") -> R.drawable.ic_bank_rbl

            // Bandhan Bank
            clean.contains("bandhan") -> R.drawable.ic_bank_bandhan

            // IDBI Bank
            clean.contains("idbi") -> R.drawable.ic_bank_idbi

            // South Indian Bank (SIB)
            clean.contains("south indian") || tokens.contains("sib") -> R.drawable.ic_bank_sib

            // Karnataka Bank
            clean.contains("karnataka") -> R.drawable.ic_bank_karnataka

            // Jammu & Kashmir Bank (J&K)
            clean.contains("jammu") || clean.contains("kashmir") || clean.contains("j&k") || clean.contains("jnk") -> R.drawable.ic_bank_jnk

            // DCB Bank (Development Credit Bank)
            clean.contains("dcb") || clean.contains("development credit") -> R.drawable.ic_bank_dcb

            // CSB Bank (Catholic Syrian Bank)
            clean.contains("csb") || clean.contains("catholic syrian") -> R.drawable.ic_bank_csb

            // Dhanlaxmi Bank
            clean.contains("dhanlaxmi") || clean.contains("dhanalakshmi") -> R.drawable.ic_bank_dhanlaxmi

            // Punjab & Sind Bank (PSB)
            clean.contains("sind") || tokens.contains("psb") -> R.drawable.ic_bank_psb

            // Tamilnad Mercantile Bank (TMB)
            clean.contains("tamilnad") || clean.contains("mercantile") || tokens.contains("tmb") -> R.drawable.ic_bank_tmb

            // ESAF Small Finance Bank
            clean.contains("esaf") -> R.drawable.ic_bank_esaf

            // Ujjivan Small Finance Bank
            clean.contains("ujjivan") -> R.drawable.ic_bank_ujjivan

            // Fino Payments Bank
            clean.contains("fino") -> R.drawable.ic_bank_fino

            // Airtel Payments Bank
            clean.contains("airtel") || tokens.contains("apb") -> R.drawable.ic_bank_airtel

            // India Post Payments Bank (IPPB)
            clean.contains("india post") || clean.contains("post office") || tokens.contains("ippb") -> R.drawable.ic_bank_ippb

            // Jio Payments Bank
            clean.contains("jio") -> R.drawable.ic_bank_jio

            // Paytm Payments Bank
            clean.contains("paytm") -> R.drawable.ic_bank_paytm

            // Karur Vysya Bank (KVB)
            clean.contains("karur") || clean.contains("vysya") || tokens.contains("kvb") -> R.drawable.ic_bank_kvb

            // Nainital Bank
            clean.contains("nainital") || tokens.contains("ntb") -> R.drawable.ic_bank_ntb

            // Global / International Banks
            clean.contains("citi") -> R.drawable.ic_bank_citi
            clean.contains("hsbc") || clean.contains("hongkong") -> R.drawable.ic_bank_hsbc
            clean.contains("standard chartered") || clean.contains("stan chart") || clean.contains("scb") -> R.drawable.ic_bank_standard_chartered
            clean.contains("dbs") || clean.contains("digibank") -> R.drawable.ic_bank_dbs
            clean.contains("barclays") -> R.drawable.ic_bank_barclays
            clean.contains("amex") || clean.contains("american express") -> R.drawable.ic_bank_amex
            clean.contains("chase") || clean.contains("jpmorgan") || clean.contains("jp morgan") -> R.drawable.ic_bank_chase
            clean.contains("bank of america") || clean.contains("bofa") -> R.drawable.ic_bank_boa

            else -> null
        }
    }
}
