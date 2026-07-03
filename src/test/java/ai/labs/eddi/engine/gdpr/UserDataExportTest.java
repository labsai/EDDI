/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.engine.memory.model.ConversationState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link UserDataExport} export record and its nested
 * entries, including the backward-compatible (no-attachments) constructor.
 */
class UserDataExportTest {

    @Test
    void backwardCompatibleConstructor_defaultsAttachmentsEmpty() {
        var now = Instant.now();
        var export = new UserDataExport("user-1", now, List.of(), List.of(), List.of(), List.of());

        assertEquals("user-1", export.userId());
        assertEquals(now, export.exportedAt());
        assertTrue(export.memories().isEmpty());
        assertTrue(export.conversations().isEmpty());
        assertTrue(export.managedConversations().isEmpty());
        assertTrue(export.auditEntries().isEmpty());
        assertNotNull(export.attachments());
        assertTrue(export.attachments().isEmpty());
    }

    @Test
    void fullConstructor_exposesAllComponents() {
        var att = new UserDataExport.AttachmentExportEntry("conv-1", "ref-1", "a.pdf", "application/pdf", 42);
        var conv = new UserDataExport.ConversationExportEntry("conv-1", "agent-1", 3, ConversationState.ENDED, List.of());
        var audit = new UserDataExport.AuditExportEntry("conv-1", "agent-1", "llm", 12L, Map.of("k", "v"), Instant.now());

        var export = new UserDataExport("user-1", Instant.now(), List.of(), List.of(conv), List.of(),
                List.of(audit), List.of(att));

        assertEquals(1, export.attachments().size());
        assertEquals("conv-1", att.conversationId());
        assertEquals("ref-1", att.storageRef());
        assertEquals("a.pdf", att.fileName());
        assertEquals("application/pdf", att.mimeType());
        assertEquals(42, att.sizeBytes());

        assertEquals("agent-1", conv.agentId());
        assertEquals(3, conv.agentVersion());
        assertEquals(ConversationState.ENDED, conv.state());
        assertTrue(conv.outputs().isEmpty());

        assertEquals("llm", audit.taskType());
        assertEquals(12L, audit.durationMs());
        assertEquals("v", audit.llmDetail().get("k"));
        assertNotNull(audit.timestamp());
    }
}
