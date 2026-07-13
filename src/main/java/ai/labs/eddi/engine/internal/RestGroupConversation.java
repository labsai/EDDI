/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IRestGroupConversation;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * REST implementation for group conversation operations.
 *
 * @author ginccc
 */
@ApplicationScoped
public class RestGroupConversation implements IRestGroupConversation {

    private static final Logger LOGGER = Logger.getLogger(RestGroupConversation.class);

    private final IGroupConversationService groupConversationService;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    public RestGroupConversation(IGroupConversationService groupConversationService,
            IJsonSerialization jsonSerialization,
            SecurityIdentity identity,
            OwnershipValidator ownershipValidator) {
        this.groupConversationService = groupConversationService;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
    }

    @PreDestroy
    void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Load a group conversation and verify it belongs to the group in the path.
     * Throws {@link IResourceStore.ResourceNotFoundException} on mismatch so each
     * caller's existing not-found handling (404 or SSE error) applies uniformly.
     */
    private GroupConversation loadInGroup(String groupId, String gcId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        GroupConversation gc = groupConversationService.readGroupConversation(gcId);
        if (gc.getGroupId() == null || !gc.getGroupId().equals(groupId)) {
            throw new IResourceStore.ResourceNotFoundException("Group conversation not found in group: " + groupId);
        }
        return gc;
    }

    @Override
    public Response discuss(String groupId, DiscussRequest request) {
        try {
            String userId = ownershipValidator.validateAndResolveUserId(identity, request.userId());
            if (userId == null || userId.isBlank())
                userId = "anonymous";
            GroupConversation gc = groupConversationService.discuss(groupId, request.question(), userId, 0);
            URI location = URI.create("/groups/" + groupId + "/conversations/" + gc.getId());
            return Response.created(location).entity(gc).build();
        } catch (IGroupConversationService.GroupDepthExceededException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (Exception e) {
            LOGGER.errorf("Group discussion failed: %s", e.getMessage());
            throw sneakyThrow(e);
        }
    }

    @Override
    public void discussStreaming(String groupId, DiscussRequest request, SseEventSink eventSink, Sse sse) {
        try {
            String userId = ownershipValidator.validateAndResolveUserId(identity, request.userId());
            if (userId == null || userId.isBlank())
                userId = "anonymous";

            GroupDiscussionEventListener listener = new GroupDiscussionEventListener() {
                @Override
                public void onGroupStart(GroupConversationEventSink.GroupStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_START, toJson(event));
                }

                @Override
                public void onPhaseStart(GroupConversationEventSink.PhaseStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_PHASE_START, toJson(event));
                }

                @Override
                public void onSpeakerStart(GroupConversationEventSink.SpeakerStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_SPEAKER_START, toJson(event));
                }

                @Override
                public void onSpeakerComplete(GroupConversationEventSink.SpeakerCompleteEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_SPEAKER_COMPLETE, toJson(event));
                }

