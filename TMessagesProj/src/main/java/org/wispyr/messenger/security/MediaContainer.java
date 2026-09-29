package org.wispyr.messenger.security;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.BitSet;

/** Random-access, chunk-authenticated WYM2 media container. */
public final class MediaContainer {
    public static final int HEADER_SIZE = 256;
    public static final int DEFAULT_CHUNK_SIZE = 256 * 1024;
    public static final int MIN_CHUNK_SIZE = 16 * 1024;
    public static final int MAX_CHUNK_SIZE = 1024 * 1024;

    private static final byte[] MAGIC = {'W', 'Y', 'M', '2'};
    private static final int VERSION = 2;
    private static final int ALGORITHM_AES_256_GCM_SIV = 1;
    private static final int METADATA_SIZE = 52;
    private static final int BINDING_OFFSET = 36;
    private static final int WRAPPED_LENGTH_OFFSET = 52;
    private static final int WRAPPED_KEY_OFFSET = 54;

    private MediaContainer() {}

    public static boolean isContainer(File file) {
        if (file == null || !file.isFile() || file.length() < HEADER_SIZE) {
            return false;
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[HEADER_SIZE];
            input.readFully(header);
            Header parsed = parseHeader(header);
            Arrays.fill(header, (byte) 0);
            Arrays.fill(parsed.fileId, (byte) 0);
            Arrays.fill(parsed.bindingHash, (byte) 0);
            Arrays.fill(parsed.wrappedKey, (byte) 0);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static Writer createWriter(File file, long plaintextSize, int chunkSize) throws IOException {
        return new Writer(file, plaintextSize, chunkSize, file.getName());
    }

    public static Writer createWriter(File file, long plaintextSize, int chunkSize, String bindingName) throws IOException {
        return new Writer(file, plaintextSize, chunkSize, bindingName);
    }

    public static Reader openReader(File file) throws IOException {
        return new Reader(file, file.getName());
    }

    public static Reader openReader(File file, String bindingName) throws IOException {
        return new Reader(file, bindingName);
    }

    public static long plaintextSize(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[HEADER_SIZE];
            input.readFully(header);
            Header parsed = parseHeader(header);
            Arrays.fill(header, (byte) 0);
            Arrays.fill(parsed.fileId, (byte) 0);
            Arrays.fill(parsed.bindingHash, (byte) 0);
            Arrays.fill(parsed.wrappedKey, (byte) 0);
            return parsed.plaintextSize;
        }
    }

    public static final class Writer implements Closeable {
        private final RandomAccessFile file;
        private final byte[] key;
        private final byte[] fileId;
        private final byte[] headerHash;
        private final long plaintextSize;
        private final int chunkSize;
        private final long chunkCount;
        private final BitSet written = new BitSet();
        private boolean closed;

        private Writer(File target, long plaintextSize, int chunkSize, String bindingName) throws IOException {
            validateShape(plaintextSize, chunkSize);
            this.plaintextSize = plaintextSize;
            this.chunkSize = chunkSize;
            this.chunkCount = chunkCount(plaintextSize, chunkSize);
            this.key = WispyrVault.randomBytes(MediaAead.KEY_SIZE);
            this.fileId = WispyrVault.randomBytes(16);
            byte[] header = buildHeader(plaintextSize, chunkSize, fileId, key, bindingName);
            this.headerHash = sha256(header);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("cannot create media directory");
            }
            file = new RandomAccessFile(target, "rws");
            file.setLength(0);
            file.write(header);
            file.setLength(containerLength(plaintextSize, chunkSize));
            Arrays.fill(header, (byte) 0);
        }

        public synchronized void writeChunk(long plaintextOffset, byte[] plaintext, int offset, int length) throws IOException {
            ensureOpen();
            if (plaintextOffset < 0 || plaintextOffset % chunkSize != 0) {
                throw new IOException("unaligned media chunk");
            }
            long index = plaintextOffset / chunkSize;
            if (index < 0 || index >= chunkCount || index > 0xffffffffL) {
                throw new IOException("media chunk index out of range");
            }
            if (written.get((int) index)) {
                throw new IOException("media chunk was already written");
            }
            int expected = plaintextLength(index, plaintextSize, chunkSize);
            if (length != expected || offset < 0 || offset + length > plaintext.length) {
                throw new IOException("invalid media chunk length");
            }
            byte[] plain = Arrays.copyOfRange(plaintext, offset, offset + length);
            byte[] ciphertext;
            try {
                ciphertext = MediaAead.seal(key, nonce(fileId, index), aad(headerHash, index, length), plain);
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
            if (ciphertext == null || ciphertext.length != length + MediaAead.TAG_SIZE) {
                throw new IOException("media chunk encryption failed");
            }
            file.seek(physicalOffset(index, chunkSize));
            file.write(ciphertext);
            Arrays.fill(ciphertext, (byte) 0);
            written.set((int) index);
        }

        public synchronized void finish() throws IOException {
            ensureOpen();
            if (written.cardinality() != chunkCount) {
                throw new IOException("media container has missing chunks");
            }
            file.getFD().sync();
        }

        public long getPlaintextSize() {
            return plaintextSize;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("media writer closed");
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            Arrays.fill(key, (byte) 0);
            Arrays.fill(fileId, (byte) 0);
            Arrays.fill(headerHash, (byte) 0);
            file.close();
        }
    }

    public static final class Reader implements Closeable {
        private RandomAccessFile file;
        private byte[] key;
        private byte[] fileId;
        private byte[] headerHash;
        private byte[] parsedBindingHash;
        private long plaintextSize;
        private int chunkSize;
        private long chunkCount;
        private long cachedIndex = -1;
        private byte[] cachedPlaintext;
        private boolean closed;

        private Reader(File source, String bindingName) throws IOException {
            file = new RandomAccessFile(source, "r");
            try {
                byte[] header = new byte[HEADER_SIZE];
                file.readFully(header);
                Header parsed = parseHeader(header);
                if (!MessageDigest.isEqual(parsed.bindingHash, bindingHash(bindingName))) {
                    throw new IOException("media file binding mismatch");
                }
                plaintextSize = parsed.plaintextSize;
                chunkSize = parsed.chunkSize;
                chunkCount = chunkCount(plaintextSize, chunkSize);
                long expectedLength = containerLength(plaintextSize, chunkSize);
                if (file.length() != expectedLength) {
                    throw new IOException("media container length mismatch");
                }
                fileId = parsed.fileId;
                parsedBindingHash = parsed.bindingHash;
                headerHash = sha256(header);
                try {
                    key = WispyrVault.unwrapMediaFileKey(
                            parsed.wrappedKey, Arrays.copyOf(header, METADATA_SIZE));
                } catch (GeneralSecurityException e) {
                    throw new IOException("media key authentication failed", e);
                } finally {
                    Arrays.fill(header, (byte) 0);
                    Arrays.fill(parsed.wrappedKey, (byte) 0);
                }
                if (key == null || key.length != MediaAead.KEY_SIZE) {
                    throw new IOException("invalid media key");
                }
            } catch (Throwable e) {
                closeFailedReader();
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }

        private void closeFailedReader() {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (fileId != null) Arrays.fill(fileId, (byte) 0);
            if (headerHash != null) Arrays.fill(headerHash, (byte) 0);
            if (parsedBindingHash != null) Arrays.fill(parsedBindingHash, (byte) 0);
            try {
                if (file != null) file.close();
            } catch (IOException ignore) {
            }
        }

        public long length() {
            return plaintextSize;
        }

        public synchronized int read(long position, byte[] output, int offset, int length) throws IOException {
            ensureOpen();
            if (position < 0 || position > plaintextSize || offset < 0 || length < 0 || offset + length > output.length) {
                throw new IOException("invalid media read");
            }
            if (position == plaintextSize) {
                return -1;
            }
            int total = 0;
            int wanted = (int) Math.min((long) length, plaintextSize - position);
            while (total < wanted) {
                long index = position / chunkSize;
                int inChunk = (int) (position % chunkSize);
                byte[] chunk = loadChunk(index);
                int copy = Math.min(wanted - total, chunk.length - inChunk);
                System.arraycopy(chunk, inChunk, output, offset + total, copy);
                total += copy;
                position += copy;
            }
            return total;
        }

        public InputStream inputStream() {
            return new InputStream() {
                private long position;

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int result = read(one, 0, 1);
                    return result == -1 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int result = Reader.this.read(position, b, off, len);
                    if (result > 0) {
                        position += result;
                    }
                    return result;
                }

                @Override
                public long skip(long n) {
                    long value = Math.max(0, Math.min(n, plaintextSize - position));
                    position += value;
                    return value;
                }
            };
        }

        public synchronized void verifyAll() throws IOException {
            ensureOpen();
            for (long index = 0; index < chunkCount; index++) {
                loadChunk(index);
            }
        }

        private byte[] loadChunk(long index) throws IOException {
            if (cachedIndex == index && cachedPlaintext != null) {
                return cachedPlaintext;
            }
            if (index < 0 || index >= chunkCount || index > 0xffffffffL) {
                throw new EOFException();
            }
            int plainLength = plaintextLength(index, plaintextSize, chunkSize);
            byte[] ciphertext = new byte[plainLength + MediaAead.TAG_SIZE];
            file.seek(physicalOffset(index, chunkSize));
            file.readFully(ciphertext);
            byte[] plaintext = MediaAead.open(key, nonce(fileId, index), aad(headerHash, index, plainLength), ciphertext);
            Arrays.fill(ciphertext, (byte) 0);
            if (plaintext == null || plaintext.length != plainLength) {
                throw new IOException("media chunk authentication failed");
            }
            if (cachedPlaintext != null) {
                Arrays.fill(cachedPlaintext, (byte) 0);
            }
            cachedPlaintext = plaintext;
            cachedIndex = index;
            return plaintext;
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("media reader closed");
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            Arrays.fill(key, (byte) 0);
            Arrays.fill(fileId, (byte) 0);
            Arrays.fill(headerHash, (byte) 0);
            Arrays.fill(parsedBindingHash, (byte) 0);
            if (cachedPlaintext != null) {
                Arrays.fill(cachedPlaintext, (byte) 0);
                cachedPlaintext = null;
            }
            file.close();
        }
    }

    private static final class Header {
        long plaintextSize;
        int chunkSize;
        byte[] fileId;
        byte[] bindingHash;
        byte[] wrappedKey;
    }

    private static byte[] buildHeader(long plaintextSize, int chunkSize, byte[] fileId,
                                      byte[] fileKey, String bindingName) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        buffer.put(MAGIC);
        buffer.put((byte) VERSION);
        buffer.put((byte) ALGORITHM_AES_256_GCM_SIV);
        buffer.putShort((short) HEADER_SIZE);
        buffer.putInt(chunkSize);
        buffer.putLong(plaintextSize);
        buffer.put(fileId);
        buffer.put(bindingHash(bindingName));
        byte[] metadata = Arrays.copyOf(header, METADATA_SIZE);
        byte[] wrapped;
        try {
            wrapped = WispyrVault.wrapMediaFileKey(fileKey, metadata);
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot wrap media key", e);
        } finally {
            Arrays.fill(metadata, (byte) 0);
        }
        if (wrapped.length > HEADER_SIZE - WRAPPED_KEY_OFFSET) {
            Arrays.fill(wrapped, (byte) 0);
            throw new IOException("wrapped media key too large");
        }
        buffer.position(WRAPPED_LENGTH_OFFSET);
        buffer.putShort((short) wrapped.length);
        buffer.put(wrapped);
        Arrays.fill(wrapped, (byte) 0);
        return header;
    }

