package org.wispyr.messenger.security;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** Empty, read-only view returned only while passcode-protected preferences are locked. */
final class LockedPreferences implements SharedPreferences {
    static final LockedPreferences INSTANCE = new LockedPreferences();

    private static final Editor EDITOR = new Editor() {
        @Override public Editor putString(String key, String value) { return this; }
        @Override public Editor putStringSet(String key, Set<String> values) { return this; }
        @Override public Editor putInt(String key, int value) { return this; }
        @Override public Editor putLong(String key, long value) { return this; }
        @Override public Editor putFloat(String key, float value) { return this; }
        @Override public Editor putBoolean(String key, boolean value) { return this; }
        @Override public Editor remove(String key) { return this; }
        @Override public Editor clear() { return this; }
        @Override public boolean commit() { return false; }
        @Override public void apply() {}
    };

    private LockedPreferences() {}

    @Override public Map<String, ?> getAll() { return Collections.emptyMap(); }
    @Override public String getString(String key, String defValue) { return defValue; }
    @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
    @Override public int getInt(String key, int defValue) { return defValue; }
    @Override public long getLong(String key, long defValue) { return defValue; }
    @Override public float getFloat(String key, float defValue) { return defValue; }
    @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
    @Override public boolean contains(String key) { return false; }
    @Override public Editor edit() { return EDITOR; }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
}
