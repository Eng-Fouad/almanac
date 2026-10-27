package io.quarkiverse.almanac.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
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
}
