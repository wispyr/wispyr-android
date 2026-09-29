package org.wispyr.messenger.security;

/** JNI bridge to BoringSSL AES-256-GCM-SIV (RFC 8452). */
final class MediaAead {
    static final int KEY_SIZE = 32;
    static final int NONCE_SIZE = 12;
    static final int TAG_SIZE = 16;

    private MediaAead() {}

    static native byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext);
    static native byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext);
}
