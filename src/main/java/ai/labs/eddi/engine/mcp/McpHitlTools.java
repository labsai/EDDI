/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
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
            summary.put("timeoutPolicy", paused && snapshot.getHitlTimeoutPolicy() != null ? snapshot.getHitlTimeoutPolicy() : "");
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
}