    private static Header parseHeader(byte[] header) throws IOException {
        if (header.length != HEADER_SIZE) {
            throw new IOException("bad media header size");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!Arrays.equals(magic, MAGIC)
                || (buffer.get() & 0xff) != VERSION
                || (buffer.get() & 0xff) != ALGORITHM_AES_256_GCM_SIV
                || (buffer.getShort() & 0xffff) != HEADER_SIZE) {
            throw new IOException("unsupported media container");
        }
        Header result = new Header();
        result.chunkSize = buffer.getInt();
        result.plaintextSize = buffer.getLong();
        result.fileId = new byte[16];
        buffer.get(result.fileId);
        result.bindingHash = new byte[16];
        buffer.get(result.bindingHash);
        validateShape(result.plaintextSize, result.chunkSize);
        buffer.position(WRAPPED_LENGTH_OFFSET);
        int wrappedLength = buffer.getShort() & 0xffff;
        if (wrappedLength < MediaAead.KEY_SIZE || wrappedLength > HEADER_SIZE - WRAPPED_KEY_OFFSET) {
            throw new IOException("invalid wrapped media key");
        }
        result.wrappedKey = new byte[wrappedLength];
        buffer.get(result.wrappedKey);
        return result;
    }

    private static void validateShape(long plaintextSize, int chunkSize) throws IOException {
        if (plaintextSize < 0 || chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE
                || chunkSize % 16 != 0) {
            throw new IOException("invalid media container shape");
        }
        long chunks = chunkCount(plaintextSize, chunkSize);
        if (chunks > Integer.MAX_VALUE) {
            throw new IOException("media has too many chunks");
        }
        containerLength(plaintextSize, chunkSize);
    }

