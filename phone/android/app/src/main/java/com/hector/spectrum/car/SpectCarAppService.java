package com.hector.spectrum.car;

import android.content.pm.ApplicationInfo;
import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/**
 * Entry point for Android Auto. The Android Auto app on the phone binds to this service and shows the screen from SpectCarScreen on the car display.
 * Phone build only; the radio build does not contain this.
 */
public class SpectCarAppService extends CarAppService {
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // Debuggable builds (and sideloaded test builds) accept any host so the Desktop Head Unit works.
        // A published build should list the real Android Auto and Google hosts instead (see phone/README.md).
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new Session() {
            @NonNull
            @Override
            public androidx.car.app.Screen onCreateScreen(@NonNull android.content.Intent intent) {
                return new SpectCarScreen(getCarContext());
            }
        };
    }
}
