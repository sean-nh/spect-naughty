package com.hector.spectrum.car;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.core.content.ContextCompat;

/**
 * The visualizer on the car display. It draws into the surface Android Auto hands to navigation-category apps, so it needs the
 * "navigation" template. It reads the phone's audio output with its own Visualizer tap (the same Android API the phone and radio
 * apps use) and draws spectrum bars. Only works while the phone has audio access (granted in the phone app, with the explanation screen).
 */
public class SpectCarScreen extends Screen implements SurfaceCallback {
    private static final int BARS = 40;

    private volatile Surface surface;
    private volatile int width, height;
    private Thread worker;
    private volatile boolean running;
    private volatile int style = 0; // 0 bars, 1 mirror, 2 line
    private static final String[] STYLES = { "Bars", "Mirror", "Line" };

    public SpectCarScreen(@NonNull CarContext ctx) {
        super(ctx);
        ctx.getCarService(AppManager.class).setSurfaceCallback(this);
        getLifecycle().addObserver(new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                stopWorker();
            }
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        Action styleAction = new Action.Builder()
            .setTitle("Style: " + STYLES[style])
            .setOnClickListener(() -> {
                style = (style + 1) % STYLES.length;
                invalidate();
            })
            .build();
        return new NavigationTemplate.Builder()
            .setActionStrip(new ActionStrip.Builder().addAction(styleAction).build())
            .setBackgroundColor(androidx.car.app.model.CarColor.createCustom(Color.rgb(5, 6, 10), Color.rgb(5, 6, 10)))
            .build();
    }

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer c) {
        surface = c.getSurface();
        width = c.getWidth();
        height = c.getHeight();
        startWorker();
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer c) {
        stopWorker();
        surface = null;
    }

    private void startWorker() {
        stopWorker();
        running = true;
        worker = new Thread(this::run, "spect-car");
        worker.start();
    }

    private void stopWorker() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            try { w.join(500); } catch (InterruptedException ignored) { }
        }
    }

    private void run() {
        boolean allowed = ContextCompat.checkSelfPermission(getCarContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        com.hector.spectrum.PlayerSessions.start(getCarContext());
        com.hector.spectrum.SpectLog.w(getCarContext(), "car screen started, audio allowed=" + allowed);
        long lastReport = 0;
        Visualizer viz = null;
        if (allowed) {
            try {
                viz = new Visualizer(0);
                viz.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
                viz.setEnabled(true);
            } catch (Throwable t) {
                viz = null;
            }
        }
        byte[] fft = viz == null ? new byte[0] : new byte[viz.getCaptureSize()];
        float[] level = new float[BARS];
        float[] peak = new float[BARS];
        float auto = 40f;
        long lastSound = System.currentTimeMillis(), lastTry = 0;
        int curSession = 0;
        java.util.List<Integer> triedIds = new java.util.ArrayList<>();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.rgb(207, 214, 228));
        text.setTextAlign(Paint.Align.CENTER);
        try {
            while (running) {
                Surface s = surface;
                if (s != null && s.isValid()) {
                    float[] raw = new float[BARS];
                    boolean got = false;
                    if (viz != null && viz.getFft(fft) == Visualizer.SUCCESS) {
                        got = true;
                        int bins = fft.length / 2;
                        for (int b = 0; b < BARS; b++) {
                            // log spacing from about 40 Hz up to 14 kHz (bin 1 is the first real bin)
                            int lo = (int) Math.max(1, Math.pow(bins * 0.7, (double) b / BARS) );
                            int hi = (int) Math.max(lo + 1, Math.pow(bins * 0.7, (double) (b + 1) / BARS));
                            float m = 0;
                            for (int i = lo; i < hi && 2 * i + 1 < fft.length; i++) {
                                m = Math.max(m, (float) Math.hypot(fft[2 * i], fft[2 * i + 1]));
                            }
                            raw[b] = m;
                        }
                    }
                    float top = 1, rawTop = 0;
                    for (float v : raw) { top = Math.max(top, v); rawTop = Math.max(rawTop, v); }
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastReport > 10000) {
                        lastReport = nowMs;
                        com.hector.spectrum.SpectLog.w(getCarContext(), "car screen: tap=" + (viz != null) + " session=" + curSession + " level=" + rawTop + " announced=" + com.hector.spectrum.PlayerSessions.latest());
                    }
                    if (rawTop > 0) lastSound = nowMs;
                    else if (allowed && nowMs - lastSound > 3000 && nowMs - lastTry > 3000) {
                        // The global tap hears nothing (the music goes to the car). Try the playing app's own audio session.
                        lastTry = nowMs;
                        java.util.List<Integer> ids = com.hector.spectrum.PlayerSessions.latest();
                        Integer pick = null;
                        for (Integer id : ids) if (id != curSession && !triedIds.contains(id)) { pick = id; break; }
                        if (pick == null && !triedIds.isEmpty() && !ids.isEmpty()) { triedIds.clear(); }
                        if (pick != null) {
                            try {
                                Visualizer nv = new Visualizer(pick);
                                nv.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
                                nv.setEnabled(true);
                                if (viz != null) { try { viz.setEnabled(false); viz.release(); } catch (Throwable ignored) { } }
                                viz = nv;
                                fft = new byte[nv.getCaptureSize()];
                                curSession = pick;
                                triedIds.add(pick);
                                android.util.Log.i("SpectViz", "car screen: listening to player session " + pick);
                                com.hector.spectrum.SpectLog.w(getCarContext(), "car screen: switched to player session " + pick);
                            } catch (Throwable t) {
                                triedIds.add(pick);
                                android.util.Log.w("SpectViz", "car screen: session " + pick + " refused: " + t);
                                com.hector.spectrum.SpectLog.w(getCarContext(), "car screen: session " + pick + " refused: " + t);
                            }
                        }
                    }
                    auto = Math.max(20f, auto * 0.995f);
                    if (top > auto) auto = top;
                    for (int b = 0; b < BARS; b++) {
                        float t = Math.min(1f, raw[b] / auto);
                        level[b] = t > level[b] ? t : level[b] * 0.86f + t * 0.14f;
                        peak[b] = Math.max(level[b], peak[b] - 0.012f);
                    }
                    Canvas cv = null;
                    try {
                        cv = s.lockHardwareCanvas();
                        draw(cv, p, text, level, peak, allowed, viz != null, got);
                    } catch (Throwable ignored) {
                    } finally {
                        if (cv != null) { try { s.unlockCanvasAndPost(cv); } catch (Throwable ignored) { } }
                    }
                }
                Thread.sleep(33);
            }
        } catch (InterruptedException ignored) {
        } finally {
            if (viz != null) {
                try { viz.setEnabled(false); } catch (Throwable ignored) { }
                try { viz.release(); } catch (Throwable ignored) { }
            }
        }
    }

    private void draw(Canvas c, Paint p, Paint text, float[] level, float[] peak, boolean allowed, boolean haveTap, boolean got) {
        int w = width, h = height;
        c.drawColor(Color.rgb(5, 6, 10));
        if (!allowed || !haveTap) {
            text.setTextSize(Math.max(28f, h / 14f));
            c.drawText(!allowed ? "Open Spectr-Acular on your phone once to allow audio access"
                                : "The audio tap is not available right now", w / 2f, h / 2f, text);
            return;
        }
        float gap = w / (float) (BARS * 8);
        float bw = (w - gap * (BARS + 1)) / BARS;
        float base = style == 1 ? h / 2f : h * 0.92f;
        float room = style == 1 ? h * 0.42f : h * 0.8f;
        p.setShader(new LinearGradient(0, h, w, 0, new int[] { Color.rgb(34, 195, 255), Color.rgb(140, 70, 255), Color.rgb(255, 60, 120) }, null, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        if (style == 2) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(4f, h / 90f));
            android.graphics.Path path = new android.graphics.Path();
            for (int b = 0; b < BARS; b++) {
                float x = gap + b * (bw + gap) + bw / 2f;
                float y = h * 0.9f - level[b] * h * 0.75f;
                if (b == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            c.drawPath(path, p);
        } else {
            for (int b = 0; b < BARS; b++) {
                float x = gap + b * (bw + gap);
                float bh = Math.max(3f, level[b] * room);
                c.drawRoundRect(x, base - bh, x + bw, style == 1 ? base + bh : base, bw / 4f, bw / 4f, p);
            }
            p.setShader(null);
            p.setColor(Color.argb(220, 255, 255, 255));
            for (int b = 0; b < BARS; b++) {
                float x = gap + b * (bw + gap);
                float py = base - Math.max(3f, peak[b] * room) - 6f;
                c.drawRect(x, py, x + bw, py + 4f, p);
            }
        }
        p.setShader(null);
    }
}
