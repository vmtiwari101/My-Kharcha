package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.entity.AccountEntity
import com.example.data.entity.CardEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.data.entity.TransactionSplitEntity
import kotlinx.coroutines.flow.Flow

enum class SafeDeleteResult {
    AVAILABLE,
    DELETED,
    IN_USE,
    NOT_FOUND,
    NOT_AUTHENTICATED
}

enum class SplitOperationResult {
    SAVED,
    DUPLICATE,
    INVALID_TOTAL,
    INVALID_AMOUNT,
    INVALID_REFERENCE,
    NOT_FOUND,
    NOT_AUTHENTICATED
}

@Dao
interface KharchaDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC, time DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE userId = :userId ORDER BY date DESC, time DESC")
    fun getAllTransactionsForUser(userId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions")
    suspend fun getAllTransactionsSync(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE userId = :userId")
    suspend fun getAllTransactionsSyncForUser(userId: String): List<TransactionEntity>

    @Transaction
    suspend fun insertTransaction(transaction: TransactionEntity): Long {
        if (transaction.userId.isNotBlank() && transaction.userId != "legacy:unassigned") {
            val result = upsertTransactionPreservingSplits(transaction.userId, transaction)
            return if (result == SplitOperationResult.SAVED) {
                getTransactionRowId(transaction.userId, transaction.id) ?: -1L
            } else {
                -1L
            }
        }

        val existing = getTransactionByIdSync(transaction.userId, transaction.id)
        if (existing != null) {
            if (getSplitsForTransactionSync(transaction.userId, transaction.id).isNotEmpty()) return -1L
            return if (updateTransactionInPlace(transaction) == 1) {
                getTransactionRowId(transaction.userId, transaction.id) ?: -1L
            } else {
                -1L
            }
        }
        val rowId = insertTransactionIfAbsent(transaction)
        return if (rowId >= 0L) rowId else -1L
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransactionStrict(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactionIfAbsent(transaction: TransactionEntity): Long

    @Update
    suspend fun updateTransactionInPlace(transaction: TransactionEntity): Int

    @Transaction
    suspend fun upsertTransactionPreservingSplits(
        userId: String,
        transaction: TransactionEntity
    ): SplitOperationResult {
        if (userId.isBlank() || userId == "legacy:unassigned" || transaction.userId != userId) {
            return SplitOperationResult.NOT_AUTHENTICATED
        }
        val existing = getTransactionByIdSync(userId, transaction.id)
        if (existing == null) {
            return if (insertTransactionIfAbsent(transaction) >= 0L) {
                updateCardPaymentContribution(userId, null, transaction)
                SplitOperationResult.SAVED
            } else {
                SplitOperationResult.DUPLICATE
            }
        }

        val existingSplits = getSplitsForTransactionSync(userId, transaction.id)
        if (existingSplits.isNotEmpty()) {
            val validation = validateSplitSet(
                userId,
                transaction.id,
                transaction.amount,
                transaction.categoryId,
                transaction.subcategoryId,
                existingSplits,
                true
            )
            if (validation != SplitOperationResult.SAVED) return validation
        }
        return if (updateTransactionInPlace(transaction) == 1) {
            updateCardPaymentContribution(userId, existing, transaction)
            SplitOperationResult.SAVED
        } else {
            SplitOperationResult.NOT_FOUND
        }
    }

    @Transaction
    suspend fun upsertTransactionAndLinkedTransfer(
        userId: String,
        transaction: TransactionEntity
    ): List<TransactionEntity>? {
        if (userId.isBlank() || userId == "legacy:unassigned" || transaction.userId != userId) return null
        val existing = getTransactionByIdSync(userId, transaction.id)
        if (existing != null &&
            (isTransferRecord(existing) || isTransferRecord(transaction)) &&
            (!isOwnedAccountOrCard(userId, transaction.accountId) ||
                transaction.counterpartyAccountId?.let { !isOwnedAccountOrCard(userId, it) } == true)
        ) return null
        val linked = existing?.let { current ->
            val groupId = current.transferGroupId
            val counterpartyId = current.counterpartyAccountId
            if (!isTransferRecord(current) || groupId.isNullOrBlank() || counterpartyId.isNullOrBlank()) null
            else getAllTransactionsSyncForUser(userId).firstOrNull { peer ->
                peer.id != current.id &&
                    isTransferRecord(peer) &&
                    peer.transferGroupId == groupId &&
                    peer.accountId == counterpartyId &&
                    peer.counterpartyAccountId == current.accountId &&
                    peer.userId == userId
            }
        }
        if (linked != null &&
            (!isTransferRecord(transaction) ||
                transaction.transferGroupId != existing?.transferGroupId)
        ) return null

        val linkedAccountId = if (linked == null) null
        else transaction.counterpartyAccountId ?: linked.accountId
        if (linkedAccountId != null &&
            linkedAccountId != linked?.accountId &&
            getAccountCountForUser(userId, linkedAccountId) != 1
        ) return null
        val primaryWrite = if (linked == null) transaction else transaction.copy(
            type = "INTERNAL_TRANSFER",
            isInternalTransfer = true,
            isExpense = false,
            transferGroupId = existing?.transferGroupId,
            counterpartyAccountId = linkedAccountId
        )
        val updatedLinked = linked?.copy(
            type = "INTERNAL_TRANSFER",
            accountId = linkedAccountId ?: linked.accountId,
            amount = transaction.amount,
            date = transaction.date,
            time = transaction.time,
            transactionType = transaction.transactionType ?: linked.transactionType,
            direction = if (transaction.direction.equals("DEBIT", true)) "CREDIT" else "DEBIT",
            isInternalTransfer = true,
            isExpense = false,
            transferGroupId = transaction.transferGroupId,
            counterpartyAccountId = transaction.accountId,
            needsReview = transaction.needsReview,
            updatedAt = transaction.updatedAt
        )

        if (!canKeepExistingSplits(userId, transaction)) return null
        if (updatedLinked != null && !canKeepExistingSplits(userId, updatedLinked)) return null

        val writes = listOfNotNull(primaryWrite, updatedLinked)
        val previousById = writes.associate { write ->
            write.id to getTransactionByIdSync(userId, write.id)
        }
        for (write in writes) {
            val current = getTransactionByIdSync(userId, write.id)
            if (current == null) {
                check(insertTransactionIfAbsent(write) >= 0L) { "Transfer transaction insert conflicted" }
            } else {
                check(updateTransactionInPlace(write) == 1) { "Transfer transaction update failed" }
            }
        }
        return writes.map { write ->
            updateCardPaymentContribution(userId, previousById[write.id], write)
        }
    }

    @Transaction
    suspend fun saveLinkedTransferPair(
        userId: String,
        first: TransactionEntity,
        second: TransactionEntity
    ): Boolean {
        if (userId.isBlank() || userId == "legacy:unassigned" ||
            first.userId != userId || second.userId != userId ||
            first.id == second.id ||
            first.transferGroupId.isNullOrBlank() ||
            first.transferGroupId != second.transferGroupId ||
            first.accountId.isBlank() || second.accountId.isBlank() ||
            first.accountId == second.accountId ||
            first.counterpartyAccountId != second.accountId ||
            second.counterpartyAccountId != first.accountId ||
            !isTransferRecord(first) || !isTransferRecord(second) ||
            first.direction != "DEBIT" && first.direction != "CREDIT" ||
            second.direction != (if (first.direction == "DEBIT") "CREDIT" else "DEBIT") ||
            kotlin.math.abs(first.amount - second.amount) >= 0.01
        ) return false

        if (!isOwnedAccountOrCard(userId, first.accountId) ||
            !isOwnedAccountOrCard(userId, second.accountId)
        ) return false
        if (!canKeepExistingSplits(userId, first) || !canKeepExistingSplits(userId, second)) return false
        val previousById = listOf(first, second).associate { transaction ->
            transaction.id to getTransactionByIdSync(userId, transaction.id)
        }
        for (transaction in listOf(first, second)) {
            val existing = getTransactionByIdSync(userId, transaction.id)
            if (existing == null) {
                check(insertTransactionIfAbsent(transaction) >= 0L) { "Transfer side insert conflicted" }
            } else {
                check(updateTransactionInPlace(transaction) == 1) { "Transfer side update failed" }
            }
        }
        listOf(first, second).forEach { transaction ->
            updateCardPaymentContribution(userId, previousById[transaction.id], transaction)
        }
        return true
    }

    private suspend fun updateCardPaymentContribution(
        userId: String,
        previous: TransactionEntity?,
        updated: TransactionEntity
    ): TransactionEntity {
        if (isCardPaymentDebit(updated)) {
            check(isValidCardPaymentTarget(userId, updated)) {
                "Card payment target does not belong to the authenticated user or is not a credit card"
            }
        }
        val unchangedPayment = previous != null &&
            isCardPaymentDebit(previous) &&
            isCardPaymentDebit(updated) &&
            previous.counterpartyAccountId == updated.counterpartyAccountId &&
            kotlin.math.abs(previous.amount - updated.amount) < 0.01
        if (!unchangedPayment && previous?.cardPaymentBalanceApplied == true) {
            adjustCardPaymentOutstanding(userId, previous, -previous.amount)
        }
        val applied = if (unchangedPayment || (previous == null && updated.cardPaymentBalanceApplied)) {
            updated.cardPaymentBalanceApplied || previous?.cardPaymentBalanceApplied == true
        } else {
            isCardPaymentDebit(updated) &&
                adjustCardPaymentOutstanding(userId, updated, updated.amount)
        }
        val finalWrite = updated.copy(cardPaymentBalanceApplied = applied)
        if (finalWrite.cardPaymentBalanceApplied != updated.cardPaymentBalanceApplied) {
            check(updateTransactionInPlace(finalWrite) == 1) { "Card payment balance marker update failed" }
        }
        return finalWrite
    }

    private fun isCardPaymentDebit(transaction: TransactionEntity): Boolean =
        transaction.direction.equals("DEBIT", ignoreCase = true) &&
            (transaction.transactionType == "CARD_PAYMENT" ||
                transaction.transactionType == "CREDIT_CARD_BILL_PAYMENT")

    private suspend fun isValidCardPaymentTarget(
        userId: String,
        transaction: TransactionEntity
    ): Boolean {
        val targetId = transaction.counterpartyAccountId ?: return false
        if (!isOwnedAccountOrCard(userId, targetId)) return false
        val cards = getAllCardsSyncForUser(userId).filter { it.userId == userId }
        if (cards.any { it.id == targetId }) return true
        val accounts = getAllAccountsSyncForUser(userId).filter { it.userId == userId }
        return accounts.any { account ->
            account.id == targetId && account.type.equals("Credit Card", ignoreCase = true)
        } || cards.any { card ->
            card.accountId == targetId &&
                accounts.any { it.id == card.accountId && it.type.equals("Credit Card", ignoreCase = true) }
        }
    }

    private suspend fun adjustCardPaymentOutstanding(
        userId: String,
        transaction: TransactionEntity,
        signedPaymentAmount: Double
    ): Boolean {
        if (signedPaymentAmount == 0.0 ||
            !isCardPaymentDebit(transaction)
        ) return false
        val targetId = transaction.counterpartyAccountId ?: return false
        val accounts = getAllAccountsSyncForUser(userId).filter { it.userId == userId }
        val cards = getAllCardsSyncForUser(userId).filter { it.userId == userId }
        val targetCard = cards.firstOrNull { it.id == targetId }
            ?: cards.filter { it.accountId == targetId }.singleOrNull()
        val targetAccount = accounts.firstOrNull {
            it.id == targetId || (targetCard != null && it.id == targetCard.accountId)
        }?.takeIf { it.type.equals("Credit Card", ignoreCase = true) }
        if (targetAccount == null && targetCard == null) return false

        targetAccount?.let { account ->
            insertAccount(account.copy(
                outstandingAmount = (account.outstandingAmount - signedPaymentAmount).coerceAtLeast(0.0),
                updatedAt = transaction.updatedAt
            ))
        }
        targetCard?.let { card ->
            insertCard(card.copy(
                outstandingAmount = (card.outstandingAmount - signedPaymentAmount).coerceAtLeast(0.0),
                updatedAt = transaction.updatedAt
            ))
        }
        return true
    }

    @Transaction
    suspend fun applyCardBillSnapshot(
        userId: String,
        account: AccountEntity?,
        card: CardEntity?,
        includedPaymentIds: List<String>,
        targetIds: List<String>
    ): Boolean {
        if (userId.isBlank() || userId == "legacy:unassigned") return false
        if (account?.userId?.let { it != userId } == true || card?.userId?.let { it != userId } == true) {
            return false
        }
        account?.let { insertAccount(it) }
        if (card != null) {
            insertCard(card)
        } else if (account != null && account.type.equals("Credit Card", ignoreCase = true)) {
            getAllCardsSyncForUser(userId)
                .filter { it.userId == userId && it.accountId == account.id }
                .forEach { mappedCard ->
                    insertCard(mappedCard.copy(
                        outstandingAmount = account.outstandingAmount,
                        creditLimit = account.creditLimit,
                        updatedAt = account.updatedAt
                    ))
                }
        }
        if (includedPaymentIds.isNotEmpty() && targetIds.isNotEmpty()) {
            includedPaymentIds.distinct().forEach { paymentId ->
                val payment = getTransactionByIdSync(userId, paymentId) ?: return@forEach
                if (payment.cardPaymentBalanceApplied &&
                    payment.counterpartyAccountId?.let { it in targetIds } == true &&
                    isCardPaymentDebit(payment)
                ) {
                    check(
                        updateTransactionInPlace(
                            payment.copy(cardPaymentBalanceApplied = false)
                        ) == 1
                    ) { "Card payment snapshot marker update failed" }
                }
            }
        }
        return true
    }

    private suspend fun canKeepExistingSplits(userId: String, transaction: TransactionEntity): Boolean {
        val splits = getSplitsForTransactionSync(userId, transaction.id)
        if (splits.isEmpty()) return true
        if (splits.any { it.userId != userId || it.transactionId != transaction.id }) return false
        return kotlin.math.abs(splits.sumOf { it.amount } - kotlin.math.abs(transaction.amount)) < 0.01
    }

    private fun isTransferRecord(transaction: TransactionEntity): Boolean =
        transaction.isInternalTransfer ||
            transaction.type == "INTERNAL_TRANSFER" ||
            transaction.transactionType == "INTERNAL_TRANSFER" ||
            transaction.transactionType == "CARD_PAYMENT" ||
            transaction.transactionType == "CREDIT_CARD_BILL_PAYMENT"

    private suspend fun getAccountCountForUser(userId: String, id: String): Int =
        getAllAccountsSyncForUser(userId).count { it.id == id && it.userId == userId } +
            getAllCardsSyncForUser(userId).count { it.id == id && it.userId == userId }

    private suspend fun isOwnedAccountOrCard(userId: String, id: String): Boolean {
        if (id.isBlank()) return false
        return getAllAccountsSyncForUser(userId).any { it.id == id && it.userId == userId } ||
            getAllCardsSyncForUser(userId).any { it.id == id && it.userId == userId }
    }

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionByIdSync(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE userId = :userId AND id = :id")
    suspend fun getTransactionByIdSync(userId: String, id: String): TransactionEntity?

    @Query("SELECT rowid FROM transactions WHERE userId = :userId AND id = :transactionId")
    suspend fun getTransactionRowId(userId: String, transactionId: String): Long?

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: String)

    @Query("DELETE FROM transactions WHERE userId = :userId AND id = :id")
    suspend fun deleteTransaction(userId: String, id: String)

    @Transaction
    suspend fun deleteTransactionAndSplits(userId: String, transactionId: String): Boolean {
        if (getTransactionByIdSync(userId, transactionId) == null) return false
        deleteSplitsForTransaction(userId, transactionId)
        deleteTransaction(userId, transactionId)
        return true
    }

    @Transaction
    suspend fun deleteTransactionAndLinkedTransfer(
        userId: String,
        transactionId: String
    ): List<TransactionEntity>? {
        if (userId.isBlank() || userId == "legacy:unassigned") return null
        val transaction = getTransactionByIdSync(userId, transactionId) ?: return null
        val linked = if (isTransferRecord(transaction) &&
            !transaction.transferGroupId.isNullOrBlank() &&
            !transaction.counterpartyAccountId.isNullOrBlank()
        ) {
            getAllTransactionsSyncForUser(userId).firstOrNull { peer ->
                peer.id != transaction.id &&
                    isTransferRecord(peer) &&
                    peer.transferGroupId == transaction.transferGroupId &&
                    peer.accountId == transaction.counterpartyAccountId &&
                    peer.counterpartyAccountId == transaction.accountId &&
                    peer.userId == userId
            }
        } else null
        val rows = listOfNotNull(transaction, linked)
        rows.filter { row ->
            row.direction == "DEBIT" &&
                row.cardPaymentBalanceApplied &&
                (row.transactionType == "CARD_PAYMENT" ||
                    row.transactionType == "CREDIT_CARD_BILL_PAYMENT")
        }.forEach { payment ->
            adjustCardPaymentOutstanding(userId, payment, -payment.amount)
        }
        rows.forEach { row ->
            deleteSplitsForTransaction(userId, row.id)
            deleteTransaction(userId, row.id)
        }
        return rows
    }

    @Query("SELECT * FROM transaction_splits")
    fun getAllSplits(): Flow<List<TransactionSplitEntity>>

    @Query("SELECT * FROM transaction_splits WHERE userId = :userId")
    fun getAllSplitsForUser(userId: String): Flow<List<TransactionSplitEntity>>

    @Query("SELECT * FROM transaction_splits")
    suspend fun getAllSplitsSync(): List<TransactionSplitEntity>

    @Query("SELECT * FROM transaction_splits WHERE userId = :userId")
    suspend fun getAllSplitsSyncForUser(userId: String): List<TransactionSplitEntity>

    @Query("SELECT * FROM transaction_splits WHERE transactionId = :txId")
    suspend fun getSplitsForTransactionSync(txId: String): List<TransactionSplitEntity>

    @Query("SELECT * FROM transaction_splits WHERE userId = :userId AND transactionId = :txId")
    suspend fun getSplitsForTransactionSync(userId: String, txId: String): List<TransactionSplitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<TransactionSplitEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSplitsStrict(splits: List<TransactionSplitEntity>)

    @Query("DELETE FROM transaction_splits WHERE transactionId = :txId")
    suspend fun deleteSplitsForTransaction(txId: String)

    @Query("DELETE FROM transaction_splits WHERE userId = :userId AND transactionId = :txId")
    suspend fun deleteSplitsForTransaction(userId: String, txId: String)

    @Query("SELECT COUNT(*) FROM categories WHERE userId = :userId AND id = :categoryId")
    suspend fun getOwnedCategoryCount(userId: String, categoryId: String): Int

    @Query("SELECT COUNT(*) FROM subcategories WHERE userId = :userId AND id = :subcategoryId AND categoryId = :categoryId")
    suspend fun getOwnedSubcategoryCount(userId: String, subcategoryId: String, categoryId: String): Int

    @Query("SELECT COUNT(*) FROM transaction_splits WHERE userId = :userId AND id = :splitId AND transactionId != :transactionId")
    suspend fun getConflictingSplitIdCount(userId: String, splitId: String, transactionId: String): Int

    @Transaction
    suspend fun validateSplitSet(
        userId: String,
        transactionId: String,
        transactionAmount: Double,
        transactionCategoryId: String,
        transactionSubcategoryId: String,
        splits: List<TransactionSplitEntity>,
        requireExistingParent: Boolean
    ): SplitOperationResult {
        if (userId.isBlank() || userId == "legacy:unassigned") {
            return SplitOperationResult.NOT_AUTHENTICATED
        }
        if (requireExistingParent) {
            if (getTransactionByIdSync(userId, transactionId) == null) return SplitOperationResult.NOT_FOUND
        }
        if (getOwnedCategoryCount(userId, transactionCategoryId) != 1 ||
            (transactionSubcategoryId.isNotBlank() &&
                getOwnedSubcategoryCount(userId, transactionSubcategoryId, transactionCategoryId) != 1)
        ) {
            return SplitOperationResult.INVALID_REFERENCE
        }
        if (splits.size < 2) return SplitOperationResult.INVALID_TOTAL
        if (splits.any {
                it.userId != userId || it.transactionId != transactionId ||
                    it.categoryId.isBlank() ||
                    getConflictingSplitIdCount(userId, it.id, transactionId) > 0 ||
                    getOwnedCategoryCount(userId, it.categoryId) != 1 ||
                    (it.subcategoryId.isNotBlank() &&
                        getOwnedSubcategoryCount(userId, it.subcategoryId, it.categoryId) != 1)
            }
        ) {
            return SplitOperationResult.INVALID_REFERENCE
        }
        if (splits.map { it.id }.toSet().size != splits.size) {
            return SplitOperationResult.INVALID_REFERENCE
        }
        if (splits.any { !it.amount.isFinite() || it.amount <= 0.0 }) {
            return SplitOperationResult.INVALID_AMOUNT
        }
        val expectedAmount = kotlin.math.abs(transactionAmount)
        if (!expectedAmount.isFinite() || expectedAmount <= 0.0 ||
            kotlin.math.abs(splits.sumOf { it.amount } - expectedAmount) >= 0.01
        ) {
            return SplitOperationResult.INVALID_TOTAL
        }
        return SplitOperationResult.SAVED
    }

    @Transaction
    suspend fun replaceTransactionSplits(
        userId: String,
        transactionId: String,
        splits: List<TransactionSplitEntity>
    ): SplitOperationResult {
        val transaction = getTransactionByIdSync(userId, transactionId)
            ?: return SplitOperationResult.NOT_FOUND
        val validation = validateSplitSet(
            userId,
            transactionId,
            transaction.amount,
            transaction.categoryId,
            transaction.subcategoryId,
            splits,
            true
        )
        if (validation != SplitOperationResult.SAVED) return validation

        deleteSplitsForTransaction(userId, transactionId)
        insertSplitsStrict(splits)
        return SplitOperationResult.SAVED
    }

    @Transaction
    suspend fun updateTransactionAndSplits(
        userId: String,
        transaction: TransactionEntity,
        splits: List<TransactionSplitEntity>
    ): SplitOperationResult {
        if (transaction.userId != userId || userId.isBlank() || userId == "legacy:unassigned") {
            return SplitOperationResult.NOT_AUTHENTICATED
        }
        val existing = getTransactionByIdSync(userId, transaction.id)
            ?: return SplitOperationResult.NOT_FOUND
        val validation = validateSplitSet(
            userId,
            transaction.id,
            transaction.amount,
            transaction.categoryId,
            transaction.subcategoryId,
            splits,
            true
        )
        if (validation != SplitOperationResult.SAVED) return validation
        if (updateTransactionInPlace(transaction) != 1) return SplitOperationResult.NOT_FOUND
        deleteSplitsForTransaction(userId, existing.id)
        insertSplitsStrict(splits)
        return SplitOperationResult.SAVED
    }

    @Transaction
    suspend fun updateTransactionAndSplitsAndLinkedTransfer(
        userId: String,
        transaction: TransactionEntity,
        splits: List<TransactionSplitEntity>
    ): SplitOperationResult {
        if (transaction.userId != userId || userId.isBlank() || userId == "legacy:unassigned") {
            return SplitOperationResult.NOT_AUTHENTICATED
        }
        val existing = getTransactionByIdSync(userId, transaction.id)
            ?: return SplitOperationResult.NOT_FOUND
        val linked = if (isTransferRecord(existing) && !existing.transferGroupId.isNullOrBlank() &&
            !existing.counterpartyAccountId.isNullOrBlank()
        ) {
            getAllTransactionsSyncForUser(userId).firstOrNull { peer ->
                peer.id != existing.id &&
                    isTransferRecord(peer) &&
                    peer.userId == userId &&
                    peer.transferGroupId == existing.transferGroupId &&
                    peer.accountId == existing.counterpartyAccountId &&
                    peer.counterpartyAccountId == existing.accountId
            }
        } else null
        if (linked != null &&
            (!isTransferRecord(transaction) || transaction.transferGroupId != existing.transferGroupId)
        ) return SplitOperationResult.INVALID_REFERENCE

        val linkedAccountId = if (linked == null) null
        else transaction.counterpartyAccountId ?: linked.accountId
        if (linkedAccountId != null &&
            (!isOwnedAccountOrCard(userId, linkedAccountId) ||
                !isOwnedAccountOrCard(userId, transaction.accountId))
        ) return SplitOperationResult.INVALID_REFERENCE

        val primaryWrite = if (linked == null) transaction else transaction.copy(
            type = "INTERNAL_TRANSFER",
            isInternalTransfer = true,
            isExpense = false,
            transferGroupId = existing.transferGroupId,
            counterpartyAccountId = linkedAccountId
        )
        val updatedLinked = linked?.copy(
            type = "INTERNAL_TRANSFER",
            accountId = linkedAccountId ?: linked.accountId,
            amount = transaction.amount,
            date = transaction.date,
            time = transaction.time,
            transactionType = transaction.transactionType ?: linked.transactionType,
            direction = if (transaction.direction.equals("DEBIT", true)) "CREDIT" else "DEBIT",
            isInternalTransfer = true,
            isExpense = false,
            transferGroupId = transaction.transferGroupId,
            counterpartyAccountId = transaction.accountId,
            needsReview = transaction.needsReview,
            updatedAt = transaction.updatedAt
        )

        val validation = validateSplitSet(
            userId,
            primaryWrite.id,
            primaryWrite.amount,
            primaryWrite.categoryId,
            primaryWrite.subcategoryId,
            splits,
            true
        )
        if (validation != SplitOperationResult.SAVED) return validation
        if (updatedLinked != null && !canKeepExistingSplits(userId, updatedLinked)) {
            return SplitOperationResult.INVALID_TOTAL
        }

        val previousTransactions = listOfNotNull(primaryWrite, updatedLinked).associate { write ->
            write.id to getTransactionByIdSync(userId, write.id)
        }
        val updateResult = updateTransactionAndSplits(userId, primaryWrite, splits)
        if (updateResult != SplitOperationResult.SAVED) return updateResult
        if (updatedLinked != null) {
            check(updateTransactionInPlace(updatedLinked) == 1) { "Linked transfer update failed" }
        }
        listOfNotNull(primaryWrite, updatedLinked).forEach { write ->
            updateCardPaymentContribution(userId, previousTransactions[write.id], write)
        }
        return SplitOperationResult.SAVED
    }

    @Transaction
    suspend fun removeTransactionSplits(userId: String, transactionId: String): Boolean {
        if (getTransactionByIdSync(userId, transactionId) == null) return false
        deleteSplitsForTransaction(userId, transactionId)
        return true
    }

    @Transaction
    suspend fun insertTransactionWithSplits(
        userId: String,
        transaction: TransactionEntity,
        splits: List<TransactionSplitEntity>
    ): SplitOperationResult {
        if (userId.isBlank() || userId == "legacy:unassigned" || transaction.userId != userId) {
            return SplitOperationResult.NOT_AUTHENTICATED
        }
        if (getTransactionByIdSync(userId, transaction.id) != null) {
            return SplitOperationResult.DUPLICATE
        }
        val validation = validateSplitSet(
            userId,
            transaction.id,
            transaction.amount,
            transaction.categoryId,
            transaction.subcategoryId,
            splits,
            false
        )
        if (validation != SplitOperationResult.SAVED) return validation

        if (insertTransactionIfAbsent(transaction) < 0L) return SplitOperationResult.DUPLICATE
        insertSplitsStrict(splits)
        return SplitOperationResult.SAVED
    }

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE userId = :userId ORDER BY name ASC")
    fun getAllCategoriesForUser(userId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun getAllCategoriesSync(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE userId = :userId")
    suspend fun getAllCategoriesSyncForUser(userId: String): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: String)

    @Query("DELETE FROM categories WHERE userId = :userId AND id = :id")
    suspend fun deleteCategory(userId: String, id: String): Int

    @Query("SELECT COUNT(*) FROM categories WHERE userId = :userId AND id = :categoryId")
    suspend fun getCategoryCountForUser(userId: String, categoryId: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun getTransactionCountForCategory(categoryId: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE userId = :userId AND categoryId = :categoryId")
    suspend fun getTransactionCountForCategory(userId: String, categoryId: String): Int

    @Query("SELECT COUNT(*) FROM transaction_splits WHERE categoryId = :categoryId")
    suspend fun getSplitCountForCategory(categoryId: String): Int

    @Query("SELECT COUNT(*) FROM transaction_splits WHERE userId = :userId AND categoryId = :categoryId")
    suspend fun getSplitCountForCategory(userId: String, categoryId: String): Int

    @Query("SELECT COUNT(*) FROM subcategories WHERE userId = :userId AND categoryId = :categoryId")
    suspend fun getSubcategoryCountForCategory(userId: String, categoryId: String): Int

    @Transaction
    suspend fun checkCategoryDeleteStatus(userId: String, categoryId: String): SafeDeleteResult {
        if (getCategoryCountForUser(userId, categoryId) != 1) return SafeDeleteResult.NOT_FOUND
        return if (
            getTransactionCountForCategory(userId, categoryId) > 0 ||
            getSplitCountForCategory(userId, categoryId) > 0 ||
            getSubcategoryCountForCategory(userId, categoryId) > 0
        ) {
            SafeDeleteResult.IN_USE
        } else {
            SafeDeleteResult.AVAILABLE
        }
    }

    @Transaction
    suspend fun deleteCategoryIfUnused(userId: String, categoryId: String): SafeDeleteResult {
        if (getCategoryCountForUser(userId, categoryId) != 1) return SafeDeleteResult.NOT_FOUND
        if (
            getTransactionCountForCategory(userId, categoryId) > 0 ||
            getSplitCountForCategory(userId, categoryId) > 0 ||
            getSubcategoryCountForCategory(userId, categoryId) > 0
        ) {
            return SafeDeleteResult.IN_USE
        }
        return if (deleteCategory(userId, categoryId) == 1) {
            SafeDeleteResult.DELETED
        } else {
            SafeDeleteResult.NOT_FOUND
        }
    }

    @Query("UPDATE transactions SET categoryId = :targetCategoryId, updatedAt = :updatedAt WHERE categoryId = :sourceCategoryId")
    suspend fun reassignTransactionsCategory(sourceCategoryId: String, targetCategoryId: String, updatedAt: String)

    @Query("UPDATE transactions SET categoryId = :targetCategoryId, updatedAt = :updatedAt WHERE userId = :userId AND categoryId = :sourceCategoryId")
    suspend fun reassignTransactionsCategory(userId: String, sourceCategoryId: String, targetCategoryId: String, updatedAt: String)

    @Query("UPDATE transaction_splits SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignSplitsCategory(sourceCategoryId: String, targetCategoryId: String)

    @Query("UPDATE transaction_splits SET categoryId = :targetCategoryId WHERE userId = :userId AND categoryId = :sourceCategoryId")
    suspend fun reassignSplitsCategory(userId: String, sourceCategoryId: String, targetCategoryId: String)

    @Query("UPDATE subcategories SET categoryId = :targetCategoryId, updatedAt = :updatedAt WHERE categoryId = :sourceCategoryId")
    suspend fun reassignSubcategoriesCategory(sourceCategoryId: String, targetCategoryId: String, updatedAt: String)

    @Query("UPDATE subcategories SET categoryId = :targetCategoryId, updatedAt = :updatedAt WHERE userId = :userId AND categoryId = :sourceCategoryId")
    suspend fun reassignSubcategoriesCategory(userId: String, sourceCategoryId: String, targetCategoryId: String, updatedAt: String)

    @Query("UPDATE transactions SET categoryId = 'cat-other', updatedAt = :updatedAt WHERE categoryId = :categoryId")
    suspend fun clearCategoryFromTransactions(categoryId: String, updatedAt: String)

    @Query("UPDATE transactions SET categoryId = 'cat-other', updatedAt = :updatedAt WHERE userId = :userId AND categoryId = :categoryId")
    suspend fun clearCategoryFromTransactions(userId: String, categoryId: String, updatedAt: String)

    @Query("UPDATE transaction_splits SET categoryId = 'cat-other' WHERE categoryId = :categoryId")
    suspend fun clearCategoryFromSplits(categoryId: String)

    @Query("UPDATE transaction_splits SET categoryId = 'cat-other' WHERE userId = :userId AND categoryId = :categoryId")
    suspend fun clearCategoryFromSplits(userId: String, categoryId: String)

    @Query("DELETE FROM subcategories WHERE categoryId = :categoryId")
    suspend fun deleteSubcategoriesForCategory(categoryId: String)

    @Query("DELETE FROM subcategories WHERE userId = :userId AND categoryId = :categoryId")
    suspend fun deleteSubcategoriesForCategory(userId: String, categoryId: String)

    @Query("SELECT * FROM subcategories ORDER BY name ASC")
    fun getAllSubcategories(): Flow<List<SubcategoryEntity>>

    @Query("SELECT * FROM subcategories WHERE userId = :userId ORDER BY name ASC")
    fun getAllSubcategoriesForUser(userId: String): Flow<List<SubcategoryEntity>>

    @Query("SELECT * FROM subcategories")
    suspend fun getAllSubcategoriesSync(): List<SubcategoryEntity>

    @Query("SELECT * FROM subcategories WHERE userId = :userId")
    suspend fun getAllSubcategoriesSyncForUser(userId: String): List<SubcategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubcategory(subcategory: SubcategoryEntity)

    @Query(
        """
        UPDATE subcategories
        SET categoryId = :newCategoryId, updatedAt = :updatedAt
        WHERE userId = :userId
          AND id = :subcategoryId
          AND categoryId != :newCategoryId
          AND EXISTS (
              SELECT 1 FROM categories
              WHERE userId = :userId AND id = :newCategoryId
          )
        """
    )
    suspend fun updateSubcategoryCategoryForUser(
        userId: String,
        subcategoryId: String,
        newCategoryId: String,
        updatedAt: String
    ): Int

    @Transaction
    suspend fun moveSubcategoryForUser(
        userId: String,
        subcategoryId: String,
        newCategoryId: String,
        updatedAt: String,
        moveExistingTransactions: Boolean
    ): Boolean {
        if (updateSubcategoryCategoryForUser(userId, subcategoryId, newCategoryId, updatedAt) != 1) {
            return false
        }
        if (moveExistingTransactions) {
            updateTransactionsCategoryForSubcategory(userId, subcategoryId, newCategoryId, updatedAt)
            updateSplitsCategoryForSubcategory(userId, subcategoryId, newCategoryId)
        }
        return true
    }

    @Query("DELETE FROM subcategories WHERE id = :id")
    suspend fun deleteSubcategory(id: String)

    @Query("DELETE FROM subcategories WHERE userId = :userId AND id = :id")
    suspend fun deleteSubcategory(userId: String, id: String): Int

    @Query("SELECT COUNT(*) FROM subcategories WHERE userId = :userId AND id = :subcategoryId")
    suspend fun getSubcategoryCountForUser(userId: String, subcategoryId: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE userId = :userId AND subcategoryId = :subcategoryId")
    suspend fun getTransactionCountForSubcategory(userId: String, subcategoryId: String): Int

    @Query("SELECT COUNT(*) FROM transaction_splits WHERE userId = :userId AND subcategoryId = :subcategoryId")
    suspend fun getSplitCountForSubcategory(userId: String, subcategoryId: String): Int

    @Transaction
    suspend fun checkSubcategoryDeleteStatus(userId: String, subcategoryId: String): SafeDeleteResult {
        if (getSubcategoryCountForUser(userId, subcategoryId) != 1) return SafeDeleteResult.NOT_FOUND
        return if (
            getTransactionCountForSubcategory(userId, subcategoryId) > 0 ||
            getSplitCountForSubcategory(userId, subcategoryId) > 0
        ) {
            SafeDeleteResult.IN_USE
        } else {
            SafeDeleteResult.AVAILABLE
        }
    }

    @Transaction
    suspend fun deleteSubcategoryIfUnused(userId: String, subcategoryId: String): SafeDeleteResult {
        if (getSubcategoryCountForUser(userId, subcategoryId) != 1) return SafeDeleteResult.NOT_FOUND
        if (
            getTransactionCountForSubcategory(userId, subcategoryId) > 0 ||
            getSplitCountForSubcategory(userId, subcategoryId) > 0
        ) {
            return SafeDeleteResult.IN_USE
        }
        return if (deleteSubcategory(userId, subcategoryId) == 1) {
            SafeDeleteResult.DELETED
        } else {
            SafeDeleteResult.NOT_FOUND
        }
    }

    @Query("UPDATE transactions SET categoryId = :newCategoryId, updatedAt = :updatedAt WHERE subcategoryId = :subcategoryId")
    suspend fun updateTransactionsCategoryForSubcategory(subcategoryId: String, newCategoryId: String, updatedAt: String)

    @Query("UPDATE transactions SET categoryId = :newCategoryId, updatedAt = :updatedAt WHERE userId = :userId AND subcategoryId = :subcategoryId")
    suspend fun updateTransactionsCategoryForSubcategory(userId: String, subcategoryId: String, newCategoryId: String, updatedAt: String)

    @Query("UPDATE transaction_splits SET categoryId = :newCategoryId WHERE subcategoryId = :subcategoryId")
    suspend fun updateSplitsCategoryForSubcategory(subcategoryId: String, newCategoryId: String)

    @Query("UPDATE transaction_splits SET categoryId = :newCategoryId WHERE userId = :userId AND subcategoryId = :subcategoryId")
    suspend fun updateSplitsCategoryForSubcategory(userId: String, subcategoryId: String, newCategoryId: String)

    @Query("UPDATE transactions SET subcategoryId = '', updatedAt = :updatedAt WHERE subcategoryId = :subcategoryId")
    suspend fun clearSubcategoryFromTransactions(subcategoryId: String, updatedAt: String)

    @Query("UPDATE transactions SET subcategoryId = '', updatedAt = :updatedAt WHERE userId = :userId AND subcategoryId = :subcategoryId")
    suspend fun clearSubcategoryFromTransactions(userId: String, subcategoryId: String, updatedAt: String)

    @Query("UPDATE transaction_splits SET subcategoryId = '' WHERE subcategoryId = :subcategoryId")
    suspend fun clearSubcategoryFromSplits(subcategoryId: String)

    @Query("UPDATE transaction_splits SET subcategoryId = '' WHERE userId = :userId AND subcategoryId = :subcategoryId")
    suspend fun clearSubcategoryFromSplits(userId: String, subcategoryId: String)

    @Query("SELECT * FROM accounts ORDER BY name ASC")
    fun getAllAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE userId = :userId ORDER BY name ASC")
    fun getAllAccountsForUser(userId: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun getAllAccountsSync(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE userId = :userId")
    suspend fun getAllAccountsSyncForUser(userId: String): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity)

    @Transaction
    suspend fun saveCreditCardBalanceSnapshot(
        userId: String,
        account: AccountEntity
    ): Boolean {
        if (userId.isBlank() || userId == "legacy:unassigned" ||
            account.userId != userId ||
            !account.type.equals("Credit Card", ignoreCase = true)
        ) return false
        val accounts = getAllAccountsSyncForUser(userId).filter { it.userId == userId }
        val existing = accounts.firstOrNull { it.id == account.id } ?: return false
        if (!existing.type.equals("Credit Card", ignoreCase = true)) return false

        insertAccount(account)
        val cards = getAllCardsSyncForUser(userId)
            .filter { it.userId == userId && it.accountId == account.id }
        cards.forEach { card ->
            insertCard(card.copy(
                outstandingAmount = account.outstandingAmount,
                creditLimit = account.creditLimit,
                updatedAt = account.updatedAt
            ))
        }
        if (existing.outstandingAmount != account.outstandingAmount) {
            val paymentTargets = cards.mapTo(mutableSetOf()) { it.id }.apply { add(account.id) }
            getAllTransactionsSyncForUser(userId)
                .filter { tx ->
                    tx.userId == userId &&
                        tx.cardPaymentBalanceApplied &&
                    tx.counterpartyAccountId?.let { it in paymentTargets } == true &&
                        isCardPaymentDebit(tx)
                }
                .forEach { tx ->
                    check(updateTransactionInPlace(tx.copy(cardPaymentBalanceApplied = false)) == 1) {
                        "Credit card balance snapshot marker update failed"
                    }
                }
        }
        return true
    }

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccount(id: String)

    @Query("DELETE FROM accounts WHERE userId = :userId AND id = :id")
    suspend fun deleteAccount(userId: String, id: String)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCategoryCount(): Int

    @Query("SELECT COUNT(*) FROM categories WHERE userId = :userId")
    suspend fun getCategoryCountForUser(userId: String): Int

    @Query("SELECT * FROM cards ORDER BY name ASC")
    fun getAllCards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE userId = :userId ORDER BY name ASC")
    fun getAllCardsForUser(userId: String): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards")
    suspend fun getAllCardsSync(): List<CardEntity>

    @Query("SELECT * FROM cards WHERE userId = :userId")
    suspend fun getAllCardsSyncForUser(userId: String): List<CardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: CardEntity)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun deleteCard(id: String)

    @Query("DELETE FROM cards WHERE userId = :userId AND id = :id")
    suspend fun deleteCard(userId: String, id: String)
}
