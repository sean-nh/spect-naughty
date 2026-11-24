package com.hector.spectrum;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing by itself. Android only lets an app read what other apps are playing
 * (title, artist, album art) once the user switches on "Notification access" for a
 * listener service like this one.
 */
public class NowPlayingListener extends NotificationListenerService { }
