package com.example.data.firestore

/**
 * User-scoped Cloud Firestore path constants and helper functions.
 * Structure:
 *   users/{uid}
 *   users/{uid}/profile/info
 *   users/{uid}/accounts/{accountId}
 *   users/{uid}/cards/{cardId}
 *   users/{uid}/transactions/{transactionId}
 *   users/{uid}/transaction_splits/{splitId}
 *   users/{uid}/categories/{categoryId}
 *   users/{uid}/subcategories/{subcategoryId}
 *   users/{uid}/merchants/{merchantId}
 *   users/{uid}/settings/{settingId}
 */
object FirestorePaths {

    fun userDocumentPath(uid: String): String = "users/$uid"

    fun profileCollectionPath(uid: String): String = "users/$uid/profile"

    fun profileDocumentPath(uid: String): String = "users/$uid/profile/info"

    fun accountsCollectionPath(uid: String): String = "users/$uid/accounts"

    fun accountDocumentPath(uid: String, accountId: String): String = "users/$uid/accounts/$accountId"

    fun cardsCollectionPath(uid: String): String = "users/$uid/cards"

    fun cardDocumentPath(uid: String, cardId: String): String = "users/$uid/cards/$cardId"

    fun transactionsCollectionPath(uid: String): String = "users/$uid/transactions"

    fun transactionDocumentPath(uid: String, transactionId: String): String = "users/$uid/transactions/$transactionId"

    fun transactionSplitsCollectionPath(uid: String): String = "users/$uid/transaction_splits"

    fun transactionSplitDocumentPath(uid: String, splitId: String): String = "users/$uid/transaction_splits/$splitId"

    fun categoriesCollectionPath(uid: String): String = "users/$uid/categories"

    fun categoryDocumentPath(uid: String, categoryId: String): String = "users/$uid/categories/$categoryId"

    fun subcategoriesCollectionPath(uid: String): String = "users/$uid/subcategories"

    fun subcategoryDocumentPath(uid: String, subcategoryId: String): String = "users/$uid/subcategories/$subcategoryId"

    fun merchantsCollectionPath(uid: String): String = "users/$uid/merchants"

    fun merchantDocumentPath(uid: String, merchantId: String): String = "users/$uid/merchants/$merchantId"

    fun settingsCollectionPath(uid: String): String = "users/$uid/settings"

    fun settingsDocumentPath(uid: String, settingId: String = "app_preferences"): String = "users/$uid/settings/$settingId"
}
