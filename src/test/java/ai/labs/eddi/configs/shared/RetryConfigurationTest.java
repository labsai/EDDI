/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RetryConfiguration Tests")
class RetryConfigurationTest {

    /**
     * Creates a RetryConfiguration with very short delays to keep tests fast.
     */
    private RetryConfiguration fastRetryConfig(int maxAttempts) {
        var config = new RetryConfiguration();
        config.setMaxAttempts(maxAttempts);
        config.setBackoffDelayMs(1L); // 1ms instead of 1s
        config.setBackoffMultiplier(1.0); // no exponential growth
        config.setMaxBackoffDelayMs(2L);
        return config;
    }

    // ==================== executeWithRetry ====================

    @Nested
    @DisplayName("executeWithRetry")
    class ExecuteWithRetryTests {

        @Test
        @DisplayName("succeeds on first attempt")
        void succeedsOnFirstAttempt() throws LifecycleException {
            var result = RetryConfiguration.executeWithRetry(
                    () -> "ok", fastRetryConfig(3), "test-action");

            assertEquals("ok", result);
        }

        @Test
        @DisplayName("retries on retryable error and succeeds")
        void retriesOnRetryableErrorThenSucceeds() throws LifecycleException {
            AtomicInteger attempts = new AtomicInteger(0);
            Callable<String> action = () -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new SocketTimeoutException("read timed out");
                }
                return "recovered";
            };

            var result = RetryConfiguration.executeWithRetry(
                    action, fastRetryConfig(3), "test-action");

