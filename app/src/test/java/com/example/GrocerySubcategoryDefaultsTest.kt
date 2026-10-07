package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.DefaultCategoryData
import com.example.data.database.AppDatabase
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity
import com.example.data.entity.TransactionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GrocerySubcategoryDefaultsTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun expansionAddsOnlyMissingGroceryDefaultsAndPreservesExistingData() = runBlocking {
        val dao = db.kharchaDao()
        val groceryCategory = CategoryEntity(
            id = "cat-groceries",
            name = "Groceries",
            nameHindi = "किराना",
            icon = "🛒",
            colour = "#123456",
            isDefault = false,
            isActive = true,
            isIncome = false,
            createdAt = "original-created",
            updatedAt = "original-updated"
        )
        val legacyCategory = CategoryEntity(
            id = "cat-grocery",
            name = "Grocery/Ration",
            nameHindi = "पुराना किराना",
            icon = "🧺",
            colour = "#654321",
            isDefault = false,
            isActive = true,
            isIncome = false,
            createdAt = "legacy-created",
            updatedAt = "legacy-updated"
        )
        val originalDefaultGrocerySubcategories = DefaultCategoryData.defaultSubcategories
            .filter { it.categoryId == "cat-grocery" }
        val userSubcategoryWithDefaultId = SubcategoryEntity(
            id = "sub-groc-12",
            categoryId = groceryCategory.id,
            name = "Family Vegetable Box",
            nameHindi = "परिवार की सब्ज़ी टोकरी",
            icon = "🥕",
            colour = "#ABCDEF",
            isDefault = false,
            isActive = true,
            createdAt = "custom-created",
            updatedAt = "custom-updated"
        )
        val existingGrocerySubcategories = originalDefaultGrocerySubcategories + userSubcategoryWithDefaultId
        val transaction = TransactionEntity(
            id = "grocery-tx",
            type = "EXPENSE",
            amount = 125.5,
            date = "2026-10-01",
            time = "12:30",
            merchant = "Local Market",
            categoryId = legacyCategory.id,
            subcategoryId = existingGrocerySubcategories.first().id,
            accountId = "cash-account",
            paymentMethod = "Cash",
            note = "Existing transaction",
            source = "MANUAL",
            transactionReference = "GROCERY-1"
        )

        dao.insertCategory(groceryCategory)
        dao.insertCategory(legacyCategory)
        existingGrocerySubcategories.forEach { dao.insertSubcategory(it) }
        dao.insertTransaction(transaction)

        DefaultCategoryData.restoreAndExpandCategoriesAndSubcategories(dao, ensureCashAccount = false)

        val categoriesAfterFirstPass = dao.getAllCategoriesSync()
        assertEquals(groceryCategory, categoriesAfterFirstPass.single { it.id == groceryCategory.id })
        assertEquals(legacyCategory, categoriesAfterFirstPass.single { it.id == legacyCategory.id })

        val subcategoriesAfterFirstPass = dao.getAllSubcategoriesSync()
        existingGrocerySubcategories.forEach { original ->
            assertEquals(original, subcategoriesAfterFirstPass.single { it.userId == original.userId && it.id == original.id })
        }
        assertEquals(transaction, dao.getTransactionByIdSync(transaction.id))

        val additions = subcategoriesAfterFirstPass.filter {
            it.categoryId == groceryCategory.id && it.id.startsWith("sub-groc-") &&
                it.id !in existingGrocerySubcategories.map(SubcategoryEntity::id)
        }
        val expectedNewDefaults = mapOf(
            "Vegetables" to ("सब्ज़ियाँ" to "🥦"),
            "Fruits" to ("फल" to "🍎"),
            "Potato & Onion" to ("आलू और प्याज़" to "🥔"),
            "Leafy Vegetables" to ("हरी पत्तेदार सब्ज़ियाँ" to "🥬"),
            "Dairy" to ("डेयरी" to "🥛"),
            "Milk" to ("दूध" to "🥛"),
            "Curd/Yogurt" to ("दही" to "🥣"),
            "Paneer" to ("पनीर" to "🧀"),
            "Butter" to ("मक्खन" to "🧈"),
            "Cheese" to ("चीज़" to "🧀"),
            "Ghee" to ("घी" to "🫙"),
            "Eggs" to ("अंडे" to "🥚"),
            "Maida" to ("मैदा" to "🌾"),
            "Grains" to ("अनाज" to "🌾"),
            "Poha" to ("पोहा" to "🍚"),
            "Oats" to ("ओट्स" to "🥣"),
            "Bread" to ("ब्रेड" to "🍞"),
            "Biscuits" to ("बिस्कुट" to "🍪"),
            "Namkeen/Snacks" to ("नमकीन/स्नैक्स" to "🥨"),
            "Dry Fruits" to ("सूखे मेवे" to "🥜"),
            "Nuts & Seeds" to ("मेवे और बीज" to "🌰"),
            "Sauces & Spreads" to ("सॉस और स्प्रेड" to "🫙"),
            "Pickles" to ("अचार" to "🥒"),
            "Packaged Food" to ("पैकेज्ड फूड" to "📦"),
            "Frozen Food" to ("फ्रोजन फूड" to "🧊"),
            "Instant Food" to ("इंस्टेंट फूड" to "🍜"),
            "Sweets" to ("मिठाई" to "🍬"),
            "Juice & Beverages" to ("जूस और पेय" to "🧃"),
            "Water" to ("पानी" to "💧"),
            "Baby Food" to ("बेबी फूड" to "🍼"),
            "Organic/Health Food" to ("ऑर्गेनिक/हेल्थ फूड" to "🌱")
        )
        assertEquals(31, additions.size)
        assertEquals(additions.size, additions.map(SubcategoryEntity::id).toSet().size)
        assertEquals(
            expectedNewDefaults,
            additions.associate { it.name to (it.nameHindi to it.icon) }
        )
        assertEquals("sub-groc-12-2", additions.single { it.name == "Vegetables" }.id)
        assertTrue(additions.all { it.userId == groceryCategory.userId })

        val categoryCountAfterFirstPass = categoriesAfterFirstPass.size
        DefaultCategoryData.restoreAndExpandCategoriesAndSubcategories(dao, ensureCashAccount = false)

        val subcategoriesAfterSecondPass = dao.getAllSubcategoriesSync()
        assertEquals(
            subcategoriesAfterFirstPass.size,
            subcategoriesAfterSecondPass.size
        )
        assertEquals(categoryCountAfterFirstPass, dao.getAllCategoriesSync().size)
        assertEquals(transaction, dao.getTransactionByIdSync(transaction.id))
        additions.forEach { original ->
            assertEquals(original, subcategoriesAfterSecondPass.single { it.userId == original.userId && it.id == original.id })
        }
    }

    @Test
    fun equivalentExistingGrocerySubcategoryIsNotDuplicatedOrChanged() = runBlocking {
        val dao = db.kharchaDao()
        val groceryCategory = CategoryEntity(
            id = "cat-groceries",
            name = "Groceries",
            nameHindi = "किराना",
            icon = "🛒",
            colour = "#10B981",
            isDefault = true,
            isActive = true,
            isIncome = false,
            createdAt = "created",
            updatedAt = "updated"
        )
        val existingCurd = SubcategoryEntity(
            id = "user-curd",
            categoryId = groceryCategory.id,
            name = "Curd",
            nameHindi = "दही",
            icon = "🥣",
            colour = "#123456",
            isDefault = false,
            isActive = true,
            createdAt = "original-created",
            updatedAt = "original-updated"
        )
        dao.insertCategory(groceryCategory)
        dao.insertSubcategory(existingCurd)

        DefaultCategoryData.restoreAndExpandCategoriesAndSubcategories(dao, ensureCashAccount = false)

        val subcategories = dao.getAllSubcategoriesSync()
        assertEquals(existingCurd, subcategories.single { it.id == existingCurd.id })
        assertEquals(1, subcategories.count {
            it.categoryId == groceryCategory.id && it.name in setOf("Curd", "Curd/Yogurt")
        })
    }
}
