/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;
import ai.labs.eddi.configs.groups.IGroupConversationStore;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.api.IRestGroupConversation;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.ws.rs.NotFoundException;
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
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
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
        GroupConversation gc;
        try {
            gc = groupConversationService.readGroupConversation(gcId);
        } catch (IllegalArgumentException e) {
            // Scoped deliberately to the id lookup ONLY. A malformed id reaches the
            // storage layer's ObjectId parser, whose message embeds the raw caller string
            // ("invalid hexadecimal representation of an ObjectId: [...]") and would be
            // echoed by the global mapper — a reflected-value sink. A malformed id cannot
            // name a conversation, so "not found" is the honest answer. Catching this any
            // wider would mask a genuine internal IllegalArgumentException as a 404.
            LOGGER.infof("Malformed group conversation id: %s", sanitize(gcId));
            throw new IResourceStore.ResourceNotFoundException("Group conversation not found.");
        }
        if (gc.getGroupId() == null || !gc.getGroupId().equals(groupId)) {
            // Curated body: the caller-supplied groupId/gcId are never reflected back
            // (CodeQL reflected-value/XSS) — several callers echo e.getMessage() into the
            // 404. The detail is logged server-side with both ids sanitized.
            LOGGER.infof("Group conversation %s does not belong to group %s", sanitize(gcId), sanitize(groupId));
            throw new IResourceStore.ResourceNotFoundException("Group conversation not found.");
        }
        return gc;
    }

    /**
     * Server-side re-check of the request-body ceilings declared on
     * {@link IRestGroupConversation}.
     * <p>
     * The ceilings themselves are Bean Validation constraints on a JAX-RS
     * <em>interface</em>. Quarkus is expected to honour those on the implementing
     * resource, but this deployment has no test that proves it (and one cannot be
     * written without booting the HTTP layer), so a wiring regression would leave
     * every ceiling silently inert while looking enforced. A group question is
     * fanned out to every member agent in every phase, which makes an unbounded one
     * an amplification surface — cheap to re-check here, and independent of the
     * interceptor being wired.
     *
     * @return a client-safe rejection reason, or {@code null} when the body is
     *         acceptable
     */
    private static String rejectionReason(String question, String userId) {
        if (blank(question)) {
            return "'question' is required.";
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            return "'question' must be at most " + MAX_QUESTION_CHARS + " characters.";
        }
        if (userId != null && userId.length() > MAX_IDENTIFIER_CHARS) {
            return "'userId' must be at most " + MAX_IDENTIFIER_CHARS + " characters.";
        }
        return null;
    }

    private static Response badRequest(String reason) {
        return Response.status(Response.Status.BAD_REQUEST).type(TEXT_PLAIN).entity(reason).build();
    }

    @Override
    public Response discuss(String groupId, DiscussRequest request) {
        if (request == null) {
            return badRequest("A request body is required.");
        }
        String rejection = rejectionReason(request.question(), request.userId());
        if (rejection != null) {
            return badRequest(rejection);
        }
        try {
            String userId = ownershipValidator.validateAndResolveUserId(identity, request.userId());
            if (userId == null || userId.isBlank())
                userId = "anonymous";
            List<Attachment> attachments = toAttachments(request.attachments());
            GroupConversation gc = attachments == null
                    ? groupConversationService.discuss(groupId, request.question(), userId, 0)
                    : groupConversationService.discuss(groupId, request.question(), userId, 0, null, attachments);
            URI location = URI.create("/groups/" + groupId + "/conversations/" + gc.getId());
            return Response.created(location).entity(gc).build();
        } catch (IGroupConversationService.GroupDepthExceededException e) {
            // Curated bodies throughout this class: the raw exception text is never
            // returned to the caller (CodeQL: information exposure / reflected value —
            // several of these messages used to embed the caller-supplied groupId/gcId).
            // The detail is logged server-side with the ids sanitized.
            LOGGER.infof("Group discussion rejected (depth) for group %s", sanitize(groupId));
            return Response.status(Response.Status.BAD_REQUEST).type(TEXT_PLAIN)
                    .entity("Maximum group nesting depth exceeded.").build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.infof("Group discussion → not found for group %s", sanitize(groupId));
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("Group not found.").build();
        } catch (IllegalArgumentException e) {
            // Usually a malformed groupId: it reaches the storage layer's ObjectId parser,
            // whose message embeds the raw caller string and would be echoed by the global
            // mapper. discuss() has no loadInGroup to scope this to the id lookup, so log
            // the full exception — a genuine internal IllegalArgumentException must stay
            // diagnosable rather than vanish behind a "not found".
            LOGGER.error("Group discussion rejected for group " + sanitize(groupId)
                    + " (malformed id, or an internal IllegalArgumentException)", e);
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("Group not found.").build();
        } catch (Exception e) {
            LOGGER.errorf("Group discussion failed: %s", e.getMessage());
            throw sneakyThrow(e);
        }
    }

    @Override
    public void discussStreaming(String groupId, DiscussRequest request, SseEventSink eventSink, Sse sse) {
        String rejection = request == null ? "A request body is required." : rejectionReason(request.question(), request.userId());
        if (rejection != null) {
            sendErrorEvent(eventSink, sse, rejection);
            closeQuietly(eventSink);
            return;
        }
        try {
            String userId = ownershipValidator.validateAndResolveUserId(identity, request.userId());
            if (userId == null || userId.isBlank())
                userId = "anonymous";

            var listener = createStreamingListener(eventSink, sse);
            List<Attachment> attachments = toAttachments(request.attachments());
            if (attachments == null) {
                groupConversationService.startAndDiscussAsync(groupId, request.question(), userId, listener);
            } else {
                groupConversationService.startAndDiscussAsync(groupId, request.question(), userId, listener, attachments);
            }

        } catch (IResourceStore.ResourceNotFoundException e) {
            // Curated event text: the SSE body is rendered by the chat UI, so never push
            // the raw message (it used to embed the caller-supplied groupId).
            LOGGER.infof("Group streaming discussion → not found for group %s: %s", sanitize(groupId), e.getMessage());
            sendErrorEvent(eventSink, sse, "Group not found.");
            closeQuietly(eventSink);
        } catch (Exception e) {
            LOGGER.errorf("Group streaming discussion failed: %s", e.getMessage());
            sendErrorEvent(eventSink, sse, "Group discussion could not be started.");
            closeQuietly(eventSink);
        }
    }

    /**
     * Convert request-level attachment refs into the memory model carrier the
     * service materializes. Refs without inline data or a url are skipped.
     */
    private static List<Attachment> toAttachments(List<AttachmentRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        List<Attachment> out = new ArrayList<>();
        for (AttachmentRef r : refs) {
            if (r == null) {
                continue;
            }
            Attachment a = new Attachment();
            a.setMimeType(r.mimeType());
            a.setFileName(r.fileName());
            if (r.data() != null && !r.data().isBlank()) {
                a.setBase64Data(r.data());
            } else if (r.url() != null && !r.url().isBlank()) {
                // A URL attachment with no mimeType is dropped later by
                // AttachmentContextExtractor; skip it here so the loss is explicit
                // rather than silent.
                if (r.mimeType() == null || r.mimeType().isBlank()) {
                    continue;
                }
                a.setUrl(r.url());
            } else {
                continue;
            }
            out.add(a);
        }
        return out.isEmpty() ? null : out;
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
        } catch (IGroupConversationService.GroupDiscussionException e) {
            // Another operation (follow-up / continue / close) is mid-flight — a retryable
            // conflict, not a server error. Curated body: never surface the raw exception
            // text to the client (CodeQL: information exposure through an error message);
            // the detail is logged server-side with the id sanitized.
            LOGGER.infof("Delete of group conversation %s conflicted: %s",
                    sanitize(groupConversationId), sanitize(e.getMessage()));
            return Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN)
                    .entity("Group conversation is busy — another operation is in progress. Please retry.")
                    .build();
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

    /** True when the string is absent or whitespace-only. */
    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public Response followUpWithMember(String groupId, String gcId, FollowUpRequest request) {
        // Reject an incomplete body up front: without this a missing targetAgentId NPEs
        // during member resolution and surfaces as a 500 rather than a 400.
        if (request == null || blank(request.question()) || blank(request.targetAgentId())) {
            return badRequest("Both 'question' and 'targetAgentId' are required.");
        }
        // Same ceilings as discuss() — see rejectionReason.
        String rejection = rejectionReason(request.question(), request.userId());
        if (rejection == null && request.targetAgentId().length() > MAX_IDENTIFIER_CHARS) {
            rejection = "'targetAgentId' must be at most " + MAX_IDENTIFIER_CHARS + " characters.";
        }
        if (rejection != null) {
            return badRequest(rejection);
        }
        try {
            GroupConversation gc = loadInGroup(groupId, gcId);
            ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
            GroupConversation result = groupConversationService.followUpWithMember(gcId, request.targetAgentId(), request.question());
            return Response.ok(result).build();
        } catch (ForbiddenException e) {
            throw e;
        } catch (IGroupConversationService.GroupMemberNotFoundException e) {
            // A typo'd / non-member agent — a client error, not a conflict. 404, curated:
            // the exception text carries the member list (server data), never the caller
            // id.
            LOGGER.infof("Follow-up on %s → target not a member: %s", sanitize(gcId), sanitize(e.getMessage()));
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("The target agent is not a member of this group conversation.").build();
        } catch (IGroupConversationService.GroupTimeoutException e) {
            LOGGER.warn("Follow-up on " + sanitize(gcId) + " timed out", e);
            return Response.status(Response.Status.GATEWAY_TIMEOUT).type(TEXT_PLAIN)
                    .entity("The member agent did not respond in time. Try again.").build();
        } catch (IGroupConversationService.GroupExecutionException e) {
            // A member agent / model call failed — an upstream-dependency failure, 5xx, not
            // a retryable "conflict". Log the full cause; the client gets a curated body.
            LOGGER.error("Follow-up on " + sanitize(gcId) + " failed to execute", e);
            return Response.status(Response.Status.BAD_GATEWAY).type(TEXT_PLAIN)
                    .entity("The follow-up could not be completed because the member agent could not be reached.")
                    .build();
        } catch (IGroupConversationService.GroupDiscussionException e) {
            // Base type now means only a state / concurrency conflict.
            LOGGER.infof("Follow-up on %s conflicted: %s", sanitize(gcId), sanitize(e.getMessage()));
            return Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN)
                    .entity("The follow-up could not be applied: the conversation must be COMPLETED and have no "
                            + "other operation in progress.")
                    .build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("Group conversation not found.").build();
        } catch (Exception e) {
            LOGGER.errorf("Follow-up with member failed: %s", e.getMessage());
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
                | IGroupConversationStore.GroupConversationGoneException e) {
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
                | IGroupConversationStore.GroupConversationGoneException e) {
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
    public Response submitHumanInput(String groupId, String gcId, HumanInputRequest request) {
        if (request == null || request.memberId() == null || request.memberId().isBlank()
                || request.content() == null || request.content().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).type(TEXT_PLAIN)
                    .entity("Request body must include a non-blank 'memberId' and 'content'").build();
        }
        // I6: NOT the approve guard — speaking as a member is impersonation unless
        // the caller IS that member (or an admin); an eddi-approver may decide
        // approvals but not talk for people.
        hitlAccessGuard.requireGroupHumanInputAccess(groupId, gcId, request.memberId());
        String submittedBy = identity != null && identity.getPrincipal() != null
                ? identity.getPrincipal().getName()
                : "anonymous";
        try {
            var gc = groupConversationService.submitHumanInput(gcId, request.memberId(), request.content(), submittedBy);
            return Response.ok(gc).build();
        } catch (IResourceStore.ResourceModifiedException e) {
            // Double-submit or a concurrent cancel won the CAS.
            return Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN)
                    .entity("The group conversation was modified concurrently — reload and retry.").build();
        } catch (IResourceStore.ResourceNotFoundException
                | IGroupConversationStore.GroupConversationGoneException e) {
            LOGGER.infof("Human input for group conversation %s → not found: %s", sanitize(gcId), e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("Group conversation not found.").build();
        } catch (IGroupConversationService.GroupDiscussionException e) {
            LOGGER.infof("Human input for group conversation %s rejected (wrong state): %s", sanitize(gcId), e.getMessage());
            // Review finding: the blanket "not awaiting human input" body affirmatively
            // misstated the config-drift case — the conversation IS still awaiting
            // input, and every retry fails identically while the real cause (a config
            // edit invalidated the bookmark) lived only in server logs. The two drift
            // messages are static server-side text (no caller input), so they are safe
            // to surface; everything else keeps the curated generic body.
            String message = e.getMessage() != null
                    && (e.getMessage().startsWith("Group config changed while paused")
                            || e.getMessage().startsWith("The paused turn's bookmark"))
                                    ? e.getMessage()
                                    : "Group conversation is not awaiting human input — the turn may have been resolved, "
                                            + "timed out, or cancelled.";
            return Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN)
                    .entity(message)
                    .build();
        } catch (IllegalArgumentException e) {
            LOGGER.infof("Human input for group conversation %s rejected (invalid request): %s", sanitize(gcId), e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).type(TEXT_PLAIN)
                    .entity("Invalid submission: the memberId does not match the pending turn, or the content is "
                            + "blank or too long.")
                    .build();
        } catch (Exception e) {
            LOGGER.error("Failed to submit human input for group conversation " + sanitize(gcId), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).type(TEXT_PLAIN)
                    .entity("Failed to submit human input.").build();
        }
    }

    /**
     * A continuation round cannot share NEW files: attachments are granted and
     * injected to a member only on its first-ever turn, and on a continuation every
     * member conversation already exists. Rather than accept them and silently drop
     * them (or store an orphaned blob), reject them explicitly. Honouring them
     * requires reworking the attachment fan-out — tracked as follow-up work.
     */
    private static Response rejectAttachmentsOnContinue() {
        return Response.status(Response.Status.BAD_REQUEST).type(TEXT_PLAIN)
                .entity("Attachments cannot be added on a continuation round — they are only "
                        + "shared with member agents when the discussion starts. Start a new "
                        + "discussion to share new files.")
                .build();
    }

    @Override
    public Response continueDiscussion(String groupId, String gcId, DiscussRequest request) {
        String rejection = request == null ? "'question' is required." : rejectionReason(request.question(), request.userId());
        if (rejection != null) {
            return badRequest(rejection);
        }
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            return rejectAttachmentsOnContinue();
        }
        try {
            GroupConversation gc = loadInGroup(groupId, gcId);
            ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
            GroupConversation result = groupConversationService.continueDiscussion(gcId, request.question(), null);
            return Response.ok(result).build();
        } catch (ForbiddenException e) {
            throw e;
        } catch (IGroupConversationService.GroupTimeoutException e) {
            LOGGER.warn("Continue on " + sanitize(gcId) + " timed out", e);
            return Response.status(Response.Status.GATEWAY_TIMEOUT).type(TEXT_PLAIN)
                    .entity("A member agent did not respond in time. Try again.").build();
        } catch (IGroupConversationService.GroupExecutionException e) {
            // The round could not be executed — a member agent / model call failed, or the
            // group config is unrunnable. An upstream/server failure (5xx), not a retryable
            // conflict. Hedged body: this catch covers both the agent-call and config
            // paths.
            LOGGER.error("Continue on " + sanitize(gcId) + " failed to execute", e);
            return Response.status(Response.Status.BAD_GATEWAY).type(TEXT_PLAIN)
                    .entity("The continuation round could not be completed: a member agent or a required "
                            + "dependency could not be reached, or the group is misconfigured.")
                    .build();
        } catch (IGroupConversationService.GroupDiscussionException e) {
            // Base type now means only a state / concurrency conflict.
            LOGGER.infof("Continue on %s conflicted: %s", sanitize(gcId), sanitize(e.getMessage()));
            return Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN)
                    .entity("The continuation could not be applied: the conversation must be COMPLETED and have no "
                            + "other operation in progress.")
                    .build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("Group conversation not found.").build();
        } catch (Exception e) {
            LOGGER.errorf("Continue discussion failed: %s", e.getMessage());
            throw sneakyThrow(e);
        }
    }

    @Override
    public void continueDiscussionStreaming(String groupId, String gcId, DiscussRequest request,
                                            SseEventSink eventSink, Sse sse) {
        String rejection = request == null ? "'question' is required" : rejectionReason(request.question(), request.userId());
        if (rejection != null) {
            sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR,
                    toJson(new GroupConversationEventSink.GroupErrorEvent(rejection)));
            closeQuietly(eventSink);
            return;
        }
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            // See rejectAttachmentsOnContinue — a continuation cannot share new files.
            sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR,
                    toJson(new GroupConversationEventSink.GroupErrorEvent(
                            "Attachments cannot be added on a continuation round.")));
            closeQuietly(eventSink);
            return;
        }
        try {
            GroupConversation gc = loadInGroup(groupId, gcId);
            ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");

            // Reuse the shared streaming listener so a continuation that pauses or is
            // cancelled for HITL streams the awaiting_approval / cancelled /
            // member_pause_skipped events (and round_start) instead of silently
            // swallowing them and hanging the client.
            var listener = createStreamingListener(eventSink, sse);

            executorService.submit(() -> {
                try {
                    groupConversationService.continueDiscussion(gcId, request.question(), listener);
                } catch (Exception e) {
                    // Curated event text — never forward the raw message over SSE.
                    LOGGER.errorf("Continue discussion streaming failed: %s", e.getMessage());
                    listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(
                            "The continuation could not be completed."));
                }
            });

        } catch (ForbiddenException e) {
            throw e;
        } catch (IResourceStore.ResourceNotFoundException e) {
            sendErrorEvent(eventSink, sse, "Group conversation not found.");
            closeQuietly(eventSink);
        } catch (Exception e) {
            LOGGER.errorf("Continue discussion streaming setup failed: %s", e.getMessage());
            sendErrorEvent(eventSink, sse, "The continuation could not be started.");
            closeQuietly(eventSink);
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
        } catch (NotFoundException e) {
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
                | IGroupConversationStore.GroupConversationGoneException e) {
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
            LOGGER.infof("Close of %s conflicted: %s", sanitize(gcId), sanitize(e.getMessage()));
            return Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN)
                    .entity("Close not possible: the conversation must be COMPLETED, FAILED or CANCELLED "
                            + "(it may still be running or already closed), and no other operation may be in progress.")
                    .build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("Group conversation not found.").build();
        } catch (Exception e) {
            // Genuine store/DB failures (ResourceStoreException) map to 500 via the
            // global mapper — only business conflicts above are 409.
            LOGGER.errorf("Close group conversation failed: %s", e.getMessage());
            throw sneakyThrow(e);
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
        // I6: the READ guard, not the strict HITL guard — the pending HUMAN
        // member must be able to see the status of the turn they owe (their
        // rendered prompt lives in the summary below).
        hitlAccessGuard.requireGroupConversationReadAccess(groupId, gcId);
        try {
            var gc = groupConversationService.readGroupConversation(gcId);
            boolean paused = gc.getState() == GroupConversation.GroupConversationState.AWAITING_APPROVAL
                    || gc.getState() == GroupConversation.GroupConversationState.AWAITING_HUMAN_INPUT;
            // The approver full-view window is the APPROVAL pause only — `paused`
            // also covers AWAITING_HUMAN_INPUT, where nothing awaits an approver's
            // decision and the transcript would leak outside their remit (review
            // finding). Summary fields keep the wider predicate.
            boolean awaitingApproval = gc.getState() == GroupConversation.GroupConversationState.AWAITING_APPROVAL;
            if ("full".equals(detail)) {
                // Approver-only callers (not owner, not admin) may read the full
                // conversation (incl. transcript) only while it is actually awaiting
                // approval — mirrors the regular surface's read-scope gate. The
                // pending human member does NOT get the full view either: their
                // working material is the rendered prompt in the summary.
                if (!ownershipValidator.isAdmin(identity)
                        && !ownershipValidator.isOwner(identity, gc.getUserId())
                        && !(awaitingApproval && ownershipValidator.isApprover(identity))) {
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
                            .filter(t -> t.status() == SharedTaskList.TaskStatus.AWAITING_APPROVAL)
                            .map(SharedTaskList.TaskItem::id)
                            .toList()
                    : List.of();
            var summary = new LinkedHashMap<String, Object>();
            summary.put("groupConversationId", gcId);
            summary.put("state", gc.getState() != null ? gc.getState().name() : "");
            summary.put("pausedAt", paused && gc.getPausedAt() != null ? gc.getPausedAt().toString() : "");
            summary.put("pausedPhaseName", paused && gc.getPausedPhaseName() != null ? gc.getPausedPhaseName() : "");
            summary.put("pauseType", paused && gc.getHitlPauseType() != null ? gc.getHitlPauseType().name() : "");
            summary.put("pauseReason", paused && gc.getHitlPauseReason() != null ? gc.getHitlPauseReason() : "");
            summary.put("timeoutPolicy", paused && gc.getHitlTimeoutPolicy() != null ? gc.getHitlTimeoutPolicy().name() : "");
            summary.put("awaitingApprovalTaskIds", awaitingTaskIds);
            // I6: a human-turn pause carries WHO is up and WHAT they were asked —
            // the summary is that member's working view, no transcript needed.
            if (gc.getPendingHumanInput() != null) {
                summary.put("pendingMemberId", gc.getPendingHumanInput().memberId());
                summary.put("pendingMemberDisplayName", gc.getPendingHumanInput().displayName());
                summary.put("pendingHumanPrompt", gc.getPendingHumanInput().renderedPrompt());
            }
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
            throw new NotFoundException("Group conversation not found.");
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

            @Override
            public void onHitlPause(GroupConversationEventSink.HitlPauseEvent event) {
                sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_AWAITING_APPROVAL, toJson(event));
                closeQuietly(eventSink);
            }

            @Override
            public void onHumanInputRequested(GroupConversationEventSink.HumanInputRequestedEvent event) {
                // I6: terminal for THIS stream, like an approval pause — the leg
                // ends; a submission opens its own resumed stream if it wants one.
                sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_HUMAN_INPUT_REQUESTED, toJson(event));
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

            @Override
            public void onConvergenceChecked(GroupConversationEventSink.ConvergenceCheckedEvent event) {
                sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_CONVERGENCE_CHECKED, toJson(event));
            }

            @Override
            public void onConvergenceReached(GroupConversationEventSink.ConvergenceReachedEvent event) {
                // Not terminal — the discussion carries on with the phases that follow.
                sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_CONVERGENCE_REACHED, toJson(event));
            }

            @Override
            public void onDecisionReached(GroupConversationEventSink.DecisionReachedEvent event) {
                // Not terminal — a decision can be reached mid-discussion (e.g. a
                // debate verdict before a later synthesis phase).
                sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_DECISION_REACHED, toJson(event));
            }

            @Override
            public void onRetroRecorded(GroupConversationEventSink.RetroRecordedEvent event) {
                // Not terminal — a retro can precede further phases.
                sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_RETRO_RECORDED, toJson(event));
            }

            @Override
            public void onArtifactUpdated(GroupConversationEventSink.ArtifactUpdatedEvent event) {
                // Not terminal — artifacts are edited throughout the discussion.
                sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_ARTIFACT_UPDATED, toJson(event));
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
    public List<PendingApprovalSummary> listGroupPendingApprovals(String groupId, Integer limit) {
        // Scoped to the group in the path — the query-level filter keeps the listing
        // from leaking other groups.
        return listPendingApprovals(groupId, limit);
    }

    @Override
    public List<PendingApprovalSummary> listAllGroupPendingApprovals(Integer limit) {
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
    private List<PendingApprovalSummary> listPendingApprovals(String groupId, Integer limit) {
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
