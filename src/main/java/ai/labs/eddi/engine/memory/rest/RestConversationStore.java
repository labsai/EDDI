/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
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
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
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
        // already re-pages until it fills `limit` (or the store is exhausted), so a
        // filtered-out row is naturally back-filled from later pages — a caller's own
        // conversations are never starved just because newer pages belong to others.
        final boolean seesAllConversations = conversationAccessGuard.seesAllConversations();

        try {
            List<ConversationDescriptor> conversationDescriptors;
            List<ConversationDescriptor> retConversationDescriptors = new LinkedList<>();

            do {
                conversationDescriptors = readConversationDescriptors(index, limit, filter);
                if (conversationDescriptors.isEmpty() && index == 0 && !isNullOrEmpty(filter)) {
                    conversationDescriptors = readConversationDescriptors(index, limit, null);
                }

                for (var conversationDescriptor : conversationDescriptors) {
                    try {
                        URI resourceUri = conversationDescriptor.getResource();
                        var conversationResourceId = extractResourceId(resourceUri);
                        if (conversationResourceId == null) {
                            log.warn(format("conversationResourceId was null, this should never happen. (%s)", resourceUri));
                            continue;
                        }

                        populateDataToDescriptor(conversationDescriptor, conversationResourceId);

                        // Ownership gate: skip conversations the caller does not own
                        // (admins/auth-disabled short-circuit via seesAllConversations).
                        // Runs after populateDataToDescriptor so the userId is resolved,
                        // including the pre-v5.1.6 fallback to the snapshot's userId; an
                        // unowned (legacy) conversation stays visible, matching
                        // OwnershipValidator.requireOwnerOrAdmin.
                        if (!seesAllConversations
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
            } while (!conversationDescriptors.isEmpty() && retConversationDescriptors.size() < limit);

            return retConversationDescriptors;

        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    private List<ConversationDescriptor> readConversationDescriptors(Integer index, Integer limit, String filter)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        return conversationDescriptorStore.readDescriptors(DESCRIPTOR_TYPE, filter, index, limit, false);
    }

    private void populateDataToDescriptor(ConversationDescriptor conversationDescriptor, IResourceStore.IResourceId resourceId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

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

        } catch (IResourceStore.ResourceNotFoundException e) {
            String message = "Resource referenced in descriptor does not exist (anymore) [%s, %s]. ";
            message += "Ignoring this resource.";
            log.warn(format(message, resourceId.getId(), resourceId.getVersion()));
        }
    }

    @Override
    public ConversationMemorySnapshot readRawConversationLog(String conversationId) {
        checkNotNull(conversationId, "conversationId");

        try {
            // Project the pending tool-call batch down to names-only before returning:
            // this generic raw-read surface is reachable by any authenticated caller
            // and must NOT leak the unredacted tool arguments or the frozen LLM
            // transcript of a paused conversation — those stay behind the approver-only
            // detail=full gate. Mirrors fix #4's confinement on the Simple surface.
            return redactRawPendingToolCallsForRead(
                    conversationMemoryStore.loadConversationMemorySnapshot(conversationId));
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public SimpleConversationMemorySnapshot readSimpleConversationLog(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                                                                      List<String> returningFields) {
        checkNotNull(conversationId, "conversationId");
        checkNotNull(returnDetailed, "returnDetailed");
        checkNotNull(returnCurrentStepOnly, "returnCurrentStepOnly");

        try {
            return convertSimpleConversationMemory(conversationMemoryStore.loadConversationMemorySnapshot(conversationId), returnDetailed,
                    returnCurrentStepOnly);

        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public void deleteConversationLog(String conversationId, Boolean deletePermanently)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        checkNotNull(conversationId, "conversationId");

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
            log.info(format("Conversation has been permanently deleted (conversationId=%s)", sanitize(conversationId)));
        }

        // DocumentDescriptorInterceptor will mark the DocumentDescriptor of this
        // resource as deleted,
        // regardless of whether it has been permanently deleted or not
    }

    @Scheduled(every = "24h")
    public void deleteEndedConversationsOlderThanXDays() {
        runtime.submitCallable(() -> {
            try {
                var amountOfEndedConversations = permanentlyDeleteEndedConversationLogs(deleteEndedConversationsOnceOlderThanDays);

                if (amountOfEndedConversations > 0) {
                    log.info(format("Successfully deleted %s conversations, which were older than %s days", amountOfEndedConversations,
                            deleteEndedConversationsOnceOlderThanDays));
                }
            } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
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
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        int amountOfEndedConversations = 0;
        if (deleteOlderThanDays != null && deleteOlderThanDays > -1) {
            var deleteOlderThanThisDate = Date.from(Instant.now().minus(Duration.ofDays(deleteOlderThanDays)));
            var endedConversationIds = conversationMemoryStore.getEndedConversationIds();

            for (var endedConversationId : endedConversationIds) {
                try {
                    var descriptor = documentDescriptorStore.readDescriptor(endedConversationId, 0);
                    if (descriptor.getLastModifiedOn().before(deleteOlderThanThisDate)) {
                        documentDescriptorStore.deleteAllDescriptor(endedConversationId);
                        deleteAttachmentsForConversation(endedConversationId);
                        conversationMemoryStore.deleteConversationMemorySnapshot(endedConversationId);
                        amountOfEndedConversations++;
                    }
                } catch (IResourceStore.ResourceNotFoundException e) {
                    deleteAttachmentsForConversation(endedConversationId);
                    conversationMemoryStore.deleteConversationMemorySnapshot(endedConversationId);
                    log.debug(format("Cleaned up orphaned conversation memory without descriptor (id=%s)", endedConversationId));
                }
            }
        }

        return amountOfEndedConversations;
    }

    @Override
    public List<ConversationStatus> getActiveConversations(String agentId, Integer agentVersion)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
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
            var conversationDescriptor = conversationDescriptorStore.readDescriptor(conversationId, 0);
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

                ConversationDescriptor conversationDescriptor = conversationDescriptorStore.readDescriptor(conversationId, 0);
                conversationDescriptor.setConversationState(ConversationState.ENDED);
                conversationDescriptorStore.setDescriptor(conversationId, 0, conversationDescriptor);

                log.info(format("conversation (%s) has been set to ENDED", conversationId));
            }

            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
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
