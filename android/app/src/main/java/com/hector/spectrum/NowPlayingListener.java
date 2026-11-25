package com.hector.spectrum;

import android.service.notification.NotificationListenerService;

/** Lets the app see which song is playing. Android keeps this service running, so it also keeps listening for player audio sessions. */
public class NowPlayingListener extends NotificationListenerService {
    @Override
    public void onCreate() {
        super.onCreate();
        PlayerSessions.start(this);
    }
}
