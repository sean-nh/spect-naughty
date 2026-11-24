package com.hector.spectrum;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.util.Base64;
import android.util.Log;
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

    /** The activity asks for the permission at launch; the page polls this until it is granted. */
    @PluginMethod
    public void granted(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", hasAudioPermission());
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
        doStart(call);
    }

    private static Visualizer createVisualizer() {
        Visualizer v = new Visualizer(0); // 0 = global output mix
        int[] range = Visualizer.getCaptureSizeRange();
        v.setCaptureSize(range[1]);
        v.setEnabled(true);
        return v;
    }

    private synchronized void doStart(PluginCall call) {
        stopInternal();
        try {
            final Visualizer v = createVisualizer();
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
                    notifyListeners("fft", o);
                } else if (++fails >= 15) {
                    // about 0.3 seconds of nothing but errors: the audio service restarted or took the tap away
                    fails = 0;
                    fft = recreate(myGen, fft);
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
                Visualizer nv = createVisualizer();
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
