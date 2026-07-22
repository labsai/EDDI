/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.*;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.engine.internal.RestAgentManagement.KEY_LANG;
import static ai.labs.eddi.engine.model.Context.ContextType.string;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotEmpty;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;

/**
 * Thin REST adapter — delegates all business logic to
 * {@link IConversationService}. v6: simplified paths — conversation-scoped
 * operations use only conversationId.
 */
@ApplicationScoped
public class RestAgentEngine implements IRestAgentEngine {

    private final IConversationService conversationService;
    private final IConversationMemoryStore conversationMemoryStore;
    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;
    private final ConversationAccessGuard conversationAccessGuard;
    private final HitlAccessGuard hitlAccessGuard;
    private final IHitlToolJournalStore hitlToolJournalStore;
    private final int agentTimeout;

    private static final Logger LOGGER = Logger.getLogger(RestAgentEngine.class);

    /** Mirrors QuotaExceededExceptionMapper; jakarta.ws.rs has no 429 constant. */
    private static final int TOO_MANY_REQUESTS = 429;

    @Inject
    public RestAgentEngine(IConversationService conversationService,
            IConversationMemoryStore conversationMemoryStore,
            SecurityIdentity identity,
            OwnershipValidator ownershipValidator,
            ConversationAccessGuard conversationAccessGuard,
            HitlAccessGuard hitlAccessGuard,
            IHitlToolJournalStore hitlToolJournalStore,
            @ConfigProperty(name = "systemRuntime.agentTimeoutInSeconds") int agentTimeout) {
        this.conversationService = conversationService;
        this.conversationMemoryStore = conversationMemoryStore;
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
        this.conversationAccessGuard = conversationAccessGuard;
        this.hitlAccessGuard = hitlAccessGuard;
        this.hitlToolJournalStore = hitlToolJournalStore;
        this.agentTimeout = agentTimeout;
    }

    @Override
    public Response startConversation(String agentId, Environment environment, String userId) {
        return startConversationWithContext(agentId, environment, userId, Collections.emptyMap());
    }

    @Override
    public Response startConversationWithContext(String agentId, Environment environment, String userId, Map<String, Context> context) {
        try {
            String resolvedUserId = ownershipValidator.validateAndResolveUserId(identity, userId);
            var result = conversationService.startConversation(environment, agentId, resolvedUserId, context);
            return Response.created(result.conversationUri()).build();
        } catch (ProcessingRestrictedException e) {
            LOGGER.warnf("GDPR processing restricted for user: %s", e.getMessage());
            return Response.status(Response.Status.FORBIDDEN).type(TEXT_PLAIN).entity(e.getMessage()).build();
        } catch (AgentNotReadyException e) {
            LOGGER.warn("Agent not ready: " + e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN).entity("Agent is not deployed or not ready").build();
        } catch (ResourceStoreException | ResourceNotFoundException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to start conversation");
        }
    }

    @Override
    public Response endConversation(String conversationId) {
        validateConversationOwnership(conversationId);
        // G4: attribute a pause-terminating end to the calling principal so it lands
        // in the hitl.approval audit trail (null-safe for anonymous callers).
        String endedBy = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
        conversationService.endConversation(conversationId, endedBy);
        return Response.ok().build();
    }

