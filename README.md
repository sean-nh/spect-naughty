# Spectr-Acular

An audio spectrum visualizer for a custom Android car radio (Android 13). It was originally a single web page (`index.html`) and is now packaged as an Android app with Capacitor.

- **App name:** Spectr-Acular (earlier names: Spect-Analyzery, and Spect-Naughty for v1.6)
- **Package ID:** `com.hector.spectanalyzery` (v1.7 and newer). Earlier builds used `com.hector.spectrum` (up to v1.1) and `com.hector.spectnaughty` (v1.6). Because the ID is different, this app installs **next to** those instead of replacing them, and it can never clash with a copy signed with a different key. Versions 1.7 and newer update each other normally. (The code's internal package folder is still `com/hector/spectrum`; that name is separate from the app's ID.)
- **Version:** 2.5 (versionCode 15)
- **Built:** 2026-11-24 (v1.1 built 2026-11-20)
- **Target:** Android 13 car head unit, landscape. The app also runs on newer Android versions.

## Install

The file to install is **`Spectr-Acular.apk`** (in this folder). It is a signed release build.

1. You do not need to uninstall an older copy. This app has its own app ID and installs beside older ones. (Uninstall the old one later if you don't want two icons.)
2. Copy the APK to the radio (USB drive or `adb`) and open it.
3. Allow "Install unknown apps" for the app you opened it with (Settings, then Apps, then Special app access, then Install unknown apps).
4. Or use `adb install Spectr-Acular.apk` (needs USB debugging on the radio).
5. On first launch, tap **While using the app** on the audio permission prompt.
6. Optional, for song titles and cover art: tap **Enable Now Playing** in the controls and switch on notification access (see "Now Playing").

The app asks to "record audio". That is Android's wording. In normal use the app does not use the microphone (see "How it works").

## Using the app

- **Tap the screen** to show the controls. They hide again after 6 seconds.
- **Gain:** manual level. Moving it turns Auto gain off.
- **Auto gain:** adjusts the level automatically (on by default). It holds steady during silence.
- **Bars:** number of bars (16 to 96).
- **Style:** Bars, Mirror, Radial, Wave, Tunnel, Gauge (two analog VU needles for bass and treble), Waterfall (scrolling spectrogram) and Particles (sparks that rise with the sound, a burst on every kick).
- **Theme:** Aurora, Fire, Ocean, Neon, Ice, and Album (colours taken from the cover art of the song playing).
- **Swipe on the picture** to change things without the buttons: left or right changes the style, up or down changes the theme.
- **Now Playing buttons:** while the controls are showing, previous, play or pause, and next appear at the top left (see "Now Playing").
- **Diagnostics:** opens a screen with everything useful for a bug report: app and Android version, the radio model, the WebView (browser engine) version, audio source and permission, whether the tap had to be rebuilt, notification access, your settings, a compatibility check of the WebView, and the last 30 events (start-ups, fallbacks, errors). **Copy report** puts it on the clipboard so you can paste it into a message; if the radio will not copy, photograph the screen. The events survive restarts.
- **Tap the picture** and glowing rings spread out from your finger, with a few sparks (fewer in Performance: Light). They are drawn over every style. Taps on buttons and the song card do nothing special.
- **Source:** switch between direct audio and the microphone.
- **Performance:** Normal or Light. Light is for slow screens: it draws about 30 frames per second instead of 60, at lower resolution, with no glow, fewer particles and a plain (not blurred) card background. The app also suggests it once, with a message, if it measures the display running below about 22 frames per second.
- **Clock:** a small, dim clock in the top-right corner (it follows the radio's 12 or 24 hour setting, and gets a little brighter while the controls are showing). The button turns it off.
- **Progress bar:** see "Now Playing".
- **Enable Now Playing:** only shows while notification access is off. It opens the Android screen where you switch it on.
- **Long-press the picture** (about 1 second, not on the controls) to toggle the debug badge in the top-left corner.
- **Settings are remembered** between launches (gain, bars, style, theme, auto-gain, source).
- **No music playing:** the screen shows "WAITING FOR MUSIC..." with gentle motion, and dims after about 45 seconds.

## How it works

1. **Direct audio (preferred).** A small native plugin (`VisualizerPlugin.java`) uses Android's `Visualizer` API on the global output mix. This reads the audio the device is playing, without the microphone. It sends about 50 FFT frames per second to the page.
2. **Microphone fallback.** If the direct tap fails, or gives only silence for 6 seconds, the app switches to the microphone (web audio `getUserMedia`) and shows a message.
3. **Permission.** `MainActivity` asks for `RECORD_AUDIO` at launch. The page waits until it is granted.

The Visualizer API needs the `RECORD_AUDIO` permission even though it does not record. Some head units send audio through a separate chip or DSP, so Android never sees it. In that case the Visualizer gives silence and the app falls back to the microphone.

### Keeping direct audio healthy (2.4)

A small watchdog looks after the direct tap while the app runs:

- **The tap rebuilds itself.** If Android takes the tap away (for example the audio service restarts), the plugin notices within about half a second and builds a new one, retrying with a growing pause. Tested by killing the audio service during a song: it was back after about a second. The debug badge shows `tap restarted x1` (and so on).
- **Stuck or silent while music plays.** If frames stop arriving, or stay silent for 8 seconds while Android says music is playing, the tap is restarted (up to 3 times). If that does not help, the microphone takes over and says so. A pause is not mistaken for a fault, because Android reports whether anything is really playing.
- **The microphone is a temporary fallback.** After falling back, every 15 seconds the app quietly tries the direct tap for about 2.5 seconds. As soon as it hears real sound (the radio's music starts, the route is fixed) the app switches back to direct audio on its own. The badge shows `mic fallback` while this is in effect.
- **"Heard audio" means real sound.** At least 10 frames with a clearly non-zero bin above the lowest few. A single stray frame at startup, or the tiny hum some devices put in the lowest bins, no longer counts, so the 6 second fallback to the microphone works when nothing is coming through.
- The page's own microphone and audio engine are fully shut while direct audio is in use. Left open, they make Android think music is playing.

### Crash protection (2.5)

- **A broken style cannot freeze the app.** Each frame is drawn inside a safety net. If a style keeps failing, the app switches to Bars after the third failure, says so, and logs it. If the display keeps failing (60 errors), the page reloads itself once.
- **Safe start.** If the app dies three starts in a row before it has been running for 6 seconds, the next start forgets the saved settings and begins with the plainest setup (Bars, default theme, Performance: Light), shows "Started in safe mode", and logs it. Closing the app normally, or running for 6 seconds, counts as a good start.
- **Page errors are logged** (not hidden) and shown in Diagnostics.

## Compatibility

- Built for Android 7.0 (API 24) up to Android 16 (API 36).
- Tested on an Android 13 (API 33) emulator, and earlier builds also ran on an API 37 emulator. Not yet tested on a real radio or on other Android versions.
- Old browser engines ("Android System WebView"): the page's script is written for Chrome 58 or newer (checked automatically at the 2017 JavaScript level), and the styling no longer needs `inset`, flex `gap`, or `clamp()` (each has a plain fallback in front of it), so even an old WebView should show a usable screen. The WebView version and a feature check are in Diagnostics.
- Android Automotive OS (the car maker's built-in system) is a different platform and is not covered: this app is for aftermarket radios running normal Android.

## Now Playing

The top-left card shows the cover art, song title, artist, album and the app that is playing (YouTube, Spotify, YouTube Music, a browser, and so on). The three buttons control that same app.

- **Progress bar (2.2).** Under the app name: elapsed time, a thin bar in the current theme colour, and the total length (for example `1:23 ===---- 3:45`). It moves smoothly between the updates the phone sends, freezes when the song is paused, and follows seeks made in the music app. It is display only (no seeking from this screen). It is hidden when the app does not report a length, such as live streams or radio-style stations, and when song info is off.

- **Song info needs "notification access" once.** Android only lets an app read what other apps are playing after the user switches this on. Tap **Enable Now Playing**, find the app in the list and turn it on. The card appears within a few seconds, and disappears again if you turn access off.
- **Android 13 "restricted settings".** For an app installed from an APK file (not from the Play Store), the switch can be greyed out. Fix: Android Settings, Apps, this app, the three-dot menu, **Allow restricted settings**, then try again. Some head units hide this screen entirely.
- **Buttons work without access.** If notification access is off, the buttons still appear (just the buttons, while the controls are showing) and send standard media-key presses to whatever app Android considers active. That worked for pause, play, next and previous in testing. In this mode the middle button cannot know the current state, so its icon only changes when you press it.
- **Media-key backup (2.1).** Each button press takes exactly one of two paths. If the playing app's media session says it supports the action (next, previous, play, pause), the app's own controls are used. If it does not list the action, a standard media key is sent instead, which some apps answer even when they do not advertise the action. One path or the other, never both, so a tap cannot skip two songs. The debug badge ends with `media session` or `media key` to show which one the last press used, and `adb logcat -s SpectMedia` prints it too.
- **How it is built.** `NowPlayingPlugin.java` follows Android's active media session (the same thing the lock screen uses), prefers the one that is playing, and sends metadata to the page. `NowPlayingListener.java` is an empty service whose only job is to make the app appear in the notification-access list.

## Project layout

| Path | What it is |
|---|---|
| `index.html` | The whole app UI and logic. **Edit this one.** |
| `www/` | What gets packaged: a copy of `index.html` (do not edit it directly) plus the images the page uses. |
| `android/` | The Android project (Capacitor). |
| `android/app/src/main/java/com/hector/spectrum/MainActivity.java` | Fullscreen, keep-screen-on, permission request, WebView mic handling. |
| `android/app/src/main/java/com/hector/spectrum/VisualizerPlugin.java` | Native audio tap. |
| `android/app/src/main/java/com/hector/spectrum/NowPlayingPlugin.java` | Song info and play, pause, next, previous. |
| `android/app/src/main/java/com/hector/spectrum/NowPlayingListener.java` | Empty service that enables the notification-access switch. |
| `android/app/src/main/res/` | Icon (a gauge-style ring of spectrum bars sweeping cyan to red around a play button, on dark carbon, plus a single-colour version for themed icons) and app name. |
| `Spectr-Acular.apk` | The finished release APK. |

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
copy app\build\outputs\apk\release\app-release.apk ..\Spectr-Acular.apk
```

### Build notes

- **Use JDK 21 for Gradle.** Android Studio's bundled JDK is Java 25. Gradle 8.14.3 fails to compile changed build scripts under it with "Unsupported class file major version 69". Use a Temurin 21 JDK and point `JAVA_HOME` at it.
- **OneDrive placeholder error.** If the build says `assets\public\index.html: not a regular file`, delete `android\app\src\main\assets\public`, run `npx cap sync android` again, and rebuild.
- **OneDrive "not a regular file" in the build folder.** If the build says `...\android\app\build\...: not a regular file`, stop Gradle (`.\gradlew.bat --stop`), delete `android\app\build`, `android\build` and `node_modules\@capacitor\android\capacitor\build`, then rebuild.
- **OneDrive locks build folders.** If a build fails with "Unable to delete directory", delete `android\app\build` and `node_modules\@capacitor\android\capacitor\build`, then rebuild.
- **Keep Gradle at 8.14.3.** `android/gradle/wrapper/gradle-wrapper.properties` must say `gradle-8.14.3`. If an IDE offers to upgrade the wrapper to Gradle 9.x, decline: the Android Gradle Plugin used here (8.13) then fails with "relies on 'InternalProblems', a Gradle internal API that was removed". Restore the file with `git checkout android/gradle/wrapper/gradle-wrapper.properties`.
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

Version 2.0, same emulator, with Chrome playing a test track (title, artist, album, cover art and a looping beat) as the media source:

- Song title, artist, album, cover art and app name appear on the card: **OK.**
- Previous, pause, play and next, checked against Chrome's own state each time (track changed, playing and paused states flipped): **OK.**
- Switching notification access on and off while the app runs: card appears and disappears, and the Album theme picks up the cover colour: **OK.**
- Buttons with access off (media-key fallback): pause, play, next and previous all worked: **OK.**
- **Enable Now Playing** opens the notification-access screen for this app: **OK.**
- Gauge, Waterfall and Particles reacting to real audio, and swipe left, right and up changing style and theme: **OK.**

Version 2.1, same emulator and Chrome test source:

- Clock shows top right, dim, and brightens with the controls; the Clock button is in the controls: **OK.**
- Performance: Normal ran at 61 fps and Light at 30 fps (shown in the debug badge), with fewer and lighter particles: **OK.** The emulator is fast, so the automatic "display looks slow" suggestion was **not** triggered or tested.
- Diagnostics screen (2.5): shows the real app, Android and WebView versions, audio and notification state, settings, compatibility checks and the event log; scrolls; **Copy report** copies it: **OK.**
- Touch ripples (2.5): appear at the tap point on Bars, Gauge, Waterfall and Particles: **OK.**
- Old-browser changes (2.5): the layout after replacing `gap`, `inset` and `clamp()` looks the same as before in the emulator's WebView (Chrome 109): **OK.** The script was also checked to parse at the 2017 JavaScript level.
- Crash protection (2.5): a style that was deliberately made to crash switched itself to Bars after three errors while the music kept playing; a pretend "three bad starts" plus odd saved settings started in safe mode with defaults: **OK** (both tested with throwaway builds that were not shipped).
- Audio watchdog (2.4): killing Android's audio service during playback made the tap stop answering and be rebuilt about a second later, with the bars still reacting (`tap restarted x1`): **OK.** Starting with nothing playing falls back to the microphone after about 6 seconds and stays there (no flapping); starting music afterwards switches back to direct audio on its own: **OK.**
- Progress bar (2.2): shows elapsed and total time from Chrome's media session, grows at the right speed (about 12 px per second for a 30 second track), freezes on pause, resumes on play, and wraps when the track loops: **OK.** Measured from screenshots, not just by eye.
- Media-key backup: with a session that lists skip actions, Next went through the session; with a session that does not list them (actions without skip), Next and Previous went through media keys, and Pause still used the session: **OK.**

## Not tested yet (check these on the real radio)

- Whether the radio's audio is visible to the Visualizer. Turn on the debug badge. It should say **direct audio** while music plays. If it says **microphone**, the radio blocks the tap and the mic is being used.
- Real-world bass response and gain. The direct audio capture has coarse low-frequency resolution (about 40 Hz per step), so the lowest bars may look alike.
- The progress bar with real streaming apps: each app decides whether it reports a length and how often it updates the position. A restart of the bar to zero on a new song was not tested (the Chrome test page only changes the title on Next).
- An actually old WebView: the emulator's is Chrome 109. The old-browser changes are by inspection and syntax check, not by running on an old engine.
- The "silent while Android says music is playing" path (restart the tap up to 3 times, then use the microphone) is written but could not be simulated on the emulator.
- Song info and the buttons with **YouTube, Spotify and other real streaming apps**. Only a test page in Chrome was used. Apps that publish a standard media session should work the same, but apps differ in what they publish, and some give no cover art.
- Whether the real radio has the notification-access screen, and whether "Allow restricted settings" is needed or even available there.
- Mirror and Radial styles, Ice, Ocean and Neon themes, and the screen dimming after about 45 seconds of silence. The code is in place but these were not viewed.
- Behavior on the real touch screen: button sizes, auto-hide timing, long-press for debug info.
- Whether the radio keeps the app running in the background or kills it.
- Auto-start on boot is **not implemented** (it was left out on purpose).

## Reporting a bug

Please include:

1. **What you did and what happened,** plus what you expected.
2. **The debug badge text.** Long-press the picture and copy the top-left line, for example `direct audio | raw peak 0.42 | gain 2.1 | fps 58`. It also shows `light` when Performance: Light is on, `media session` or `media key` after you press a Now Playing button, `tap restarted xN` if the direct tap had to be rebuilt, and `mic fallback` while the microphone is standing in for direct audio. A screenshot works.
3. **Which source was active** (direct audio or microphone), and whether music was playing. For Now Playing problems: which app was playing (YouTube, Spotify, ...) and whether notification access is on.
4. **Radio details:** make and model, Android version, screen resolution, and whether the radio plays music itself or through another app.
5. **Any on-screen message,** such as "No audio from the radio output" or "Audio unavailable: ...".
6. **The Diagnostics report.** In the controls tap **Diagnostics**, then **Copy report**, and paste it into your message (or photograph the screen). It already contains most of what is listed above.
7. **Crash logs, only if more is needed.** With the radio connected over USB debugging, run:
   ```powershell
   adb logcat -d > spect-log.txt
   ```
   Send `spect-log.txt` right after the problem happens. To follow one run, clear first with `adb logcat -c`.
8. **Settings in use:** style, theme, Auto gain on or off, and the Bars setting.

### Quick troubleshooting

| Symptom | Try |
|---|---|
| Bars flat while music plays | Check the badge. If it says "direct audio" but the raw peak is about 0, the radio isn't exposing its audio. Tap **Source** to switch to the microphone. |
| Bars move but are too small or too large | Turn Auto gain off and set Gain manually, or turn it back on. |
| "Audio unavailable: NotAllowedError" | Audio permission was denied. Re-enable it in Android Settings, Apps, Spectr-Acular, Permissions, then reopen the app. |
| App won't install | Allow "Install unknown apps". If it still fails, uninstall an earlier copy of this app (version 1.7 or newer) that was signed with a different key. |
| No song title or cover art | Tap **Enable Now Playing** and switch on notification access. If the switch is greyed out, use App info, the menu, **Allow restricted settings**. The play, next and previous buttons work without it. |
| Song info is for the wrong app | The app follows whichever media app is playing. Pause the other one, or close it. |
| Choppy or slow animation | Tap **Performance** to switch to Light. Check the `fps` number in the debug badge. |
| Clock is in the way | Tap **Clock** in the controls to turn it off. |
| Controls won't show | Tap the picture, not the edge of the screen. |
| Start screen shows instead of the visualizer | Tap **Start listening**, or **Demo** to check the display. |
