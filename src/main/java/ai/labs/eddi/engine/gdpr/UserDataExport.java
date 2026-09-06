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
 * @param attachments
 *            attachment metadata for the exported conversations
 * @param totalConversations
 *            how many conversations the user has — <em>not</em> necessarily
 *            {@code conversations.size()}, see {@code conversationsTruncated}
 * @param conversationsTruncated
 *            true when the per-request conversation cap bit, so this bundle is
 *            <strong>incomplete</strong>. Handing a data subject an Art. 15/20
 *            bundle that silently omits conversations is the same class of
 *            misreporting the erasure half of this API answers 207 for, so the
 *            cap is stated in the payload and not only in the server log;
 *            {@code RestGdprAdmin} answers 206 Partial Content when it is set.
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
        List<AttachmentExportEntry> attachments,
        int totalConversations,
        boolean conversationsTruncated) {

    /**
     * Backward-compatible constructor without attachment metadata.
     */
    public UserDataExport(String userId, Instant exportedAt, List<UserMemoryEntry> memories,
            List<ConversationExportEntry> conversations, List<UserConversation> managedConversations,
            List<AuditExportEntry> auditEntries) {
        this(userId, exportedAt, memories, conversations, managedConversations, auditEntries, List.of());
    }

    /**
     * Backward-compatible constructor for the shape that predates the truncation
     * marker — reports the bundle as complete, carrying every conversation it
     * holds.
     */
    public UserDataExport(String userId, Instant exportedAt, List<UserMemoryEntry> memories,
            List<ConversationExportEntry> conversations, List<UserConversation> managedConversations,
            List<AuditExportEntry> auditEntries, List<AttachmentExportEntry> attachments) {
        this(userId, exportedAt, memories, conversations, managedConversations, auditEntries, attachments,
                conversations == null ? 0 : conversations.size(), false);
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