    @Override
    public SimpleConversationMemorySnapshot readConversation(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                                                             List<String> returningFields) {
        validateConversationOwnership(conversationId);
        try {
            return conversationService.readConversation(conversationId, returnDetailed, returnCurrentStepOnly, returningFields);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to read conversation");
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response readConversationLog(String conversationId, String outputType, Integer logSize) {
        validateConversationOwnership(conversationId);
        try {
            var result = conversationService.readConversationLog(conversationId, outputType, logSize);
            return Response.ok(result.content(), result.mediaType()).build();
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to read conversation log");
        }
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
        validateConversationOwnership(conversationId);
        return conversationService.getConversationState(conversationId);
    }

    @Override
    public void rerunLastConversationStep(String conversationId, String language, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                                          List<String> returningFields, final AsyncResponse response) {
        checkNotNull(conversationId, "conversationId");
        checkNotEmpty(language, "language");
        validateConversationOwnership(conversationId);

        sayInternal(conversationId, returnDetailed, returnCurrentStepOnly, returningFields,
                new InputData("", Map.of(KEY_LANG, new Context(string, language))), true, response);
    }

    @Override
    public void say(final String conversationId, final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
                    final List<String> returningFields, final String message, final AsyncResponse response) {

        sayWithinContext(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, new InputData(message, new HashMap<>()), response);
    }

    @Override
    public void sayWithinContext(final String conversationId, final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
                                 final List<String> returningFields, final InputData inputData, final AsyncResponse response) {

        checkNotNull(conversationId, "conversationId");
        checkNotNull(inputData, "inputData");
        checkNotNull(inputData.getInput(), "inputData.input");
        validateConversationOwnership(conversationId);

        sayInternal(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, false, response);
    }

    private void sayInternal(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields,
                             InputData inputData, boolean rerunOnly, AsyncResponse response) {

        response.setTimeout(agentTimeout, TimeUnit.SECONDS);
        response.setTimeoutHandler(asyncResp -> asyncResp.resume(Response.status(Response.Status.REQUEST_TIMEOUT).build()));

        // onSkipped: the queued turn was dropped without consuming the input
        // (pause/busy committed after the request was accepted) — answer honestly
        // with 409 instead of letting the request run into the timeout handler.
        var responseHandler = new ConversationResponseHandler() {
            @Override
            public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                response.resume(snapshot);
            }

            @Override
            public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                String reason = snapshot.getConversationState() == ConversationState.AWAITING_HUMAN
                        ? "Conversation is awaiting human approval — your message was not processed;"
                                + " a reviewer must resolve it via POST /agents/" + conversationId + "/resume (or cancel)"
                        : "Conversation is processing another turn — your message was not processed; retry shortly";
                response.resume(Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN).entity(reason).build());
            }
        };

