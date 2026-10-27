package io.quarkiverse.almanac.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/// Fan-out helper on virtual threads. Network concurrency is bounded separately by `Http`; the bound here
/// only limits how many tasks hold their (possibly large) intermediate results at the same time.
public final class Parallel {

    /// A function that may fail with an [IOException].
    @FunctionalInterface
    public interface IoFunction<T, R> {
        R apply(T item) throws IOException;
    }

    private Parallel() {
    }

    /// Applies `function` to every item with at most `parallelism` tasks running at once and returns
    /// the results in input order. Every task runs to completion; the first failure is then rethrown with the
    /// others attached as suppressed exceptions, each once: tasks can fail with one shared exception, such as the
    /// failed fetch of a pom that several of them waited for.
    public static <T, R> List<R> map(Collection<T> items, int parallelism, IoFunction<? super T, ? extends R> function)
            throws IOException {
        Semaphore permits = new Semaphore(Math.max(1, parallelism));
        List<Future<R>> futures = new ArrayList<>(items.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (T item : items) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return function.apply(item);
                    } finally {
                        permits.release();
                    }
                }));
            }
        }
        List<R> results = new ArrayList<>(futures.size());
        Throwable failure = null;
        Set<Throwable> failures = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Future<R> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException e) {
                if (!failures.add(e.getCause())) {
                    continue;
                }
                if (failure == null) {
                    failure = e.getCause();
                } else {
                    failure.addSuppressed(e.getCause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while waiting for parallel tasks");
            }
        }
        if (failure != null) {
            throw rethrow(failure);
        }
        return results;
    }

    private static IOException rethrow(Throwable failure) {
        if (failure instanceof IOException io) {
            return io;
        }
        if (failure instanceof UncheckedIOException unchecked) {
            return unchecked.getCause();
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IOException(failure);
    }
}
