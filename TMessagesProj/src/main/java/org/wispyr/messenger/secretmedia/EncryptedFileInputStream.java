/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.wispyr.messenger.secretmedia;

import org.wispyr.messenger.SecureDocumentKey;
import org.wispyr.messenger.Utilities;
import org.wispyr.messenger.security.MediaContainer;
import org.wispyr.messenger.security.WispyrVault;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class EncryptedFileInputStream extends FileInputStream {

    private byte[] key = new byte[32];
    private byte[] iv = new byte[16];
    private long fileOffset;
    private int currentMode;
    private MediaContainer.Reader mediaReader;
    private InputStream mediaInput;

    private final static int MODE_CTR = 0;
    private final static int MODE_CBC = 1;

    public EncryptedFileInputStream(File file, File keyFile) throws Exception {
        super(file);

        if (MediaContainer.isContainer(file)) {
            try {
                mediaReader = MediaContainer.openReader(file);
                mediaInput = mediaReader.inputStream();
                currentMode = MODE_CTR;
                return;
            } catch (Exception e) {
                super.close();
                throw e;
            }
        }
        currentMode = MODE_CTR;
        if (!WispyrVault.readMediaKey(keyFile, key, iv)) {
            super.close();
            throw new IOException("media key unavailable");
        }
    }

    public EncryptedFileInputStream(File file, SecureDocumentKey secureDocumentKey) throws Exception {
        super(file);

        currentMode = MODE_CBC;
        System.arraycopy(secureDocumentKey.file_key, 0, key, 0, key.length);
        System.arraycopy(secureDocumentKey.file_iv, 0, iv, 0, iv.length);
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int result = read(one, 0, 1);
        return result == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (mediaInput != null) {
            return mediaInput.read(b, off, len);
        }
        if (currentMode == MODE_CBC && fileOffset == 0) {
            byte[] temp = new byte[32];
            super.read(temp, 0, 32);
            Utilities.aesCbcEncryptionByteArraySafe(b, key, iv, off, len, (int) fileOffset, 0);
            fileOffset += 32;
            skip((temp[0] & 0xff) - 32);
        }
        int result = super.read(b, off, len);
        if (result > 0 && currentMode == MODE_CBC) {
            Utilities.aesCbcEncryptionByteArraySafe(b, key, iv, off, result, (int) fileOffset, 0);
        } else if (result > 0 && currentMode == MODE_CTR) {
            Utilities.aesCtrDecryptionByteArray(b, key, iv, off, result, fileOffset);
        }
        if (result > 0) {
            fileOffset += result;
        }
        return result;
    }

    @Override
    public long skip(long n) throws IOException {
        if (mediaInput != null) {
            return mediaInput.skip(n);
        }
        fileOffset += n;
        return super.skip(n);
    }

    @Override
    public void close() throws IOException {
        IOException error = null;
        try {
            if (mediaInput != null) {
                mediaInput.close();
                mediaInput = null;
            }
            if (mediaReader != null) {
                mediaReader.close();
                mediaReader = null;
            }
        } catch (IOException e) {
            error = e;
        }
        try {
            super.close();
        } catch (IOException e) {
            if (error == null) {
                error = e;
            }
        }
        if (error != null) {
            throw error;
        }
    }

    public static void decryptBytesWithKeyFile(byte[] bytes, int offset, int length, SecureDocumentKey secureDocumentKey) {
        Utilities.aesCbcEncryptionByteArraySafe(bytes, secureDocumentKey.file_key, secureDocumentKey.file_iv, offset, length, 0, 0);
    }

    public static void decryptBytesWithKeyFile(byte[] bytes, int offset, int length, File keyFile) throws Exception {
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        if (!WispyrVault.readMediaKey(keyFile, key, iv)) {
            throw new IOException("media key unavailable");
        }
        Utilities.aesCtrDecryptionByteArray(bytes, key, iv, offset, length, 0);
    }
}
