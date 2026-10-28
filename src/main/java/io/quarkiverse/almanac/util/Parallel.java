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
    /// the results in input order. Every task runs to completion; the first failure is then rethrown as it is (an
    /// [UncheckedIOException] as the [IOException] it carries) with the others attached to it as suppressed
    /// exceptions, each once: tasks can fail with one shared exception, such as the failed fetch of a pom that several
    /// of them waited for, and an exception is one failure with the [UncheckedIOException] that carries it.
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
        Throwable thrown = null;
        // what the failures so far stand for (unwrapped), so that none is attached twice or to itself
        Set<Throwable> failures = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Future<R> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException e) {
                if (!failures.add(unwrap(e.getCause()))) {
                    continue;
                }
                if (thrown == null) {
                    thrown = toThrow(e.getCause());
                } else {
                    thrown.addSuppressed(e.getCause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while waiting for parallel tasks");
            }
        }
        if (thrown != null) {
            throw rethrow(thrown);
        }
        return results;
    }

    /// The exception a failure stands for: the [IOException] that an [UncheckedIOException] carries, else the
    /// failure itself.
    private static Throwable unwrap(Throwable failure) {
        return failure instanceof UncheckedIOException unchecked ? unchecked.getCause() : failure;
    }

    /// The exception that [#map] throws for its first failure, worked out before the other failures are attached to
    /// it: the exception the failure stands for, or a new [IOException] that carries any other checked exception.
    private static Throwable toThrow(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof IOException || cause instanceof RuntimeException || cause instanceof Error
                ? cause
                : new IOException(cause);
    }

    /// Throws an unchecked `thrown` ([#toThrow]); returns an [IOException], for `throw rethrow(thrown)`.
    private static IOException rethrow(Throwable thrown) {
        if (thrown instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
        return (IOException) thrown;
    }
}
