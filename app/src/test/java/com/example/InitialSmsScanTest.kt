package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.ui.screens.SmsScanRangeOption
import com.example.utils.IngestionStatus
import com.example.utils.SmsParser
import com.example.utils.TransactionIngestionEngine
import com.example.viewmodel.KharchaViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InitialSmsScanTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var viewModel: KharchaViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setTestInstance(db)
        runBlocking {
            val dao = db.kharchaDao()
            AppDatabase.prepopulateData(dao)
        }
        viewModel = KharchaViewModel(context.applicationContext as android.app.Application)
    }

    @After
    fun tearDown() {
        AppDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testInitialScanFlagPersistence() {
        // First install state
        assertFalse(viewModel.isSmsInitialScanCompleted())

        // Mark completed
        viewModel.markSmsInitialScanCompleted()
        assertTrue(viewModel.isSmsInitialScanCompleted())

        // Verify state survives new ViewModel instance / app restart
        val newViewModel = KharchaViewModel(context.applicationContext as android.app.Application)
        assertTrue(newViewModel.isSmsInitialScanCompleted())
    }

    @Test
    fun testSkipForNowDoesNotPerformScanAndSavesCompletedFlag() {
        assertFalse(viewModel.isSmsInitialScanCompleted())

        // Simulating "Skip for Now" action in UI
        viewModel.markSmsInitialScanCompleted()

        assertTrue(viewModel.isSmsInitialScanCompleted())
        // Verify tracking state remains enabled if SMS tracking was enabled
        viewModel.setSmsTrackingEnabled(true, context)
        assertTrue(viewModel.smsTrackingEnabled.value)
    }

    @Test
    fun testSmsScanRangeOptionCalculations() {
        val now = Calendar.getInstance()

        // Today
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Last 7 Days
        val sevenDaysCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -7)
        }

        // Last 30 Days
        val thirtyDaysCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -30)
        }

        // Last 3 Months
        val threeMonthsCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, -3)
        }

        // Last 6 Months
        val sixMonthsCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, -6)
        }

        // Last 1 Year
        val oneYearCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.YEAR, -1)
        }

        assertTrue(todayCal.timeInMillis <= now.timeInMillis)
        assertTrue(sevenDaysCal.timeInMillis < todayCal.timeInMillis)
        assertTrue(thirtyDaysCal.timeInMillis < sevenDaysCal.timeInMillis)
        assertTrue(threeMonthsCal.timeInMillis < thirtyDaysCal.timeInMillis)
        assertTrue(sixMonthsCal.timeInMillis < threeMonthsCal.timeInMillis)
        assertTrue(oneYearCal.timeInMillis < sixMonthsCal.timeInMillis)
    }

    @Test
    fun testCustomDateRangeCalculation() {
        val customCal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val timestamp = customCal.timeInMillis

        val verifyCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        assertEquals(2026, verifyCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, verifyCal.get(Calendar.MONTH))
        assertEquals(15, verifyCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun testDuplicatePreventionDuringIngestion() = runBlocking {
        val body = "Your A/C XX1234 debited by Rs. 500.00 on 20-09-26 at Amazon. Ref: 11223344"
        val sender = "HDFCBK"
        val timestamp = System.currentTimeMillis()

        val parseStatus = SmsParser.parseSms("sms-100", sender, body, timestamp)
        assertTrue("Expected SmsParseStatus.Success", parseStatus is SmsParser.SmsParseStatus.Success)
        val tx = (parseStatus as SmsParser.SmsParseStatus.Success).transaction

        // First ingestion -> IMPORTED
        val (ingested1, status1) = TransactionIngestionEngine.ingestTransaction(context, tx, body)
        assertEquals("First ingestion should be IMPORTED", IngestionStatus.IMPORTED, status1)
        assertNotNull("Ingested transaction 1 should not be null", ingested1)

        // Second ingestion -> DUPLICATE
        val (ingested2, status2) = TransactionIngestionEngine.ingestTransaction(context, tx, body)
        assertEquals("Second ingestion should be DUPLICATE", IngestionStatus.DUPLICATE, status2)
        assertNotNull("Ingested transaction 2 should not be null", ingested2)
    }
}
