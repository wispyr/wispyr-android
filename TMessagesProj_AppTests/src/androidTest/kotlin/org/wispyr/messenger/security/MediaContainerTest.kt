package org.wispyr.messenger.security

import androidx.test.core.app.ApplicationProvider
import androidx.media3.datasource.DataSpec
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Properties
import org.wispyr.messenger.Utilities
import org.wispyr.messenger.FileLoader
import org.wispyr.messenger.FilePathDatabase
import org.wispyr.messenger.FileLoadOperation
import org.wispyr.messenger.secretmedia.EncryptedFileDataSource

class MediaContainerTest {
    private val cache = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir

    @Test
    fun roundTripAndRandomSeek() {
        val sizes = intArrayOf(0, 1, 16383, 16384, 16385, 262143, 262144, 262145, 700000)
        for (size in sizes) {
            val plain = ByteArray(size)
            SecureRandom().nextBytes(plain)
            val file = File(cache, "wym2_$size.bin")
            write(file, plain, 64 * 1024)
            MediaContainer.openReader(file).use { reader ->
                assertEquals(size.toLong(), reader.length())
                val all = ByteArray(size)
                var offset = 0
                while (offset < all.size) {
                    val count = reader.read(offset.toLong(), all, offset, minOf(7777, all.size - offset))
                    offset += count
                }
                assertArrayEquals(plain, all)
                if (size > 100) {
                    val slice = ByteArray(73)
                    val start = size / 2 - 20
                    assertEquals(slice.size, reader.read(start.toLong(), slice, 0, slice.size))
                    assertArrayEquals(plain.copyOfRange(start, start + slice.size), slice)
                }
            }
            file.delete()
        }
    }

    @Test
    fun detectsHeaderCiphertextTagTruncationAndChunkSwap() {
        val plain = ByteArray(180_000)
        SecureRandom().nextBytes(plain)
        val original = File(cache, "wym2_original.bin")
        write(original, plain, 64 * 1024)

        tampered(original, "header", 10)
        tampered(original, "wrapped_key", 50)
        tampered(original, "ciphertext", MediaContainer.HEADER_SIZE + 100)
        tampered(original, "tag", MediaContainer.HEADER_SIZE + 64 * 1024)

        val truncated = File(cache, "wym2_truncated.bin")
        original.copyTo(truncated, overwrite = true)
        RandomAccessFile(truncated, "rw").use { it.setLength(it.length() - 1) }
        assertThrows(IOException::class.java) { MediaContainer.openReader(truncated).use { it.read(0, ByteArray(1), 0, 1) } }

        val swapped = File(cache, "wym2_swapped.bin")
        original.copyTo(swapped, overwrite = true)
        RandomAccessFile(swapped, "rw").use { raf ->
            val slot = 64 * 1024 + 16
            val a = ByteArray(slot)
            val b = ByteArray(slot)
            raf.seek(MediaContainer.HEADER_SIZE.toLong())
            raf.readFully(a)
            raf.readFully(b)
            raf.seek(MediaContainer.HEADER_SIZE.toLong())
            raf.write(b)
            raf.write(a)
        }
        assertThrows(IOException::class.java) { MediaContainer.openReader(swapped).use { it.read(0, ByteArray(1), 0, 1) } }

        listOf(original, truncated, swapped).forEach(File::delete)
    }

