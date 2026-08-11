/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.mongo.GroupConversationStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.audit.AuditHmac;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationCheckpointStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

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
    private final Instance<IAttachmentStore> attachmentStorageInstance;
    private final IHitlToolJournalStore hitlToolJournalStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IConversationCheckpointStore checkpointStore;
    private final Instance<GroupConversationStore> groupConversationStoreInstance;
    private final Instance<ISharedArtifactStore> sharedArtifactStoreInstance;
    private final IScheduleStore scheduleStore;
    private final ICache<String, UserConversation> userConversationCache;

    @Inject
    public GdprComplianceService(IUserMemoryStore userMemoryStore,
            IConversationMemoryStore conversationMemoryStore,
            IUserConversationStore userConversationStore,
            IDatabaseLogs databaseLogs,
            IAuditStore auditStore,
            AuditLedgerService auditLedgerService,
            Instance<IAttachmentStore> attachmentStorageInstance,
            IHitlToolJournalStore hitlToolJournalStore,
            IConversationDescriptorStore conversationDescriptorStore,
            IConversationCheckpointStore checkpointStore,
            Instance<GroupConversationStore> groupConversationStoreInstance,
            Instance<ISharedArtifactStore> sharedArtifactStoreInstance,
            IScheduleStore scheduleStore,
            ICacheFactory cacheFactory) {
        this.userMemoryStore = userMemoryStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.userConversationStore = userConversationStore;
        this.databaseLogs = databaseLogs;
        this.auditStore = auditStore;
        this.auditLedgerService = auditLedgerService;
        this.attachmentStorageInstance = attachmentStorageInstance;
        this.hitlToolJournalStore = hitlToolJournalStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.checkpointStore = checkpointStore;
        this.groupConversationStoreInstance = groupConversationStoreInstance;
        this.sharedArtifactStoreInstance = sharedArtifactStoreInstance;
        this.scheduleStore = scheduleStore;
        this.userConversationCache = cacheFactory.getCache(USER_CONVERSATION_CACHE_NAME);
    }

    /**
     * Name of the Caffeine cache {@code RestUserConversationStore} reads managed
     * conversation mappings through. Erasure deletes from the store directly, so
     * without an explicit invalidation the REST API kept serving the deleted
     * mapping — the cache has no TTL, so "indefinitely" is literal.
     */
    static final String USER_CONVERSATION_CACHE_NAME = "userConversations";

    /**
     * Cache key format used by {@code RestUserConversationStore}. Duplicated rather
     * than shared because the erasure cascade must not depend on a REST resource;
     * the two are pinned together by
     * {@code GdprComplianceServiceTest.deleteUserData_invalidatesCachedConversationMappings},
     * which drives a real {@code CacheFactory} and a real
     * {@code RestUserConversationStore} so a divergence in the key format shows up
     * as a stale mapping still being served after erasure.
     */
    private static String userConversationCacheKey(String intent, String userId) {
        return intent + "::" + userId;
    }

    /**
     * Execute a full GDPR erasure cascade for a user.
     * <p>
     * Order of operations:
     * <ol>
     * <li>Delete all persistent user memories</li>
     * <li>Delete all binary attachments for user conversations</li>
     * <li>Delete all HITL tool execution journal entries for user
     * conversations</li>
     * <li>Delete all conversation descriptors</li>
     * <li>Delete all conversation memory checkpoints</li>
     * <li>Delete all conversation memory snapshots</li>
     * <li>Delete all managed conversation mappings (and invalidate their
     * cache)</li>
     * <li>Delete all group conversation transcripts</li>
     * <li>Delete all shared artifacts owned by the user</li>
     * <li>Delete all schedules owned by the user</li>
     * <li>Pseudonymize database log entries</li>
     * <li>Pseudonymize audit ledger entries</li>
     * </ol>
     * <p>
     * Conversation IDs are resolved once before step 2 and reused across steps 2–4.
     * The journal, descriptor and checkpoint deletions run <em>before</em> the
     * conversation snapshots are deleted because they reference conversation IDs
     * that the bulk delete removes.
     *
     * @param userId
     *            the user to erase
     * @return result with per-store deletion/pseudonymization counts
     */
    public GdprDeletionResult deleteUserData(String userId) {
        String pseudonym = AuditHmac.pseudonymFor(userId);
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

        // Resolve conversation IDs once — needed by steps 2, 3, 4a, and 4b.
        // Must happen BEFORE the bulk snapshot delete (step 4b) which removes
        // the documents that getConversationIdsByUserId queries.
        List<String> conversationIds = List.of();
        try {
            conversationIds = conversationMemoryStore.getConversationIdsByUserId(userId);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to resolve conversation ids for user [%s]",
                    pseudonym);
        }

        try {
            if (attachmentStorageInstance.isResolvable()) {
                var attachmentStorage = attachmentStorageInstance.get();
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

        // 3. Delete HITL tool execution journal entries for user conversations.
        // Must run BEFORE the conversations themselves are deleted (step 4b), since
        // the journal entries reference conversation ids.
        long journalEntriesDeleted = 0;
        try {
            for (String convId : conversationIds) {
                journalEntriesDeleted += hitlToolJournalStore.deleteByConversationId(convId);
            }
            if (journalEntriesDeleted > 0) {
                LOGGER.infof("[GDPR] Deleted %d HITL tool journal entries [%s]",
                        journalEntriesDeleted, pseudonym);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete HITL tool journal entries [%s]",
                    pseudonym);
        }

        // 4a. Delete conversation descriptors (must run BEFORE or alongside
        // the snapshot delete so the ids are still available)
        long descriptorsDeleted = 0;
        try {
            for (String convId : conversationIds) {
                conversationDescriptorStore.deleteAllDescriptor(convId);
                descriptorsDeleted++;
            }
            if (descriptorsDeleted > 0) {
                LOGGER.infof("[GDPR] Deleted %d conversation descriptors [%s]",
                        descriptorsDeleted, pseudonym);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete conversation descriptors [%s]",
                    pseudonym);
        }

        // 4b. Delete conversation memory checkpoints.
        // A MemoryCheckpoint carries a copy of the conversation properties — the
        // same PII the conversation holds — so an erasure that stopped at the
        // snapshots left a full copy of it behind.
        long checkpointsDeleted = 0;
        try {
            for (String convId : conversationIds) {
                checkpointsDeleted += checkpointStore.deleteByConversationId(convId);
            }
            if (checkpointsDeleted > 0) {
                LOGGER.infof("[GDPR] Deleted %d conversation checkpoints [%s]",
                        checkpointsDeleted, pseudonym);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete conversation checkpoints [%s]",
                    pseudonym);
        }

        // 4c. Delete conversation memory snapshots
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

        // 5. Delete managed conversation mappings.
        // The intents are read FIRST: the cache is keyed by intent+userId, and once
        // the rows are gone there is no way left to work out which keys to evict.
        long mappingsDeleted = 0;
        List<String> mappedIntents = List.of();
        try {
            mappedIntents = userConversationStore.getAllForUser(userId).stream()
                    .map(UserConversation::getIntent)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to resolve conversation mapping intents [%s]",
                    pseudonym);
        }

        try {
            mappingsDeleted = userConversationStore.deleteAllForUser(userId);
            LOGGER.infof("[GDPR] Deleted %d conversation mappings [%s]",
                    mappingsDeleted, pseudonym);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete conversation mappings [%s]",
                    pseudonym);
        }

        // 5b. Evict the mappings from the read-through cache.
        // Deleting straight from the store bypassed it entirely, and the cache has
        // no TTL — so readUserConversation kept serving erased data for as long as
        // the process lived.
        try {
            for (String intent : mappedIntents) {
                userConversationCache.remove(userConversationCacheKey(intent, userId));
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to invalidate conversation mapping cache [%s]",
                    pseudonym);
        }

        // 5c. Delete group conversation transcripts. A GroupConversation stores the
        // user's id next to the verbatim discussion transcript.
        long groupConversationsDeleted = 0;
        try {
            if (groupConversationStoreInstance.isResolvable()) {
                groupConversationsDeleted = groupConversationStoreInstance.get().deleteAllForUser(userId);
                if (groupConversationsDeleted > 0) {
                    LOGGER.infof("[GDPR] Deleted %d group conversations [%s]",
                            groupConversationsDeleted, pseudonym);
                }
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete group conversations [%s]",
                    pseudonym);
        }

        // 5c2. Delete shared artifacts (I17). Artifacts carry ownerUserId (the
        // owning discussion's user) precisely so this sweep works even when the
        // parent discussion document is already gone.
        long sharedArtifactsDeleted = 0;
        try {
            if (sharedArtifactStoreInstance.isResolvable()) {
                sharedArtifactsDeleted = sharedArtifactStoreInstance.get().deleteAllForUser(userId);
                if (sharedArtifactsDeleted > 0) {
                    LOGGER.infof("[GDPR] Deleted %d shared artifacts [%s]",
                            sharedArtifactsDeleted, pseudonym);
                }
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete shared artifacts [%s]",
                    pseudonym);
        }

        // 5d. Delete schedules owned by the user. Left behind, they keep firing new
        // conversations under the erased identity — recreating the data forever.
        long schedulesDeleted = 0;
        try {
            schedulesDeleted = scheduleStore.deleteSchedulesByUserId(userId);
            if (schedulesDeleted > 0) {
                LOGGER.infof("[GDPR] Deleted %d schedules [%s]", schedulesDeleted, pseudonym);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to delete schedules [%s]", pseudonym);
        }

        // 6. Pseudonymize database logs (not deleted — operational data)
        long logsPseudonymized = 0;
        try {
            logsPseudonymized = databaseLogs.pseudonymizeByUserId(userId, pseudonym);
            LOGGER.infof("[GDPR] Pseudonymized %d log entries [%s]",
                    logsPseudonymized, pseudonym);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to pseudonymize logs [%s]",
                    pseudonym);
        }

        // 7. Pseudonymize audit ledger (retained under Art. 17(3)(e))
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
                + "memories=%d, conversations=%d, checkpoints=%d, mappings=%d, "
                + "groupConversations=%d, sharedArtifacts=%d, schedules=%d, logs=%d, audit=%d",
                pseudonym, memoriesDeleted, conversationsDeleted, checkpointsDeleted,
                mappingsDeleted, groupConversationsDeleted, sharedArtifactsDeleted, schedulesDeleted,
                logsPseudonymized, auditPseudonymized);

        // Write compliance event to immutable audit ledger
        var auditDetails = new LinkedHashMap<String, Object>();
        auditDetails.put("memoriesDeleted", memoriesDeleted);
        auditDetails.put("attachmentsDeleted", attachmentsDeleted);
        auditDetails.put("journalEntriesDeleted", journalEntriesDeleted);
        auditDetails.put("checkpointsDeleted", checkpointsDeleted);
        auditDetails.put("conversationsDeleted", conversationsDeleted);
        auditDetails.put("mappingsDeleted", mappingsDeleted);
        auditDetails.put("groupConversationsDeleted", groupConversationsDeleted);
        auditDetails.put("sharedArtifactsDeleted", sharedArtifactsDeleted);
        auditDetails.put("schedulesDeleted", schedulesDeleted);
        auditDetails.put("logsPseudonymized", logsPseudonymized);
        auditDetails.put("auditPseudonymized", auditPseudonymized);
        submitComplianceAuditEntry("GDPR_ERASURE", pseudonym, auditDetails);

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
        String pseudonym = AuditHmac.pseudonymFor(userId);
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

        // 5. Attachment metadata (no bytes — the payload is fetched via the
        // download API; portability requires the metadata, not the blobs).
        var attachmentEntries = new ArrayList<UserDataExport.AttachmentExportEntry>();
        try {
            if (attachmentStorageInstance.isResolvable()) {
                var store = attachmentStorageInstance.get();
                for (var convId : conversationMemoryStore.getConversationIdsByUserId(userId)) {
                    try {
                        for (var a : store.listByConversation(convId)) {
                            attachmentEntries.add(new UserDataExport.AttachmentExportEntry(
                                    convId, a.storageRef(), a.filename(), a.mimeType(), a.sizeBytes()));
                        }
                    } catch (Exception e) {
                        // Isolate per conversation so one bad lookup doesn't truncate
                        // the whole export (mirrors the conversation-snapshot block above).
                        LOGGER.warnf("[GDPR] Skipping attachments for conversation %s during export: %s",
                                convId, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to export attachment metadata [%s]", pseudonym);
        }

        LOGGER.infof("[GDPR] Export complete [%s]: memories=%d, "
                + "conversations=%d, managedConversations=%d, auditEntries=%d, attachments=%d",
                pseudonym, memories.size(), conversations.size(),
                managedConversations.size(), auditExportEntries.size(), attachmentEntries.size());

        // Write compliance event to immutable audit ledger
        submitComplianceAuditEntry("GDPR_EXPORT", pseudonym, Map.of(
                "memoriesExported", memories.size(),
                "conversationsExported", conversations.size(),
                "managedConversationsExported", managedConversations.size(),
                "auditEntriesExported", auditExportEntries.size(),
                "attachmentsExported", attachmentEntries.size()));

        return new UserDataExport(userId, Instant.now(), memories,
                conversations, managedConversations, auditExportEntries, attachmentEntries);
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
        String pseudonym = AuditHmac.pseudonymFor(userId);
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
        String pseudonym = AuditHmac.pseudonymFor(userId);
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

}
