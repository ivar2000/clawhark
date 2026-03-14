---
name: clawhark
description: "Pull, transcribe, and extract actions from ClawHark watch recordings. Use when: syncing recordings from Google Drive, transcribing audio, scanning transcripts for action items, or checking recording status."
homepage: https://github.com/etticat/clawhark
metadata:
  {
    "openclaw":
      {
        "emoji": "🦀",
        "requires": { "bins": ["ffmpeg", "python3", "curl"] },
      },
  }
---

# ClawHark

ClawHark is an open-source always-on audio recorder for Wear OS watches. This skill manages the server-side pipeline: pulling recordings from Google Drive, transcribing them, and extracting action items.

## When to Use

✅ **USE this skill when:**

- "Sync my watch recordings"
- "Pull recordings from Drive"
- "Transcribe today's recordings"
- "What did I talk about today?"
- "Scan for action items from my watch"
- "Check watch recording status"
- Heartbeat-triggered recording sync

❌ **DON'T use this skill when:**

- Installing or configuring the watch app (see README)
- Google Drive OAuth setup (see app setup guide)
- Issues with the watch hardware itself

## Setup

### 1. Install dependencies

```bash
pip install openai-whisper   # or faster-whisper
brew install ffmpeg
```

### 2. Create credentials file

After completing OAuth on the watch, get a refresh token and create:

```bash
mkdir -p ~/.clawhark
cat > ~/.clawhark/credentials.json << 'EOF'
{
  "client_id": "YOUR_CLIENT_ID.apps.googleusercontent.com",
  "client_secret": "YOUR_CLIENT_SECRET",
  "refresh_token": "YOUR_REFRESH_TOKEN"
}
EOF
```

### 3. Set API key for transcription

```bash
# Option A: AssemblyAI (recommended for speaker diarization)
export ASSEMBLYAI_API_KEY="your-key"

# Option B: Gemini
export GEMINI_API_KEY="your-key"
```

### 4. Install scripts

Copy `scripts/pull.sh` and `scripts/transcribe.py` from the ClawHark repo to your preferred location, or clone the repo:

```bash
git clone https://github.com/etticat/clawhark.git ~/.clawhark/repo
```

## Commands

### Pull recordings from Drive

```bash
~/.clawhark/repo/scripts/pull.sh
```

Downloads all recordings from the ClawHark folder in Google Drive, organises by date, and deletes from Drive after download.

**Environment variables:**
- `CLAWHARK_CREDENTIALS` — path to credentials JSON (default: `~/.clawhark/credentials.json`)
- `CLAWHARK_OUTPUT` — recordings output directory (default: `~/.clawhark/recordings`)

### Transcribe a day

```bash
python3 ~/.clawhark/repo/scripts/transcribe.py 2026-02-28
python3 ~/.clawhark/repo/scripts/transcribe.py 2026-02-28 --provider gemini
```

**4-phase pipeline:**
1. **Whisper** — local speech detection, filters silent chunks
2. **Segment** — groups chunks into conversations by time gaps (>10min gap = new conversation)
3. **Concat** — merges related chunks into single audio files
4. **Diarize** — speaker-separated transcription via AssemblyAI or Gemini

**Output:** `~/.clawhark/transcripts/YYYY-MM-DD-diarized.md`

**Environment variables:**
- `CLAWHARK_OUTPUT` — recordings directory (default: `~/.clawhark/recordings`)
- `CLAWHARK_TRANSCRIPTS` — transcripts directory (default: `~/.clawhark/transcripts`)
- `ASSEMBLYAI_API_KEY` or `GEMINI_API_KEY` — for diarization

### Check for un-transcribed dates

```bash
for dir in ~/.clawhark/recordings/20*/; do
  date=$(basename "$dir")
  transcript=~/.clawhark/transcripts/${date}-diarized.md
  chunks=$(ls "$dir"chunk_*.wav 2>/dev/null | wc -l)
  if [ "$chunks" -gt 0 ] && [ ! -f "$transcript" ]; then
    echo "NEEDS_TRANSCRIPTION: $date ($chunks chunks)"
  fi
done
```

## Automation with OpenClaw

### Cron: Auto-pull every 30 minutes

```bash
openclaw cron create \
  --name "ClawHark Pull" \
  --cron "*/30 8-23 * * *" \
  --message "Run scripts/pull.sh from the ClawHark repo to sync watch recordings from Drive"
```

### Heartbeat: Pull + transcribe check

Add to your `HEARTBEAT.md`:

```markdown
## ClawHark Sync

Pull new watch recordings and check for un-transcribed dates:

\```bash
~/.clawhark/repo/scripts/pull.sh

for dir in ~/.clawhark/recordings/20*/; do
  date=$(basename "$dir")
  transcript=~/.clawhark/transcripts/${date}-diarized.md
  chunks=$(ls "$dir"chunk_*.wav 2>/dev/null | wc -l)
  if [ "$chunks" -gt 0 ] && [ ! -f "$transcript" ]; then
    echo "NEEDS_TRANSCRIPTION: $date ($chunks chunks)"
  fi
done
\```

If any dates need transcription, run in background:
\```bash
python3 ~/.clawhark/repo/scripts/transcribe.py <date>
\```
```

### Action extraction

After transcription, scan the transcript for actionable items:

```markdown
Read today's transcript at ~/.clawhark/transcripts/YYYY-MM-DD-diarized.md

Look for:
- Todos, reminders, follow-ups mentioned by the user
- Promises made to other people ("I'll send you...", "Let me check on...")
- Meeting decisions and key takeaways
- Mentions of the AI assistant by name (trigger for direct tasks)

Create tasks for anything actionable and alert the user with a summary.
```

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `pull.sh` fails with auth error | Refresh token expired — re-auth on the watch |
| No recordings appearing | Check watch WiFi, ensure ClawHark is running |
| Whisper not found | `pip install openai-whisper` |
| Empty transcripts | VAD threshold may be too high — check watch app settings |
| Diarization fails | Check API key is set: `echo $ASSEMBLYAI_API_KEY` |

## Links

- **App:** [Google Play Store](https://play.google.com/store/apps/details?id=ai.etti.clawhark)
- **Source:** [github.com/etticat/clawhark](https://github.com/etticat/clawhark)
- **Privacy:** [Privacy Policy](https://github.com/etticat/clawhark/blob/main/PRIVACY.md)
