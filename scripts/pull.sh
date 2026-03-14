#!/bin/bash
# Pull ClawHark recordings from Google Drive
# Requires: CLAWHARK_CREDENTIALS (path to JSON with client_id, client_secret, refresh_token)

set -euo pipefail

CREDS_FILE="${CLAWHARK_CREDENTIALS:-$HOME/.clawhark/credentials.json}"
OUTPUT_DIR="${CLAWHARK_OUTPUT:-$HOME/.clawhark/recordings}"

if [ ! -f "$CREDS_FILE" ]; then
  echo "Error: Credentials file not found at $CREDS_FILE"
  echo "Create it with: {\"client_id\": \"...\", \"client_secret\": \"...\", \"refresh_token\": \"...\"}"
  exit 1
fi

CLIENT_ID=$(python3 -c "import json; print(json.load(open('$CREDS_FILE'))['client_id'])")
CLIENT_SECRET=$(python3 -c "import json; print(json.load(open('$CREDS_FILE'))['client_secret'])")
REFRESH_TOKEN=$(python3 -c "import json; print(json.load(open('$CREDS_FILE'))['refresh_token'])")

# Get access token
ACCESS_TOKEN=$(curl -sf -X POST https://oauth2.googleapis.com/token \
  -d "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&refresh_token=$REFRESH_TOKEN&grant_type=refresh_token" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

if [ -z "$ACCESS_TOKEN" ]; then
  echo "Error: Failed to get access token"
  exit 1
fi

# Find ClawHark folder
FOLDER_ID=$(curl -sf -H "Authorization: Bearer $ACCESS_TOKEN" \
  "https://www.googleapis.com/drive/v3/files?q=name='ClawHark'+and+mimeType='application/vnd.google-apps.folder'+and+trashed=false&fields=files(id)" \
  | python3 -c "import sys,json; f=json.load(sys.stdin).get('files',[]); print(f[0]['id'] if f else '')")

if [ -z "$FOLDER_ID" ]; then
  echo "No ClawHark folder found in Drive"
  exit 0
fi

# List files in folder
FILES=$(curl -sf -H "Authorization: Bearer $ACCESS_TOKEN" \
  "https://www.googleapis.com/drive/v3/files?q='$FOLDER_ID'+in+parents+and+trashed=false&fields=files(id,name,size)&orderBy=name&pageSize=100")

FILE_COUNT=$(echo "$FILES" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('files',[])))")
echo "Found $FILE_COUNT files in Drive"

if [ "$FILE_COUNT" -eq 0 ]; then
  exit 0
fi

# Download each file, organize by date
echo "$FILES" | python3 -c "
import sys, json
files = json.load(sys.stdin).get('files', [])
for f in files:
    print(f'{f[\"id\"]} {f[\"name\"]} {f.get(\"size\",\"?\")}')
" | while read FILE_ID FILENAME SIZE; do
  # Extract date from filename (chunk_YYYY-MM-DD_HH-MM-SS.wav)
  DATE=$(echo "$FILENAME" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1)
  if [ -z "$DATE" ]; then
    DATE="unknown"
  fi

  DATE_DIR="$OUTPUT_DIR/$DATE"
  mkdir -p "$DATE_DIR"

  DEST="$DATE_DIR/$FILENAME"
  if [ -f "$DEST" ]; then
    echo "  Skip (exists): $FILENAME"
    continue
  fi

  echo "  Downloading: $FILENAME ($SIZE bytes) → $DATE_DIR/"
  curl -sf -H "Authorization: Bearer $ACCESS_TOKEN" \
    "https://www.googleapis.com/drive/v3/files/$FILE_ID?alt=media" \
    -o "$DEST"

  # Delete from Drive after successful download
  if [ -f "$DEST" ]; then
    curl -sf -X DELETE -H "Authorization: Bearer $ACCESS_TOKEN" \
      "https://www.googleapis.com/drive/v3/files/$FILE_ID" > /dev/null
    echo "  Deleted from Drive: $FILENAME"
  fi
done

echo "Done. Recordings in: $OUTPUT_DIR"
