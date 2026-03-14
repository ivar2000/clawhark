package com.ettlinger.wearrecorder

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages Google OAuth2 via the Device Authorization Grant (RFC 8628).
 *
 * First-time flow:
 *   1. Call requestDeviceCode() — returns a user code + verification URL
 *   2. User visits the URL on their phone/computer and enters the code
 *   3. Call pollForAuthorization(deviceCode) repeatedly until success
 *   4. Refresh token is stored in EncryptedSharedPreferences for future use
 *
 * Subsequent launches:
 *   - Call getAccessToken() — refreshes automatically using stored refresh token
 *
 * Setup: Create an OAuth 2.0 client in Google Cloud Console:
 *   - Application type: "TVs and Limited Input devices"
 *   - No client_secret needed for this type
 *   - Enable the Google Drive API for the project
 *   - Set CLIENT_ID below to your client ID
 */
object AuthManager {
    private const val TAG = "Auth"

    // OAuth credentials loaded from assets/oauth_config.json at runtime.
    // See oauth_config.json.example for the format.
    // Create your own at: https://console.cloud.google.com/apis/credentials
    // Application type: "TVs and Limited Input devices"
    private var CLIENT_ID = ""
    private var CLIENT_SECRET = ""

    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val CONNECT_TIMEOUT = 30_000
    private const val READ_TIMEOUT = 60_000

    private const val PREF_REFRESH_TOKEN = "refresh_token"
    private const val PREF_ACCESS_TOKEN = "access_token"
    private const val PREF_TOKEN_EXPIRY = "token_expiry"

    private const val ENCRYPTED_PREFS_FILE = "clawhark_auth_enc"
    private const val OLD_PREFS_FILE = "clawhark_auth"

    private var prefs: SharedPreferences? = null
    private val tokenMutex = Mutex()

