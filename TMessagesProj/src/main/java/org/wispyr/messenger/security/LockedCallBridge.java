package org.wispyr.messenger.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.wispyr.messenger.ApplicationLoader;
import org.wispyr.messenger.MessagesController;

/**
 * Carries the user's response to a call that rang while the process was locked (no network keys) to the
 * real PhoneCall that MTProto delivers after the passcode unlocks the account.
 */
public final class LockedCallBridge {
    public static final int ACTION_NONE = 0;
    public static final int ACTION_OPEN = 1;
    public static final int ACTION_ANSWER = 2;
    public static final int ACTION_DECLINE = 3;

    private static final String PREFS = "wispyr_bootstrap";
    private static final String ID = "pending_call_id";
    private static final String ACTION = "pending_call_action";
    private static final String TIME = "pending_call_time";
    // Copy of the server call_ring_timeout_ms: the account config is unreadable while locked.
    private static final String RING_TIMEOUT = "call_ring_timeout_ms";

    private LockedCallBridge() {}

    public static void setRingTimeout(int timeoutMs) {
        if (timeoutMs > 0 && ApplicationLoader.applicationContext != null && prefs().getInt(RING_TIMEOUT, 0) != timeoutMs) {
            prefs().edit().putInt(RING_TIMEOUT, timeoutMs).apply();
        }
    }

    public static long getRingTimeoutMs() {
        return prefs().getInt(RING_TIMEOUT, MessagesController.DEFAULT_CALL_RING_TIMEOUT_MS);
    }

    public static void request(long callId, int action) {
        if (callId == 0 || action == ACTION_NONE || ApplicationLoader.applicationContext == null) {
            return;
        }
        prefs().edit()
                .putLong(ID, callId)
                .putInt(ACTION, action)
                .putLong(TIME, SystemClock.elapsedRealtime())
                .commit();
    }

    /** Returns and forgets the pending action for {@code callId}; stale requests are dropped. */
    public static int consume(long callId) {
        SharedPreferences prefs = prefs();
        long stored = prefs.getLong(ID, 0);
        if (stored == 0) {
            return ACTION_NONE;
        }
        long age = SystemClock.elapsedRealtime() - prefs.getLong(TIME, 0);
        boolean fresh = age >= 0 && age <= getRingTimeoutMs();
        if (stored != callId && fresh) {
            return ACTION_NONE;
        }
        int action = stored == callId && fresh ? prefs.getInt(ACTION, ACTION_NONE) : ACTION_NONE;
        prefs.edit().remove(ID).remove(ACTION).remove(TIME).commit();
        return action;
    }

    public static void clear(long callId) {
        SharedPreferences prefs = prefs();
        if (callId != 0 && prefs.getLong(ID, 0) == callId) {
            prefs.edit().remove(ID).remove(ACTION).remove(TIME).commit();
        }
    }

    private static SharedPreferences prefs() {
        Context context = ApplicationLoader.applicationContext;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
