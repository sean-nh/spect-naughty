# Spectr-Acular privacy

Spectr-Acular does not collect, store or share any personal data. There are no accounts, ads, analytics or tracking.

## No internet access

Since version 2.7 the app has **no internet permission**, so it cannot send anything off the device. Everything it shows comes from its own files.

Earlier note, for honesty: versions 1.2 to 1.6 had a news ticker that downloaded public headlines. It was removed. Those versions did not send any information about you, but they did connect to the internet to fetch the headlines.

## Permissions and why

| Permission | Why | What the app does with it |
|---|---|---|
| Audio access (`RECORD_AUDIO`, shown by Android as "record audio") | Android requires it for any app that analyses the sound being played. | The sound is analysed in memory to draw the graphics. Nothing is recorded, saved or sent. If the device's audio cannot be read directly, the microphone is used as a fallback, and Android shows its microphone indicator while it is in use. |
| Audio settings (`MODIFY_AUDIO_SETTINGS`) | Needed by the audio analysis. | Nothing else. |
| Notification access (optional) | Lets the app see which song is playing (title, artist, cover, progress) and send play, pause, next and previous. | It only looks at what music apps publish about the current song. It does not read, store or send your notifications or messages. The visualizer works without it. |

The app explains each permission on screen **before** Android asks, and you can say no. If you update from an older version that already had audio access, nothing is asked again.

## Phone version only: capturing app audio (optional)

In the phone version you can choose **Source: app audio**. Android then shows its own permission dialog (worded as screen sharing). If you allow it, the app reads the sound other apps play, in memory, only to draw the graphics. Nothing is recorded, saved or sent anywhere (the app has no internet permission). Android shows a notification while it is on, and it is off again after you close the app or choose another source.

## What stays on the device

- Your display settings (style, theme, bars and so on).
- A short log of app events (start-ups, errors) shown on the Diagnostics screen. It is only copied anywhere if you choose **Copy report** and paste it yourself.

Uninstalling the app deletes all of it.

## Your control

You can change or withdraw any permission in Android Settings, Apps, Spectr-Acular. The in-app **Privacy** button shows a short version of this page and opens those settings.
