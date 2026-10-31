/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.mcpcalls.impl;

import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
