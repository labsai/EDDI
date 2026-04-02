package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;

/**
 * Orchestrates GDPR-compliant data operations across all stores.
 * <p>
 * <strong>Deletion:</strong> Cascades erasure across all stores containing user
 * PII. User memories and conversations are permanently deleted. Audit ledger
 * and database logs are pseudonymized (userId → SHA-256 hash) under GDPR Art.
 * 17(3)(e).
 * <p>
 * <strong>Export:</strong> Aggregates all user data into a single
 * JSON-serializable bundle (GDPR Art. 15/20 — Right of Access / Data
 * Portability).
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class GdprComplianceService {

    private static final Logger LOGGER = Logger.getLogger(GdprComplianceService.class);

    private final IUserMemoryStore userMemoryStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IUserConversationStore userConversationStore;
    private final IDatabaseLogs databaseLogs;
    private final IAuditStore auditStore;

    @Inject
    public GdprComplianceService(IUserMemoryStore userMemoryStore,
            IConversationMemoryStore conversationMemoryStore,
            IUserConversationStore userConversationStore,
            IDatabaseLogs databaseLogs,
            IAuditStore auditStore) {
        this.userMemoryStore = userMemoryStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.userConversationStore = userConversationStore;
        this.databaseLogs = databaseLogs;
        this.auditStore = auditStore;
    }

    /**
     * Execute a full GDPR erasure cascade for a user.
     * <p>
     * Order of operations:
     * <ol>
     * <li>Delete all persistent user memories</li>
     * <li>Delete all conversation memory snapshots</li>
     * <li>Delete all managed conversation mappings</li>
     * <li>Pseudonymize database log entries</li>
     * <li>Pseudonymize audit ledger entries</li>
     * </ol>
     *
     * @param userId
     *            the user to erase
     * @return result with per-store deletion/pseudonymization counts
     */
    public GdprDeletionResult deleteUserData(String userId) {
        String pseudonym = "gdpr-erased:" + sha256(userId);
        LOGGER.infof("[GDPR] Starting erasure cascade for pseudonym '%s'", pseudonym);

        // 1. Delete user memories
        long memoriesDeleted = 0;
        try {
            memoriesDeleted = userMemoryStore.countEntries(userId);
            userMemoryStore.deleteAllForUser(userId);
            LOGGER.infof("[GDPR] Deleted %d user memory entries [%s]",
                    memoriesDeleted, pseudonym);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete user memories [%s]",
                    pseudonym);
        }

        // 2. Delete conversation memory snapshots
        long conversationsDeleted = 0;
        try {
            conversationsDeleted = conversationMemoryStore
                    .deleteConversationsByUserId(userId);
            LOGGER.infof("[GDPR] Deleted %d conversations [%s]",
                    conversationsDeleted, pseudonym);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete conversations [%s]",
                    pseudonym);
        }

        // 3. Delete managed conversation mappings
        long mappingsDeleted = 0;
        try {
            mappingsDeleted = userConversationStore.deleteAllForUser(userId);
            LOGGER.infof("[GDPR] Deleted %d conversation mappings [%s]",
                    mappingsDeleted, pseudonym);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete conversation mappings [%s]",
                    pseudonym);
        }

        // 4. Pseudonymize database logs (not deleted — operational data)
        long logsPseudonymized = 0;
        try {
            logsPseudonymized = databaseLogs.pseudonymizeByUserId(userId, pseudonym);
            LOGGER.infof("[GDPR] Pseudonymized %d log entries [%s]",
                    logsPseudonymized, pseudonym);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to pseudonymize logs [%s]",
                    pseudonym);
        }

        // 5. Pseudonymize audit ledger (retained under Art. 17(3)(e))
        long auditPseudonymized = 0;
        try {
            auditPseudonymized = auditStore.pseudonymizeByUserId(userId, pseudonym);
            LOGGER.infof("[GDPR] Pseudonymized %d audit entries [%s]",
                    auditPseudonymized, pseudonym);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to pseudonymize audit entries [%s]",
                    pseudonym);
        }

        var result = new GdprDeletionResult(userId, memoriesDeleted,
                conversationsDeleted, mappingsDeleted, logsPseudonymized,
                auditPseudonymized, Instant.now());

        LOGGER.infof("[GDPR] Erasure cascade complete [%s]: "
                + "memories=%d, conversations=%d, mappings=%d, "
                + "logs=%d, audit=%d",
                pseudonym, memoriesDeleted, conversationsDeleted,
                mappingsDeleted, logsPseudonymized, auditPseudonymized);

        return result;
    }

    /**
     * Export all user data (GDPR Art. 15/20).
     *
     * @param userId
     *            the user whose data to export
     * @return a JSON-serializable bundle of all user data
     */
    public UserDataExport exportUserData(String userId) {
        String pseudonym = "gdpr-erased:" + sha256(userId);
        LOGGER.infof("[GDPR] Starting data export [%s]", pseudonym);

        // 1. User memories
        var memories = new ArrayList<UserMemoryEntry>();
        try {
            memories.addAll(userMemoryStore.getAllEntries(userId));
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to export memories [%s]", pseudonym);
        }

        // 2. Conversation snapshots (lightweight export)
        var conversations = new ArrayList<UserDataExport.ConversationExportEntry>();
        try {
            var conversationIds = conversationMemoryStore
                    .getConversationIdsByUserId(userId);
            for (var convId : conversationIds) {
                try {
                    var snapshot = conversationMemoryStore
                            .loadConversationMemorySnapshot(convId);
                    if (snapshot != null) {
                        conversations.add(new UserDataExport.ConversationExportEntry(
                                convId,
                                snapshot.getAgentId(),
                                snapshot.getAgentVersion(),
                                snapshot.getConversationState(),
                                snapshot.getConversationOutputs()));
                    }
                } catch (Exception e) {
                    LOGGER.warnf("[GDPR] Skipping conversation %s during export: %s",
                            convId, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to export conversations [%s]",
                    pseudonym);
        }

        // 3. Managed conversation mappings
        var managedConversations = new ArrayList<UserConversation>();
        try {
            managedConversations.addAll(userConversationStore.getAllForUser(userId));
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to export managed conversations [%s]",
                    pseudonym);
        }

        LOGGER.infof("[GDPR] Export complete [%s]: memories=%d, "
                + "conversations=%d, managedConversations=%d",
                pseudonym, memories.size(), conversations.size(),
                managedConversations.size());

        return new UserDataExport(userId, Instant.now(), memories,
                conversations, managedConversations);
    }

    /**
     * Compute SHA-256 hash for pseudonymization.
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
