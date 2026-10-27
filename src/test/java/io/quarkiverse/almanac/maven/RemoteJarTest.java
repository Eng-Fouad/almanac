package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.ScratchDir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Zip reading over range requests, against zips built locally and an in-memory fetcher (no network).
class RemoteJarTest {

    private static final String STORED_MARKER = "STORED-CONTENT-MARKER";

    private ScratchDir dir;

    @BeforeEach
    void createDir() throws IOException {
        dir = ScratchDir.create();
    }

    @AfterEach
    void deleteDir() throws InterruptedException {
        dir.close();
    }

    /// [RangeFetcher] over a byte array that counts requests; a null array behaves like HTTP 404.
    private static final class BytesFetcher implements RangeFetcher {
        private final byte[] data;
        private int tails;
        private int ranges;
        private int alls;

        BytesFetcher(byte[] data) {
            this.data = data;
        }

        @Override
        public Optional<Tail> tail(int length) {
            tails++;
            if (data == null) {
                return Optional.empty();
            }
            return Optional.of(new Tail(Arrays.copyOfRange(data, Math.max(0, data.length - length), data.length),
                    data.length));
        }

        @Override
        public byte[] range(long offset, int length) {
            ranges++;
            return Arrays.copyOfRange(data, (int) offset, (int) offset + length);
        }

        @Override
        public byte[] all() {
            alls++;
            return data.clone();
        }

        int requests() {
            return tails + ranges + alls;
        }
    }

    @Test
    void wholeFileInTailIsCachedAsTheFullJar() throws IOException {
        Map<String, byte[]> entries = sampleEntries(3);
        byte[] zip = zip("small.jar", entries);
        BytesFetcher fetcher = new BytesFetcher(zip);
        Path cacheFile = dir.resolve("cache/small.jar");
        RemoteJar jar = jar(fetcher, cacheFile, RemoteJar.Options.DEFAULT);

        assertTrue(jar.exists());
        assertEquals(entries.keySet(), jar.entryNames());
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            assertArrayEquals(entry.getValue(), jar.read(entry.getKey()).orElseThrow(), entry.getKey());
        }
        assertEquals(Optional.empty(), jar.read("no/such/Entry.class"));
        assertEquals(1, fetcher.tails);
        assertEquals(1, fetcher.requests());
        assertArrayEquals(zip, Files.readAllBytes(cacheFile));

