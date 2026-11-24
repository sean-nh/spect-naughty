# Spect-Analyzery

An audio spectrum visualizer for a custom Android car radio (Android 13). It was originally a single web page (`index.html`) and is now packaged as an Android app with Capacitor.

- **App name:** Spect-Analyzery
- **Package ID:** `com.hector.spectanalyzery`. Earlier builds used `com.hector.spectrum` (up to v1.1) and `com.hector.spectnaughty` (v1.6, see the v1.6 release). Because the ID is different, this app installs **next to** those instead of replacing them, and it can never clash with a copy signed with a different key. It starts as a new app: it asks for audio permission again and does not inherit their settings. (The code's internal package folder is still `com/hector/spectrum`; that name is separate from the app's ID.)
- **Version:** 1.7 (versionCode 8)
- **Built:** 2026-11-24 (v1.1 built 2026-11-20)
- **Target:** Android 13 car head unit, landscape. The app also runs on newer Android versions.

## Install

The file to install is **`Spect-Analyzery.apk`** (in this folder). It is a signed release build.

1. You do not need to uninstall an older copy. This app has its own app ID and installs beside older ones. (Uninstall the old one later if you don't want two icons.)
2. Copy the APK to the radio (USB drive or `adb`) and open it.
3. Allow "Install unknown apps" for the app you opened it with (Settings, then Apps, then Special app access, then Install unknown apps).
4. Or use `adb install Spect-Analyzery.apk` (needs USB debugging on the radio).
5. On first launch, tap **While using the app** on the audio permission prompt.

The app asks to "record audio". That is Android's wording. In normal use the app does not use the microphone (see "How it works").

## Using the app

- **Tap the screen** to show the controls. They hide again after 6 seconds.
- **Gain:** manual level. Moving it turns Auto gain off.
- **Auto gain:** adjusts the level automatically (on by default). It holds steady during silence.
- **Bars:** number of bars (16 to 96).
- **Style:** Bars, Mirror, Radial, Wave, Tunnel.
- **Theme:** Aurora, Fire, Ocean, Neon, Ice.
- **Source:** switch between direct audio and the microphone.
- **Long-press the picture** (about 1 second, not on the controls) to toggle the debug badge in the top-left corner.
- **Settings are remembered** between launches (gain, bars, style, theme, auto-gain, source).
- **No music playing:** the screen shows "WAITING FOR MUSIC..." with gentle motion, and dims after about 45 seconds.

## How it works

1. **Direct audio (preferred).** A small native plugin (`VisualizerPlugin.java`) uses Android's `Visualizer` API on the global output mix. This reads the audio the device is playing, without the microphone. It sends about 50 FFT frames per second to the page.
2. **Microphone fallback.** If the direct tap fails, or gives only silence for 6 seconds, the app switches to the microphone (web audio `getUserMedia`) and shows a message.
3. **Permission.** `MainActivity` asks for `RECORD_AUDIO` at launch. The page waits until it is granted.

The Visualizer API needs the `RECORD_AUDIO` permission even though it does not record. Some head units send audio through a separate chip or DSP, so Android never sees it. In that case the Visualizer gives silence and the app falls back to the microphone.

## Project layout

| Path | What it is |
|---|---|
| `index.html` | The whole app UI and logic. **Edit this one.** |
| `www/index.html` | Copy of `index.html` that gets packaged. Do not edit directly. |
| `android/` | The Android project (Capacitor). |
| `android/app/src/main/java/com/hector/spectrum/MainActivity.java` | Fullscreen, keep-screen-on, permission request, WebView mic handling. |
| `android/app/src/main/java/com/hector/spectrum/VisualizerPlugin.java` | Native audio tap. |
| `android/app/src/main/res/` | Icon (a winking devil face with equalizer bars on a pink-purple gradient, plus a single-colour version for themed icons) and app name. |
| `Spect-Analyzery.apk` | The finished release APK. |

## Rebuild

Needs Node.js, Android Studio (for the SDK), and a **JDK 21** (see "Build notes").

```powershell
# 1. after editing index.html
copy index.html www\index.html
npx cap sync android

# 2. build (from the android folder)
cd android
$env:JAVA_HOME="C:\path\to\jdk21"
.\gradlew.bat assembleRelease
# output: android\app\build\outputs\apk\release\app-release.apk

# 3. copy it next to index.html
copy app\build\outputs\apk\release\app-release.apk ..\Spect-Analyzery.apk
```

### Build notes

- **Use JDK 21 for Gradle.** Android Studio's bundled JDK is Java 25. Gradle 8.14.3 fails to compile changed build scripts under it with "Unsupported class file major version 69". Use a Temurin 21 JDK and point `JAVA_HOME` at it.
- **OneDrive placeholder error.** If the build says `assets\public\index.html: not a regular file`, delete `android\app\src\main\assets\public`, run `npx cap sync android` again, and rebuild.
- **OneDrive locks build folders.** If a build fails with "Unable to delete directory", delete `android\app\build` and `node_modules\@capacitor\android\capacitor\build`, then rebuild.
- **Signing.** The release key is kept outside the project (it is never committed) and is read from `SPECT_STORE_FILE`, `SPECT_STORE_PASSWORD`, `SPECT_KEY_ALIAS` and `SPECT_KEY_PASSWORD` in your `~/.gradle/gradle.properties`. **Back up the key file.** If it is lost, future versions can only be installed after uninstalling the old app.
- **Capacitor 8.6.2 quirks.** The injected bridge has no `Capacitor.registerPlugin`, so the page uses `Capacitor.nativePromise(...)` and `Capacitor.addListener(...)` directly. Capacitor's own WebView permission launcher crashes on Android 13 ("unregistered ActivityResultLauncher"), so `MainActivity` handles the audio permission itself.

## What was tested

Tested on an Android 13 (API 33) emulator, a tablet-sized landscape screen:

- Fresh install, permission prompt, and launch: **OK, no crash.**
- Direct audio capture with a test tone: **OK.** The badge showed "direct audio" and the bars followed the sound.
- Styles (Bars, Wave, Tunnel) and themes: **OK.**
- Settings remembered after relaunch: **OK.**
- No audio: shows the idle screen, then falls back to the microphone after 6 seconds: **OK.**
- Release APK signature check (`apksigner verify`): **OK** (v1.1).

## Not tested yet (check these on the real radio)

- Whether the radio's audio is visible to the Visualizer. Turn on the debug badge. It should say **direct audio** while music plays. If it says **microphone**, the radio blocks the tap and the mic is being used.
- Real-world bass response and gain. The direct audio capture has coarse low-frequency resolution (about 40 Hz per step), so the lowest bars may look alike.
- Mirror and Radial styles, Ice, Ocean and Neon themes, and the screen dimming after about 45 seconds of silence. The code is in place but these were not viewed.
- Behavior on the real touch screen: button sizes, auto-hide timing, long-press for debug info.
- Whether the radio keeps the app running in the background or kills it.
- Auto-start on boot is **not implemented** (it was left out on purpose).

## Reporting a bug

Please include:

1. **What you did and what happened,** plus what you expected.
2. **The debug badge text.** Long-press the picture and copy the top-left line, for example `direct audio | raw peak 0.42 | gain 2.1`. A screenshot works.
3. **Which source was active** (direct audio or microphone), and whether music was playing.
4. **Radio details:** make and model, Android version, screen resolution, and whether the radio plays music itself or through another app.
5. **Any on-screen message,** such as "No audio from the radio output" or "Audio unavailable: ...".
6. **Crash logs, if possible.** With the radio connected over USB debugging, run:
   ```powershell
   adb logcat -d > spect-log.txt
   ```
   Send `spect-log.txt` right after the problem happens. To follow one run, clear first with `adb logcat -c`.
7. **Settings in use:** style, theme, Auto gain on or off, and the Bars setting.

### Quick troubleshooting

| Symptom | Try |
|---|---|
| Bars flat while music plays | Check the badge. If it says "direct audio" but the raw peak is about 0, the radio isn't exposing its audio. Tap **Source** to switch to the microphone. |
| Bars move but are too small or too large | Turn Auto gain off and set Gain manually, or turn it back on. |
| "Audio unavailable: NotAllowedError" | Audio permission was denied. Re-enable it in Android Settings, Apps, Spect-Analyzery, Permissions, then reopen the app. |
| App won't install | Allow "Install unknown apps". If it still fails, uninstall an earlier Spect-Analyzery 1.7 or newer that was signed with a different key. |
| Controls won't show | Tap the picture, not the edge of the screen. |
| Start screen shows instead of the visualizer | Tap **Start listening**, or **Demo** to check the display. |
