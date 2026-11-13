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

The app itself never asks for microphone or camera permission.

## Connecting (one time, no root, no extra app)

Android does not let a normal app see which app is using the mic, or change another app's permissions.
The only way without root is Android's built-in **Wireless debugging**, and this app connects to it directly
(using [libadb-android](https://github.com/MuntashirAkon/libadb-android)). The Guard tab walks you through it:

1. Turn on Developer options (tap Build number 7 times).
2. Tap **Start**, turn on Wireless debugging and tap "Pair device with pairing code".
3. Type the 6-digit code into the app's notification.

After a restart or Wi-Fi change, turn Wireless debugging on again and the app reconnects by itself.
Blocks you made stay in place either way.

Without connecting, the app still detects and logs every use. With **Usage access** allowed it also shows the
app that was on screen at the time, marked "Likely".

The app uses the internet permission only to reach the phone's own debugging service on 127.0.0.1.
