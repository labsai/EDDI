/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.mcpcalls.impl;

import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A retry re-sends the tool call, and an MCP tool may have a side effect a
 * timeout does not undo — so a call without a {@code retry} block runs once.
 */
@DisplayName("McpCallsTask — retry is opt-in")
class McpCallsTaskRetryOptInTest {

    @Test
    @DisplayName("a call without a retry block gets a single attempt, not the shared three-attempt default")
    void noRetryBlockMeansOneAttempt() {
        var call = new McpCall();
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(LifecycleException.class, () -> RetryConfiguration.executeWithRetry(() -> {
            attempts.incrementAndGet();
            throw new SocketTimeoutException("timed out");
        }, McpCallsTask.effectiveRetry(call), "MCP call 'x'"));

        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("an explicit retry block is used as written")
    void explicitRetryBlockIsHonoured() {
        var call = new McpCall();
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(2);
        retry.setBackoffDelayMs(0L);
        call.setRetry(retry);

        assertSame(retry, McpCallsTask.effectiveRetry(call));
    }

    /**
     * Inside a turn deadline every attempt runs on the retry helper's own executor;
     * an MCP server's {@code apiKey: "${caller:token}"} is resolved from the caller
     * bound to the thread that sends the request.
     */
    @Test
    @DisplayName("inside a turn deadline the attempt thread still sees the turn's caller")
    void attemptThreadCarriesTheCaller() throws Exception {
        var context = new CallerIdentityContext(null, null);
        var task = new McpCallsTask(null, null, null, null, null, null);
        task.callerIdentityContext = context;
        var caller = new CallerIdentity("tok", "user-1", "https://eddi.example:443");
        var deadline = TurnDeadline.of(Clock.systemUTC(), System.currentTimeMillis(), 60_000, 1_000L);
        AtomicReference<CallerIdentity> seen = new AtomicReference<>();
        AtomicReference<Thread> attemptThread = new AtomicReference<>();

        context.bind(caller);
        try {
            RetryConfiguration.executeWithRetry(task.carryingCaller(() -> {
                attemptThread.set(Thread.currentThread());
                seen.set(context.current());
                return "ok";
            }), McpCallsTask.effectiveRetry(new McpCall()), "MCP call 'x'", new long[1], deadline);
        } finally {
            context.clear();
        }

        assertNotSame(Thread.currentThread(), attemptThread.get(), "the attempt is expected to run on another thread");
        assertSame(caller, seen.get());
    }
}
