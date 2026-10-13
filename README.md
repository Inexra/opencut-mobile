# OpenCut Mobile (Android MVP)

A free, open-source native Kotlin video-editor starter for Android 17 / API 36 and devices such as the Moto G57 Power.

## Current working features
- Native Android interface
- Select a local video using Android's document picker
- Preview video with AndroidX Media3 ExoPlayer
- Set trim start and end times in seconds
- Export a trimmed MP4 using AndroidX Media3 Transformer
- Progress and completion/error feedback
- No account, watermark, ads, cloud service, or paid API required

## Current limitations
This is a first buildable MVP, not a complete CapCut replacement yet. It currently edits one video per export. The timeline is a trim-range control rather than a multi-track, drag-and-drop timeline. AI captions and automatic highlight detection are not implemented in this initial build. Those should be added as separate modules after the core app is tested on-device.

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
