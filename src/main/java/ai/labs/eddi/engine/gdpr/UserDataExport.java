/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import com.fasterxml.jackson.annotation.JsonProperty;

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
 *            cap is stated in the payload and not only in the server log. It is
 *            <em>one</em> of the reasons a bundle can be incomplete — see
 *            {@link #complete()} for the whole answer.
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
     * Personal-data categories this exporter does not reach yet, named as the
     * erasure cascade names them in {@code GdprDeletionResult}.
     * <p>
     * The two halves of the feature disagree about what the user's data is:
     * {@code deleteUserData} erases group discussion transcripts, shared artifacts,
     * schedules and HITL tool journal entries as this user's personal data, and the
     * export omits all four (closing the gap needs read-by-user methods those
     * stores do not have — see {@code IRestGdprAdmin}). Until it is closed, the
     * omission is part of the answer rather than a footnote in the interface
     * Javadoc: a data subject whose data lives only in these categories would
     * otherwise be handed an empty bundle described as complete.
     */
    public static final List<String> OMITTED_CATEGORIES = List.of("groupConversations", "sharedArtifacts", "schedules", "journalEntries");

    /**
     * The categories this bundle is known not to cover.
     * <p>
     * Annotated for the reason {@code GdprDeletionResult.complete()} is: a derived
     * method is neither a record component nor a bean getter, so Jackson would
     * leave it out of the REST entity while {@code McpGdprTools} puts it into the
     * MCP payload explicitly, and a client written against one surface would read
     * null from the other. {@code READ_ONLY} keeps it out of deserialization, where
     * the canonical constructor has no matching component.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public List<String> omittedCategories() {
        return OMITTED_CATEGORIES;
    }

    /**
     * Whether this bundle covers everything EDDI holds on the user.
     * <p>
     * {@code conversationsTruncated} used to be the only completeness signal, so a
     * user with fewer conversations than the cap got a bundle described as complete
     * however many whole categories were missing from it — and a user whose data
     * lives <em>only</em> in {@link #OMITTED_CATEGORIES} got an empty one. A DPO
     * answering an Art. 15/20 request needs the honest answer, so the omitted
     * categories count against completeness exactly as the cap does. False until
     * those four stores are exportable.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public boolean complete() {
        return !conversationsTruncated && omittedCategories().isEmpty();
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
