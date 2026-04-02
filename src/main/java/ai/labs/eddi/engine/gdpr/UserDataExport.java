package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;

import java.time.Instant;
import java.util.List;

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
 *
 * @author ginccc
 * @since 6.0.0
 */
public record UserDataExport(
        String userId,
        Instant exportedAt,
        List<UserMemoryEntry> memories,
        List<ConversationExportEntry> conversations,
        List<UserConversation> managedConversations) {

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
}
