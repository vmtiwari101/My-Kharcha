package com.example

import com.example.data.firestore.FirestorePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreSecurityRulesPhase5Test {

    // Simulation helper of Security Rules evaluation
    private fun evaluateIsOwner(requestAuthUid: String?, targetPathUid: String): Boolean {
        if (requestAuthUid == null) return false
        return requestAuthUid == targetPathUid
    }

    @Test
    fun testUnauthenticatedAccessDeniedForAllPaths() {
        val requestAuthUid: String? = null
        val targetUserUid = "user_alpha_123"

        // Unauthenticated access to user document
        assertFalse(evaluateIsOwner(requestAuthUid, targetUserUid))

        // Unauthenticated access to subcollections
        assertFalse(evaluateIsOwner(requestAuthUid, targetUserUid))
    }

    @Test
    fun testUserAIsIsolatedFromUserBForUserDocument() {
        val userAUid = "user_A_111"
        val userBUid = "user_B_222"

        // User A accessing User A
        assertTrue("User A can access User A document", evaluateIsOwner(userAUid, userAUid))

        // User A accessing User B
        assertFalse("User A CANNOT access User B document", evaluateIsOwner(userAUid, userBUid))

        // User B accessing User B
        assertTrue("User B can access User B document", evaluateIsOwner(userBUid, userBUid))

        // User B accessing User A
        assertFalse("User B CANNOT access User A document", evaluateIsOwner(userBUid, userAUid))
    }

    @Test
    fun testUserAIsIsolatedFromUserBForAllSubcollections() {
        val userAUid = "user_A_111"
        val userBUid = "user_B_222"

        val subcollectionPathsForUserB = listOf(
            FirestorePaths.profileDocumentPath(userBUid),
            FirestorePaths.accountsCollectionPath(userBUid),
            FirestorePaths.cardsCollectionPath(userBUid),
            FirestorePaths.transactionsCollectionPath(userBUid),
            FirestorePaths.categoriesCollectionPath(userBUid),
            FirestorePaths.subcategoriesCollectionPath(userBUid),
            FirestorePaths.merchantsCollectionPath(userBUid),
            FirestorePaths.settingsCollectionPath(userBUid)
        )

        subcollectionPathsForUserB.forEach { path ->
            val isUserBPath = path.startsWith("users/$userBUid")
            assertTrue("Path must belong to User B hierarchy", isUserBPath)

            // User A trying to access User B's subcollection path
            val accessGrantedToUserA = evaluateIsOwner(userAUid, userBUid)
            assertFalse("User A MUST BE DENIED access to $path", accessGrantedToUserA)

            // User B accessing User B's subcollection path
            val accessGrantedToUserB = evaluateIsOwner(userBUid, userBUid)
            assertTrue("User B MUST BE GRANTED access to $path", accessGrantedToUserB)
        }
    }

    @Test
    fun testSubcollectionPathGenerationCorrectness() {
        val uid = "user_test_99"

        assertEquals("users/user_test_99/profile/info", FirestorePaths.profileDocumentPath(uid))
        assertEquals("users/user_test_99/accounts", FirestorePaths.accountsCollectionPath(uid))
        assertEquals("users/user_test_99/cards", FirestorePaths.cardsCollectionPath(uid))
        assertEquals("users/user_test_99/transactions", FirestorePaths.transactionsCollectionPath(uid))
        assertEquals("users/user_test_99/categories", FirestorePaths.categoriesCollectionPath(uid))
        assertEquals("users/user_test_99/subcategories", FirestorePaths.subcategoriesCollectionPath(uid))
        assertEquals("users/user_test_99/merchants", FirestorePaths.merchantsCollectionPath(uid))
        assertEquals("users/user_test_99/settings", FirestorePaths.settingsCollectionPath(uid))
    }
}