        BytesFetcher second = new BytesFetcher(zip);
        assertArrayEquals(entries.get("META-INF/stored.txt"),
                jar(second, cacheFile, RemoteJar.Options.DEFAULT).read("META-INF/stored.txt").orElseThrow());
        assertEquals(0, second.requests());
    }

    @Test
    void centralDirectoryOutsideTheTailIsFetchedThenEntriesAreReadByRange() throws IOException {
        Map<String, byte[]> entries = sampleEntries(40);
        BytesFetcher fetcher = new BytesFetcher(zip("big.jar", entries));
        Path cacheFile = dir.resolve("cache/big.jar");
        RemoteJar.Options rangedReads = new RemoteJar.Options(128, 0);
        RemoteJar jar = jar(fetcher, cacheFile, rangedReads);

        assertEquals(entries.keySet(), jar.entryNames());
        assertEquals(1, fetcher.tails);
        assertEquals(1, fetcher.ranges);

        assertArrayEquals(entries.get("pkg/Entry7.class"), jar.read("pkg/Entry7.class").orElseThrow());
        assertArrayEquals(entries.get("META-INF/stored.txt"), jar.read("META-INF/stored.txt").orElseThrow());
        assertEquals(3, fetcher.ranges);
        assertEquals(0, fetcher.alls);
        assertFalse(Files.exists(cacheFile));

        BytesFetcher second = new BytesFetcher(fetcher.data);
        RemoteJar cached = jar(second, cacheFile, rangedReads);
        assertArrayEquals(entries.get("pkg/Entry7.class"), cached.read("pkg/Entry7.class").orElseThrow());
        assertEquals(0, second.requests());
        assertArrayEquals(entries.get("pkg/Entry8.class"), cached.read("pkg/Entry8.class").orElseThrow());
        assertEquals(1, second.requests());
    }

    @Test
    void smallJarIsDownloadedWholeOnFirstRead() throws IOException {
        Map<String, byte[]> entries = sampleEntries(40);
        BytesFetcher fetcher = new BytesFetcher(zip("medium.jar", entries));
        Path cacheFile = dir.resolve("cache/medium.jar");
        RemoteJar jar = jar(fetcher, cacheFile, new RemoteJar.Options(128, 1024 * 1024));

        assertTrue(jar.contains("pkg/Entry3.class"));
        assertEquals(2, fetcher.requests());
        assertArrayEquals(entries.get("pkg/Entry3.class"), jar.read("pkg/Entry3.class").orElseThrow());
        assertArrayEquals(entries.get("pkg/Entry4.class"), jar.read("pkg/Entry4.class").orElseThrow());
        assertEquals(1, fetcher.alls);
        assertEquals(3, fetcher.requests());
        assertArrayEquals(fetcher.data, Files.readAllBytes(cacheFile));
    }

    @Test
    void corruptEntryFailsTheCrcCheck() throws IOException {
        byte[] zip = zip("corrupt.jar", sampleEntries(2));
        int marker = indexOf(zip, STORED_MARKER.getBytes(StandardCharsets.UTF_8));
        zip[marker] ^= 0x20;
        RemoteJar jar = jar(new BytesFetcher(zip), dir.resolve("cache/corrupt.jar"), RemoteJar.Options.DEFAULT);

        ZipException error = assertThrows(ZipException.class, () -> jar.read("META-INF/stored.txt"));
        assertTrue(error.getMessage().contains("CRC"), error.getMessage());
        assertTrue(jar.read("pkg/Entry1.class").isPresent());
    }

    @Test
    void missingJarIsRememberedWithAMarker() throws IOException {
        Path cacheFile = dir.resolve("cache/missing.jar");
        BytesFetcher fetcher = new BytesFetcher(null);
        RemoteJar jar = jar(fetcher, cacheFile, RemoteJar.Options.DEFAULT);

        assertFalse(jar.exists());
        assertTrue(jar.entryNames().isEmpty());
        assertEquals(Optional.empty(), jar.read("META-INF/MANIFEST.MF"));
        assertTrue(Files.exists(dir.resolve("cache/missing.jar.404")));

        BytesFetcher second = new BytesFetcher(null);
        assertFalse(jar(second, cacheFile, RemoteJar.Options.DEFAULT).exists());
        assertEquals(0, second.requests());
    }

    @Test
    void zip64ArchivesAreDownloadedWhole() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 65_540; i++) {
            entries.put("e" + i, new byte[0]);
        }
        entries.put("last.txt", "last".getBytes(StandardCharsets.UTF_8));
        BytesFetcher fetcher = new BytesFetcher(zip("zip64.jar", entries));
        RemoteJar jar = jar(fetcher, dir.resolve("cache/zip64.jar"), new RemoteJar.Options(1024, 0));

        assertEquals(65_541, jar.entryNames().size());
        assertEquals(1, fetcher.alls);
        assertArrayEquals("last".getBytes(StandardCharsets.UTF_8), jar.read("last.txt").orElseThrow());
        assertEquals(2, fetcher.requests());
    }

    private static RemoteJar jar(RangeFetcher fetcher, Path cacheFile, RemoteJar.Options options) {
        return new RemoteJar("io.quarkus", "test", "1.0", fetcher, cacheFile, options);
    }

    /// One stored entry, one empty directory and `count` compressible deflated entries.
    private static Map<String, byte[]> sampleEntries(int count) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/", new byte[0]);
        entries.put("META-INF/stored.txt", (STORED_MARKER + " stored without compression").getBytes(
                StandardCharsets.UTF_8));
        for (int i = 0; i < count; i++) {
            entries.put("pkg/Entry" + i + ".class", ("class file " + i + " ").repeat(50).getBytes(
                    StandardCharsets.UTF_8));
        }
        return entries;
    }

    /// Writes a zip into the temp folder (entries ending in .txt are stored, the others deflated).
    private byte[] zip(String name, Map<String, byte[]> entries) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                if (entry.getKey().endsWith(".txt")) {
                    CRC32 crc = new CRC32();
                    crc.update(entry.getValue());
                    zipEntry.setMethod(ZipEntry.STORED);
                    zipEntry.setSize(entry.getValue().length);
                    zipEntry.setCompressedSize(entry.getValue().length);
                    zipEntry.setCrc(crc.getValue());
                }
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return Files.readAllBytes(file);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("marker not found");
    }
}
