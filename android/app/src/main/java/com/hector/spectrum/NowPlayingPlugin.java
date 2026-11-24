package com.hector.spectrum;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * "Now playing" for whatever media app is running (YouTube, Spotify, ...): sends title, artist and album art
 * to the page, and forwards play / pause / next / previous.
 * Reading the song info needs the user to switch on Notification access for this app once.
 * The transport buttons also work without it, using standard media key presses.
 */
@CapacitorPlugin(name = "SpectMedia")
public class NowPlayingPlugin extends Plugin {
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private ComponentName listenerName;
    private MediaController cur;
    private boolean registered;
    private String lastSig = "";
    private String lastArtKey = "";

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { emit(false); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { pick(); }
        @Override public void onSessionDestroyed() { pick(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = list -> pick();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (registered) pick();
            h.postDelayed(this, 2000);
        }
    };

    @Override
    public void load() {
        Context c = getContext();
        msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listenerName = new ComponentName(c, NowPlayingListener.class);
    }

    private boolean accessEnabled() {
        try {
            return NotificationManagerCompat.getEnabledListenerPackages(getContext()).contains(getContext().getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Starts (or stops) watching media sessions depending on whether Notification access is on. Safe to call repeatedly. */
    @PluginMethod
    public void start(PluginCall call) {
        final PluginCall c = call;
        h.post(() -> {
            boolean en = accessEnabled();
            if (en && !registered) {
                try {
                    msm.addOnActiveSessionsChangedListener(sessionsListener, listenerName, h);
                    registered = true;
                    h.removeCallbacks(tick);
                    h.postDelayed(tick, 2000);
                    pick();
                    emit(true);
                } catch (Throwable t) {
                    registered = false;
                }
            } else if (!en && registered) {
                stopWatching();
                JSObject none = new JSObject();
                none.put("active", false); // access was switched off: hide the card
                notifyListeners("media", none);
            }
            JSObject r = new JSObject();
            r.put("enabled", registered);
            c.resolve(r);
        });
    }

    /** What the followed media session is doing right now (asked directly, not inferred from events). */
    @PluginMethod
    public void state(PluginCall call) {
        final PluginCall c = call;
        h.post(() -> {
            JSObject r = new JSObject();
            MediaController mc = cur;
            if (mc == null) {
                r.put("active", false);
            } else {
                r.put("active", true);
                r.put("playing", isPlaying(mc.getPlaybackState()));
                r.put("title", text(mc.getMetadata(), MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
            }
            c.resolve(r);
        });
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        boolean ok = false;
        Context ctx = getContext();
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 30) {
                i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerName.flattenToString());
            } else {
                i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            ok = true;
        } catch (Throwable first) {
            try {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                ok = true;
            } catch (ActivityNotFoundException e) {
                ok = false;
            } catch (Throwable ignored) { }
        }
        JSObject r = new JSObject();
        r.put("opened", ok);
        call.resolve(r);
    }

    /** action: play | pause | toggle | next | prev */
    @PluginMethod
    public void control(PluginCall call) {
        final String action = call.getString("action", "");
        final PluginCall c = call;
        h.post(() -> {
            boolean done = false;
            String via = "none";
            MediaController mc = cur;
            if (mc != null) {
                // Use the app's own media session only if it says it supports the action; otherwise the
                // media-key path below is used instead. One path or the other, never both, so a tap can't skip twice.
                PlaybackState st = mc.getPlaybackState();
                String act = "toggle".equals(action) ? (isPlaying(st) ? "pause" : "play") : action;
                if (supports(st, act)) {
                    try {
                        MediaController.TransportControls t = mc.getTransportControls();
                        switch (act) {
                            case "next": t.skipToNext(); done = true; break;
                            case "prev": t.skipToPrevious(); done = true; break;
                            case "play": t.play(); done = true; break;
                            case "pause": t.pause(); done = true; break;
                            default: break;
                        }
                        if (done) via = "session";
                    } catch (Throwable ignored) { }
                }
            }
            if (!done) {
                int code = -1;
                switch (action) {
                    case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
                    case "prev": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
                    case "play": code = KeyEvent.KEYCODE_MEDIA_PLAY; break;
                    case "pause": code = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
                    case "toggle": code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
                    default: break;
                }
                if (code != -1) {
                    try {
                        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                        done = true;
                        via = "key";
                    } catch (Throwable ignored) { }
                }
            }
            Log.i("SpectMedia", "control " + action + " via " + via
                + (cur != null ? " (app " + cur.getPackageName() + ")" : " (no session)"));
            JSObject r = new JSObject();
            r.put("ok", done);
            r.put("via", via);
            c.resolve(r);
        });
    }

    /** Does the app's media session say it can do this action right now? */
    private static boolean supports(PlaybackState s, String action) {
        if (s == null) return false;
        long a = s.getActions();
        switch (action) {
            case "next": return (a & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;
            case "prev": return (a & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0;
            case "play": return (a & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0;
            case "pause": return (a & (PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) != 0;
            default: return false;
        }
    }

    private static boolean isPlaying(PlaybackState s) {
        if (s == null) return false;
        int st = s.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING;
    }

    /** Chooses which media session to follow: one that is playing, else the one we already had, else the first. */
    private void pick() {
        if (!registered) return;
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(listenerName);
        } catch (Throwable t) {
            return;
        }
        MediaController chosen = null;
        if (list != null) {
            for (MediaController m : list) {
                if (isPlaying(m.getPlaybackState())) { chosen = m; break; }
            }
            if (chosen == null && cur != null) {
                for (MediaController m : list) {
                    if (m.getSessionToken().equals(cur.getSessionToken())) { chosen = m; break; }
                }
            }
            if (chosen == null && !list.isEmpty()) chosen = list.get(0);
        }
        boolean same = chosen != null && cur != null && chosen.getSessionToken().equals(cur.getSessionToken());
        if (!same) {
            if (cur != null) { try { cur.unregisterCallback(callback); } catch (Throwable ignored) { } }
            cur = chosen;
            if (cur != null) { try { cur.registerCallback(callback, h); } catch (Throwable ignored) { } }
        }
        emit(false);
    }

    private void emit(boolean force) {
        JSObject o = new JSObject();
        String sig;
        String artKey = "";
        Bitmap bmp = null;
        MediaController mc = cur;
        if (mc == null) {
            sig = "none";
            o.put("active", false);
        } else {
            MediaMetadata md = mc.getMetadata();
            String title = text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String artist = text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (artist.isEmpty()) artist = text(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_AUTHOR);
            String album = text(md, MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
            boolean playing = isPlaying(mc.getPlaybackState());
            String pkg = mc.getPackageName();
            if (md != null) {
                bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            artKey = pkg + "|" + title + "|" + artist + "|" + album + "|" + (bmp == null ? 0 : System.identityHashCode(bmp));
            // progress: length of the song (0 if unknown, e.g. live streams) and where it is right now
            long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
            if (dur < 0) dur = 0;
            PlaybackState ps = mc.getPlaybackState();
            long pos = -1;
            float speed = 0;
            long posStamp = 0;
            if (ps != null) {
                pos = ps.getPosition();
                posStamp = ps.getLastPositionUpdateTime();
                if (playing) speed = ps.getPlaybackSpeed();
                if (pos >= 0 && speed > 0 && posStamp > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - posStamp) * speed); // where it is now, not when the app last said
                }
                if (pos >= 0 && dur > 0 && pos > dur) pos = dur;
            }
            sig = title + "|" + artist + "|" + album + "|" + playing + "|" + artKey + "|" + dur + "|" + posStamp
                + "|" + (ps == null ? -1 : ps.getPosition()) + "|" + speed;
            o.put("active", true);
            o.put("title", title);
            o.put("artist", artist);
            o.put("album", album);
            o.put("playing", playing);
            o.put("app", appName(pkg));
            o.put("dur", dur);
            o.put("pos", pos);
            o.put("speed", speed);
        }
        if (!force && sig.equals(lastSig)) return;
        lastSig = sig;
        if (mc != null && (force || !artKey.equals(lastArtKey))) {
            lastArtKey = artKey;
            o.put("art", bmp == null ? "" : encode(bmp));
        }
        notifyListeners("media", o);
    }

    private static String text(MediaMetadata md, String k1, String k2) {
        if (md == null) return "";
        CharSequence a = md.getText(k1);
        if (a == null || a.length() == 0) a = md.getText(k2);
        return a == null ? "" : a.toString();
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = getContext().getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable ignored) { }
        switch (pkg) {
            case "com.spotify.music": return "Spotify";
            case "com.google.android.youtube": return "YouTube";
            case "com.google.android.apps.youtube.music": return "YouTube Music";
            case "com.android.chrome": return "Chrome";
            case "com.amazon.mp3": return "Amazon Music";
            case "com.apple.android.music": return "Apple Music";
            case "deezer.android.app": return "Deezer";
            case "com.pandora.android": return "Pandora";
            default:
                int dot = pkg.lastIndexOf('.');
                return dot >= 0 ? pkg.substring(dot + 1) : pkg;
        }
    }

    private static String encode(Bitmap src) {
        try {
            int w = src.getWidth(), hgt = src.getHeight(), max = Math.max(w, hgt);
            Bitmap b = src;
            if (max > 320) {
                float s = 320f / max;
                b = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(hgt * s)), true);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 82, out);
            if (b != src) b.recycle();
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    private void stopWatching() {
        registered = false;
        h.removeCallbacks(tick);
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Throwable ignored) { }
        if (cur != null) { try { cur.unregisterCallback(callback); } catch (Throwable ignored) { } cur = null; }
        lastSig = "";
        lastArtKey = "";
    }

    @Override
    protected void handleOnDestroy() {
        stopWatching();
    }
}
