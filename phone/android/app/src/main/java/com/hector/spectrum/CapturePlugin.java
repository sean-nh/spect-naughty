package com.hector.spectrum;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Phone build only: lets the page switch the audio source to "capture what other apps play". */
@CapacitorPlugin(name = "SpectCapture")
public class CapturePlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        if (Build.VERSION.SDK_INT < 29) {
            call.reject("Capturing app audio needs Android 10 or newer");
            return;
        }
        if (CaptureService.state == 2) {
            call.resolve(info());
            return;
        }
        try {
            MediaProjectionManager m = (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(call, m.createScreenCaptureIntent(), "projectionResult");
        } catch (Throwable t) {
            call.reject("Could not ask for permission: " + t.getMessage());
        }
    }

    @ActivityCallback
    private void projectionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Permission to capture app audio was not given");
            return;
        }
        Intent svc = new Intent(getContext(), CaptureService.class);
        svc.putExtra("code", result.getResultCode());
        svc.putExtra("data", result.getData());
        CaptureService.state = 1;
        try {
            ContextCompat.startForegroundService(getContext(), svc);
        } catch (Throwable t) {
            call.reject("Could not start: " + t.getMessage());
            return;
        }
        new Thread(() -> { // wait until the service says it is capturing (or failed)
            long t0 = System.currentTimeMillis();
            while (CaptureService.state == 1 && System.currentTimeMillis() - t0 < 5000) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            }
            if (CaptureService.state == 2) call.resolve(info());
            else call.reject("Capture did not start" + (CaptureService.error.isEmpty() ? "" : ": " + CaptureService.error));
        }).start();
    }

    private JSObject info() {
        JSObject r = new JSObject();
        r.put("sampleRate", CaptureService.RATE);
        r.put("captureSize", CaptureService.N);
        return r;
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            Intent svc = new Intent(getContext(), CaptureService.class);
            svc.putExtra("stop", true);
            getContext().startService(svc);
        } catch (Throwable ignored) { }
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        try { getContext().stopService(new Intent(getContext(), CaptureService.class)); } catch (Throwable ignored) { }
    }
}