    @Test
    fun migratesPlainAndLegacyCtrWithoutLeavingKeys() {
        val methodPlain = MediaVault::class.java.getDeclaredMethod("migratePlainFile", File::class.java).apply { isAccessible = true }
        val plain = File(cache, "legacy_plain.jpg")
        val value = ByteArray(90_000).also { SecureRandom().nextBytes(it) }
        plain.writeBytes(value)
        val documentId = 9_876_543_210L
        val dcId = 4
        val type = FileLoader.MEDIA_DIR_IMAGE
        val paths = FileLoader.getInstance(0).fileDatabase
        paths.putPath(documentId, dcId, type, FilePathDatabase.FLAG_LOCALLY_CREATED, plain.absolutePath)
        assertEquals(plain.absolutePath, paths.getPath(documentId, dcId, type, true))
        methodPlain.invoke(null, plain)
        val migratedPlain = File(plain.path + ".enc")
        assertArrayEquals(value, readAll(migratedPlain))
        assertEquals(false, plain.exists())
        assertEquals(migratedPlain.absolutePath, paths.getPath(documentId, dcId, type, true))

        val methodCtr = MediaVault::class.java.getDeclaredMethod("migrateLegacyCtrFile", File::class.java).apply { isAccessible = true }
        val legacy = File(cache, "legacy_ctr.jpg.enc")
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val ciphertext = value.clone()
        Utilities.aesCtrDecryptionByteArray(ciphertext, key, iv, 0, ciphertext.size.toLong(), 0L)
        legacy.writeBytes(ciphertext)
        WispyrVault.writeMediaKey(MediaVault.keyFile(legacy), key, iv)
        methodCtr.invoke(null, legacy)
        assertArrayEquals(value, readAll(legacy))
        assertEquals(false, MediaVault.keyFile(legacy).exists())

        listOf(migratedPlain, legacy).forEach(File::delete)
    }

    @Test
    fun recoversPublishedPartAfterCrash() {
        val source = File(cache, "crash_source.jpg")
        val destination = File(cache, "crash_source.jpg.enc")
        val temporary = File(destination.path + ".wym2.part")
        val backup = File(source.path + ".ctr.bak")
        listOf(source, destination, temporary, backup).forEach { it.delete() }
        val value = ByteArray(50_000).also { SecureRandom().nextBytes(it) }
        source.writeBytes(value)
        val documentId = 9_876_543_211L
        val paths = FileLoader.getInstance(0).fileDatabase
        paths.putPath(
            documentId, 4, FileLoader.MEDIA_DIR_IMAGE,
            FilePathDatabase.FLAG_LOCALLY_CREATED, source.absolutePath)
        assertEquals(
            source.absolutePath,
            paths.getPath(documentId, 4, FileLoader.MEDIA_DIR_IMAGE, true))
        write(temporary, value, 64 * 1024, destination.name)
        source.renameTo(backup)

        val journal = File(WispyrVault.getSecureDir("vault"), "media_migration.journal")
        Properties().apply {
            setProperty("source", source.absolutePath)
            setProperty("destination", destination.absolutePath)
            setProperty("temporary", temporary.absolutePath)
            setProperty("backup", backup.absolutePath)
            setProperty("legacyKey", "")
            journal.outputStream().use { store(it, "test") }
        }
        MediaVault::class.java.getDeclaredMethod("recoverMigration").apply { isAccessible = true }.invoke(null)

        assertArrayEquals(value, readAll(destination))
        assertEquals(false, source.exists())
        assertEquals(false, backup.exists())
        assertEquals(false, journal.exists())
        assertEquals(
            destination.absolutePath,
            paths.getPath(documentId, 4, FileLoader.MEDIA_DIR_IMAGE, true))
        destination.delete()
    }

    @Test
    fun rejectsWrongKeyAndCrossFileChunk() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val aad = ByteArray(44).also { SecureRandom().nextBytes(it) }
        val plain = ByteArray(4096).also { SecureRandom().nextBytes(it) }
        val sealed = MediaAead.seal(key, nonce, aad, plain)
        assertEquals(null, MediaAead.open(wrongKey, nonce, aad, sealed))

