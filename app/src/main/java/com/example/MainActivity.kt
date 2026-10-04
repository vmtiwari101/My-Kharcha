package com.example

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.OnboardingPermissionsScreen
import com.example.ui.screens.MainScaffold
import com.example.ui.screens.SplashScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.AuthManager
import com.example.viewmodel.KharchaViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        AuthManager.init(this)
        com.example.utils.CreditCardReminderManager.rescheduleAllAsync(applicationContext)
        com.example.worker.EmailTrackingScheduler.rescheduleIfNeeded(applicationContext)

        // Register global uncaught exception handler to capture startup crashes
        val prefs = getSharedPreferences("crash_reports", Context.MODE_PRIVATE)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            prefs.edit().putString("last_crash", stackTrace).commit()
            Log.e("MyKharchaCrash", "CRASH in thread ${thread.name}", throwable)
            android.os.Process.killProcess(android.os.Process.myPid())
            java.lang.System.exit(10)
        }
        
        enableEdgeToEdge()
        
        setContent {
            MyApplicationTheme {
                var lastCrash by remember { mutableStateOf(prefs.getString("last_crash", null)) }
                
                if (lastCrash != null) {
                    CrashScreen(
                        stackTrace = lastCrash!!,
                        onClear = {
                            prefs.edit().remove("last_crash").commit()
                            lastCrash = null
                        }
                    )
                } else {
                    var showSplash by remember { mutableStateOf(true) }
                    var viewModelError by remember { mutableStateOf<Throwable?>(null) }
                    
                    val kharchaViewModel = remember {
                        try {
                            // Safely initialize ViewModel on startup
                            androidx.lifecycle.ViewModelProvider(this@MainActivity)[KharchaViewModel::class.java]
                        } catch (t: Throwable) {
                            viewModelError = t
                            null
                        }
                    }
                    
                    val isLoggedIn by AuthManager.isLoggedIn.collectAsState()
                    val hasCompletedOnboarding by AuthManager.hasCompletedOnboarding.collectAsState()

                    if (viewModelError != null) {
                        CrashScreen(
                            stackTrace = Log.getStackTraceString(viewModelError!!),
                            onClear = {
                                viewModelError = null
                            }
                        )
                    } else if (showSplash) {
                        SplashScreen(onTimeout = { showSplash = false })
                    } else if (kharchaViewModel != null) {
                        if (!isLoggedIn) {
                            LoginScreen(
                                viewModel = kharchaViewModel,
                                onLoginSuccess = { isNewUser ->
                                    AuthManager.setOnboardingCompleted(this@MainActivity, true)
                                }
                            )
                        } else {
                            MainScaffold(viewModel = kharchaViewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CrashScreen(stackTrace: String, onClear: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0F172A))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "My Kharcha Diagnostics",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Surface(
                color = Color(0xFFEF4444).copy(alpha = 0.15f),
                contentColor = Color(0xFFFCA5A5),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Startup Crash Intercepted!",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF87171)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "The application caught a real-device crash. Copy the stack trace below and report it to identify the issue:",
                        fontSize = 14.sp,
                        color = Color(0xFFE2E8F0)
                    )
                }
            }
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B), MaterialTheme.shapes.small)
                    .padding(12.dp)
            ) {
                val scrollState = rememberScrollState()
                SelectionContainer {
                    Text(
                        text = stackTrace,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF38BDF8),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    )
                }
            }
            
            Button(
                onClick = onClear,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Log & Try Again", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
