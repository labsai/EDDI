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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
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
 * <p>
 * <strong>Restriction (Art. 18):</strong> {@link #isProcessingRestricted} reads
 * the store on every call by default — the verdict is <em>not</em> cached
 * unless a deployment opts in with {@link #RESTRICTION_CACHE_TTL_PROPERTY}. The
 * cache is node-local and has no cross-node invalidation, so a cached "not
 * restricted" is exactly what would let a second replica keep processing a user
 * who has just been restricted elsewhere. Caching is a single-node /
 * conversation-affinity optimisation, not a default.
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
    /**
     * Art. 18 restriction flags, short-TTL — <strong>off unless a deployment
     * switches it on</strong>. {@code isProcessingRestricted} is called at
     * conversation start and on every {@code say}/{@code sayStreaming}, so an
     * uncached lookup is a store round trip per turn on the hottest path in the
     * system — to read a flag only an admin endpoint ever changes. That is the
     * whole case for caching, and it is only sound on one node (or with
     * conversation affinity), which is why it is opt-in. Both admin endpoints
     * invalidate the entry explicitly, so the TTL is only a backstop for another
     * cluster node's write.
     * <p>
     * <strong>Null unless caching is switched on, which is not the default</strong>
     * — see {@link #RESTRICTION_CACHE_TTL_PROPERTY}. Every access goes through
     * {@link #cachedRestriction}, {@link #publishRestriction},
     * {@link #offerUnrestricted} or {@link #forgetRestriction}, so the disabled
     * case is handled in one place rather than at each call site.
     */
    private final ICache<String, Boolean> restrictionCache;

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
            ICacheFactory cacheFactory,
            @ConfigProperty(name = RESTRICTION_CACHE_TTL_PROPERTY,
                            defaultValue = RESTRICTION_CACHE_TTL_DEFAULT) long restrictionCacheTtlSeconds) {
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
        this.restrictionCache = restrictionCacheTtlSeconds <= 0
                ? null
                : cacheFactory.getCache(RESTRICTION_CACHE_NAME, Duration.ofSeconds(restrictionCacheTtlSeconds));
        if (this.restrictionCache == null) {
            LOGGER.infof("[GDPR] Art. 18 restriction caching is off (%s=%d); every check reads the store.",
                    RESTRICTION_CACHE_TTL_PROPERTY, restrictionCacheTtlSeconds);
        }
    }

    /**
     * Test seam: the shipped default (caching off), without a running
     * configuration.
     */
    GdprComplianceService(IUserMemoryStore userMemoryStore,
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
        this(userMemoryStore, conversationMemoryStore, userConversationStore, databaseLogs, auditStore,
                auditLedgerService, attachmentStorageInstance, hitlToolJournalStore, conversationDescriptorStore,
                checkpointStore, groupConversationStoreInstance, sharedArtifactStoreInstance, scheduleStore,
                cacheFactory, Long.parseLong(RESTRICTION_CACHE_TTL_DEFAULT));
    }

    /**
     * Name of the short-TTL cache behind {@link #isProcessingRestricted}.
     * <p>
     * Public because its sizing lives in {@code CacheFactory.CACHE_SIZES} under
     * this exact string and {@code CacheFactoryTest} asserts the two still meet: a
     * rename here with the map left alone silently drops the cache back to the
     * 1,000 default, which nothing else would report.
     */
    public static final String RESTRICTION_CACHE_NAME = "gdprProcessingRestrictions";

    /**
     * How long a restriction verdict may be reused without re-reading the store, in
     * seconds. <strong>0 — the default — switches the cache off entirely</strong>,
     * so every check reads the store.
     * <p>
     * The cache is node-local and there is no cross-node invalidation, so a
     * <em>negative</em> verdict cached here is the one thing that can let a
     * restricted user keep being processed: node A applies the Art. 18 restriction
     * and evicts its own entry, while node B goes on answering "not restricted"
     * from cache until the TTL expires — and, for the same reason, keeps answering
     * from cache through a store outage instead of failing closed. Explicit
     * invalidation in {@code restrictProcessing}/{@code unrestrictProcessing}
     * closes that window on the node that served the admin call only.
     * <p>
     * That fail-open window is why the shipped default is 0 rather than a short
     * TTL: a default has to be safe on every topology, and on a multi-replica
     * deployment a cached {@code false} suspends a legal control for the length of
     * the TTL. The cost of the default is one indexed lookup per turn.
     * <p>
     * Setting it above 0 is an <em>explicit single-node or conversation-affinity
     * optimisation</em>: it says "every turn of a conversation, and every admin
     * call, reaches the same node", which is what makes the explicit invalidation
     * sufficient. Do not set it on a cluster without affinity until cluster-wide
     * invalidation exists.
     */
    static final String RESTRICTION_CACHE_TTL_PROPERTY = "eddi.gdpr.restriction-cache-ttl-seconds";

    /**
     * Default for {@link #RESTRICTION_CACHE_TTL_PROPERTY}, as MicroProfile needs a
     * String. Zero: no verdict is cached unless a deployment asks for it.
     */
    static final String RESTRICTION_CACHE_TTL_DEFAULT = "0";

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

        // Every step below is allowed to fail without aborting the cascade — but a
        // failure must be visible in the response, or an admin files an Art. 17
        // request as fulfilled while the data is still there.
        var failedSteps = new ArrayList<String>();

        // 1. Delete user memories
        long memoriesDeleted = 0;
        try {
            long countedMemories = userMemoryStore.countEntries(userId);
            userMemoryStore.deleteAllForUser(userId);
            // The restriction flag lives in the same store and has just been deleted
            // with everything else, so a cached "restricted" verdict is now wrong.
            forgetRestriction(userId);
            // Assigned only once the delete returned: assigning before it made the
            // response claim N memories deleted when the delete had thrown and zero
            // were.
            memoriesDeleted = countedMemories;
            LOGGER.infof("[GDPR] Deleted %d user memory entries [%s]",
                    memoriesDeleted, pseudonym);
        } catch (Exception e) {
            recordFailure(failedSteps, "userMemories", e, pseudonym);
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
            recordFailure(failedSteps, "conversationIdLookup", e, pseudonym);
        }

        // The per-conversation loops below wrap EACH conversation in its own try.
        // With the try outside the loop, the first conversation whose attachment
        // (or journal, or descriptor, or checkpoint) delete threw ended the sweep:
        // every remaining conversation kept the PII the cascade exists to remove,
        // and the response still looked plausible. The export path in this class
        // already isolates per conversation for exactly this reason.
        try {
            if (attachmentStorageInstance.isResolvable()) {
                var attachmentStorage = attachmentStorageInstance.get();
                for (String convId : conversationIds) {
                    try {
                        attachmentsDeleted += attachmentStorage.deleteByConversation(convId);
                    } catch (Exception e) {
                        recordFailure(failedSteps, "attachments", e, pseudonym);
                    }
                }
                if (attachmentsDeleted > 0) {
                    LOGGER.infof("[GDPR] Deleted %d attachments across %d conversations [%s]",
                            attachmentsDeleted, conversationIds.size(), pseudonym);
                }
            }
        } catch (Exception e) {
            recordFailure(failedSteps, "attachments", e, pseudonym);
        }

        // 3. Delete HITL tool execution journal entries for user conversations.
        // Must run BEFORE the conversations themselves are deleted (step 4b), since
        // the journal entries reference conversation ids.
        long journalEntriesDeleted = 0;
        for (String convId : conversationIds) {
            try {
                journalEntriesDeleted += hitlToolJournalStore.deleteByConversationId(convId);
            } catch (Exception e) {
                recordFailure(failedSteps, "hitlToolJournal", e, pseudonym);
            }
        }
        if (journalEntriesDeleted > 0) {
            LOGGER.infof("[GDPR] Deleted %d HITL tool journal entries [%s]",
                    journalEntriesDeleted, pseudonym);
        }

        // 4a. Delete conversation descriptors (must run BEFORE or alongside
        // the snapshot delete so the ids are still available)
        long descriptorsDeleted = 0;
        for (String convId : conversationIds) {
            try {
                conversationDescriptorStore.deleteAllDescriptor(convId);
                descriptorsDeleted++;
            } catch (Exception e) {
                recordFailure(failedSteps, "conversationDescriptors", e, pseudonym);
            }
        }
        if (descriptorsDeleted > 0) {
            LOGGER.infof("[GDPR] Deleted %d conversation descriptors [%s]",
                    descriptorsDeleted, pseudonym);
        }

        // 4b. Delete conversation memory checkpoints.
        // A MemoryCheckpoint carries a copy of the conversation properties — the
        // same PII the conversation holds — so an erasure that stopped at the
        // snapshots left a full copy of it behind.
        long checkpointsDeleted = 0;
        for (String convId : conversationIds) {
            try {
                checkpointsDeleted += checkpointStore.deleteByConversationId(convId);
            } catch (Exception e) {
                recordFailure(failedSteps, "conversationCheckpoints", e, pseudonym);
            }
        }
        if (checkpointsDeleted > 0) {
            LOGGER.infof("[GDPR] Deleted %d conversation checkpoints [%s]",
                    checkpointsDeleted, pseudonym);
        }

        // 4c. Delete conversation memory snapshots
        long conversationsDeleted = 0;
        try {
            conversationsDeleted = conversationMemoryStore
                    .deleteConversationsByUserId(userId);
            LOGGER.infof("[GDPR] Deleted %d conversations [%s]",
                    conversationsDeleted, pseudonym);
        } catch (Exception e) {
            recordFailure(failedSteps, "conversations", e, pseudonym);
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
            recordFailure(failedSteps, "conversationMappingIntents", e, pseudonym);
        }

        try {
            mappingsDeleted = userConversationStore.deleteAllForUser(userId);
            LOGGER.infof("[GDPR] Deleted %d conversation mappings [%s]",
                    mappingsDeleted, pseudonym);
        } catch (Exception e) {
            recordFailure(failedSteps, "conversationMappings", e, pseudonym);
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
            recordFailure(failedSteps, "conversationMappingCache", e, pseudonym);
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
            recordFailure(failedSteps, "groupConversations", e, pseudonym);
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
            recordFailure(failedSteps, "sharedArtifacts", e, pseudonym);
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
            recordFailure(failedSteps, "schedules", e, pseudonym);
        }

        // 6. Pseudonymize database logs (not deleted — operational data)
        long logsPseudonymized = 0;
        try {
            logsPseudonymized = databaseLogs.pseudonymizeByUserId(userId, pseudonym);
            LOGGER.infof("[GDPR] Pseudonymized %d log entries [%s]",
                    logsPseudonymized, pseudonym);
        } catch (Exception e) {
            recordFailure(failedSteps, "databaseLogs", e, pseudonym);
        }

        // 7. Pseudonymize audit ledger (retained under Art. 17(3)(e))
        long auditPseudonymized = 0;
        try {
            auditPseudonymized = auditStore.pseudonymizeByUserId(userId, pseudonym);
            LOGGER.infof("[GDPR] Pseudonymized %d audit entries [%s]",
                    auditPseudonymized, pseudonym);
        } catch (Exception e) {
            recordFailure(failedSteps, "auditLedger", e, pseudonym);
        }

        var result = new GdprDeletionResult(userId, memoriesDeleted,
                conversationsDeleted, mappingsDeleted, logsPseudonymized,
                auditPseudonymized, attachmentsDeleted, journalEntriesDeleted,
                checkpointsDeleted, groupConversationsDeleted, sharedArtifactsDeleted,
                schedulesDeleted, failedSteps, Instant.now());

        if (result.complete()) {
            LOGGER.infof("[GDPR] Erasure cascade complete [%s]: "
                    + "memories=%d, conversations=%d, checkpoints=%d, mappings=%d, "
                    + "groupConversations=%d, sharedArtifacts=%d, schedules=%d, logs=%d, audit=%d",
                    pseudonym, memoriesDeleted, conversationsDeleted, checkpointsDeleted,
                    mappingsDeleted, groupConversationsDeleted, sharedArtifactsDeleted, schedulesDeleted,
                    logsPseudonymized, auditPseudonymized);
        } else {
            LOGGER.errorf("[GDPR] Erasure cascade INCOMPLETE [%s] — failed steps: %s. "
                    + "memories=%d, conversations=%d, checkpoints=%d, mappings=%d, "
                    + "groupConversations=%d, sharedArtifacts=%d, schedules=%d, logs=%d, audit=%d",
                    pseudonym, result.failedSteps(), memoriesDeleted, conversationsDeleted, checkpointsDeleted,
                    mappingsDeleted, groupConversationsDeleted, sharedArtifactsDeleted, schedulesDeleted,
                    logsPseudonymized, auditPseudonymized);
        }

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
        auditDetails.put("complete", result.complete());
        auditDetails.put("failedSteps", result.failedSteps());
        submitComplianceAuditEntry("GDPR_ERASURE", pseudonym, auditDetails);

        return result;
    }

    /**
     * Log a failed cascade step and record its name for the result.
     * <p>
     * Recorded once per step even when a per-conversation loop fails repeatedly —
     * the caller needs to know <em>which</em> category is incomplete, not how many
     * conversations were affected.
     * <p>
     * The <em>stack trace</em> is logged once per step too, for the same reason.
     * These calls sit inside per-conversation loops, so a user with 5,000
     * conversations during a store outage produced 20,000 ERROR stack traces for
     * one erasure request and buried the single line that matters. Repeats are
     * still reported — at WARN, with the cause's message — so nothing goes
     * unrecorded; only the identical trace is not repeated.
     *
     * @return whether this call logged the full stack trace (true on the first
     *         failure of a step) — so a test can assert the dedup without scraping
     *         the log
     */
    static boolean recordFailure(List<String> failedSteps, String step, Exception e, String pseudonym) {
        if (failedSteps.contains(step)) {
            LOGGER.warnf("[GDPR] Failed step '%s' again [%s]: %s", step, pseudonym, e.getMessage());
            return false;
        }
        LOGGER.errorf(e, "[GDPR] Failed step '%s' [%s]", step, pseudonym);
        failedSteps.add(step);
        return true;
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

        // Resolved ONCE and reused by the conversation and attachment blocks below.
        // Both used to call getConversationIdsByUserId separately, which is a second
        // full lookup for the same list.
        List<String> conversationIds = List.of();
        try {
            conversationIds = conversationMemoryStore.getConversationIdsByUserId(userId);
        } catch (Exception e) {
            LOGGER.errorf(e, "[GDPR] Failed to resolve conversation ids for export [%s]", pseudonym);
        }

        // 2. Conversation snapshots (lightweight export)
        var conversations = new ArrayList<UserDataExport.ConversationExportEntry>();
        // Each snapshot is a full document load and the whole bundle is assembled in
        // memory on the request thread, so the same cap that already bounds the audit
        // entries bounds these. A user with thousands of conversations otherwise held
        // a JAX-RS worker for minutes and produced a response measured in hundreds of
        // megabytes.
        // Residue, deliberately not fixed here: *which* conversations survive the cap
        // is whatever order the store returned. MongoDB's natural order is not
        // insertion order, so a truncated bundle is an arbitrary 200 of 1,200 rather
        // than the oldest or the newest. The bundle says it is truncated and gives the
        // total, which is what makes the response honest; making the subset itself
        // predictable needs an ordered projection in both conversation stores.
        int totalConversations = conversationIds.size();
        int exportable = Math.min(totalConversations, CONVERSATION_EXPORT_LIMIT);
        // Reported in the bundle, not only in the log: a subject access request that
        // silently omits 200 of 1,200 conversations while answering 200 OK is the
        // same misreporting the erasure half of this class answers 207 for.
        boolean conversationsTruncated = totalConversations > CONVERSATION_EXPORT_LIMIT;
        if (conversationsTruncated) {
            LOGGER.warnf("[GDPR] User has %d conversations; exporting the first %d and marking the bundle truncated [%s]",
                    totalConversations, CONVERSATION_EXPORT_LIMIT, pseudonym);
        }
        for (var convId : conversationIds.subList(0, exportable)) {
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
                for (var convId : conversationIds) {
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
                + "conversations=%d of %d, truncated=%s, managedConversations=%d, auditEntries=%d, attachments=%d",
                pseudonym, memories.size(), conversations.size(), totalConversations, conversationsTruncated,
                managedConversations.size(), auditExportEntries.size(), attachmentEntries.size());

        // Write compliance event to immutable audit ledger
        submitComplianceAuditEntry("GDPR_EXPORT", pseudonym, Map.of(
                "memoriesExported", memories.size(),
                "conversationsExported", conversations.size(),
                "totalConversations", totalConversations,
                "conversationsTruncated", conversationsTruncated,
                "managedConversationsExported", managedConversations.size(),
                "auditEntriesExported", auditExportEntries.size(),
                "attachmentsExported", attachmentEntries.size()));

        return new UserDataExport(userId, Instant.now(), memories,
                conversations, managedConversations, auditExportEntries, attachmentEntries,
                totalConversations, conversationsTruncated);
    }

    // === Right to Restriction of Processing (GDPR Art. 18) ===

    private static final String RESTRICTION_KEY = "_gdpr_processing_restricted";
    private static final int AUDIT_EXPORT_LIMIT = 10_000;

    /**
     * Cap on conversation snapshots in one export bundle — the counterpart of
     * {@link #AUDIT_EXPORT_LIMIT}, which already bounded the audit half of the same
     * response while the conversation half was unbounded.
     */
    static final int CONVERSATION_EXPORT_LIMIT = 1_000;

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
            publishRestriction(userId, true);
        } catch (Exception e) {
            // Drop any cached verdict rather than leaving a stale "not restricted"
            // in place after a half-applied write.
            forgetRestriction(userId);
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
            // Publish the lift, do not merely forget it. Where caching is switched
            // on, a cached "true" that outlives the removal keeps answering
            // "restricted" for the whole TTL on this node, so a user an admin has just
            // cleared keeps receiving 403 "Processing is restricted for this user
            // (GDPR Art. 18)" on every turn — a false legal statement about someone
            // who is no longer restricted.
            publishRestriction(userId, false);
        } catch (Exception e) {
            forgetRestriction(userId);
            LOGGER.errorf(e, "[GDPR] Failed to unrestrict processing [%s]",
                    pseudonym);
            throw new RuntimeException("Failed to unrestrict processing", e);
        }

        submitComplianceAuditEntry("GDPR_UNRESTRICT", pseudonym, Map.of());
    }

    /**
     * Drop a cached restriction verdict. Null-safe because it is called from catch
     * blocks, where throwing would replace the failure being reported (Caffeine
     * rejects a null key outright).
     */
    private void forgetRestriction(String userId) {
        if (userId != null && restrictionCache != null) {
            restrictionCache.remove(userId);
        }
    }

    /**
     * The published verdict for a user, or null when nothing is published — which
     * is always the case while caching is switched off, so the caller reads the
     * store every time.
     */
    private Boolean cachedRestriction(String userId) {
        return restrictionCache == null ? null : restrictionCache.get(userId);
    }

    /** Force a verdict into the cache, if there is one. */
    private void publishRestriction(String userId, boolean restricted) {
        if (restrictionCache != null) {
            restrictionCache.put(userId, restricted);
        }
    }

    /**
     * Offer "not restricted" without displacing a restriction another writer
     * already published; returns the value that ends up published, or null when
     * caching is off and the caller should simply use its own store read.
     */
    private Boolean offerUnrestricted(String userId) {
        return restrictionCache == null ? null : restrictionCache.putIfAbsent(userId, false);
    }

    /**
     * Check if processing is restricted for a user.
     * <p>
     * <strong>By default this reads the store on every call.</strong>
     * {@link #RESTRICTION_CACHE_TTL_PROPERTY} defaults to 0, so no verdict is
     * cached: a restriction applied on any node takes effect on every node at once,
     * and a store outage always raises
     * {@link ProcessingRestrictionUnavailableException} rather than being absorbed
     * by a cached verdict. The cost is one indexed lookup per turn — this runs at
     * conversation start and again on every {@code say}/{@code sayStreaming}, which
     * is the hottest path in the system, to read a flag only an admin endpoint ever
     * changes.
     * <p>
     * A deployment that is a single node, or a cluster with conversation affinity,
     * may buy that lookup back by setting the property above 0. The cache is then
     * node-local, and {@link #restrictProcessing} and {@link #unrestrictProcessing}
     * publish through it so the node serving the admin call applies the change on
     * the very next turn.
     * <p>
     * With caching on, a miss publishes the value read from the store
     * <em>monotonically toward restriction</em>: a {@code true} overwrites whatever
     * is cached, a {@code false} is only offered and yields to any value already
     * there. This method is not the only writer — concurrent misses publish too —
     * so neither a plain {@code put} nor a plain {@code putIfAbsent} is safe on its
     * own; see the comment at the publish site. The caller always gets a value that
     * is at least as restrictive as its own store read.
     *
     * @param userId
     *            the user to check
     * @return true if a processing restriction is in effect
     * @throws ProcessingRestrictionUnavailableException
     *             if the flag cannot be read at all. Still fail-closed — the caller
     *             must not process the turn — but reported as the availability
     *             failure it is rather than as a restriction nobody applied.
     */
    public boolean isProcessingRestricted(String userId) {
        if (userId == null) {
            // Nothing to look up and nothing to cache — Caffeine rejects a null key
            // outright, so this must not reach the cache.
            return false;
        }
        Boolean cached = cachedRestriction(userId);
        if (cached != null) {
            return cached;
        }
        try {
            var entry = userMemoryStore.getByKey(userId, RESTRICTION_KEY);
            boolean restricted = entry.isPresent() && "true".equals(String.valueOf(entry.get().value()));
            // Publish monotonically toward restriction: within one TTL window a
            // "restricted" observation always wins, whoever else is writing.
            //
            // This read is not atomic with restrictProcessing, and it is not the only
            // writer either — two concurrent misses of this very method also publish.
            // A plain put loses an administrative restriction applied mid-read (T1
            // reads false, an admin writes the row and publishes true, T1 overwrites
            // it with the stale false and the node processes a restricted user for
            // the whole TTL). A plain putIfAbsent loses the opposite way,
            // which is worse: T2 misses and publishes false, T1 misses, reads true
            // from the store, and its putIfAbsent is refused — so T1 would return the
            // sibling's false and process a turn its OWN read said must be blocked.
            //
            // So: a true is forced into the cache and returned; a false is only
            // offered, and yields to whatever is already published (which may be a
            // restriction from restrictProcessing or from a concurrent reader).
            // Erring toward "restricted" for at most one TTL is the safe direction
            // for an Art. 18 legal control — an unrestrictProcessing that loses this
            // race delays a release by 30s, while the other direction processes data
            // that must not be processed.
            if (restricted) {
                publishRestriction(userId, true);
                return true;
            }
            Boolean published = offerUnrestricted(userId);
            return published != null ? published : false;
        } catch (Exception e) {
            // Still fail-closed — the turn does not proceed — but no longer
            // fail-dishonest. Returning true here told the user "Processing is
            // restricted for this user (GDPR Art. 18)" with a 403 whenever the store
            // hiccuped, which is a false statement about them and hides the outage
            // from 5xx-based monitoring.
            LOGGER.errorf("[GDPR] Failed to check processing restriction for user " +
                    "(blocking processing): %s", e.getMessage());
            throw new ProcessingRestrictionUnavailableException(
                    "Cannot determine processing-restriction status right now; the request was not processed", e);
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