    private static long chunkCount(long plaintextSize, int chunkSize) {
        if (plaintextSize == 0) {
            return 0;
        }
        return plaintextSize / chunkSize + (plaintextSize % chunkSize == 0 ? 0 : 1);
    }

    private static int plaintextLength(long index, long plaintextSize, int chunkSize) {
        long remaining = plaintextSize - index * (long) chunkSize;
        return (int) Math.min(chunkSize, remaining);
    }

    private static long physicalOffset(long index, int chunkSize) throws IOException {
        try {
            return Math.addExact(HEADER_SIZE, Math.multiplyExact(index, (long) chunkSize + MediaAead.TAG_SIZE));
        } catch (ArithmeticException e) {
            throw new IOException("media offset overflow", e);
        }
    }

    private static long containerLength(long plaintextSize, int chunkSize) throws IOException {
        try {
            return Math.addExact(HEADER_SIZE,
                    Math.addExact(plaintextSize, Math.multiplyExact(chunkCount(plaintextSize, chunkSize), MediaAead.TAG_SIZE)));
        } catch (ArithmeticException e) {
            throw new IOException("media length overflow", e);
        }
    }

    private static byte[] nonce(byte[] fileId, long chunkIndex) throws IOException {
        if (chunkIndex < 0 || chunkIndex > 0xffffffffL) {
            throw new IOException("media nonce overflow");
        }
        ByteBuffer nonce = ByteBuffer.allocate(MediaAead.NONCE_SIZE).order(ByteOrder.BIG_ENDIAN);
        nonce.put(fileId, 0, 8);
        nonce.putInt((int) chunkIndex);
        return nonce.array();
    }

    private static byte[] aad(byte[] headerHash, long chunkIndex, int plaintextLength) {
        return ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN)
                .put(headerHash)
                .putLong(chunkIndex)
                .putInt(plaintextLength)
                .array();
    }

    private static byte[] sha256(byte[] value) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static byte[] bindingHash(String bindingName) throws IOException {
        if (bindingName == null || bindingName.isEmpty()) {
            throw new IOException("missing media binding");
        }
        return Arrays.copyOf(sha256(bindingName.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 16);
    }
}
