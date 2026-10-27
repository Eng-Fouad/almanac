package io.quarkiverse.almanac.maven;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/// Minimal zip format reader working on byte ranges of a file, so a remote jar can be indexed from its tail and
/// single entries read without downloading the whole file: end-of-central-directory search (ZIP64 aware), central
/// directory parsing and entry extraction (stored or deflated, CRC-checked).
///
/// Byte arrays passed in are slices of the file; `dataOffset` is the file offset of their first byte.
final class ZipIndex {

    static final int LOCAL_HEADER_SIZE = 30;

    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int EOCD_SIZE = 22;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int ZIP64_LOCATOR_SIZE = 20;
    private static final int ZIP64_EOCD_SIGNATURE = 0x06064b50;
    private static final int CENTRAL_HEADER_SIGNATURE = 0x02014b50;
    private static final int CENTRAL_HEADER_SIZE = 46;
    private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;
    private static final int ZIP64_EXTRA_ID = 0x0001;
    private static final long MAGIC_32 = 0xFFFFFFFFL;
    private static final int STORED = 0;
    private static final int DEFLATED = 8;

    /// Where the central directory is.
    ///
    /// @param offset file offset of the central directory, -1 when unknown
    /// @param size   its length in bytes, -1 when unknown
    /// @param zip64  whether the archive uses ZIP64 end records (offset/size are unknown when those records are not
    ///               part of the inspected bytes)
    record CentralDirectory(long offset, long size, boolean zip64) {
    }

    /// One central directory entry.
    ///
    /// @param nameLength  length of the encoded name
    /// @param extraLength length of the central extra field (the local one is usually the same size)
    record Entry(String name, int method, long crc, long compressedSize, long size, long localHeaderOffset,
            int nameLength, int extraLength) {
    }

    private ZipIndex() {
    }

    /// Locates the central directory from bytes at the end of a file.
    ///
    /// @param data       the last bytes of the file (or all of it)
    /// @param dataOffset file offset of `data[0]`
    /// @throws ZipException when the bytes contain no end-of-central-directory record
    static CentralDirectory findCentralDirectory(byte[] data, long dataOffset) throws ZipException {
        for (int p = data.length - EOCD_SIZE; p >= 0 && p >= data.length - EOCD_SIZE - 0xFFFF; p--) {
            if (int32(data, p) != EOCD_SIGNATURE || p + EOCD_SIZE + uint16(data, p + 20) != data.length) {
                continue;
            }
            int locator = p - ZIP64_LOCATOR_SIZE;
            boolean zip64 = locator >= 0 && int32(data, locator) == ZIP64_LOCATOR_SIGNATURE;
            if (zip64) {
                return zip64CentralDirectory(data, dataOffset, uint64(data, locator + 8));
            }
            long size = uint32(data, p + 12);
            long offset = uint32(data, p + 16);
            if (uint16(data, p + 10) == 0xFFFF || size == MAGIC_32 || offset == MAGIC_32) {
                return new CentralDirectory(-1, -1, true);
            }
            return new CentralDirectory(offset, size, false);
        }
        throw new ZipException("no end of central directory record");
    }

    private static CentralDirectory zip64CentralDirectory(byte[] data, long dataOffset, long recordOffset)
            throws ZipException {
        long p = recordOffset - dataOffset;
        if (p < 0 || p + 56 > data.length) {
            return new CentralDirectory(-1, -1, true);
        }
        if (int32(data, (int) p) != ZIP64_EOCD_SIGNATURE) {
            throw new ZipException("invalid ZIP64 end of central directory record");
        }
        return new CentralDirectory(uint64(data, (int) p + 48), uint64(data, (int) p + 40), true);
    }

    /// Parses a complete central directory into entries keyed by name, in directory order.
    static Map<String, Entry> parseCentralDirectory(byte[] cd) throws ZipException {
        Map<String, Entry> entries = new LinkedHashMap<>();
        int p = 0;
        while (p + CENTRAL_HEADER_SIZE <= cd.length && int32(cd, p) == CENTRAL_HEADER_SIGNATURE) {
            int nameLength = uint16(cd, p + 28);
            int extraLength = uint16(cd, p + 30);
            int commentLength = uint16(cd, p + 32);
            int next = p + CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength;
            if (next > cd.length) {
                throw new ZipException("truncated central directory");
            }
            String name = new String(cd, p + CENTRAL_HEADER_SIZE, nameLength, StandardCharsets.UTF_8);
            long compressedSize = uint32(cd, p + 20);
            long size = uint32(cd, p + 24);
            long localHeaderOffset = uint32(cd, p + 42);
            if (compressedSize == MAGIC_32 || size == MAGIC_32 || localHeaderOffset == MAGIC_32) {
                long[] values = zip64Extra(cd, p + CENTRAL_HEADER_SIZE + nameLength, extraLength,
                        size == MAGIC_32, compressedSize == MAGIC_32, localHeaderOffset == MAGIC_32);
                size = size == MAGIC_32 ? values[0] : size;
                compressedSize = compressedSize == MAGIC_32 ? values[1] : compressedSize;
                localHeaderOffset = localHeaderOffset == MAGIC_32 ? values[2] : localHeaderOffset;
            }
            entries.put(name, new Entry(name, uint16(cd, p + 10), uint32(cd, p + 16), compressedSize, size,
                    localHeaderOffset, nameLength, extraLength));
            p = next;
        }
        if (p != cd.length) {
            throw new ZipException("unexpected data in central directory at " + p);
        }
        return Collections.unmodifiableMap(entries);
    }

