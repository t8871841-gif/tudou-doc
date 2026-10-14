# JARVIS AI Android App

Independent Android app prototype for Android 8.0+ (API 26+), designed around a blue holographic HUD interface.

## Features in this first version
- Animated circular HUD interface
- OpenAI-compatible Chat Completions endpoint configuration
- API base URL, API key, and model settings
- Chinese speech recognition via Android's installed speech-recognition provider
- Chinese text-to-speech via Android TTS
- In-session chat log

## Build APK in GitHub Actions
1. Create a new GitHub repository and upload all files in this folder.
2. Open the repository's **Actions** tab.
3. Select **Build JARVIS AI APK** and click **Run workflow** (or push to `main`).
4. Open the completed workflow run and download the `JARVIS-AI-debug-apk` artifact.
5. Extract the artifact ZIP on your phone and install `app-debug.apk`. Android may ask you to allow installation from that source.

The current chat log stays in memory for the running session. API settings are saved locally in app preferences. Use HTTPS endpoints where possible; the app enables cleartext traffic only to support compatible self-hosted endpoints. Do not share your API key.

## Important
This repository is an initial functional implementation. The build environment in the ChatGPT workspace does not include Android SDK/Gradle, so APK compilation must be performed by the included GitHub Actions workflow or another Android build environment. This source bundle is not itself an APK.
