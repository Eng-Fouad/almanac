package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;
import java.util.zip.ZipException;

/// A jar in a remote repository, indexed and read lazily with range requests and cached on disk.
///
/// - The first access fetches the last [Options#tailSize()] bytes. When that is the whole file it is cached
///   as the full jar; otherwise the end-of-central-directory record is parsed and the central directory (from the
///   tail, or fetched separately) is cached.
/// - Reading an entry of a jar of at most [Options#fullJarMax()] bytes downloads and caches the full jar;
///   larger jars read the entry's local header and data with one range request (plus slack for the local extra
///   field), inflate it, verify its CRC-32 and cache the entry.
/// - ZIP64 archives are downloaded in full. A 404 is cached as a marker file.
///
/// Cache files live next to `cacheFile` (the full jar's path): `.404`, `.cd` and `.entries/`.
/// Instances are thread-safe.
public final class RemoteJar {

    /// @param tailSize   bytes fetched from the end of the file on first access
    /// @param fullJarMax jars up to this size are downloaded whole when an entry is read
    public record Options(int tailSize, long fullJarMax) {
        public static final int DEFAULT_TAIL_SIZE = 64 * 1024;
        public static final long DEFAULT_FULL_JAR_MAX = 2 * 1024 * 1024;
        public static final Options DEFAULT = new Options(DEFAULT_TAIL_SIZE, DEFAULT_FULL_JAR_MAX);
    }

    /// Extra bytes requested after an entry's data to cover a local extra field larger than the central one.
    private static final int LOCAL_EXTRA_SLACK = 1024;
    private static final int CD_CACHE_MAGIC = 0x41434431; // "ACD1"

    private record Index(boolean exists, long size, Map<String, ZipIndex.Entry> entries) {
        static final Index MISSING = new Index(false, 0, Map.of());
    }

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final RangeFetcher fetcher;
    private final Path cacheFile;
    private final Options options;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Index index;
    private volatile byte[] content;

