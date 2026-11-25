package com.hector.spectrum;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.media.audiofx.AudioEffect;
import java.util.LinkedHashMap;
import java.util.Map;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebView;
import android.media.audiofx.Visualizer;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Taps Android's global audio output mix (no microphone) and streams FFT frames to the web page.
 * If the tap stops answering (for example the audio service restarts and takes it away) it is rebuilt automatically.
 */
@CapacitorPlugin(name = "SpectVisualizer")
public class VisualizerPlugin extends Plugin {
    private static final String TAG = "SpectViz";

    private volatile Visualizer viz;
    private final Object swapLock = new Object();
    private Thread worker;
    private volatile boolean running;
    private volatile int gen; // bumped on every start / stop so an old worker thread can never keep running next to a new one

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    static final int AUDIO_REQUEST = 77;
    private static VisualizerPlugin instance;

    // Music apps (Spotify and others) announce the audio session of their player with a system broadcast when playback starts.
    // Listening to that session (instead of the global output) can still work when the sound is sent to a car, where the global tap is silent.
    private final Map<String, Integer> playerSessions = new LinkedHashMap<>();
    private volatile int session = 0; // 0 = global output mix, otherwise one player's audio session
    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            try {
                String pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
                int id = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
                if (pkg == null) return;
                synchronized (playerSessions) {
                    if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction()) && id > 0) {
                        playerSessions.remove(pkg);
                        playerSessions.put(pkg, id);
                        Log.i(TAG, "audio session opened: " + pkg + " " + id);
                    } else {
                        playerSessions.remove(pkg);
                        Log.i(TAG, "audio session closed: " + pkg);
                    }
                }
            } catch (Throwable ignored) { }
        }
    };

    @Override
    public void load() {
        instance = this;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            ContextCompat.registerReceiver(getContext(), sessionReceiver, f, ContextCompat.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "could not listen for player sessions: " + t);
        }
    }

    /** The audio sessions that music apps have announced since this app started: {list:[{pkg,id}]}, newest last. */
    @PluginMethod
    public void sessions(PluginCall call) {
        JSObject r = new JSObject();
        com.getcapacitor.JSArray a = new com.getcapacitor.JSArray();
        synchronized (playerSessions) {
            for (Map.Entry<String, Integer> e : playerSessions.entrySet()) {
                JSObject o = new JSObject();
                o.put("pkg", e.getKey());
                o.put("id", e.getValue());
                a.put(o);
            }
        }
        r.put("list", a);
        r.put("session", session);
        call.resolve(r);
    }

    /** Lets another audio source (the phone build's app-audio capture) send frames to the page as if they came from the tap. */
    static void emitFrame(JSObject o) {
        VisualizerPlugin p = instance;
        if (p != null) p.notifyListeners("fft", o);
    }

    /** MainActivity calls this with the answer to the audio permission dialog. */
    static void deliverAudioResult(boolean granted) {
        VisualizerPlugin p = instance;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put("granted", granted);
        p.notifyListeners("audioPermission", o);
    }

    /** Is audio access allowed right now? */
    @PluginMethod
    public void granted(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", hasAudioPermission());
        call.resolve(r);
    }

    /** Shows Android's audio permission dialog. The page calls this only after it has explained why (first-run screen). */
    @PluginMethod
    public void requestAudio(PluginCall call) {
        JSObject r = new JSObject();
        if (hasAudioPermission()) {
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        try {
            ActivityCompat.requestPermissions(getActivity(), new String[] { Manifest.permission.RECORD_AUDIO }, AUDIO_REQUEST);
            r.put("granted", false);
            r.put("asked", true);
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("Could not ask for audio access: " + t.getMessage());
        }
    }

    /** Opens this app's page in Android Settings (where every permission can be changed). */
    @PluginMethod
    public void openAppSettings(PluginCall call) {
        JSObject r = new JSObject();
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            r.put("opened", true);
        } catch (Throwable t) {
            r.put("opened", false);
        }
        call.resolve(r);
    }

    // ---- self-test hook, used by tools/smoke_test.py -------------------------------------------------
    // `adb shell am start ... --es selftest tone` asks the page to play a test tone and report what it heard.
    // Only honoured while USB debugging is switched on, so an ordinary app cannot trigger it.
    private static volatile String pendingSelfTest = null; // request that arrived in a new intent, waiting for the page to collect it
    private static long lastGivenAt = 0;                   // the same request can arrive twice (launch intent + new-intent); give it out once

    private boolean adbOn() {
        try {
            return Settings.Global.getInt(getContext().getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The page polls this (every 2 s while USB debugging is on, every 30 s otherwise). It returns {adb: bool} and, once,
     * {selftest: "tone" | "tone+controls"} when the app was started or re-opened with `--es selftest ...`.
     */
    @PluginMethod
    public void launchExtras(PluginCall call) {
        JSObject r = new JSObject();
        try {
            boolean adb = adbOn();
            r.put("adb", adb);
            if (adb) {
                String v = pendingSelfTest;
                pendingSelfTest = null;
                if (v == null) {
                    Intent i = getActivity() == null ? null : getActivity().getIntent();
                    v = i == null ? null : i.getStringExtra("selftest");
                }
                long now = System.currentTimeMillis();
                if (v != null && now - lastGivenAt > 25000) {
                    lastGivenAt = now;
                    r.put("selftest", v);
                    Log.i("SpectTest", "self-test request handed to the page: " + v);
                }
            }
        } catch (Throwable t) {
            Log.w("SpectTest", "launchExtras failed: " + t);
        }
        call.resolve(r);
    }

    /** The app was already running and got a new intent: remember a self-test request for the page to collect. */
    @Override
    protected void handleOnNewIntent(Intent intent) {
        try {
            if (intent != null && adbOn()) {
                String v = intent.getStringExtra("selftest");
                if (v != null) pendingSelfTest = v;
            }
        } catch (Throwable ignored) { }
    }

    /** The page writes its self-test result here so a script can read it from logcat (tag SpectTest). */
    @PluginMethod
    public void testReport(PluginCall call) {
        Log.i("SpectTest", call.getString("json", "{}"));
        call.resolve();
    }

    /** App, device and system facts for the Diagnostics screen. */
    @PluginMethod
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        try {
            Context c = getContext();
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            r.put("packageName", c.getPackageName());
            r.put("versionName", pi.versionName);
            r.put("versionCode", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Throwable ignored) { }
        r.put("manufacturer", Build.MANUFACTURER);
        r.put("model", Build.MODEL);
        r.put("release", Build.VERSION.RELEASE);
        r.put("sdk", Build.VERSION.SDK_INT);
        r.put("audioGranted", hasAudioPermission());
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                PackageInfo w = WebView.getCurrentWebViewPackage();
                if (w != null) r.put("webView", w.packageName + " " + w.versionName);
            }
        } catch (Throwable ignored) { }
        call.resolve(r);
    }

    /** Is any app playing music through Android right now? Lets the page tell "paused" from "audio route is broken". */
    @PluginMethod
    public void musicActive(PluginCall call) {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            JSObject r = new JSObject();
            r.put("active", am.isMusicActive());
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("Cannot read audio state: " + t.getMessage());
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!hasAudioPermission()) {
            call.reject("Audio permission not granted");
            return;
        }
        if (call.hasOption("session")) session = call.getInt("session", 0); // without the option the current session is kept (restarts)
        doStart(call);
    }

    private static Visualizer createVisualizer(int session) {
        Visualizer v = new Visualizer(session); // 0 = global output mix
        int[] range = Visualizer.getCaptureSizeRange();
        v.setCaptureSize(range[1]);
        v.setEnabled(true);
        return v;
    }

    private synchronized void doStart(PluginCall call) {
        stopInternal();
        try {
            final Visualizer v = createVisualizer(session);
            synchronized (swapLock) { viz = v; }
            final int size = v.getCaptureSize();
            running = true;
            final int myGen = ++gen;
            worker = new Thread(() -> runWorker(myGen, size), "spect-visualizer");
            worker.start();
            JSObject res = new JSObject();
            res.put("sampleRate", v.getSamplingRate() / 1000);
            res.put("captureSize", size);
            call.resolve(res);
        } catch (Throwable t) {
            stopInternal();
            call.reject("Visualizer unavailable: " + t.getMessage());
        }
    }

    private void runWorker(int myGen, int size) {
        byte[] fft = new byte[size];
        byte[] wf = new byte[size]; // the raw sound wave, for the Scope style
        int fails = 0;
        try {
            while (running && gen == myGen) {
                Visualizer v = viz;
                int rc = Visualizer.ERROR;
                try {
                    if (v != null) rc = v.getFft(fft);
                } catch (Throwable t) {
                    rc = Visualizer.ERROR;
                }
                if (rc == Visualizer.SUCCESS) {
                    fails = 0;
                    JSObject o = new JSObject();
                    o.put("fft", Base64.encodeToString(fft, Base64.NO_WRAP));
                    try {
                        if (v != null && wf.length == fft.length && v.getWaveForm(wf) == Visualizer.SUCCESS) {
                            o.put("wf", Base64.encodeToString(wf, Base64.NO_WRAP));
                        }
                    } catch (Throwable ignored) { }
                    notifyListeners("fft", o);
                } else if (++fails >= 15) {
                    // about 0.3 seconds of nothing but errors: the audio service restarted or took the tap away
                    fails = 0;
                    fft = recreate(myGen, fft);
                    if (wf.length != fft.length) wf = new byte[fft.length];
                }
                Thread.sleep(20);
            }
        } catch (Throwable ignored) { }
    }

    /** Throws the dead tap away and builds a new one, retrying with a growing pause until it works or we are stopped. */
    private byte[] recreate(int myGen, byte[] fft) throws InterruptedException {
        Log.w(TAG, "audio tap stopped answering, restarting it");
        synchronized (swapLock) {
            Visualizer old = viz;
            viz = null;
            if (old != null) {
                try { old.setEnabled(false); } catch (Throwable ignored) { }
                try { old.release(); } catch (Throwable ignored) { }
            }
        }
        long wait = 400;
        for (int attempt = 1; running && gen == myGen; attempt++) {
            try {
                Visualizer nv = createVisualizer(session);
                synchronized (swapLock) {
                    if (!(running && gen == myGen)) {
                        try { nv.release(); } catch (Throwable ignored) { }
                        return fft;
                    }
                    viz = nv;
                }
                Log.i(TAG, "audio tap restored after " + attempt + " attempt(s)");
                JSObject st = new JSObject();
                st.put("state", "restored");
                st.put("attempts", attempt);
                notifyListeners("status", st);
                int sz = nv.getCaptureSize();
                return sz == fft.length ? fft : new byte[sz];
            } catch (Throwable t) {
                Log.w(TAG, "restart attempt " + attempt + " failed: " + t.getMessage());
                Thread.sleep(wait);
                wait = Math.min(wait * 2, 5000);
            }
        }
        return fft;
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopInternal();
        call.resolve();
    }

    private synchronized void stopInternal() {
        running = false;
        gen++;
        if (worker != null) {
            try { worker.join(300); } catch (InterruptedException ignored) { }
            worker = null;
        }
        synchronized (swapLock) {
            Visualizer v = viz;
            viz = null;
            if (v != null) {
                try { v.setEnabled(false); } catch (Throwable ignored) { }
                try { v.release(); } catch (Throwable ignored) { }
            }
        }
    }

    @Override
    protected void handleOnDestroy() {
        stopInternal();
    }
}
