/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.memory.ConversationMemoryTail;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

import jakarta.ws.rs.NotFoundException;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Single source of truth for HITL authorization and owner-scoped
 * pending-approval listing, shared by the REST layer ({@code RestAgentEngine} /
 * {@code RestGroupConversation}) and the MCP layer ({@code McpHitlTools}).
 * <p>
 * It <em>composes</em> — it delegates role decisions to
 * {@link OwnershipValidator} and queries to the conversation services; it does
 * not duplicate either. Extracting the fail-closed HITL ownership check into
 * one place keeps "who may decide" from drifting between the REST and MCP
 * surfaces.
 */
@ApplicationScoped
public class HitlAccessGuard {

    private static final Logger LOGGER = Logger.getLogger(HitlAccessGuard.class);

    /** {@code ConversationPauseException.PauseOrigin.TOOL_CALL}, as persisted. */
    private static final String TOOL_CALL_PAUSE = "TOOL_CALL";

    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IConversationService conversationService;
    private final IGroupConversationService groupConversationService;

    /**
     * The turn guard a resume by the owner has to pass — see
     * {@link #requireConversationResumeAccess}. Field-injected and lazy, so the
     * tests that build this guard with its constructor keep working; without it no
     * {@code USE} re-check runs.
     */
    @Inject
    Instance<ConversationAccessGuard> conversationAccessGuardInstance;

    @Inject
    public HitlAccessGuard(SecurityIdentity identity,
            OwnershipValidator ownershipValidator,
            IConversationDescriptorStore conversationDescriptorStore,
            IConversationService conversationService,
            IGroupConversationService groupConversationService) {
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
    }

    /**
     * Whether the caller may cancel a conversation that is <em>executing</em>, not
     * only one awaiting a decision: the owner and admins may, a caller who is only
     * an {@code eddi-approver} may not (L9 of the 2026-10-15 security review). The
     * REST and MCP cancel surfaces of both the 1:1 and the group conversation
     * decide with this, so an approver's cancel is the paused-only compare-and-set
     * on all four.
     */
    public boolean mayCancelExecuting(String ownerId) {
        return ownershipValidator.isAdmin(identity) || ownershipValidator.isOwner(identity, ownerId);
    }

    /**
     * Whether the caller may follow a group discussion past the approval they just
     * decided — the owner and admins may. An {@code eddi-approver} who is neither
     * may decide the pause, and may read the conversation while it awaits approval,
     * but the member turns that run after the resume are outside that window (the
     * same boundary as the agent resume's "a reply is a read").
     */
    public boolean mayReadResumedDiscussion(String ownerId) {
        return ownershipValidator.isAdmin(identity) || ownershipValidator.isOwner(identity, ownerId);
    }

    /**
     * Strict HITL ownership check (owner OR admin OR approver). Fail-closed when
     * the descriptor is absent and the caller is neither admin nor approver — a
     * conversation without a verifiable owner may only be decided by
     * admins/approvers.
     *
     * @return the conversation owner's userId, or {@code null} if the descriptor
     *         was not found (the caller then handles the eventual 404 from the
     *         actual operation).
     * @throws ForbiddenException
     *             if the caller may not decide this conversation.
     */
    public String requireConversationHitlAccess(String conversationId) {
        try {
            var descriptor = conversationDescriptorStore.readDescriptor(conversationId, 0);
            ownershipValidator.requireOwnerAdminOrApprover(identity, descriptor.getUserId(), "conversation");
            return descriptor.getUserId();
        } catch (ForbiddenException e) {
            throw e;
        } catch (ResourceNotFoundException e) {
            if (!ownershipValidator.isAdmin(identity) && !ownershipValidator.isApprover(identity)) {
                LOGGER.warnf("HITL operation on conversation %s denied: no descriptor to verify ownership against",
                        sanitize(conversationId));
                throw new ForbiddenException("Access denied: conversation ownership cannot be verified");
            }
            LOGGER.debugf("Conversation descriptor not found for %s", sanitize(conversationId));
            return null;
        } catch (ResourceStoreException e) {
            LOGGER.warnf("Could not load conversation descriptor for HITL ownership check: %s", sanitize(conversationId));
            throw new ForbiddenException("Access denied: unable to verify conversation ownership");
        }
    }

    /**
     * {@link #requireConversationHitlAccess} for a decision that resumes the
     * conversation. A resume runs the rest of the paused turn — its tools, a model
     * call — so an owner who is neither admin nor approver also has to pass the
     * turn check {@code say} applies: under enforced workspaces, still allowed to
     * {@code USE} the conversation's agent. Ownership outlives a revoked share; the
     * right to run the agent does not. Admins and approvers decide by their role,
     * unchanged.
     *
     * @return the conversation owner's userId, as
     *         {@link #requireConversationHitlAccess} returns it
     * @throws ForbiddenException
     *             if the caller may not resume this conversation.
     */
    public String requireConversationResumeAccess(String conversationId) {
        String ownerId = requireConversationHitlAccess(conversationId);
        if (ownershipValidator.isAdmin(identity) || ownershipValidator.isApprover(identity)) {
            return ownerId;
        }
        requireOwnerMayDecide(conversationId);
        if (conversationAccessGuardInstance != null && conversationAccessGuardInstance.isResolvable()) {
            conversationAccessGuardInstance.get().requireConversationTurnAccess(conversationId);
        }
        return ownerId;
    }

