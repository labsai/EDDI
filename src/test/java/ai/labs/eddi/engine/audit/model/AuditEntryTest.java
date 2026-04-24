/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditEntryTest {

    private AuditEntry createSample() {
        return new AuditEntry(
                "entry-1", "conv-1", "agent-1", 3, "user-1", "production",
                0, "ai.labs.parser", "expressions", 0, 150L,
                Map.of("userInput", "Hello"),
                Map.of("output", List.of("Hi there!")),
                null, null,
                List.of("greeting"),
                0.001,
                Instant.now(),
                null, null);
    }

    @Test
    void constructor_setsAllFields() {
        var entry = createSample();
        assertEquals("entry-1", entry.id());
        assertEquals("conv-1", entry.conversationId());
        assertEquals("agent-1", entry.agentId());
        assertEquals(3, entry.agentVersion());
        assertEquals("user-1", entry.userId());
        assertEquals("production", entry.environment());
        assertEquals(0, entry.stepIndex());
        assertEquals("ai.labs.parser", entry.taskId());
        assertEquals("expressions", entry.taskType());
        assertEquals(0, entry.taskIndex());
        assertEquals(150L, entry.durationMs());
        assertEquals("Hello", entry.input().get("userInput"));
        assertEquals(List.of("greeting"), entry.actions());
        assertEquals(0.001, entry.cost(), 0.0001);
        assertNotNull(entry.timestamp());
        assertNull(entry.hmac());
        assertNull(entry.agentSignature());
    }

    @Test
    void withEnvironment_preservesOtherFields() {
        var entry = createSample();
        var updated = entry.withEnvironment("test");

        assertEquals("test", updated.environment());
        assertEquals(entry.id(), updated.id());
        assertEquals(entry.conversationId(), updated.conversationId());
        assertEquals(entry.agentId(), updated.agentId());
        assertEquals(entry.taskId(), updated.taskId());
        assertEquals(entry.durationMs(), updated.durationMs());
    }

    @Test
    void withEnvironment_doesNotMutateOriginal() {
        var entry = createSample();
        entry.withEnvironment("staging");
        assertEquals("production", entry.environment());
    }

    @Test
    void withHmac_setsHmac() {
        var entry = createSample();
        var signed = entry.withHmac("abc123hmac");

        assertEquals("abc123hmac", signed.hmac());
        assertEquals(entry.id(), signed.id());
        assertEquals(entry.environment(), signed.environment());
    }

    @Test
    void withHmac_doesNotMutateOriginal() {
        var entry = createSample();
        entry.withHmac("hmac-value");
        assertNull(entry.hmac());
    }

    @Test
    void withAgentSignature_setsSignature() {
        var entry = createSample();
        var signed = entry.withAgentSignature("sig-xyz");

        assertEquals("sig-xyz", signed.agentSignature());
        assertEquals(entry.id(), signed.id());
        assertNull(signed.hmac()); // hmac was null originally
    }

    @Test
    void withAgentSignature_doesNotMutateOriginal() {
        var entry = createSample();
        entry.withAgentSignature("sig");
        assertNull(entry.agentSignature());
    }

    @Test
    void chaining_withMethods() {
        var entry = createSample()
                .withEnvironment("staging")
                .withHmac("hmac-123")
                .withAgentSignature("sig-456");

        assertEquals("staging", entry.environment());
        assertEquals("hmac-123", entry.hmac());
        assertEquals("sig-456", entry.agentSignature());
        // All other fields preserved
        assertEquals("entry-1", entry.id());
        assertEquals("conv-1", entry.conversationId());
    }
}
