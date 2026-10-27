package io.quarkiverse.almanac.maven;

import java.io.IOException;
import java.util.Optional;

/// Random access to the bytes of one remote file. [RemoteJar] uses it over HTTP; tests use an in-memory one.
public interface RangeFetcher {

    /// The end of a file.
    ///
    /// @param bytes    the last bytes of the file (all of it when the file is shorter than requested)
    /// @param fileSize the total file size
    record Tail(byte[] bytes, long fileSize) {

        /// File offset of `bytes[0]`.
        public long offset() {
            return fileSize - bytes.length;
        }

        public boolean isWholeFile() {
            return bytes.length == fileSize;
        }
    }

    /// The last `length` bytes of the file; empty when the file does not exist.
    Optional<Tail> tail(int length) throws IOException;

    /// `length` bytes starting at file offset `offset`.
    byte[] range(long offset, int length) throws IOException;

    /// The whole file.
    byte[] all() throws IOException;
}
