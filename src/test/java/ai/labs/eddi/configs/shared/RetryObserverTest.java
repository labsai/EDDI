/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RetryObserver — retries visible to whoever listens on the thread")
class RetryObserverTest {

    private static RetryConfiguration fast(int attempts) {
        var config = new RetryConfiguration();
        config.setMaxAttempts(attempts);
        config.setBackoffDelayMs(0L);
        return config;
    }

    @Test
    @DisplayName("each retry is reported once; the final, unretried failure is not")
    void reportsRetriesOnly() {
        List<FailureClass> seen = new ArrayList<>();
        try (var ignored = RetryObserver.bind(seen::add); var modelCall = RetryObserver.modelCall()) {
            assertThrows(Exception.class, () -> RetryConfiguration.executeWithRetry(() -> {
                throw new SocketTimeoutException("always");
            }, fast(3), "x"));
        }
        assertEquals(List.of(FailureClass.TIMEOUT, FailureClass.TIMEOUT), seen);
    }

    @Test
    @DisplayName("a non-retryable failure is not a retry")
    void permanentFailure() {
        List<FailureClass> seen = new ArrayList<>();
        try (var ignored = RetryObserver.bind(seen::add); var modelCall = RetryObserver.modelCall()) {
            assertThrows(Exception.class, () -> RetryConfiguration.executeWithRetry(() -> {
                throw new IllegalArgumentException("bad input");
            }, fast(3), "x"));
        }
        assertTrue(seen.isEmpty());
    }

    @Test
    @DisplayName("bindings nest and restore; with none bound a retry notifies nobody")
    void nesting() {
        List<FailureClass> outer = new ArrayList<>();
        List<FailureClass> inner = new ArrayList<>();
        try (var a = RetryObserver.bind(outer::add); var call = RetryObserver.modelCall()) {
            try (var b = RetryObserver.bind(inner::add)) {
                RetryObserver.retrying(FailureClass.TRANSIENT);
            }
            RetryObserver.retrying(null);
        }
        RetryObserver.retrying(FailureClass.AUTH);
        assertEquals(List.of(FailureClass.TRANSIENT), inner);
        assertEquals(List.of(FailureClass.UNKNOWN), outer);
    }

    @Test
    @DisplayName("a listener that throws cannot change the retry")
    void throwingListener() throws Exception {
        try (var ignored = RetryObserver.bind(c -> {
            throw new IllegalStateException("listener bug");
        }); var call = RetryObserver.modelCall()) {
            assertDoesNotThrow(() -> RetryObserver.retrying(FailureClass.TRANSIENT));
            int[] calls = {0};
            assertEquals("ok", RetryConfiguration.executeWithRetry(() -> {
                if (calls[0]++ == 0) {
                    throw new SocketTimeoutException("once");
                }
                return "ok";
            }, fast(2), "x"));
        }
    }

    @Test
    @DisplayName("outside a model call a retry is not reported — side calls are not the task's model")
    void outsideModelCall() {
        List<FailureClass> seen = new ArrayList<>();
        try (var ignored = RetryObserver.bind(seen::add)) {
            RetryObserver.retrying(FailureClass.TRANSIENT);
            try (var call = RetryObserver.modelCall()) {
                RetryObserver.retrying(FailureClass.TIMEOUT);
            }
            RetryObserver.retrying(FailureClass.TRANSIENT);
        }
        assertEquals(List.of(FailureClass.TIMEOUT), seen);
    }

    @Test
    @DisplayName("propagate carries the listener to another thread, and is the identity when none is bound")
    void propagate() throws Exception {
        Callable<String> work = () -> "done";
        assertSame(work, RetryObserver.propagate(work));
        List<FailureClass> seen = new ArrayList<>();
        Callable<Void> wrapped;
        try (var ignored = RetryObserver.bind(seen::add)) {
            wrapped = RetryObserver.propagate(() -> {
                try (var call = RetryObserver.modelCall()) {
                    RetryObserver.retrying(FailureClass.RATE_LIMITED);
                }
                return null;
            });
        }
        Thread thread = new Thread(() -> {
            try {
                wrapped.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        thread.start();
        thread.join();
        assertEquals(List.of(FailureClass.RATE_LIMITED), seen);
    }
}
