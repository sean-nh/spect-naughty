package com.hector.spectrum;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import com.getcapacitor.JSObject;

/**
 * Phone build only. Captures the sound that other apps (Spotify and so on) play, using Android's "capture app audio" feature, which
 * the user allows once with Android's own dialog. The sound is turned into spectrum data in memory and sent to the page; nothing is
 * recorded or saved. Android requires a visible notification while this runs.
 */
public class CaptureService extends Service {
    private static final String TAG = "SpectCapture";
    static final int RATE = 48000;
    static final int N = 1024; // window size = capture size reported to the page

    /** 0 stopped, 1 starting, 2 running, -1 failed (see error) */
    static volatile int state = 0;
    static volatile String error = "";

    private MediaProjection projection;
    private AudioRecord rec;
    private Thread worker;
    private volatile boolean running;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getBooleanExtra("stop", false)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        state = 1;
        error = "";
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("spect_capture", "Listening to music", NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, "spect_capture")
                .setContentTitle("Spectr-Acular")
                .setContentText("Listening to the music playing on this phone (nothing is recorded)")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
            startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

            int code = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("data");
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    shutdown();
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));

            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
            AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
            rec = new AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setAudioPlaybackCaptureConfig(cfg)
                .setBufferSizeInBytes(N * 2 * 2 * 4)
                .build();
            rec.startRecording();
            running = true;
            worker = new Thread(this::loop, "spect-capture");
            worker.start();
            state = 2;
            Log.i(TAG, "capturing app audio");
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            state = -1;
            Log.w(TAG, "could not start: " + t);
            shutdown();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void loop() {
        short[] pcm = new short[N * 2];
        float[] re = new float[N], im = new float[N];
        float[] win = new float[N];
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        byte[] fft = new byte[N];
        byte[] wf = new byte[N];
        long lastLog = 0; int peak = 0;
        float sc = 400f / (N / 4f);
        try {
            while (running) {
                int got = 0;
                while (got < pcm.length && running) {
                    int r = rec.read(pcm, got, pcm.length - got);
                    if (r <= 0) { Thread.sleep(10); continue; }
                    got += r;
                }
                if (!running) break;
                for (int i = 0; i < pcm.length; i++) peak = Math.max(peak, Math.abs((int) pcm[i]));
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastLog > 2000) { Log.i(TAG, "level: peak sample " + peak + " of 32767"); lastLog = nowMs; peak = 0; }
                for (int i = 0; i < N; i++) {
                    float s =(pcm[2 * i] + pcm[2 * i + 1]) / 65536f; // mono, -0.5..0.5
                    re[i] = s * 2f * win[i];
                    im[i] = 0;
                    int w = 128 + Math.round(s * 2f * 127f);
                    wf[i] = (byte) Math.max(0, Math.min(255, w));
                }
                fftInPlace(re, im);
                fft[0] = clamp(re[0] * sc);
                fft[1] = clamp(re[N / 2] * sc);
                for (int k = 1; k < N / 2; k++) {
                    fft[2 * k] = clamp(re[k] * sc);
                    fft[2 * k + 1] = clamp(im[k] * sc);
                }
                JSObject o = new JSObject();
                o.put("fft", Base64.encodeToString(fft, Base64.NO_WRAP));
                o.put("wf", Base64.encodeToString(wf, Base64.NO_WRAP));
                VisualizerPlugin.emitFrame(o);
            }
        } catch (Throwable t) {
            Log.w(TAG, "capture loop ended: " + t);
        }
    }

    private static byte clamp(float v) {
        return (byte) Math.max(-127, Math.min(127, Math.round(v)));
    }

    /** Plain radix-2 FFT, N must be a power of two. */
    private static void fftInPlace(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }

    private void shutdown() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) { try { w.join(300); } catch (InterruptedException ignored) { } }
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Throwable ignored) { }
        rec = null;
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
        projection = null;
        if (state != -1) state = 0;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }
}
