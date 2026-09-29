package org.wispyr.messenger.security;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.wispyr.messenger.AndroidUtilities;
import org.wispyr.messenger.ApplicationLoader;
import org.wispyr.messenger.LocaleController;
import org.wispyr.messenger.R;
import org.wispyr.messenger.voip.VoIPPreNotificationService;
import org.wispyr.messenger.voip.VoIPService;

/**
 * Shows notifications while the data tier is passcode-locked. Nothing here touches the database, the
 * account network keys or the roster: the payload comes straight from the decrypted push. Message bodies
 * are never shown; only "new message" / "incoming call" plus the sender name the server already put in
 * the push are surfaced.
 *
 * An incoming call rings full screen and opens the passcode screen; only the unlocked account can reach the
 * server, so Telegram's own call screen (answer / decline) follows once the passcode is entered.
 */
public final class LockedPushNotifier {

    private static final String MESSAGES_CHANNEL_ID = "wispyr_locked_messages";
    private static final String LEGACY_CALLS_CHANNEL_ID = "wispyr_locked_calls";
    private static final String CALLS_CHANNEL_ID = "wispyr_locked_calls_v2";
    private static final int MESSAGE_NOTIFICATION_ID = 100001;
    static final int CALL_NOTIFICATION_ID = 100002;

    // Call push loc_keys, as handled by PushListenerController / VoIPGroupNotification.
    private static final String PHONE_CALL_REQUEST = "PHONE_CALL_REQUEST";
    private static final String CONF_CALL_REQUEST = "CONF_CALL_REQUEST";
    private static final String CONF_VIDEOCALL_REQUEST = "CONF_VIDEOCALL_REQUEST";
    private static final String PHONE_CALL_MISSED = "PHONE_CALL_MISSED";

    public interface CallEndedListener {
        void onCallEnded(long callId);
    }

    private static volatile CallEndedListener callEndedListener;

    private LockedPushNotifier() {
    }

    public static void setCallEndedListener(CallEndedListener listener) {
        callEndedListener = listener;
    }

    public static void removeCallEndedListener(CallEndedListener listener) {
        if (callEndedListener == listener) {
            callEndedListener = null;
        }
    }

    private static void notifyCallEnded(long callId) {
        final CallEndedListener listener = callEndedListener;
        if (listener != null) {
            AndroidUtilities.runOnUIThread(() -> listener.onCallEnded(callId));
        }
    }

    /** @param sentTime push send time in ms (as passed to PushListenerController.processRemoteMessage) */
    public static void onPush(JSONObject json, long sentTime) {
        try {
            String locKey = json.optString("loc_key", "");
            if (locKey.isEmpty() || isSilent(locKey)) {
                return;
            }
            String senderName = firstLocArg(json);
            if (senderName == null) {
                senderName = LocaleController.getString(R.string.NotificationHiddenName);
            }
            JSONObject custom = json.optJSONObject("custom");
            long callId = custom == null ? 0 : custom.optLong("call_id", 0);
            if (PHONE_CALL_REQUEST.equals(locKey) || CONF_CALL_REQUEST.equals(locKey) || CONF_VIDEOCALL_REQUEST.equals(locKey)) {
                long ringTimeout = LockedCallBridge.getRingTimeoutMs();
                if (System.currentTimeMillis() - sentTime >= ringTimeout) {
                    // Delayed push: the call has already stopped ringing.
                    return;
                }
                // MTProto may deliver the real PhoneCall update just before FCM. Never cover Telegram's
                // native incoming-call UI with the locked fallback in that case.
                if (VoIPPreNotificationService.pendingCall != null
                        || VoIPService.getSharedInstance() != null
                        || VoIPService.callIShouldHavePutIntoIntent != null) {
                    cancelCall();
                    return;
                }
                showIncomingCall(senderName, callId, CONF_VIDEOCALL_REQUEST.equals(locKey), ringTimeout - (System.currentTimeMillis() - sentTime));
                return;
            }
            if (PHONE_CALL_MISSED.equals(locKey)) {
                cancelCall();
                LockedCallBridge.clear(callId);
                notifyCallEnded(callId);
            }
            showMessage(senderName);
        } catch (Throwable ignore) {
        }
    }

    public static void cancelCall() {
        cancel(CALL_NOTIFICATION_ID);
    }

