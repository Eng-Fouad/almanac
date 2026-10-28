package io.quarkiverse.almanac.util;

import io.quarkiverse.almanac.maven.Http;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParallelTest {

    @Test
    void returnsTheResultsInInputOrder() throws IOException {
        assertEquals(List.of(1, 4, 9, 16), Parallel.map(List.of(1, 2, 3, 4), 2, i -> i * i));
    }

    @Test
    void rethrowsTheFirstFailureWithEachOtherOneSuppressedOnce() {
        // several tasks can fail with one shared exception, such as the failed fetch of a pom they all waited for
        IOException shared = new IOException("shared");
        IOException other = new IOException("other");
        IOException thrown = assertThrows(IOException.class, () -> Parallel.map(List.of(1, 2, 3, 4, 5), 5, i -> {
            if (i == 3) {
                throw other;
            }
            if (i % 2 == 1 || i == 4) {
                throw shared;
            }
            return i;
        }));
        assertSame(shared, thrown);
        assertArrayEquals(new Throwable[] {other}, thrown.getSuppressed());
    }

    @Test
    void rethrowsTheExceptionOfAnUncheckedIOExceptionWithTheOtherFailuresAttachedToIt() {
        // a stop of the requests that a task carries in an UncheckedIOException comes out as the stop
        Http.StoppedException stopped = new Http.StoppedException("requests stopped");
        IllegalStateException broken = new IllegalStateException("broken");
        UncheckedIOException wrapped = new UncheckedIOException(new IOException("wrapped"));
        IOException thrown = assertThrows(IOException.class, () -> Parallel.map(List.of(1, 2, 3, 4), 4, i -> {
            if (i == 1) {
                throw new UncheckedIOException(stopped);
            }
            if (i == 2) {
                throw broken;
            }
            if (i == 4) {
                throw wrapped;
            }
            return i;
        }));
        assertSame(stopped, thrown);
        assertArrayEquals(new Throwable[] {broken, wrapped}, thrown.getSuppressed());
    }

    @Test
    void anExceptionAndTheUncheckedIOExceptionsThatCarryItAreOneFailure() {
        IOException shared = new IOException("shared");
        IOException other = new IOException("other");
        // the exception the first failure carries, thrown by other tasks too, is not attached to itself
        IOException thrown = assertThrows(IOException.class, () -> Parallel.map(List.of(1, 2, 3, 4), 4, i -> {
            if (i == 1 || i == 3) {
                throw new UncheckedIOException(shared);
            }
            if (i == 2) {
                throw shared;
            }
            throw other;
        }));
        assertSame(shared, thrown);
        assertArrayEquals(new Throwable[] {other}, thrown.getSuppressed());

        // nor is an UncheckedIOException that carries the first failure
        IOException first = new IOException("first");
        thrown = assertThrows(IOException.class, () -> Parallel.map(List.of(1, 2), 2, i -> {
            if (i == 1) {
                throw first;
            }
            throw new UncheckedIOException(first);
        }));
        assertSame(first, thrown);
        assertArrayEquals(new Throwable[0], thrown.getSuppressed());
    }
}
