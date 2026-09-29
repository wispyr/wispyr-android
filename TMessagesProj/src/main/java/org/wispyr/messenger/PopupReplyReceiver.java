/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.wispyr.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.wispyr.messenger.security.WispyrLockGate;

public class PopupReplyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        if (WispyrLockGate.isLocked()) {
            return;
        }
        ApplicationLoader.postInitApplication();
        int currentAccount = intent.getIntExtra("currentAccount", UserConfig.selectedAccount);
        if (!UserConfig.isValidAccount(currentAccount)) {
            return;
        }
        NotificationsController.getInstance(currentAccount).forceShowPopupForReply();
    }
}
