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
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.api.IRestGroupConversation;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

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

            var listener = createStreamingListener(eventSink, sse);
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
            GroupConversation gc = groupConversationService.readGroupConversation(groupConversationId);
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
            GroupConversation gc = groupConversationService.readGroupConversation(groupConversationId);
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
    public Response cancelDiscussion(String groupId, String gcId) {
        validateGroupConversationOwnership(gcId, true);
        try {
            groupConversationService.cancelDiscussion(gcId, ControlSignal.CANCEL_GRACEFUL);
            var gc = groupConversationService.readGroupConversation(gcId);
            return Response.ok(gc).build();
        } catch (IGroupConversationService.GroupDiscussionException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @Override
    public Response approveGroupPhase(String groupId, String gcId, GroupApprovalRequest request) {
        if (request == null || request.getDecision() == null || request.getDecision().getVerdict() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Request body must include a 'decision' with a 'verdict' field (APPROVED or REJECTED)")
                    .build();
        }
        validateGroupConversationOwnership(gcId, true);
        setDecidedByFromIdentity(request);
        try {
            var gc = groupConversationService.resumeDiscussion(gcId, request, null);
            return Response.ok(gc).build();
        } catch (IResourceStore.ResourceModifiedException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (IGroupConversationService.GroupDiscussionException e) {
            // #12: wrong-state (e.g., double-approve) → 409, not 500
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @Override
    public void approveGroupPhaseStreaming(String groupId, String gcId, GroupApprovalRequest request,
                                           SseEventSink eventSink, Sse sse) {
        if (request == null || request.getDecision() == null || request.getDecision().getVerdict() == null) {
            sendEvent(eventSink, sse, "error",
                    "{\"error\":\"Request body must include a 'decision' with a 'verdict' field\"}");
            closeQuietly(eventSink);
            return;
        }
        validateGroupConversationOwnership(gcId, true);
        setDecidedByFromIdentity(request);
        var listener = createStreamingListener(eventSink, sse);
        try {
            groupConversationService.resumeDiscussion(gcId, request, listener);
        } catch (IResourceStore.ResourceModifiedException e) {
            sendEvent(eventSink, sse, "error", "{\"error\":\"" + e.getMessage() + "\"}");
            closeQuietly(eventSink);
        } catch (IGroupConversationService.GroupDiscussionException e) {
            sendEvent(eventSink, sse, "error", "{\"error\":\"" + e.getMessage() + "\"}");
            closeQuietly(eventSink);
        } catch (Exception e) {
            sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR,
                    toJson(new GroupConversationEventSink.GroupErrorEvent(e.getMessage())));
            closeQuietly(eventSink);
        }
    }

    @Override
    public Response getGroupApprovalStatus(String groupId, String gcId, String detail) {
        validateGroupConversationOwnership(gcId, true);
        try {
            var gc = groupConversationService.readGroupConversation(gcId);
            return Response.ok(gc).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    /**
     * Validates that the caller owns the group conversation or is an admin. Mirrors
     * the pattern in RestAgentEngine.validateConversationOwnership.
     */
    private void validateGroupConversationOwnership(String gcId) {
        validateGroupConversationOwnership(gcId, false);
    }

    /**
     * @param hitlOperation
     *            if true, uses strict ownership + approver role check
     */
    private void validateGroupConversationOwnership(String gcId, boolean hitlOperation) {
        try {
            var gc = groupConversationService.readGroupConversation(gcId);
            if (hitlOperation) {
                ownershipValidator.requireOwnerAdminOrApprover(identity, gc.getUserId(), "group conversation");
            } else {
                ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
            }
        } catch (ForbiddenException e) {
            throw e;
        } catch (IResourceStore.ResourceNotFoundException e) {
            // Let the actual operation handle the 404
            LOGGER.debugf("Group conversation not found for ownership check: %s", gcId);
        } catch (Exception e) {
            throw new ForbiddenException("Access denied: unable to verify group conversation ownership");
        }
    }

    /**
     * Sets decidedBy from the authenticated identity (server-side), not from the
     * request body.
     */
    private void setDecidedByFromIdentity(GroupApprovalRequest request) {
        if (request.getDecision() != null && identity != null && identity.getPrincipal() != null) {
            request.getDecision().setDecidedBy(identity.getPrincipal().getName());
        }
    }

    // --- SSE Helpers ---

    private GroupDiscussionEventListener createStreamingListener(SseEventSink eventSink, Sse sse) {
        return new GroupDiscussionEventListener() {
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

            @Override
            public void onHitlPause(GroupConversationEventSink.HitlPauseEvent event) {
                sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_AWAITING_APPROVAL, toJson(event));
                closeQuietly(eventSink);
            }
        };
    }

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

    @Override
    public List<GroupConversation> listGroupPendingApprovals(String groupId) {
        try {
            return groupConversationService.listGroupPendingApprovals();
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException("Failed to list pending approvals: " + e.getMessage(), e);
        }
    }
}
