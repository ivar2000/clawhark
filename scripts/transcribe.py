#!/usr/bin/env python3
"""
ClawHark Transcription Pipeline

4-phase pipeline to turn watch recordings into speaker-diarized transcripts.

Usage:
    python3 transcribe.py 2026-02-28
    python3 transcribe.py 2026-02-28 --provider gemini

Phases:
    1. Whisper  — local speech detection, filter silent chunks
    2. Segment  — group chunks into conversations by time gaps
    3. Concat   — merge related chunks into conversation audio
    4. Diarize  — speaker-separated transcription (AssemblyAI or Gemini)

Requirements:
    - ffmpeg + ffprobe
    - whisper (pip install openai-whisper) OR faster-whisper
    - AssemblyAI API key (ASSEMBLYAI_API_KEY env var) OR Gemini API key (GEMINI_API_KEY)
"""

import argparse
import glob
import json
import os
import subprocess
import sys
from datetime import datetime, timedelta
from pathlib import Path

def get_recordings_dir():
    return os.environ.get("CLAWHARK_OUTPUT", os.path.expanduser("~/.clawhark/recordings"))

def get_transcripts_dir():
    return os.environ.get("CLAWHARK_TRANSCRIPTS", os.path.expanduser("~/.clawhark/transcripts"))

def phase1_detect_speech(date_dir, chunks):
    """Use whisper to detect which chunks have actual speech."""
    print(f"\n📝 Phase 1: Speech detection ({len(chunks)} chunks)")
    speech_chunks = []

    for chunk in chunks:
        # Quick check with whisper tiny model
        try:
            result = subprocess.run(
                ["whisper", str(chunk), "--model", "tiny", "--language", "en",
                 "--output_format", "json", "--output_dir", "/tmp/clawhark_whisper"],
                capture_output=True, text=True, timeout=30
            )
            json_out = Path(f"/tmp/clawhark_whisper/{chunk.stem}.json")
            if json_out.exists():
                data = json.loads(json_out.read_text())
                text = data.get("text", "").strip()
                if len(text) > 10:  # More than just noise
                    speech_chunks.append(chunk)
                    print(f"  ✅ {chunk.name}: {text[:60]}...")
                else:
                    print(f"  ⏭️  {chunk.name}: silent/noise")
                json_out.unlink()
        except (subprocess.TimeoutExpired, FileNotFoundError):
            # If whisper not available, include all chunks
            speech_chunks.append(chunk)

    print(f"  {len(speech_chunks)}/{len(chunks)} chunks have speech")
    return speech_chunks

def phase2_segment(chunks):
    """Group chunks into conversations based on time gaps."""
    print(f"\n🔗 Phase 2: Segmentation")
    if not chunks:
        return []

    conversations = []
    current = [chunks[0]]

    for i in range(1, len(chunks)):
        # Parse timestamps from filenames: chunk_YYYY-MM-DD_HH-MM-SS.wav
        prev_name = chunks[i-1].stem
        curr_name = chunks[i].stem

        try:
            prev_time = datetime.strptime(prev_name.split("chunk_")[1], "%Y-%m-%d_%H-%M-%S")
            curr_time = datetime.strptime(curr_name.split("chunk_")[1], "%Y-%m-%d_%H-%M-%S")
            gap = (curr_time - prev_time).total_seconds()
        except (ValueError, IndexError):
            gap = 300  # Default 5min gap

        if gap > 600:  # >10 min gap = new conversation
            conversations.append(current)
            current = [chunks[i]]
            print(f"  📍 Gap of {gap/60:.0f}min → new conversation")
        else:
            current.append(chunks[i])

    conversations.append(current)
    print(f"  Found {len(conversations)} conversation(s)")
    return conversations

def phase3_concat(conversations, output_dir):
    """Concatenate chunks in each conversation into single audio files."""
    print(f"\n🔊 Phase 3: Concatenation")
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    outputs = []

    for i, conv in enumerate(conversations):
        if len(conv) == 1:
            outputs.append(conv[0])
            continue

        output = output_dir / f"conversation_{i+1}.wav"
        filelist = output_dir / f"filelist_{i+1}.txt"

        with open(filelist, "w") as f:
            for chunk in conv:
                f.write(f"file '{chunk.absolute()}'\n")

        subprocess.run([
            "ffmpeg", "-y", "-f", "concat", "-safe", "0",
            "-i", str(filelist), "-c", "copy", str(output)
        ], capture_output=True)

        filelist.unlink()
        print(f"  Merged {len(conv)} chunks → {output.name}")
        outputs.append(output)

    return outputs

