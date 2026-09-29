/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.wispyr.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.wispyr.messenger.security.WispyrVault;
import org.wispyr.tgnet.ConnectionsManager;

public class ScreenReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final boolean locked = WispyrVault.isLocked();
        if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("screen off");
            }
            if (!locked) {
                ConnectionsManager.getInstance(UserConfig.selectedAccount).setAppPaused(true, true);
            }
            ApplicationLoader.isScreenOn = false;
        } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("screen on");
            }
            if (!locked) {
                ConnectionsManager.getInstance(UserConfig.selectedAccount).setAppPaused(false, true);
            }
            ApplicationLoader.isScreenOn = true;
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.screenStateChanged);
    }
}
