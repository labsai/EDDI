/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full export of all user data (GDPR Art. 15/20 — Right of Access /
 * Portability).
 *
 * @param userId
 *            the user whose data is exported
 * @param exportedAt
 *            timestamp of the export
 * @param memories
 *            all persistent memory entries
 * @param conversations
 *            conversation summaries with chat history
 * @param managedConversations
 *            all managed (intent-based) conversation mappings
 * @param auditEntries
 *            processing records from the audit ledger (capped at 10,000)
 *
 * @author ginccc
 * @since 6.0.0
 */
public record UserDataExport(
        String userId,
        Instant exportedAt,
        List<UserMemoryEntry> memories,
        List<ConversationExportEntry> conversations,
        List<UserConversation> managedConversations,
        List<AuditExportEntry> auditEntries,
        List<AttachmentExportEntry> attachments) {

    /**
     * Backward-compatible constructor without attachment metadata.
     */
    public UserDataExport(String userId, Instant exportedAt, List<UserMemoryEntry> memories,
            List<ConversationExportEntry> conversations, List<UserConversation> managedConversations,
            List<AuditExportEntry> auditEntries) {
        this(userId, exportedAt, memories, conversations, managedConversations, auditEntries, List.of());
    }

    /**
     * Attachment metadata for export — never includes the binary payload
     * (portability is metadata; the bytes can be fetched via the download API).
     */
    public record AttachmentExportEntry(
            String conversationId,
            String storageRef,
            String fileName,
            String mimeType,
            long sizeBytes) {
    }

    /**
     * Lightweight conversation summary for export.
     */
    public record ConversationExportEntry(
            String conversationId,
            String agentId,
            Integer agentVersion,
            ConversationState state,
            List<ConversationOutput> outputs) {
    }

    /**
     * Lightweight audit entry for export — omits internal HMAC/signature fields
     * that are not user-relevant.
     */
    public record AuditExportEntry(
            String conversationId,
            String agentId,
            String taskType,
            long durationMs,
            Map<String, Object> llmDetail,
            Instant timestamp) {
    }
}