    /// Reads the ZIP64 extended information extra field: only the values flagged as overflowed are present.
    private static long[] zip64Extra(byte[] cd, int start, int length, boolean hasSize, boolean hasCompressed,
            boolean hasOffset) throws ZipException {
        int p = start;
        while (p + 4 <= start + length) {
            int id = uint16(cd, p);
            int size = uint16(cd, p + 2);
            if (id == ZIP64_EXTRA_ID) {
                long[] values = new long[3];
                int q = p + 4;
                if (hasSize) {
                    values[0] = uint64(cd, q);
                    q += 8;
                }
                if (hasCompressed) {
                    values[1] = uint64(cd, q);
                    q += 8;
                }
                if (hasOffset) {
                    values[2] = uint64(cd, q);
                }
                return values;
            }
            p += 4 + size;
        }
        throw new ZipException("missing ZIP64 extra field");
    }

    /// Total length of an entry's local record (header, name, extra field and data), read from its local header.
    ///
    /// @param data bytes starting at or before the entry's local header, covering at least the fixed header
    static long recordLength(byte[] data, long dataOffset, Entry entry) throws ZipException {
        int p = localHeader(data, dataOffset, entry);
        return LOCAL_HEADER_SIZE + uint16(data, p + 26) + uint16(data, p + 28) + entry.compressedSize();
    }

    /// Extracts an entry: inflates it when deflated and verifies its size and CRC-32.
    ///
    /// @param data bytes covering the entry's whole local record (see [#recordLength])
    static byte[] extract(byte[] data, long dataOffset, Entry entry) throws ZipException {
        int p = localHeader(data, dataOffset, entry);
        long start = p + LOCAL_HEADER_SIZE + uint16(data, p + 26) + uint16(data, p + 28);
        long end = start + entry.compressedSize();
        if (end > data.length || entry.size() > Integer.MAX_VALUE) {
            throw new ZipException("entry " + entry.name() + " is not fully contained in the given bytes");
        }
        byte[] content = switch (entry.method()) {
            case STORED -> Arrays.copyOfRange(data, (int) start, (int) end);
            case DEFLATED -> inflate(data, (int) start, (int) entry.compressedSize(), (int) entry.size(), entry.name());
            default -> throw new ZipException("unsupported compression method " + entry.method() + " for "
                    + entry.name());
        };
        if (content.length != entry.size()) {
            throw new ZipException("size mismatch for " + entry.name());
        }
        CRC32 crc = new CRC32();
        crc.update(content);
        if (crc.getValue() != entry.crc()) {
            throw new ZipException("CRC mismatch for " + entry.name());
        }
        return content;
    }

    private static int localHeader(byte[] data, long dataOffset, Entry entry) throws ZipException {
        long p = entry.localHeaderOffset() - dataOffset;
        if (p < 0 || p + LOCAL_HEADER_SIZE > data.length) {
            throw new ZipException("local header of " + entry.name() + " is outside the given bytes");
        }
        if (int32(data, (int) p) != LOCAL_HEADER_SIGNATURE) {
            throw new ZipException("bad local header signature for " + entry.name());
        }
        return (int) p;
    }

    private static byte[] inflate(byte[] data, int start, int length, int size, String name) throws ZipException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data, start, length);
            byte[] out = new byte[size];
            int n = 0;
            boolean paddingSupplied = false;
            while (n < size && !inflater.finished()) {
                int read = inflater.inflate(out, n, size - n);
                n += read;
                if (read == 0) {
                    if (!inflater.needsInput() || paddingSupplied) {
                        break;
                    }
                    // raw deflate streams may need one extra byte to signal their end
                    inflater.setInput(new byte[1]);
                    paddingSupplied = true;
                }
            }
            return n == size ? out : Arrays.copyOf(out, n);
        } catch (DataFormatException e) {
            throw new ZipException("corrupt deflate data in " + name + ": " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    private static int uint16(byte[] b, int p) {
        return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8;
    }

    private static int int32(byte[] b, int p) {
        return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | (b[p + 3] & 0xFF) << 24;
    }

    private static long uint32(byte[] b, int p) {
        return int32(b, p) & MAGIC_32;
    }

    private static long uint64(byte[] b, int p) {
        return uint32(b, p) | uint32(b, p + 4) << 32;
    }
}
