package org.wispyr.messenger.security;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

/**
 * Root of the local data protection. A random 256-bit master key is generated once and stored only in
 * wrapped form: AES-256-GCM under a non-exportable Android Keystore key (RSA-2048 on API 21-22).
 * Every storage layer uses its own HKDF-derived subkey, so a copied data directory is useless without
 * the device's secure hardware.
 */
public final class WispyrVault {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String MASTER_KEK_ALIAS = "wispyr_master_kek";
    private static final String MASTER_KEK_RSA_ALIAS = "wispyr_master_kek_rsa";
    private static final String PASSCODE_PEPPER_ALIAS = "wispyr_passcode_pepper";

    private static final byte[] MASTER_MAGIC = {'W', 'V', 'M', '1'};
    private static final byte[] MEDIA_KEY_MAGIC = {'W', 'M', 'K', '1'};
    private static final int WRAP_AES_GCM = 1;
    private static final int WRAP_RSA = 2;
    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;
    public static final int SEAL_OVERHEAD = GCM_NONCE_LENGTH + GCM_TAG_LENGTH;
    private static final int MEDIA_KEY_FILE_LENGTH = MEDIA_KEY_MAGIC.length + SEAL_OVERHEAD + 48;

    private static final SecureRandom random = new SecureRandom();
    private static final Object lock = new Object();

    private static final byte[] DATA_MAGIC = {'W', 'V', 'D', '1'};
    private static final int DATA_MODE_DEVICE = 1;
    private static final int DATA_MODE_PASSCODE = 2;

    private static volatile Context context;
    // Device tier: usable without the passcode (preferences, passcode verifier, push key).
    private static volatile byte[] masterKey;
    private static byte[] preferencesKey;
    private static byte[] passcodeFallbackPepper;
    private static byte[] dataDeviceWrapKey;
    private static byte[] systemTokenKey;
    // Passcode tier: databases, network auth keys and media keys. Null while locked.
    private static volatile byte[] dataKey;
    private static byte[] databaseKey;
    private static byte[] networkConfigKey;
    private static byte[] mediaKeyWrapKey;
    private static byte[] dataPreferencesKey;
    private static volatile int dataMode;

    private static final class UnrecoverableMasterKeyException extends Exception {
        UnrecoverableMasterKeyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private WispyrVault() {
    }

    public static void attach(Context appContext) {
        if (context == null && appContext != null) {
            context = appContext;
        }
    }

    public static byte[] getDatabaseKey() {
        requireDataKey();
        return databaseKey.clone();
    }

    public static byte[] getNetworkConfigKey() {
        requireDataKey();
        return networkConfigKey.clone();
    }

    /** True when a passcode protects the data tier and it has not been unlocked in this process. */
    public static boolean isLocked() {
        ensureLoaded();
        return dataKey == null;
    }

    public static boolean isPasscodeProtected() {
        ensureLoaded();
        return dataMode == DATA_MODE_PASSCODE;
    }

    private static void requireDataKey() {
        ensureLoaded();
        if (dataKey == null) {
            throw new VaultLockedException();
        }
    }

    public static final class VaultLockedException extends IllegalStateException {
        VaultLockedException() {
            super("data tier is locked by passcode");
        }
    }

    static byte[] getPreferencesKey() {
        ensureLoaded();
        return preferencesKey;
    }

    static byte[] getDataPreferencesKey() {
        requireDataKey();
        return dataPreferencesKey;
    }

    static byte[] getPasscodeFallbackPepper() {
        ensureLoaded();
        return passcodeFallbackPepper;
    }

