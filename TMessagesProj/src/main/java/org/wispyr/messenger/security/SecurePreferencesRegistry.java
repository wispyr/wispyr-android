package org.wispyr.messenger.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public final class SecurePreferencesRegistry {

    public interface PlatformPreferences {
        SharedPreferences get(String name);

        boolean delete(String name);
    }

    // Preferences owned by third-party libraries keep the platform implementation.
    private static final String[] PASSTHROUGH_PREFIXES = {
            "com.google.", "com.android.", "android.", "androidx.", "com.huawei.", "com.hms.",
            "FirebaseHeartBeat", "FirebaseApp", "WebView", "admob", "com.facebook."
    };

    private static final HashMap<String, SecurePreferences> cache = new HashMap<>();
    private static final HashMap<String, SecurePreferences> dataCache = new HashMap<>();

    private SecurePreferencesRegistry() {
    }

    public static boolean shouldEncrypt(String name) {
        if (name == null) {
            return false;
        }
        for (String prefix : PASSTHROUGH_PREFIXES) {
            if (name.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Files that contain account identifiers, per-dialog settings, history or login state. Global display
     * preferences and the bootstrap file (push key + lock state) intentionally remain in the device tier.
     */
    public static boolean isDataTier(String name) {
        if (name == null) {
            return false;
        }
        return name.startsWith("Notifications")
                || name.startsWith("emoji")
                || name.startsWith("userconfig")
                || name.startsWith("logininfo")
                || name.startsWith("shortcut_widget")
                || name.startsWith("webhistory")
                || name.startsWith("botshare")
                || name.startsWith("media_saved_pos")
                || name.startsWith("webview_bots")
                || name.startsWith("account_config_")
                || name.startsWith("mainconfig")
                || name.contains("save_gallery")
                || name.startsWith("bot_");
    }

    public static SharedPreferences get(Context context, String name, PlatformPreferences platform) {
        synchronized (cache) {
            SecurePreferences preferences = cache.get(name);
            if (preferences != null) {
                return preferences;
            }
            File file = deviceFile(name);
            File legacyFile = legacyFile(context, name);
            Map<String, ?> legacyValues = null;
            if (!file.exists() && legacyFile.exists()) {
                legacyValues = platform.get(name).getAll();
            }
            preferences = new SecurePreferences(name, file, legacyValues);
            if (legacyValues != null && preferences.hasFileOnDisk()) {
                deleteLegacy(context, name, platform);
            }
            cache.put(name, preferences);
            return preferences;
        }
    }

    /**
     * Preferences encrypted with the passcode-protected data tier. Callers must only request these after
     * the vault is unlocked.
     */
    public static SharedPreferences getData(String name, Map<String, ?> legacyValues) {
        if (WispyrVault.isLocked()) {
            return LockedPreferences.INSTANCE;
        }
        synchronized (dataCache) {
            SecurePreferences preferences = dataCache.get(name);
            if (preferences != null) {
                return preferences;
            }
            File file = dataFile(name);
            preferences = new SecurePreferences("data:" + name, file, file.exists() ? null : legacyValues, true);
            dataCache.put(name, preferences);
            return preferences;
        }
    }

    public static SharedPreferences getDataMigrating(Context context, String name, PlatformPreferences platform) {
        if (WispyrVault.isLocked()) {
            return LockedPreferences.INSTANCE;
        }
        if (hasData(name)) {
            return getData(name, null);
        }
        SharedPreferences old = get(context, name, platform);
        Map<String, ?> values = old.getAll();
        SharedPreferences result = getData(name, values);
        if (!values.isEmpty()) {
            old.edit().clear().commit();
        }
        return result;
    }

    public static boolean hasData(String name) {
        File file = dataFile(name);
        return file.exists();
    }

    public static void deleteData(String name) {
        synchronized (dataCache) {
            SecurePreferences preferences = dataCache.remove(name);
            if (preferences != null) {
                preferences.deleteFile();
            } else {
                dataFile(name).delete();
            }
        }
    }

    public static boolean delete(Context context, String name, PlatformPreferences platform) {
        synchronized (cache) {
            SecurePreferences preferences = cache.get(name);
            if (preferences != null) {
                preferences.deleteFile();
            } else {
                deviceFile(name).delete();
            }
            deleteLegacy(context, name, platform);
            return true;
        }
    }

    private static void deleteLegacy(Context context, String name, PlatformPreferences platform) {
        File legacyFile = legacyFile(context, name);
        if (!legacyFile.exists()) {
            return;
        }
        platform.get(name).edit().clear().commit();
        if (Build.VERSION.SDK_INT >= 24) {
            platform.delete(name);
        }
        legacyFile.delete();
        new File(legacyFile.getPath() + ".bak").delete();
    }

    private static File legacyFile(Context context, String name) {
        return new File(new File(context.getApplicationInfo().dataDir, "shared_prefs"), name + ".xml");
    }

    private static File deviceFile(String name) {
        return new File(WispyrVault.getSecureDir("prefs"), fileName(name));
    }

    private static File dataFile(String name) {
        return new File(WispyrVault.getSecureDir("data_prefs"), fileName("data:" + name));
    }

    private static String fileName(String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("wispyr-prefs:" + name).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(40);
            for (int i = 0; i < 16; i++) {
                builder.append(String.format("%02x", digest[i] & 0xff));
            }
            return builder.append(".bin").toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
