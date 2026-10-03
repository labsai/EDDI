/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("TransientFailureRetry")
class TransientFailureRetryTest {

    @Test
    @DisplayName("a transient failure is retried once and the second answer is returned")
    void retriesATransientFailureOnce() {
        AtomicInteger calls = new AtomicInteger();
        String result = TransientFailureRetry.call(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new RateLimitException("429 slow down");
            }
            return "ok";
        }, "test");
        assertEquals("ok", result);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("a second transient failure reaches the caller; no third attempt")
    void givesUpAfterOneRetry() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RateLimitException.class, () -> TransientFailureRetry.call(() -> {
            calls.incrementAndGet();
            throw new RateLimitException("429 slow down");
        }, "test"));
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("a non-retryable failure is thrown at once, unchanged")
    void doesNotRetryAPermanentFailure() {
        AtomicInteger calls = new AtomicInteger();
        AuthenticationException denied = new AuthenticationException("bad key");
        AuthenticationException thrown = assertThrows(AuthenticationException.class, () -> TransientFailureRetry.call(() -> {
            calls.incrementAndGet();
            throw denied;
        }, "test"));
        assertSame(denied, thrown);
        assertEquals(1, calls.get());
    }
}
