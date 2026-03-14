package com.ettlinger.wearrecorder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Uploads files to a "ClawHark" folder in Google Drive.
 * Uses AuthManager for OAuth tokens — no credentials stored here.
 */
class DriveUploader {

    companion object {
        const val TAG = "Drive"
        const val FOLDER_NAME = "ClawHark"
        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 120_000
    }

    private var folderId: String? = null

    private suspend fun getOrCreateFolder(token: String): String? = withContext(Dispatchers.IO) {
        folderId?.let {
            AppLog.d(TAG, "Using cached folder ID: $it")
            return@withContext it
        }

        AppLog.d(TAG, "Looking up Drive folder '$FOLDER_NAME'")

        // Search for existing folder
        var conn: HttpURLConnection? = null
        try {
            val searchUrl = "https://www.googleapis.com/drive/v3/files?q=name%3D%27$FOLDER_NAME%27+and+mimeType%3D%27application%2Fvnd.google-apps.folder%27+and+trashed%3Dfalse&fields=files(id)"
            conn = URL(searchUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT

            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                val files = JSONObject(resp).getJSONArray("files")
                if (files.length() > 0) {
                    folderId = files.getJSONObject(0).getString("id")
                    AppLog.i(TAG, "Found existing folder: $folderId")
                    return@withContext folderId
                }
                AppLog.d(TAG, "Folder not found — creating")
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "no body"
                AppLog.e(TAG, "Folder search failed HTTP $code: $error")
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Folder search error", e)
            return@withContext null
        } finally {
            conn?.disconnect()
        }

        // Create folder
        var createConn: HttpURLConnection? = null
        try {
            createConn = URL("https://www.googleapis.com/drive/v3/files").openConnection() as HttpURLConnection
            createConn.setRequestProperty("Authorization", "Bearer $token")
            createConn.setRequestProperty("Content-Type", "application/json")
            createConn.requestMethod = "POST"
            createConn.doOutput = true
            createConn.connectTimeout = CONNECT_TIMEOUT
            createConn.readTimeout = READ_TIMEOUT

            val body = JSONObject().apply {
                put("name", FOLDER_NAME)
                put("mimeType", "application/vnd.google-apps.folder")
            }
            OutputStreamWriter(createConn.outputStream).use { it.write(body.toString()) }

            val createCode = createConn.responseCode
            if (createCode in 200..299) {
                val resp = createConn.inputStream.bufferedReader().readText()
                folderId = JSONObject(resp).getString("id")
                AppLog.i(TAG, "Created folder: $folderId")
            } else {
                val error = createConn.errorStream?.bufferedReader()?.readText() ?: "no body"
                AppLog.e(TAG, "Create folder failed HTTP $createCode: $error")
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Create folder error", e)
        } finally {
            createConn?.disconnect()
        }
        folderId
    }

    suspend fun uploadFile(file: File): Boolean = withContext(Dispatchers.IO) {
        val fileSize = file.length()
        AppLog.i(TAG, "=== UPLOAD START: ${file.name} (${fileSize/1024}KB) ===")

        val token = AuthManager.getAccessToken()
        if (token == null) {
            AppLog.e(TAG, "Upload aborted: no access token")
            return@withContext false
        }

        val folder = getOrCreateFolder(token)
        if (folder == null) {
            AppLog.e(TAG, "Upload aborted: no folder ID")
            return@withContext false
        }

        var conn: HttpURLConnection? = null
        try {
            val boundary = "----ClawHark${UUID.randomUUID()}"
            val url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
            conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setChunkedStreamingMode(0)

            val metadata = JSONObject().apply {
                put("name", file.name)
                put("parents", JSONArray().put(folder))
            }

            AppLog.d(TAG, "Starting multipart upload to Drive...")
            val uploadStart = System.currentTimeMillis()

            // Use raw OutputStream throughout to avoid BufferedWriter/OutputStream mixing issues
            conn.outputStream.buffered().use { out ->
                fun writeStr(s: String) { out.write(s.toByteArray(Charsets.UTF_8)) }

                writeStr("--$boundary\r\n")
                writeStr("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                writeStr(metadata.toString())
                writeStr("\r\n--$boundary\r\n")
                writeStr("Content-Type: audio/mp4\r\n\r\n")

                var uploaded = 0L
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (fis.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        uploaded += n
                    }
                }
                AppLog.d(TAG, "Sent ${uploaded/1024}KB of file data")

                writeStr("\r\n--$boundary--\r\n")
                out.flush()
            }

            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - uploadStart
            val speedKBps = if (elapsed > 0) (fileSize / 1024.0) / (elapsed / 1000.0) else 0.0

            if (code in 200..299) {
                val resp = conn.inputStream.bufferedReader().readText()
                val driveFileId = try { JSONObject(resp).getString("id") } catch (_: Exception) { "?" }
                AppLog.i(TAG, "=== UPLOAD SUCCESS === ${file.name} -> Drive ID $driveFileId | ${elapsed}ms | ${String.format("%.0f", speedKBps)} KB/s")
                true
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "no body"
                AppLog.e(TAG, "=== UPLOAD FAILED === HTTP $code after ${elapsed}ms: $error")
                if (code == 401) {
                    AppLog.e(TAG, "Token expired during upload — invalidating cached token")
                    AuthManager.invalidateAccessToken()
                }
                false
            }
        } catch (e: FileNotFoundException) {
            AppLog.w(TAG, "File deleted before upload: ${file.name} — skipping")
            true // Return true so UploadWorker doesn't count it as a failure
        } catch (e: java.net.ConnectException) {
            AppLog.e(TAG, "=== UPLOAD FAILED === Connection error (no WiFi?)", e)
            false
        } catch (e: java.net.SocketTimeoutException) {
            AppLog.e(TAG, "=== UPLOAD FAILED === Timeout (slow connection)", e)
            false
        } catch (e: Exception) {
            AppLog.e(TAG, "=== UPLOAD FAILED === Unexpected error", e)
            false
        } finally {
            conn?.disconnect()
        }
    }
}
