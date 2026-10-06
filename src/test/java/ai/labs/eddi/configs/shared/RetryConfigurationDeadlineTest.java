/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import dev.langchain4j.exception.HttpException;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RetryConfiguration#executeWithRetry} inside a turn deadline. A hand
 * driven clock stands in for the turn's budget, so no test waits for real time
 * except the one that proves an overrunning attempt is abandoned.
 */
@DisplayName("RetryConfiguration inside a turn deadline")
class RetryConfigurationDeadlineTest {

    private static final String RATE_LIMIT_BODY_4S = "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"slow\","
            + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"4s\"}]}}";

    private static RetryConfiguration retry(int attempts, long backoffMs) {
        var config = new RetryConfiguration();
        config.setMaxAttempts(attempts);
        config.setBackoffDelayMs(backoffMs);
        config.setBackoffMultiplier(1.0);
        config.setMaxBackoffDelayMs(backoffMs);
        return config;
    }

    private static TurnDeadline deadline(MutableTestClock clock, long budgetMs, long reserveMs) {
        return TurnDeadline.of(clock, clock.millis(), budgetMs, reserveMs);
    }

    private static Callable<String> failing(AtomicInteger calls, int failures) {
        return () -> {
            if (calls.incrementAndGet() <= failures) {
                throw new SocketTimeoutException("read timed out");
            }
            return "ok";
        };
    }

    @Test
    @DisplayName("with room to spare it retries exactly as it does without a deadline")
    void retriesWhenThereIsRoom() throws LifecycleException {
        var clock = new MutableTestClock();
        AtomicInteger calls = new AtomicInteger();

        String result = RetryConfiguration.executeWithRetry(failing(calls, 2), retry(3, 1), "test", new long[1], deadline(clock, 60_000, 1_500));

        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    @DisplayName("a retry is not started when less than the minimum attempt plus the reserve remains")
    void retryIsSkippedWhenTheBudgetIsShort() {
        var clock = new MutableTestClock();
        AtomicInteger calls = new AtomicInteger();
        // 4000 ms left, 1500 reserved: 2500 < 3000 minimum attempt. The first attempt
        // still runs.
        var d = deadline(clock, 4_000, 1_500);

        var ex = assertThrows(LifecycleException.class,
                () -> RetryConfiguration.executeWithRetry(failing(calls, 5), retry(3, 1), "test", new long[1], d));

        assertEquals(1, calls.get(), "only the first attempt may run");
        assertTrue(ex.getMessage().contains("turn deadline"), ex.getMessage());
        assertInstanceOf(SocketTimeoutException.class, ex.getCause(), "the failure that caused the stop stays on the chain");
    }

    @Test
    @DisplayName("the budget shrinks as the clock advances during an attempt")
    void budgetShrinksWithTime() {
        var clock = new MutableTestClock();
        AtomicInteger calls = new AtomicInteger();
        var d = deadline(clock, 10_000, 1_500);
        Callable<String> slowFailure = () -> {
            calls.incrementAndGet();
            clock.advance(6_000); // the attempt "took" 6 s
            throw new SocketTimeoutException("read timed out");
        };

        assertThrows(LifecycleException.class, () -> RetryConfiguration.executeWithRetry(slowFailure, retry(3, 1), "test", new long[1], d));

        assertEquals(1, calls.get(), "4000 ms left, 2500 after the reserve: no second attempt");
    }

    @Test
    @DisplayName("a backoff that would cross the deadline is not slept: the loop stops and rethrows at once")
    void sleepNeverCrossesTheDeadline() {
        var clock = new MutableTestClock();
        AtomicInteger calls = new AtomicInteger();
        // 9000 left, 1500 reserved = 7500. A 5000 ms backoff + a 3000 ms attempt = 8000
        // > 7500.
        var d = deadline(clock, 9_000, 1_500);
        long start = System.nanoTime();

        var ex = assertThrows(LifecycleException.class,
                () -> RetryConfiguration.executeWithRetry(failing(calls, 5), retry(3, 5_000), "test", new long[1], d));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(1, calls.get());
        assertTrue(elapsedMs < 2_000, "must not have slept the 5 s backoff, took " + elapsedMs + "ms");
        assertTrue(ex.getMessage().contains("no room to wait"), ex.getMessage());
    }

    @Test
    @DisplayName("a provider Retry-After that does not fit is not slept either")
    void retryAfterThatDoesNotFitIsNotSlept() {
        var clock = new MutableTestClock();
        AtomicInteger calls = new AtomicInteger();
        // The provider asks for 4 s (under maxRetryAfterMs, so normally honoured). 4000
        // + 3000 > 5500 - 1500... budget 7000-1500 = 5500.
        var d = deadline(clock, 7_000, 1_500);
        Callable<String> rateLimited = () -> {
            calls.incrementAndGet();
            throw new HttpException(429, RATE_LIMIT_BODY_4S);
        };
        long start = System.nanoTime();

        var ex = assertThrows(LifecycleException.class,
                () -> RetryConfiguration.executeWithRetry(rateLimited, retry(3, 1), "test", new long[1], d));

        assertEquals(1, calls.get());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "must not have slept the 4 s the provider asked for");
        assertEquals(FailureClass.RATE_LIMITED, LlmFailureClassifier.classify(ex).cls(), "the cascade still sees a rate limit");
    }

    @Test
    @DisplayName("a deadline that has already passed fails fast without calling the model")
    void expiredDeadlineFailsFast() {
        var clock = new MutableTestClock();
        var d = deadline(clock, 1_000, 0);
        clock.advance(1_000);
        AtomicInteger calls = new AtomicInteger();

        var ex = assertThrows(LifecycleException.class,
                () -> RetryConfiguration.executeWithRetry(failing(calls, 0), retry(3, 1), "test", new long[1], d));

        assertEquals(0, calls.get());
        assertTrue(ex.getMessage().contains("already passed"), ex.getMessage());
    }

    @Test
    @DisplayName("an attempt that overruns the remaining budget is abandoned, and counted as cancelled")
    void overrunningAttemptIsAbandoned() {
        var realClock = Clock.systemUTC();
        var d = TurnDeadline.of(realClock, realClock.millis(), 1_500, 1_000L); // 500 ms for the attempt
        AtomicInteger interrupted = new AtomicInteger();
        Callable<String> hang = () -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                throw e;
            }
            return "late";
        };
        var meters = new SimpleMeterRegistry();
        Metrics.addRegistry(meters);
        try {
            long start = System.nanoTime();

            assertThrows(LifecycleException.class, () -> RetryConfiguration.executeWithRetry(hang, retry(3, 1), "test", new long[1], d));

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 5_000, "the 30 s hang must be cut at ~500 ms, took " + elapsedMs + "ms");
            assertEquals(1.0, meters.counter("eddi.llm.cancelled", "scope", "attempt").count());
            assertEquals(1.0, meters.counter("eddi.llm.turn.deadline.exceeded", "stage", "attempt_timeout").count());
        } finally {
            Metrics.removeRegistry(meters);
        }
    }

    @Test
    @DisplayName("without a deadline nothing changes: the action runs on the caller's thread")
    void noDeadlineRunsInline() throws LifecycleException {
        Thread caller = Thread.currentThread();
        String result = RetryConfiguration.executeWithRetry(() -> Thread.currentThread() == caller ? "inline" : "other", retry(3, 1), "test",
                new long[1], null);
        assertEquals("inline", result);
    }
}
