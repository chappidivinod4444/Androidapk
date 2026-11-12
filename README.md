# Mic & Cam Guard

An Android app that shows when any app uses your microphone or camera, keeps a history, and lets you block apps in one tap.

## Install

1. Open the **Releases** page of this repository on your phone.
2. Download `MicCamGuard.apk` from the newest release and open it.
3. Allow "Install unknown apps" for your browser when Android asks.

Every push to `main` builds a new APK automatically (see the **Actions** tab).

## What it does

- **Live status**: tells you the moment the mic or camera is opened by any app.
- **Alerts**: a notification each time, with Block and "Don't alert for this app" buttons.
- **History**: which app, when, and for how long.
- **Apps list**: every app that can use the mic or camera, with one-tap block and allow.
- **Switch off for every app**: the system-wide mic and camera switches.

The app itself never asks for microphone or camera permission and has no internet permission.

## Shizuku

Android does not let a normal app see which app is using the mic, or change another app's permissions.
[Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) gives this app that ability without root.
The Guard tab has a step-by-step setup guide.

Without Shizuku the app still detects and logs every use, but shows "Unknown app" and opens the system settings page instead of blocking directly.