                @Override
                public void onPhaseComplete(GroupConversationEventSink.PhaseCompleteEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_PHASE_COMPLETE, toJson(event));
                }

                @Override
                public void onSynthesisStart(GroupConversationEventSink.SynthesisStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_SYNTHESIS_START, toJson(event));
                }

                @Override
                public void onGroupComplete(GroupConversationEventSink.GroupCompleteEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_COMPLETE, toJson(event));
                    closeQuietly(eventSink);
                }

                @Override
                public void onGroupError(GroupConversationEventSink.GroupErrorEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR, toJson(event));
                    closeQuietly(eventSink);
                }

                @Override
                public void onTaskPlanCreated(GroupConversationEventSink.TaskPlanCreatedEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_TASK_PLAN_CREATED, toJson(event));
                }

                @Override
                public void onTaskVerified(GroupConversationEventSink.TaskVerifiedEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_TASK_VERIFIED, toJson(event));
                }
            };

            groupConversationService.startAndDiscussAsync(groupId, request.question(), userId, listener);

        } catch (IResourceStore.ResourceNotFoundException e) {
            sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR,
                    toJson(new GroupConversationEventSink.GroupErrorEvent(e.getMessage())));
            closeQuietly(eventSink);
        } catch (Exception e) {
            LOGGER.errorf("Group streaming discussion failed: %s", e.getMessage());
            sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR,
                    toJson(new GroupConversationEventSink.GroupErrorEvent(e.getMessage())));
            closeQuietly(eventSink);
        }
    }

    @Override
    public GroupConversation readGroupConversation(String groupId, String groupConversationId) {
        try {
            GroupConversation gc = loadInGroup(groupId, groupConversationId);
            ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
            return gc;
        } catch (ForbiddenException e) {
            throw e;
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response deleteGroupConversation(String groupId, String groupConversationId) {
        try {
            GroupConversation gc = loadInGroup(groupId, groupConversationId);
            ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
            groupConversationService.deleteGroupConversation(groupConversationId);
            return Response.ok().build();
        } catch (ForbiddenException e) {
            throw e;
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<GroupConversation> listGroupConversations(String groupId, Integer index, Integer limit) {
        try {
            List<GroupConversation> conversations = groupConversationService.listGroupConversations(groupId, index, limit);
            // Filter to owned conversations unless admin
            if (ownershipValidator.isAuthEnabled() && identity != null && !identity.isAnonymous()
                    && !identity.hasRole("eddi-admin")) {
                String callerId = identity.getPrincipal().getName();
                conversations = conversations.stream()
                        .filter(gc -> callerId.equals(gc.getUserId()))
                        .toList();
            }
            return conversations;
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response followUpWithMember(String groupId, String gcId, FollowUpRequest request) {
        try {
            GroupConversation gc = loadInGroup(groupId, gcId);
            ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
            GroupConversation result = groupConversationService.followUpWithMember(gcId, request.targetAgentId(), request.question());
            return Response.ok(result).build();
        } catch (ForbiddenException e) {
            throw e;
        } catch (IGroupConversationService.GroupDiscussionException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (Exception e) {
            LOGGER.errorf("Follow-up with member failed: %s", e.getMessage());
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response continueDiscussion(String groupId, String gcId, DiscussRequest request) {
        try {
            GroupConversation gc = loadInGroup(groupId, gcId);
            ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
            GroupConversation result = groupConversationService.continueDiscussion(gcId, request.question(), null);
            return Response.ok(result).build();
        } catch (ForbiddenException e) {
            throw e;
        } catch (IGroupConversationService.GroupDiscussionException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (Exception e) {
            LOGGER.errorf("Continue discussion failed: %s", e.getMessage());
            throw sneakyThrow(e);
        }
    }

    @Override
    public void continueDiscussionStreaming(String groupId, String gcId, DiscussRequest request,
                                            SseEventSink eventSink, Sse sse) {
        try {
            GroupConversation gc = loadInGroup(groupId, gcId);
            ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");

            GroupDiscussionEventListener listener = new GroupDiscussionEventListener() {
                @Override
                public void onRoundStart(GroupConversationEventSink.RoundStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_ROUND_START, toJson(event));
                }

                @Override
                public void onGroupStart(GroupConversationEventSink.GroupStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_START, toJson(event));
                }

                @Override
                public void onPhaseStart(GroupConversationEventSink.PhaseStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_PHASE_START, toJson(event));
                }

                @Override
                public void onSpeakerStart(GroupConversationEventSink.SpeakerStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_SPEAKER_START, toJson(event));
                }

                @Override
                public void onSpeakerComplete(GroupConversationEventSink.SpeakerCompleteEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_SPEAKER_COMPLETE, toJson(event));
                }

                @Override
                public void onPhaseComplete(GroupConversationEventSink.PhaseCompleteEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_PHASE_COMPLETE, toJson(event));
                }

                @Override
                public void onSynthesisStart(GroupConversationEventSink.SynthesisStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_SYNTHESIS_START, toJson(event));
                }

                @Override
                public void onGroupComplete(GroupConversationEventSink.GroupCompleteEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_COMPLETE, toJson(event));
                    closeQuietly(eventSink);
                }

                @Override
                public void onGroupError(GroupConversationEventSink.GroupErrorEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR, toJson(event));
                    closeQuietly(eventSink);
                }

                @Override
                public void onTaskPlanCreated(GroupConversationEventSink.TaskPlanCreatedEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_TASK_PLAN_CREATED, toJson(event));
                }

                @Override
                public void onTaskVerified(GroupConversationEventSink.TaskVerifiedEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_TASK_VERIFIED, toJson(event));
                }
            };

            executorService.submit(() -> {
                try {
                    groupConversationService.continueDiscussion(gcId, request.question(), listener);
                } catch (Exception e) {
                    LOGGER.errorf("Continue discussion streaming failed: %s", e.getMessage());
                    listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(e.getMessage()));
                }
            });

        } catch (ForbiddenException e) {
            throw e;
        } catch (IResourceStore.ResourceNotFoundException e) {
            sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR,
                    toJson(new GroupConversationEventSink.GroupErrorEvent(e.getMessage())));
            closeQuietly(eventSink);
        } catch (Exception e) {
            LOGGER.errorf("Continue discussion streaming setup failed: %s", e.getMessage());
            sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR,
                    toJson(new GroupConversationEventSink.GroupErrorEvent(e.getMessage())));
            closeQuietly(eventSink);
        }
    }

    @Override
    public Response closeGroupConversation(String groupId, String gcId) {
        try {
            GroupConversation gc = loadInGroup(groupId, gcId);
            ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
            GroupConversation result = groupConversationService.closeGroupConversation(gcId);
            return Response.ok(result).build();
        } catch (ForbiddenException e) {
            throw e;
        } catch (IGroupConversationService.GroupDiscussionException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (Exception e) {
            // Genuine store/DB failures (ResourceStoreException) map to 500 via the
            // global mapper — only business conflicts above are 409.
            LOGGER.errorf("Close group conversation failed: %s", e.getMessage());
            throw sneakyThrow(e);
        }
    }

    // --- SSE Helpers ---

    private void sendEvent(SseEventSink eventSink, Sse sse, String eventName, String data) {
        if (eventSink.isClosed()) {
            LOGGER.debugf("SSE sink closed, dropping event: %s", eventName);
            return;
        }
        try {
            eventSink.send(sse.newEventBuilder().name(eventName).data(String.class, data).build());
        } catch (Exception e) {
            LOGGER.warnf("Failed to send SSE event '%s': %s", eventName, e.getMessage());
        }
    }

    private void closeQuietly(SseEventSink eventSink) {
        try {
            if (!eventSink.isClosed()) {
                eventSink.close();
            }
        } catch (Exception e) {
            LOGGER.debugf("Error closing SSE sink: %s", e.getMessage());
        }
    }

    private String toJson(Object obj) {
        try {
            return jsonSerialization.serialize(obj);
        } catch (Exception e) {
            LOGGER.warnf("Failed to serialize SSE event: %s", e.getMessage());
            return "{}";
        }
    }
}