    /// @param fetcher   access to the remote bytes
    /// @param cacheFile where the full jar is (or would be) cached; the other cache files are its siblings
    public RemoteJar(String groupId, String artifactId, String version, RangeFetcher fetcher, Path cacheFile,
            Options options) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.fetcher = fetcher;
        this.cacheFile = cacheFile;
        this.options = options;
    }

    public String groupId() {
        return groupId;
    }

    public String artifactId() {
        return artifactId;
    }

    public String version() {
        return version;
    }

    /// False when the repository answers 404 for the jar.
    public boolean exists() throws IOException {
        return index().exists();
    }

    /// Names of all entries (directories end with `/`), in central directory order.
    public Set<String> entryNames() throws IOException {
        return index().entries().keySet();
    }

    public boolean contains(String entry) throws IOException {
        return index().entries().containsKey(entry);
    }

    /// The uncompressed, CRC-checked content of an entry; empty when the jar or the entry does not exist.
    public Optional<byte[]> read(String entry) throws IOException {
        Index idx = index();
        ZipIndex.Entry zipEntry = idx.entries().get(entry);
        if (zipEntry == null) {
            return Optional.empty();
        }
        byte[] whole = wholeJar(idx);
        return Optional.of(whole != null ? ZipIndex.extract(whole, 0, zipEntry) : readRanged(zipEntry, idx.size()));
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }

    private Index index() throws IOException {
        Index current = index;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (index == null) {
                index = loadIndex();
            }
            return index;
        } finally {
            lock.unlock();
        }
    }

    private Index loadIndex() throws IOException {
        if (Files.exists(sibling(".404"))) {
            return Index.MISSING;
        }
        if (Files.exists(cacheFile)) {
            content = Files.readAllBytes(cacheFile);
            return indexOf(content);
        }
        Path cdCache = sibling(".cd");
        if (Files.exists(cdCache)) {
            return readCentralDirectoryCache(cdCache);
        }
        Optional<RangeFetcher.Tail> fetched = fetcher.tail(options.tailSize());
        if (fetched.isEmpty()) {
            AtomicFiles.write(sibling(".404"), new byte[0]);
            return Index.MISSING;
        }
        RangeFetcher.Tail tail = fetched.get();
        if (tail.isWholeFile()) {
            AtomicFiles.write(cacheFile, tail.bytes());
            content = tail.bytes();
            return indexOf(content);
        }
        ZipIndex.CentralDirectory cd = ZipIndex.findCentralDirectory(tail.bytes(), tail.offset());
        if (cd.zip64()) {
            Log.debug("%s uses ZIP64, downloading it whole", this);
            content = download(tail.fileSize());
            return indexOf(content);
        }
        byte[] cdBytes = cd.offset() >= tail.offset()
                ? slice(tail.bytes(), cd.offset() - tail.offset(), cd.size())
                : fetcher.range(cd.offset(), Math.toIntExact(cd.size()));
        Map<String, ZipIndex.Entry> entries = ZipIndex.parseCentralDirectory(cdBytes);
        writeCentralDirectoryCache(cdCache, tail.fileSize(), cdBytes);
        return new Index(true, tail.fileSize(), entries);
    }

    private static Index indexOf(byte[] jar) throws ZipException {
        ZipIndex.CentralDirectory cd = ZipIndex.findCentralDirectory(jar, 0);
        if (cd.offset() < 0) {
            throw new ZipException("unsupported zip layout (multi-disk or inconsistent ZIP64 records)");
        }
        return new Index(true, jar.length, ZipIndex.parseCentralDirectory(slice(jar, cd.offset(), cd.size())));
    }

    /// The whole jar when it is (or should be) available locally, else null for ranged reads.
    private byte[] wholeJar(Index idx) throws IOException {
        byte[] whole = content;
        if (whole != null || (idx.size() > options.fullJarMax() && !Files.exists(cacheFile))) {
            return whole;
        }
        lock.lock();
        try {
            if (content == null) {
                content = Files.exists(cacheFile) ? Files.readAllBytes(cacheFile) : download(idx.size());
            }
            return content;
        } finally {
            lock.unlock();
        }
    }

    private byte[] download(long expectedSize) throws IOException {
        byte[] jar = fetcher.all();
        if (jar.length != expectedSize) {
            throw new IOException(this + ": expected " + expectedSize + " bytes, got " + jar.length);
        }
        AtomicFiles.write(cacheFile, jar);
        return jar;
    }

    private byte[] readRanged(ZipIndex.Entry entry, long fileSize) throws IOException {
        Path cached = sibling(".entries").resolve(hash(entry.name()));
        if (Files.exists(cached)) {
            byte[] data = Files.readAllBytes(cached);
            if (data.length == entry.size() && crc(data) == entry.crc()) {
                return data;
            }
        }
        long start = entry.localHeaderOffset();
        long estimate = ZipIndex.LOCAL_HEADER_SIZE + entry.nameLength() + entry.extraLength()
                + entry.compressedSize() + LOCAL_EXTRA_SLACK;
        byte[] region = fetcher.range(start, Math.toIntExact(Math.min(estimate, fileSize - start)));
        long needed = ZipIndex.recordLength(region, start, entry);
        if (needed > region.length) {
            region = fetcher.range(start, Math.toIntExact(needed));
        }
        byte[] data = ZipIndex.extract(region, start, entry);
        AtomicFiles.write(cached, data);
        return data;
    }

    private Index readCentralDirectoryCache(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(in)) {
            if (data.readInt() != CD_CACHE_MAGIC) {
                throw new IOException("corrupt central directory cache " + file);
            }
            long size = data.readLong();
            return new Index(true, size, ZipIndex.parseCentralDirectory(data.readAllBytes()));
        }
    }

    private static void writeCentralDirectoryCache(Path file, long fileSize, byte[] cd) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(cd.length + 12);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(CD_CACHE_MAGIC);
            out.writeLong(fileSize);
            out.write(cd);
        }
        AtomicFiles.write(file, bytes.toByteArray());
    }

    private Path sibling(String suffix) {
        return cacheFile.resolveSibling(cacheFile.getFileName() + suffix);
    }

    private static byte[] slice(byte[] bytes, long from, long length) throws ZipException {
        if (from < 0 || length < 0 || from + length > bytes.length) {
            throw new ZipException("central directory outside the available bytes");
        }
        return Arrays.copyOfRange(bytes, (int) from, (int) (from + length));
    }

    private static long crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static String hash(String entryName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(entryName.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
