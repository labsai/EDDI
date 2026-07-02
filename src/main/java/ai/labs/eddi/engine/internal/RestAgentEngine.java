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
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.Collections;
import java.util.HashMap;
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
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;
    private final int agentTimeout;

    private static final Logger LOGGER = Logger.getLogger(RestAgentEngine.class);

    @Inject
    public RestAgentEngine(IConversationService conversationService,
            IConversationDescriptorStore conversationDescriptorStore,
            SecurityIdentity identity,
            OwnershipValidator ownershipValidator,
            @ConfigProperty(name = "systemRuntime.agentTimeoutInSeconds") int agentTimeout) {
        this.conversationService = conversationService;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
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
        conversationService.endConversation(conversationId);
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

        try {
            conversationService.say(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, rerunOnly, response::resume);
        } catch (AgentMismatchException e) {
            LOGGER.warn("Agent mismatch for conversation " + conversationId + ": " + e.getMessage());
            response.resume(Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN).entity("Agent version mismatch").build());
        } catch (AgentNotReadyException e) {
            LOGGER.warn("Agent not ready for conversation " + conversationId + ": " + e.getMessage());
            response.resume(new NotFoundException("Agent is not deployed or not ready"));
        } catch (ConversationEndedException e) {
            response.resume(Response.status(Response.Status.GONE).entity("Conversation has ended").build());
        } catch (ProcessingRestrictedException e) {
            LOGGER.warnf("GDPR processing restricted: %s", e.getMessage());
            response.resume(Response.status(Response.Status.FORBIDDEN).type(TEXT_PLAIN).entity(e.getMessage()).build());
        } catch (ResourceNotFoundException e) {
            response.resume(new NotFoundException());
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
            var outcome = conversationService.cancelConversation(conversationId,
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL);
            return switch (outcome) {
                case CANCELLED -> Response.ok().build();
                case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND)
                        .entity("Conversation not found: " + conversationId).build();
                case NOTHING_TO_CANCEL -> Response.status(Response.Status.CONFLICT)
                        .entity("Nothing to cancel: conversation is neither awaiting approval nor executing."
                                + " Use endConversation to close it.")
                        .build();
            };
        } catch (ResourceStoreException e) {
            LOGGER.error("Failed to cancel conversation: " + conversationId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    /** Upper bound for the free-text reviewer note persisted with a decision. */
    private static final int MAX_HITL_NOTE_LENGTH = 4096;

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
        String userId = identity.getPrincipal().getName();
        decision.setDecidedBy(userId);
        try {
            conversationService.resumeConversation(conversationId, decision, null);
            return Response.ok().build();
        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (IllegalStateException e) {
            // wrong state (already resumed/cancelled/timed out, agent not deployed)
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (ResourceStoreException e) {
            // genuine infrastructure failure — the pause was restored by the service
            LOGGER.error("Resume failed for conversation " + conversationId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
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
            var summary = Map.of(
                    "conversationId", conversationId,
                    "state", snapshot.getConversationState().name(),
                    "pausedAt", paused && snapshot.getHitlPausedAt() != null ? snapshot.getHitlPausedAt().toString() : "",
                    "pauseReason", paused && snapshot.getHitlPauseReason() != null ? snapshot.getHitlPauseReason() : "",
                    "timeoutPolicy", paused && snapshot.getHitlTimeoutPolicy() != null ? snapshot.getHitlTimeoutPolicy() : "");
            return Response.ok(summary).build();
        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (ResourceStoreException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @Override
    public List<PendingApprovalSummary> listPendingApprovals(Integer limit) {
        try {
            var all = conversationService.listPendingApprovals(limit != null ? limit : 200);

            // MAJOR-6: Admins and designated approvers see all; other callers see
            // only their own conversations (an approver who can decide approvals
            // must also be able to list them).
            if (ownershipValidator.isAdmin(identity) || ownershipValidator.isApprover(identity)) {
                return all;
            }

            String callerId = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
            if (callerId == null || callerId.isBlank()) {
                return List.of(); // Fail-closed: anonymous user sees nothing
            }

            // Ownership from the projected summary (no per-row descriptor reads);
            // fail-closed: summaries without an owner are excluded for non-admins.
            return all.stream()
                    .filter(summary -> callerId.equals(summary.getUserId()))
                    .toList();
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
        try {
            var descriptor = conversationDescriptorStore.readDescriptor(conversationId, 0);
            if (hitlOperation) {
                ownershipValidator.requireOwnerAdminOrApprover(identity, descriptor.getUserId(), "conversation");
            } else {
                ownershipValidator.requireOwnerOrAdmin(identity, descriptor.getUserId(), "conversation");
            }
            return descriptor.getUserId();
        } catch (ForbiddenException e) {
            throw e;
        } catch (ResourceNotFoundException e) {
            // Descriptor not found — let the actual operation handle it
            LOGGER.debugf("Conversation descriptor not found for %s", sanitize(conversationId));
            return null;
        } catch (ResourceStoreException e) {
            // Fail-closed: cannot verify ownership → deny access
            LOGGER.warnf("Could not load conversation descriptor for ownership check: %s", sanitize(conversationId));
            throw new ForbiddenException("Access denied: unable to verify conversation ownership");
        }
    }
}
