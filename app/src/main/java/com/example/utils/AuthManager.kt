package com.example.utils

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.data.entity.UserEntity
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import android.accounts.Account

object AuthManager {
    private const val TAG = "AuthManager"

    fun requestGmailAuthorization(
        activity: Activity,
        email: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scope = "oauth2:https://www.googleapis.com/auth/gmail.readonly"
                val account = Account(email, "com.google")
                val token = GoogleAuthUtil.getToken(activity, account, scope)
                withContext(Dispatchers.Main) {
                    onSuccess(token)
                }
            } catch (e: UserRecoverableAuthException) {
                withContext(Dispatchers.Main) {
                    activity.startActivityForResult(e.intent, 1001) // 1001: Request Code
                    onError("User intervention required to authorize Gmail.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Failed to authorize Gmail: ${e.message}")
                }
            }
        }
    }

    fun requestGoogleDriveAuthorization(
        activity: Activity,
        email: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (email.isBlank()) {
            onError("Google Account is required for Drive Backup. Please sign in first.")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scope = "oauth2:https://www.googleapis.com/auth/drive.file"
                val account = Account(email, "com.google")
                val token = GoogleAuthUtil.getToken(activity, account, scope)
                withContext(Dispatchers.Main) {
                    onSuccess(token)
                }
            } catch (e: UserRecoverableAuthException) {
                withContext(Dispatchers.Main) {
                    activity.startActivityForResult(e.intent, 1002) // 1002: Drive OAuth Request Code
                    onError("Google Drive permission required. Please complete authorization.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Failed to authorize Google Drive: ${e.message}")
                }
            }
        }
    }
    private const val PREF_NAME = "kharcha_auth_prefs"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_PHONE_NUMBER = "user_phone_number"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_CREATED_AT = "user_created_at"
    private const val KEY_LAST_LOGIN_AT = "user_last_login_at"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    // Default Web Client ID removed, must come from strings.xml
    
    fun getWebClientId(context: Context): String {
        return try {
            val resId = com.example.R.string.default_web_client_id
            val id = context.getString(resId)
            if (id.isNotEmpty()) id else throw Exception("Google Client ID is empty in strings.xml")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get default_web_client_id: ${e.message}")
            throw Exception("Google Client ID not configured in strings.xml")
        }
    }

    private val _currentUser = MutableStateFlow<UserEntity?>(null)
    val currentUser: StateFlow<UserEntity?> = _currentUser.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _hasCompletedOnboarding = MutableStateFlow(false)
    val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun getNowIso(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    fun init(context: Context) {
        val prefs = getPrefs(context)

        val loggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        val onboarding = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        _isLoggedIn.value = loggedIn
        _hasCompletedOnboarding.value = onboarding

        val firebaseUser: FirebaseUser? = try {
            FirebaseAuth.getInstance().currentUser
        } catch (e: Throwable) {
            null
        }

        if (loggedIn) {
            val uid = firebaseUser?.uid ?: prefs.getString(KEY_USER_ID, "usr_default") ?: "usr_default"
            val phone = firebaseUser?.phoneNumber ?: prefs.getString(KEY_PHONE_NUMBER, "") ?: ""
            val name = firebaseUser?.displayName ?: prefs.getString(KEY_USER_NAME, "My Kharcha User") ?: "My Kharcha User"
            val email = firebaseUser?.email ?: prefs.getString(KEY_USER_EMAIL, "") ?: ""
            val createdAt = prefs.getString(KEY_CREATED_AT, getNowIso()) ?: getNowIso()
            val lastLogin = prefs.getString(KEY_LAST_LOGIN_AT, getNowIso()) ?: getNowIso()

            _currentUser.value = UserEntity(
                id = uid,
                phoneNumber = phone,
                name = name,
                email = email,
                createdAt = createdAt,
                lastLoginAt = lastLogin
            )
        } else if (firebaseUser != null) {
            val uid = firebaseUser.uid
            val phone = firebaseUser.phoneNumber ?: ""
            val name = firebaseUser.displayName ?: "My Kharcha User"
            val email = firebaseUser.email ?: ""
            val now = getNowIso()

            val userEntity = setAuthenticatedUser(
                context = context,
                userId = uid,
                email = email,
                name = name,
                phone = phone
            )
            _currentUser.value = userEntity
            _isLoggedIn.value = true
        } else {
            _currentUser.value = null
        }
    }

    /**
     * Signs in using CredentialManager with Google ID Token, authenticates with Firebase,
     * and returns the authenticated UserEntity.
     */
    suspend fun signInWithGoogle(
        activity: Activity
    ): Result<UserEntity> = withContext(Dispatchers.IO) {
        val serverClientId = getWebClientId(activity)
        val credentialManager = CredentialManager.create(activity)
        val rawNonce = UUID.randomUUID().toString()
        val bytes = rawNonce.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        val hashedNonce = digest.fold("") { str, it -> str + "%02x".format(it) }

        // Option 1: GetSignInWithGoogleOption for explicit button clicks; enables account chooser across all device accounts
        val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(serverClientId)
            .setNonce(hashedNonce)
            .build()

        // Option 2: GetGoogleIdOption configured to allow unauthorized accounts on first login
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .setNonce(hashedNonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .addCredentialOption(googleIdOption)
            .build()

        val result: GetCredentialResponse = try {
            credentialManager.getCredential(
                request = request,
                context = activity
            )
        } catch (e: GetCredentialCancellationException) {
            Log.i(TAG, "Google Sign-In cancelled (${e::class.java.simpleName}): ${e.message}")
            return@withContext Result.failure(Exception("Sign-in cancelled. Please select your Google account to continue."))
        } catch (e: GetCredentialException) {
            val errorDetail = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
            Log.e(TAG, "Google credential request failed (${e::class.java.simpleName}): $errorDetail")
            val isNoCredential = e::class.java.simpleName == "NoCredentialException" ||
                e.message?.contains("matching credential", ignoreCase = true) == true
            val errorMessage = if (isNoCredential) {
                "No Google account is available. Add a Google account to this device and try again."
            } else {
                "Google Sign-In failed: $errorDetail"
            }
            return@withContext Result.failure(Exception(errorMessage))
        } catch (e: Exception) {
            val errorDetail = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
            Log.e(TAG, "Unexpected Google credential error (${e::class.java.simpleName}): $errorDetail")
            return@withContext Result.failure(Exception("Google Sign-In failed: $errorDetail"))
        }

        try {
            val credential = result.credential
            val isGoogleIdToken = credential is CustomCredential && (
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
            )

            if (isGoogleIdToken) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                val email = googleIdTokenCredential.id
                val displayName = googleIdTokenCredential.displayName ?: "Kharcha User"
                val phone = googleIdTokenCredential.phoneNumber ?: ""

                if (idToken.isNullOrEmpty()) {
                    Log.e(TAG, "Google ID Token is empty or null")
                    return@withContext Result.failure(Exception("Unable to retrieve Google ID token. Please try again."))
                }

                // Authenticate with Firebase Authentication using the Google ID Token
                val firebaseUser: FirebaseUser? = try {
                    val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                    val authResult = FirebaseAuth.getInstance().signInWithCredential(authCredential).await()
                    authResult.user
                } catch (e: Exception) {
                    val errorDetail = e.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: e.message?.takeIf { it.isNotBlank() }
                        ?: e::class.java.simpleName
                    Log.e(TAG, "Firebase Google authentication failed (${e::class.java.simpleName}): $errorDetail")
                    return@withContext Result.failure(Exception("Firebase authentication failed: $errorDetail"))
                }

                // Obtain the Firebase UID
                val finalUid = firebaseUser?.uid ?: ("usr_g_" + kotlin.math.abs(email.hashCode()).toString())
                val finalEmail = firebaseUser?.email ?: email
                val finalName = firebaseUser?.displayName ?: displayName
                val finalPhone = firebaseUser?.phoneNumber ?: phone

                val userEntity = setAuthenticatedUser(
                    context = activity,
                    userId = finalUid,
                    email = finalEmail,
                    name = finalName,
                    phone = finalPhone
                )
                return@withContext Result.success(userEntity)
            } else {
                return@withContext Result.failure(Exception("Unexpected credential type: ${credential.type}"))
            }
        } catch (e: Exception) {
            val errorDetail = e.localizedMessage?.takeIf { it.isNotBlank() }
                ?: e.message?.takeIf { it.isNotBlank() }
                ?: e::class.java.simpleName
            Log.e(TAG, "Google Sign-In session failed (${e::class.java.simpleName}): $errorDetail")
            return@withContext Result.failure(Exception(errorDetail))
        }
    }

    /**
     * Stores authenticated user profile and activates session.
     */
    fun setAuthenticatedUser(
        context: Context,
        userId: String,
        email: String = "",
        name: String = "My Kharcha User",
        phone: String = ""
    ): UserEntity {
        val now = getNowIso()
        val prefs = getPrefs(context)
        val existingCreatedAt = prefs.getString(KEY_CREATED_AT, null)
        val createdAt = existingCreatedAt ?: now

        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_NAME, name)
            .putString(KEY_PHONE_NUMBER, phone)
            .putString(KEY_CREATED_AT, createdAt)
            .putString(KEY_LAST_LOGIN_AT, now)
            .apply()

        val user = UserEntity(
            id = userId,
            phoneNumber = phone,
            name = name,
            email = email,
            createdAt = createdAt,
            lastLoginAt = now
        )

        _currentUser.value = user
        _isLoggedIn.value = true
        return user
    }

    /**
     * Returns the Firebase UID of the authenticated user if available.
     */
    fun getFirebaseUid(): String? {
        return try {
            FirebaseAuth.getInstance().currentUser?.uid ?: _currentUser.value?.id
        } catch (e: Throwable) {
            _currentUser.value?.id
        }
    }

    /**
     * Returns the current FirebaseUser instance if authenticated.
     */
    fun getFirebaseUser(): FirebaseUser? {
        return try {
            FirebaseAuth.getInstance().currentUser
        } catch (e: Throwable) {
            null
        }
    }

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, completed)
            .apply()
        _hasCompletedOnboarding.value = completed
    }

    fun logout(context: Context) {
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Error signing out of Firebase: ${e.message}")
        }
        try {
            val credentialManager = CredentialManager.create(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Error clearing credentials state: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error accessing CredentialManager on logout: ${e.message}")
        }

        getPrefs(context).edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .putString(KEY_USER_ID, "")
            .putString(KEY_USER_EMAIL, "")
            .putString(KEY_PHONE_NUMBER, "")
            .apply()

        _currentUser.value = null
        _isLoggedIn.value = false
    }

    fun normalizePhoneNumber(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }
}
