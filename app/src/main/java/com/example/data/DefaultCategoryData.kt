package com.example.data

import android.util.Log
import com.example.data.dao.KharchaDao
import com.example.data.entity.CategoryEntity
import com.example.data.entity.SubcategoryEntity

object DefaultCategoryData {
    private const val TAG = "DefaultCategoryData"
    private const val DEFAULT_DATE = "2026-09-22T19:00:00Z"

    val defaultCategories: List<CategoryEntity> = listOf(
        // Expense Categories
        CategoryEntity("cat-food", "Food & Dining", "भोजन और डाइनिंग", "🍔", "#F97316", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-groceries", "Groceries", "किराना", "🛒", "#10B981", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-transport", "Transport", "परिवहन", "🚗", "#3B82F6", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-fuel", "Fuel", "ईंधन", "⛽", "#EF4444", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-shopping", "Shopping", "खरीददारी", "🛍️", "#EC4899", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-bills", "Bills & Utilities", "बिल और उपयोगिताएँ", "💡", "#F59E0B", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-rent", "Rent", "किराया", "🏠", "#6366F1", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-health", "Health", "स्वास्थ्य", "❤️", "#EF4444", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-education", "Education", "शिक्षा", "📚", "#06B6D4", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-repair", "Repair & Maintenance", "मरम्मत और रखरखाव", "🔧", "#EA580C", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-entertainment", "Entertainment", "मनोरंजन", "🎬", "#8B5CF6", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-recharge", "Recharge", "रिचार्ज", "📱", "#14B8A6", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-personal", "Personal Care", "व्यक्तिगत देखभाल", "🧴", "#F43F5E", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-household", "Household & Cleaning", "घरेलू और सफाई", "🧹", "#84CC16", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-travel", "Travel", "यात्रा", "✈️", "#0EA5E9", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-emi", "EMI", "ईएमआई", "💳", "#D97706", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-cash", "Cash Withdrawal", "नकद निकासी", "🏧", "#0D9488", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-cc-bill", "Credit Card Bill", "क्रेडिट कार्ड बिल", "💳", "#4B5563", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-transfer", "Internal Transfer", "आंतरिक स्थानांतरण", "🔄", "#3B82F6", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-other", "Other", "अन्य", "📦", "#64748B", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        
        // Detailed food & grocery categories
        CategoryEntity("cat-vegetables", "Vegetables", "सब्ज़ियाँ", "🥦", "#10B981", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-fruits", "Fruits", "फल", "🍎", "#EF4444", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-dairy", "Dairy & Milk", "डेयरी और दूध", "🥛", "#0EA5E9", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-bakery", "Bakery", "बेकरी", "🥐", "#D97706", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-dryfruits", "Dry Fruits & Nuts", "सूखे मेवे", "🥜", "#A16207", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-beverages", "Beverages", "पेय पदार्थ", "🥤", "#06B6D4", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-snacks", "Snacks", "नाश्ता", "🍿", "#F59E0B", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-nonveg", "Non-Veg", "मांसाहारी", "🍗", "#DC2626", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-puja", "Puja & Religious", "पूजा एवं धार्मिक", "🙏", "#8B5CF6", isDefault = true, isActive = true, isIncome = false, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),

        // Income Categories
        CategoryEntity("cat-salary", "Salary", "वेतन", "💼", "#10B981", isDefault = true, isActive = true, isIncome = true, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-business", "Business Income", "व्यापार आय", "📈", "#059669", isDefault = true, isActive = true, isIncome = true, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-refund", "Refund", "धनवापसी", "🔄", "#3B82F6", isDefault = true, isActive = true, isIncome = true, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-interest", "Interest", "ब्याज", "💰", "#F59E0B", isDefault = true, isActive = true, isIncome = true, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE),
        CategoryEntity("cat-income-other", "Other Income", "अन्य आय", "💵", "#64748B", isDefault = true, isActive = true, isIncome = true, createdAt = DEFAULT_DATE, updatedAt = DEFAULT_DATE)
    )

    val defaultSubcategories: List<SubcategoryEntity> = listOf(
        // 1. Education
        SubcategoryEntity("sub-edu-1", "cat-education", "School Fees", "स्कूल की फीस", "🏫", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-2", "cat-education", "Tuition Fees", "ट्यूशन फीस", "👨‍🏫", "#0891B2", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-3", "cat-education", "College Fees", "कॉलेज की फीस", "🎓", "#0E7490", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-4", "cat-education", "Coaching Fees", "कोचिंग फीस", "🧑‍🏫", "#155E75", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-5", "cat-education", "Exam Fees", "परीक्षा शुल्क", "📝", "#3B82F6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-6", "cat-education", "Admission Fees", "प्रवेश शुल्क", "📋", "#2563EB", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-7", "cat-education", "Books", "पुस्तकें", "📚", "#1D4ED8", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-8", "cat-education", "Stationery", "स्टेशनरी", "✏️", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-9", "cat-education", "School Uniform", "स्कूल की वर्दी", "👕", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-10", "cat-education", "School Shoes", "स्कूल के जूते", "👟", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-11", "cat-education", "School Bag", "स्कूल बैग", "🎒", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-12", "cat-education", "Online Course", "ऑनलाइन कोर्स", "💻", "#6366F1", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-13", "cat-education", "Training & Certification", "प्रशिक्षण और प्रमाणन", "📜", "#4F46E5", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-14", "cat-education", "Library", "पुस्तकालय", "📖", "#0D9488", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-15", "cat-education", "Educational Software", "शैक्षिक सॉफ्टवेयर", "💻", "#0284C7", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-edu-16", "cat-education", "Other Education", "अन्य शिक्षा", "🎓", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 2. Repair & Maintenance
        SubcategoryEntity("sub-rep-1", "cat-repair", "Bike Repair", "बाइक मरम्मत", "🏍️", "#EA580C", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-2", "cat-repair", "Scooty Repair", "स्कूटी मरम्मत", "🛵", "#F97316", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-3", "cat-repair", "Car Repair", "कार मरम्मत", "🚗", "#C2410C", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-4", "cat-repair", "Motorcycle Service", "मोटरसाइकिल सर्विस", "🏍️", "#9A3412", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-5", "cat-repair", "Cycle Repair", "साइकिल मरम्मत", "🚲", "#0284C7", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-6", "cat-repair", "Tyre Repair", "टायर मरम्मत", "🛞", "#475569", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-7", "cat-repair", "Puncture Repair", "पंचर मरम्मत", "🛞", "#334155", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-8", "cat-repair", "Battery Repair", "बैटरी मरम्मत", "🔋", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-9", "cat-repair", "Fridge Repair", "फ्रिज मरम्मत", "🧊", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-10", "cat-repair", "AC Repair", "एसी मरम्मत", "❄️", "#0284C7", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-11", "cat-repair", "TV Repair", "टीवी मरम्मत", "📺", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-12", "cat-repair", "Fan Repair", "पंखे की मरम्मत", "🌀", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-13", "cat-repair", "Cooler Repair", "कूलर की मरम्मत", "💨", "#38BDF8", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-14", "cat-repair", "Washing Machine Repair", "वाशिंग मशीन मरम्मत", "🧺", "#6366F1", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-15", "cat-repair", "Microwave Repair", "माइक्रोवेव मरम्मत", "📡", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-16", "cat-repair", "Water Purifier Repair", "वॉटर प्यूरीफायर मरम्मत", "💧", "#0284C7", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-17", "cat-repair", "Geyser Repair", "गीजर की मरम्मत", "🚿", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-18", "cat-repair", "Mobile Repair", "मोबाइल मरम्मत", "📱", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-19", "cat-repair", "Laptop/Computer Repair", "लैपटॉप/कंप्यूटर मरम्मत", "💻", "#3B82F6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-20", "cat-repair", "Electrical Repair", "इलेक्ट्रिकल मरम्मत", "⚡", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-21", "cat-repair", "Plumbing Repair", "प्लंबिंग मरम्मत", "🔧", "#0284C7", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-22", "cat-repair", "Carpenter Work", "बढ़ई का काम", "🪚", "#B45309", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-23", "cat-repair", "Home Appliance Repair", "घरेलू उपकरण मरम्मत", "🔌", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rep-24", "cat-repair", "Other Repair", "अन्य मरम्मत", "🔧", "#EA580C", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 3. Food & Dining
        SubcategoryEntity("sub-food-1", "cat-food", "Restaurant", "रेस्तरां", "🍽️", "#F97316", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-food-2", "cat-food", "Fast Food", "फास्ट फूड", "🍔", "#F97316", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-food-3", "cat-food", "Food Delivery", "फूड डिलीवरी", "🛵", "#EA580C", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-food-4", "cat-food", "Tea & Coffee", "चाय और कॉफी", "☕", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-food-5", "cat-food", "Sweets & Desserts", "मिठाई और डेसर्ट", "🍰", "#EC4899", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-food-6", "cat-food", "Bakery", " बेकरी", "🥐", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-food-7", "cat-food", "Street Food", "स्ट्रीट फूड", "🌮", "#EA580C", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-food-8", "cat-food", "Snacks", "नाश्ता", "🍿", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-food-9", "cat-food", "Ice Cream", "आइसक्रीम", "🍦", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-food-10", "cat-food", "Canteen", "कैंटीन", "🍱", "#F97316", true, true, DEFAULT_DATE, DEFAULT_DATE),
        
        // Detailed food categories subcategories
        SubcategoryEntity("sub-veg-1", "cat-vegetables", "Potato", "आलू", "🥔", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-2", "cat-vegetables", "Onion", "प्याज़", "🧅", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-3", "cat-vegetables", "Tomato", "टमाटर", "🍅", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-4", "cat-vegetables", "Carrot", "गाजर", "🥕", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-5", "cat-vegetables", "Peas", "मटर", "🫛", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-6", "cat-vegetables", "Cauliflower", "फूलगोभी", "🥦", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-7", "cat-vegetables", "Cabbage", "पत्तागोभी", "🥬", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-8", "cat-vegetables", "Brinjal", "बैंगन", "🍆", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-9", "cat-vegetables", "Lady Finger", "भिंडी", "🥒", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-10", "cat-vegetables", "Capsicum", "शिमला मिर्च", "🫑", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-11", "cat-vegetables", "Green Chilli", "हरी मिर्च", "🌶️", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-12", "cat-vegetables", "Spinach", "पालक", "🍃", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-13", "cat-vegetables", "Coriander", "धनिया", "🌿", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-14", "cat-vegetables", "Bottle Gourd", "लौकी", "🥒", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-15", "cat-vegetables", "Bitter Gourd", "करेला", "🥒", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-16", "cat-vegetables", "Radish", "मूली", "🥕", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-17", "cat-vegetables", "Beans", "बीन्स", "🫘", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-18", "cat-vegetables", "Mix Vegetable", "मिक्स सब्ज़ी", "🍲", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-veg-19", "cat-vegetables", "Other Vegetables", "अन्य सब्ज़ियाँ", "🥦", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),

        SubcategoryEntity("sub-fruit-1", "cat-fruits", "Apple", "सेब", "🍎", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-2", "cat-fruits", "Banana", "केला", "🍌", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-3", "cat-fruits", "Mango", "आम", "🥭", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-4", "cat-fruits", "Orange", "संतरा", "🍊", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-5", "cat-fruits", "Grapes", "अंगूर", "🍇", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-6", "cat-fruits", "Watermelon", "तरबूज", "🍉", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-7", "cat-fruits", "Pineapple", "अनानास", "🍍", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-8", "cat-fruits", "Papaya", "पपीता", "🥭", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-9", "cat-fruits", "Guava", "अमरूद", "🍐", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-10", "cat-fruits", "Coconut", "नारियल", "🥥", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-11", "cat-fruits", "Coconut Water", "नारियल पानी", "🥥", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-12", "cat-fruits", "Mango Juice", "आम का रस", "🧃", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-13", "cat-fruits", "Mix Fruit", "मिक्स फल", "🍎", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fruit-14", "cat-fruits", "Other Fruits", "अन्य फल", "🍎", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),

        SubcategoryEntity("sub-dairy-1", "cat-dairy", "Milk", "दूध", "🥛", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dairy-2", "cat-dairy", "Curd", "दही", "🥣", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dairy-3", "cat-dairy", "Paneer", "पनीर", "🧊", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dairy-4", "cat-dairy", "Cheese", "पनीर/चीज़", "🧀", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dairy-5", "cat-dairy", "Butter", "मक्खन", "🧈", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dairy-6", "cat-dairy", "Ghee", "घी", "🫙", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dairy-7", "cat-dairy", "Lassi", "लस्सी", "🥛", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dairy-8", "cat-dairy", "Cream", "मलाई/क्रीम", "🥣", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dairy-9", "cat-dairy", "Ice Cream", "आइसक्रीम", "🍦", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dairy-10", "cat-dairy", "Other Dairy", "अन्य डेयरी", "🥛", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),

        SubcategoryEntity("sub-bakery-1", "cat-bakery", "Bread", "ब्रेड", "🍞", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bakery-2", "cat-bakery", "Biscuit", "बिस्कुट", "🍪", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bakery-3", "cat-bakery", "Cake", "केक", "🎂", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bakery-4", "cat-bakery", "Pastry", "पेस्ट्री", "🍰", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bakery-5", "cat-bakery", "Rusk", "रस्क", "🍞", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bakery-6", "cat-bakery", "Other Bakery", "अन्य बेकरी", "🥐", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),

        SubcategoryEntity("sub-groc-1", "cat-grocery", "Rice", "चावल", "🍚", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-groc-2", "cat-grocery", "Wheat/Flour", "गेहूँ/आटा", "🌾", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-groc-3", "cat-grocery", "Dal", "दाल", "🫘", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-groc-4", "cat-grocery", "Oil", "तेल", "🫗", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-groc-5", "cat-grocery", "Sugar", "चीनी", "🧂", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-groc-6", "cat-grocery", "Salt", "नमक", "🧂", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-groc-7", "cat-grocery", "Spices", "मसाले", "🌶️", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-groc-8", "cat-grocery", "Tea", "चाय", "☕", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-groc-9", "cat-grocery", "Coffee", "कॉफी", "☕", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-groc-10", "cat-grocery", "Atta", "आटा", "🌾", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-groc-11", "cat-grocery", "Other Grocery", "अन्य किराना", "🛒", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),

        SubcategoryEntity("sub-dry-1", "cat-dryfruits", "Almond", "बादाम", "🥜", "#A16207", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dry-2", "cat-dryfruits", "Cashew", "काजू", "🥜", "#A16207", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dry-3", "cat-dryfruits", "Raisins", "किशमिश", "🥜", "#A16207", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dry-4", "cat-dryfruits", "Walnut", "अखरोट", "🥜", "#A16207", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dry-5", "cat-dryfruits", "Pistachio", "पिस्ता", "🥜", "#A16207", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dry-6", "cat-dryfruits", "Mixed Dry Fruits", "मिश्रित सूखे मेवे", "🥜", "#A16207", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-dry-7", "cat-dryfruits", "Other Dry Fruits", "अन्य सूखे मेवे", "🥜", "#A16207", true, true, DEFAULT_DATE, DEFAULT_DATE),

        SubcategoryEntity("sub-bev-1", "cat-beverages", "Tea", "चाय", "☕", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bev-2", "cat-beverages", "Coffee", "कॉफी", "☕", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bev-3", "cat-beverages", "Soft Drink", "सॉफ्ट ड्रिंक", "🥤", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bev-4", "cat-beverages", "Juice", "रस/जूस", "🧃", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bev-5", "cat-beverages", "Water", "पानी", "💧", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bev-6", "cat-beverages", "Energy Drink", "एनर्जी ड्रिंक", "⚡", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bev-7", "cat-beverages", "Other Beverages", "अन्य पेय", "🥤", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),

        SubcategoryEntity("sub-snack-1", "cat-snacks", "Chips", "चिप्स", "🍿", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-snack-2", "cat-snacks", "Namkeen", "नमकीन", "🥨", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-snack-3", "cat-snacks", "Samosa", "समोसा", "🥟", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-snack-4", "cat-snacks", "Kachori", "कचौड़ी", "🥟", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-snack-5", "cat-snacks", "Momos", "मोमोज़", "🥟", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-snack-6", "cat-snacks", "Fast Food", "फास्ट फूड", "🍔", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-snack-7", "cat-snacks", "Other Snacks", "अन्य नाश्ता", "🍿", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        
        SubcategoryEntity("sub-nonveg-1", "cat-nonveg", "Chicken", "चिकन", "🍗", "#DC2626", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-nonveg-2", "cat-nonveg", "Mutton", "मटन", "🥩", "#DC2626", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-nonveg-3", "cat-nonveg", "Fish", "मछली", "🐟", "#DC2626", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-nonveg-4", "cat-nonveg", "Egg", "अंडा", "🥚", "#DC2626", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-nonveg-5", "cat-nonveg", "Other Non-Veg", "अन्य मांसाहारी", "🍗", "#DC2626", true, true, DEFAULT_DATE, DEFAULT_DATE),
        
        SubcategoryEntity("sub-puja-1", "cat-puja", "Puja Samagri", "पूजा सामग्री", "🥣", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-2", "cat-puja", "Agarbatti", "अगरबत्ती", "🪵", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-3", "cat-puja", "Dhoop", "धूप", "💨", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-4", "cat-puja", "Diya", "दिया", "🕯️", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-5", "cat-puja", "Kapoor", "कपूर", "🧊", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-6", "cat-puja", "Tel / Ghee", "तेल / घी", "🫙", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-7", "cat-puja", "Phool / Mala", "फूल / माला", "🌼", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-8", "cat-puja", "Prasad", "प्रसाद", "🍬", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-9", "cat-puja", "Bhog Samagri", "भोग सामग्री", "🍱", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-10", "cat-puja", "Mandir Donation", "मंदिर दान", "🙏", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-11", "cat-puja", "Pandit Dakshina", "पंडित दक्षिणा", "💰", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-12", "cat-puja", "Havan / Yagya", "हवन / यज्ञ", "🔥", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-13", "cat-puja", "Puja Service", "पूजा सेवा", "🧘", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-14", "cat-puja", "Vrat / Fasting Items", "व्रत / उपवास का सामान", "🍎", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-15", "cat-puja", "Festival Puja", "त्योहार पूजा", "🎉", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-16", "cat-puja", "Religious Travel / Darshan", "धार्मिक यात्रा / दर्शन", "🚗", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-puja-17", "cat-puja", "Other Religious", "अन्य धार्मिक", "🙏", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 5. Transport
        SubcategoryEntity("sub-trans-1", "cat-transport", "Bus", "बस", "🚌", "#3B82F6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trans-2", "cat-transport", "Train", "ट्रेन", "🚆", "#2563EB", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trans-3", "cat-transport", "Auto Rickshaw", "ऑटो रिक्शा", "🛺", "#1D4ED8", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trans-4", "cat-transport", "Taxi", "टैक्सी", "🚕", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trans-5", "cat-transport", "Cab", "कैब", "🚖", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trans-6", "cat-transport", "Metro", "मेट्रो", "🚇", "#4F46E5", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trans-7", "cat-transport", "Parking", "पार्किंग", "🅿️", "#64748B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trans-8", "cat-transport", "Toll", "टोल", "🛣️", "#475569", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trans-9", "cat-transport", "Vehicle Rental", "वाहन किराया", "🚙", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 6. Fuel
        SubcategoryEntity("sub-fuel-1", "cat-fuel", "Petrol", "पेट्रोल", "⛽", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fuel-2", "cat-fuel", "Diesel", "डीजल", "🛢️", "#DC2626", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fuel-3", "cat-fuel", "CNG", "सीएनजी", "💨", "#B91C1C", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-fuel-4", "cat-fuel", "EV Charging", "ईवी चार्जिंग", "🔋", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 7. Shopping
        SubcategoryEntity("sub-shop-1", "cat-shopping", "Clothes", "कपड़े", "👕", "#EC4899", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-shop-2", "cat-shopping", "Shoes", "जूते", "👟", "#DB2777", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-shop-3", "cat-shopping", "Electronics", "इलेक्ट्रॉनिक्स", "📱", "#7C3AED", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-shop-4", "cat-shopping", "Mobile Accessories", "मोबाइल एक्सेसरीज", "📱", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-shop-5", "cat-shopping", "Home Appliances", "घरेलू उपकरण", "🔌", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-shop-6", "cat-shopping", "Online Shopping", "ऑनलाइन शॉपिंग", "📦", "#9D174D", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-shop-7", "cat-shopping", "Gifts", "उपहार", "🎁", "#F43F5E", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-shop-8", "cat-shopping", "Jewellery", "आभूषण", "💍", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-shop-9", "cat-shopping", "Bags", "बैग", "🎒", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-shop-10", "cat-shopping", "Cosmetics", "सौंदर्य प्रसाधन", "💄", "#E11D48", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 8. Bills & Utilities
        SubcategoryEntity("sub-bills-1", "cat-bills", "Electricity", "बिजली", "⚡", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bills-2", "cat-bills", "Water", "पानी", "💧", "#0284C7", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bills-3", "cat-bills", "Gas", "गैस", "🔥", "#EA580C", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bills-4", "cat-bills", "Internet", "इंटरनेट", "🌐", "#6366F1", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bills-5", "cat-bills", "Broadband", "ब्रॉडबैंड", "📡", "#3B82F6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bills-6", "cat-bills", "Mobile Bill", "मोबाइल बिल", "📱", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bills-7", "cat-bills", "Cable TV", "केबल टीवी", "📺", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bills-8", "cat-bills", "DTH", "डीटीएच", "📡", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bills-9", "cat-bills", "Property Tax", "संपत्ति कर", "🏠", "#64748B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-bills-10", "cat-bills", "Other Bills", "अन्य बिल", "📄", "#475569", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 9. Recharge
        SubcategoryEntity("sub-rech-1", "cat-recharge", "Mobile Recharge", "मोबाइल रिचार्ज", "📱", "#14B8A6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rech-2", "cat-recharge", "Data Recharge", "डेटा रिचार्ज", "📶", "#0D9488", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rech-3", "cat-recharge", "DTH Recharge", "डीटीएच रिचार्ज", "📺", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rech-4", "cat-recharge", "FASTag Recharge", "फास्टैग रिचार्ज", "🚗", "#0284C7", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rech-5", "cat-recharge", "Other Recharge", "अन्य रिचार्ज", "🔄", "#3B82F6", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 10. Health
        SubcategoryEntity("sub-health-1", "cat-health", "Doctor Consultation", "डॉक्टर परामर्श", "👨‍⚕️", "#EF4444", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-health-2", "cat-health", "Medicine", "दवा", "💊", "#DC2626", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-health-3", "cat-health", "Hospital", "अस्पताल", "🏥", "#B91C1C", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-health-4", "cat-health", "Diagnostic Test", "नैदानिक परीक्षण", "🧪", "#0284C7", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-health-5", "cat-health", "Dental", "दंत चिकित्सा", "🦷", "#06B6D4", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-health-6", "cat-health", "Eye Care", "आंखों की देखभाल", "👁️", "#3B82F6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-health-7", "cat-health", "Health Checkup", "स्वास्थ्य जांच", "🩺", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-health-8", "cat-health", "Health Equipment", "स्वास्थ्य उपकरण", "🏥", "#F43F5E", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 11. Personal Care
        SubcategoryEntity("sub-pers-1", "cat-personal", "Haircut", "बाल कटवाना", "💇", "#F43F5E", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-pers-2", "cat-personal", "Salon", "सैलून", "💇‍♀️", "#E11D48", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-pers-3", "cat-personal", "Spa", "स्पा", "🧖", "#BE185D", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-pers-4", "cat-personal", "Cosmetics", "सौंदर्य प्रसाधन", "💄", "#EC4899", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-pers-5", "cat-personal", "Skincare", "त्वचा की देखभाल", "🧴", "#DB2777", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-pers-6", "cat-personal", "Grooming", "ग्रूमिंग", "✂️", "#9D174D", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-pers-7", "cat-personal", "Personal Products", "व्यक्तिगत उत्पाद", "🧼", "#F43F5E", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 12. Household
        SubcategoryEntity("sub-house-1", "cat-household", "Cleaning", "सफाई", "🧹", "#84CC16", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-house-2", "cat-household", "Kitchen", "रसोई", "🍳", "#65A30D", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-house-3", "cat-household", "Furniture", "फर्नीचर", "🛋️", "#4D7C0F", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-house-4", "cat-household", "Home Decor", "घर की सजावट", "🏠", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-house-5", "cat-household", "Utensils", "बर्तन", "🍽️", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-house-6", "cat-household", "Household Supplies", "घरेलू सामान", "🧺", "#0D9488", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-house-7", "cat-household", "Domestic Help", "घरेलू मदद", "👩‍🍳", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 13. Rent
        SubcategoryEntity("sub-rent-1", "cat-rent", "House Rent", "घर का किराया", "🏠", "#6366F1", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rent-2", "cat-rent", "Shop Rent", "दुकान का किराया", "🏪", "#4F46E5", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rent-3", "cat-rent", "Office Rent", "ऑफिस का किराया", "🏢", "#4338CA", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-rent-4", "cat-rent", "Parking Rent", "पार्किंग किराया", "🅿️", "#3730A3", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 14. Travel
        SubcategoryEntity("sub-trav-1", "cat-travel", "Flight", "फ्लाइट", "✈️", "#0EA5E9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trav-2", "cat-travel", "Train", "ट्रेन", "🚆", "#0284C7", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trav-3", "cat-travel", "Bus", "बस", "🚌", "#0369A1", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trav-4", "cat-travel", "Hotel", "होटल", "🏨", "#075985", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trav-5", "cat-travel", "Travel Food", "यात्रा भोजन", "🍱", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trav-6", "cat-travel", "Travel Shopping", "यात्रा खरीदारी", "🛍️", "#EC4899", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trav-7", "cat-travel", "Local Transport", "स्थानीय परिवहन", "🚕", "#F97316", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-trav-8", "cat-travel", "Tour/Package", "टूर/पैकेज", "🧳", "#3B82F6", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 15. Entertainment
        SubcategoryEntity("sub-ent-1", "cat-entertainment", "Movie", "फिल्म", "🎬", "#8B5CF6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-ent-2", "cat-entertainment", "Games", "खेल", "🎮", "#7C3AED", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-ent-3", "cat-entertainment", "Streaming", "स्ट्रीमिंग", "📺", "#6D28D9", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-ent-4", "cat-entertainment", "Music", "संगीत", "🎵", "#5B21B6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-ent-5", "cat-entertainment", "Concert", "कॉन्सर्ट", "🎸", "#4C1D95", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-ent-6", "cat-entertainment", "Event", "ईवेंट", "🎉", "#A78BFA", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 16. EMI
        SubcategoryEntity("sub-emi-1", "cat-emi", "Home Loan EMI", "होम लोन ईएमआई", "🏠", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-emi-2", "cat-emi", "Car Loan EMI", "कार लोन ईएमआई", "🚗", "#B45309", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-emi-3", "cat-emi", "Personal Loan EMI", "पर्सनल लोन ईएमआई", "💵", "#92400E", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-emi-4", "cat-emi", "Education Loan EMI", "शिक्षा ऋण ईएमआई", "📚", "#78350F", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-emi-5", "cat-emi", "Consumer Durable EMI", "उपभोक्ता टिकाऊ ईएमआई", "🔌", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 17. Cash Withdrawal
        SubcategoryEntity("sub-cash-1", "cat-cash", "ATM Withdrawal", "एटीएम निकासी", "🏧", "#0D9488", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-cash-2", "cat-cash", "Self Withdrawal", "स्वयं की निकासी", "💵", "#0F766E", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 18. Credit Card Bill
        SubcategoryEntity("sub-ccb-1", "cat-cc-bill", "Credit Card Payment", "क्रेडिट कार्ड भुगतान", "💳", "#4B5563", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 19. Internal Transfer
        SubcategoryEntity("sub-tf-1", "cat-transfer", "Self Transfer", "स्वयं स्थानांतरण", "🔄", "#3B82F6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-tf-2", "cat-transfer", "Bank to Wallet Transfer", "बैंक से वॉलेट ट्रांसफर", "💳", "#2563EB", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-tf-3", "cat-transfer", "Wallet to Bank Transfer", "वॉलेट से बैंक ट्रांसफर", "💳", "#1D4ED8", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-tf-4", "cat-transfer", "Account to Account Transfer", "अकाउंट से अकाउंट ट्रांसफर", "🔄", "#1E40AF", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 20. Other
        SubcategoryEntity("sub-oth-1", "cat-other", "Miscellaneous", "विविध", "📦", "#64748B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-oth-2", "cat-other", "Other Expense", "अन्य खर्च", "📦", "#475569", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 21. Salary (Income)
        SubcategoryEntity("sub-sal-1", "cat-salary", "Monthly Salary", "मासिक वेतन", "💼", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-sal-2", "cat-salary", "Bonus", "बोनस", "🎁", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-sal-3", "cat-salary", "Arrears", "बकाया", "💰", "#047857", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-sal-4", "cat-salary", "Overtime", "ओवरटाइम", "💼", "#065F46", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 22. Business Income (Income)
        SubcategoryEntity("sub-biz-1", "cat-business", "Business Income", "व्यापार आय", "💼", "#059669", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-biz-2", "cat-business", "Sales Income", "बिक्री आय", "🛒", "#047857", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-biz-3", "cat-business", "Commission", "कमीशन", "💰", "#10B981", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-biz-4", "cat-business", "Professional Fees", "पेशेवर शुल्क", "👨‍💼", "#0D9488", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 23. Refund (Income)
        SubcategoryEntity("sub-ref-1", "cat-refund", "Shopping Refund", "शॉपिंग रिफंड", "🛍️", "#3B82F6", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-ref-2", "cat-refund", "Bank Refund", "बैंक रिफंड", "🏦", "#2563EB", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-ref-3", "cat-refund", "UPI Refund", "यूपीआई रिफंड", "📱", "#1D4ED8", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-ref-4", "cat-refund", "Payment Refund", "भुगतान रिफंड", "💳", "#1E40AF", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 24. Interest (Income)
        SubcategoryEntity("sub-int-1", "cat-interest", "Bank Interest", "बैंक ब्याज", "🏦", "#F59E0B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-int-2", "cat-interest", "FD Interest", "एफडी ब्याज", "💰", "#D97706", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-int-3", "cat-interest", "Savings Interest", "बचत ब्याज", "💵", "#B45309", true, true, DEFAULT_DATE, DEFAULT_DATE),

        // 25. Other Income (Income)
        SubcategoryEntity("sub-inco-1", "cat-income-other", "Gift Received", "उपहार मिला", "🎁", "#64748B", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-inco-2", "cat-income-other", "Cash Received", "नकद प्राप्त हुआ", "💵", "#475569", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-inco-3", "cat-income-other", "Freelance Income", "फ्रीलांस आय", "💻", "#334155", true, true, DEFAULT_DATE, DEFAULT_DATE),
        SubcategoryEntity("sub-inco-4", "cat-income-other", "Other Income", "अन्य आय", "💰", "#1E293B", true, true, DEFAULT_DATE, DEFAULT_DATE)
    )

    /**
     * Restores and expands categories and subcategories safely without altering or deleting
     * existing user records or breaking existing transaction foreign keys / IDs.
     */
    suspend fun restoreAndExpandCategoriesAndSubcategories(dao: KharchaDao) {
        try {
            // 0. Auto-merge legacy duplicate cat-grocery or same-named categories into cat-groceries safely
            val initialCats = dao.getAllCategoriesSync()
            val hasGroceries = initialCats.find { it.id == "cat-groceries" || it.name.equals("Groceries", ignoreCase = true) }
            val legacyGrocery = initialCats.find { it.id == "cat-grocery" || (it.id != hasGroceries?.id && (it.name.equals("Grocery/Ration", ignoreCase = true) || it.name.equals("Groceries", ignoreCase = true))) }
            if (hasGroceries != null && legacyGrocery != null && hasGroceries.id != legacyGrocery.id) {
                Log.d(TAG, "Auto-merging legacy duplicate category '${legacyGrocery.name}' into '${hasGroceries.name}'")
                val now = DEFAULT_DATE
                dao.reassignTransactionsCategory(legacyGrocery.id, hasGroceries.id, now)
                dao.reassignSplitsCategory(legacyGrocery.id, hasGroceries.id)
                dao.reassignSubcategoriesCategory(legacyGrocery.id, hasGroceries.id, now)
                dao.deleteCategory(legacyGrocery.id)
            }

            val existingCats = dao.getAllCategoriesSync().toMutableList()
            val existingSubs = dao.getAllSubcategoriesSync().toMutableList()

            // 1. Ensure all default categories exist with proper icons and colours
            for (defCat in defaultCategories) {
                val match = existingCats.find { it.id == defCat.id }
                    ?: existingCats.find { it.name.equals(defCat.name, ignoreCase = true) }

                if (match == null) {
                    dao.insertCategory(defCat)
                    existingCats.add(defCat)
                    Log.d(TAG, "Restored missing default category: ${defCat.name} (${defCat.id})")
                } else {
                    // If existing category has blank icon, colour or nameHindi, heal it
                    if (match.icon.isBlank() || match.colour.isBlank() || match.icon == "📁" || match.nameHindi.isBlank()) {
                        val healed = match.copy(
                            icon = if (match.icon.isBlank() || match.icon == "📁") defCat.icon else match.icon,
                            colour = if (match.colour.isBlank()) defCat.colour else match.colour,
                            nameHindi = if (match.nameHindi.isBlank()) defCat.nameHindi else match.nameHindi
                        )
                        dao.insertCategory(healed)
                    }
                }
            }

            // Reload categories map to map category names to actual IDs in the DB
            val finalCats = dao.getAllCategoriesSync()
            val catNameToId = finalCats.associate { it.name.lowercase().trim() to it.id }
            val catIdToCat = finalCats.associateBy { it.id }

            // 2. Ensure all default subcategories exist with proper icons and colours
            for (defSub in defaultSubcategories) {
                // Find parent category in DB
                val targetCat = catIdToCat[defSub.categoryId]
                    ?: catNameToId[defaultCategories.find { it.id == defSub.categoryId }?.name?.lowercase()?.trim()]?.let { catIdToCat[it] }

                if (targetCat == null) continue
                val targetCatId = targetCat.id

                val subMatch = existingSubs.find {
                    it.categoryId == targetCatId && it.name.equals(defSub.name, ignoreCase = true)
                }

                if (subMatch == null) {
                    val subToInsert = defSub.copy(categoryId = targetCatId)
                    dao.insertSubcategory(subToInsert)
                    existingSubs.add(subToInsert)
                    Log.d(TAG, "Restored missing subcategory: ${defSub.name} for ${targetCat.name}")
                } else {
                    // Heal blank icons, colors or nameHindi
                    if (subMatch.icon.isBlank() || subMatch.colour.isBlank() || subMatch.nameHindi.isBlank()) {
                        val healed = subMatch.copy(
                            icon = if (subMatch.icon.isBlank()) defSub.icon else subMatch.icon,
                            colour = if (subMatch.colour.isBlank()) defSub.colour else subMatch.colour,
                            nameHindi = if (subMatch.nameHindi.isBlank()) defSub.nameHindi else subMatch.nameHindi
                        )
                        dao.insertSubcategory(healed)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring categories and subcategories: ${e.message}", e)
        }
    }
}
