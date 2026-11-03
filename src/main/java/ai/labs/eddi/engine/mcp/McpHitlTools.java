/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;
import ai.labs.eddi.configs.groups.IGroupConversationStore;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.internal.groups.HumanTieBreakEngine;
import ai.labs.eddi.engine.internal.groups.PendingHumanInputView;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.NotFoundException;
import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.escapeJsonString;
import static ai.labs.eddi.engine.mcp.McpToolUtils.parseIntOrDefault;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;

/**
 * MCP tools for resolving Human-in-the-Loop (HITL) approval gates over the MCP
 * server — the counterpart to the REST HITL endpoints. Lets an external MCP
 * client list, read, resume/approve, and cancel paused conversations and group
 * discussions.
 *
 * <p>
 * MCP is a transport, not a new authority: authorization mirrors the REST
 * surface exactly — first the {@link McpRoles#HITL} role tier, the same
 * {@code @RolesAllowed} the REST HITL endpoints carry (so {@code eddi-viewer},
 * which REST refuses at the door, is refused here too), then the
 * per-conversation owner/admin/approver check in {@link HitlAccessGuard}; a
 * decision is always attributed to the authenticated caller (prefixed
 * {@code mcp:}, never taken from a tool argument). Mutating tools additionally
 * honour the {@code eddi.mcp.hitl.mutations.enabled} kill-switch. These tools
 * resolve both {@code RULE} and {@code TOOL_CALL} pauses — both are
 * {@code AWAITING_HUMAN} and both are decided by a single verdict.
 */
@ApplicationScoped
@McpErrorResults
public class McpHitlTools {

    private static final Logger LOGGER = Logger.getLogger(McpHitlTools.class);

    private final IConversationService conversationService;
    private final IGroupConversationService groupConversationService;
    private final HitlAccessGuard hitlAccessGuard;
    private final OwnershipValidator ownershipValidator;
    private final ResourceAccessGuard resourceAccessGuard;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final MeterRegistry meterRegistry;
    private final boolean authEnabled;
    private final boolean mutationsEnabled;

    @Inject
    public McpHitlTools(IConversationService conversationService,
            IGroupConversationService groupConversationService,
            HitlAccessGuard hitlAccessGuard,
            OwnershipValidator ownershipValidator,
            ResourceAccessGuard resourceAccessGuard,
            IJsonSerialization jsonSerialization,
            SecurityIdentity identity,
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled,
            @ConfigProperty(name = "eddi.mcp.hitl.mutations.enabled", defaultValue = "true") boolean mutationsEnabled) {
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.hitlAccessGuard = hitlAccessGuard;
        this.ownershipValidator = ownershipValidator;
        this.resourceAccessGuard = resourceAccessGuard;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.meterRegistry = meterRegistry;
        this.authEnabled = authEnabled;
        this.mutationsEnabled = mutationsEnabled;
    }

    /**
     * Server-side actor attribution; {@code mcp:} channel prefix mirrors the
     * existing {@code system:timeout} convention.
     */
    String principalWithMcpPrefix() {
        String name = identity != null && identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
        return "mcp:" + (name != null && !name.isBlank() ? name : "anonymous");
    }

