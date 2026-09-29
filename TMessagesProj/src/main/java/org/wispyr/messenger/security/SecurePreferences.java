package org.wispyr.messenger.security;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SharedPreferences stored as a single AES-256-GCM encrypted file (key derived from {@link WispyrVault}).
 * Semantics follow the platform implementation: in-memory reads, synchronous commit(), asynchronous apply().
 */
public final class SecurePreferences implements SharedPreferences {

    private static final byte[] MAGIC = {'W', 'P', 'F', '1'};
    private static final int TYPE_STRING = 1;
    private static final int TYPE_INT = 2;
    private static final int TYPE_LONG = 3;
    private static final int TYPE_FLOAT = 4;
    private static final int TYPE_BOOLEAN = 5;
    private static final int TYPE_STRING_SET = 6;

    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SecurePrefsWriter");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Object REMOVED = new Object();

    private final String name;
    private final File file;
    private final byte[] aad;
    private final boolean dataTier;
    private final Object lock = new Object();
    private final Object diskLock = new Object();
    private HashMap<String, Object> map;
    private long memoryGeneration;
    private long diskGeneration;
    private final WeakHashMap<OnSharedPreferenceChangeListener, Object> listeners = new WeakHashMap<>();

    SecurePreferences(String name, File file, @Nullable Map<String, ?> legacyValues) {
        this(name, file, legacyValues, false);
    }