        try {
            conversationService.say(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, rerunOnly, responseHandler);
        } catch (AgentMismatchException e) {
            LOGGER.warn("Agent mismatch for conversation " + conversationId + ": " + e.getMessage());
            response.resume(Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN).entity("Agent version mismatch").build());
        } catch (AgentNotReadyException e) {
            LOGGER.warn("Agent not ready for conversation " + conversationId + ": " + e.getMessage());
            response.resume(new NotFoundException("Agent is not deployed or not ready"));
        } catch (ConversationEndedException e) {
            response.resume(Response.status(Response.Status.GONE).entity("Conversation has ended").build());
        } catch (ConversationAwaitingApprovalException e) {
            // docs/hitl.md: say() into a paused conversation is REJECTED — promptly
            response.resume(Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN).entity(e.getMessage()).build());
        } catch (ProcessingRestrictedException e) {
            LOGGER.warnf("GDPR processing restricted: %s", e.getMessage());
            response.resume(Response.status(Response.Status.FORBIDDEN).type(TEXT_PLAIN).entity(e.getMessage()).build());
        } catch (ResourceNotFoundException e) {
            response.resume(new NotFoundException());
        } catch (QuotaExceededException e) {
            // Must be caught explicitly: say() is resumed through an AsyncResponse, so
            // the exception never reaches QuotaExceededExceptionMapper — without this
            // branch it fell through to the generic handler below and the api-call
            // quota surfaced as a 500 instead of a 429. Body and headers mirror the
            // mapper so both quota denials look identical to clients.
            LOGGER.warnf("Quota exceeded for conversation %s: %s", sanitize(conversationId), e.getMessage());
            response.resume(Response.status(TOO_MANY_REQUESTS)
                    .entity(Map.of("error", "quota_exceeded", "message", e.getMessage()))
                    .type(MediaType.APPLICATION_JSON).header("Retry-After", "60").build());
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("An internal error occurred");
        }
    }

    @Override
    public Boolean isUndoAvailable(String conversationId) {
        validateConversationOwnership(conversationId);
        try {
            return conversationService.isUndoAvailable(conversationId);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to check undo availability");
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response undo(final String conversationId) {
        validateConversationOwnership(conversationId);
        try {
            boolean performed = conversationService.undo(conversationId);
            return performed ? Response.ok().build() : Response.status(Response.Status.CONFLICT).build();
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to undo");
        }
    }

    @Override
    public Boolean isRedoAvailable(final String conversationId) {
        validateConversationOwnership(conversationId);
        try {
            return conversationService.isRedoAvailable(conversationId);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to check redo availability");
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response redo(final String conversationId) {
        validateConversationOwnership(conversationId);
        try {
            boolean performed = conversationService.redo(conversationId);
            return performed ? Response.ok().build() : Response.status(Response.Status.CONFLICT).build();
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to redo");
        }
    }

    @Override
    public Response cancelConversation(String conversationId) {
        validateConversationOwnership(conversationId, true);
        try {
            String cancelledBy = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
            var outcome = conversationService.cancelConversation(conversationId,
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL, cancelledBy);
            // Plain-text, curated bodies: never reflect the raw conversationId (it is
            // a caller-supplied path param — echoing it is a reflected-XSS vector) and
            // never leak internal exception detail to the client.
            return switch (outcome) {
                case CANCELLED -> Response.ok().build();
                case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND)
                        .type(TEXT_PLAIN).entity("Conversation not found.").build();
                case NOTHING_TO_CANCEL -> Response.status(Response.Status.CONFLICT)
                        .type(TEXT_PLAIN)
                        .entity("Nothing to cancel: conversation is neither awaiting approval nor executing."
                                + " Use endConversation to close it.")
                        .build();
            };
        } catch (ResourceStoreException e) {
            LOGGER.error("Failed to cancel conversation: " + sanitize(conversationId), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(TEXT_PLAIN).entity("Failed to cancel conversation.").build();
        }
    }

    /** Upper bound for the free-text reviewer note persisted with a decision. */
    private static final int MAX_HITL_NOTE_LENGTH = HitlDecision.MAX_NOTE_LENGTH;

    @Override
    public Response resumeConversation(String conversationId, HitlDecision decision) {
        if (decision == null || decision.getVerdict() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Request body must include a 'verdict' field (APPROVED or REJECTED)")
                    .build();
        }
        if (decision.getNote() != null && decision.getNote().length() > MAX_HITL_NOTE_LENGTH) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Decision note exceeds the maximum length of " + MAX_HITL_NOTE_LENGTH + " characters")
                    .build();
        }
        validateConversationOwnership(conversationId, true);
        // Null-safe principal (parity with the cancel/end paths): an anonymous caller
        // must not NPE — the decision is attributed to "unknown" downstream.
        String userId = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
        decision.setDecidedBy(userId);
        try {
            conversationService.resumeConversation(conversationId, decision, null);
            return Response.ok().build();
        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("Conversation not found.").build();
        } catch (IllegalArgumentException e) {
            // Task 7: toolDecisions validation (unknown callId, missing per-call
            // verdict, amendedArguments misuse, …) — validated BEFORE the resume CAS,
            // so the pause was never consumed. Mirrors the group taskApprovals 400
            // precedent (RestGroupConversation).
            LOGGER.infof("Resume of conversation %s rejected (invalid request): %s", sanitize(conversationId), e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).type(TEXT_PLAIN)
                    .entity(e.getMessage()).build();
        } catch (IllegalStateException e) {
            // wrong state (already resumed/cancelled/timed out, agent not deployed).
            // Contract (docs/hitl.md): the 409 body names the CURRENT state so the
            // client knows why. Build it from the safe ConversationState enum — not
            // the raw exception message (avoids the CodeQL error-exposure sink while
            // preserving the documented, useful state hint).
            String currentState;
            try {
                currentState = conversationService.getConversationState(conversationId).name();
            } catch (RuntimeException lookupFailure) {
                currentState = "unknown";
            }
            return Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN)
                    .entity("Conversation is not in a resumable state (current state: " + currentState
                            + ") — it may have been resumed, cancelled, or timed out already, or its agent is not deployed.")
                    .build();
        } catch (ResourceStoreException e) {
            // genuine infrastructure failure — the pause was restored by the service
            LOGGER.error("Resume failed for conversation " + sanitize(conversationId), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).type(TEXT_PLAIN)
                    .entity("Failed to resume conversation.").build();
        }
    }

    @Override
    public Response getApprovalStatus(String conversationId, String detail) {
        String ownerId = validateConversationOwnership(conversationId, true);
        try {
            var snapshot = conversationService.getConversationMemorySnapshot(conversationId);
            boolean paused = snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
            if ("full".equals(detail)) {
                // Approver-only callers (not owner, not admin) may read the full
                // memory only while the conversation is actually awaiting approval —
                // the approver role exists to decide pending approvals, not as a
                // universal read-everything grant over all conversations.
                if (!paused && !ownershipValidator.isAdmin(identity)
                        && !ownershipValidator.isOwner(identity, ownerId)) {
                    return Response.status(Response.Status.FORBIDDEN)
                            .entity("Full approval status is available to approvers only while the conversation "
                                    + "is awaiting approval — use the summary view")
                            .build();
                }
                return Response.ok(snapshot).build();
            }
            // Bookmark fields describe the pause — suppress them once the
            // conversation left AWAITING_HUMAN so stale fields (e.g. after a
            // cancel that predates bookmark clearing) never mislead dashboards.
            // approvalTimeout lets a UI render the auto-decision deadline
            // (pausedAt + approvalTimeout) without extra round trips.
            var summary = new LinkedHashMap<String, Object>();
            summary.put("conversationId", conversationId);
            summary.put("state", snapshot.getConversationState().name());
            summary.put("pausedAt", paused && snapshot.getHitlPausedAt() != null ? snapshot.getHitlPausedAt().toString() : "");
            summary.put("pauseReason", paused && snapshot.getHitlPauseReason() != null ? snapshot.getHitlPauseReason() : "");
            summary.put("timeoutPolicy", paused && snapshot.getHitlTimeoutPolicy() != null ? snapshot.getHitlTimeoutPolicy().name() : "");
            summary.put("approvalTimeout", paused && snapshot.getHitlApprovalTimeout() != null ? snapshot.getHitlApprovalTimeout() : "");
            // Structured pauseDetails (Task 11) — computed at read time, never
            // persisted. Absent entirely once the conversation is no longer
            // paused, same suppression rule as the bookmark fields above.
            summary.put("pauseDetails", paused ? buildPauseDetails(conversationId, snapshot) : null);
            return Response.ok(summary).build();
        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN)
                    .entity("Conversation not found.").build();
        } catch (ResourceStoreException e) {
            LOGGER.error("Failed to read approval status for conversation " + sanitize(conversationId), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).type(TEXT_PLAIN)
                    .entity("Failed to read approval status.").build();
        }
    }

    /**
     * Builds the structured {@code pauseDetails} object for a paused conversation
     * (Task 11) — computed at read time from the snapshot, never persisted.
     * TOOL_CALL pauses expose ONLY the redacted arguments (never
     * {@code argumentsRaw}); RULE pauses (and legacy null-pauseType snapshots)
     * expose the pause reason and the ACTIONS data of the paused step.
     */
    private Map<String, Object> buildPauseDetails(String conversationId, ConversationMemorySnapshot snapshot) {
        String pauseType = snapshot.getHitlPauseType();
        if ("TOOL_CALL".equals(pauseType)) {
            return buildToolCallPauseDetails(conversationId, snapshot);
        }
        return buildRulePauseDetails(snapshot);
    }

    private Map<String, Object> buildToolCallPauseDetails(String conversationId, ConversationMemorySnapshot snapshot) {
        var details = new LinkedHashMap<String, Object>();
        details.put("type", "TOOL_CALL");

        PendingToolCallBatch batch = snapshot.getHitlPendingToolCalls();
        List<PendingToolCall> pendingCalls = batch != null && batch.getCalls() != null
                ? batch.getCalls()
                : List.of();
        String pauseEpoch = batch != null ? batch.getPauseEpoch() : null;

        List<Map<String, Object>> calls = new ArrayList<>();
        List<String> outcomeUnknown = new ArrayList<>();
        for (PendingToolCall call : pendingCalls) {
            var callView = new LinkedHashMap<String, Object>();
            callView.put("callId", call.getCallId());
            callView.put("toolName", call.getToolName());
            callView.put("source", call.getSource());
            // ONLY the redacted, capped value is ever surfaced here — never
            // call.getArgumentsRaw().
            callView.put("arguments", call.getArgumentsRedacted());
            callView.put("argsTruncated", call.isArgsTruncated());
            callView.put("gateReason", call.getGateReason());
            calls.add(callView);

            if (pauseEpoch != null && call.getCallId() != null) {
                hitlToolJournalStore.find(conversationId, pauseEpoch, call.getCallId())
                        .filter(entry -> entry.status() == IHitlToolJournalStore.Status.EXECUTING)
                        .ifPresent(entry -> outcomeUnknown.add(call.getCallId()));
            }
        }
        details.put("calls", calls);
        details.put("executedUngatedCalls",
                batch != null && batch.getExecutedUngatedCallNames() != null
                        ? batch.getExecutedUngatedCallNames()
                        : List.of());
        details.put("outcomeUnknown", outcomeUnknown);
        return details;
    }

    private Map<String, Object> buildRulePauseDetails(ConversationMemorySnapshot snapshot) {
        var details = new LinkedHashMap<String, Object>();
        details.put("type", "RULE");
        details.put("reason", snapshot.getHitlPauseReason());
        details.put("actions", findPausedStepActions(snapshot));
        return details;
    }

    /**
     * The ACTIONS data of the most recent (paused) conversation step — read-time
     * lookup over the snapshot's step history, no new persistence.
     * <p>
     * {@code ConversationMemorySnapshot.getConversationSteps()} is built by
     * {@code ConversationMemoryUtilities.convertConversationMemory} in
     * REVERSE-chronological order (index 0 = most recent step), each holding a
     * single {@code WorkflowRunSnapshot} whose {@code lifecycleTasks} preserve the
     * original insertion order — so within that step, the LAST matching "actions"
     * entry is the most recent one (same semantics as
     * {@code IConversationStep.getLatestData}).
     */
    private List<String> findPausedStepActions(ConversationMemorySnapshot snapshot) {
        List<ConversationStepSnapshot> steps = snapshot.getConversationSteps();
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        for (ConversationStepSnapshot step : steps) {
            List<String> latestActionsInStep = null;
            for (WorkflowRunSnapshot workflow : step.getWorkflows()) {
                for (ResultSnapshot data : workflow.getLifecycleTasks()) {
                    if ("actions".equals(data.getKey()) && data.getResult() instanceof List<?> actions) {
                        @SuppressWarnings("unchecked")
                        List<String> typed = (List<String>) actions;
                        latestActionsInStep = typed;
                    }
                }
            }
            if (latestActionsInStep != null) {
                return latestActionsInStep;
            }
        }
        return List.of();
    }

    @Override
    public List<PendingApprovalSummary> listPendingApprovals(Integer limit) {
        try {
            int effectiveLimit = limit != null ? limit : 200;
            // Owner-scoping (admins/approvers see all; others see only their own;
            // anonymous sees nothing) lives in HitlAccessGuard so the MCP surface
            // shares the exact same rule verbatim.
            return hitlAccessGuard.listScopedPendingApprovals(effectiveLimit);
        } catch (ResourceStoreException e) {
            throw new InternalServerErrorException(e.getMessage(), e);
        }
    }

    /**
     * Validates that the caller owns the conversation identified by
     * {@code conversationId}. Admin role bypasses the check. If the descriptor
     * cannot be loaded due to a store error, access is denied (fail-closed). If the
     * descriptor is not found, the check is skipped and the actual operation will
     * handle the 404.
     */
    private void validateConversationOwnership(String conversationId) {
        validateConversationOwnership(conversationId, false);
    }

    /**
     * @param hitlOperation
     *            if true, uses strict ownership + approver role check
     * @return the conversation owner's userId, or {@code null} if the descriptor
     *         was not found (the actual operation handles the 404)
     */
    private String validateConversationOwnership(String conversationId, boolean hitlOperation) {
        if (hitlOperation) {
            // Strict HITL ownership (owner/admin/approver, fail-closed on a missing
            // descriptor) is shared with the MCP surface via HitlAccessGuard.
            return hitlAccessGuard.requireConversationHitlAccess(conversationId);
        }
        // Plain owner-or-admin access is shared with the MCP surface via
        // ConversationAccessGuard, so REST and MCP cannot drift apart on who may
        // read or drive a conversation.
        return conversationAccessGuard.requireConversationOwner(conversationId);
    }

    @Override
    public Response resetState(String conversationId, String targetState) {
        if (!"READY".equalsIgnoreCase(targetState)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Only READY is supported as target state"))
                    .build();
        }
        try {
            var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            var currentState = snapshot.getConversationState();
            if (currentState == ConversationState.IN_PROGRESS) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "Cannot reset while conversation is IN_PROGRESS"))
                        .build();
            }
            if (currentState == ConversationState.ENDED) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "Cannot reset an ENDED conversation"))
                        .build();
            }
            if (currentState == ConversationState.READY) {
                return Response.ok(Map.of("message", "Conversation already in READY state")).build();
            }
            conversationService.resetConversationState(conversationId, ConversationState.READY);
            LOGGER.infof("Admin reset conversation %s from %s to READY", conversationId, currentState);
            return Response.ok(Map.of("message", "State reset from " + currentState + " to READY")).build();
        } catch (ResourceStoreException e) {
            LOGGER.errorf(e, "Failed to reset conversation state for %s", conversationId);
            throw new InternalServerErrorException("Failed to reset state");
        } catch (ResourceNotFoundException e) {
            throw new NotFoundException("Conversation not found: " + conversationId);
        }
    }
}
