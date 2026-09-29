package org.wispyr.messenger.security;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.wispyr.messenger.BuildVars;
import org.wispyr.messenger.voip.VoIPService;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * While the data tier is passcode-locked nothing that touches accounts, databases or account preferences
 * may run: those singletons would be created with empty state and keep it (or write it back) after unlock.
 * Every app entry point checks this gate first; activities are redirected to {@link WispyrUnlockActivity}.
 */
public final class WispyrLockGate {

    private WispyrLockGate() {
    }

    public static boolean isLocked() {
        return WispyrVault.isLocked();
    }

    private static final Set<String> lockedAccountAccess = Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * Called by every account controller constructor. A controller built while locked caches empty
     * placeholder preferences in final fields and keeps that state for the rest of the process.
     */
    public static void onAccountControllerCreated(Object controller) {
        if (!WispyrVault.isLocked()) {
            return;
        }
        lockedAccountAccess.add(controller.getClass().getName());
        if (BuildVars.DEBUG_VERSION) {
            Log.w("WispyrLockGate", "account controller created while locked: " + controller.getClass().getName(), new Throwable());
        }
    }

    /** Account controllers created while the vault was locked; must stay empty. */
    public static Set<String> getLockedAccountAccess() {
        synchronized (lockedAccountAccess) {
            return new LinkedHashSet<>(lockedAccountAccess);
        }
    }

    /**
     * Sends the user to the unlock screen, which re-launches {@code activity}'s original intent after a
     * successful unlock. The caller must still call {@code super.onCreate} and return.
     */
    public static void redirect(Activity activity) {
        Intent original = activity.getIntent();
        Intent target = original != null ? new Intent(original) : new Intent();
        target.setComponent(new ComponentName(activity, activity.getClass()));
        Intent unlock = new Intent(activity, WispyrUnlockActivity.class);
        unlock.putExtra(WispyrUnlockActivity.EXTRA_TARGET, target);
        if (original != null) {
            int grants = original.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            unlock.addFlags(grants);
            unlock.setClipData(original.getClipData());
        }
        try {
            activity.startActivity(unlock);
        } catch (Exception ignore) {
        }
        activity.finish();
        activity.overridePendingTransition(0, 0);
    }

    /**
     * Locks the data tier for real. The data key only leaves memory with the process, so this opens the
     * unlock screen in relock mode; it seals the data and ends the process, and the system recreates the
     * screen in a fresh, locked process. Returns false when the vault cannot be relocked right now.
     */
    public static boolean relock(Activity activity) {
        if (!WispyrVault.isPasscodeProtected() || WispyrVault.isLocked() || VoIPService.getSharedInstance() != null) {
            return false;
        }
        Intent target = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        Intent unlock = new Intent(activity, WispyrUnlockActivity.class)
                .putExtra(WispyrUnlockActivity.EXTRA_TARGET, target)
                .putExtra(WispyrUnlockActivity.EXTRA_RELOCK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            activity.startActivity(unlock);
        } catch (Exception e) {
            return false;
        }
        activity.overridePendingTransition(0, 0);
        return true;
    }

    public static RemoteViewsService.RemoteViewsFactory emptyWidgetFactory() {
        return new RemoteViewsService.RemoteViewsFactory() {
            @Override public void onCreate() {}
            @Override public void onDataSetChanged() {}
            @Override public void onDestroy() {}
            @Override public int getCount() { return 0; }
            @Override public RemoteViews getViewAt(int position) { return null; }
            @Override public RemoteViews getLoadingView() { return null; }
            @Override public int getViewTypeCount() { return 1; }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean hasStableIds() { return true; }
        };
    }
}
