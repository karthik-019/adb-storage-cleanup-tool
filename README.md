# adb-storage-cleanup-tool (OnePlus Cleaner Companion)

This repo contains the companion Android app used by the ADB BAT script to delete SAF-protected folders (Android/media, Android/data, Download).

## How to build the debug APK (cloud via GitHub Actions)
1. Ensure this repo is pushed to GitHub under `main` branch.
2. The workflow `.github/workflows/build-apk.yml` will run on push and produce a downloadable artifact `onepluscleaner-apk` containing `app-debug.apk`.

## If Gradle wrapper is missing
CI expects `gradle/wrapper/gradle-wrapper.jar` to exist. If it's missing, the workflow will download a full Gradle distribution and use it to run the build.
Locally, you can generate the Gradle wrapper by running `gradle wrapper` or by opening the project in Android Studio and letting it generate the wrapper.

## Build locally
Open this project in Android Studio and Build → Build APK(s). The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Safety
- The app uses SAF to delete folders; you must pick folders on the phone when the app launches.
- Back up important files before performing aggressive cleanup.