    /**
     * Refuses the owner (a caller who is neither admin nor approver) a decision on
     * a tool-call pause whose effective {@code toolApprovals.deciders} is
     * {@code ROLE_ONLY}.
     * <p>
     * The pause exists because the model — possibly steered by injected content —
     * wants to run a gated call in this user's turn; letting that same user approve
     * it, arguments included, makes the gate advisory. Read from the paused batch's
     * persisted {@code effectiveToolApprovals}, the exact config that gated it.
     * Only the decision is refused: the owner keeps the pending-approval listing
     * and the approval-status reads ({@link #requireConversationHitlAccess}), and
     * may still cancel. A conversation whose pause cannot be read is refused rather
     * than decided unchecked; a missing one is left for the resume to 404.
     */
    private void requireOwnerMayDecide(String conversationId) {
        ConversationMemorySnapshot paused;
        try {
            ConversationMemoryTail tail = conversationService.getConversationMemoryTail(conversationId);
            paused = tail != null ? tail.snapshot() : null;
        } catch (ResourceNotFoundException e) {
            return;
        } catch (ResourceStoreException | RuntimeException e) {
            LOGGER.warnf("Could not read the pause of conversation %s to check who may decide it", sanitize(conversationId));
            throw new ForbiddenException("Access denied: unable to verify who may decide this approval");
        }
        if (paused == null || paused.getConversationState() != ConversationState.AWAITING_HUMAN
                || !TOOL_CALL_PAUSE.equals(paused.getHitlPauseType())) {
            return;
        }
        PendingToolCallBatch batch = paused.getHitlPendingToolCalls();
        if (batch != null && ToolApprovalsConfig.roleOnly(batch.getEffectiveToolApprovals())) {
            LOGGER.infof("Owner decision on tool-call pause of conversation %s refused: deciders=ROLE_ONLY", sanitize(conversationId));
            throw new ForbiddenException("Access denied: this agent's tool calls are approved by an approver or an administrator, "
                    + "not by the conversation's owner");
        }
    }

    /**
     * Owner-scoped pending-approval inbox for regular (1:1) conversations: admins
     * and approvers see all; other callers see only their own; anonymous callers
     * see nothing (fail-closed). The owner filter is applied inside the query
     * (before the limit) so a personal inbox is never starved by a large global
     * backlog.
     */
    public List<PendingApprovalSummary> listScopedPendingApprovals(int limit) throws ResourceStoreException {
        if (ownershipValidator.isAdmin(identity) || ownershipValidator.isApprover(identity)) {
            return conversationService.listPendingApprovals(limit);
        }
        String callerId = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
        if (callerId == null || callerId.isBlank()) {
            return List.of();
        }
        return conversationService.listPendingApprovals(callerId, limit);
    }

    /**
     * Strict HITL ownership check for a group conversation (owner OR admin OR
     * approver). When {@code groupId} is non-null the target must belong to that
     * group, otherwise a mismatch is surfaced as
     * {@link jakarta.ws.rs.NotFoundException} so a wrong-group path never leaks the
     * conversation's existence or owner. A missing conversation is left for the
     * actual operation to 404.
     *
     * @return the group conversation owner's userId, or {@code null} if the
     *         conversation was not found (the actual operation then 404s).
     * @throws ForbiddenException
     *             if the caller may not decide this group conversation.
     */
    public String requireGroupConversationHitlAccess(String groupId, String groupConversationId) {
        GroupConversation gc;
        try {
            gc = groupConversationService.readGroupConversation(groupConversationId);
        } catch (ResourceNotFoundException e) {
            LOGGER.debugf("Group conversation not found for HITL ownership check: %s", sanitize(groupConversationId));
            return null;
        } catch (Exception e) {
            throw new ForbiddenException("Access denied: unable to verify group conversation ownership");
        }
        if (groupId != null && !groupId.equals(gc.getGroupId())) {
            LOGGER.infof("Group conversation %s does not belong to group %s",
                    sanitize(groupConversationId), sanitize(groupId));
            throw new NotFoundException("Group conversation not found.");
        }
        ownershipValidator.requireOwnerAdminOrApprover(identity, gc.getUserId(), "group conversation");
        return gc.getUserId();
    }

    /**
     * Owner-scoped pending-approval inbox for group conversations: admins and
     * approvers see all; other callers see their own — or, for I6 human-turn
     * pauses, entries waiting on THEM as the pending member, since a human member
     * usually does not own the conversation whose turn they owe. Post-filtered,
     * since the group store has no owner-scoped query; anonymous callers see
     * nothing (fail-closed). {@code groupId == null} lists across all groups
     * (cross-group inbox).
     */
    public List<PendingApprovalSummary> listScopedGroupPendingApprovals(String groupId, int limit)
            throws ResourceStoreException {
        var scoped = groupConversationService.listGroupPendingApprovals(groupId, limit).stream();
        if (ownershipValidator.isAdmin(identity) || ownershipValidator.isApprover(identity)) {
            return scoped.toList();
        }
        String callerId = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
        if (callerId == null || callerId.isBlank()) {
            return List.of();
        }
        return scoped.filter(summary -> callerId.equals(summary.getUserId())
                || callerId.equals(summary.getPendingMemberId())).toList();
    }

