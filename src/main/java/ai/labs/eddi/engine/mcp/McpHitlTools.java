/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

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
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.escapeJsonString;
import static ai.labs.eddi.engine.mcp.McpToolUtils.parseIntOrDefault;

/**
 * MCP tools for resolving Human-in-the-Loop (HITL) approval gates over the MCP
 * server — the counterpart to the REST HITL endpoints. Lets an external MCP
 * client list, read, resume/approve, and cancel paused conversations and group
 * discussions.
 *
 * <p>
 * MCP is a transport, not a new authority: authorization mirrors the REST
 * surface exactly (per-conversation owner/admin/approver via
 * {@link HitlAccessGuard}); a decision is always attributed to the
 * authenticated caller (prefixed {@code mcp:}, never taken from a tool
 * argument). Mutating tools additionally honour the
 * {@code eddi.mcp.hitl.mutations.enabled} kill-switch. These tools resolve both
 * {@code RULE} and {@code TOOL_CALL} pauses — both are {@code AWAITING_HUMAN}
 * and both are decided by a single verdict.
 */
@ApplicationScoped
public class McpHitlTools {

    private static final Logger LOGGER = Logger.getLogger(McpHitlTools.class);

    private final IConversationService conversationService;
    private final IGroupConversationService groupConversationService;
    private final HitlAccessGuard hitlAccessGuard;
    private final OwnershipValidator ownershipValidator;
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
            IJsonSerialization jsonSerialization,
            SecurityIdentity identity,
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled,
            @ConfigProperty(name = "eddi.mcp.hitl.mutations.enabled", defaultValue = "true") boolean mutationsEnabled) {
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.hitlAccessGuard = hitlAccessGuard;
        this.ownershipValidator = ownershipValidator;
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
    @Blocking
    public String listPendingApprovals(
                                       @ToolArg(description = "Max entries to return (optional, default 200, capped at 1000)") String limit) {
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

    @Tool(name = "get_approval_status",
          description = "Read the approval status of a paused regular conversation. detail=summary (default) returns "
                  + "pause metadata incl. pauseType (RULE or TOOL_CALL); detail=full returns the full memory snapshot "
                  + "(incl. any pending tool-call batch) — owner/admin, or approver only while awaiting approval.")
    @Blocking
    public String getApprovalStatus(
                                    @ToolArg(description = "Conversation ID") String conversationId,
                                    @ToolArg(description = "summary (default) or full (optional)") String detail) {
        if (conversationId == null || conversationId.isBlank()) {
            return errorJson("conversationId is required", "BAD_REQUEST", null);
        }
        try {
            String ownerId = hitlAccessGuard.requireConversationHitlAccess(conversationId);
            ConversationMemorySnapshot snapshot = conversationService.getConversationMemorySnapshot(conversationId);
            boolean paused = snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
            if ("full".equals(detail)) {
                if (!paused && !ownershipValidator.isAdmin(identity) && !ownershipValidator.isOwner(identity, ownerId)) {
                    return errorJson("Full approval status is available to approvers only while awaiting approval — "
                            + "use the summary view", "FORBIDDEN", null);
                }
                return jsonSerialization.serialize(snapshot);
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

    @Tool(name = "resume_conversation",
          description = "Resume a paused regular conversation with a human decision. verdict=APPROVED or REJECTED "
                  + "(case-insensitive). Resolves both RULE and TOOL_CALL pauses. The decision is attributed to the "
                  + "authenticated caller.")
    @Blocking
    public String resumeConversation(
                                     @ToolArg(description = "Conversation ID awaiting approval") String conversationId,
                                     @ToolArg(description = "APPROVED or REJECTED (case-insensitive)") String verdict,
                                     @ToolArg(description = "Optional reviewer note (max 4096 chars)") String note) {
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
            conversationService.resumeConversation(conversationId, decision, null);
            meterRegistry.counter("eddi.mcp.hitl.decision", "surface", "regular", "verdict", parsed.name()).increment();
            return "{\"status\":\"RESUMED\",\"conversationId\":\"" + escapeJsonString(conversationId)
                    + "\",\"verdict\":\"" + parsed.name() + "\"}";
        } catch (ForbiddenException e) {
            return errorJson("Access denied", "FORBIDDEN", null);
        } catch (ResourceNotFoundException e) {
            return errorJson("Conversation not found", "NOT_FOUND", null);
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
    @Blocking
    public String cancelConversation(
                                     @ToolArg(description = "Conversation ID") String conversationId) {
        String disabled = disabledIfMutationsOff();
        if (disabled != null) {
            return disabled;
        }
        if (conversationId == null || conversationId.isBlank()) {
            return errorJson("conversationId is required", "BAD_REQUEST", null);
        }
        try {
            hitlAccessGuard.requireConversationHitlAccess(conversationId);
            var outcome = conversationService.cancelConversation(conversationId,
                    ControlSignal.CANCEL_GRACEFUL, principalWithMcpPrefix());
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
    @Blocking
    public String listGroupPendingApprovals(
                                            @ToolArg(description = "Group ID") String groupId,
                                            @ToolArg(description = "Max entries to return (optional, default 100)") String limit) {
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
    @Blocking
    public String listAllGroupPendingApprovals(
                                               @ToolArg(description = "Max entries to return (optional, default 100)") String limit) {
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
    @Blocking
    public String getGroupApprovalStatus(
                                         @ToolArg(description = "Group ID") String groupId,
                                         @ToolArg(description = "Group conversation ID") String conversationId,
                                         @ToolArg(description = "summary (default) or full (optional)") String detail) {
        if (groupId == null || groupId.isBlank() || conversationId == null || conversationId.isBlank()) {
            return errorJson("groupId and conversationId are required", "BAD_REQUEST", null);
        }
        try {
            hitlAccessGuard.requireGroupConversationHitlAccess(groupId, conversationId);
            GroupConversation gc = groupConversationService.readGroupConversation(conversationId);
            boolean paused = gc.getState() == GroupConversation.GroupConversationState.AWAITING_APPROVAL;
            if ("full".equals(detail)) {
                if (!paused && !ownershipValidator.isAdmin(identity) && !ownershipValidator.isOwner(identity, gc.getUserId())) {
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
            summary.put("pausedPhaseName", paused && gc.getPausedPhaseName() != null ? gc.getPausedPhaseName() : "");
            summary.put("pauseType", paused && gc.getHitlPauseType() != null ? gc.getHitlPauseType().name() : "");
            summary.put("pauseReason", paused && gc.getHitlPauseReason() != null ? gc.getHitlPauseReason() : "");
            summary.put("timeoutPolicy", paused && gc.getHitlTimeoutPolicy() != null ? gc.getHitlTimeoutPolicy().name() : "");
            summary.put("awaitingApprovalTaskIds", awaitingTaskIds);
            return jsonSerialization.serialize(summary);
        } catch (ForbiddenException e) {
            return errorJson("Access denied", "FORBIDDEN", null);
        } catch (jakarta.ws.rs.NotFoundException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (ResourceNotFoundException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (Exception e) {
            LOGGER.warn("MCP get_group_approval_status failed", e);
            return errorJson("Failed to read group approval status", "INTERNAL", null);
        }
    }

    @Tool(name = "approve_group_phase",
          description = "Approve or reject a paused group discussion phase (or specific tasks for TASK-granularity). "
                  + "verdict=APPROVED or REJECTED (case-insensitive). taskApprovals is an optional JSON object mapping "
                  + "task-id to APPROVED/REJECTED. Returns the resumed group conversation. The decision is attributed "
                  + "to the authenticated caller.")
    @Blocking
    @SuppressWarnings("unchecked")
    public String approveGroupPhase(
                                    @ToolArg(description = "Group ID") String groupId,
                                    @ToolArg(description = "Group conversation ID") String conversationId,
                                    @ToolArg(description = "APPROVED or REJECTED (case-insensitive)") String verdict,
                                    @ToolArg(description = "Optional reviewer note (max 4096 chars)") String note,
                                    @ToolArg(description = "Optional JSON object mapping task-id to APPROVED/REJECTED, e.g. {\"t1\":\"APPROVED\"}") String taskApprovalsJson) {
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
            taskApprovals = new java.util.LinkedHashMap<>();
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
            HitlDecision decision = new HitlDecision();
            decision.setVerdict(parsed);
            decision.setNote(note);
            decision.setDecidedBy(principalWithMcpPrefix());
            GroupApprovalRequest request = new GroupApprovalRequest();
            request.setDecision(decision);
            request.setTaskApprovals(taskApprovals);
            GroupConversation result = groupConversationService.resumeDiscussion(conversationId, request, null);
            meterRegistry.counter("eddi.mcp.hitl.decision", "surface", "group", "verdict", parsed.name()).increment();
            return jsonSerialization.serialize(result);
        } catch (ForbiddenException e) {
            return errorJson("Access denied", "FORBIDDEN", null);
        } catch (jakarta.ws.rs.NotFoundException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (IResourceStore.ResourceModifiedException e) {
            return errorJson("The group conversation was modified concurrently — reload and retry", "CONFLICT", null);
        } catch (ResourceNotFoundException
                | ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (IGroupConversationService.GroupDiscussionException e) {
            return errorJson("Group conversation is not awaiting approval — it may have been resolved, cancelled, "
                    + "or already approved", "WRONG_STATE", null);
        } catch (IllegalArgumentException e) {
            return errorJson("Invalid approval request: an unknown task id was referenced, or a task is not awaiting "
                    + "approval", "BAD_REQUEST", null);
        } catch (Exception e) {
            LOGGER.warn("MCP approve_group_phase failed", e);
            return errorJson("Failed to approve group phase", "INTERNAL", null);
        }
    }

    @Tool(name = "cancel_group_discussion",
          description = "Cancel an in-progress or paused group discussion. Attributed to the authenticated caller.")
    @Blocking
    public String cancelGroupDiscussion(
                                        @ToolArg(description = "Group ID") String groupId,
                                        @ToolArg(description = "Group conversation ID") String conversationId) {
        String disabled = disabledIfMutationsOff();
        if (disabled != null) {
            return disabled;
        }
        if (groupId == null || groupId.isBlank() || conversationId == null || conversationId.isBlank()) {
            return errorJson("groupId and conversationId are required", "BAD_REQUEST", null);
        }
        try {
            hitlAccessGuard.requireGroupConversationHitlAccess(groupId, conversationId);
            boolean cancelled = groupConversationService.cancelDiscussion(conversationId, ControlSignal.CANCEL_GRACEFUL);
            meterRegistry.counter("eddi.mcp.hitl.cancelled", "surface", "group").increment();
            return cancelled
                    ? "{\"status\":\"CANCELLED\"}"
                    : errorJson("Group conversation is already in a terminal state — nothing to cancel",
                            "WRONG_STATE", null);
        } catch (ForbiddenException e) {
            return errorJson("Access denied", "FORBIDDEN", null);
        } catch (jakarta.ws.rs.NotFoundException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (ResourceNotFoundException
                | ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException e) {
            return errorJson("Group conversation not found", "NOT_FOUND", null);
        } catch (Exception e) {
            LOGGER.warn("MCP cancel_group_discussion failed", e);
            return errorJson("Failed to cancel group discussion", "INTERNAL", null);
        }
    }
}
