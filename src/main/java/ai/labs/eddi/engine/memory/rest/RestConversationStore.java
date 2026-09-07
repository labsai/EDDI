/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.IResourceStore.ResourceModifiedException;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;

import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.ConversationStatus;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.ThreadContext;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertSimpleConversationMemory;
import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.redactRawPendingToolCallsForRead;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RestUtilities.extractResourceId;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.String.format;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestConversationStore implements IRestConversationStore {
    public static final String DESCRIPTOR_TYPE = "ai.labs.conversation";

    /**
     * Conversation descriptors are written once and then updated in place, so they
     * never leave version 0 — every other read in this class hard-codes it too.
     */
    private static final int CONVERSATION_DESCRIPTOR_VERSION = 0;

    /**
     * Upper bound on how many descriptors an owner-filtered listing will scan
     * before giving up, so a caller who owns few/none of a large shared store
     * cannot turn one list request into a full-collection scan. This is the single
     * owner-scan budget in the system — the MCP {@code list_conversations} tool
     * delegates to this endpoint rather than scanning itself. Admins /
     * auth-disabled callers are not filtered and never reach this bound.
     */
    private static final int MAX_OWNER_SCAN = 500;

    /**
     * Smallest age (in days) the deployment-wide retention sweep accepts. Zero used
     * to be legal and meant "every ended conversation, regardless of age" — one
     * query parameter away from wiping the deployment. Values below this bound are
     * rejected on the REST path and treated as "retention disabled" by the
     * scheduled sweep.
     */
    static final int MIN_RETENTION_DAYS = 1;

    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationService conversationService;
    private final IUserMemoryStore userMemoryStore;
    private final IRuntime runtime;
    private final ConversationAccessGuard conversationAccessGuard;
    private final Integer deleteEndedConversationsOnceOlderThanDays;
    private final Integer deleteMemoriesOlderThanDays;
    private final Instance<IAttachmentStore> attachmentStorageInstance;

    // Field-injected per the AGENTS.md metrics pattern; the SimpleMeterRegistry
    // default keeps it non-null in unit tests that construct this bean directly
    // (CDI overwrites it with the real registry in production).
    @Inject
    MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static final Logger log = Logger.getLogger(RestConversationStore.class);

    @Inject
    // @formatter:off
    public RestConversationStore(
            IDocumentDescriptorStore documentDescriptorStore,
            IConversationDescriptorStore conversationDescriptorStore,
            IConversationMemoryStore conversationMemoryStore,
            IConversationService conversationService,
            IUserMemoryStore userMemoryStore,
            IRuntime runtime,
            ConversationAccessGuard conversationAccessGuard,
            @ConfigProperty(name = "eddi.conversations.deleteEndedConversationsOnceOlderThanDays")
            Integer deleteEndedConversationsOnceOlderThanDays,
            @ConfigProperty(name = "eddi.usermemories.deleteOlderThanDays")
            Integer deleteMemoriesOlderThanDays,
            Instance<IAttachmentStore> attachmentStorageInstance) {
    // @formatter:on

        this.documentDescriptorStore = documentDescriptorStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationService = conversationService;
        this.userMemoryStore = userMemoryStore;
        this.runtime = runtime;
        this.conversationAccessGuard = conversationAccessGuard;
        this.deleteEndedConversationsOnceOlderThanDays = deleteEndedConversationsOnceOlderThanDays;
        this.deleteMemoriesOlderThanDays = deleteMemoriesOlderThanDays;
        this.attachmentStorageInstance = attachmentStorageInstance;
    }

    @Override
    public List<ConversationDescriptor> readConversationDescriptors(Integer index, Integer limit, String filter, String conversationId,
                                                                    String agentId, Integer agentVersion, ConversationState conversationState,
                                                                    ConversationDescriptor.ViewState viewState) {
        // Sanitize pagination parameters to prevent overflow (CodeQL: integer-overflow)
        if (index == null || index < 0) {
            index = 0;
        }
        if (limit == null || limit < 1) {
            limit = 20;
        }
        if (limit > 100) {
            limit = 100;
        }

        // Owner-scoping: a non-admin caller may only enumerate their own
        // conversations. The descriptor store has no owner-scoped query, so we
        // post-filter each descriptor by its resolved owner. Admins (and any caller
        // when authorization is disabled) see all — resolved once, up front, so the
        // per-row check is skipped entirely on that path. The existing do-while
        // back-fills across pages so a filtered-out row does not starve a personal
        // list, but that back-fill is bounded by MAX_OWNER_SCAN so a caller who owns
        // few/none of a large shared store cannot force a full-collection scan
        // (descriptors come back most-recent-first, so a typical caller's own
        // conversations fall well within the budget).
        final boolean seesAllConversations = conversationAccessGuard.seesAllConversations();

        try {
            List<ConversationDescriptor> conversationDescriptors;
            List<ConversationDescriptor> retConversationDescriptors = new LinkedList<>();
            int scannedDescriptors = 0;

            do {
                conversationDescriptors = readConversationDescriptors(index, limit, filter);
                if (conversationDescriptors.isEmpty() && index == 0 && !isNullOrEmpty(filter)) {
                    conversationDescriptors = readConversationDescriptors(index, limit, null);
                }

                for (var conversationDescriptor : conversationDescriptors) {
                    // Enforce the scan budget per-descriptor, not just per-page, so a
                    // non-admin scan honours MAX_OWNER_SCAN exactly rather than
                    // overrunning by up to a page (and the exhaustion metric fires at
                    // the documented bound).
                    if (!seesAllConversations && scannedDescriptors >= MAX_OWNER_SCAN) {
                        break;
                    }
                    scannedDescriptors++;
                    try {
                        URI resourceUri = conversationDescriptor.getResource();
                        var conversationResourceId = extractResourceId(resourceUri);
                        if (conversationResourceId == null) {
                            log.warn(format("conversationResourceId was null, this should never happen. (%s)", resourceUri));
                            continue;
                        }

                        // Ownership gate — split around the expensive snapshot load so a
                        // foreign conversation is skipped WITHOUT loading its memory
                        // document. Every conversation since v5.1.6 records its owner on
                        // the descriptor, so decide here for the common case; only a
                        // legacy row with no recorded owner falls through to the
                        // post-populate re-check below (populate resolves its owner from
                        // the snapshot). A fully unowned (null both ways) conversation
                        // stays visible, matching OwnershipValidator.requireOwnerOrAdmin.
                        String recordedOwner = conversationDescriptor.getUserId();
                        boolean ownerRecorded = !isNullOrEmpty(recordedOwner);
                        if (!seesAllConversations && ownerRecorded
                                && !conversationAccessGuard.canAccessConversation(recordedOwner)) {
                            continue;
                        }

                        populateDataToDescriptor(conversationDescriptor, conversationResourceId);

                        // Legacy safety net: the descriptor recorded no owner; populate
                        // has now resolved it from the snapshot (pre-v5.1.6 fallback), so
                        // re-check before returning it.
                        if (!seesAllConversations && !ownerRecorded
                                && !conversationAccessGuard.canAccessConversation(conversationDescriptor.getUserId())) {
                            continue;
                        }

                        // Agent filtering uses the agentResource URI (which contains
                        // the agent's ID), NOT the conversation's resource URI.
                        if (!isNullOrEmpty(agentId)) {
                            URI agentResourceUri = conversationDescriptor.getAgentResource();
                            var agentResourceId = agentResourceUri != null ? extractResourceId(agentResourceUri) : null;
                            if (agentResourceId == null || !agentId.equals(agentResourceId.getId())) {
                                continue;
                            }

                            if (!isNullOrEmpty(agentVersion) && !agentVersion.equals(agentResourceId.getVersion())) {
                                continue;
                            }
                        }

                        if (!isNullOrEmpty(conversationState)) {
                            if (!conversationState.equals(conversationDescriptor.getConversationState())) {
                                continue;
                            }
                        }

                        if (!isNullOrEmpty(viewState)) {
                            if (!viewState.equals(conversationDescriptor.getViewState())) {
                                continue;
                            }
                        }

                        retConversationDescriptors.add(conversationDescriptor);
                    } catch (Exception e) {
                        // Skip individual corrupted/orphaned descriptors gracefully
                        log.debug(format("Skipping descriptor due to error: %s", e.getMessage()));
                    }
                }

                if (index < Integer.MAX_VALUE) {
                    index++;
                } else {
                    break; // prevent integer overflow
                }
                // Bound the owner-filtered back-fill: stop once the scan budget is spent
                // (admins/auth-disabled are never filtered, so they page only as far as
                // filling `limit` requires and never hit this).
            } while (!conversationDescriptors.isEmpty() && retConversationDescriptors.size() < limit
                    && (seesAllConversations || scannedDescriptors < MAX_OWNER_SCAN));

            // Observability: a non-admin listing that stopped on the scan budget with
            // fewer than `limit` results may have owned conversations beyond what was
            // scanned (the List return type can't signal that truncation to the
            // caller). Count it so a persistently-truncated user is not invisible.
            if (!seesAllConversations && retConversationDescriptors.size() < limit && scannedDescriptors >= MAX_OWNER_SCAN) {
                meterRegistry.counter("eddi.conversations.listing.owner_scan_exhausted").increment();
            }

            return retConversationDescriptors;

        } catch (ResourceStoreException | ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    private List<ConversationDescriptor> readConversationDescriptors(Integer index, Integer limit, String filter)
            throws ResourceStoreException, ResourceNotFoundException {

        return conversationDescriptorStore.readDescriptors(DESCRIPTOR_TYPE, filter, index, limit, false);
    }

    private void populateDataToDescriptor(ConversationDescriptor conversationDescriptor, IResourceId resourceId)
            throws ResourceStoreException, ResourceNotFoundException {

        try {
            var memorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(resourceId.getId());

            if (memorySnapshot == null) {
                log.warn(format("Memory snapshot not found for conversation [%s, %s]. Descriptor is orphaned.",
                        resourceId.getId(), resourceId.getVersion()));
                return;
            }

            if (conversationDescriptor.getUserId() == null) {
                // fallback for older conversations pre v5.1.6
                conversationDescriptor.setUserId(memorySnapshot.getUserId());
            }
            conversationDescriptor.setEnvironment(memorySnapshot.getEnvironment());
            conversationDescriptor.setConversationStepSize(memorySnapshot.getConversationSteps().size());
            conversationDescriptor.setConversationState(memorySnapshot.getConversationState());
            if (isNullOrEmpty(conversationDescriptor.getAgentName())) {
                var documentDescriptor = documentDescriptorStore.readDescriptor(memorySnapshot.getAgentId(), memorySnapshot.getAgentVersion());

                conversationDescriptor.setAgentName(documentDescriptor.getName());
            }

        } catch (ResourceNotFoundException e) {
            String message = "Resource referenced in descriptor does not exist (anymore) [%s, %s]. ";
            message += "Ignoring this resource.";
            log.warn(format(message, resourceId.getId(), resourceId.getVersion()));
        }
    }

    @Override
    public ConversationMemorySnapshot readRawConversationLog(String conversationId) {
        checkNotNull(conversationId, "conversationId");
        // Owner-or-admin, the same gate RestAgentEngine/RestAttachmentUpload apply.
        // Without it any authenticated caller could read any conversation by id — the
        // raw surface returns the full memory document, properties included.
        conversationAccessGuard.requireConversationOwner(conversationId);

        try {
            // Project the pending tool-call batch down to names-only before returning:
            // this generic raw-read surface is reachable by any authenticated caller
            // and must NOT leak the unredacted tool arguments or the frozen LLM
            // transcript of a paused conversation — those stay behind the approver-only
            // detail=full gate. Mirrors fix #4's confinement on the Simple surface.
            // requireSnapshot, not the raw load: a missing conversation returned null
            // here, which JAX-RS renders as 204 No Content — indistinguishable from a
            // conversation that exists and happens to be empty.
            return redactRawPendingToolCallsForRead(requireSnapshot(conversationId));
        } catch (ResourceStoreException | ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public SimpleConversationMemorySnapshot readSimpleConversationLog(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                                                                      List<String> returningFields) {
        checkNotNull(conversationId, "conversationId");
        checkNotNull(returnDetailed, "returnDetailed");
        checkNotNull(returnCurrentStepOnly, "returnCurrentStepOnly");
        conversationAccessGuard.requireConversationOwner(conversationId);

        try {
            return convertSimpleConversationMemory(requireSnapshot(conversationId), returnDetailed, returnCurrentStepOnly);

        } catch (ResourceStoreException | ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * The snapshot, or a 404 — never a {@code null} for the caller to dereference.
     *
     * <p>
     * {@code loadConversationMemorySnapshot} answers {@code null} for a
     * conversation that is not there, and the read paths went straight on to call
     * {@code getEnvironment()} on it. So a deleted or mistyped conversation id
     * produced a {@link NullPointerException}, which reached the client as
     * {@code 500 Internal Server Error} plus an error id — from endpoints whose own
     * {@code @APIResponse} promised a 404, and which the troubleshooting
     * documentation tells people to call precisely when something has already gone
     * wrong.
     * </p>
     *
     * <p>
     * Throws the checked {@code ResourceNotFoundException} rather than
     * {@code ConversationNotFoundException}, which {@code ConversationService}'s
     * twin uses: both read endpoints here already declare it on
     * {@link IRestConversationStore}, so this is the contract they publish. Both
     * map to 404.
     * </p>
     */
    private ConversationMemorySnapshot requireSnapshot(String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {
        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        if (snapshot == null) {
            throw new ResourceNotFoundException(
                    String.format("No conversation found! (conversationId=%s)", sanitize(conversationId)));
        }
        return snapshot;
    }

    @Override
    public void deleteConversationLog(String conversationId, Boolean deletePermanently)
            throws ResourceStoreException, ResourceNotFoundException {
        checkNotNull(conversationId, "conversationId");
        // Deletion is irreversible, so the gate runs before anything is touched —
        // the soft-delete path included, which still removes the conversation from
        // every listing.
        conversationAccessGuard.requireConversationOwner(conversationId);

        if (deletePermanently) {
            // If the conversation is a live pending approval, resolve the HITL state
            // BEFORE removing the document: endConversation disarms the armed
            // timeout schedule (otherwise it fires later against a deleted
            // conversation, logs "Conversation not found", and leaves a dead
            // schedule row), clears the bookmark, writes the hitl.approval
            // cancellation audit, and invalidates the cached AWAITING_HUMAN state
            // (which getConversationState would otherwise keep serving for a
            // nonexistent conversation until TTL).
            try {
                if (conversationMemoryStore.getConversationState(conversationId) == ConversationState.AWAITING_HUMAN) {
                    // G4: attribute the pause-terminating end (this store has no request
                    // principal and also runs from scheduled cleanup).
                    conversationService.endConversation(conversationId, "system:admin-end");
                }
            } catch (Exception e) {
                log.warn(format("HITL cleanup before permanent delete failed for conversation %s: %s",
                        sanitize(conversationId), e.getMessage()));
            }

            deleteAttachmentsForConversation(conversationId);
            conversationMemoryStore.deleteConversationMemorySnapshot(conversationId);
            conversationDescriptorStore.deleteAllDescriptor(conversationId);
            log.info(format("Conversation has been permanently deleted (conversationId=%s)", sanitize(conversationId)));
        } else {
            softDelete(conversationId);
        }
    }

    /**
     * Retires the conversation's descriptor so the conversation disappears from
     * every listing, while its memory snapshot and attachments stay on the server.
     *
     * <p>
     * This branch used to do <em>nothing at all</em>. A comment claimed a
     * {@code DocumentDescriptorInterceptor} would mark the descriptor deleted
     * "regardless of whether it has been permanently deleted or not", but no such
     * interceptor exists anywhere in the code base — so {@code DELETE
     * /conversationstore/conversations/{id}} (whose {@code deletePermanently}
     * defaults to {@code false}) answered 204 and left the row untouched, still
     * {@code "deleted": false} and still listed. The Manager's delete dialog
     * describes exactly the behaviour implemented here and then reports
     * "Conversation deleted", so the honest-looking answer was the wrong one: users
     * saw a success toast next to a conversation that was still there.
     * </p>
     *
     * <p>
     * {@code deleteDescriptor} archives the descriptor into its history collection
     * with {@code deleted=true} and drops the live row, which is what the
     * {@code includeDeleted=false} listings filter on. The snapshot itself is
     * deliberately untouched — that is the whole distinction from the permanent
     * path, and it is what lets the retention sweep and GDPR erasure still find the
     * data.
     * </p>
     */
    private void softDelete(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        try {
            conversationDescriptorStore.deleteDescriptor(conversationId, CONVERSATION_DESCRIPTOR_VERSION);
            log.info(format("Conversation has been deleted (conversationId=%s)", sanitize(conversationId)));
        } catch (ResourceModifiedException e) {
            // The descriptor moved under us — surface it rather than reporting a
            // deletion that did not happen.
            throw new ResourceStoreException(
                    format("Could not delete conversation %s: its descriptor was modified concurrently", sanitize(conversationId)), e);
        }
    }

    @Scheduled(every = "24h")
    public void deleteEndedConversationsOlderThanXDays() {
        if (deleteEndedConversationsOnceOlderThanDays == null || deleteEndedConversationsOnceOlderThanDays < MIN_RETENTION_DAYS) {
            log.debugf("Ended-conversation retention sweep disabled (deleteEndedConversationsOnceOlderThanDays < %d)", MIN_RETENTION_DAYS);
            return;
        }

        runtime.submitCallable(() -> {
            try {
                var amountOfEndedConversations = permanentlyDeleteEndedConversationLogs(deleteEndedConversationsOnceOlderThanDays);

                if (amountOfEndedConversations > 0) {
                    log.info(format("Successfully deleted %s conversations, which were older than %s days", amountOfEndedConversations,
                            deleteEndedConversationsOnceOlderThanDays));
                }
            } catch (ResourceStoreException | ResourceNotFoundException e) {
                log.error(e.getLocalizedMessage(), e);
            }
            return null;
        }, ThreadContext.getResources());
    }

    @Scheduled(every = "24h", delayed = "2m")
    void cleanupOldUserMemories() {
        if (deleteMemoriesOlderThanDays == null || deleteMemoriesOlderThanDays <= 0) {
            return; // Disabled
        }

        runtime.submitCallable(() -> {
            try {
                long deleted = userMemoryStore.deleteOlderThan(deleteMemoriesOlderThanDays);
                if (deleted > 0) {
                    log.infof("User memory retention: deleted %d entries older than %d days",
                            deleted, deleteMemoriesOlderThanDays);
                }
            } catch (Exception e) {
                log.error("User memory retention cleanup failed", e);
            }
            return null;
        }, ThreadContext.getResources());
    }

    @Override
    public Integer permanentlyDeleteEndedConversationLogs(Integer deleteOlderThanDays)
            throws ResourceStoreException, ResourceNotFoundException {

        if (deleteOlderThanDays == null || deleteOlderThanDays < MIN_RETENTION_DAYS) {
            throw new BadRequestException(
                    "deleteOlderThanDays must be at least " + MIN_RETENTION_DAYS
                            + " — a smaller value would permanently delete every ended conversation in the deployment");
        }

        int amountOfEndedConversations = 0;
        var deleteOlderThanThisDate = Date.from(Instant.now().minus(Duration.ofDays(deleteOlderThanDays)));
        var endedConversationIds = conversationMemoryStore.getEndedConversationIds();

        for (var endedConversationId : endedConversationIds) {
            try {
                var descriptor = documentDescriptorStore.readDescriptor(endedConversationId, CONVERSATION_DESCRIPTOR_VERSION);
                if (descriptor.getLastModifiedOn().before(deleteOlderThanThisDate)) {
                    documentDescriptorStore.deleteAllDescriptor(endedConversationId);
                    conversationDescriptorStore.deleteAllDescriptor(endedConversationId);
                    deleteAttachmentsForConversation(endedConversationId);
                    conversationMemoryStore.deleteConversationMemorySnapshot(endedConversationId);
                    amountOfEndedConversations++;
                }
            } catch (ResourceNotFoundException e) {
                conversationDescriptorStore.deleteAllDescriptor(endedConversationId);
                deleteAttachmentsForConversation(endedConversationId);
                conversationMemoryStore.deleteConversationMemorySnapshot(endedConversationId);
                log.debug(format("Cleaned up orphaned conversation memory without descriptor (id=%s)", endedConversationId));
            }
        }

        return amountOfEndedConversations;
    }

    @Override
    public List<ConversationStatus> getActiveConversations(String agentId, Integer agentVersion)
            throws ResourceStoreException, ResourceNotFoundException {
        checkNotNull(agentId, "agentId");
        checkNotNull(agentVersion, "agentVersion");

        List<ConversationMemorySnapshot> conversationMemorySnapshots;
        List<ConversationStatus> conversationStatuses = new LinkedList<>();

        conversationMemorySnapshots = conversationMemoryStore.loadActiveConversationMemorySnapshot(agentId, agentVersion);
        for (var snapshot : conversationMemorySnapshots) {
            ConversationStatus conversationStatus = new ConversationStatus();
            String conversationId = snapshot.getId();
            conversationStatus.setConversationId(conversationId);
            conversationStatus.setAgentId(agentId);
            conversationStatus.setAgentVersion(agentVersion);
            conversationStatus.setConversationState(snapshot.getConversationState());
            var conversationDescriptor = conversationDescriptorStore.readDescriptor(conversationId, CONVERSATION_DESCRIPTOR_VERSION);
            conversationStatus.setLastInteraction(conversationDescriptor.getLastModifiedOn());
            conversationStatuses.add(conversationStatus);
        }

        return conversationStatuses;
    }

    @Override
    public Response endActiveConversations(List<ConversationStatus> conversationStatuses) {
        try {
            for (ConversationStatus conversationStatus : conversationStatuses) {
                String conversationId = conversationStatus.getConversationId();

                // A paused (AWAITING_HUMAN) conversation must be ended through the
                // HITL-aware service path: a raw setConversationState(ENDED) would
                // leave the armed timeout schedule (a later stale fire logs errors),
                // keep the bookmark, skip the hitl.approval cancellation audit, and
                // miss the in-flight-resume signal that stops a concurrent resume
                // from persisting its snapshot back over the ENDED state.
                if (conversationStatus.getConversationState() == ConversationState.AWAITING_HUMAN) {
                    // G4: attribute the pause-terminating end (admin bulk-end path).
                    conversationService.endConversation(conversationId, "system:admin-end");
                } else {
                    conversationMemoryStore.setConversationState(conversationId, ConversationState.ENDED);
                }

                ConversationDescriptor conversationDescriptor = conversationDescriptorStore.readDescriptor(conversationId,
                        CONVERSATION_DESCRIPTOR_VERSION);
                conversationDescriptor.setConversationState(ConversationState.ENDED);
                conversationDescriptorStore.setDescriptor(conversationId, CONVERSATION_DESCRIPTOR_VERSION, conversationDescriptor);

                log.info(format("conversation (%s) has been set to ENDED", conversationId));
            }

            return Response.ok().build();
        } catch (ResourceStoreException | ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * Delete any binary attachments stored for a conversation. Silently skips if no
     * attachment storage is configured.
     */
    private void deleteAttachmentsForConversation(String conversationId) {
        if (attachmentStorageInstance.isResolvable()) {
            try {
                long deleted = attachmentStorageInstance.get().deleteByConversation(conversationId);
                if (deleted > 0) {
                    log.debug(format("Deleted %d attachments for conversation %s", deleted, conversationId));
                }
            } catch (Exception e) {
                log.warn(format("Failed to delete attachments for conversation %s: %s",
                        conversationId, e.getMessage()));
            }
        }
    }
}
