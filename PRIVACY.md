# ClawHark Privacy Policy

**Last updated:** February 28, 2026

## Overview

ClawHark is an open-source audio recording app for Wear OS. It records audio on your watch and uploads it to **your own** Google Drive account. We do not operate any servers or collect any data.

## Data Collection

**ClawHark does not collect, store, or transmit any personal data to us or any third party.**

### What the app accesses

| Data | Purpose | Where it goes |
|------|---------|---------------|
| Microphone audio | Recording conversations | Saved locally on watch, then uploaded to YOUR Google Drive |
| Google Drive (scoped) | Cloud backup of recordings | Your personal Drive account only, in a "ClawHark" folder |

### What we do NOT collect

- No analytics or telemetry
- No crash reporting to external services
- No advertising identifiers
- No location data
- No contact information
- No usage tracking

## Data Storage & Transfer

1. **On-device:** Audio is recorded as WAV files and stored temporarily on the watch
2. **Google Drive:** Files are uploaded to a "ClawHark" folder in your Google Drive using the `drive.file` scope (the app can only access files it created — it cannot read your other Drive files)
3. **Auto-cleanup:** Local files are deleted after successful upload

All data transfer uses HTTPS encryption. Audio files are not encrypted at rest on the watch or in Drive.

## Google OAuth

ClawHark uses Google's Device Authorization flow to link your Google account. The app requests only the `drive.file` scope, which limits access to files created by the app. You can revoke access at any time via [Google Account permissions](https://myaccount.google.com/permissions).

## Third-Party Services

The app uses only:
- **Google Drive API** — for uploading recordings (governed by [Google's Privacy Policy](https://policies.google.com/privacy))

No other third-party services, SDKs, or analytics tools are included.

## Your Rights

- You can stop recording at any time
- You can sign out to revoke Drive access
- You can delete all recordings from your Google Drive
- You can uninstall the app to remove all local data
- You can revoke OAuth access via your Google Account settings

## Open Source

ClawHark is fully open source. You can inspect the complete source code to verify these claims:
https://github.com/etticat/clawhark

## Children's Privacy

ClawHark is not directed at children under 13. We do not knowingly collect data from children.

## Changes

We may update this policy. Changes will be posted in the GitHub repository and reflected in the "Last updated" date above.

## Contact

For questions about this privacy policy:
- GitHub Issues: https://github.com/etticat/clawhark/issues
