package com.hector.spectrum;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.audiofx.AudioEffect;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers the audio sessions that music apps (Spotify and others) announce with a system broadcast when playback starts.
 * Listening to a player's own session still works when the sound is sent to a car (Android Auto), where the global output tap
 * hears nothing. Android only delivers these broadcasts to a running process, so this is started from the always-running
 * notification listener as well as from the page and the car screen.
 */
public final class PlayerSessions {
    private static final String TAG = "SpectViz";
    private static final Map<String, Integer> sessions = new LinkedHashMap<>();
    private static boolean registered;

    private PlayerSessions() { }

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            try {
                String pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
                int id = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
                if (pkg == null) return;
                synchronized (sessions) {
                    if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction()) && id > 0) {
                        sessions.remove(pkg);
                        sessions.put(pkg, id);
                        Log.i(TAG, "audio session opened: " + pkg + " " + id);
                    } else {
                        sessions.remove(pkg);
                        Log.i(TAG, "audio session closed: " + pkg);
                    }
                }
            } catch (Throwable ignored) { }
        }
    };

    /** Safe to call many times. */
    public static synchronized void start(Context ctx) {
        if (registered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            ContextCompat.registerReceiver(ctx.getApplicationContext(), RECEIVER, f, ContextCompat.RECEIVER_EXPORTED);
            registered = true;
        } catch (Throwable t) {
            Log.w(TAG, "could not listen for player sessions: " + t);
        }
    }

    /** Announced sessions, newest first. */
    public static List<Integer> latest() {
        synchronized (sessions) {
            List<Integer> out = new ArrayList<>(sessions.values());
            java.util.Collections.reverse(out);
            return out;
        }
    }

    /** {pkg, id} pairs, oldest first. */
    public static List<Map.Entry<String, Integer>> entries() {
        synchronized (sessions) {
            return new ArrayList<>(sessions.entrySet());
        }
    }
}
