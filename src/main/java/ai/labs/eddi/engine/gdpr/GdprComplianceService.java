package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.memory.IAttachmentStorage;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
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
    private final AuditLedgerService auditLedgerService;
    private final Instance<IAttachmentStorage> attachmentStorageInstance;

    @Inject
    public GdprComplianceService(IUserMemoryStore userMemoryStore,
            IConversationMemoryStore conversationMemoryStore,
            IUserConversationStore userConversationStore,
            IDatabaseLogs databaseLogs,
            IAuditStore auditStore,
            AuditLedgerService auditLedgerService,
            Instance<IAttachmentStorage> attachmentStorageInstance) {
        this.userMemoryStore = userMemoryStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.userConversationStore = userConversationStore;
        this.databaseLogs = databaseLogs;
        this.auditStore = auditStore;
        this.auditLedgerService = auditLedgerService;
        this.attachmentStorageInstance = attachmentStorageInstance;
    }

    /**
     * Execute a full GDPR erasure cascade for a user.
     * <p>
     * Order of operations:
     * <ol>
     * <li>Delete all persistent user memories</li>
     * <li>Delete all binary attachments for user conversations</li>
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

        // 2. Delete attachments for all user conversations
        long attachmentsDeleted = 0;
        try {
            if (attachmentStorageInstance.isResolvable()) {
                var attachmentStorage = attachmentStorageInstance.get();
                var conversationIds = conversationMemoryStore.getConversationIdsByUserId(userId);
                for (String convId : conversationIds) {
                    attachmentsDeleted += attachmentStorage.deleteByConversation(convId);
                }
                if (attachmentsDeleted > 0) {
                    LOGGER.infof("[GDPR] Deleted %d attachments across %d conversations [%s]",
                            attachmentsDeleted, conversationIds.size(), pseudonym);
                }
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete attachments [%s]", pseudonym);
        }

        // 3. Delete conversation memory snapshots
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

        // 4. Delete managed conversation mappings
        long mappingsDeleted = 0;
        try {
            mappingsDeleted = userConversationStore.deleteAllForUser(userId);
            LOGGER.infof("[GDPR] Deleted %d conversation mappings [%s]",
                    mappingsDeleted, pseudonym);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete conversation mappings [%s]",
                    pseudonym);
        }

        // 5. Pseudonymize database logs (not deleted — operational data)
        long logsPseudonymized = 0;
        try {
            logsPseudonymized = databaseLogs.pseudonymizeByUserId(userId, pseudonym);
            LOGGER.infof("[GDPR] Pseudonymized %d log entries [%s]",
                    logsPseudonymized, pseudonym);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to pseudonymize logs [%s]",
                    pseudonym);
        }

        // 6. Pseudonymize audit ledger (retained under Art. 17(3)(e))
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

        // Write compliance event to immutable audit ledger
        submitComplianceAuditEntry("GDPR_ERASURE", pseudonym, Map.of(
                "memoriesDeleted", memoriesDeleted,
                "attachmentsDeleted", attachmentsDeleted,
                "conversationsDeleted", conversationsDeleted,
                "mappingsDeleted", mappingsDeleted,
                "logsPseudonymized", logsPseudonymized,
                "auditPseudonymized", auditPseudonymized));

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
        // 4. Audit entries (capped at 10,000 to avoid excessive export size)
        var auditExportEntries = new ArrayList<UserDataExport.AuditExportEntry>();
        try {
            var auditEntries = auditStore.getEntriesByUserId(userId, 0, AUDIT_EXPORT_LIMIT);
            for (var ae : auditEntries) {
                auditExportEntries.add(new UserDataExport.AuditExportEntry(
                        ae.conversationId(), ae.agentId(), ae.taskType(),
                        ae.durationMs(), ae.llmDetail(), ae.timestamp()));
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to export audit entries [%s]",
                    pseudonym);
        }

        LOGGER.infof("[GDPR] Export complete [%s]: memories=%d, "
                + "conversations=%d, managedConversations=%d, auditEntries=%d",
                pseudonym, memories.size(), conversations.size(),
                managedConversations.size(), auditExportEntries.size());

        // Write compliance event to immutable audit ledger
        submitComplianceAuditEntry("GDPR_EXPORT", pseudonym, Map.of(
                "memoriesExported", memories.size(),
                "conversationsExported", conversations.size(),
                "managedConversationsExported", managedConversations.size(),
                "auditEntriesExported", auditExportEntries.size()));

        return new UserDataExport(userId, Instant.now(), memories,
                conversations, managedConversations, auditExportEntries);
    }

    // === Right to Restriction of Processing (GDPR Art. 18) ===

    private static final String RESTRICTION_KEY = "_gdpr_processing_restricted";
    private static final int AUDIT_EXPORT_LIMIT = 10_000;

    /**
     * Restrict processing for a user (GDPR Art. 18). Data is preserved but new
     * conversations and message processing are blocked.
     *
     * @param userId
     *            the user whose processing to restrict
     */
    public void restrictProcessing(String userId) {
        String pseudonym = "gdpr-erased:" + sha256(userId);
        LOGGER.infof("[GDPR] Processing restriction applied [%s]", pseudonym);

        try {
            var entry = new UserMemoryEntry(
                    null, userId, RESTRICTION_KEY, "true",
                    "gdpr", Property.Visibility.global, null,
                    List.of(), null, false, 0,
                    Instant.now(), Instant.now());
            userMemoryStore.upsert(entry);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to restrict processing [%s]",
                    pseudonym);
            throw new RuntimeException("Failed to restrict processing", e);
        }

        submitComplianceAuditEntry("GDPR_RESTRICT", pseudonym, Map.of());
    }

    /**
     * Remove processing restriction for a user. Restores normal conversation
     * processing.
     *
     * @param userId
     *            the user whose restriction to lift
     */
    public void unrestrictProcessing(String userId) {
        String pseudonym = "gdpr-erased:" + sha256(userId);
        LOGGER.infof("[GDPR] Processing restriction removed [%s]", pseudonym);

        try {
            var existing = userMemoryStore.getByKey(userId, RESTRICTION_KEY);
            if (existing.isPresent()) {
                userMemoryStore.deleteEntry(existing.get().id());
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to unrestrict processing [%s]",
                    pseudonym);
            throw new RuntimeException("Failed to unrestrict processing", e);
        }

        submitComplianceAuditEntry("GDPR_UNRESTRICT", pseudonym, Map.of());
    }

    /**
     * Check if processing is restricted for a user.
     *
     * @param userId
     *            the user to check
     * @return true if a processing restriction is in effect
     */
    public boolean isProcessingRestricted(String userId) {
        try {
            var entry = userMemoryStore.getByKey(userId, RESTRICTION_KEY);
            return entry.isPresent() && "true".equals(String.valueOf(entry.get().value()));
        } catch (Exception e) {
            // Fail-closed: if we can't verify restriction status, block processing
            // to prevent accidental processing of a restricted user during outages
            LOGGER.errorf("[GDPR] Failed to check processing restriction for user " +
                    "(fail-closed — blocking processing): %s", e.getMessage());
            return true;
        }
    }

    /**
     * Submit a compliance-relevant event to the immutable audit ledger. These
     * entries use the "compliance" task type and have no conversation context —
     * they represent administrative data operations.
     */
    private void submitComplianceAuditEntry(String eventType, String pseudonym,
                                            Map<String, Object> details) {
        try {
            var output = new LinkedHashMap<String, Object>(details);
            output.put("pseudonym", pseudonym);

            var entry = new AuditEntry(
                    UUID.randomUUID().toString(),
                    null, // no conversation
                    null, // no agent
                    null, // no version
                    pseudonym, // pseudonymized userId
                    null, // no environment
                    0, // no step
                    "ai.labs.compliance",
                    "compliance",
                    0, // no task index
                    0, // no duration
                    Map.of("eventType", eventType),
                    output,
                    null, // no LLM detail
                    null, // no tool calls
                    List.of(eventType),
                    0.0,
                    Instant.now(),
                    null // HMAC computed by AuditLedgerService
                    , null);
            auditLedgerService.submit(entry);
        } catch (Exception e) {
            // Never let audit logging failure break the GDPR operation
            LOGGER.warnf("[GDPR] Failed to write %s audit entry: %s",
                    eventType, e.getMessage());
        }
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
