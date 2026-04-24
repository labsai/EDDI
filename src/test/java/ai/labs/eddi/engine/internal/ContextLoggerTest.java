/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.model.Deployment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextLoggerTest {

    private ContextLogger logger;

    @BeforeEach
    void setUp() {
        logger = new ContextLogger();
    }

    @Test
    void createLoggingContext_allFields() {
        var ctx = logger.createLoggingContext(
                Deployment.Environment.production, "agent-1", "conv-1", "user-1");

        assertEquals("production", ctx.get("environment"));
        assertEquals("agent-1", ctx.get("agentId"));
        assertEquals("conv-1", ctx.get("conversationId"));
        assertEquals("user-1", ctx.get("userId"));
    }

    @Test
    void createLoggingContext_nullConversationId() {
        var ctx = logger.createLoggingContext(
                Deployment.Environment.production, "agent-1", null, "user-1");

        assertFalse(ctx.containsKey("conversationId"));
        assertEquals("user-1", ctx.get("userId"));
    }

    @Test
    void createLoggingContext_nullUserId() {
        var ctx = logger.createLoggingContext(
                Deployment.Environment.production, "agent-1", "conv-1", null);

        assertEquals("conv-1", ctx.get("conversationId"));
        assertFalse(ctx.containsKey("userId"));
    }

    @Test
    void createLoggingContext_bothNull() {
        var ctx = logger.createLoggingContext(
                Deployment.Environment.production, "agent-1", null, null);

        assertEquals(2, ctx.size());
        assertEquals("production", ctx.get("environment"));
        assertEquals("agent-1", ctx.get("agentId"));
    }

    @Test
    void setLoggingContext_nullSafe() {
        assertDoesNotThrow(() -> logger.setLoggingContext(null));
    }

    @Test
    void setLoggingContext_withValues() {
        assertDoesNotThrow(() -> logger.setLoggingContext(Map.of("environment", "test")));
    }

    @Test
    void clearLoggingContext() {
        assertDoesNotThrow(() -> logger.clearLoggingContext());
    }
}