    SecurePreferences(String name, File file, @Nullable Map<String, ?> legacyValues, boolean dataTier) {
        this.name = name;
        this.file = file;
        this.aad = ("wispyr-prefs:" + name).getBytes(StandardCharsets.UTF_8);
        this.dataTier = dataTier;
        HashMap<String, Object> loaded = load();
        if (loaded == null) {
            loaded = new HashMap<>();
            if (legacyValues != null) {
                for (Map.Entry<String, ?> entry : legacyValues.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof Set) {
                        value = new HashSet<>((Set<?>) value);
                    }
                    if (value != null) {
                        loaded.put(entry.getKey(), value);
                    }
                }
                map = loaded;
                memoryGeneration = 1;
                writeToDisk();
            }
        }
        map = loaded;
    }

    private byte[] encryptionKey() {
        return dataTier ? WispyrVault.getDataPreferencesKey() : WispyrVault.getPreferencesKey();
    }

    public String getName() {
        return name;
    }

    boolean hasFileOnDisk() {
        return file.exists();
    }

    void deleteFile() {
        synchronized (lock) {
            map = new HashMap<>();
            memoryGeneration++;
        }
        synchronized (diskLock) {
            file.delete();
            diskGeneration = memoryGeneration;
        }
    }

    // ---- reads ----

    @Override
    public Map<String, ?> getAll() {
        synchronized (lock) {
            return new HashMap<>(map);
        }
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) {
        synchronized (lock) {
            Object value = map.get(key);
            return value instanceof String ? (String) value : value == null ? defValue : throwWrongType(key, "String");
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        synchronized (lock) {
            Object value = map.get(key);
            return value instanceof Set ? (Set<String>) value : value == null ? defValues : throwWrongType(key, "Set");
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        synchronized (lock) {
            Object value = map.get(key);
            return value instanceof Integer ? (Integer) value : value == null ? defValue : SecurePreferences.<Integer>throwWrongType(key, "Integer");
        }
    }

    @Override
    public long getLong(String key, long defValue) {
        synchronized (lock) {
            Object value = map.get(key);
            return value instanceof Long ? (Long) value : value == null ? defValue : SecurePreferences.<Long>throwWrongType(key, "Long");
        }
    }

    @Override
    public float getFloat(String key, float defValue) {
        synchronized (lock) {
            Object value = map.get(key);
            return value instanceof Float ? (Float) value : value == null ? defValue : SecurePreferences.<Float>throwWrongType(key, "Float");
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        synchronized (lock) {
            Object value = map.get(key);
            return value instanceof Boolean ? (Boolean) value : value == null ? defValue : SecurePreferences.<Boolean>throwWrongType(key, "Boolean");
        }
    }

    @Override
    public boolean contains(String key) {
        synchronized (lock) {
            return map.containsKey(key);
        }
    }

    private static <T> T throwWrongType(String key, String type) {
        throw new ClassCastException("preference " + key + " is not a " + type);
    }

    // ---- writes ----

    @Override
    public Editor edit() {
        return new EditorImpl();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        synchronized (lock) {
            listeners.put(listener, REMOVED);
        }
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        synchronized (lock) {
            listeners.remove(listener);
        }
    }

    private final class EditorImpl implements Editor {

        private final HashMap<String, Object> modified = new HashMap<>();
        private boolean clear;

        @Override
        public Editor putString(String key, @Nullable String value) {
            synchronized (this) {
                modified.put(key, value == null ? REMOVED : value);
            }
            return this;
        }

        @Override
        public Editor putStringSet(String key, @Nullable Set<String> values) {
            synchronized (this) {
                modified.put(key, values == null ? REMOVED : new HashSet<>(values));
            }
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            synchronized (this) {
                modified.put(key, value);
            }
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            synchronized (this) {
                modified.put(key, value);
            }
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            synchronized (this) {
                modified.put(key, value);
            }
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            synchronized (this) {
                modified.put(key, value);
            }
            return this;
        }

        @Override
        public Editor remove(String key) {
            synchronized (this) {
                modified.put(key, REMOVED);
            }
            return this;
        }

        @Override
        public Editor clear() {
            synchronized (this) {
                clear = true;
            }
            return this;
        }

        @Override
        public boolean commit() {
            ArrayList<String> changedKeys = commitToMemory();
            boolean ok = writeToDisk();
            notifyListeners(changedKeys);
            return ok;
        }

        @Override
        public void apply() {
            ArrayList<String> changedKeys = commitToMemory();
            writer.execute(SecurePreferences.this::writeToDisk);
            notifyListeners(changedKeys);
        }

        private ArrayList<String> commitToMemory() {
            ArrayList<String> changedKeys = new ArrayList<>();
            synchronized (lock) {
                synchronized (this) {
                    boolean changed = false;
                    if (clear) {
                        if (!map.isEmpty()) {
                            map = new HashMap<>();
                            changed = true;
                        }
                        clear = false;
                    }
                    for (Map.Entry<String, Object> entry : modified.entrySet()) {
                        String key = entry.getKey();
                        Object value = entry.getValue();
                        if (value == REMOVED) {
                            if (!map.containsKey(key)) {
                                continue;
                            }
                            map.remove(key);
                        } else {
                            Object existing = map.get(key);
                            if (existing != null && existing.equals(value)) {
                                continue;
                            }
                            map.put(key, value);
                        }
                        changed = true;
                        changedKeys.add(key);
                    }
                    modified.clear();
                    if (changed) {
                        memoryGeneration++;
                    }
                }
            }
            return changedKeys;
        }
    }

    private void notifyListeners(ArrayList<String> changedKeys) {
        if (changedKeys.isEmpty()) {
            return;
        }
        final ArrayList<OnSharedPreferenceChangeListener> targets;
        synchronized (lock) {
            if (listeners.isEmpty()) {
                return;
            }
            targets = new ArrayList<>(listeners.keySet());
        }
        Runnable notify = () -> {
            for (int i = changedKeys.size() - 1; i >= 0; i--) {
                for (OnSharedPreferenceChangeListener listener : targets) {
                    if (listener != null) {
                        listener.onSharedPreferenceChanged(this, changedKeys.get(i));
                    }
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notify.run();
        } else {
            mainHandler.post(notify);
        }
    }

    // ---- disk ----

    private boolean writeToDisk() {
        synchronized (diskLock) {
            final HashMap<String, Object> snapshot;
            final long generation;
            synchronized (lock) {
                if (diskGeneration >= memoryGeneration) {
                    return true;
                }
                snapshot = new HashMap<>(map);
                generation = memoryGeneration;
            }
            byte[] plain = null;
            try {
                plain = serialize(snapshot);
                byte[] sealed = WispyrVault.seal(encryptionKey(), plain, aad);
                byte[] out = new byte[MAGIC.length + sealed.length];
                System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
                System.arraycopy(sealed, 0, out, MAGIC.length, sealed.length);
                WispyrVault.writeAtomic(file, out);
                diskGeneration = generation;
                return true;
            } catch (Exception e) {
                android.util.Log.e("SecurePreferences", "failed to write " + name, e);
                return false;
            } finally {
                if (plain != null) {
                    Arrays.fill(plain, (byte) 0);
                }
            }
        }
    }

    @Nullable
    private HashMap<String, Object> load() {
        if (!file.exists()) {
            return null;
        }
        byte[] plain = null;
        try {
            byte[] data = WispyrVault.readFile(file);
            if (data.length < MAGIC.length || data[0] != MAGIC[0] || data[1] != MAGIC[1] || data[2] != MAGIC[2] || data[3] != MAGIC[3]) {
                return new HashMap<>();
            }
            plain = WispyrVault.open(encryptionKey(), data, MAGIC.length, data.length - MAGIC.length, aad);
            return deserialize(plain);
        } catch (Exception e) {
            android.util.Log.e("SecurePreferences", "failed to read " + name + ", starting empty", e);
            return new HashMap<>();
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    private static byte[] serialize(HashMap<String, Object> values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 + values.size() * 32);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(values.size());
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            writeString(out, entry.getKey());
            Object value = entry.getValue();
            if (value instanceof String) {
                out.writeByte(TYPE_STRING);
                writeString(out, (String) value);
            } else if (value instanceof Integer) {
                out.writeByte(TYPE_INT);
                out.writeInt((Integer) value);
            } else if (value instanceof Long) {
                out.writeByte(TYPE_LONG);
                out.writeLong((Long) value);
            } else if (value instanceof Float) {
                out.writeByte(TYPE_FLOAT);
                out.writeFloat((Float) value);
            } else if (value instanceof Boolean) {
                out.writeByte(TYPE_BOOLEAN);
                out.writeBoolean((Boolean) value);
            } else if (value instanceof Set) {
                Set<?> set = (Set<?>) value;
                out.writeByte(TYPE_STRING_SET);
                out.writeInt(set.size());
                for (Object item : set) {
                    writeString(out, (String) item);
                }
            } else {
                throw new IOException("unsupported preference type " + value);
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static HashMap<String, Object> deserialize(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int count = in.readInt();
        HashMap<String, Object> values = new HashMap<>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) {
            String key = readString(in);
            int type = in.readByte();
            switch (type) {
                case TYPE_STRING:
                    values.put(key, readString(in));
                    break;
                case TYPE_INT:
                    values.put(key, in.readInt());
                    break;
                case TYPE_LONG:
                    values.put(key, in.readLong());
                    break;
                case TYPE_FLOAT:
                    values.put(key, in.readFloat());
                    break;
                case TYPE_BOOLEAN:
                    values.put(key, in.readBoolean());
                    break;
                case TYPE_STRING_SET: {
                    int size = in.readInt();
                    HashSet<String> set = new HashSet<>(Math.max(4, size * 2));
                    for (int j = 0; j < size; j++) {
                        set.add(readString(in));
                    }
                    values.put(key, set);
                    break;
                }
                default:
                    throw new IOException("unknown preference type " + type);
            }
        }
        return values;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) {
            throw new IOException("corrupted string length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