            assertEquals("recovered", result);
            assertEquals(3, attempts.get());
        }

        @Test
        @DisplayName("throws LifecycleException after exhausting all attempts")
        void throwsAfterExhaustingAttempts() {
            Callable<String> action = () -> {
                throw new SocketTimeoutException("always fails");
            };

            var ex = assertThrows(LifecycleException.class,
                    () -> RetryConfiguration.executeWithRetry(
                            action, fastRetryConfig(2), "test-action"));

            assertTrue(ex.getMessage().contains("failed after 2 attempts"));
            assertInstanceOf(SocketTimeoutException.class, ex.getCause());
        }

        @Test
        @DisplayName("throws immediately on non-retryable error")
        void throwsImmediatelyOnNonRetryableError() {
            AtomicInteger attempts = new AtomicInteger(0);
            Callable<String> action = () -> {
                attempts.incrementAndGet();
                throw new IllegalArgumentException("bad input");
            };

            var ex = assertThrows(LifecycleException.class,
                    () -> RetryConfiguration.executeWithRetry(
                            action, fastRetryConfig(3), "test-action"));

            assertEquals(1, attempts.get(), "should not retry non-retryable errors");
            assertTrue(ex.getMessage().contains("failed"));
            assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        }

        @Test
        @DisplayName("null config uses defaults (3 attempts)")
        void nullConfigUsesDefaults() {
            AtomicInteger attempts = new AtomicInteger(0);
            Callable<String> action = () -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new SocketTimeoutException("timeout");
                }
                return "ok";
            };

            // null config — defaults to 3 attempts; delays will be real (1s) but
            // the action succeeds on attempt 3, so only 2 backoff sleeps occur.
            // For speed we accept this test may take ~3s. For CI, the fast config
            // variant above is preferred.
            assertDoesNotThrow(() -> RetryConfiguration.executeWithRetry(action, null, "test-action"));
            assertEquals(3, attempts.get());
        }
    }

    // ==================== isRetryableError ====================

    @Nested
    @DisplayName("isRetryableError")
    class IsRetryableErrorTests {

        @Test
        @DisplayName("recognizes SocketTimeoutException")
        void socketTimeoutException() {
            assertTrue(RetryConfiguration.isRetryableError(
                    new Exception(new SocketTimeoutException("read timed out"))));
        }

        @Test
        @DisplayName("recognizes ConnectException")
        void connectException() {
            assertTrue(RetryConfiguration.isRetryableError(
                    new Exception(new ConnectException("Connection refused"))));
        }

        @Test
        @DisplayName("recognizes TimeoutException")
        void timeoutException() {
            assertTrue(RetryConfiguration.isRetryableError(
                    new Exception(new TimeoutException("timed out"))));
        }

        @Test
        @DisplayName("recognizes UnknownHostException")
        void unknownHostException() {
            assertTrue(RetryConfiguration.isRetryableError(
                    new Exception(new UnknownHostException("api.example.com"))));
        }

        @Test
        @DisplayName("recognizes HTTP 429 via WebApplicationException")
        void http429() {
            var response = Response.status(429).build();
            var wae = new WebApplicationException("Too Many Requests", response);
            assertTrue(RetryConfiguration.isRetryableError(wae));
        }

        @Test
        @DisplayName("recognizes HTTP 502 via WebApplicationException")
        void http502() {
            var response = Response.status(502).build();
            var wae = new WebApplicationException("Bad Gateway", response);
            assertTrue(RetryConfiguration.isRetryableError(wae));
        }

        @Test
        @DisplayName("recognizes HTTP 503 via WebApplicationException")
        void http503() {
            var response = Response.status(503).build();
            var wae = new WebApplicationException("Service Unavailable", response);
            assertTrue(RetryConfiguration.isRetryableError(wae));
        }

        @Test
        @DisplayName("recognizes HTTP 504 via WebApplicationException")
        void http504() {
            var response = Response.status(504).build();
            var wae = new WebApplicationException("Gateway Timeout", response);
            assertTrue(RetryConfiguration.isRetryableError(wae));
        }

        @Test
        @DisplayName("recognizes 'timeout' message pattern")
        void timeoutMessagePattern() {
            assertTrue(RetryConfiguration.isRetryableError(
                    new RuntimeException("Operation timeout after 30s")));
        }

        @Test
        @DisplayName("recognizes 'rate limit' message pattern")
        void rateLimitMessagePattern() {
            assertTrue(RetryConfiguration.isRetryableError(
                    new RuntimeException("Rate limit exceeded, please retry later")));
        }

        @Test
        @DisplayName("recognizes 'connection' message pattern")
        void connectionMessagePattern() {
            assertTrue(RetryConfiguration.isRetryableError(
                    new RuntimeException("Connection reset by peer")));
        }

        @Test
        @DisplayName("returns false for unknown exceptions")
        void unknownException() {
            assertFalse(RetryConfiguration.isRetryableError(
                    new IllegalArgumentException("bad input")));
        }

        @Test
        @DisplayName("walks cause chain to find retryable error")
        void walksCauseChain() {
            var root = new SocketTimeoutException("read timed out");
            var wrapped = new RuntimeException("wrapper", root);
            var doubleWrapped = new Exception("outer", wrapped);

            assertTrue(RetryConfiguration.isRetryableError(doubleWrapped));
        }
    }

    // ==================== backoff ====================

    @Nested
    @DisplayName("backoff")
    class BackoffTests {

        @Test
        @DisplayName("sleeps for configured duration on first attempt")
        void sleepsOnFirstAttempt() {
            var config = new RetryConfiguration();
            config.setBackoffDelayMs(10L);
            config.setBackoffMultiplier(2.0);
            config.setMaxBackoffDelayMs(1000L);

            long start = System.nanoTime();
            RetryConfiguration.backoff(1, config);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // attempt 1 → exponent 0 → delay = 10 * 2^0 = 10ms
            assertTrue(elapsedMs >= 5, "Expected at least ~10ms sleep, got " + elapsedMs + "ms");
        }

        @Test
        @DisplayName("respects maxBackoffDelay cap")
        void respectsMaxBackoffCap() {
            var config = new RetryConfiguration();
            config.setBackoffDelayMs(10L);
            config.setBackoffMultiplier(100.0); // would compute huge delay
            config.setMaxBackoffDelayMs(20L); // but capped at 20ms

            long start = System.nanoTime();
            RetryConfiguration.backoff(5, config);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // Should be capped at 20ms, not 10 * 100^4
            assertTrue(elapsedMs < 200, "Expected backoff capped at 20ms, got " + elapsedMs + "ms");
        }

        @Test
        @DisplayName("null config uses defaults without error")
        void nullConfigUsesDefaults() {
            // Should not throw — defaults are applied internally
            assertDoesNotThrow(() -> RetryConfiguration.backoff(1, null));
        }
    }

    // ==================== Getters and Setters ====================

    @Nested
    @DisplayName("getters and setters")
    class GettersSettersTests {

        @Test
        @DisplayName("default values are correct")
        void defaultValues() {
            var config = new RetryConfiguration();
            assertEquals(3, config.getMaxAttempts());
            assertEquals(1000L, config.getBackoffDelayMs());
            assertEquals(2.0, config.getBackoffMultiplier());
            assertEquals(10000L, config.getMaxBackoffDelayMs());
        }

        @Test
        @DisplayName("setters update values")
        void settersWork() {
            var config = new RetryConfiguration();
            config.setMaxAttempts(5);
            config.setBackoffDelayMs(500L);
            config.setBackoffMultiplier(1.5);
            config.setMaxBackoffDelayMs(5000L);

            assertEquals(5, config.getMaxAttempts());
            assertEquals(500L, config.getBackoffDelayMs());
            assertEquals(1.5, config.getBackoffMultiplier());
            assertEquals(5000L, config.getMaxBackoffDelayMs());
        }
    }
}