    /**
     * Case-insensitive verdict parse; null for null/blank/unrecognized — never
     * throws.
     */
    static HitlVerdict parseVerdictOrNull(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            return null;
        }
        try {
            return HitlVerdict.fromString(verdict);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns a DISABLED error JSON when the mutation kill-switch is off, else
     * null.
     */
    private String disabledIfMutationsOff() {
        if (!mutationsEnabled) {
            return errorJson("HITL mutations are disabled over MCP", "DISABLED",
                    Map.of("property", "eddi.mcp.hitl.mutations.enabled"));
        }
        return null;
    }

    // ==================================================================
    // Regular (1:1) surface
    // ==================================================================

    @Tool(name = "list_pending_approvals",
          description = "List regular (1:1) conversations awaiting human approval. Admins and approvers see all; "
                  + "other callers see only their own; unauthenticated callers see nothing. Includes RULE and "
                  + "TOOL_CALL pauses.")
    public String listPendingApprovals(
                                       @ToolArg(description = "Max entries to return (default: 200, capped at 1000)", required = false,
                                                defaultValue = "200") String limit) {
        requireAnyRole(identity, authEnabled, McpRoles.HITL);
        try {
            int effectiveLimit = parseIntOrDefault(limit, 200);
            var result = hitlAccessGuard.listScopedPendingApprovals(effectiveLimit);
            meterRegistry.counter("eddi.mcp.hitl.pending.listed", "surface", "regular").increment();
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.warn("MCP list_pending_approvals failed", e);
            return errorJson("Failed to list pending approvals", "INTERNAL", null);
        }
    }

    /**
     * MCP mirror of {@code GET /agents/{id}/approval-status}. {@code detail=full}
     * must serve exactly what the REST surface serves: the snapshot passed through
     * {@link ConversationMemoryUtilities#sanitizePendingToolCallsForApprover},
     * never a partial projection of it — the raw tool arguments and the frozen LLM
     * transcript on the pending batch are resume machinery, not approver material.
     */
    @Tool(name = "get_approval_status",
          description = "Read the approval status of a paused regular conversation. detail=summary (default) returns "
                  + "pause metadata incl. pauseType (RULE or TOOL_CALL); detail=full returns the full memory snapshot "
                  + "(incl. any pending tool-call batch) — owner/admin, or approver only while awaiting approval.")
    public String getApprovalStatus(
                                    @ToolArg(description = "Conversation ID") String conversationId,
                                    @ToolArg(description = "'summary' or 'full' (default: summary)", required = false,
                                             defaultValue = "summary") String detail) {
        requireAnyRole(identity, authEnabled, McpRoles.HITL);
        if (conversationId == null || conversationId.isBlank()) {
            return errorJson("conversationId is required", "BAD_REQUEST", null);
        }
        try {
            String ownerId = hitlAccessGuard.requireConversationHitlAccess(conversationId);
            // Same reads as the REST surface: the full snapshot (less the resume-only
            // batch fields the projection strips anyway) for detail=full, the history-free
            // tail for the summary, which reads only the header.
            boolean full = "full".equals(detail);
            ConversationMemorySnapshot snapshot = full
                    ? conversationService.getConversationMemorySnapshotForApprover(conversationId)
                    : conversationService.getConversationMemoryTail(conversationId).snapshot();
            boolean paused = snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
            if (full) {
                if (!paused && !ownershipValidator.isAdmin(identity) && !ownershipValidator.isOwner(identity, ownerId)) {
                    return errorJson("Full approval status is available to approvers only while awaiting approval — "
                            + "use the summary view", "FORBIDDEN", null);
                }
                // The SAME approver projection the REST surface serves, through the
                // same method — not a local subset of it. This door used to strip
                // only the fingerprint, so argumentsRaw, the frozen LLM transcript
                // and the running trace (all carrying raw tool arguments) reached
                // every MCP caller the gate above admits.
                return jsonSerialization.serialize(ConversationMemoryUtilities.sanitizePendingToolCallsForApprover(snapshot));
            }
            Map<String, String> summary = new LinkedHashMap<>();
            summary.put("conversationId", conversationId);
            summary.put("state", snapshot.getConversationState() != null ? snapshot.getConversationState().name() : "");
            // pauseType distinguishes a RULE (PAUSE_CONVERSATION) pause from a TOOL_CALL
            // pause (null legacy snapshots => RULE). The pending tool-call batch itself is
            // only exposed via detail=full.
            summary.put("pauseType", paused
                    ? (snapshot.getHitlPauseType() != null ? snapshot.getHitlPauseType() : "RULE")
                    : "");
            summary.put("pausedAt", paused && snapshot.getHitlPausedAt() != null ? snapshot.getHitlPausedAt().toString() : "");
            // The id a decision passes back as HitlDecision.pauseId, so it applies only
            // to the pause the reviewer is looking at.
            summary.put("pauseId", paused && snapshot.getHitlPausedAt() != null ? HitlDecision.pauseIdOf(snapshot.getHitlPausedAt()) : "");
            summary.put("pauseReason", paused && snapshot.getHitlPauseReason() != null ? snapshot.getHitlPauseReason() : "");
            summary.put("timeoutPolicy", paused && snapshot.getHitlTimeoutPolicy() != null ? snapshot.getHitlTimeoutPolicy().name() : "");
            summary.put("approvalTimeout", paused && snapshot.getHitlApprovalTimeout() != null ? snapshot.getHitlApprovalTimeout() : "");
            return jsonSerialization.serialize(summary);
        } catch (ForbiddenException e) {
            return errorJson("Access denied", "FORBIDDEN", null);
        } catch (ResourceNotFoundException e) {
            return errorJson("Conversation not found", "NOT_FOUND", null);
        } catch (Exception e) {
            LOGGER.warn("MCP get_approval_status failed", e);
            return errorJson("Failed to read approval status", "INTERNAL", null);
        }
    }

    /** {@code resume_conversation} without a {@code pauseId}. */
    public String resumeConversation(String conversationId, String verdict, String note) {
        return resumeConversation(conversationId, verdict, note, null);
    }

    /**
     * {@code resume_conversation} without the review arguments (6.7).
     */
    public String resumeConversation(String conversationId, String verdict, String note, String pauseId) {
        return resumeConversation(conversationId, verdict, note, pauseId, null, null, null);
    }

    /**
     * {@code pauseId} is optional. Passed, the decision applies only to that pause:
     * a conversation that has since been resumed and paused again answers
     * {@code PAUSE_CHANGED} instead of approving a request the caller never saw.
     * {@code editedAnswer}, {@code reasonCode} and {@code toolDecisionsJson} (6.7)
     * are the REST resume body's fields, validated the same way before the pause is
     * consumed.
     */
    @Tool(name = "resume_conversation",
          description = "Resume a paused regular conversation with a human decision. verdict=APPROVED or REJECTED "
                  + "(case-insensitive). Resolves both RULE and TOOL_CALL pauses. The decision is attributed to the "
                  + "authenticated caller. Pass pauseId (from get_approval_status) to bind the decision to the pause "
                  + "you reviewed: if the conversation has since paused again on a different request, the decision "
                  + "is refused with errorCode PAUSE_CHANGED. Optional: editedAnswer approves a RULE pause with your "
                  + "own final answer (agents with hitlConfig.review.allowAnswerEdits); reasonCode explains a rejection "
                  + "or an edit; toolDecisionsJson decides TOOL_CALL pauses per call "
                  + "({\"<callId>\":{\"verdict\":\"APPROVED\",\"amendedArguments\":{...},\"reasonCode\":\"TONE\"}}). "
                  + "Tool arguments are logged by MCP clients — use the REST resume endpoint for long or sensitive edits.")
    public String resumeConversation(
                                     @ToolArg(description = "Conversation ID awaiting approval") String conversationId,
                                     @ToolArg(description = "APPROVED or REJECTED (case-insensitive)") String verdict,
                                     @ToolArg(description = "Reviewer note, max 4096 chars (optional)", required = false) String note,
                                     @ToolArg(description = "pauseId from get_approval_status — the pause this decision is for",
                                              required = false) String pauseId,
                                     @ToolArg(description = "APPROVED only: your final answer in place of the model's draft "
                                             + "(RULE pauses of agents with hitlConfig.review.allowAnswerEdits)",
                                              required = false) String editedAnswer,
                                     @ToolArg(description = "Reason code for a rejection or an edit (pauseDetails.reasonCodes)",
                                              required = false) String reasonCode,
                                     @ToolArg(description = "TOOL_CALL pauses: JSON object of callId to {verdict, note, "
                                             + "amendedArguments, reasonCode}",
                                              required = false) String toolDecisionsJson) {
        requireAnyRole(identity, authEnabled, McpRoles.HITL);
        String disabled = disabledIfMutationsOff();
        if (disabled != null) {
            return disabled;
        }
        if (conversationId == null || conversationId.isBlank()) {
            return errorJson("conversationId is required", "BAD_REQUEST", null);
        }
        HitlVerdict parsed = parseVerdictOrNull(verdict);
        if (parsed == null) {
            return errorJson("Invalid verdict; use APPROVED or REJECTED", "BAD_REQUEST", null);
        }
        if (note != null && note.length() > HitlDecision.MAX_NOTE_LENGTH) {
            return errorJson("Note exceeds the maximum length of " + HitlDecision.MAX_NOTE_LENGTH + " characters",
                    "BAD_REQUEST", null);
        }
        try {
            hitlAccessGuard.requireConversationHitlAccess(conversationId);
            HitlDecision decision = new HitlDecision();
            decision.setVerdict(parsed);
            decision.setNote(note);
            decision.setDecidedBy(principalWithMcpPrefix());
            decision.setPauseId(pauseId != null && !pauseId.isBlank() ? pauseId.trim() : null);
            McpReviewArguments.applyReview(decision, editedAnswer, reasonCode);
            decision.setToolDecisions(McpReviewArguments.toolDecisions(jsonSerialization, toolDecisionsJson));
            conversationService.resumeConversation(conversationId, decision, null);
            meterRegistry.counter("eddi.mcp.hitl.decision", "surface", "regular", "verdict", parsed.name()).increment();
            return "{\"status\":\"RESUMED\",\"conversationId\":\"" + escapeJsonString(conversationId)
                    + "\",\"verdict\":\"" + parsed.name() + "\"}";
        } catch (ForbiddenException e) {
            return errorJson("Access denied", "FORBIDDEN", null);
        } catch (ResourceNotFoundException e) {
            return errorJson("Conversation not found", "NOT_FOUND", null);
        } catch (IConversationService.PauseMismatchException e) {
            return errorJson("The pending approval changed since this decision was made — re-read get_approval_status "
                    + "and decide again", "PAUSE_CHANGED", null);
        } catch (McpReviewArguments.BadArgument e) {
            return errorJson(e.getMessage(), "BAD_REQUEST", null);
        } catch (IllegalArgumentException e) {
            // The service's pre-CAS validation (tool decisions, edit, reason code):
            // nothing was consumed. Its messages name fields and codes, never values.
            return errorJson("Invalid decision: " + e.getMessage(), "BAD_REQUEST", null);
        } catch (IllegalStateException e) {
            String state;
            try {
                state = conversationService.getConversationState(conversationId).name();
            } catch (RuntimeException lookupFailure) {
                state = "unknown";
            }
            return errorJson("Conversation is not in a resumable state — it may have been resumed, cancelled, or timed "
                    + "out already", "WRONG_STATE", Map.of("currentState", state));
        } catch (ResourceStoreException e) {
            LOGGER.warn("MCP resume_conversation failed", e);
            return errorJson("Failed to resume conversation", "INTERNAL", null);
        }
    }

    @Tool(name = "cancel_conversation",
          description = "Cancel a paused or running regular conversation. Attributed to the authenticated caller.")
    public String cancelConversation(
                                     @ToolArg(description = "Conversation ID") String conversationId) {
        requireAnyRole(identity, authEnabled, McpRoles.HITL);
        String disabled = disabledIfMutationsOff();
        if (disabled != null) {
            return disabled;
        }
        if (conversationId == null || conversationId.isBlank()) {
            return errorJson("conversationId is required", "BAD_REQUEST", null);
        }
        try {
            String ownerId = hitlAccessGuard.requireConversationHitlAccess(conversationId);
            IConversationService.CancelOutcome outcome;
            if (hitlAccessGuard.mayCancelExecuting(ownerId)) {
                outcome = conversationService.cancelConversation(conversationId,
                        ControlSignal.CANCEL_GRACEFUL, principalWithMcpPrefix());
            } else {
                // Approver-only caller: the same paused-only compare-and-set the REST
                // surface uses (L9 of the 2026-10-15 security review), so an approver
                // cannot stop another user's executing turn over MCP either.
                outcome = conversationService.cancelPausedConversation(conversationId, principalWithMcpPrefix());
                if (outcome == IConversationService.CancelOutcome.NOTHING_TO_CANCEL) {
                    return errorJson("Access denied: an approver may only cancel a conversation that is awaiting a human decision",
                            "FORBIDDEN", null);
                }
            }
            meterRegistry.counter("eddi.mcp.hitl.cancelled", "surface", "regular").increment();
            return switch (outcome) {
                case CANCELLED -> "{\"status\":\"CANCELLED\"}";
                case NOT_FOUND -> errorJson("Conversation not found", "NOT_FOUND", null);
                case NOTHING_TO_CANCEL -> errorJson(
                        "Nothing to cancel: conversation is neither awaiting approval nor executing",
                        "WRONG_STATE", null);
            };
        } catch (ForbiddenException e) {
            return errorJson("Access denied", "FORBIDDEN", null);
        } catch (ResourceStoreException e) {
            LOGGER.warn("MCP cancel_conversation failed", e);
            return errorJson("Failed to cancel conversation", "INTERNAL", null);
        }
    }

    // ==================================================================
    // Group (multi-agent) surface
    // ==================================================================

    @Tool(name = "list_group_pending_approvals",
          description = "List a group's conversations awaiting human approval. Admins and approvers see all; other "
                  + "callers see only their own; unauthenticated callers see nothing.")
    public String listGroupPendingApprovals(
                                            @ToolArg(description = "Group ID") String groupId,
                                            @ToolArg(description = "Max entries to return (default: 100)", required = false,
                                                     defaultValue = "100") String limit) {
        requireAnyRole(identity, authEnabled, McpRoles.HITL);
        if (groupId == null || groupId.isBlank()) {
            return errorJson("groupId is required", "BAD_REQUEST", null);
        }
        try {
            var result = hitlAccessGuard.listScopedGroupPendingApprovals(groupId, parseIntOrDefault(limit, 100));
            meterRegistry.counter("eddi.mcp.hitl.pending.listed", "surface", "group").increment();
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.warn("MCP list_group_pending_approvals failed", e);
            return errorJson("Failed to list group pending approvals", "INTERNAL", null);
        }
    }

    @Tool(name = "list_all_group_pending_approvals",
          description = "Cross-group HITL inbox: all group conversations awaiting approval across all groups. Admins "
                  + "and approvers see all; other callers see only their own; unauthenticated callers see nothing.")
    public String listAllGroupPendingApprovals(
                                               @ToolArg(description = "Max entries to return (default: 100)", required = false,
                                                        defaultValue = "100") String limit) {
        requireAnyRole(identity, authEnabled, McpRoles.HITL);
        try {
            var result = hitlAccessGuard.listScopedGroupPendingApprovals(null, parseIntOrDefault(limit, 100));
            meterRegistry.counter("eddi.mcp.hitl.pending.listed", "surface", "group-all").increment();
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.warn("MCP list_all_group_pending_approvals failed", e);
            return errorJson("Failed to list group pending approvals", "INTERNAL", null);
        }
    }

    @Tool(name = "get_group_approval_status",
          description = "Read the approval status (summary) of a paused group discussion — state, paused phase, "
                  + "pauseType, and the task ids awaiting approval. detail=full returns the whole group conversation "
                  + "(incl. transcript) — owner/admin, or approver only while awaiting approval.")
    public String getGroupApprovalStatus(
                                         @ToolArg(description = "Group ID") String groupId,
                                         @ToolArg(description = "Group conversation ID") String conversationId,
                                         @ToolArg(description = "'summary' or 'full' (default: summary)", required = false,
                                                  defaultValue = "summary") String detail) {
        requireAnyRole(identity, authEnabled, McpRoles.HITL);
        if (groupId == null || groupId.isBlank() || conversationId == null || conversationId.isBlank()) {
            return errorJson("groupId and conversationId are required", "BAD_REQUEST", null);
        }
        try {
            // I6: the READ guard — the pending HUMAN member may read the status
            // of the turn they owe (their rendered prompt is in the summary).
            hitlAccessGuard.requireGroupConversationReadAccess(groupId, conversationId);
            GroupConversation gc = groupConversationService.readGroupConversation(conversationId);
            boolean paused = gc.getState() == GroupConversation.GroupConversationState.AWAITING_APPROVAL
                    || gc.getState() == GroupConversation.GroupConversationState.AWAITING_HUMAN_INPUT;
            // The approver full-view window is the APPROVAL pause only — `paused`
            // also covers AWAITING_HUMAN_INPUT, where there is nothing for an
            // approver to decide and the transcript would leak outside their
            // remit (review finding). Summary fields keep the wider predicate.
            boolean awaitingApproval = gc.getState() == GroupConversation.GroupConversationState.AWAITING_APPROVAL;
            if ("full".equals(detail)) {
                // The pending human member (admitted by the READ guard) does NOT
                // get the full view — their working material is the rendered
                // prompt in the summary. Same gate as the REST surface.
                if (!ownershipValidator.isAdmin(identity) && !ownershipValidator.isOwner(identity, gc.getUserId())
                        && !(awaitingApproval && ownershipValidator.isApprover(identity))) {
                    return errorJson("Full approval status is available to approvers only while the group conversation "
                            + "is awaiting approval — use the summary view", "FORBIDDEN", null);
                }
                return jsonSerialization.serialize(gc);
            }
            List<String> awaitingTaskIds = paused && gc.getTaskList() != null
                    ? gc.getTaskList().all().stream()
                            .filter(t -> t.status() == SharedTaskList.TaskStatus.AWAITING_APPROVAL)
                            .map(SharedTaskList.TaskItem::id)
                            .toList()
                    : List.of();
            var summary = new LinkedHashMap<String, Object>();
            summary.put("groupConversationId", conversationId);
            summary.put("state", gc.getState() != null ? gc.getState().name() : "");
            summary.put("pausedAt", paused && gc.getPausedAt() != null ? gc.getPausedAt().toString() : "");
            // The id a decision passes back as HitlDecision.pauseId, so it applies only
            // to the pause the reviewer is looking at.
            summary.put("pauseId", paused && gc.getPausedAt() != null ? HitlDecision.pauseIdOf(gc.getPausedAt()) : "");
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
                // Same rule as the REST summary — see HitlAccessGuard.
                if (hitlAccessGuard.mayReadPendingHumanPrompt(gc)) {
                    summary.put("pendingHumanPrompt", gc.getPendingHumanInput().renderedPrompt());
                }
                PendingHumanInputView.addTo(summary, gc);
            }
            return jsonSerialization.serialize(summary);
        } catch (ForbiddenException e) {
            return errorJson("Access denied", "FORBIDDEN", null);
        } catch (NotFoundException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (ResourceNotFoundException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (Exception e) {
            LOGGER.warn("MCP get_group_approval_status failed", e);
            return errorJson("Failed to read group approval status", "INTERNAL", null);
        }
    }

    /** {@code approve_group_phase} without a {@code pauseId}. */
    public String approveGroupPhase(String groupId, String conversationId, String verdict, String note,
                                    String taskApprovalsJson) {
        return approveGroupPhase(groupId, conversationId, verdict, note, taskApprovalsJson, null);
    }

    /**
     * {@code approve_group_phase} without the review arguments (6.7).
     */
    public String approveGroupPhase(String groupId, String conversationId, String verdict, String note, String taskApprovalsJson,
                                    String pauseId) {
        return approveGroupPhase(groupId, conversationId, verdict, note, taskApprovalsJson, pauseId, null, null, null);
    }

    /**
     * {@code pauseId} is optional. Passed, the decision applies only to that pause:
     * a discussion that has since been resumed and paused again answers
     * {@code PAUSE_CHANGED} instead of approving something the caller never saw.
     * {@code editedAnswer}, {@code reasonCode} and {@code taskDecisionsJson} (6.7)
     * are the REST approve body's fields.
     */
    @Tool(name = "approve_group_phase",
          description = "Approve or reject a paused group discussion phase (or specific tasks for TASK-granularity). "
                  + "verdict=APPROVED or REJECTED (case-insensitive). taskApprovals is an optional JSON object mapping "
                  + "task-id to APPROVED/REJECTED. Returns the resumed group conversation to its owner or an admin, "
                  + "and only the resulting state to an approver-only caller. The decision is attributed "
                  + "to the authenticated caller. Pass pauseId (from get_group_approval_status) to bind the decision "
                  + "to the pause you reviewed: if the discussion has since paused again, the decision is refused "
                  + "with errorCode PAUSE_CHANGED. Optional (groups with hitlConfig.review.allowResultEdits): "
                  + "taskDecisionsJson decides tasks with an edited result "
                  + "({\"<taskId>\":{\"verdict\":\"APPROVED\",\"editedResult\":\"...\",\"reasonCode\":\"SCOPE\"}}, "
                  + "instead of taskApprovals); editedAnswer replaces a SYNTHESIS phase's output; reasonCode explains a "
                  + "rejection or an edit.")
    @SuppressWarnings("unchecked")
    public String approveGroupPhase(
                                    @ToolArg(description = "Group ID") String groupId,
                                    @ToolArg(description = "Group conversation ID") String conversationId,
                                    @ToolArg(description = "APPROVED or REJECTED (case-insensitive)") String verdict,
                                    @ToolArg(description = "Reviewer note, max 4096 chars (optional)", required = false) String note,
                                    @ToolArg(description = "JSON object mapping task-id to APPROVED/REJECTED, e.g. {\"t1\":\"APPROVED\"} (optional)",
                                             required = false) String taskApprovalsJson,
                                    @ToolArg(description = "pauseId from get_group_approval_status — the pause this decision is for",
                                             required = false) String pauseId,
                                    @ToolArg(description = "APPROVED only: replaces the output of a SYNTHESIS phase",
                                             required = false) String editedAnswer,
                                    @ToolArg(description = "Reason code for a rejection or an edit", required = false) String reasonCode,
                                    @ToolArg(description = "JSON object of task-id to {verdict, note, editedResult, reasonCode}; "
                                            + "exclusive with taskApprovals",
                                             required = false) String taskDecisionsJson) {
        requireAnyRole(identity, authEnabled, McpRoles.HITL);
        String disabled = disabledIfMutationsOff();
        if (disabled != null) {
            return disabled;
        }
        if (groupId == null || groupId.isBlank() || conversationId == null || conversationId.isBlank()) {
            return errorJson("groupId and conversationId are required", "BAD_REQUEST", null);
        }
        HitlVerdict parsed = parseVerdictOrNull(verdict);
        if (parsed == null) {
            return errorJson("Invalid verdict; use APPROVED or REJECTED", "BAD_REQUEST", null);
        }
        if (note != null && note.length() > HitlDecision.MAX_NOTE_LENGTH) {
            return errorJson("Note exceeds the maximum length of " + HitlDecision.MAX_NOTE_LENGTH + " characters",
                    "BAD_REQUEST", null);
        }
        Map<String, String> taskApprovals = null;
        if (taskApprovalsJson != null && !taskApprovalsJson.isBlank()) {
            Map<?, ?> rawApprovals;
            try {
                rawApprovals = jsonSerialization.deserialize(taskApprovalsJson, Map.class);
            } catch (Exception e) {
                return errorJson("Malformed taskApprovals JSON (expected a JSON object of task-id to APPROVED/REJECTED)",
                        "BAD_REQUEST", null);
            }
            // Validate the value TYPES here: the erasure-blind cast to
            // Map<String,String> accepts any JSON object, so a non-string value
            // (e.g. {"t1": 5}) would otherwise survive to resumeDiscussion and throw
            // a ClassCastException that surfaces as INTERNAL instead of the correct
            // BAD_REQUEST. Fail fast with a clear message; keys are always JSON strings.
            taskApprovals = new LinkedHashMap<>();
            for (var entry : rawApprovals.entrySet()) {
                if (!(entry.getValue() instanceof String value) || value.isBlank()) {
                    return errorJson("Invalid taskApprovals: each value must be a non-empty string "
                            + "(APPROVED or REJECTED)", "BAD_REQUEST", null);
                }
                taskApprovals.put(String.valueOf(entry.getKey()), value);
            }
        }
        try {
            hitlAccessGuard.requireGroupConversationHitlAccess(groupId, conversationId);
            // The USE gate REST's approve endpoint applies (requireResumeUseAccess): an
            // approval resumes the run — every remaining member turn, at the group
            // owner's cost — so an owner whose access to the group was revoked while
            // it was paused must not drive it to completion. Admins and designated
            // approvers decide by role, independently of group access.
            if (!ownershipValidator.isAdmin(identity) && !ownershipValidator.isApprover(identity)) {
                resourceAccessGuard.requireUseAccess(groupId, "group");
            }
            HitlDecision decision = new HitlDecision();
            decision.setVerdict(parsed);
            decision.setNote(note);
            decision.setDecidedBy(principalWithMcpPrefix());
            decision.setPauseId(pauseId != null && !pauseId.isBlank() ? pauseId.trim() : null);
            GroupApprovalRequest request = new GroupApprovalRequest();
            request.setDecision(decision);
            request.setTaskApprovals(taskApprovals);
            McpReviewArguments.applyReview(decision, editedAnswer, reasonCode);
            request.setTaskDecisions(McpReviewArguments.taskDecisions(jsonSerialization, taskDecisionsJson));
            GroupConversation result = groupConversationService.resumeDiscussion(conversationId, request, null);
            meterRegistry.counter("eddi.mcp.hitl.decision", "surface", "group", "verdict", parsed.name()).increment();
            return readableGroupResult(result);
        } catch (ForbiddenException e) {
            return errorJson("Access denied", "FORBIDDEN", null);
        } catch (NotFoundException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (IResourceStore.ResourceModifiedException e) {
            return errorJson("The group conversation was modified concurrently — reload and retry", "CONFLICT", null);
        } catch (ResourceNotFoundException
                | IGroupConversationStore.GroupConversationGoneException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (IGroupConversationService.GroupPauseMismatchException e) {
            return errorJson("The pending approval changed since this decision was made — re-read "
                    + "get_group_approval_status and decide again", "PAUSE_CHANGED", null);
        } catch (IGroupConversationService.GroupDiscussionException e) {
            return errorJson("Group conversation is not awaiting approval — it may have been resolved, cancelled, "
                    + "or already approved", "WRONG_STATE", null);
        } catch (McpReviewArguments.BadArgument e) {
            return errorJson(e.getMessage(), "BAD_REQUEST", null);
        } catch (IllegalArgumentException e) {
            return errorJson("Invalid approval request: " + e.getMessage(), "BAD_REQUEST", null);
        } catch (Exception e) {
            LOGGER.warn("MCP approve_group_phase failed", e);
            return errorJson("Failed to approve group phase", "INTERNAL", null);
        }
    }

    @Tool(name = "submit_group_human_input",
          description = "Submit a HUMAN group member's response for the turn an AWAITING_HUMAN_INPUT discussion is "
                  + "waiting on (I6). The response is recorded as the member's transcript entry and the discussion "
                  + "resumes from the next speaker. Only the pending member's own principal (or an admin) may submit "
                  + "— this is the member SPEAKING, not an approval. When the pending kind is TIE_BREAK "
                  + "(get_group_approval_status pendingKind/pendingOptions), pass choice = exactly one of the options; "
                  + "content is then an optional rationale.")
    public String submitGroupHumanInput(
                                        @ToolArg(description = "Group ID") String groupId,
                                        @ToolArg(description = "Group conversation ID") String conversationId,
                                        @ToolArg(description = "The pending HUMAN member's id (their principal id)") String memberId,
                                        @ToolArg(description = "The member's response text (for a tie-break: optional rationale)",
                                                 required = false) String content,
                                        @ToolArg(description = "For a TIE_BREAK pause: exactly one of the pendingOptions",
                                                 required = false) String choice) {
        requireAnyRole(identity, authEnabled, McpRoles.HITL);
        String disabled = disabledIfMutationsOff();
        if (disabled != null) {
            return disabled;
        }
        if (groupId == null || groupId.isBlank() || conversationId == null || conversationId.isBlank()
                || memberId == null || memberId.isBlank()
                || ((content == null || content.isBlank()) && (choice == null || choice.isBlank()))) {
            return errorJson("groupId, conversationId, memberId and content (or choice for a tie-break) are required", "BAD_REQUEST",
                    null);
        }
        try {
            hitlAccessGuard.requireGroupHumanInputAccess(groupId, conversationId, memberId);
            GroupConversation result = choice != null
                    ? groupConversationService.submitHumanInput(conversationId, memberId, content, choice, principalWithMcpPrefix())
                    : groupConversationService.submitHumanInput(conversationId, memberId, content, principalWithMcpPrefix());
            meterRegistry.counter("eddi.mcp.hitl.decision", "surface", "group", "verdict", "HUMAN_INPUT").increment();
            return readableSubmission(result);
        } catch (ForbiddenException e) {
            return errorJson("Access denied", "FORBIDDEN", null);
        } catch (NotFoundException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (IResourceStore.ResourceModifiedException e) {
            return errorJson("The group conversation was modified concurrently — reload and retry", "CONFLICT", null);
        } catch (ResourceNotFoundException
                | IGroupConversationStore.GroupConversationGoneException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (IGroupConversationService.GroupDiscussionException e) {
            return errorJson("Group conversation is not awaiting human input — the turn may have been resolved, "
                    + "timed out, or cancelled", "WRONG_STATE", null);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith(HumanTieBreakEngine.INVALID_CHOICE_PREFIX)) {
                return errorJson(e.getMessage(), "BAD_REQUEST", null);
            }
            return errorJson("Invalid submission: the memberId does not match the pending turn, or the content is "
                    + "blank or too long", "BAD_REQUEST", null);
        } catch (Exception e) {
            LOGGER.warn("MCP submit_group_human_input failed", e);
            return errorJson("Failed to submit human input", "INTERNAL", null);
        }
    }

    @Tool(name = "cancel_group_discussion",
          description = "Cancel an in-progress or paused group discussion. Attributed to the authenticated caller. "
                  + "A caller who is only an approver (neither the owner nor an admin) may cancel only a discussion "
                  + "that is awaiting a decision.")
    public String cancelGroupDiscussion(
                                        @ToolArg(description = "Group ID") String groupId,
                                        @ToolArg(description = "Group conversation ID") String conversationId) {
        requireAnyRole(identity, authEnabled, McpRoles.HITL);
        String disabled = disabledIfMutationsOff();
        if (disabled != null) {
            return disabled;
        }
        if (groupId == null || groupId.isBlank() || conversationId == null || conversationId.isBlank()) {
            return errorJson("groupId and conversationId are required", "BAD_REQUEST", null);
        }
        try {
            String ownerId = hitlAccessGuard.requireGroupConversationHitlAccess(groupId, conversationId);
            boolean cancelled;
            if (hitlAccessGuard.mayCancelExecuting(ownerId)) {
                cancelled = groupConversationService.cancelDiscussion(conversationId, ControlSignal.CANCEL_GRACEFUL);
            } else {
                // Approver-only caller: the paused-only compare-and-set the REST
                // surface uses, so an approver cannot stop another user's running
                // discussion over MCP either.
                if (!groupConversationService.cancelPausedDiscussion(conversationId, ControlSignal.CANCEL_GRACEFUL)) {
                    return errorJson("Access denied: an approver may only cancel a group discussion that is awaiting "
                            + "a decision", "FORBIDDEN", null);
                }
                cancelled = true;
            }
            meterRegistry.counter("eddi.mcp.hitl.cancelled", "surface", "group").increment();
            return cancelled
                    ? "{\"status\":\"CANCELLED\"}"
                    : errorJson("Group conversation is already in a terminal state — nothing to cancel",
                            "WRONG_STATE", null);
        } catch (ForbiddenException e) {
            return errorJson("Access denied", "FORBIDDEN", null);
        } catch (NotFoundException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (ResourceNotFoundException
                | IGroupConversationStore.GroupConversationGoneException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (Exception e) {
            LOGGER.warn("MCP cancel_group_discussion failed", e);
            return errorJson("Failed to cancel group discussion", "INTERNAL", null);
        }
    }

    /**
     * The full group conversation for its owner or an admin; a state-only receipt
     * for a caller whose action was authorized (an approver deciding, a pending
     * HUMAN member speaking) but who may not read the discussion past it — the same
     * projection the REST surface applies.
     */
    private String readableGroupResult(GroupConversation result) throws Exception {
        if (result == null || hitlAccessGuard.mayReadResumedDiscussion(result.getUserId())) {
            return jsonSerialization.serialize(result);
        }
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("state", result.getState() != null ? result.getState().name() : null);
        return jsonSerialization.serialize(receipt);
    }

    /**
     * A submitted turn answers the owner or an admin with the conversation; anyone
     * else gets the acknowledgement {@code {groupConversationId, accepted, state}}.
     */
    private String readableSubmission(GroupConversation result) throws Exception {
        if (result == null || hitlAccessGuard.mayReadResumedDiscussion(result.getUserId())) {
            return jsonSerialization.serialize(result);
        }
        var acknowledgement = new LinkedHashMap<String, Object>();
        acknowledgement.put("groupConversationId", result.getId());
        acknowledgement.put("accepted", true);
        acknowledgement.put("state", result.getState() != null ? result.getState().name() : "");
        return jsonSerialization.serialize(acknowledgement);
    }
}