    private static boolean isSilent(String locKey) {
        return locKey.startsWith("READ_")
                || locKey.startsWith("SESSION_")
                || locKey.equals("MESSAGE_DELETED")
                || locKey.equals("MESSAGE_MUTED")
                || locKey.equals("DC_UPDATE")
                || locKey.equals("GEO_LIVE_PENDING")
                || locKey.equals("LOCKED_MESSAGE")
                || locKey.equals("AUTH_REGION")
                || locKey.equals("AUTH_UNKNOWN");
    }

    private static String firstLocArg(JSONObject json) {
        JSONArray args = json.optJSONArray("loc_args");
        if (args != null && args.length() > 0) {
            String value = args.optString(0, null);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static void cancel(int id) {
        Context context = ApplicationLoader.applicationContext;
        NotificationManager manager = context != null ? (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE) : null;
        if (manager != null) {
            manager.cancel(id);
        }
    }

    private static void ensureChannels(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        if (manager.getNotificationChannel(MESSAGES_CHANNEL_ID) == null) {
            NotificationChannel messages = new NotificationChannel(MESSAGES_CHANNEL_ID, LocaleController.getString(R.string.NotificationHiddenName), NotificationManager.IMPORTANCE_HIGH);
            messages.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            manager.createNotificationChannel(messages);
        }
        if (manager.getNotificationChannel(LEGACY_CALLS_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(LEGACY_CALLS_CHANNEL_ID);
        }
        if (manager.getNotificationChannel(CALLS_CHANNEL_ID) == null) {
            NotificationChannel calls = new NotificationChannel(CALLS_CHANNEL_ID, LocaleController.getString(R.string.IncomingCallsSystemSetting), NotificationManager.IMPORTANCE_HIGH);
            calls.setDescription(LocaleController.getString(R.string.IncomingCallsSystemSettingDescription));
            calls.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            calls.enableVibration(true);
            calls.setVibrationPattern(new long[]{0, 700, 500});
            calls.setBypassDnd(true);
            calls.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(calls);
        }
    }

    private static void showMessage(String senderName) {
        Context context = ApplicationLoader.applicationContext;
        NotificationManager manager = context != null ? (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE) : null;
        if (manager == null) {
            return;
        }
        ensureChannels(manager);
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        Notification notification = new NotificationCompat.Builder(context, MESSAGES_CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(senderName)
                .setContentText(LocaleController.getString(R.string.YouHaveNewMessage))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(activityIntent(context, MESSAGE_NOTIFICATION_ID, launch))
                .build();
        manager.notify(MESSAGE_NOTIFICATION_ID, notification);
    }

    private static void showIncomingCall(String callerName, long callId, boolean video, long ringRemainingMs) {
        Context context = ApplicationLoader.applicationContext;
        NotificationManager manager = context != null ? (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE) : null;
        if (manager == null) {
            return;
        }
        ensureChannels(manager);

        final long deadline = System.currentTimeMillis() + ringRemainingMs;
        PendingIntent openIntent = activityIntent(context, CALL_NOTIFICATION_ID, callLaunchIntent(context, callId, callerName, deadline));
        String title = LocaleController.getString(video ? R.string.VoipInVideoCallBranding : R.string.VoipInCallBranding);

        Notification notification = new NotificationCompat.Builder(context, CALLS_CHANNEL_ID)
                .setSmallIcon(R.drawable.call)
                .setContentTitle(callerName)
                .setContentText(title)
                .setColor(0xff2ca5e0)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setShowWhen(false)
                .setTimeoutAfter(ringRemainingMs)
                .setContentIntent(openIntent)
                .setFullScreenIntent(openIntent, true)
                .build();
        notification.flags |= Notification.FLAG_INSISTENT;
        manager.notify(CALL_NOTIFICATION_ID, notification);
    }

    static Intent callLaunchIntent(Context context, long callId, String callerName, long deadline) {
        Intent target = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        return new Intent(context, WispyrUnlockActivity.class)
                .putExtra(WispyrUnlockActivity.EXTRA_TARGET, target)
                .putExtra(WispyrUnlockActivity.EXTRA_CALL_ID, callId)
                .putExtra(WispyrUnlockActivity.EXTRA_CALLER_NAME, callerName)
                .putExtra(WispyrUnlockActivity.EXTRA_CALL_DEADLINE, deadline);
    }

    private static PendingIntent activityIntent(Context context, int requestCode, Intent intent) {
        if (intent == null) {
            return null;
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }
}
