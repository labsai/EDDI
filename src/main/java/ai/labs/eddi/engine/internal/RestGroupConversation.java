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
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.api.IRestGroupConversation;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
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
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;

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
    private final HitlAccessGuard hitlAccessGuard;

    @Inject
    public RestGroupConversation(IGroupConversationService groupConversationService,
            IJsonSerialization jsonSerialization,
            SecurityIdentity identity,
            OwnershipValidator ownershipValidator,
            HitlAccessGuard hitlAccessGuard) {
        this.groupConversationService = groupConversationService;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
        this.hitlAccessGuard = hitlAccessGuard;
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
            requireGroupMembership(groupId, groupConversationId, gc);
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
            requireGroupMembership(groupId, groupConversationId, gc);
            ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
            groupConversationService.deleteGroupConversation(groupConversationId);
            return Response.ok().build();
        } catch (ForbiddenException e) {
            throw e;
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * Guards against a wrong-group path: the target conversation must belong to
     * {groupId}, else 404 — so the {groupId} path parameter is meaningful and
     * read/delete cannot act on a conversation under an arbitrary group.
     */
    private void requireGroupMembership(String groupId, String gcId, GroupConversation gc) {
        if (groupId != null && gc != null && !groupId.equals(gc.getGroupId())) {
            // Curated body: the caller-supplied gcId/groupId are never reflected
            // (CodeQL reflected-value/XSS — there is no ExceptionMapper for jakarta
            // NotFoundException, so RESTEasy would echo the message verbatim); the
            // detail is logged server-side with both ids sanitized.
            LOGGER.infof("Group conversation %s does not belong to group %s", sanitize(gcId), sanitize(groupId));
            throw new jakarta.ws.rs.NotFoundException("Group conversation not found.");
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
        validateGroupConversationOwnership(groupId, gcId, true);
        try {
            boolean cancelled = groupConversationService.cancelDiscussion(gcId, ControlSignal.CANCEL_GRACEFUL);
            if (!cancelled) {
                // Terminal state or lost a concurrent state race — report honestly
                // instead of a 200 that implies the cancel took effect.
                return Response.status(Response.Status.CONFLICT)
                        .entity("Group conversation is already in a terminal state — nothing to cancel")
                        .build();
            }
            var gc = groupConversationService.readGroupConversation(gcId);
            return Response.ok(gc).build();
        } catch (IResourceStore.ResourceNotFoundException
                | ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException e) {
            // Curated body: GroupConversationGoneException embeds the caller-supplied
            // gcId — never reflect it (CodeQL reflected-value/XSS) and never echo the
            // raw exception text; the detail is logged server-side with the id sanitized
            // (parity with RestAgentEngine).
            LOGGER.infof("Cancel of group conversation %s → not found: %s", sanitize(gcId), e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("Group conversation not found.").build();
        } catch (Exception e) {
            LOGGER.error("Failed to cancel group conversation " + sanitize(gcId), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).type(TEXT_PLAIN)
                    .entity("Failed to cancel group discussion.").build();
        }
    }

    /** Upper bound for the free-text reviewer note persisted with a decision. */
    private static final int MAX_HITL_NOTE_LENGTH = HitlDecision.MAX_NOTE_LENGTH;

    @Override
    public Response approveGroupPhase(String groupId, String gcId, GroupApprovalRequest request) {
        if (request == null || request.getDecision() == null || request.getDecision().getVerdict() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Request body must include a 'decision' with a 'verdict' field (APPROVED or REJECTED)")
                    .build();
        }
        if (request.getDecision().getNote() != null
                && request.getDecision().getNote().length() > MAX_HITL_NOTE_LENGTH) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Decision note exceeds the maximum length of " + MAX_HITL_NOTE_LENGTH + " characters")
                    .build();
        }
        validateGroupConversationOwnership(groupId, gcId, true);
        setDecidedByFromIdentity(request);
        try {
            var gc = groupConversationService.resumeDiscussion(gcId, request, null);
            return Response.ok(gc).build();
        } catch (IResourceStore.ResourceModifiedException e) {
            // Curated bodies throughout (parity with RestAgentEngine): the HTTP status
            // carries the outcome; the raw exception text (and any caller-supplied id
            // it embeds) is logged server-side, never reflected to the client.
            return Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN)
                    .entity("The group conversation was modified concurrently — reload and retry.").build();
        } catch (IResourceStore.ResourceNotFoundException
                | ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException e) {
            // deleted concurrently — a genuine 404, not a state conflict
            LOGGER.infof("Approve of group conversation %s → not found: %s", sanitize(gcId), e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("Group conversation not found.").build();
        } catch (IGroupConversationService.GroupDiscussionException e) {
            // #12: wrong-state (e.g., double-approve) → 409, not 500. The current state
            // is discoverable via the approval-status endpoint.
            LOGGER.infof("Approve of group conversation %s rejected (wrong state): %s", sanitize(gcId), e.getMessage());
            return Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN)
                    .entity("Group conversation is not awaiting approval — it may have been resolved, cancelled, "
                            + "or already approved.")
                    .build();
        } catch (IllegalArgumentException e) {
            // #13: invalid taskApprovals (unknown taskId / task not awaiting) → 400. Do
            // not reflect the caller-supplied taskId; keep the hint generic.
            LOGGER.infof("Approve of group conversation %s rejected (invalid request): %s", sanitize(gcId), e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).type(TEXT_PLAIN)
                    .entity("Invalid approval request: an unknown task id was referenced, or a task is not awaiting "
                            + "approval.")
                    .build();
        } catch (Exception e) {
            LOGGER.error("Failed to approve group conversation " + sanitize(gcId), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).type(TEXT_PLAIN)
                    .entity("Failed to process group approval.").build();
        }
    }

    @Override
    public void approveGroupPhaseStreaming(String groupId, String gcId, GroupApprovalRequest request,
                                           SseEventSink eventSink, Sse sse) {
        if (request == null || request.getDecision() == null || request.getDecision().getVerdict() == null) {
            sendErrorEvent(eventSink, sse, "Request body must include a 'decision' with a 'verdict' field");
            closeQuietly(eventSink);
            return;
        }
        if (request.getDecision().getNote() != null
                && request.getDecision().getNote().length() > MAX_HITL_NOTE_LENGTH) {
            sendErrorEvent(eventSink, sse, "Decision note exceeds the maximum length of " + MAX_HITL_NOTE_LENGTH + " characters");
            closeQuietly(eventSink);
            return;
        }
        try {
            validateGroupConversationOwnership(groupId, gcId, true);
        } catch (jakarta.ws.rs.NotFoundException e) {
            // Streaming endpoint has no Response to return — surface the mismatch as
            // a terminal SSE error instead of letting a WebApplicationException abort
            // the stream opaquely.
            sendErrorEvent(eventSink, sse, e.getMessage());
            closeQuietly(eventSink);
            return;
        }
        setDecidedByFromIdentity(request);
        var listener = createStreamingListener(eventSink, sse);
        try {
            groupConversationService.resumeDiscussion(gcId, request, listener);
        } catch (IResourceStore.ResourceModifiedException e) {
            // Curated messages throughout (parity with the non-streaming
            // approveGroupPhase): never forward e.getMessage() over SSE — it can
            // embed the caller-supplied gcId/taskIds and internal detail.
            sendErrorEvent(eventSink, sse,
                    "The group conversation was modified concurrently — reload and retry.");
            closeQuietly(eventSink);
        } catch (IResourceStore.ResourceNotFoundException
                | ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException e) {
            // deleted concurrently — a genuine 404 equivalent
            sendErrorEvent(eventSink, sse, "Group conversation not found.");
            closeQuietly(eventSink);
        } catch (IGroupConversationService.GroupDiscussionException e) {
            // wrong-state (e.g., double-approve) — the current state is discoverable
            // via the approval-status endpoint.
            sendErrorEvent(eventSink, sse,
                    "Group conversation is not awaiting approval — it may have been resolved, cancelled, "
                            + "or already approved.");
            closeQuietly(eventSink);
        } catch (IllegalArgumentException e) {
            // invalid taskApprovals (unknown taskId / task not awaiting) — keep the
            // hint generic, never reflect the caller-supplied taskId.
            sendErrorEvent(eventSink, sse,
                    "Invalid approval request: an unknown task id was referenced, or a task is not awaiting "
                            + "approval.");
            closeQuietly(eventSink);
        } catch (Exception e) {
            LOGGER.error("Failed to approve group conversation " + sanitize(gcId), e);
            sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR,
                    toJson(new GroupConversationEventSink.GroupErrorEvent("Failed to process group approval.")));
            closeQuietly(eventSink);
        }
    }

    /**
     * JSON-safe error event — the message goes through the serializer, never string
     * concatenation.
     */
    private void sendErrorEvent(SseEventSink eventSink, Sse sse, String message) {
        // The documented event set uses "group_error" for every application-level
        // failure; a raw "error" name additionally collides with the browser
        // EventSource's built-in transport-error event, so clients cannot tell an
        // invalid approval body from a dropped connection (#36).
        sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR,
                toJson(new GroupConversationEventSink.GroupErrorEvent(message)));
    }

    @Override
    public Response getGroupApprovalStatus(String groupId, String gcId, String detail) {
        validateGroupConversationOwnership(groupId, gcId, true);
        try {
            var gc = groupConversationService.readGroupConversation(gcId);
            boolean paused = gc.getState() == GroupConversation.GroupConversationState.AWAITING_APPROVAL;
            if ("full".equals(detail)) {
                // Approver-only callers (not owner, not admin) may read the full
                // conversation (incl. transcript) only while it is actually awaiting
                // approval — mirrors the regular surface's read-scope gate.
                if (!paused && !ownershipValidator.isAdmin(identity)
                        && !ownershipValidator.isOwner(identity, gc.getUserId())) {
                    return Response.status(Response.Status.FORBIDDEN)
                            .entity("Full approval status is available to approvers only while the group "
                                    + "conversation is awaiting approval — use the summary view")
                            .build();
                }
                return Response.ok(gc).build();
            }
            // Summary (default): pause coordinates only — no transcript. Pause
            // fields are suppressed outside AWAITING_APPROVAL so stale values
            // never mislead dashboards.
            List<String> awaitingTaskIds = paused && gc.getTaskList() != null
                    ? gc.getTaskList().all().stream()
                            .filter(t -> t.status() == ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus.AWAITING_APPROVAL)
                            .map(ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem::id)
                            .toList()
                    : List.of();
            var summary = new java.util.LinkedHashMap<String, Object>();
            summary.put("groupConversationId", gcId);
            summary.put("state", gc.getState() != null ? gc.getState().name() : "");
            summary.put("pausedAt", paused && gc.getPausedAt() != null ? gc.getPausedAt().toString() : "");
            summary.put("pausedPhaseName", paused && gc.getPausedPhaseName() != null ? gc.getPausedPhaseName() : "");
            summary.put("pauseType", paused && gc.getHitlPauseType() != null ? gc.getHitlPauseType().name() : "");
            summary.put("pauseReason", paused && gc.getHitlPauseReason() != null ? gc.getHitlPauseReason() : "");
            summary.put("timeoutPolicy", paused && gc.getHitlTimeoutPolicy() != null ? gc.getHitlTimeoutPolicy().name() : "");
            summary.put("awaitingApprovalTaskIds", awaitingTaskIds);
            return Response.ok(summary).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("Group conversation not found.").build();
        } catch (Exception e) {
            LOGGER.error("Failed to read group approval status for " + sanitize(gcId), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).type(TEXT_PLAIN)
                    .entity("Failed to read group approval status.").build();
        }
    }

    /**
     * Validates that the caller owns the group conversation (or is admin/approver)
     * and — when {@code groupId} is non-null — that the target conversation belongs
     * to that group. Mirrors the pattern in
     * RestAgentEngine.validateConversationOwnership.
     *
     * @param groupId
     *            when non-null, also verifies the target conversation belongs to
     *            this group — a mismatch (or unknown conversation) yields 404 so
     *            approve/cancel/status cannot act on a conversation under the wrong
     *            group path (CodeQL: unused path param → consistency hole).
     * @param hitlOperation
     *            if true, uses strict ownership + approver role check
     */
    private void validateGroupConversationOwnership(String groupId, String gcId, boolean hitlOperation) {
        if (hitlOperation) {
            // Strict HITL ownership (group-membership check + owner/admin/approver,
            // fail-closed) is shared with the MCP surface via HitlAccessGuard.
            hitlAccessGuard.requireGroupConversationHitlAccess(groupId, gcId);
            return;
        }
        GroupConversation gc;
        try {
            gc = groupConversationService.readGroupConversation(gcId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            // Let the actual operation handle the 404
            LOGGER.debugf("Group conversation not found for ownership check: %s", gcId);
            return;
        } catch (Exception e) {
            throw new ForbiddenException("Access denied: unable to verify group conversation ownership");
        }
        // Membership check first: a wrong-group path must look like "not found" here,
        // never leak the conversation's existence or owner via an authz error.
        if (groupId != null && !groupId.equals(gc.getGroupId())) {
            LOGGER.infof("Group conversation %s does not belong to group %s", sanitize(gcId), sanitize(groupId));
            throw new jakarta.ws.rs.NotFoundException("Group conversation not found.");
        }
        ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
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

            @Override
            public void onHitlResume(GroupConversationEventSink.HitlResumeEvent event) {
                // Deliberately does NOT close — the stream continues with the
                // resumed discussion's phase/speaker events.
                sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_HITL_RESUME, toJson(event));
            }

            @Override
            public void onCancelled(GroupConversationEventSink.CancelledEvent event) {
                sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_CANCELLED, toJson(event));
                closeQuietly(eventSink);
            }

            @Override
            public void onMemberPauseSkipped(GroupConversationEventSink.MemberPauseSkippedEvent event) {
                // Not terminal — the discussion continues past the skipped member.
                sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_MEMBER_PAUSE_SKIPPED, toJson(event));
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
    public List<ai.labs.eddi.engine.model.PendingApprovalSummary> listGroupPendingApprovals(String groupId, Integer limit) {
        // Scoped to the group in the path — the query-level filter keeps the listing
        // from leaking other groups.
        return listPendingApprovals(groupId, limit);
    }

    @Override
    public List<ai.labs.eddi.engine.model.PendingApprovalSummary> listAllGroupPendingApprovals(Integer limit) {
        // #21: cross-group inbox — groupId=null lists pending approvals across all
        // groups (the store's existing findByState(state, null, limit) variant, the
        // same one crash recovery uses). Same authz as the per-group endpoint.
        return listPendingApprovals(null, limit);
    }

    /**
     * Shared listing + ownership filter for the per-group and cross-group inboxes.
     * Mirrors RestAgentEngine.listPendingApprovals: admins and designated approvers
     * see all pending items, other callers only their own conversations; anonymous
     * or principal-less callers see nothing (fail-closed).
     */
    private List<ai.labs.eddi.engine.model.PendingApprovalSummary> listPendingApprovals(String groupId, Integer limit) {
        try {
            // Owner-scoping (admins/approvers see all; others see only their own;
            // anonymous sees nothing) lives in HitlAccessGuard so the MCP surface
            // shares the exact same rule verbatim.
            return hitlAccessGuard.listScopedGroupPendingApprovals(groupId, limit != null ? limit : 100);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException("Failed to list pending approvals: " + e.getMessage(), e);
        }
    }
}