    fun init(context: Context) {
        if (prefs != null) return
        val appContext = context.applicationContext

        // Load OAuth config from assets
        try {
            val json = appContext.assets.open("oauth_config.json").bufferedReader().readText()
            val config = JSONObject(json)
            CLIENT_ID = config.getString("client_id")
            CLIENT_SECRET = config.optString("client_secret", "")
            AppLog.i(TAG, "OAuth config loaded (client_id ends ...${CLIENT_ID.takeLast(12)})")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to load oauth_config.json from assets — auth will fail", e)
        }
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            prefs = EncryptedSharedPreferences.create(
                ENCRYPTED_PREFS_FILE,
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore or encrypted prefs corrupted — delete and recreate.
            // User will need to re-authenticate, but app won't crash-loop.
            AppLog.e(TAG, "EncryptedSharedPreferences corrupted — resetting", e)
            try { appContext.deleteSharedPreferences(ENCRYPTED_PREFS_FILE) } catch (_: Exception) {}
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            prefs = EncryptedSharedPreferences.create(
                ENCRYPTED_PREFS_FILE,
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        // Migrate from old unencrypted prefs if they exist
        migrateFromPlainPrefs(appContext)
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("AuthManager.init() not called")

    private fun migrateFromPlainPrefs(context: Context) {
        val oldPrefs = context.getSharedPreferences(OLD_PREFS_FILE, Context.MODE_PRIVATE)
        val oldRefreshToken = oldPrefs.getString(PREF_REFRESH_TOKEN, null) ?: return

        // Only migrate if encrypted prefs don't already have a refresh token
        if (requirePrefs().getString(PREF_REFRESH_TOKEN, null) != null) {
            // Already migrated — clear old prefs
            oldPrefs.edit().clear().apply()
            return
        }

        AppLog.i(TAG, "Migrating tokens to encrypted storage")
        requirePrefs().edit()
            .putString(PREF_REFRESH_TOKEN, oldRefreshToken)
            .putString(PREF_ACCESS_TOKEN, oldPrefs.getString(PREF_ACCESS_TOKEN, null))
            .putLong(PREF_TOKEN_EXPIRY, oldPrefs.getLong(PREF_TOKEN_EXPIRY, 0))
            .apply()

        // Clear old unencrypted prefs
        oldPrefs.edit().clear().apply()
        AppLog.i(TAG, "Migration complete — old prefs cleared")
    }

    fun isAuthenticated(): Boolean {
        return requirePrefs().getString(PREF_REFRESH_TOKEN, null) != null
    }

    fun clearAuth() {
        requirePrefs().edit().clear().apply()
        AppLog.i(TAG, "Auth cleared")
    }

    /** Invalidate cached access token (call on 401 from API) */
    fun invalidateAccessToken() {
        requirePrefs().edit()
            .remove(PREF_ACCESS_TOKEN)
            .putLong(PREF_TOKEN_EXPIRY, 0)
            .apply()
        AppLog.d(TAG, "Access token cache invalidated")
    }

    // ─── Device Code Flow ────────────────────────────────────────────────

    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresIn: Int,
        val interval: Int
    )

    sealed class PollResult {
        data class Success(val accessToken: String) : PollResult()
        object Pending : PollResult()
        object SlowDown : PollResult()
        object Expired : PollResult()
        data class Error(val message: String) : PollResult()
    }

    /**
     * Step 1: Request a device code from Google.
     * Returns a DeviceCodeResponse with the user code to display on screen.
     */
    suspend fun requestDeviceCode(): DeviceCodeResponse? = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "Requesting device code...")
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://oauth2.googleapis.com/device/code").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = "client_id=$CLIENT_ID&scope=$SCOPE"
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(resp)
                val result = DeviceCodeResponse(
                    deviceCode = json.getString("device_code"),
                    userCode = json.getString("user_code"),
                    verificationUrl = json.optString("verification_url", "https://www.google.com/device"),
                    expiresIn = json.getInt("expires_in"),
                    interval = json.getInt("interval")
                )
                AppLog.i(TAG, "Device code received: ${result.userCode} (expires in ${result.expiresIn}s)")
                return@withContext result
            } else {
                AppLog.e(TAG, "Device code request failed HTTP $code")
                return@withContext null
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Device code request error", e)
            return@withContext null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Step 2: Poll Google's token endpoint to check if the user has authorized.
     * Call this every `interval` seconds (from DeviceCodeResponse).
     */
    suspend fun pollForAuthorization(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            var body = "client_id=$CLIENT_ID&device_code=$deviceCode&grant_type=urn:ietf:params:oauth:grant-type:device_code"
            if (CLIENT_SECRET.isNotEmpty()) {
                body += "&client_secret=$CLIENT_SECRET"
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val code = conn.responseCode
            val resp = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }

            if (code == 200) {
                val json = JSONObject(resp)
                val accessToken = json.getString("access_token")
                val refreshToken = json.getString("refresh_token")
                val expiresIn = json.getInt("expires_in")

                requirePrefs().edit()
                    .putString(PREF_REFRESH_TOKEN, refreshToken)
                    .putString(PREF_ACCESS_TOKEN, accessToken)
                    .putLong(PREF_TOKEN_EXPIRY, System.currentTimeMillis() + expiresIn * 1000L)
                    .apply()

                AppLog.i(TAG, "Authorization successful — tokens stored (encrypted)")
                return@withContext PollResult.Success(accessToken)
            }

            // Parse error
            val json = try { JSONObject(resp) } catch (_: Exception) { null }
            val error = json?.optString("error", "") ?: ""

            return@withContext when (error) {
                "authorization_pending" -> PollResult.Pending
                "slow_down" -> PollResult.SlowDown
                "expired_token" -> {
                    AppLog.w(TAG, "Device code expired")
                    PollResult.Expired
                }
                "access_denied" -> {
                    AppLog.w(TAG, "User denied access")
                    PollResult.Error("Access denied")
                }
                else -> {
                    AppLog.e(TAG, "Poll error HTTP $code: $error")
                    PollResult.Error("HTTP $code: $error")
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Poll error", e)
            return@withContext PollResult.Error(e.message ?: "Unknown error")
        } finally {
            conn?.disconnect()
        }
    }

    // ─── Access Token ────────────────────────────────────────────────────

    /**
     * Returns a valid access token, refreshing if needed.
     * Uses Mutex to prevent concurrent refresh races.
     * Returns null if not authenticated or refresh fails.
     */
    suspend fun getAccessToken(): String? {
        val refreshToken = requirePrefs().getString(PREF_REFRESH_TOKEN, null)
        if (refreshToken == null) {
            AppLog.w(TAG, "No refresh token — not authenticated")
            return null
        }

        // Return cached token if still valid (with 2 min buffer)
        val cachedToken = requirePrefs().getString(PREF_ACCESS_TOKEN, null)
        val expiry = requirePrefs().getLong(PREF_TOKEN_EXPIRY, 0)
        if (cachedToken != null && System.currentTimeMillis() < expiry - 120_000) {
            return cachedToken
        }

        // Refresh with mutex to prevent concurrent refreshes
        return tokenMutex.withLock {
            // Double-check after acquiring lock — another coroutine may have refreshed
            val recheckedToken = requirePrefs().getString(PREF_ACCESS_TOKEN, null)
            val recheckedExpiry = requirePrefs().getLong(PREF_TOKEN_EXPIRY, 0)
            if (recheckedToken != null && System.currentTimeMillis() < recheckedExpiry - 120_000) {
                return@withLock recheckedToken
            }
            refreshAccessToken(refreshToken)
        }
    }

    private suspend fun refreshAccessToken(refreshToken: String): String? = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "Refreshing access token...")
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            var body = "client_id=$CLIENT_ID&refresh_token=$refreshToken&grant_type=refresh_token"
            if (CLIENT_SECRET.isNotEmpty()) {
                body += "&client_secret=$CLIENT_SECRET"
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(resp)
                val accessToken = json.getString("access_token")
                val expiresIn = json.getInt("expires_in")

                requirePrefs().edit()
                    .putString(PREF_ACCESS_TOKEN, accessToken)
                    .putLong(PREF_TOKEN_EXPIRY, System.currentTimeMillis() + expiresIn * 1000L)
                    .apply()

                AppLog.d(TAG, "Token refreshed — expires in ${expiresIn}s")
                return@withContext accessToken
            } else {
                // Don't log full error body — may contain sensitive token data
                AppLog.e(TAG, "Token refresh failed HTTP $code")
                if (code == 400 || code == 401) {
                    AppLog.e(TAG, "Refresh token invalid — clearing auth")
                    clearAuth()
                }
                return@withContext null
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Token refresh error", e)
            return@withContext null
        } finally {
            conn?.disconnect()
        }
    }
}
