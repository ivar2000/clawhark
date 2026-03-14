# ClawHark — Always-On Audio Recording for Wear OS

## What This Is
A Wear OS app for Pixel Watch 3 that continuously records audio 24/7. Simple on/off toggle UI. An external trigger (HTTP endpoint or companion command) pulls recordings and clears memory.

## Requirements

### Core
- **Always-on recording** via a Wear OS foreground service with microphone access
- **On/off toggle** — single screen with a big toggle button, shows recording status and duration
- **Persistent notification** (required by Android for foreground mic service)
- **Voice Activity Detection (VAD)** — only save chunks when someone is speaking (saves battery + storage)
- **Chunked storage** — save audio in 5-minute chunks locally on watch storage
- **Pull & clear** — expose a way to pull all recorded chunks (via HTTP server on watch, or ADB pull from known path) and clear them after transfer

### Technical
- **Target:** Wear OS 4+ (API 33+), Pixel Watch 3
- **Language:** Kotlin
- **Audio format:** OGG/Opus (good compression, low CPU)
- **Storage location:** app-specific internal storage
- **Battery optimization:** VAD to skip silence, low sample rate (16kHz mono), efficient codec
- **Permissions:** RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, WAKE_LOCK, POST_NOTIFICATIONS

### Pull Mechanism
Option A (preferred): Tiny HTTP server on the watch (port 8080) that serves:
- `GET /recordings` — list all chunks with timestamps
- `GET /recordings/{filename}` — download a chunk
- `DELETE /recordings` — clear all chunks after transfer
- `GET /status` — recording state, duration, storage used

Option B (fallback): Just save to a known ADB-accessible path so we can `adb pull` them.

Implement BOTH — HTTP server for programmatic access, known path for ADB fallback.

### UI
- Single screen
- Big toggle (on/off)
- Status: "Recording" / "Stopped"
- Duration since last clear
- Storage used
- Number of chunks

## Build
- Use Gradle with Kotlin DSL
- Target Wear OS (no phone companion needed — standalone)
- Output: APK installable via `adb install`
- Must build from command line: `./gradlew assembleDebug`

## Android SDK
- ANDROID_HOME=~/Library/Android/sdk
- Build tools: 34.0.0
- Platform: android-34
- ADB: ~/Library/Android/sdk/platform-tools/adb
- Java 8 available (may need JDK 17 — install if needed)

## Don't
- No phone companion app
- No cloud sync
- No complex UI
- No Google Play services dependency
