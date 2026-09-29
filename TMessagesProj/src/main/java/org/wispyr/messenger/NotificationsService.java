/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.wispyr.messenger;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Telegram's keep-alive service. Wispyr never keeps the process alive this way, so it stops immediately. */
public class NotificationsService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
