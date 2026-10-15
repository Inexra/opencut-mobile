# OpenCut Mobile (Android MVP)

A free, open-source native Kotlin video-editor starter for Android 17 / API 36 and devices such as the Moto G57 Power.

## Current working features
- Native Android interface
- Import multiple local videos using Android's document picker
- A basic clip-list timeline: select, reorder, and remove clips
- Preview the selected video with AndroidX Media3 ExoPlayer
- Play/pause and preview-speed controls (1×, 1.25×, 1.5×, 2×)
- Set trim start/end from the current preview playhead
- Set trim start and end times manually in seconds
- Export the selected clip as a trimmed MP4 using AndroidX Media3 Transformer
- Progress and completion/error feedback
- No account, watermark, ads, cloud service, or paid API required

## Current limitations
This is an evolving MVP, not a complete CapCut replacement yet. Multiple clips can be collected, selected, reordered, and removed in a basic list, but the app does not yet join all clips into one export or provide a multitrack waveform timeline. Text/subtitle overlays, speech-to-text captions, automatic highlight detection, and AI-assisted Shorts generation are not implemented yet. The current export trims only the selected clip.

## Build
Requirements:
- Android Studio with Android SDK Platform 36
- JDK 17 or 21
- Android Gradle Plugin 8.13.2-compatible Gradle (8.13 recommended)

Open this folder in Android Studio, allow Gradle sync, then use **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

Or from a terminal with Gradle 8.13 installed:
```bash
gradle assembleDebug
```
APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## Install on Moto G57 Power
Copy the debug APK to the phone and open it. If Android asks, allow installation from that file manager/browser. Use Android's standard picker to grant access to each video you edit.

## Technical choices
- Kotlin + Android Views to keep the initial app lean
- Media3 ExoPlayer for preview
- Media3 Transformer for export
- Android Storage Access Framework for input selection
- Scoped app-specific cache for temporary export files

Media3's editing APIs support clipping, scaling, rotation, overlays and export. See Android's official guide:
https://developer.android.com/media/implement/editing-app

## License
This starter project is released under the MIT License. Media3 remains under its own license.

## Build entirely on GitHub (no Android Studio required)

1. Create a new GitHub repository, for example `opencut-mobile`.
2. Upload the contents of this folder to the repository root. Include the hidden `.github` folder and its workflow file.
3. Open the repository's **Actions** tab and select **Build OpenCut Mobile APK**.
4. If the workflow has not already started, choose **Run workflow**.
5. Open the completed workflow run and download the artifact named `OpenCut-Mobile-debug-APK`.
6. Extract the downloaded artifact ZIP to get `app-debug.apk`, then transfer it to your phone and install it.

Every push to `main` or `master` starts a build automatically. GitHub Actions compiles the app on a hosted Linux runner, so your laptop does not need Android Studio or a local Android SDK. The repository must have GitHub Actions enabled.
