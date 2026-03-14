package com.ettlinger.wearrecorder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "Upload"
        const val WORK_NAME = "upload_recordings"
        const val STALE_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
    }

    override suspend fun doWork(): Result {
        AppLog.init(applicationContext)
        AuthManager.init(applicationContext)

        val dir = File(applicationContext.filesDir, "recordings")
        if (!dir.exists()) return Result.success()

        // Recover stale .uploading files FIRST — before any early returns.
        // These are from previous crashed workers. Only recover files older than
        // 10 minutes to avoid stealing files a concurrent worker is actively uploading.
        recoverStaleFiles(dir)

        if (!AuthManager.isAuthenticated()) {
            AppLog.d(TAG, "Not authenticated — skipping upload")
            return Result.success()
        }

        // Only upload .m4a files (not .tmp files which are still being written)
        val files = dir.listFiles()?.filter { it.extension == "m4a" }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) {
            AppLog.d(TAG, "No files to upload")
            return Result.success()
        }

        AppLog.i(TAG, "Upload worker started — ${files.size} files pending")
        val uploader = DriveUploader()
        var succeeded = 0
        var failed = 0
        var consecutiveFailures = 0

        for (file in files) {
            // Claim the file by renaming to .uploading — prevents concurrent workers
            // from uploading the same file
            val uploadingFile = File(file.parent, file.name + ".uploading")
            if (!file.renameTo(uploadingFile)) {
                // File disappeared or another worker claimed it
                AppLog.d(TAG, "Could not claim ${file.name} — skipping (likely another worker)")
                continue
            }

            AppLog.i(TAG, "Uploading: ${file.name} (${uploadingFile.length() / 1024}KB)")
            val startMs = System.currentTimeMillis()
            val ok = uploader.uploadFile(uploadingFile)
            val elapsed = System.currentTimeMillis() - startMs

            if (ok) {
                uploadingFile.delete()
                succeeded++
                consecutiveFailures = 0
                AppLog.i(TAG, "Upload SUCCESS: ${file.name} in ${elapsed}ms ($succeeded/${files.size})")
            } else {
                // Rename back so it can be retried next time
                if (!uploadingFile.renameTo(file)) {
                    AppLog.w(TAG, "Could not restore ${uploadingFile.name} — file may be orphaned")
                }
                failed++
                consecutiveFailures++
                AppLog.e(TAG, "Upload FAILED: ${file.name} after ${elapsed}ms ($failed failures)")
                if (consecutiveFailures >= 3) {
                    AppLog.e(TAG, "3 consecutive failures — stopping (likely network issue)")
                    break
                }
            }
        }

        AppLog.i(TAG, "Upload worker done — $succeeded succeeded, $failed failed of ${files.size}")
        return if (failed == 0) Result.success() else Result.retry()
    }

    private fun recoverStaleFiles(dir: File) {
        val now = System.currentTimeMillis()
        val staleUploading = dir.listFiles()?.filter {
            it.extension == "uploading" && (now - it.lastModified() > STALE_THRESHOLD_MS)
        } ?: emptyList()
        for (stale in staleUploading) {
            val ageMs = now - stale.lastModified()
            val originalName = stale.name.removeSuffix(".uploading")
            val original = File(dir, originalName)
            if (stale.renameTo(original)) {
                AppLog.i(TAG, "Recovered stale uploading file: $originalName (age: ${ageMs / 1000}s)")
            }
        }
    }
}
