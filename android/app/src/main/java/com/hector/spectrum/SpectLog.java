package com.hector.spectrum;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiny event log kept in the app's own folder (readable with a USB cable), so a drive can be checked afterwards. About 150 KB at most. */
public final class SpectLog {
    private static final long MAX = 150_000;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private SpectLog() { }

    public static synchronized void w(Context ctx, String msg) {
        try {
            File dir = ctx.getApplicationContext().getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "spect-events.log");
            if (f.length() > MAX) {
                File old = new File(dir, "spect-events.old.log");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
                f = new File(dir, "spect-events.log");
            }
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(FMT.format(new Date()) + " " + msg + "\n");
            }
        } catch (Throwable ignored) { }
    }
}
