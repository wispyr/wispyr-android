package org.wispyr.messenger.security;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Passcode verifier: Argon2id (memory-hard, ~1 s per guess on the device) followed by HMAC with a
 * non-exportable Android Keystore key. The stored verifier cannot be attacked offline at all, and
 * on-device guessing costs a full Argon2id evaluation per attempt.
 *
 * Encoded form: $wy1$m=<KiB>,t=<iterations>,p=<lanes>$<salt hex>$<verifier hex>
 */
public final class PasscodeHasher {

    private static final String PREFIX = "$wy1$";
    private static final int MEMORY_KIB = 64 * 1024;
    private static final int LOW_RAM_MEMORY_KIB = 32 * 1024;
    private static final int PARALLELISM = 2;
    private static final long TARGET_MILLIS = 1000;
    private static final int MIN_ITERATIONS = 2;
    private static final int MAX_ITERATIONS = 64;
    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PasscodeHasher");
        thread.setPriority(Thread.MAX_PRIORITY);
        return thread;
    });

    private static native byte[] argon2id(byte[] password, byte[] salt, int iterations, int memoryKiB, int parallelism, int hashLength);

    private PasscodeHasher() {
    }

    public static ExecutorService executor() {
        return executor;
    }

    public static boolean isCurrentFormat(String encoded) {
        return encoded != null && encoded.startsWith(PREFIX);
    }

    /** Encoded verifier plus the secret that wraps the passcode-protected data tier. */
    public static final class Result {
        public final String encoded;
        public final byte[] secret;

        Result(String encoded, byte[] secret) {
            this.encoded = encoded;
            this.secret = secret;
        }
    }

    /** Blocking; call from a background thread. */
    public static Result create(String passcode, Context context) throws Exception {
        int memory = MEMORY_KIB;
        ActivityManager activityManager = context != null ? (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE) : null;
        if (activityManager != null && activityManager.isLowRamDevice()) {
            memory = LOW_RAM_MEMORY_KIB;
        }
        byte[] salt = WispyrVault.randomBytes(SALT_LENGTH);

        long start = SystemClock.elapsedRealtime();
        byte[] probe = argon2id(new byte[]{0}, salt, 1, memory, PARALLELISM, HASH_LENGTH);
        long elapsed = Math.max(1, SystemClock.elapsedRealtime() - start);
        if (probe == null) {
            throw new IllegalStateException("argon2id failed");
        }
        int iterations = (int) Math.max(MIN_ITERATIONS, Math.min(MAX_ITERATIONS, (TARGET_MILLIS + elapsed - 1) / elapsed));

        byte[] secret = computeSecret(passcode, salt, iterations, memory, PARALLELISM);
        byte[] verifier = WispyrVault.hkdf(secret, "wispyr/v1/passcode-verifier");
        String encoded = String.format(Locale.US, "%sm=%d,t=%d,p=%d$%s$%s", PREFIX, memory, iterations, PARALLELISM, toHex(salt), toHex(verifier));
        return new Result(encoded, secret);
    }

    /** Blocking; call from a background thread. */
    public static boolean verify(String passcode, String encoded) {
        byte[] secret = deriveSecret(passcode, encoded);
        if (secret == null) {
            return false;
        }
        Arrays.fill(secret, (byte) 0);
        return true;
    }

    /** Blocking; returns the data-tier secret if the passcode matches {@code encoded}, otherwise null. */
    public static byte[] deriveSecret(String passcode, String encoded) {
        if (!isCurrentFormat(encoded)) {
            return null;
        }
        try {
            String[] parts = encoded.substring(PREFIX.length()).split("\\$");
            if (parts.length != 3) {
                return null;
            }
            int memory = 0, iterations = 0, parallelism = 0;
            for (String param : parts[0].split(",")) {
                int eq = param.indexOf('=');
                int value = Integer.parseInt(param.substring(eq + 1));
                switch (param.substring(0, eq)) {
                    case "m": memory = value; break;
                    case "t": iterations = value; break;
                    case "p": parallelism = value; break;
                }
            }
            if (memory < 8 * 1024 || iterations < 1 || parallelism < 1) {
                return null;
            }
            byte[] salt = fromHex(parts[1]);
            byte[] expected = fromHex(parts[2]);
            byte[] secret = computeSecret(passcode, salt, iterations, memory, parallelism);
            byte[] actual = WispyrVault.hkdf(secret, "wispyr/v1/passcode-verifier");
            if (MessageDigest.isEqual(expected, actual)) {
                return secret;
            }
            Arrays.fill(secret, (byte) 0);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] computeSecret(String passcode, byte[] salt, int iterations, int memory, int parallelism) throws Exception {
        byte[] password = passcode.getBytes(StandardCharsets.UTF_8);
        byte[] stretched = argon2id(password, salt, iterations, memory, parallelism, HASH_LENGTH);
        Arrays.fill(password, (byte) 0);
        if (stretched == null) {
            throw new IllegalStateException("argon2id failed");
        }
        byte[] peppered = WispyrVault.passcodePepper(stretched);
        Arrays.fill(stretched, (byte) 0);
        return peppered;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return builder.toString();
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