        val first = File(cache, "wym2_cross_a.bin")
        val second = File(cache, "wym2_cross_b.bin")
        write(first, plain, 16 * 1024)
        write(second, plain, 16 * 1024)
        val renamed = File(cache, "wym2_cross_renamed.bin")
        second.copyTo(renamed, overwrite = true)
        assertThrows(IOException::class.java) {
            MediaContainer.openReader(renamed).use { it.read(0, ByteArray(1), 0, 1) }
        }
        RandomAccessFile(first, "rw").use { target ->
            RandomAccessFile(second, "r").use { source ->
                val chunk = ByteArray(plain.size + 16)
                source.seek(MediaContainer.HEADER_SIZE.toLong())
                source.readFully(chunk)
                target.seek(MediaContainer.HEADER_SIZE.toLong())
                target.write(chunk)
            }
        }
        assertThrows(IOException::class.java) {
            MediaContainer.openReader(first).use { it.read(0, ByteArray(1), 0, 1) }
        }
        first.delete()
        second.delete()
        renamed.delete()
    }

    @Test
    fun mediaVaultAndMedia3ReadAuthenticatedPlaintext() {
        val plain = File(cache, "vault_source.ogg")
        val value = ByteArray(400_000).also { SecureRandom().nextBytes(it) }
        plain.writeBytes(value)
        val encrypted = MediaVault.encryptPlainFile(plain)
        assertEquals(false, plain.exists())
        assertEquals(true, MediaContainer.isContainer(encrypted))
        MediaVault.openInputStream(encrypted).use { assertArrayEquals(value, it.readBytes()) }

        val start = 123_456
        val expected = value.copyOfRange(start, start + 50_000)
        val source = EncryptedFileDataSource()
        val length = source.open(
            DataSpec.Builder()
                .setUri(Uri.fromFile(encrypted))
                .setPosition(start.toLong())
                .setLength(expected.size.toLong())
                .build()
        )
        assertEquals(expected.size.toLong(), length)
        val actual = ByteArray(expected.size)
        var offset = 0
        while (offset < actual.size) {
            offset += source.read(actual, offset, actual.size - offset)
        }
        source.close()
        assertArrayEquals(expected, actual)
        encrypted.delete()
    }

    @Test
    fun physicalOffsetsRemainLongBeyondTwoGiB() {
        val method = MediaContainer::class.java.getDeclaredMethod(
            "physicalOffset",
            java.lang.Long.TYPE,
            Integer.TYPE
        ).apply { isAccessible = true }
        val index = 9_000L
        val offset = method.invoke(null, index, 256 * 1024) as Long
        val expected = MediaContainer.HEADER_SIZE.toLong() + index * (256 * 1024L + 16L)
        assertEquals(expected, offset)
        assertEquals(true, offset > Int.MAX_VALUE)
    }

    @Test
    fun randomizedSingleByteCorruptionAlwaysFailsAuthentication() {
        val plain = ByteArray(300_000).also { SecureRandom().nextBytes(it) }
        val original = File(cache, "wym2_fuzz_original.bin")
        write(original, plain, 64 * 1024)
        val random = SecureRandom()
        repeat(40) { iteration ->
            val file = File(cache, "wym2_fuzz_$iteration.bin")
            original.copyTo(file, overwrite = true)
            RandomAccessFile(file, "rw").use { raf ->
                val offset = random.nextLong().ushr(1) % raf.length()
                raf.seek(offset)
                val value = raf.read()
                raf.seek(offset)
                raf.write(value xor (1 shl random.nextInt(8)))
            }
            assertThrows(IOException::class.java) {
                MediaContainer.openReader(file).use { reader ->
                    reader.verifyAll()
                }
            }
            file.delete()
        }
        original.delete()
    }

    @Test
    fun supportsOutOfOrderChunksAndRejectsDuplicateOrMissingChunk() {
        val chunkSize = 32 * 1024
        val plain = ByteArray(chunkSize * 3 - 7).also { SecureRandom().nextBytes(it) }
        val file = File(cache, "wym2_out_of_order.bin")
        MediaContainer.createWriter(file, plain.size.toLong(), chunkSize).use { writer ->
            val lastOffset = chunkSize * 2
            writer.writeChunk(
                lastOffset.toLong(), plain, lastOffset, plain.size - lastOffset)
            writer.writeChunk(chunkSize.toLong(), plain, chunkSize, chunkSize)

            MediaContainer.openReader(file).use { partial ->
                val middle = ByteArray(1024)
                assertEquals(middle.size, partial.read(chunkSize.toLong(), middle, 0, middle.size))
                assertArrayEquals(plain.copyOfRange(chunkSize, chunkSize + middle.size), middle)
                assertThrows(IOException::class.java) {
                    partial.read(0, ByteArray(1), 0, 1)
                }
            }

            writer.writeChunk(0, plain, 0, chunkSize)
            assertThrows(IOException::class.java) {
                writer.writeChunk(0, plain, 0, chunkSize)
            }
            writer.finish()
        }
        assertArrayEquals(plain, readAll(file))
        file.delete()
    }

    @Test
    fun authenticationFailureDeletesPlaintextTemporaryFile() {
        val plain = ByteArray(100_000).also { SecureRandom().nextBytes(it) }
        val encrypted = File(cache, "wym2_cleanup.enc")
        write(encrypted, plain, 32 * 1024)
        RandomAccessFile(encrypted, "rw").use {
            it.seek(MediaContainer.HEADER_SIZE + 10L)
            val value = it.read()
            it.seek(MediaContainer.HEADER_SIZE + 10L)
            it.write(value xor 1)
        }
        val destination = File(cache, "wym2_cleanup_plain.bin")
        assertThrows(IOException::class.java) {
            MediaVault.decryptTo(encrypted, destination)
        }
        assertEquals(false, destination.exists())
        assertEquals(false, File(destination.path + ".tmp").exists())
        encrypted.delete()
    }

    @Test
    fun secretMediaPaddingDependsOnResponseOffsetNotArrivalOrder() {
        val method = FileLoadOperation::class.java.getDeclaredMethod(
            "getPlaintextResponseLength",
            java.lang.Long.TYPE,
            Integer.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE
        ).apply { isAccessible = true }
        val paddedTotal = 100_016L
        val padding = 16
        // Tail arrives first in an out-of-order download: padding must still be removed.
        assertEquals(16_368, method.invoke(null, 83_632L, 16_384, paddedTotal, padding.toLong()))
        // A middle response arriving last must not be trimmed.
        assertEquals(16_384, method.invoke(null, 32_768L, 16_384, paddedTotal, padding.toLong()))
    }

    private fun tampered(source: File, suffix: String, offset: Int) {
        val file = File(cache, "wym2_$suffix.bin")
        source.copyTo(file, overwrite = true)
        RandomAccessFile(file, "rw").use {
            it.seek(offset.toLong())
            val value = it.read()
            it.seek(offset.toLong())
            it.write(value xor 1)
        }
        assertThrows(IOException::class.java) {
            MediaContainer.openReader(file).use { it.read(0, ByteArray(1), 0, 1) }
        }
        file.delete()
    }

    private fun write(file: File, plain: ByteArray, chunkSize: Int) {
        write(file, plain, chunkSize, file.name)
    }

    private fun write(file: File, plain: ByteArray, chunkSize: Int, bindingName: String) {
        MediaContainer.createWriter(file, plain.size.toLong(), chunkSize, bindingName).use { writer ->
            var offset = 0
            while (offset < plain.size) {
                val count = minOf(chunkSize, plain.size - offset)
                writer.writeChunk(offset.toLong(), plain, offset, count)
                offset += count
            }
            writer.finish()
        }
    }

    private fun readAll(file: File): ByteArray {
        MediaContainer.openReader(file).use { reader ->
            val result = ByteArray(reader.length().toInt())
            var offset = 0
            while (offset < result.size) {
                offset += reader.read(offset.toLong(), result, offset, result.size - offset)
            }
            return result
        }
    }
}
