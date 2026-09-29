package org.wispyr.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.wispyr.messenger.security.WispyrLockGate;

public class ShortcutResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (WispyrLockGate.isLocked()) {
            return;
        }
        final int currentAccount = intent.getIntExtra("account", UserConfig.selectedAccount);
        final String req_id = intent.getStringExtra("req_id");

        Utilities.Callback<Boolean> callback = MediaDataController.getInstance(currentAccount).shortcutCallbacks.remove(req_id);
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> {
                callback.run(true);
            });
        }
    }

}
