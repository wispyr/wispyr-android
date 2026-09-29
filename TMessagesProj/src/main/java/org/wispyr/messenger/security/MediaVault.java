package org.wispyr.messenger.security;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.wispyr.messenger.ApplicationLoader;
import org.wispyr.messenger.FileLog;
import org.wispyr.messenger.FileLoader;
import org.wispyr.messenger.UserConfig;
import org.wispyr.messenger.Utilities;
import org.wispyr.messenger.utils.BitmapsCache;
import org.wispyr.messenger.secretmedia.EncryptedFileInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * WYM2 AES-256-GCM-SIV media storage. Legacy AES-CTR + .key files are accepted only for migration.
 */
public final class MediaVault {
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final Object MIGRATION_LOCK = new Object();
    // Background migration skips files this recent: they may still be written or open. The lock-time
    // pass (encryptAllBeforeLock) converts them too.
    private static final long MIGRATION_STABLE_AGE_MS = 5 * 60_000L;

    private MediaVault() {}

    public static File keyFile(File encryptedFile) {
        return new File(FileLoader.getInternalCacheDir(), encryptedFile.getName() + ".key");
    }

    public static File encryptPlainFile(File plainFile) throws IOException {
        if (plainFile == null || !plainFile.isFile()) {
            throw new IOException("plain media file is missing");
        }
        if (plainFile.getName().endsWith(".enc")) {
            return plainFile;
        }
        File encrypted = new File(plainFile.getPath() + ".enc");
        encryptPlainFileTo(plainFile, encrypted);
        return encrypted;
    }