def phase4_diarize(audio_files, transcript_path, provider="assemblyai"):
    """Speaker-diarized transcription."""
    print(f"\n🎙️ Phase 4: Diarization ({provider})")

    transcript_path = Path(transcript_path)
    transcript_path.parent.mkdir(parents=True, exist_ok=True)

    all_text = []

    for audio in audio_files:
        print(f"  Transcribing: {audio.name}")

        if provider == "assemblyai":
            text = _diarize_assemblyai(audio)
        elif provider == "gemini":
            text = _diarize_gemini(audio)
        else:
            text = f"Unknown provider: {provider}"

        all_text.append(text)

    full_transcript = "\n\n---\n\n".join(all_text)
    transcript_path.write_text(full_transcript)
    print(f"\n✅ Transcript saved: {transcript_path}")
    return transcript_path

def _diarize_assemblyai(audio_path):
    """Transcribe with AssemblyAI Universal-3 + speaker diarization."""
    import requests

    api_key = os.environ.get("ASSEMBLYAI_API_KEY")
    if not api_key:
        return "Error: ASSEMBLYAI_API_KEY not set"

    headers = {"authorization": api_key}

    # Upload
    with open(audio_path, "rb") as f:
        upload = requests.post("https://api.assemblyai.com/v2/upload",
                             headers=headers, data=f)
    upload_url = upload.json()["upload_url"]

    # Transcribe with diarization
    resp = requests.post("https://api.assemblyai.com/v2/transcript",
                        headers=headers,
                        json={"audio_url": upload_url, "speaker_labels": True})
    transcript_id = resp.json()["id"]

    # Poll
    while True:
        result = requests.get(f"https://api.assemblyai.com/v2/transcript/{transcript_id}",
                            headers=headers).json()
        if result["status"] == "completed":
            break
        elif result["status"] == "error":
            return f"Error: {result.get('error', 'unknown')}"
        import time; time.sleep(3)

    # Format with speakers
    lines = []
    for utterance in result.get("utterances", []):
        speaker = utterance["speaker"]
        text = utterance["text"]
        start = utterance["start"] / 1000
        lines.append(f"**Speaker {speaker}** ({start:.0f}s): {text}")

    return "\n\n".join(lines) if lines else result.get("text", "No text")

def _diarize_gemini(audio_path):
    """Transcribe with Gemini multimodal."""
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        return "Error: GEMINI_API_KEY not set"

    import base64, requests

    with open(audio_path, "rb") as f:
        audio_b64 = base64.b64encode(f.read()).decode()

    resp = requests.post(
        f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={api_key}",
        json={
            "contents": [{"parts": [
                {"inlineData": {"mimeType": "audio/wav", "data": audio_b64}},
                {"text": "Transcribe this audio with speaker diarization. Format as:\n**Speaker A** (timestamp): text\n\nIdentify different speakers and label them consistently."}
            ]}]
        }
    )

    result = resp.json()
    try:
        return result["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError):
        return f"Error: {json.dumps(result)[:200]}"

def main():
    parser = argparse.ArgumentParser(description="ClawHark Transcription Pipeline")
    parser.add_argument("date", help="Date to transcribe (YYYY-MM-DD)")
    parser.add_argument("--provider", default="assemblyai", choices=["assemblyai", "gemini"],
                       help="Transcription provider (default: assemblyai)")
    parser.add_argument("--recordings", default=None, help="Recordings directory")
    parser.add_argument("--transcripts", default=None, help="Transcripts directory")
    args = parser.parse_args()

    recordings_dir = Path(args.recordings or get_recordings_dir())
    transcripts_dir = Path(args.transcripts or get_transcripts_dir())

    date_dir = recordings_dir / args.date
    if not date_dir.exists():
        print(f"No recordings found for {args.date} in {recordings_dir}")
        sys.exit(1)

    chunks = sorted(date_dir.glob("chunk_*.wav")) + sorted(date_dir.glob("chunk_*.m4a"))
    if not chunks:
        print(f"No audio chunks found in {date_dir}")
        sys.exit(1)

    print(f"🎧 ClawHark Transcription Pipeline")
    print(f"   Date: {args.date}")
    print(f"   Chunks: {len(chunks)}")
    print(f"   Provider: {args.provider}")

    # Phase 1: Speech detection
    speech_chunks = phase1_detect_speech(date_dir, chunks)
    if not speech_chunks:
        print("\nNo speech detected in any chunks.")
        sys.exit(0)

    # Phase 2: Segment into conversations
    conversations = phase2_segment(speech_chunks)

    # Phase 3: Concatenate
    concat_dir = date_dir / "concat"
    audio_files = phase3_concat(conversations, concat_dir)

    # Phase 4: Diarize
    transcript_path = transcripts_dir / f"{args.date}-diarized.md"
    phase4_diarize(audio_files, transcript_path, args.provider)

if __name__ == "__main__":
    main()
