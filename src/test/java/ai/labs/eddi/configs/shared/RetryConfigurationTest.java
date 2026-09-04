/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
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

        /**
         * langchain4j's own classification is what {@code chatModel.chat()} actually
         * throws, and neither of the two previous copies of this method knew about it —
         * a provider timeout was only retried because its message happened to embed the
         * cause class name.
         */
        @Test
        @DisplayName("langchain4j RetriableException is retryable on its type alone")
        void langchain4jRetriable() {
            assertTrue(RetryConfiguration.isRetryableError(new RateLimitException("slow down")));
            assertTrue(RetryConfiguration.isRetryableError(new InternalServerException("upstream blew up")));
        }

        /**
         * And it must short-circuit in the other direction too: an auth failure whose
         * message merely mentions a timeout would otherwise burn every attempt.
         */
        @Test
        @DisplayName("langchain4j NonRetriableException stops immediately, even with a retryable-looking message")
        void langchain4jNonRetriable() {
            assertFalse(RetryConfiguration.isRetryableError(new AuthenticationException("invalid key after timeout")));
            assertFalse(RetryConfiguration.isRetryableError(new InvalidRequestException("bad request")));
        }

        /**
         * {@code CascadingModelExecutor} had a second copy of this method whose regex
         * matched bare status numbers while this one only looked at
         * {@code WebApplicationException} — so the same provider failure was retried on
         * one path and not the other, decided by message wording. One implementation
         * now answers for both; these are the cases only the other copy used to catch.
         */
        @Test
        @DisplayName("bare status numbers in a message are retryable (the cascade copy's rule)")
        void bareStatusNumbersInMessage() {
            assertTrue(RetryConfiguration.isRetryableError(new RuntimeException("provider returned 429")));
            assertTrue(RetryConfiguration.isRetryableError(new RuntimeException("HTTP 503 from upstream")));
        }

        @Test
        @DisplayName("a status-like number inside a larger token is not a match")
        void statusNumbersNeedWordBoundaries() {
            assertFalse(RetryConfiguration.isRetryableError(new RuntimeException("used 4290 tokens")));
            assertFalse(RetryConfiguration.isRetryableError(new RuntimeException("request id 5031abc")));
        }

        /**
         * {@code HttpException} is what several langchain4j HTTP clients actually
         * throw, and it is neither {@code RetriableException} nor
         * {@code NonRetriableException} — its {@code statusCode()} is the only signal
         * it carries. The messages here deliberately contain no transient wording, so
         * only the status-code branch can classify them: with a wrong accessor or a
         * wrong status set these would fall through to the text fallback and answer
         * false.
         */
        @Test
        @DisplayName("langchain4j HttpException is classified by its status code, not its text")
        void langchain4jHttpExceptionStatusCodes() {
            assertTrue(RetryConfiguration.isRetryableError(new HttpException(429, "quota")));
            assertTrue(RetryConfiguration.isRetryableError(new HttpException(502, "upstream")));
            assertTrue(RetryConfiguration.isRetryableError(new HttpException(503, "upstream")));
            assertTrue(RetryConfiguration.isRetryableError(new HttpException(504, "upstream")));
        }

        @Test
        @DisplayName("a non-transient HttpException status is not retryable")
        void langchain4jHttpExceptionNonRetryableStatus() {
            assertFalse(RetryConfiguration.isRetryableError(new HttpException(400, "malformed body")));
            assertFalse(RetryConfiguration.isRetryableError(new HttpException(401, "bad key")));
            assertFalse(RetryConfiguration.isRetryableError(new HttpException(500, "upstream")));
        }

        @Test
        @DisplayName("a wrapped HttpException is found by walking the cause chain")
        void langchain4jHttpExceptionWrapped() {
            assertTrue(RetryConfiguration.isRetryableError(
                    new RuntimeException("provider call failed", new HttpException(503, "upstream"))));
        }
    }

    /**
     * The total-backoff budget bounds one {@code executeWithRetry} call — and ONLY
     * the budget. Deciding "budget spent" and "this retry sleeps for zero" with the
     * same {@code sleepFor <= 0} test conflated the two, so an agent configuring
     * {@code "backoffDelayMs": 0} — a legitimate "retry immediately" policy — lost
     * retries entirely on its first retryable failure.
     */
    @Nested
    @DisplayName("total backoff budget")
    class TotalBackoffBudgetTests {

        @Test
        @DisplayName("zero backoff still makes every configured attempt")
        void zeroBackoffStillRetriesEveryAttempt() {
            var config = new RetryConfiguration();
            config.setMaxAttempts(5);
            config.setBackoffDelayMs(0L);
            config.setBackoffMultiplier(2.0);
            config.setMaxBackoffDelayMs(10L);

            AtomicInteger attempts = new AtomicInteger(0);
            Callable<String> action = () -> {
                attempts.incrementAndGet();
                throw new SocketTimeoutException("always fails");
            };

            var ex = assertThrows(LifecycleException.class,
                    () -> RetryConfiguration.executeWithRetry(action, config, "test-action"));

            assertEquals(5, attempts.get(), "backoffDelayMs=0 means retry immediately, not stop retrying");
            assertTrue(ex.getMessage().contains("failed after 5 attempts"), "got: " + ex.getMessage());
        }

        @Test
        @DisplayName("a negative backoff is treated as zero rather than reaching Thread.sleep")
        void negativeBackoffDoesNotThrow() {
            var config = new RetryConfiguration();
            config.setMaxAttempts(3);
            config.setBackoffDelayMs(-5L);
            config.setBackoffMultiplier(1.0);
            config.setMaxBackoffDelayMs(10L);

            AtomicInteger attempts = new AtomicInteger(0);
            Callable<String> action = () -> {
                attempts.incrementAndGet();
                throw new SocketTimeoutException("always fails");
            };

            assertThrows(LifecycleException.class, () -> RetryConfiguration.executeWithRetry(action, config, "test-action"));
            assertEquals(3, attempts.get());
        }

        /**
         * The budget arithmetic itself, which cannot be reached through
         * {@code executeWithRetry} without genuinely sleeping the full 60 seconds.
         */
        @Test
        @DisplayName("zero is a sleep of zero; only a spent budget answers 'stop'")
        void budgetedSleepDistinguishesZeroFromExhausted() {
            assertEquals(0L, RetryConfiguration.budgetedSleep(0L, 0L), "a zero backoff is not an exhausted budget");
            assertEquals(0L, RetryConfiguration.budgetedSleep(-7L, 0L), "a negative backoff clamps to zero, it does not stop the loop");
            assertEquals(1_000L, RetryConfiguration.budgetedSleep(1_000L, 0L));
        }

        @Test
        @DisplayName("the last sleep is trimmed to what is left, and the next one gives up")
        void budgetedSleepTrimsThenGivesUp() {
            long almostSpent = RetryConfiguration.MAX_TOTAL_BACKOFF_MS - 250L;

            assertEquals(250L, RetryConfiguration.budgetedSleep(30_000L, almostSpent), "the final sleep may only use what is left");
            assertEquals(-1L, RetryConfiguration.budgetedSleep(30_000L, RetryConfiguration.MAX_TOTAL_BACKOFF_MS));
            assertEquals(-1L, RetryConfiguration.budgetedSleep(1L, RetryConfiguration.MAX_TOTAL_BACKOFF_MS + 5_000L));
        }
    }

    /**
     * AGENTS.md §4.1 rule 5: a task must not block for extended periods. Attempts
     * and delays come from an agent's JSON {@code retry} block, which had no upper
     * bound at all — {@code {"maxAttempts": 50, "maxBackoffDelayMs": 60000}} parked
     * a pipeline worker for the best part of an hour per turn.
     */
    @Nested
    @DisplayName("engine ceilings on config-supplied retry policy")
    class CeilingTests {

        @Test
        @DisplayName("maxAttempts is clamped")
        void clampsAttempts() {
            var config = new RetryConfiguration();
            config.setMaxAttempts(50);
            config.setBackoffDelayMs(1L);
            config.setBackoffMultiplier(1.0);
            config.setMaxBackoffDelayMs(1L);

            AtomicInteger attempts = new AtomicInteger(0);
            Callable<String> action = () -> {
                attempts.incrementAndGet();
                throw new SocketTimeoutException("always fails");
            };

            assertThrows(LifecycleException.class, () -> RetryConfiguration.executeWithRetry(action, config, "test-action"));
            assertEquals(RetryConfiguration.MAX_ATTEMPTS_CEILING, attempts.get());
        }

        @Test
        @DisplayName("a single backoff is clamped, and a modest configured value is left alone")
        void clampsSingleBackoff() {
            var overAmbitious = new RetryConfiguration();
            overAmbitious.setMaxBackoffDelayMs(600_000L);
            assertEquals(RetryConfiguration.MAX_BACKOFF_CEILING_MS, RetryConfiguration.effectiveMaxBackoffMs(overAmbitious));

            var reasonable = new RetryConfiguration();
            reasonable.setMaxBackoffDelayMs(5_000L);
            assertEquals(5_000L, RetryConfiguration.effectiveMaxBackoffMs(reasonable), "config still chooses within the ceiling");
        }

        @Test
        @DisplayName("a modest attempt count is left alone")
        void doesNotClampReasonableAttempts() {
            var config = new RetryConfiguration();
            config.setMaxAttempts(4);
            assertEquals(4, RetryConfiguration.effectiveMaxAttempts(config));
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