    public static void writeEncryptedBytes(byte[] plaintext, File encrypted) throws IOException {
        if (plaintext == null || encrypted == null) {
            throw new IOException("missing media bytes");
        }
        File temporary = new File(encrypted.getPath() + ".tmp");
        try (MediaContainer.Writer writer = MediaContainer.createWriter(
                temporary, plaintext.length, MediaContainer.DEFAULT_CHUNK_SIZE, encrypted.getName())) {
            int offset = 0;
            while (offset < plaintext.length) {
                int count = Math.min(MediaContainer.DEFAULT_CHUNK_SIZE, plaintext.length - offset);
                writer.writeChunk(offset, plaintext, offset, count);
                offset += count;
            }
            writer.finish();
        } catch (Exception e) {
            temporary.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        if (encrypted.exists() && !encrypted.delete()) {
            temporary.delete();
            throw new IOException("cannot replace encrypted media bytes");
        }
        if (!temporary.renameTo(encrypted)) {
            temporary.delete();
            throw new IOException("cannot publish encrypted media bytes");
        }
    }

    public static void encryptPlainFileTo(File plainFile, File encrypted) throws IOException {
        if (plainFile == null || !plainFile.isFile() || encrypted == null) {
            throw new IOException("plain media file is missing");
        }
        File temporary = new File(encrypted.getPath() + ".tmp");
        writePlainContainer(plainFile, temporary, encrypted.getName());
        try {
            if (encrypted.exists() && !encrypted.delete()) {
                temporary.delete();
                throw new IOException("cannot replace encrypted media");
            }
            if (!temporary.renameTo(encrypted)) {
                temporary.delete();
                throw new IOException("cannot publish encrypted media");
            }
            if (!plainFile.delete()) {
                encrypted.delete();
                throw new IOException("cannot remove plaintext media");
            }
        } finally {
            temporary.delete();
        }
    }

    public static void decryptTo(File encryptedFile, File destination) throws IOException {
        if (MediaContainer.isContainer(encryptedFile)) {
            File temporary = new File(destination.getPath() + ".tmp");
            try {
                try (MediaContainer.Reader reader = MediaContainer.openReader(encryptedFile);
                     InputStream input = reader.inputStream();
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    try {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                        output.getFD().sync();
                    } finally {
                        Arrays.fill(buffer, (byte) 0);
                    }
                }
                if (destination.exists() && !destination.delete()) {
                    throw new IOException("cannot replace decrypted copy");
                }
                if (!temporary.renameTo(destination)) {
                    throw new IOException("cannot publish decrypted copy");
                }
            } catch (Exception e) {
                temporary.delete();
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
            return;
        }
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        try {
            if (!WispyrVault.readMediaKey(keyFile(encryptedFile), key, iv)) {
                throw new IOException("legacy media key unavailable");
            }
            File temporary = new File(destination.getPath() + ".tmp");
            transformLegacyCtr(encryptedFile, temporary, key, iv);
            if (destination.exists() && !destination.delete()) {
                temporary.delete();
                throw new IOException("cannot replace decrypted copy");
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete();
                throw new IOException("cannot publish decrypted copy");
            }
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public static void convertLegacyCtr(File source, File legacyKeyFile, File destination,
                                        String bindingName) throws IOException {
        try (EncryptedFileInputStream input = new EncryptedFileInputStream(source, legacyKeyFile);
             MediaContainer.Writer writer = MediaContainer.createWriter(
                     destination, source.length(), MediaContainer.DEFAULT_CHUNK_SIZE, bindingName)) {
            byte[] buffer = new byte[MediaContainer.DEFAULT_CHUNK_SIZE];
            long offset = 0;
            int read;
            while ((read = readChunk(input, buffer)) > 0) {
                writer.writeChunk(offset, buffer, 0, read);
                offset += read;
            }
            writer.finish();
            Arrays.fill(buffer, (byte) 0);
        } catch (Exception e) {
            destination.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    public static InputStream openInputStream(File file) throws IOException {
        if (file != null && file.getName().endsWith(".enc")) {
            if (MediaContainer.isContainer(file)) {
                MediaContainer.Reader reader = MediaContainer.openReader(file);
                InputStream input = reader.inputStream();
                return new InputStream() {
                    @Override public int read() throws IOException { return input.read(); }
                    @Override public int read(byte[] b, int off, int len) throws IOException { return input.read(b, off, len); }
                    @Override public long skip(long n) throws IOException { return input.skip(n); }
                    @Override public void close() throws IOException {
                        try {
                            input.close();
                        } finally {
                            reader.close();
                        }
                    }
                };
            }
            try {
                return new EncryptedFileInputStream(file, keyFile(file));
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        return new FileInputStream(file);
    }

    public static Bitmap decodeBitmap(File file, BitmapFactory.Options options) {
        if (file == null) {
            return null;
        }
        if (!file.getName().endsWith(".enc")) {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        }
        try (InputStream input = openInputStream(file)) {
            return BitmapFactory.decodeStream(input, null, options);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Plain copy for APIs that only accept a filesystem path. The caller must delete it promptly. */
    public static File materializeTemporary(File encryptedFile) throws IOException {
        if (encryptedFile == null || !encryptedFile.getName().endsWith(".enc")) {
            return encryptedFile;
        }
        File dir = decryptedTempDir();
        String originalName = encryptedFile.getName().substring(0, encryptedFile.getName().length() - 4);
        File result = new File(dir, UUID.randomUUID() + "_" + originalName);
        decryptTo(encryptedFile, result);
        return result;
    }

    /**
     * Media is encrypted from the first launch: the data key exists before the passcode is chosen (it is
     * only re-wrapped by the passcode), so nothing downloaded between login and passcode setup stays plain.
     */
    public static boolean protectMediaAtRest() {
        return !WispyrVault.isLocked();
    }

    // ---- camera placeholders (blurred last camera frame shown while a camera starts) ----

    /** One slot per camera surface. The frames show the user's face and are only ever stored encrypted. */
    public enum CameraThumb {
        ROUND_VIDEO("icthumb.jpg"),
        CALL_VIDEO("voip_icthumb.jpg"),
        CAMERA("cthumb.jpg"),
        CALL_PREVIEW_PAGE_1("cthumb1.jpg"),
        CALL_PREVIEW_PAGE_2("cthumb2.jpg");

        /** Name older builds used in the files directory (plain JPEG, or "<name>.enc" briefly). */
        private final String legacyFileName;

        CameraThumb(String legacyFileName) {
            this.legacyFileName = legacyFileName;
        }

        /** Camera pages of the video-call preview dialogs (page 0 is screen sharing and has no frame). */
        public static CameraThumb forCallPreviewPage(int page) {
            return page == 1 ? CALL_PREVIEW_PAGE_1 : page == 2 ? CALL_PREVIEW_PAGE_2 : null;
        }

        private File file() {
            return new File(WispyrVault.getSecureDir("camera_thumbs"), name().toLowerCase(Locale.US) + ".enc");
        }
    }

    public static void saveCameraThumb(Bitmap bitmap, CameraThumb thumb, int quality) {
        if (bitmap == null || thumb == null || WispyrVault.isLocked()) {
            return;
        }
        java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);
        byte[] jpeg = stream.toByteArray();
        try {
            writeEncryptedBytes(jpeg, thumb.file());
        } catch (IOException e) {
            FileLog.e(e);
        } finally {
            Arrays.fill(jpeg, (byte) 0);
        }
    }

    public static Bitmap loadCameraThumb(CameraThumb thumb) {
        if (thumb == null || WispyrVault.isLocked()) {
            return null;
        }
        File file = thumb.file();
        return file.exists() ? decodeBitmap(file, null) : null;
    }

    private static void deleteLegacyCameraThumbs() {
        File filesDir = ApplicationLoader.getFilesDirFixed();
        for (CameraThumb thumb : CameraThumb.values()) {
            new File(filesDir, thumb.legacyFileName).delete();
            new File(filesDir, thumb.legacyFileName + ".enc").delete();
        }
    }

    // ---- rendered animation frame cache (BitmapsCache) ----

    // Frames are only a rendering cache: they are sealed with a key that never leaves this process and the
    // directory is wiped on start, so earlier caches cannot be decrypted by anyone, including this app.
    private static final byte[] frameCacheKey = WispyrVault.randomBytes(32);
    private static final byte[] FRAME_AAD = {'W', 'Y', 'F', 'C'};

    public static byte[] sealCacheFrame(byte[] data, int offset, int length) throws IOException {
        byte[] plain = Arrays.copyOfRange(data, offset, offset + length);
        try {
            return WispyrVault.seal(frameCacheKey, plain, FRAME_AAD);
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public static byte[] openCacheFrame(byte[] data, int offset, int length) throws IOException {
        try {
            return WispyrVault.open(frameCacheKey, data, offset, length, FRAME_AAD);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** Called once per process before the first frame cache is used: older caches are undecryptable. */
    public static void resetFrameCacheDir(File dir) {
        deleteRecursively(dir);
        dir.mkdirs();
    }

    private static boolean isFrameCacheDir(File dir) {
        File cache = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_CACHE);
        return cache != null && dir.equals(new File(cache, BitmapsCache.CACHE_DIR_NAME));
    }

    public static boolean clearDecryptedTemps() {
        deleteLegacyCameraThumbs();
        File directory = decryptedTempDir();
        boolean deleted = deleteRecursively(directory);
        boolean created = directory.exists() || directory.mkdirs();
        File[] remaining = directory.listFiles();
        return deleted && created && (remaining == null || remaining.length == 0);
    }

    public static void migrateLegacyMediaAsync() {
        if (!protectMediaAtRest()) {
            return;
        }
        new Thread(() -> {
            synchronized (MIGRATION_LOCK) {
                try {
                    recoverMigration();
                    migrateDirectory(new File(ApplicationLoader.getFilesDirFixed(), "Wispyr"), true, false);
                    migrateDirectory(ApplicationLoader.applicationContext.getCacheDir(), false, false);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }, "MediaVaultMigration").start();
    }

    /** Converts every plaintext media file, including just-written ones (used right after the passcode is set). */
    public static void encryptAllAsync() {
        new Thread(MediaVault::encryptAllBeforeLock, "MediaVaultMigration").start();
    }

    public static boolean encryptAllBeforeLock() {
        if (!WispyrVault.isPasscodeProtected() || WispyrVault.isLocked()) {
            return WispyrVault.isLocked();
        }
        synchronized (MIGRATION_LOCK) {
            recoverMigration();
            migrateDirectory(new File(ApplicationLoader.getFilesDirFixed(), "Wispyr"), true, true);
            migrateDirectory(ApplicationLoader.applicationContext.getCacheDir(), false, true);
            return !migrationJournal().exists()
                    && !hasPlaintextMedia(new File(ApplicationLoader.getFilesDirFixed(), "Wispyr"), true)
                    && !hasPlaintextMedia(ApplicationLoader.applicationContext.getCacheDir(), false);
        }
    }

    private static boolean hasPlaintextMedia(File dir, boolean allFilesAreMedia) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.equals(decryptedTempDir()) && hasPlaintextMedia(file, allFilesAreMedia)) {
                    return true;
                }
                continue;
            }
            String name = file.getName();
            if (name.equals(".nomedia") || name.endsWith(".enc") || name.endsWith(".key")
                    || name.endsWith(".wym2.part") || name.endsWith(".ctr.bak")) {
                continue;
            }
            if (allFilesAreMedia || looksLikeMedia(name)) {
                return true;
            }
        }
        return false;
    }

    private static void migrateDirectory(File dir, boolean allFilesAreMedia, boolean includeRecent) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return;
        }
        long stableBefore = System.currentTimeMillis() - MIGRATION_STABLE_AGE_MS;
        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.equals(decryptedTempDir()) && !isFrameCacheDir(file)) {
                    migrateDirectory(file, allFilesAreMedia, includeRecent);
                }
                continue;
            }
            if ((!includeRecent && file.lastModified() > stableBefore)
                    || file.getName().equals(".nomedia")
                    || file.getName().endsWith(".wym2.part")
                    || file.getName().endsWith(".ctr.bak")
                    || file.getName().endsWith(".key")) {
                continue;
            }
            if (file.getName().endsWith(".enc")) {
                if (MediaContainer.isContainer(file)) {
                    continue;
                }
                if (keyFile(file).exists()) {
                    migrateLegacyCtrFile(file);
                    if (migrationJournal().exists()) {
                        return;
                    }
                } else if (!allFilesAreMedia) {
                    // Unsupported/partial cache container without a legacy key is safe to re-download.
                    file.delete();
                }
                continue;
            }
            if (!allFilesAreMedia && !looksLikeMedia(file.getName())) {
                // Unknown cache entries are disposable. Deleting is safer than leaving an unclassified
                // plaintext artifact that may contain a thumbnail, draft or transcoding fragment.
                file.delete();
                continue;
            }
            migratePlainFile(file);
            if (migrationJournal().exists()) {
                return;
            }
        }
    }

    private static boolean looksLikeMedia(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".mp4")
                || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".ogg")
                || lower.endsWith(".opus") || lower.endsWith(".mp3") || lower.endsWith(".m4a")
                || lower.endsWith(".wav") || lower.endsWith(".tgs") || lower.endsWith(".pdf");
    }

    private static File decryptedTempDir() {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "wispyr_decrypted");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        boolean success = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    success &= deleteRecursively(child);
                }
            }
        }
        return file.delete() && success;
    }

    private static void writePlainContainer(File source, File destination, String bindingName) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             MediaContainer.Writer writer = MediaContainer.createWriter(
                     destination, source.length(), MediaContainer.DEFAULT_CHUNK_SIZE, bindingName)) {
            byte[] buffer = new byte[MediaContainer.DEFAULT_CHUNK_SIZE];
            try {
                long offset = 0;
                int read;
                while ((read = readChunk(input, buffer)) > 0) {
                    writer.writeChunk(offset, buffer, 0, read);
                    offset += read;
                }
                writer.finish();
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
        } catch (Exception e) {
            destination.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private static void migratePlainFile(File source) {
        File destination = new File(source.getPath() + ".enc");
        File temporary = new File(destination.getPath() + ".wym2.part");
        File backup = new File(source.getPath() + ".ctr.bak");
        try {
            writeJournal(source, destination, temporary, backup, null);
            writePlainContainer(source, temporary, destination.getName());
            verifyContainer(temporary, destination.getName());
            promoteMigration(source, destination, temporary, backup, null);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void migrateLegacyCtrFile(File source) {
        File temporary = new File(source.getPath() + ".wym2.part");
        File backup = new File(source.getPath() + ".ctr.bak");
        File legacyKey = keyFile(source);
        try {
            writeJournal(source, source, temporary, backup, legacyKey);
            convertLegacyCtr(source, legacyKey, temporary, source.getName());
            verifyContainer(temporary, source.getName());
            promoteMigration(source, source, temporary, backup, legacyKey);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void verifyContainer(File file, String bindingName) throws IOException {
        try (MediaContainer.Reader reader = MediaContainer.openReader(file, bindingName)) {
            reader.verifyAll();
        }
    }

    private static void promoteMigration(File source, File destination, File temporary,
                                         File backup, File legacyKey) throws IOException {
        if (destination.equals(source)) {
            if (backup.exists()) {
                backup.delete();
            }
            if (!source.renameTo(backup)) {
                throw new IOException("cannot preserve legacy media");
            }
        }
        if (destination.exists() && !destination.equals(source) && !destination.delete()) {
            throw new IOException("cannot replace migrated media");
        }
        if (!temporary.renameTo(destination)) {
            if (backup.exists()) {
                backup.renameTo(source);
            }
            throw new IOException("cannot publish migrated media");
        }
        if (!destination.equals(source)) {
            if (!updatePathDatabases(source, destination)) {
                throw new IOException("cannot update media path database");
            }
            if (source.exists() && !source.delete()) {
                throw new IOException("cannot delete plaintext media");
            }
        }
        backup.delete();
        if (legacyKey != null) {
            legacyKey.delete();
        }
        migrationJournal().delete();
    }

    private static void recoverMigration() {
        File journal = migrationJournal();
        if (!journal.exists()) {
            return;
        }
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(journal)) {
            properties.load(input);
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        File source = propertyFile(properties, "source");
        File destination = propertyFile(properties, "destination");
        File temporary = propertyFile(properties, "temporary");
        File backup = propertyFile(properties, "backup");
        File legacyKey = propertyFile(properties, "legacyKey");
        if (source == null || destination == null || temporary == null || backup == null) {
            return;
        }
        try {
            if (destination.exists() && MediaContainer.isContainer(destination)) {
                verifyContainer(destination, destination.getName());
                if (!destination.equals(source)) {
                    if (!updatePathDatabases(source, destination)) {
                        return;
                    }
                    source.delete();
                }
                backup.delete();
                if (legacyKey != null) {
                    legacyKey.delete();
                }
                temporary.delete();
                journal.delete();
                return;
            }
            if (temporary.exists()) {
                verifyContainer(temporary, destination.getName());
                if (!source.exists() && backup.exists()) {
                    // Source was already moved in the previous attempt.
                    if (!temporary.renameTo(destination)) {
                        throw new IOException("cannot recover migrated media");
                    }
                    if (!destination.equals(source) && !updatePathDatabases(source, destination)) {
                        return;
                    }
                    backup.delete();
                    if (legacyKey != null) {
                        legacyKey.delete();
                    }
                    journal.delete();
                    return;
                }
                promoteMigration(source, destination, temporary, backup, legacyKey);
                return;
            }
            if (!source.exists() && backup.exists()) {
                backup.renameTo(source);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        // Preserve both journal and artifacts for the next recovery attempt.
    }

    private static File propertyFile(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null || value.isEmpty() ? null : new File(value);
    }

    private static void writeJournal(File source, File destination, File temporary,
                                     File backup, File legacyKey) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("source", source.getAbsolutePath());
        properties.setProperty("destination", destination.getAbsolutePath());
        properties.setProperty("temporary", temporary.getAbsolutePath());
        properties.setProperty("backup", backup.getAbsolutePath());
        properties.setProperty("legacyKey", legacyKey == null ? "" : legacyKey.getAbsolutePath());
        File journal = migrationJournal();
        File temp = new File(journal.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            properties.store(output, "Wispyr media migration");
            output.getFD().sync();
        }
        if (journal.exists()) {
            temp.delete();
            throw new IOException("unfinished media migration already exists");
        }
        if (!temp.renameTo(journal)) {
            temp.delete();
            throw new IOException("cannot publish media migration journal");
        }
    }

    private static File migrationJournal() {
        return new File(WispyrVault.getSecureDir("vault"), "media_migration.journal");
    }

    private static boolean updatePathDatabases(File source, File destination) {
        boolean success = true;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                success &= FileLoader.getInstance(account).getFileDatabase().replacePath(
                        source.getAbsolutePath(), destination.getAbsolutePath());
            } catch (Throwable e) {
                FileLog.e(e);
                success = false;
            }
        }
        return success;
    }

    private static int readChunk(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read == -1) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static void transformLegacyCtr(File source, File destination, byte[] key, byte[] iv) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long offset = 0;
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                Utilities.aesCtrDecryptionByteArray(buffer, key, iv, 0, read, offset);
                output.write(buffer, 0, read);
                offset += read;
            }
            output.getFD().sync();
        } catch (Exception e) {
            destination.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }
}
