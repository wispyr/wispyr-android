/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.wispyr.messenger;

import android.app.IntentService;
import android.content.Intent;

import org.wispyr.messenger.security.WispyrLockGate;

public class NotificationRepeat extends IntentService {

    public NotificationRepeat() {
        super("NotificationRepeat");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null || WispyrLockGate.isLocked()) {
            return;
        }
        final int currentAccount = intent.getIntExtra("currentAccount", UserConfig.selectedAccount);
        if (!UserConfig.isValidAccount(currentAccount)) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> NotificationsController.getInstance(currentAccount).repeatNotificationMaybe());
    }
}