    /**
     * Read access to a group conversation's approval/pause STATUS (I6): the
     * owner/admin/approver rule, plus the one extra reader the strict guard cannot
     * admit — the HUMAN member a pending turn is waiting on. The inbox tells that
     * member they are up; without this, the status API (where their rendered prompt
     * lives) would refuse to show them what they were asked. Read-only surfaces
     * only — approve/cancel/submit keep their own guards.
     *
     * @throws ForbiddenException
     *             if the caller may not read this group conversation's status.
     */
    public void requireGroupConversationReadAccess(String groupId, String groupConversationId) {
        GroupConversation gc;
        try {
            gc = groupConversationService.readGroupConversation(groupConversationId);
        } catch (ResourceNotFoundException e) {
            LOGGER.debugf("Group conversation not found for read-access check: %s", sanitize(groupConversationId));
            return; // the actual operation 404s
        } catch (Exception e) {
            throw new ForbiddenException("Access denied: unable to verify group conversation");
        }
        if (groupId != null && !groupId.equals(gc.getGroupId())) {
            LOGGER.infof("Group conversation %s does not belong to group %s",
                    sanitize(groupConversationId), sanitize(groupId));
            throw new NotFoundException("Group conversation not found.");
        }
        String callerId = identity != null && identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
        if (gc.getPendingHumanInput() != null && callerId != null
                && callerId.equals(gc.getPendingHumanInput().memberId())) {
            return;
        }
        ownershipValidator.requireOwnerAdminOrApprover(identity, gc.getUserId(), "group conversation");
    }

    /**
     * Whether the caller may see a pending HUMAN turn's rendered prompt in the
     * approval-status summary. The read guard admits every {@code eddi-approver},
     * but the prompt is built from the discussion (peer responses, the question) —
     * transcript content that the full view withholds from an approver outside an
     * APPROVAL pause. So the prompt goes only to the member it is addressed to, the
     * owner and admins, plus an approver while the conversation is actually
     * awaiting approval (the same window the full view grants). Everybody else
     * still sees who is up and when — the pause metadata.
     */
    public boolean mayReadPendingHumanPrompt(GroupConversation gc) {
        if (ownershipValidator.isAdmin(identity) || ownershipValidator.isOwner(identity, gc.getUserId())) {
            return true;
        }
        String callerId = OwnershipValidator.principalName(identity);
        if (gc.getPendingHumanInput() != null && callerId != null
                && callerId.equals(gc.getPendingHumanInput().memberId())) {
            return true;
        }
        return gc.getState() == GroupConversation.GroupConversationState.AWAITING_APPROVAL
                && ownershipValidator.isApprover(identity);
    }

    /**
     * Authorization for submitting a HUMAN member's turn (I6): the caller must BE
     * that member (their principal equals the pending turn's member id) or an
     * admin. Deliberately narrower than the approve surface — an
     * {@code eddi-approver} may decide approvals they do not own, but SPEAKING as
     * another human is impersonation, not review; only the admin break-glass
     * crosses that line. A wrong-group path 404s without leaking existence, same as
     * {@link #requireGroupConversationHitlAccess}. No-op when authorization is
     * disabled.
     *
     * @throws ForbiddenException
     *             if the caller may not submit for this member.
     */
    public void requireGroupHumanInputAccess(String groupId, String groupConversationId, String memberId) {
        if (!ownershipValidator.isAuthEnabled()) {
            return;
        }
        GroupConversation gc;
        try {
            gc = groupConversationService.readGroupConversation(groupConversationId);
        } catch (ResourceNotFoundException e) {
            LOGGER.debugf("Group conversation not found for human-input access check: %s", sanitize(groupConversationId));
            return; // the actual operation 404s
        } catch (Exception e) {
            throw new ForbiddenException("Access denied: unable to verify group conversation");
        }
        if (groupId != null && !groupId.equals(gc.getGroupId())) {
            LOGGER.infof("Group conversation %s does not belong to group %s",
                    sanitize(groupConversationId), sanitize(groupId));
            throw new NotFoundException("Group conversation not found.");
        }
        if (ownershipValidator.isAdmin(identity)) {
            return;
        }
        String callerId = identity != null && identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
        if (callerId == null || callerId.isBlank() || memberId == null || !callerId.equals(memberId)) {
            LOGGER.warnf("Human-input submission for member '%s' of %s denied for caller '%s'",
                    sanitize(memberId), sanitize(groupConversationId), sanitize(callerId));
            throw new ForbiddenException("Access denied: only the pending member (or an admin) may submit this turn");
        }
    }
}
