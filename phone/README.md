# Spectr-Acular for phone and Android Auto

This folder is the **phone** version of Spectr-Acular. It lives next to the radio version in the same repository and shares its web page (`../index.html`) and its Java code (`../android/app/src/main/java`). Nothing is copied, so a fix to one is a fix to both. The radio app in `../android` is not changed by anything in this folder.

| | Radio app | Phone app (this folder) |
|---|---|---|
| App ID | `com.hector.spectanalyzery` | `com.hector.spectanalyzery.phone` (installs next to the radio one) |
| Orientation | landscape | any (upright phone layout) |
| Android Auto screen | no | yes (`SpectCarScreen`) |
| APK | `Spectr-Acular.apk` | `Spectr-Acular-Phone.apk` |

## Build

```powershell
pwsh -NoProfile -File tools\build.ps1 -Target phone
```

Same requirements as the radio build (JDK 21, signing key in `~\.gradle\gradle.properties`). The result is `Spectr-Acular-Phone.apk` in the repository root.

## What works (tested on an Android 17 phone emulator)

- The app installs, shows the audio-access explanation screen first, then the visualizer in portrait. Controls, song card and clock fit a phone screen.
- It reads the audio the phone plays with the same direct audio tap as the radio app.
- The Android Auto service (`SpectCarAppService`) builds and is registered with Android.

## Audio sources on the phone

Press **Source** to cycle: **direct audio** (the phone's output tap, same as the radio app), **app audio**, **microphone**.

- **App audio** (phone build only) uses Android's "capture app audio" feature. Android asks first with its own dialog, worded as screen sharing because it is the same permission screen recorders use. Choose **Share entire screen**, then **Start** (the app only takes sound, not the picture, and nothing is recorded or saved). While it runs Android shows a notification. Use this if direct audio shows flat bars while music plays, which can happen when the phone sends its sound to a car.
- Some apps refuse to be captured. If the bars stay flat in app audio, that app blocks it (not tested with Spotify yet; Chrome on the emulator was silent, the app's own test tone was captured).
- The **microphone** is never started by itself on the phone, because on a phone connected to a car it makes Android interrupt the music.

## Android Auto: status and limits

Android Auto only lets apps in a few Google-approved categories onto the car screen. A visualizer is not one of them. The only place an app may draw its own picture is the **navigation** category, so `SpectCarScreen` is declared as a navigation app and draws spectrum bars into the surface Android Auto gives it (three styles, switched with the on-screen **Style** button).

What this means in practice:

- **Not tested in a car or on the Desktop Head Unit yet.** The head unit emulator (`extras;google;auto`) is installed, but the Android Auto app on the phone emulator is only a stub that needs a Google account to update, so the car screen has not been seen running.
- **Sideloading.** To see the app on a real car screen you must switch on Android Auto's developer mode (tap the version number in Android Auto settings ten times, then Developer settings, Unknown sources). Without that, Android Auto only shows apps installed from Google Play.
- **Google Play.** Putting it on Play (a private internal-testing track works) needs a Google Play developer account (one-time fee) and Google may reject a navigation-category app that does not navigate. This is a risk to confirm before relying on it.
- **Safety.** Android Auto may refuse to show a non-navigation picture while driving. A parked-only mode is planned.
- `SpectCarAppService.createHostValidator()` currently accepts any host so testing works. Before any public release it must list only the real Android Auto hosts.

## Still to do

1. Run it on the Desktop Head Unit or a real car (needs a phone with Android Auto and developer mode).
2. Song title and cover art on the car screen.
3. Parked-only mode and a proper host list.
4. Decide on distribution (sideload with developer mode, or Play internal testing).
