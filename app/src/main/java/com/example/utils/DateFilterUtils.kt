package com.example.utils

import com.example.data.entity.TransactionEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateFilterUtils {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

    fun filterByRange(transactions: List<TransactionEntity>, rangeType: String, customStart: Date? = null, customEnd: Date? = null): List<TransactionEntity> {
        val now = Date()
        val calendar = Calendar.getInstance()
        
        return when (rangeType) {
            "TODAY" -> {
                val todayStr = dateFormat.format(now)
                transactions.filter { it.date == todayStr }
            }
            "THIS_WEEK" -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                val weekStart = calendar.time
                transactions.filter {
                    val date = dateFormat.parse(it.date)
                    date != null && (date.after(weekStart) || date == weekStart)
                }
            }
            "THIS_MONTH" -> {
                val currentMonth = SimpleDateFormat("yyyy-MM", Locale.ENGLISH).format(now)
                transactions.filter { it.date.startsWith(currentMonth) }
            }
            "LAST_MONTH" -> {
                calendar.add(Calendar.MONTH, -1)
                val lastMonth = SimpleDateFormat("yyyy-MM", Locale.ENGLISH).format(calendar.time)
                transactions.filter { it.date.startsWith(lastMonth) }
            }
            "CUSTOM" -> {
                if (customStart != null && customEnd != null) {
                    transactions.filter {
                        val date = dateFormat.parse(it.date)
                        date != null && (date.after(customStart) || date == customStart) && (date.before(customEnd) || date == customEnd)
                    }
                } else {
                    transactions
                }
            }
            else -> transactions
        }
    }
}
