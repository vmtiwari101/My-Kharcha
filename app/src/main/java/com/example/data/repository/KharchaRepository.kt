package com.example.data.repository

import android.util.Log
import com.example.data.dao.KharchaDao
import com.example.data.dao.SafeDeleteResult
import com.example.data.dao.SplitOperationResult
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import com.example.data.firestore.FirestoreRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

internal interface AuthenticatedUidSource {
    val currentUid: String?
    val uidChanges: Flow<String?>
}

private object FirebaseAuthenticatedUidSource : AuthenticatedUidSource {
    override val currentUid: String?
        get() = try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (e: IllegalStateException) {
            Log.w("KharchaRepository", "Firebase authentication is unavailable", e)
            null
        }

    override val uidChanges: Flow<String?> = callbackFlow {
        val auth = try {
            FirebaseAuth.getInstance()
        } catch (e: IllegalStateException) {
            Log.w("KharchaRepository", "Firebase authentication is unavailable", e)
            trySend(null)
            close()
            return@callbackFlow
        }
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.uid)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()
}

class KharchaRepository internal constructor(
    private val dao: KharchaDao,
    private val firestoreRepository: FirestoreRepository,
    private val authenticatedUidSource: AuthenticatedUidSource
) {
    constructor(
        dao: KharchaDao,
        firestoreRepository: FirestoreRepository = FirestoreRepository()
    ) : this(dao, firestoreRepository, FirebaseAuthenticatedUidSource)

    companion object {
        private const val TAG = "KharchaRepository"
        private const val LEGACY_OWNER = "legacy:unassigned"
        private const val WRITE_REJECTED = -1L
        private const val CLEANUP_UNAVAILABLE = -1
    }

    private fun authenticatedUid(): String? =
        authenticatedUidSource.currentUid?.takeIf { it.isNotBlank() && it != LEGACY_OWNER }

    private fun requestedOwner(requestedUserId: String? = null): String? {
        val owner = authenticatedUid()
        if (owner == null) {
            Log.w(TAG, "User-owned operation rejected because no authenticated Firebase user is available")
            return null
        }
        if (requestedUserId != null && requestedUserId != owner) {
            Log.w(TAG, "User-owned operation rejected because the requested owner does not match the authenticated user")
            return null
        }
        return owner
    }

    private fun matchesDeleteOwner(owner: String, expectedOwner: String?): Boolean {
        if (expectedOwner == null || expectedOwner == owner) return true
        Log.w(TAG, "Deletion rejected because source ownership does not match the authenticated user")
        return false
    }

    private fun entityOwner(entityUserId: String, owner: String, entityType: String): String? {
        if (entityUserId.isBlank()) return owner
        if (entityUserId == owner && entityUserId != LEGACY_OWNER) return owner
        Log.w(TAG, "$entityType operation rejected because entity ownership does not match the authenticated user")
        return null
    }

    private fun <T> userFlow(
        query: (String) -> Flow<List<T>>
    ): Flow<List<T>> = authenticatedUidSource.uidChanges.flatMapLatest { candidateUid ->
        val owner = candidateUid?.takeIf { it.isNotBlank() && it != LEGACY_OWNER }
        if (owner == null) flowOf(emptyList()) else query(owner)
    }

    val allTransactions: Flow<List<TransactionEntity>> =
        userFlow(dao::getAllTransactionsForUser)
    val allCategories: Flow<List<CategoryEntity>> =
        userFlow(dao::getAllCategoriesForUser)
    val allSubcategories: Flow<List<SubcategoryEntity>> =
        userFlow(dao::getAllSubcategoriesForUser)
    val allAccounts: Flow<List<AccountEntity>> =
        userFlow(dao::getAllAccountsForUser)
    val allSplits: Flow<List<TransactionSplitEntity>> =
        userFlow(dao::getAllSplitsForUser)
    val allCards: Flow<List<CardEntity>> =
        userFlow(dao::getAllCardsForUser)

    suspend fun getAllCardsSync(): List<CardEntity> {
        val owner = requestedOwner() ?: return emptyList()
        return dao.getAllCardsSyncForUser(owner)
    }

    suspend fun insertCard(card: CardEntity) {
        val owner = requestedOwner() ?: return
        val entityOwner = entityOwner(card.userId, owner, "Card") ?: return
        val safeCard = if (card.userId.isBlank()) card.copy(userId = entityOwner) else card
        dao.insertCard(safeCard)
        try {
            if (firestoreRepository.currentUid != null) firestoreRepository.saveCard(safeCard, owner)
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore card sync skipped: ${e.message}")
        }
    }

    suspend fun deleteCard(id: String, userId: String? = null) {
        val owner = requestedOwner(userId) ?: return
        dao.deleteCard(owner, id)
        try {
            if (firestoreRepository.currentUid != null) firestoreRepository.deleteCard(id, owner)
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore card delete skipped: ${e.message}")
        }
    }

    suspend fun insertTransaction(tx: TransactionEntity): Long {
        val owner = requestedOwner() ?: return WRITE_REJECTED
        val entityOwner = entityOwner(tx.userId, owner, "Transaction") ?: return WRITE_REJECTED
        val safeTx = if (tx.userId.isBlank()) tx.copy(userId = entityOwner) else tx
        if (authenticatedUid() != owner) return WRITE_REJECTED
        val writtenTransactions = try {
            dao.upsertTransactionAndLinkedTransfer(owner, safeTx)
        } catch (e: Exception) {
            Log.w(TAG, "Transaction write rejected while preserving its linked transfer", e)
            null
        }
        if (writtenTransactions == null) {
            Log.w(TAG, "Transaction write rejected because it would invalidate its split set or ownership changed")
            return WRITE_REJECTED
        }
        val rowId = dao.getTransactionRowId(owner, safeTx.id) ?: return WRITE_REJECTED
        try {
            if (authenticatedUid() == owner && firestoreRepository.currentUid == owner) {
                writtenTransactions.forEach { firestoreRepository.saveTransaction(it, owner) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore transaction sync skipped: ${e.message}")
        }
        return rowId
    }

    suspend fun saveTransactionWithSplits(
        transaction: TransactionEntity,
        splits: List<TransactionSplitEntity>
    ): SplitOperationResult {
        val owner = requestedOwner() ?: return SplitOperationResult.NOT_AUTHENTICATED
        val entityOwner = entityOwner(transaction.userId, owner, "Transaction")
            ?: return SplitOperationResult.NOT_AUTHENTICATED
        val safeTransaction = if (transaction.userId.isBlank()) {
            transaction.copy(userId = entityOwner)
        } else {
            transaction
        }
        if (splits.any { it.userId.isNotBlank() && it.userId != owner }) {
            Log.w(TAG, "Transaction split write rejected because a split belongs to another user")
            return SplitOperationResult.INVALID_REFERENCE
        }
        val safeSplits = splits.map { split ->
            if (split.transactionId != safeTransaction.id) {
                Log.w(TAG, "Transaction split write rejected because a split references a different parent")
                return SplitOperationResult.INVALID_REFERENCE
            }
            split.copy(userId = owner)
        }
        if (authenticatedUid() != owner) return SplitOperationResult.NOT_AUTHENTICATED
        val result = dao.insertTransactionWithSplits(owner, safeTransaction, safeSplits)
        if (result != SplitOperationResult.SAVED) return result
        try {
            if (authenticatedUid() == owner && firestoreRepository.currentUid == owner) {
                firestoreRepository.saveTransaction(safeTransaction, owner)
                if (!firestoreRepository.saveTransactionSplitsBatch(safeSplits, owner)) {
                    Log.w(TAG, "Firestore transaction split sync did not complete")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore transaction and split sync skipped: ${e.message}")
        }
        return result
    }

    suspend fun updateTransactionWithSplits(
        transaction: TransactionEntity,
        splits: List<TransactionSplitEntity>
    ): SplitOperationResult {
        val owner = requestedOwner() ?: return SplitOperationResult.NOT_AUTHENTICATED
        val entityOwner = entityOwner(transaction.userId, owner, "Transaction")
            ?: return SplitOperationResult.NOT_AUTHENTICATED
        val safeTransaction = if (transaction.userId.isBlank()) {
            transaction.copy(userId = entityOwner)
        } else {
            transaction
        }
        if (splits.any { it.userId.isNotBlank() && it.userId != owner }) {
            Log.w(TAG, "Transaction split update rejected because a split belongs to another user")
            return SplitOperationResult.INVALID_REFERENCE
        }
        val safeSplits = splits.map { split ->
            if (split.transactionId != safeTransaction.id) {
                Log.w(TAG, "Transaction split update rejected because a split references a different parent")
                return SplitOperationResult.INVALID_REFERENCE
            }
            split.copy(userId = owner)
        }
        if (authenticatedUid() != owner) return SplitOperationResult.NOT_AUTHENTICATED
        val oldSplits = dao.getSplitsForTransactionSync(owner, safeTransaction.id)
        val result = dao.updateTransactionAndSplitsAndLinkedTransfer(owner, safeTransaction, safeSplits)
        if (result != SplitOperationResult.SAVED) return result
        try {
            if (authenticatedUid() == owner && firestoreRepository.currentUid == owner) {
                val oldIds = oldSplits.map { it.id }.toSet()
                val newIds = safeSplits.mapTo(mutableSetOf()) { it.id }
                if (!firestoreRepository.deleteTransactionSplits(oldIds - newIds, owner)) {
                    Log.w(TAG, "Firestore stale transaction split cleanup did not complete")
                }
                if (!firestoreRepository.saveTransaction(safeTransaction, owner)) {
                    Log.w(TAG, "Firestore transaction update did not complete")
                }
                if (!firestoreRepository.saveTransactionSplitsBatch(safeSplits, owner)) {
                    Log.w(TAG, "Firestore transaction split sync did not complete")
                }
                val updated = dao.getTransactionByIdSync(owner, safeTransaction.id)
                if (updated != null) {
                    val linkedPeer = dao.getAllTransactionsSyncForUser(owner).firstOrNull { peer ->
                        peer.id != updated.id &&
                            peer.userId == owner &&
                            peer.transferGroupId == updated.transferGroupId &&
                            peer.accountId == updated.counterpartyAccountId &&
                            peer.counterpartyAccountId == updated.accountId
                    }
                    if (linkedPeer != null) {
                        if (!firestoreRepository.saveTransaction(linkedPeer, owner)) {
                            Log.w(TAG, "Firestore linked transfer update did not complete")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore transaction and split update skipped: ${e.message}")
        }
        return result
    }

    suspend fun deleteTransaction(id: String, userId: String? = null): Boolean {
        val owner = requestedOwner(userId) ?: return false
        if (authenticatedUid() != owner) return false
        val ownerTransactions = dao.getAllTransactionsSyncForUser(owner).filter { it.userId == owner }
        val target = ownerTransactions.firstOrNull { it.id == id } ?: return false
        val linked = ownerTransactions.firstOrNull { peer ->
            peer.id != target.id &&
                !target.transferGroupId.isNullOrBlank() &&
                peer.transferGroupId == target.transferGroupId &&
                peer.accountId == target.counterpartyAccountId &&
                peer.counterpartyAccountId == target.accountId
        }
        val relatedTransactions = listOfNotNull(target, linked)
        val splitIdsByTransaction = relatedTransactions.associate { tx ->
            tx.id to dao.getSplitsForTransactionSync(owner, tx.id).mapTo(mutableSetOf()) { it.id }
        }
        val deletedTransactions = dao.deleteTransactionAndLinkedTransfer(owner, id) ?: return false
        if (authenticatedUid() != owner) return true
        try {
            if (firestoreRepository.currentUid == owner) {
                deletedTransactions.forEach { tx ->
                    val splitIds = splitIdsByTransaction[tx.id].orEmpty()
                    if (splitIds.isNotEmpty()) firestoreRepository.deleteTransactionSplits(splitIds, owner)
                    firestoreRepository.deleteTransaction(tx.id, owner)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore transaction delete skipped: ${e.message}")
        }
        return true
    }

    suspend fun insertSplitsForTransaction(
        txId: String,
        splits: List<TransactionSplitEntity>
    ): SplitOperationResult {
        val owner = requestedOwner() ?: return SplitOperationResult.NOT_AUTHENTICATED
        if (splits.any { it.userId.isNotBlank() && it.userId != owner }) {
            Log.w(TAG, "Transaction split write rejected because a split belongs to another user")
            return SplitOperationResult.INVALID_REFERENCE
        }
        val oldSplits = dao.getSplitsForTransactionSync(owner, txId)
        val safeSplits = splits.map { split ->
            if (split.transactionId != txId) {
                Log.w(TAG, "Transaction split write rejected because a split references a different parent")
                return SplitOperationResult.INVALID_REFERENCE
            }
            split.copy(userId = owner)
        }
        if (authenticatedUid() != owner) return SplitOperationResult.NOT_AUTHENTICATED
        val result = dao.replaceTransactionSplits(owner, txId, safeSplits)
        if (result == SplitOperationResult.SAVED &&
            authenticatedUid() == owner && firestoreRepository.currentUid == owner
        ) {
            try {
                val oldIds = oldSplits.map { it.id }.toSet()
                val newIds = safeSplits.mapTo(mutableSetOf()) { it.id }
                if (!firestoreRepository.deleteTransactionSplits(oldIds - newIds, owner)) {
                    Log.w(TAG, "Firestore stale transaction split cleanup did not complete")
                }
                if (!firestoreRepository.saveTransactionSplitsBatch(safeSplits, owner)) {
                    Log.w(TAG, "Firestore transaction split sync did not complete")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Firestore transaction split sync skipped: ${e.message}")
            }
        }
        return result
    }

    suspend fun deleteSplitsForTransaction(txId: String, userId: String? = null): SplitOperationResult {
        val owner = requestedOwner(userId) ?: return SplitOperationResult.NOT_AUTHENTICATED
        if (authenticatedUid() != owner) return SplitOperationResult.NOT_AUTHENTICATED
        val oldSplits = dao.getSplitsForTransactionSync(owner, txId)
        if (!dao.removeTransactionSplits(owner, txId)) return SplitOperationResult.NOT_FOUND
        try {
            if (authenticatedUid() == owner &&
                firestoreRepository.currentUid == owner &&
                oldSplits.isNotEmpty()
            ) {
                if (!firestoreRepository.deleteTransactionSplits(oldSplits.map { it.id }.toSet(), owner)) {
                    Log.w(TAG, "Firestore transaction split deletion did not complete")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore transaction split delete skipped: ${e.message}")
        }
        return SplitOperationResult.SAVED
    }

    suspend fun insertCategory(cat: CategoryEntity) {
        val owner = requestedOwner() ?: return
        val entityOwner = entityOwner(cat.userId, owner, "Category") ?: return
        val safeCat = if (cat.userId.isBlank()) cat.copy(userId = entityOwner) else cat
        dao.insertCategory(safeCat)
        try {
            if (firestoreRepository.currentUid != null) firestoreRepository.saveCategory(safeCat, owner)
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore category sync skipped: ${e.message}")
        }
    }

    suspend fun getTransactionCountForCategory(categoryId: String): Int {
        val owner = requestedOwner() ?: return 0
        return dao.getTransactionCountForCategory(owner, categoryId) +
            dao.getSplitCountForCategory(owner, categoryId)
    }

    suspend fun mergeCategory(sourceCategoryId: String, targetCategoryId: String, updatedAt: String) {
        val owner = requestedOwner() ?: return
        val categories = dao.getAllCategoriesSyncForUser(owner)
        if (categories.none { it.id == sourceCategoryId } ||
            categories.none { it.id == targetCategoryId }
        ) {
            Log.w(TAG, "Category merge rejected because source or target is not owned by the authenticated user")
            return
        }
        dao.reassignTransactionsCategory(owner, sourceCategoryId, targetCategoryId, updatedAt)
        dao.reassignSplitsCategory(owner, sourceCategoryId, targetCategoryId)
        dao.reassignSubcategoriesCategory(owner, sourceCategoryId, targetCategoryId, updatedAt)
        dao.deleteCategory(owner, sourceCategoryId)
        try {
            if (firestoreRepository.currentUid != null) firestoreRepository.deleteCategory(sourceCategoryId, owner)
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore category merge delete skipped: ${e.message}")
        }
    }

    suspend fun checkCategoryDeleteStatus(
        id: String,
        expectedOwner: String? = null
    ): SafeDeleteResult {
        val owner = requestedOwner() ?: return SafeDeleteResult.NOT_AUTHENTICATED
        if (!matchesDeleteOwner(owner, expectedOwner)) return SafeDeleteResult.NOT_FOUND
        val status = dao.checkCategoryDeleteStatus(owner, id)
        return if (authenticatedUid() == owner) status else SafeDeleteResult.NOT_AUTHENTICATED
    }

    suspend fun deleteCategory(
        id: String,
        expectedOwner: String? = null
    ): SafeDeleteResult {
        val owner = requestedOwner() ?: return SafeDeleteResult.NOT_AUTHENTICATED
        if (!matchesDeleteOwner(owner, expectedOwner)) return SafeDeleteResult.NOT_FOUND
        if (authenticatedUid() != owner) return SafeDeleteResult.NOT_AUTHENTICATED

        val result = dao.deleteCategoryIfUnused(owner, id)
        if (result != SafeDeleteResult.DELETED) return result

        if (authenticatedUid() == owner && firestoreRepository.currentUid == owner) {
            try {
                firestoreRepository.deleteCategory(id, owner)
            } catch (e: Throwable) {
                Log.w(TAG, "Firestore category delete skipped: ${e.message}")
            }
        }
        return result
    }

    suspend fun insertSubcategory(sub: SubcategoryEntity) {
        val owner = requestedOwner() ?: return
        val entityOwner = entityOwner(sub.userId, owner, "Subcategory") ?: return
        if (dao.getAllCategoriesSyncForUser(owner).none { it.id == sub.categoryId }) {
            Log.w(TAG, "Subcategory write rejected because its category is not owned by the authenticated user")
            return
        }
        if (authenticatedUid() != owner) {
            Log.w(TAG, "Subcategory write rejected because the authenticated user changed before the update")
            return
        }
        val safeSub = if (sub.userId.isBlank()) sub.copy(userId = entityOwner) else sub
        dao.insertSubcategory(safeSub)
        try {
            if (authenticatedUid() == owner && firestoreRepository.currentUid == owner) {
                firestoreRepository.saveSubcategory(safeSub, owner)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore subcategory sync skipped: ${e.message}")
        }
    }

    suspend fun checkSubcategoryDeleteStatus(
        id: String,
        expectedOwner: String? = null
    ): SafeDeleteResult {
        val owner = requestedOwner() ?: return SafeDeleteResult.NOT_AUTHENTICATED
        if (!matchesDeleteOwner(owner, expectedOwner)) return SafeDeleteResult.NOT_FOUND
        val status = dao.checkSubcategoryDeleteStatus(owner, id)
        return if (authenticatedUid() == owner) status else SafeDeleteResult.NOT_AUTHENTICATED
    }

    suspend fun deleteSubcategory(
        id: String,
        expectedOwner: String? = null
    ): SafeDeleteResult {
        val owner = requestedOwner() ?: return SafeDeleteResult.NOT_AUTHENTICATED
        if (!matchesDeleteOwner(owner, expectedOwner)) return SafeDeleteResult.NOT_FOUND
        if (authenticatedUid() != owner) return SafeDeleteResult.NOT_AUTHENTICATED

        val result = dao.deleteSubcategoryIfUnused(owner, id)
        if (result != SafeDeleteResult.DELETED) return result

        if (authenticatedUid() == owner && firestoreRepository.currentUid == owner) {
            try {
                firestoreRepository.deleteSubcategory(id, owner)
            } catch (e: Throwable) {
                Log.w(TAG, "Firestore subcategory delete skipped: ${e.message}")
            }
        }
        return result
    }

    suspend fun moveSubcategory(
        subcategoryId: String,
        newCategoryId: String,
        updatedAt: String,
        moveTransactions: Boolean = false
    ) {
        val owner = requestedOwner() ?: return
        val subcategory = dao.getAllSubcategoriesSyncForUser(owner)
            .firstOrNull { it.id == subcategoryId }
            ?: return
        if (subcategory.categoryId == newCategoryId ||
            dao.getAllCategoriesSyncForUser(owner).none { it.id == newCategoryId }
        ) {
            Log.w(TAG, "Subcategory move rejected because target category is not owned by the authenticated user")
            return
        }
        if (authenticatedUid() != owner) {
            Log.w(TAG, "Subcategory move rejected because the authenticated user changed before the update")
            return
        }

        val updatedSubcategory = subcategory.copy(categoryId = newCategoryId, updatedAt = updatedAt)
        val moved = dao.moveSubcategoryForUser(
            userId = owner,
            subcategoryId = subcategoryId,
            newCategoryId = newCategoryId,
            updatedAt = updatedAt,
            moveExistingTransactions = moveTransactions
        )
        if (!moved) {
            Log.w(TAG, "Subcategory move rejected because its source or destination is no longer valid")
            return
        }
        try {
            if (authenticatedUid() == owner && firestoreRepository.currentUid == owner) {
                firestoreRepository.saveSubcategory(updatedSubcategory, owner)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore move subcategory sync skipped: ${e.message}")
        }
    }

    suspend fun insertAccount(acc: AccountEntity) {
        val owner = requestedOwner() ?: return
        val entityOwner = entityOwner(acc.userId, owner, "Account") ?: return
        val safeAccount = if (acc.userId.isBlank()) acc.copy(userId = entityOwner) else acc
        if (authenticatedUid() != owner) return
        val existing = dao.getAllAccountsSyncForUser(owner)
            .firstOrNull { it.id == safeAccount.id && it.userId == owner }
        if (safeAccount.type.equals("Credit Card", ignoreCase = true) &&
            existing?.type.equals("Credit Card", ignoreCase = true) &&
            existing?.outstandingAmount != safeAccount.outstandingAmount
        ) {
            if (authenticatedUid() != owner) return
            if (!dao.saveCreditCardBalanceSnapshot(owner, safeAccount)) {
                Log.w(TAG, "Credit card balance snapshot rejected because ownership changed")
                return
            }
        } else {
            if (authenticatedUid() != owner) return
            dao.insertAccount(safeAccount)
        }
        try {
            if (authenticatedUid() == owner && firestoreRepository.currentUid == owner) {
                firestoreRepository.saveAccount(safeAccount, owner)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore account sync skipped: ${e.message}")
        }
    }

    suspend fun deleteAccount(id: String, userId: String? = null) {
        val owner = requestedOwner(userId) ?: return
        dao.deleteAccount(owner, id)
        try {
            if (firestoreRepository.currentUid != null) firestoreRepository.deleteAccount(id, owner)
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore account delete skipped: ${e.message}")
        }
    }

    suspend fun getCategoryCount(): Int {
        val owner = requestedOwner() ?: return 0
        return dao.getCategoryCountForUser(owner)
    }

    suspend fun cleanupDuplicateTransactions(): Int {
        requestedOwner() ?: return CLEANUP_UNAVAILABLE
        Log.w(TAG, "Duplicate cleanup is disabled until an owner-scoped ingestion implementation is available")
        return CLEANUP_UNAVAILABLE
    }
}
