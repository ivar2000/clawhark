package com.ettlinger.wearrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RecordingService : Service() {

    companion object {
        const val TAG = "Service"
        const val CHANNEL_ID = "clawhark_channel"
        const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE = 16000
        const val CHUNK_DURATION_MS = 15 * 60 * 1000L // 15 minutes
        const val VAD_THRESHOLD = 500
        const val VAD_SILENCE_TIMEOUT_MS = 3000L
        const val AAC_BIT_RATE = 32000 // 32kbps — good for voice
        const val READ_BUFFER_SAMPLES = 8192 // 512ms at 16kHz — halves CPU wakeups vs 4096
        const val UPLOAD_INTERVAL_MINUTES = 60L
        const val UPLOAD_FALLBACK_INTERVAL_HOURS = 4L
        const val UPLOAD_FALLBACK_WORK_NAME = "upload_fallback"
        const val STATUS_LOG_INTERVAL_MS = 300_000L // 5 min
        const val MIN_FREE_SPACE_BYTES = 50 * 1024 * 1024L // 50MB
        const val MAX_LOCAL_STORAGE_BYTES = 500 * 1024 * 1024L // 500MB — FIFO eviction
        const val MIC_RECOVERY_MAX_RETRIES = 5
        const val STALE_TMP_THRESHOLD_MS = 20 * 60 * 1000L // 20min — older .tmp files are likely complete

        // Shared preference keys (used by MainActivity too)
        const val PREF_FILE = "clawhark"
        const val PREF_SHOULD_RECORD = "should_record"
        const val ACTION_STOP = "STOP"
    }

    private val binder = LocalBinder()
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null

    // Stats
    @Volatile private var totalBytesEncoded = 0L
    @Volatile private var totalSilenceSkipped = 0L
    @Volatile private var totalReadErrors = 0
    @Volatile private var chunksWithVoice = 0
    @Volatile private var chunksWithoutVoice = 0

    @Volatile var recordingStartTime: Long = 0L
        private set
    @Volatile var totalChunks: Int = 0
        private set

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent): IBinder {
        AppLog.d(TAG, "onBind() called")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.d(TAG, "onUnbind() called")
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i(TAG, "=== SERVICE CREATED ===")
        AppLog.i(TAG, "Device: ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        AppLog.i(TAG, "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        AppLog.i(TAG, "Codec: AAC ${AAC_BIT_RATE/1000}kbps | Chunk: ${CHUNK_DURATION_MS/60000}min | Upload: every ${UPLOAD_INTERVAL_MINUTES}min")
        logBatteryStatus()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "START"
        AppLog.i(TAG, "onStartCommand() action=$action flags=$flags startId=$startId")

        when (action) {
            ACTION_STOP -> {
                AppLog.i(TAG, "STOP requested — shutting down")
                getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                    .edit().putBoolean(PREF_SHOULD_RECORD, false).apply()
                logStats()
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // On START_STICKY restart (null intent), check if user explicitly stopped
                if (intent == null) {
                    val shouldRecord = getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                        .getBoolean(PREF_SHOULD_RECORD, true)
                    if (!shouldRecord) {
                        AppLog.i(TAG, "START_STICKY restart but user stopped — not restarting")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    AppLog.i(TAG, "START_STICKY restart — resuming recording")
                }
                startForeground(NOTIFICATION_ID, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                startRecording()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i(TAG, "=== SERVICE DESTROYED ===")
        logStats()
        isRecording = false
        scope.cancel()
        // Safety-net: release wake lock in case NonCancellable finally block hasn't run yet.
        // Non-ref-counted lock + isHeld check makes double-release a safe no-op.
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.w(TAG, "onTaskRemoved() — app swiped from recents. Service should persist as foreground.")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        val levelName = when (level) {
            TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "level=$level"
        }
        AppLog.w(TAG, "onTrimMemory($levelName)")
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        AppLog.e(TAG, "onLowMemory() — system critically low!")
        super.onLowMemory()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Always-on audio recording"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        AppLog.d(TAG, "Notification channel created")
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording")
            .setContentText("Listening...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startRecording() {
        if (isRecording) {
            AppLog.w(TAG, "startRecording() called but already recording — ignoring")
            return
        }

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val readBufBytes = READ_BUFFER_SAMPLES * 2 // 2 bytes per 16-bit sample
        val internalBufSize = maxOf(minBufSize, readBufBytes) * 2
        AppLog.i(TAG, "AudioRecord minBufSize=$minBufSize, internal=$internalBufSize, readSamples=$READ_BUFFER_SAMPLES (${READ_BUFFER_SAMPLES * 1000 / SAMPLE_RATE}ms)")

        logAudioState()

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                internalBufSize
            )
            AppLog.i(TAG, "AudioRecord created — state=${audioRecord?.state} (${if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) "INITIALIZED" else "ERROR"})")
        } catch (e: SecurityException) {
            AppLog.e(TAG, "FATAL: No mic permission!", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        } catch (e: Exception) {
            AppLog.e(TAG, "FATAL: Failed to create AudioRecord", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            AppLog.e(TAG, "FATAL: AudioRecord not initialized — mic may be in use")
            audioRecord?.release()
            audioRecord = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        isRecording = true
        recordingStartTime = System.currentTimeMillis()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClawHark::Recording").apply {
            setReferenceCounted(false) // Prevents stacking — single release() fully releases
        }
        wakeLock?.acquire() // No timeout — foreground service prevents doze; released in finally block
        AppLog.i(TAG, "Wake lock acquired (non-ref-counted, no timeout)")

        try {
            audioRecord?.startRecording()
            AppLog.i(TAG, "=== RECORDING STARTED === sampleRate=$SAMPLE_RATE chunkDuration=${CHUNK_DURATION_MS/1000}s vadThreshold=$VAD_THRESHOLD codec=AAC@${AAC_BIT_RATE/1000}kbps readBuf=${READ_BUFFER_SAMPLES}samples")
        } catch (e: Exception) {
            AppLog.e(TAG, "FATAL: AudioRecord.startRecording() failed", e)
            isRecording = false
            audioRecord?.release()
            audioRecord = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Clean up any orphaned .tmp files from previous crashes
        cleanupOrphanedTmpFiles()

        // Schedule periodic uploads via WorkManager
        scheduleUploads()

        // Periodic status logger
        scope.launch {
            while (isRecording) {
                delay(STATUS_LOG_INTERVAL_MS)
                if (isRecording) logPeriodicStatus()
            }
        }

        // Record loop — owns AudioRecord and wake lock cleanup via finally block
        recordJob = scope.launch {
            try {
                recordLoop()
            } catch (e: CancellationException) {
                AppLog.d(TAG, "recordLoop cancelled")
            } catch (e: Exception) {
                AppLog.e(TAG, "FATAL: recordLoop crashed!", e)
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            AppLog.d(TAG, "stopRecording() called but not recording")
            return
        }
        AppLog.i(TAG, "=== STOPPING RECORDING ===")
        isRecording = false
        // recordLoop detects isRecording=false, finalizes encoder, and cleans up AudioRecord + wake lock
        // Cancel periodic uploads (no longer producing files) and trigger one final upload
        val wm = WorkManager.getInstance(this)
        wm.cancelUniqueWork(UploadWorker.WORK_NAME)
        wm.cancelUniqueWork(UPLOAD_FALLBACK_WORK_NAME)
        triggerImmediateUpload()
    }

    fun isCurrentlyRecording() = isRecording

    // ─── Streaming Encoder ───────────────────────────────────────────────

    /**
     * Streams PCM data to AAC encoder incrementally via MediaCodec + MediaMuxer.
     * Created lazily when first voice audio is detected in a chunk.
     * Writes to a .tmp file during encoding, renamed to .m4a on completion
     * so UploadWorker never touches an in-progress file.
     */
    private inner class StreamingEncoder(private val finalFile: File) {
        // Write to .tmp during encoding to prevent UploadWorker from touching it
        private val tmpFile = File(finalFile.parent, finalFile.name + ".tmp")
        private val codec: MediaCodec
        private val muxer: MediaMuxer
        private var trackIndex = -1
        private var muxerStarted = false
        private val bufferInfo = MediaCodec.BufferInfo()
        private var presentationTimeUs = 0L
        private var totalFed = 0L

        init {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch (e: Exception) {
                try { codec.stop() } catch (_: Exception) {}
                try { codec.release() } catch (_: Exception) {}
                throw e
            }
        }

        fun feed(pcmData: ByteArray, length: Int = pcmData.size) {
            var pos = 0
            var stalls = 0
            while (pos < length) {
                val inputIndex = codec.dequeueInputBuffer(1_000L)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                    val size = minOf(length - pos, inputBuffer.capacity())
                    inputBuffer.clear()
                    inputBuffer.put(pcmData, pos, size)
                    codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                    presentationTimeUs += (size.toLong() * 1_000_000L) / (SAMPLE_RATE * 2)
                    pos += size
                    stalls = 0
                } else {
                    stalls++
                    if (stalls > 50) {
                        AppLog.e(TAG, "Encoder stalled — dropping ${pcmData.size - pos} bytes")
                        break
                    }
                }
                drainOutput(blocking = false)
            }
            totalFed += pos
        }

        fun complete(): File? {
            AppLog.d(TAG, "Completing encoder: ${totalFed / 1024}KB PCM fed -> ${finalFile.name}")
            val encodeStart = System.currentTimeMillis()

            try {
                var eosSent = false
                for (i in 0 until 100) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                        break
                    }
                    drainOutput(blocking = false)
                }
                if (!eosSent) {
                    AppLog.w(TAG, "Failed to send EOS after 100 attempts — file may be truncated")
                }

                drainOutput(blocking = true)

                try { codec.stop() } catch (_: Exception) {}
                try { codec.release() } catch (_: Exception) {}
                try { muxer.stop() } catch (_: Exception) {}
                try { muxer.release() } catch (_: Exception) {}

                val elapsed = System.currentTimeMillis() - encodeStart
                val ratio = if (totalFed > 0 && tmpFile.length() > 0)
                    String.format("%.1fx", totalFed.toFloat() / tmpFile.length()) else "?"
                AppLog.i(TAG, "Encoder completed: ${totalFed/1024}KB -> ${tmpFile.length()/1024}KB (${ratio} compression) in ${elapsed}ms")

                if (tmpFile.length() > 0) {
                    if (!tmpFile.renameTo(finalFile)) {
                        AppLog.e(TAG, "Failed to rename ${tmpFile.name} -> ${finalFile.name}, trying copy")
                        try {
                            tmpFile.copyTo(finalFile, overwrite = true)
                            tmpFile.delete()
                        } catch (copyErr: Exception) {
                            AppLog.e(TAG, "Copy fallback also failed", copyErr)
                            return null
                        }
                    }
                    return finalFile
                }
                tmpFile.delete()
                return null

            } catch (e: Exception) {
                AppLog.e(TAG, "Encoder complete FAILED", e)
                release()
                tmpFile.delete()
                return null
            }
        }

        fun release() {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            tmpFile.delete()
        }

        private fun drainOutput(blocking: Boolean) {
            var iterations = 0
            val maxIterations = if (blocking) 1000 else 100
            while (iterations++ < maxIterations) {
                val timeoutUs = if (blocking) 10_000L else 0L
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer == null) {
                            codec.releaseOutputBuffer(outputIndex, false)
                            continue
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> {
                        if (!blocking) return
                    }
                }
            }
        }
    }

    // ─── Record Loop ─────────────────────────────────────────────────────

    private suspend fun recordLoop() {
        val buffer = ShortArray(READ_BUFFER_SAMPLES)
        var chunkStartTime = System.currentTimeMillis()
        var lastVoiceTime = System.currentTimeMillis()
        var hasVoiceInChunk = false
        var chunkNumber = 0
        var readsSinceLastLog = 0
        var voiceReadsSinceLastLog = 0
        var silenceReadsSinceLastLog = 0
        var maxAmplSinceLastLog = 0

        val pcmByteBuffer = ByteArray(READ_BUFFER_SAMPLES * 2) // Pre-allocated — avoids GC in hot loop
        var encoder: StreamingEncoder? = null
        var pcmFed = 0L

        fun startNewChunk() {
            encoder?.release()
            encoder = null

            chunkStartTime = System.currentTimeMillis()
            hasVoiceInChunk = false
            pcmFed = 0L
            chunkNumber++
            totalChunks++

            wakeLock?.acquire()
            enforceStorageLimit()

            AppLog.i(TAG, "New chunk #$chunkNumber")
        }

        try {
            startNewChunk()

            while (isRecording) {
                val ar = audioRecord ?: break
                val read = ar.read(buffer, 0, buffer.size)

                if (read < 0) {
                    totalReadErrors++
                    val errorName = when (read) {
                        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                        else -> "ERROR($read)"
                    }
                    AppLog.e(TAG, "AudioRecord.read() returned $errorName — totalReadErrors=$totalReadErrors")
                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        AppLog.e(TAG, "DEAD OBJECT — mic taken by another app. Attempting recovery...")
                        logAudioState()
                        try { ar.stop() } catch (_: Exception) {}
                        try { ar.release() } catch (_: Exception) {}
                        audioRecord = null

                        // Retry with exponential backoff: 5s, 10s, 20s, 40s, 60s
                        var recovered = false
                        for (attempt in 1..MIC_RECOVERY_MAX_RETRIES) {
                            val backoffMs = minOf(5000L * (1L shl (attempt - 1)), 60_000L)
                            AppLog.i(TAG, "Mic recovery attempt $attempt/$MIC_RECOVERY_MAX_RETRIES in ${backoffMs/1000}s...")
                            delay(backoffMs)
                            if (!isRecording) break
                            try {
                                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                                val readBuf = READ_BUFFER_SAMPLES * 2
                                val intBuf = maxOf(minBuf, readBuf) * 2
                                val newAr = AudioRecord(
                                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, intBuf
                                )
                                if (newAr.state == AudioRecord.STATE_INITIALIZED) {
                                    newAr.startRecording()
                                    audioRecord = newAr
                                    AppLog.i(TAG, "AudioRecord recovered on attempt $attempt")
                                    recovered = true
                                    break
                                } else {
                                    AppLog.w(TAG, "Mic recovery attempt $attempt failed — mic still in use")
                                    newAr.release()
                                }
                            } catch (e: Exception) {
                                AppLog.e(TAG, "Mic recovery attempt $attempt exception", e)
                            }
                        }
                        if (!recovered) {
                            AppLog.e(TAG, "All $MIC_RECOVERY_MAX_RETRIES mic recovery attempts failed — recording will stop")
                        }
                        continue
                    }
                    delay(100)
                    continue
                }

                if (read == 0) { delay(10); continue }

                readsSinceLastLog++

                var maxAmplitude = 0
                for (i in 0 until read) {
                    val abs = kotlin.math.abs(buffer[i].toInt())
                    if (abs > maxAmplitude) maxAmplitude = abs
                }
                if (maxAmplitude > maxAmplSinceLastLog) maxAmplSinceLastLog = maxAmplitude
                val now = System.currentTimeMillis()

                if (maxAmplitude > VAD_THRESHOLD) {
                    lastVoiceTime = now
                    hasVoiceInChunk = true
                    voiceReadsSinceLastLog++
                } else {
                    silenceReadsSinceLastLog++
                }

                val silenceDuration = now - lastVoiceTime
                if (silenceDuration < VAD_SILENCE_TIMEOUT_MS) {
                    val pcmBytes = read * 2
                    for (i in 0 until read) {
                        pcmByteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        pcmByteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }

                    if (encoder == null && hasEnoughDiskSpace()) {
                        try {
                            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(chunkStartTime))
                            val aacFile = File(getChunkDir(), "chunk_${timestamp}.m4a")
                            encoder = StreamingEncoder(aacFile)
                            AppLog.d(TAG, "Encoder created for chunk #$chunkNumber: ${aacFile.name}")
                        } catch (e: Exception) {
                            AppLog.e(TAG, "Failed to create encoder for chunk #$chunkNumber", e)
                        }
                    }

                    encoder?.let { enc ->
                        try {
                            enc.feed(pcmByteBuffer, pcmBytes)
                            pcmFed += pcmBytes
                            totalBytesEncoded += pcmBytes
                        } catch (e: Exception) {
                            AppLog.e(TAG, "Encoder feed error — releasing encoder", e)
                            enc.release()
                            encoder = null
                        }
                    }
                } else {
                    totalSilenceSkipped += read * 2
                }

                val readsPerInterval = (30 * SAMPLE_RATE) / READ_BUFFER_SAMPLES
                if (readsSinceLastLog >= readsPerInterval) {
                    AppLog.d(TAG, "chunk#$chunkNumber: ${pcmFed/1024}KB PCM encoded, reads=$readsSinceLastLog voice=$voiceReadsSinceLastLog silence=$silenceReadsSinceLastLog maxAmpl=$maxAmplSinceLastLog")
                    readsSinceLastLog = 0; voiceReadsSinceLastLog = 0; silenceReadsSinceLastLog = 0; maxAmplSinceLastLog = 0
                }

                if (now - chunkStartTime >= CHUNK_DURATION_MS) {
                    AppLog.i(TAG, "Chunk #$chunkNumber done (${(now - chunkStartTime)/1000}s). hasVoice=$hasVoiceInChunk pcmFed=${pcmFed/1024}KB")

                    val enc = encoder
                    if (enc != null) {
                        chunksWithVoice++
                        encoder = null
                        val encoded = enc.complete()
                        if (encoded != null) {
                            AppLog.i(TAG, "Chunk finalized: ${encoded.name} (${encoded.length()/1024}KB)")
                        } else {
                            AppLog.e(TAG, "Encoding failed for chunk #$chunkNumber — data lost")
                        }
                    } else {
                        chunksWithoutVoice++
                        AppLog.d(TAG, "Chunk #$chunkNumber silent — no encoder created ($chunksWithoutVoice silent total)")
                    }

                    startNewChunk()
                }
            }
        } finally {
            // All cleanup in finally with NonCancellable — runs even if scope is cancelled.
            // This is the ONLY cleanup path (no safety-net in onDestroy), eliminating double-release.
            withContext(NonCancellable) {
                AppLog.i(TAG, "recordLoop: finalizing and cleaning up")

                // Finalize last encoder chunk
                val finalEnc = encoder
                if (finalEnc != null) {
                    encoder = null
                    try {
                        val encoded = finalEnc.complete()
                        if (encoded != null) {
                            AppLog.i(TAG, "Final chunk: ${encoded.name} (${encoded.length()/1024}KB)")
                        }
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Final chunk finalization failed", e)
                        finalEnc.release()
                    }
                } else {
                    AppLog.d(TAG, "Final chunk had no voice — no encoder to finalize")
                }

                // Release AudioRecord
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null

                // Release wake lock
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null

                AppLog.d(TAG, "recordLoop: cleanup complete")
            }
        }
    }

    // ─── Upload Scheduling ───────────────────────────────────────────────

    private fun scheduleUploads() {
        val wm = WorkManager.getInstance(this)

        // Primary: upload on WiFi (every 60min)
        val wifiConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val wifiWork = PeriodicWorkRequestBuilder<UploadWorker>(
            UPLOAD_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).setConstraints(wifiConstraints).build()
        wm.enqueueUniquePeriodicWork(
            UploadWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            wifiWork
        )

        // Fallback: upload on any network (every 4h) — handles Bluetooth proxy when WiFi is off
        val anyNetConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val fallbackWork = PeriodicWorkRequestBuilder<UploadWorker>(
            UPLOAD_FALLBACK_INTERVAL_HOURS, TimeUnit.HOURS
        ).setConstraints(anyNetConstraints).build()
        wm.enqueueUniquePeriodicWork(
            UPLOAD_FALLBACK_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            fallbackWork
        )

        AppLog.i(TAG, "Upload scheduled: every ${UPLOAD_INTERVAL_MINUTES}min (WiFi) + every ${UPLOAD_FALLBACK_INTERVAL_HOURS}h (any network)")
    }

    private fun triggerImmediateUpload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeWork = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "upload_immediate",
            ExistingWorkPolicy.REPLACE,
            oneTimeWork
        )
        AppLog.i(TAG, "One-time upload enqueued for remaining files")
    }

    // ─── Status & Logging ────────────────────────────────────────────────

    private fun logPeriodicStatus() {
        val uptimeMin = (System.currentTimeMillis() - recordingStartTime) / 60000
        val localFiles = getRecordings().size
        val localMB = String.format("%.1f", getStorageUsed() / 1024.0 / 1024.0)
        AppLog.i(TAG, "=== STATUS (${uptimeMin}m uptime) ===")
        AppLog.i(TAG, "  Recording: $isRecording | AudioRecord state: ${audioRecord?.state}")
        AppLog.i(TAG, "  Chunks: $totalChunks total ($chunksWithVoice voice, $chunksWithoutVoice silent)")
        AppLog.i(TAG, "  PCM encoded: ${totalBytesEncoded/1024/1024}MB | Silence skipped: ${totalSilenceSkipped/1024/1024}MB")
        AppLog.i(TAG, "  Local files: $localFiles ($localMB MB) — uploads every ${UPLOAD_INTERVAL_MINUTES}min")
        AppLog.i(TAG, "  Read errors: $totalReadErrors")
        AppLog.i(TAG, "  WakeLock held: ${wakeLock?.isHeld}")
        AppLog.i(TAG, "  Free space: ${getChunkDir().usableSpace / 1024 / 1024}MB")
        logBatteryStatus(); logAudioState()
    }

    private fun logStats() {
        val uptimeSec = if (recordingStartTime > 0) (System.currentTimeMillis() - recordingStartTime) / 1000 else 0
        AppLog.i(TAG, "=== FINAL STATS (${uptimeSec}s uptime) ===")
        AppLog.i(TAG, "  Chunks: $totalChunks ($chunksWithVoice voice, $chunksWithoutVoice silent)")
        AppLog.i(TAG, "  PCM encoded: ${totalBytesEncoded/1024/1024}MB | Silence skipped: ${totalSilenceSkipped/1024/1024}MB")
        AppLog.i(TAG, "  Read errors: $totalReadErrors")
    }

    private fun logBatteryStatus() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            AppLog.d(TAG, "Battery: ${level}% charging=${bm.isCharging}")
        } catch (_: Exception) { AppLog.d(TAG, "Battery: unable to read") }
    }

    private fun logAudioState() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mode = when (am.mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"; AudioManager.MODE_RINGTONE -> "RINGTONE"
                AudioManager.MODE_IN_CALL -> "IN_CALL"; AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"; else -> "mode=${am.mode}"
            }
            AppLog.d(TAG, "Audio: mode=$mode micMuted=${am.isMicrophoneMute} musicActive=${am.isMusicActive}")
            try {
                @Suppress("DEPRECATION")
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val cs = when (tm.callState) {
                    TelephonyManager.CALL_STATE_IDLE -> "IDLE"; TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"; else -> "unknown"
                }
                AppLog.d(TAG, "Phone: callState=$cs")
            } catch (_: Exception) { AppLog.d(TAG, "Phone: unable to read") }
        } catch (_: Exception) { AppLog.d(TAG, "Audio: unable to read") }
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────

    private fun cleanupOrphanedTmpFiles() {
        val dir = File(filesDir, "recordings")
        if (!dir.exists()) return
        val now = System.currentTimeMillis()
        val tmpFiles = dir.listFiles()?.filter { it.extension == "tmp" } ?: emptyList()
        for (tmp in tmpFiles) {
            val ageMs = now - tmp.lastModified()
            if (ageMs > STALE_TMP_THRESHOLD_MS && tmp.length() > 0) {
                // Old .tmp with data — likely a completed encode that crashed before rename.
                // Recover by renaming to .m4a so it gets uploaded.
                val m4aName = tmp.name.removeSuffix(".tmp")
                val recovered = File(dir, m4aName)
                if (tmp.renameTo(recovered)) {
                    AppLog.i(TAG, "Recovered orphaned .tmp → ${recovered.name} (${tmp.length()/1024}KB, age ${ageMs/1000}s)")
                } else {
                    AppLog.w(TAG, "Failed to recover ${tmp.name} — deleting")
                    tmp.delete()
                }
            } else if (ageMs > STALE_TMP_THRESHOLD_MS) {
                // Old but empty — just delete
                AppLog.d(TAG, "Deleting empty orphaned .tmp: ${tmp.name}")
                tmp.delete()
            } else {
                // Recent .tmp — may still be actively encoding (shouldn't happen on startup, but be safe)
                AppLog.d(TAG, "Skipping recent .tmp: ${tmp.name} (age ${ageMs/1000}s)")
            }
        }
    }

    // ─── Storage ─────────────────────────────────────────────────────────

    private fun enforceStorageLimit() {
        val recordings = getRecordings()
        var totalSize = recordings.sumOf { it.length() }
        if (totalSize <= MAX_LOCAL_STORAGE_BYTES) return

        val sorted = recordings.sortedBy { it.lastModified() }
        val target = (MAX_LOCAL_STORAGE_BYTES * 0.8).toLong() // Shrink to 80%
        for (file in sorted) {
            if (totalSize <= target) break
            val size = file.length()
            AppLog.w(TAG, "Storage limit: deleting oldest recording ${file.name} (${size/1024}KB)")
            file.delete()
            totalSize -= size
        }
    }

    private fun hasEnoughDiskSpace(): Boolean {
        val freeSpace = getChunkDir().usableSpace
        if (freeSpace < MIN_FREE_SPACE_BYTES) {
            AppLog.w(TAG, "Low disk space: ${freeSpace / 1024 / 1024}MB free (min ${MIN_FREE_SPACE_BYTES / 1024 / 1024}MB) — skipping encoding")
            return false
        }
        return true
    }

    fun getChunkDir(): File {
        val dir = File(filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getRecordings(): List<File> {
        return getChunkDir().listFiles()?.filter { it.extension == "m4a" }?.sortedBy { it.name } ?: emptyList()
    }

    fun getStorageUsed(): Long = getRecordings().sumOf { it.length() }
}