    public static File getSecureDir(String name) {
        File dir = new File(noBackupDir(), name);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    static byte[] wrapMediaFileKey(byte[] fileKey, byte[] aad) throws GeneralSecurityException {
        requireDataKey();
        return seal(mediaKeyWrapKey, fileKey, aad);
    }

    static byte[] unwrapMediaFileKey(byte[] wrappedKey, byte[] aad) throws GeneralSecurityException {
        requireDataKey();
        return open(mediaKeyWrapKey, wrappedKey, 0, wrappedKey.length, aad);
    }

    // ---- media key files (".key" next to ".enc" media) ----

    public static void writeMediaKey(File keyFile, byte[] key, byte[] iv) throws IOException {
        if (isLocked()) {
            throw new IOException("data tier is locked");
        }
        byte[] plain = new byte[48];
        System.arraycopy(key, 0, plain, 0, 32);
        System.arraycopy(iv, 0, plain, 32, 16);
        try {
            byte[] sealed = seal(mediaKeyWrapKey, plain, MEDIA_KEY_MAGIC);
            ByteArrayOutputStream out = new ByteArrayOutputStream(MEDIA_KEY_FILE_LENGTH);
            out.write(MEDIA_KEY_MAGIC);
            out.write(sealed);
            try (FileOutputStream stream = new FileOutputStream(keyFile)) {
                stream.write(out.toByteArray());
                stream.getFD().sync();
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /**
     * Reads a wrapped media key. Returns false when the file is missing, malformed or cannot be
     * authenticated; the caller should then treat the media as unavailable (or generate a new key).
     */
    public static boolean readMediaKey(File keyFile, byte[] key, byte[] iv) {
        if (keyFile == null || !keyFile.exists() || keyFile.length() != MEDIA_KEY_FILE_LENGTH || isLocked()) {
            return false;
        }
        byte[] plain = null;
        try {
            byte[] data = readFile(keyFile);
            if (!startsWith(data, MEDIA_KEY_MAGIC)) {
                return false;
            }
            plain = open(mediaKeyWrapKey, data, MEDIA_KEY_MAGIC.length, data.length - MEDIA_KEY_MAGIC.length, MEDIA_KEY_MAGIC);
            System.arraycopy(plain, 0, key, 0, 32);
            System.arraycopy(plain, 32, iv, 0, 16);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    // ---- passcode pepper ----

    /**
     * HMAC-SHA256 with a non-exportable Keystore key: offline brute force of the passcode hash is
     * impossible without executing on this device's secure hardware.
     */
    static byte[] passcodePepper(byte[] input) throws GeneralSecurityException {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                return keystorePepper(input);
            } catch (GeneralSecurityException | IOException e) {
                throw new GeneralSecurityException(e);
            }
        }
        return hmac(getPasscodeFallbackPepper(), input);
    }

    @TargetApi(23)
    private static byte[] keystorePepper(byte[] input) throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        Key key = keyStore.getKey(PASSCODE_PEPPER_ALIAS, null);
        if (!(key instanceof SecretKey)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(PASSCODE_PEPPER_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build());
            key = generator.generateKey();
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        return mac.doFinal(input);
    }

    // ---- AES-256-GCM helpers (software keys derived from the master key) ----

    static byte[] seal(byte[] key, byte[] plain, byte[] aad) throws GeneralSecurityException {
        byte[] nonce = new byte[GCM_NONCE_LENGTH];
        random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        byte[] out = new byte[GCM_NONCE_LENGTH + plain.length + GCM_TAG_LENGTH];
        System.arraycopy(nonce, 0, out, 0, GCM_NONCE_LENGTH);
        cipher.doFinal(plain, 0, plain.length, out, GCM_NONCE_LENGTH);
        return out;
    }

    static byte[] open(byte[] key, byte[] data, int offset, int length, byte[] aad) throws GeneralSecurityException {
        if (length < SEAL_OVERHEAD) {
            throw new GeneralSecurityException("sealed data too short");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH * 8, data, offset, GCM_NONCE_LENGTH));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(data, offset + GCM_NONCE_LENGTH, length - GCM_NONCE_LENGTH);
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] hkdf(byte[] inputKey, String info) {
        try {
            byte[] prk = hmac(new byte[32], inputKey);
            byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
            byte[] block = new byte[infoBytes.length + 1];
            System.arraycopy(infoBytes, 0, block, 0, infoBytes.length);
            block[infoBytes.length] = 1;
            byte[] okm = hmac(prk, block);
            Arrays.fill(prk, (byte) 0);
            return okm;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    /** Stable, non-reversible identifier for Android system objects (channels, shortcuts, etc.). */
    public static String systemToken(String purpose, long id) {
        ensureLoaded();
        try {
            byte[] input = (purpose + ":" + id).getBytes(StandardCharsets.UTF_8);
            byte[] digest = hmac(systemTokenKey, input);
            StringBuilder result = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                result.append(Character.forDigit((digest[i] >>> 4) & 0xf, 16));
                result.append(Character.forDigit(digest[i] & 0xf, 16));
            }
            Arrays.fill(digest, (byte) 0);
            return result.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- master key lifecycle ----

    private static void ensureLoaded() {
        if (masterKey != null) {
            return;
        }
        synchronized (lock) {
            if (masterKey != null) {
                return;
            }
            byte[] key = loadOrCreateMasterKey();
            preferencesKey = hkdf(key, "wispyr/v1/preferences");
            passcodeFallbackPepper = hkdf(key, "wispyr/v1/passcode-pepper");
            dataDeviceWrapKey = hkdf(key, "wispyr/v2/data-device-wrap");
            systemTokenKey = hkdf(key, "wispyr/v2/system-token");
            loadDataKey();
            masterKey = key;
        }
    }

    // ---- passcode tier ----

    private static File dataKeyFile() {
        return new File(getSecureDir("vault"), "data.bin");
    }

    private static void setDataKey(byte[] key) {
        databaseKey = hkdf(key, "wispyr/v2/database");
        networkConfigKey = hkdf(key, "wispyr/v2/tgnet-config");
        mediaKeyWrapKey = hkdf(key, "wispyr/v2/media-keys");
        dataPreferencesKey = hkdf(key, "wispyr/v2/preferences");
        dataKey = key;
    }

    // data.bin layout: MAGIC(4) | version(1) | mode(1) | verifierLen(2, big-endian) | verifier | sealed(key)
    // The passcode verifier lives here, in the same atomically written file as the wrapped key, so the
    // "is a passcode required" state and the key state can never disagree (that would hang the app).
    private static final int DATA_VERSION = 2;
    private static volatile String passcodeVerifier;

    /** Called with {@link #lock} held. Leaves {@link #dataKey} null when a passcode is required. */
    private static void loadDataKey() {
        File file = dataKeyFile();
        if (file.exists()) {
            byte[] blob = readFileWithRetry(file);
            if (blob == null) {
                // The file exists but is temporarily unreadable. Never regenerate here: that would wipe the
                // real key and force a logout. Fail so the caller can retry on the next launch instead.
                throw new IllegalStateException("data key file is temporarily unreadable");
            }
            Parsed parsed = parse(blob);
            if (parsed != null) {
                if (parsed.mode == DATA_MODE_PASSCODE) {
                    dataMode = DATA_MODE_PASSCODE;
                    passcodeVerifier = parsed.verifier;
                    return;
                }
                if (parsed.mode == DATA_MODE_DEVICE) {
                    try {
                        byte[] key = open(dataDeviceWrapKey, parsed.sealed, 0, parsed.sealed.length,
                                parsed.legacy ? legacyDataAad(DATA_MODE_DEVICE) : dataAad(DATA_MODE_DEVICE));
                        dataMode = DATA_MODE_DEVICE;
                        setDataKey(key);
                        if (parsed.legacy) {
                            writeDataKey(DATA_MODE_DEVICE, dataDeviceWrapKey, key, null);
                        }
                        return;
                    } catch (AEADBadTagException e) {
                        // Device key rotated (data restored to another device): the old data is unrecoverable.
                    } catch (GeneralSecurityException | IOException e) {
                        throw new IllegalStateException("unable to unwrap data key", e);
                    }
                }
            }
            // Malformed or device-key mismatch: fall through and start a fresh (empty) data tier.
        }
        byte[] key = randomBytes(32);
        try {
            writeDataKey(DATA_MODE_DEVICE, dataDeviceWrapKey, key, null);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("unable to store data key", e);
        }
        dataMode = DATA_MODE_DEVICE;
        passcodeVerifier = null;
        setDataKey(key);
    }

    private static final class Parsed {
        int mode;
        String verifier;
        byte[] sealed;
        boolean legacy;
    }

    private static Parsed parse(byte[] blob) {
        if (!startsWith(blob, DATA_MAGIC)) {
            return null;
        }
        // v1 layout: MAGIC(4) | mode(1) | sealed 32-byte key(60).
        if (blob.length == DATA_MAGIC.length + 1 + 32 + SEAL_OVERHEAD) {
            int mode = blob[DATA_MAGIC.length] & 0xff;
            if (mode != DATA_MODE_DEVICE && mode != DATA_MODE_PASSCODE) {
                return null;
            }
            Parsed parsed = new Parsed();
            parsed.mode = mode;
            parsed.legacy = true;
            parsed.sealed = Arrays.copyOfRange(blob, DATA_MAGIC.length + 1, blob.length);
            return parsed;
        }
        if (blob.length < DATA_MAGIC.length + 4) {
            return null;
        }
        int offset = DATA_MAGIC.length;
        int version = blob[offset++] & 0xff;
        if (version != DATA_VERSION) {
            return null;
        }
        Parsed parsed = new Parsed();
        parsed.mode = blob[offset++] & 0xff;
        int verifierLen = ((blob[offset] & 0xff) << 8) | (blob[offset + 1] & 0xff);
        offset += 2;
        if (offset + verifierLen > blob.length) {
            return null;
        }
        parsed.verifier = verifierLen == 0 ? null : new String(blob, offset, verifierLen, StandardCharsets.UTF_8);
        offset += verifierLen;
        parsed.sealed = Arrays.copyOfRange(blob, offset, blob.length);
        return parsed;
    }

    private static byte[] dataAad(int mode) {
        return new byte[]{DATA_MAGIC[0], DATA_MAGIC[1], DATA_MAGIC[2], DATA_MAGIC[3], (byte) DATA_VERSION, (byte) mode};
    }

    private static byte[] legacyDataAad(int mode) {
        return new byte[]{DATA_MAGIC[0], DATA_MAGIC[1], DATA_MAGIC[2], DATA_MAGIC[3], (byte) mode};
    }

    private static void writeDataKey(int mode, byte[] wrapKey, byte[] key, String verifier) throws GeneralSecurityException, IOException {
        byte[] sealed = seal(wrapKey, key, dataAad(mode));
        byte[] verifierBytes = verifier == null ? new byte[0] : verifier.getBytes(StandardCharsets.UTF_8);
        if (verifierBytes.length > 0xffff) {
            throw new IOException("verifier too long");
        }
        byte[] out = new byte[DATA_MAGIC.length + 2 + 2 + verifierBytes.length + sealed.length];
        int offset = 0;
        System.arraycopy(DATA_MAGIC, 0, out, offset, DATA_MAGIC.length);
        offset += DATA_MAGIC.length;
        out[offset++] = (byte) DATA_VERSION;
        out[offset++] = (byte) mode;
        out[offset++] = (byte) ((verifierBytes.length >> 8) & 0xff);
        out[offset++] = (byte) (verifierBytes.length & 0xff);
        System.arraycopy(verifierBytes, 0, out, offset, verifierBytes.length);
        offset += verifierBytes.length;
        System.arraycopy(sealed, 0, out, offset, sealed.length);
        writeAtomic(dataKeyFile(), out);
    }

    private static byte[] readFileWithRetry(File file) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return readFile(file);
            } catch (IOException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignore) {
                }
            }
        }
        return null;
    }

    /** Encoded passcode verifier stored in the vault, or null when no passcode protects the data tier. */
    public static String getPasscodeVerifier() {
        ensureLoaded();
        String meta = passcodeVerifier;
        if (meta == null) {
            return null;
        }
        int sep = meta.indexOf(':');
        return sep < 0 ? meta : meta.substring(sep + 1);
    }

    /**
     * Supplies the verifier for the one-time v1 migration. In v2 this value is stored in data.bin itself.
     */
    public static void setLegacyPasscodeMetadata(String verifier, int passcodeType) {
        ensureLoaded();
        synchronized (lock) {
            if (dataMode == DATA_MODE_PASSCODE && passcodeVerifier == null && verifier != null) {
                passcodeVerifier = passcodeType + ":" + verifier;
            }
        }
    }

    /** Passcode input type (PIN or password) stored with the verifier, or -1 when not protected. */
    public static int getPasscodeType() {
        ensureLoaded();
        String meta = passcodeVerifier;
        if (meta == null) {
            return -1;
        }
        int sep = meta.indexOf(':');
        try {
            return sep < 0 ? 0 : Integer.parseInt(meta.substring(0, sep));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte[] passcodeWrapKey(byte[] passcodeSecret) {
        return hkdf(passcodeSecret, "wispyr/v2/data-passcode-wrap");
    }

    /**
     * Unwraps the data tier with the secret derived from the passcode (see {@link PasscodeHasher}).
     * Returns false if the secret does not match.
     */
    public static boolean unlock(byte[] passcodeSecret) {
        ensureLoaded();
        synchronized (lock) {
            if (dataKey != null) {
                return true;
            }
            byte[] wrapKey = passcodeWrapKey(passcodeSecret);
            byte[] unwrappedKey = null;
            try {
                byte[] blob = readFile(dataKeyFile());
                Parsed parsed = parse(blob);
                if (parsed == null || parsed.mode != DATA_MODE_PASSCODE) {
                    return false;
                }
                unwrappedKey = open(wrapKey, parsed.sealed, 0, parsed.sealed.length,
                        parsed.legacy ? legacyDataAad(DATA_MODE_PASSCODE) : dataAad(DATA_MODE_PASSCODE));
                if (parsed.legacy) {
                    if (passcodeVerifier == null) {
                        Arrays.fill(unwrappedKey, (byte) 0);
                        return false;
                    }
                    writeDataKey(DATA_MODE_PASSCODE, wrapKey, unwrappedKey, passcodeVerifier);
                }
                setDataKey(unwrappedKey);
                unwrappedKey = null; // ownership transferred to dataKey
            } catch (Exception e) {
                if (unwrappedKey != null) {
                    Arrays.fill(unwrappedKey, (byte) 0);
                }
                return false;
            } finally {
                Arrays.fill(wrapKey, (byte) 0);
            }
        }
        onUnlocked();
        return true;
    }

    // ---- waiting for the data tier ----

    private static final Object unlockMonitor = new Object();
    private static final ArrayList<Runnable> unlockActions = new ArrayList<>();

    /** Blocks the calling background thread until the data tier is available. */
    public static void awaitUnlocked() {
        if (!isLocked()) {
            return;
        }
        synchronized (unlockMonitor) {
            while (isLocked()) {
                try {
                    unlockMonitor.wait();
                } catch (InterruptedException ignore) {
                }
            }
        }
    }

    /** Runs {@code action} now if unlocked, otherwise right after the passcode unlocks the data tier. */
    public static void runWhenUnlocked(Runnable action) {
        synchronized (unlockMonitor) {
            if (isLocked()) {
                unlockActions.add(action);
                return;
            }
        }
        action.run();
    }

    private static void onUnlocked() {
        final ArrayList<Runnable> actions;
        synchronized (unlockMonitor) {
            unlockMonitor.notifyAll();
            actions = new ArrayList<>(unlockActions);
            unlockActions.clear();
        }
        for (Runnable action : actions) {
            action.run();
        }
    }

    /**
     * Re-wraps the (unlocked) data tier so that it can only be opened with the new passcode, storing the
     * verifier in the same atomic write.
     */
    public static void protectWithPasscode(byte[] passcodeSecret, String verifier, int passcodeType) throws GeneralSecurityException, IOException {
        requireDataKey();
        String meta = passcodeType + ":" + verifier;
        synchronized (lock) {
            byte[] wrapKey = passcodeWrapKey(passcodeSecret);
            try {
                writeDataKey(DATA_MODE_PASSCODE, wrapKey, dataKey, meta);
                dataMode = DATA_MODE_PASSCODE;
                passcodeVerifier = meta;
            } finally {
                Arrays.fill(wrapKey, (byte) 0);
            }
        }
    }

    /** Removes passcode protection: the data tier becomes available without user input again. */
    public static void removePasscodeProtection() throws GeneralSecurityException, IOException {
        requireDataKey();
        synchronized (lock) {
            writeDataKey(DATA_MODE_DEVICE, dataDeviceWrapKey, dataKey, null);
            dataMode = DATA_MODE_DEVICE;
            passcodeVerifier = null;
        }
    }

    private static File noBackupDir() {
        Context ctx = context;
        if (ctx == null) {
            throw new IllegalStateException("WispyrVault is not attached to a context");
        }
        return ctx.getNoBackupFilesDir();
    }

    private static byte[] loadOrCreateMasterKey() {
        File file = new File(getSecureDir("vault"), "master.bin");
        if (file.exists()) {
            GeneralSecurityException transientError = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    return unwrapMasterKey(readFile(file));
                } catch (UnrecoverableMasterKeyException e) {
                    // The Keystore key is gone (e.g. data restored to another device): previously
                    // encrypted data is unreadable by design and every layer falls back to empty state.
                    transientError = null;
                    break;
                } catch (GeneralSecurityException | IOException e) {
                    transientError = new GeneralSecurityException(e);
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ignore) {
                    }
                }
            }
            if (transientError != null) {
                // Never regenerate on a transient Keystore failure, that would destroy user data.
                throw new IllegalStateException("unable to unwrap master key", transientError);
            }
        }
        byte[] key = randomBytes(32);
        try {
            writeAtomic(file, wrapMasterKey(key));
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("unable to store master key", e);
        }
        return key;
    }

    private static byte[] wrapMasterKey(byte[] key) throws GeneralSecurityException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MASTER_MAGIC);
        if (Build.VERSION.SDK_INT >= 23) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKek());
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(key);
            out.write(WRAP_AES_GCM);
            out.write(iv.length);
            out.write(iv);
            out.write(cipherText);
        } else {
            KeyPair keyPair = getOrCreateRsaKek();
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keyPair.getPublic());
            out.write(WRAP_RSA);
            out.write(0);
            out.write(cipher.doFinal(key));
        }
        return out.toByteArray();
    }

    private static byte[] unwrapMasterKey(byte[] blob) throws GeneralSecurityException, IOException, UnrecoverableMasterKeyException {
        if (blob.length < MASTER_MAGIC.length + 2 || !startsWith(blob, MASTER_MAGIC)) {
            throw new UnrecoverableMasterKeyException("malformed master key file", null);
        }
        int type = blob[MASTER_MAGIC.length];
        int ivLength = blob[MASTER_MAGIC.length + 1] & 0xff;
        int offset = MASTER_MAGIC.length + 2;
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        try {
            byte[] key;
            if (type == WRAP_AES_GCM && Build.VERSION.SDK_INT >= 23) {
                Key kek = keyStore.getKey(MASTER_KEK_ALIAS, null);
                if (!(kek instanceof SecretKey)) {
                    throw new UnrecoverableMasterKeyException("keystore key missing", null);
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(128, blob, offset, ivLength));
                key = cipher.doFinal(blob, offset + ivLength, blob.length - offset - ivLength);
            } else if (type == WRAP_RSA) {
                Key kek = keyStore.getKey(MASTER_KEK_RSA_ALIAS, null);
                if (!(kek instanceof PrivateKey)) {
                    throw new UnrecoverableMasterKeyException("keystore key missing", null);
                }
                Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                cipher.init(Cipher.DECRYPT_MODE, kek);
                key = cipher.doFinal(blob, offset, blob.length - offset);
            } else {
                throw new UnrecoverableMasterKeyException("unknown wrap type " + type, null);
            }
            if (key.length != 32) {
                throw new UnrecoverableMasterKeyException("bad master key length", null);
            }
            return key;
        } catch (AEADBadTagException | UnrecoverableKeyException e) {
            throw new UnrecoverableMasterKeyException("master key cannot be recovered", e);
        } catch (GeneralSecurityException e) {
            if (Build.VERSION.SDK_INT >= 23 && e instanceof KeyPermanentlyInvalidatedException) {
                throw new UnrecoverableMasterKeyException("keystore key invalidated", e);
            }
            throw e;
        }
    }

    @TargetApi(23)
    private static SecretKey getOrCreateAesKek() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        Key existing = keyStore.getKey(MASTER_KEK_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(MASTER_KEK_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    @SuppressWarnings("deprecation")
    private static KeyPair getOrCreateRsaKek() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(MASTER_KEK_RSA_ALIAS)) {
            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(MASTER_KEK_RSA_ALIAS, null);
            return new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
        }
        Calendar start = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 30);
        KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(context)
                .setAlias(MASTER_KEK_RSA_ALIAS)
                .setSubject(new X500Principal("CN=" + MASTER_KEK_RSA_ALIAS))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.getTime())
                .setEndDate(end.getTime())
                .setKeySize(2048)
                .build();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", KEYSTORE);
        generator.initialize(spec);
        return generator.generateKeyPair();
    }

    // ---- file helpers ----

    static byte[] readFile(File file) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) {
            long length = file.length();
            if (length > Integer.MAX_VALUE) {
                throw new IOException("file too large");
            }
            byte[] data = new byte[(int) length];
            int read = 0;
            while (read < data.length) {
                int count = stream.read(data, read, data.length - read);
                if (count < 0) {
                    throw new IOException("unexpected end of file");
                }
                read += count;
            }
            return data;
        }
    }

    static void writeAtomic(File file, byte[] data) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temp)) {
            stream.write(data);
            stream.getFD().sync();
        }
        if (!temp.renameTo(file)) {
            temp.delete();
            throw new IOException("rename failed for " + file.getName());
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
