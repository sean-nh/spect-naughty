package com.hector.spectrum;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.util.Base64;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Taps Android's global audio output mix (no microphone) and streams FFT frames to the web page. */
@CapacitorPlugin(name = "SpectVisualizer")
public class VisualizerPlugin extends Plugin {
    private Visualizer viz;
    private Thread worker;
    private volatile boolean running;

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

    @PluginMethod
    public void start(PluginCall call) {
        if (!hasAudioPermission()) {
            call.reject("Audio permission not granted");
            return;
        }
        doStart(call);
    }

    private synchronized void doStart(PluginCall call) {
        stopInternal();
        try {
            final Visualizer v = new Visualizer(0); // 0 = global output mix
            viz = v;
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(range[1]);
            v.setEnabled(true);
            final int size = v.getCaptureSize();
            running = true;
            worker = new Thread(() -> {
                byte[] fft = new byte[size];
                try {
                    while (running) {
                        if (v.getFft(fft) == Visualizer.SUCCESS) {
                            JSObject o = new JSObject();
                            o.put("fft", Base64.encodeToString(fft, Base64.NO_WRAP));
                            notifyListeners("fft", o);
                        }
                        Thread.sleep(20);
                    }
                } catch (Throwable ignored) { }
            }, "spect-visualizer");
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

    @PluginMethod
    public void stop(PluginCall call) {
        stopInternal();
        call.resolve();
    }

    private synchronized void stopInternal() {
        running = false;
        if (worker != null) {
            try { worker.join(300); } catch (InterruptedException ignored) { }
            worker = null;
        }
        if (viz != null) {
            try { viz.setEnabled(false); viz.release(); } catch (Throwable ignored) { }
            viz = null;
        }
    }

    @Override
    protected void handleOnDestroy() {
        stopInternal();
    }
}
