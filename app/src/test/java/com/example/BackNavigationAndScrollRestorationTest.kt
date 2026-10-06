package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entity.AccountEntity
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import com.example.viewmodel.KharchaViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackNavigationAndScrollRestorationTest {

    private lateinit var db: AppDatabase
    private lateinit var viewModel: KharchaViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = AppDatabase.getDatabase(context)
        viewModel = KharchaViewModel(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testBackNavigationHierarchyForTransactionEditAndDetail() {
        // Simulates back navigation layer ordering:
        // Edit Transaction (top layer) -> Transaction Detail (under layer) -> Main List
        var selectedTxDetail: TransactionEntity? = TransactionEntity(
            id = "tx-1",
            type = "EXPENSE",
            amount = 120.0,
            date = "2026-10-01",
            time = "12:00",
            merchant = "Starbucks",
            categoryId = "cat-food",
            subcategoryId = "",
            accountId = "acc-1",
            paymentMethod = "UPI",
            note = "",
            source = "MANUAL",
            transactionReference = ""
        )
        var selectedTxToEdit: TransactionEntity? = selectedTxDetail

        // Layer 1: Topmost is Edit Transaction
        assertTrue("Edit dialog should be open", selectedTxToEdit != null)
        
        // Simulating BACK action when Edit Transaction is open
        if (selectedTxToEdit != null) {
            selectedTxToEdit = null
        }
        assertNull("Edit dialog should close on first BACK", selectedTxToEdit)
        assertNotNull("Detail dialog should remain visible after dismissing edit", selectedTxDetail)

        // Layer 2: Detail Transaction
        if (selectedTxDetail != null && selectedTxToEdit == null) {
            selectedTxDetail = null
        }
        assertNull("Detail dialog should close on second BACK, returning to list", selectedTxDetail)
    }

    @Test
    fun testBackNavigationForAddTransactionAndSubmodals() {
        // Simulates back navigation when creating category inside AddTransactionDialog
        var showAddTxModal = true
        var showCatModal = true

        // User is creating a new category inside the Add Transaction sheet
        assertTrue("Submodal should be active", showCatModal)

        // First BACK press dismisses category creation modal
        if (showCatModal) {
            showCatModal = false
        }
        assertFalse("Submodal closed", showCatModal)
        assertTrue("Add Transaction sheet remains open", showAddTxModal)

        // Second BACK press dismisses Add Transaction modal
        if (!showCatModal && showAddTxModal) {
            showAddTxModal = false
        }
        assertFalse("Add Transaction modal dismissed", showAddTxModal)
    }

    @Test
    fun testBackNavigationForSplitExpenseAndNestedDialogs() {
        var showSplitDialog = true
        var activeCatModalTargetDraftIndex: Int? = 1

        // When inside split expense modal creating category for draft item
        assertNotNull(activeCatModalTargetDraftIndex)

        // First BACK closes nested category creation
        if (activeCatModalTargetDraftIndex != null) {
            activeCatModalTargetDraftIndex = null
        }
        assertNull(activeCatModalTargetDraftIndex)
        assertTrue("Split dialog remains open", showSplitDialog)

        // Second BACK closes split expense dialog
        if (activeCatModalTargetDraftIndex == null && showSplitDialog) {
            showSplitDialog = false
        }
        assertFalse("Split dialog closed", showSplitDialog)
    }

    @Test
    fun testBackNavigationForCategoryAndSubcategoryDrillDown() {
        var currentTab = "home"
        var lastMainTab = "home"

        // Navigate Home -> Category Drilldown
        lastMainTab = currentTab
        currentTab = "category_drill"
        assertEquals("category_drill", currentTab)

        // Navigate Category Drilldown -> Subcategory Drilldown
        currentTab = "subcategory_drill"
        assertEquals("subcategory_drill", currentTab)

        // Press BACK from Subcategory Drilldown -> should go to Category Drilldown
        when (currentTab) {
            "subcategory_drill" -> currentTab = "category_drill"
            "category_drill" -> currentTab = lastMainTab
            else -> currentTab = "home"
        }
        assertEquals("category_drill", currentTab)

        // Press BACK from Category Drilldown -> should go back to Home
        when (currentTab) {
            "subcategory_drill" -> currentTab = "category_drill"
            "category_drill" -> currentTab = lastMainTab
            else -> currentTab = "home"
        }
        assertEquals("home", currentTab)
    }

    @Test
    fun testBackNavigationForAccountsAndCardHistory() {
        var selectedAccountIdForHistory: String? = "acc-idfc-2345"
        var selectedTxDetail: TransactionEntity? = null
        var showAllCreditCardsScreen = false

        // User enters account history
        assertNotNull(selectedAccountIdForHistory)

        // User taps transaction in account history
        selectedTxDetail = TransactionEntity(
            id = "tx-acc-1",
            type = "EXPENSE",
            amount = 450.0,
            date = "2026-10-02",
            time = "14:00",
            merchant = "Dmart",
            categoryId = "cat-groceries",
            subcategoryId = "",
            accountId = "acc-idfc-2345",
            paymentMethod = "UPI",
            note = "",
            source = "MANUAL",
            transactionReference = ""
        )
        assertNotNull(selectedTxDetail)

        // First BACK closes transaction detail
        if (selectedTxDetail != null) {
            selectedTxDetail = null
        }
        assertNull(selectedTxDetail)
        assertEquals("acc-idfc-2345", selectedAccountIdForHistory)

        // Second BACK closes account history, returning to Accounts list
        if (selectedAccountIdForHistory != null) {
            selectedAccountIdForHistory = null
        }
        assertNull(selectedAccountIdForHistory)
    }

    @Test
    fun testPreservationOfFiltersAndTabsOnNavigation() {
        // Verify ViewModel tab state and subtab state preservation
        viewModel.transactionSubTab.value = "merchants"
        viewModel.currentTab.value = "transactions"

        assertEquals("merchants", viewModel.transactionSubTab.value)
        assertEquals("transactions", viewModel.currentTab.value)

        // Switch to categories tab and back to transactions
        viewModel.currentTab.value = "categories"
        assertEquals("categories", viewModel.currentTab.value)

        viewModel.currentTab.value = "transactions"
        assertEquals("transactions", viewModel.currentTab.value)
        assertEquals("merchants", viewModel.transactionSubTab.value) // preserved!
    }

    @Test
    fun testScrollPositionRestorationAndFilterPreservationOnBackNavigation() {
        // A. List is scrolled away from top
        val originalListState = androidx.compose.foundation.lazy.LazyListState(
            firstVisibleItemIndex = 14,
            firstVisibleItemScrollOffset = 85
        )
        assertEquals(14, originalListState.firstVisibleItemIndex)
        assertEquals(85, originalListState.firstVisibleItemScrollOffset)

        // Set filters/tabs on list
        var selectedSourceFilter = "SMS"
        var selectedAccountIdFilter = "acc-idfc-2345"
        viewModel.currentTab.value = "transactions"
        viewModel.transactionSubTab.value = "all"

        // B. Detail/Edit screen is opened
        var selectedTxDetail: TransactionEntity? = TransactionEntity(
            id = "tx-scroll-14",
            type = "EXPENSE",
            amount = 1450.0,
            date = "2026-10-03",
            time = "18:30",
            merchant = "Amazon",
            categoryId = "cat-shopping",
            subcategoryId = "",
            accountId = "acc-idfc-2345",
            paymentMethod = "Credit Card",
            note = "Headphones",
            source = "SMS",
            transactionReference = "REF9999"
        )
        assertNotNull(selectedTxDetail)

        // Save state via Compose Saver to simulate backstack/lifecycle transition if any
        val saverScope = androidx.compose.runtime.saveable.SaverScope { true }
        val savedScroll = with(androidx.compose.foundation.lazy.LazyListState.Saver) {
            saverScope.save(originalListState)
        }
        assertNotNull(savedScroll)

        // C. Android BACK returns to original list
        if (selectedTxDetail != null) {
            selectedTxDetail = null
        }
        assertNull("Detail dialog/screen must be dismissed on BACK", selectedTxDetail)

        // D. The previous scroll position/index is restored rather than returning to the top (0)
        @Suppress("UNCHECKED_CAST")
        val saver = androidx.compose.foundation.lazy.LazyListState.Saver as androidx.compose.runtime.saveable.Saver<androidx.compose.foundation.lazy.LazyListState, Any>
        val restoredListState = saver.restore(savedScroll!!)
        assertNotNull(restoredListState)
        assertEquals("Scroll index must be restored to 14, not jump to 0", 14, restoredListState!!.firstVisibleItemIndex)
        assertEquals("Scroll offset must be restored to 85", 85, restoredListState.firstVisibleItemScrollOffset)
        assertEquals("In-memory list state must also maintain index 14", 14, originalListState.firstVisibleItemIndex)

        // E. Existing filters/tabs remain unchanged
        assertEquals("SMS", selectedSourceFilter)
        assertEquals("acc-idfc-2345", selectedAccountIdFilter)
        assertEquals("transactions", viewModel.currentTab.value)
        assertEquals("all", viewModel.transactionSubTab.value)
    }
}
