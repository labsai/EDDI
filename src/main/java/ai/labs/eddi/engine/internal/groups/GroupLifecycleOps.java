/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LifecyclePolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupExecutionException;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupMemberNotFoundException;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupTimeoutException;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Post-discussion lifecycle operations for group conversations: follow-up,
 * continue, close, delete/read/list, pending-approval listing, ephemeral agent
 * cleanup, terminal-state failure handling, and dynamic-agent tracking
 * propagation. Extracted from {@code GroupConversationService} (Wave R, R1 step
 * 8) as a pure move — no behavior change.
 * <p>
 * Constructed fresh per facade call (see {@code GroupConversationService
 * .lifecycleOps()}), mirroring {@link GroupAttachmentBinder}'s
 * {@code attachmentBinder()} pattern — {@code deploymentStore} is
 * {@code @Inject}-field-injected on the facade and is not yet populated when
 * the facade's own constructor runs, so this class must read its current value
 * at call time rather than capture it once eagerly. {@code
 * operationsInProgress} and {@code activeTokens} are shared by reference, not
 * owned — they are mutable coordination state that must stay the SAME instance
 * across every per-call wrapper, exactly like the facade's other collaborators
 * share {@code activeTokens}.
 * <p>
 * Holds a back-reference to the concrete {@link GroupConversationService} for
 * calls that stay on the facade: {@code executeDiscussion}, {@code
 * resolvePhases} (both used by {@code continueDiscussion}'s re-entry), and
 * {@code resolveAgentTimeoutSeconds}/{@code extractResponse} (used by {@code
 * followUpWithMember}). Safe for the same reason as every other R1
 * collaborator's self-reference: it is only invoked from delegator methods that
 * run after the facade is fully constructed.
 */
public class GroupLifecycleOps {

    private static final Logger LOGGER = Logger.getLogger(GroupLifecycleOps.class);
    private static final Environment DEFAULT_ENV = Environment.production;

    private final IGroupConversationStore conversationStore;
    private final IAgentGroupStore groupStore;
    private final IConversationService conversationService;
    private final IAgentFactory agentFactory;
    private final IAgentStore agentStore;
    private final IDeploymentStore deploymentStore;
    private final ISharedArtifactStore sharedArtifactStore;
    private final Set<String> operationsInProgress;
    private final ConcurrentHashMap<String, DiscussionControlToken> activeTokens;
    private final GroupConversationService groupConversationService;
    private final Counter counterGroupFollowUp;
    private final Counter counterGroupContinue;
    private final Counter counterGroupClose;
    private final Counter counterGroupFailure;

    public GroupLifecycleOps(IGroupConversationStore conversationStore, IAgentGroupStore groupStore,
            IConversationService conversationService, IAgentFactory agentFactory, IAgentStore agentStore,
            IDeploymentStore deploymentStore, ISharedArtifactStore sharedArtifactStore, Set<String> operationsInProgress,
            ConcurrentHashMap<String, DiscussionControlToken> activeTokens,
            GroupConversationService groupConversationService, Counter counterGroupFollowUp,
            Counter counterGroupContinue, Counter counterGroupClose, Counter counterGroupFailure) {
        this.conversationStore = conversationStore;
        this.groupStore = groupStore;
        this.conversationService = conversationService;
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
        this.deploymentStore = deploymentStore;
        this.sharedArtifactStore = sharedArtifactStore;
        this.operationsInProgress = operationsInProgress;
        this.activeTokens = activeTokens;
        this.groupConversationService = groupConversationService;
        this.counterGroupFollowUp = counterGroupFollowUp;
        this.counterGroupContinue = counterGroupContinue;
        this.counterGroupClose = counterGroupClose;
        this.counterGroupFailure = counterGroupFailure;
    }

    public GroupConversation readGroupConversation(String groupConversationId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return conversationStore.read(groupConversationId);
    }

    public void deleteGroupConversation(String groupConversationId)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException {
        // Serialize against an in-flight follow-up/continue/close on the same
        // conversation
        // (single-node) — delete is terminal (it ends member conversations and reclaims
        // ephemeral agents), so racing an active discussion could tear those down
        // mid-run
        // and let a later update() resurrect a stale "zombie" document.
        if (!operationsInProgress.add(groupConversationId)) {
            // A retryable conflict, not a store failure — surfaces as 409, not 500.
            throw new GroupDiscussionException(
                    "Cannot delete: another operation is already in progress for this group conversation");
        }
        try {
            GroupConversation gc = conversationStore.read(groupConversationId);
            // #12: deleting a paused discussion must run the same cleanup as
            // cancel-of-paused. executeDiscussion's finally deliberately skipped
            // cleanup while AWAITING_APPROVAL, so without this the armed timeout
            // schedule fires against a deleted conversation, ephemeral dynamic
            // agents stay deployed forever, and the signing guard's verification
            // cursor leaks.
            if (gc.getState() == GroupConversationState.AWAITING_APPROVAL
                    || gc.getState() == GroupConversationState.AWAITING_HUMAN_INPUT) {
                groupConversationService.deleteGroupHitlTimeoutSchedule(groupConversationId);
                groupConversationService.cleanupAfterTerminalState(gc);
            }
            for (String privateConvId : gc.getMemberConversationIds().values()) {
                try {
                    conversationService.endConversation(privateConvId);
                } catch (Exception e) {
                    LOGGER.warnf("Failed to end private conversation %s: %s", privateConvId, e.getMessage());
                }
            }
            // Ephemeral agent cleanup — deferred from executeDiscussion() to terminal
            // operations. Delete is terminal, so reclaim any dynamically-created agents
            // here; otherwise deleting a COMPLETED conversation would orphan them.
            cleanupEphemeralAgentsForGroup(gc);
            // I17: artifacts live in their own collection keyed by this conversation
            // — remove them with their discussion, before the document goes (while
            // the id is still provably a discussion the caller could delete).
            deleteArtifactsForGroupConversation(groupConversationId);
            conversationStore.delete(groupConversationId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.warnf("Group conversation %s not found for deletion", LogSanitizer.sanitize(groupConversationId));
        } finally {
            operationsInProgress.remove(groupConversationId);
        }
    }

    public List<GroupConversation> listGroupConversations(String groupId, int index, int limit)
            throws IResourceStore.ResourceStoreException {
        return conversationStore.listByGroupId(groupId, index, limit);
    }

    public GroupConversation followUpWithMember(String groupConversationId, String targetAgentId, String question)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        // Validate BEFORE taking the guard or transitioning state: a null targetAgentId
        // would otherwise NPE deep in the member-resolution scan (500 instead of 400),
        // and a null question would be appended to the transcript and sent to the
        // agent.
        if (targetAgentId == null || targetAgentId.isBlank()) {
            throw new IllegalArgumentException("targetAgentId must not be null or blank");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be null or blank");
        }

        if (!operationsInProgress.add(groupConversationId)) {
            throw new GroupDiscussionException(
                    "Another operation is already in progress for this group conversation");
        }
        try {
            GroupConversation gc = conversationStore.read(groupConversationId);

            // Atomic state transition: COMPLETED → IN_PROGRESS
            if (!conversationStore.compareAndSetState(groupConversationId, GroupConversationState.COMPLETED, GroupConversationState.IN_PROGRESS)) {
                throw new GroupDiscussionException(
                        "Cannot follow up: conversation is not in COMPLETED state (current: %s)".formatted(gc.getState()));
            }

            boolean success = false;
            try {
                // Re-read after CAS to get the freshest transcript
                gc = conversationStore.read(groupConversationId);

                // Resolve targetAgentId — accept either agent ID or display name
                String resolvedAgentId = targetAgentId;
                String privateConvId = gc.getMemberConversationIds().get(targetAgentId);
                if (privateConvId == null) {
                    // Try resolving as display name
                    for (var entry : gc.getMemberDisplayNames().entrySet()) {
                        if (targetAgentId.equalsIgnoreCase(entry.getValue())) {
                            resolvedAgentId = entry.getKey();
                            privateConvId = gc.getMemberConversationIds().get(resolvedAgentId);
                            break;
                        }
                    }
                }
                if (privateConvId == null) {
                    throw new GroupMemberNotFoundException(
                            // The caller-supplied targetAgentId is deliberately NOT echoed.
                            // REST maps this to 404 with a curated body; keeping the id out
                            // keeps the message safe wherever it DOES surface — e.g. the MCP
                            // tools, which return it as their error string.
                            "The requested agent is not a member of this group conversation. Available members: %s"
                                    .formatted(gc.getMemberDisplayNames()));
                }

                // Resolve display name
                String displayName = gc.getMemberDisplayNames().getOrDefault(resolvedAgentId, resolvedAgentId);

                // Record the user's follow-up question on the transcript
                gc.getTranscript().add(new TranscriptEntry(
                        "user", "User", question, -1, "Follow-up",
                        TranscriptEntryType.FOLLOW_UP, Instant.now(), null, resolvedAgentId));

                // Call the agent's private conversation
                InputData inputData = new InputData();
                inputData.setInput(question);
                Map<String, Context> context = new LinkedHashMap<>();
                // Snapshot, never the live list: the member conversation serialises this
                // context on its own thread while this one keeps appending to the
                // transcript (see the same hand-off in executeAgentTurn).
                List<TranscriptEntry> followUpTranscript;
                synchronized (gc.getTranscript()) {
                    followUpTranscript = List.copyOf(gc.getTranscript());
                }
                context.put("groupTranscript", new Context(Context.ContextType.object, followUpTranscript));
                context.put("groupId", new Context(Context.ContextType.string, gc.getGroupId()));
                context.put("groupConversationId", new Context(Context.ContextType.string, gc.getId()));
                inputData.setContext(context);

                CompletableFuture<String> responseFuture = new CompletableFuture<>();
                try {
                    conversationService.say(DEFAULT_ENV, resolvedAgentId, privateConvId, true, true, null, inputData, false, snapshot -> {
                        String response = groupConversationService.extractResponse(snapshot);
                        if ((response == null || response.isEmpty()) && snapshot != null
                                && snapshot.getConversationState() == ConversationState.ERROR) {
                            response = "[Agent failed to produce output — conversation entered ERROR state]";
                        }
                        responseFuture.complete(response);
                    });
                } catch (Exception e) {
                    throw new GroupExecutionException("Failed to call agent '%s': %s".formatted(resolvedAgentId, e.getMessage()), e);
                }

                int timeoutSeconds = groupConversationService.resolveAgentTimeoutSeconds(gc);
                String response;
                try {
                    response = responseFuture.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    throw new GroupTimeoutException("Follow-up timed out for agent '%s'".formatted(resolvedAgentId), e);
                } catch (ExecutionException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw new GroupExecutionException("Follow-up failed for agent '%s': %s".formatted(resolvedAgentId, e.getMessage()), e);
                }

                // Record the agent's response on the transcript
                gc.getTranscript().add(new TranscriptEntry(
                        resolvedAgentId, displayName, response, -1, "Follow-up",
                        TranscriptEntryType.FOLLOW_UP, Instant.now(), null, null));

                // Transition back to COMPLETED — atomically (CAS on IN_PROGRESS) so a
                // follow-up that races a cancel cannot resurrect a CANCELLED terminal
                // state via an unconditional whole-document write. Mirrors the CAS the
                // error path below already uses.
                gc.setState(GroupConversationState.COMPLETED);
                gc.setLastModified(Instant.now());
                try {
                    conversationStore.updateIfState(gc, GroupConversationState.IN_PROGRESS);
                } catch (IResourceStore.ResourceModifiedException
                        | IGroupConversationStore.GroupConversationGoneException e) {
                    // A concurrent cancel/delete moved the conversation out of
                    // IN_PROGRESS while the follow-up ran — do not overwrite that
                    // terminal state; the follow-up exchange is not applied.
                    throw new GroupDiscussionException(
                            "Follow-up could not be applied: the conversation was concurrently "
                                    + "cancelled or deleted",
                            e);
                }

                success = true;
                counterGroupFollowUp.increment();
                return gc;

            } finally {
                if (!success) {
                    // Restore COMPLETED state so the conversation remains usable.
                    // Wrap in try-catch to avoid masking the original exception.
                    try {
                        conversationStore.compareAndSetState(groupConversationId,
                                GroupConversationState.IN_PROGRESS, GroupConversationState.COMPLETED);
                    } catch (Exception recoveryEx) {
                        LOGGER.warnf("Failed to restore COMPLETED state after follow-up error for %s: %s",
                                LogSanitizer.sanitize(groupConversationId), recoveryEx.getMessage());
                    }
                }
            }
        } finally {
            operationsInProgress.remove(groupConversationId);
        }
    }

    public GroupConversation continueDiscussion(String groupConversationId, String question,
                                                GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        // Validate before taking the guard / transitioning state — a blank question
        // would
        // otherwise be appended to the transcript and drive every phase's agent input.
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be null or blank");
        }

        if (!operationsInProgress.add(groupConversationId)) {
            throw new GroupDiscussionException(
                    "Another operation is already in progress for this group conversation");
        }
        try {
            GroupConversation gc = conversationStore.read(groupConversationId);

            // Atomic state transition: COMPLETED → IN_PROGRESS
            if (!conversationStore.compareAndSetState(groupConversationId, GroupConversationState.COMPLETED, GroupConversationState.IN_PROGRESS)) {
                throw new GroupDiscussionException(
                        "Cannot continue: conversation is not in COMPLETED state (current: %s)".formatted(gc.getState()));
            }

            // Re-read after CAS
            gc = conversationStore.read(groupConversationId);

            // Increment round and append the new question. Persist the follow-up as the
            // run's resumeQuestion so that if a continuation round pauses at an HITL gate,
            // resumeDiscussion re-runs the remaining phases with THIS question rather than
            // the stale round-1 one. Uses a dedicated field (not originalQuestion, which
            // the UI shows as the conversation title) so continuations don't rewrite it.
            gc.setRound(gc.getRound() + 1);
            // Mark where this round's transcript begins, BEFORE appending its
            // question. Everything that asks "what did this round conclude?" scopes
            // to it; without the mark, a round whose synthesis produced nothing
            // adopts the previous round's conclusion as its own.
            gc.setRoundStartTranscriptIndex(gc.getTranscript().size());
            // Clearing these is the other half, and the previous fix omitted it:
            // scoping the SCAN cannot change the outcome while the FIELDS still hold
            // round 1's values. The extraction is `.ifPresent(...)` with no else, and
            // recordDissents MERGES into an existing DecisionRecord — so a round whose
            // synthesis produced nothing kept the previous round's answer and verdict,
            // and had this round's dissents merged onto them.
            gc.setSynthesizedAnswer(null);
            gc.setDecision(null);
            // I12: same defensive class as the two fields above — completion already
            // clears the facilitator's one-off phase divergence, but a continuation
            // must never run round N+1 against round N's inserted phases or count
            // its extensions against stale phase indices.
            gc.setRuntimePhases(null);
            gc.clearFacilitatorExtensions();
            // I11 (final-review finding): the negotiation table is a ROUND-scoped
            // conclusion like the two fields above. Left in place, round 2 of a
            // NEGOTIATION group runs against round 1's proposals — a single fresh
            // acceptance can then reach "unanimous agreement" on signatures cast
            // for a DIFFERENT question, and the signed-acceptance indices in the
            // decision would even point at round 1's transcript entries.
            gc.setNegotiation(null);
            gc.setResumeQuestion(question);
            gc.getTranscript().add(new TranscriptEntry(
                    "user", "User", question, 0, "Question",
                    TranscriptEntryType.QUESTION, Instant.now(), null, null));
            gc.setLastModified(Instant.now());
            // Conditional write (CAS on IN_PROGRESS): an unconditional whole-document
            // update here would resurrect a conversation that a concurrent
            // cancel/close/delete moved to a terminal state in the window after our CAS
            // above. Terminal states must stay irreversible (mirrors followUpWithMember).
            try {
                conversationStore.updateIfState(gc, GroupConversationState.IN_PROGRESS);
            } catch (IResourceStore.ResourceModifiedException
                    | IGroupConversationStore.GroupConversationGoneException e) {
                throw new GroupDiscussionException(
                        "Cannot continue: the conversation was concurrently cancelled, closed or deleted", e);
            }
            counterGroupContinue.increment();

            // Pre-register the control token BEFORE the config-load window so a cancel
            // racing the gap between the CAS above and executeDiscussion's own token
            // registration takes the signal path (stops at the top-of-phase check)
            // rather than the DB branch, which would CAS to CANCELLED and then be
            // overwritten by this leg (mirrors startAndDiscussAsync / resumeDiscussion).
            activeTokens.put(groupConversationId, new DiscussionControlToken());

            // Load the group config and re-execute — wrapped in try-catch so that
            // failures before executeDiscussion() (which has its own failConversation
            // logic) still set the GC to FAILED rather than leaving it IN_PROGRESS.
            try {
                IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(gc.getGroupId());
                if (currentGroupId == null) {
                    throw new IResourceStore.ResourceNotFoundException("Group not found.");
                }
                AgentGroupConfiguration config = groupStore.read(gc.getGroupId(), currentGroupId.getVersion());

                // Resolve phases and re-execute
                List<DiscussionPhase> phases = groupConversationService.resolvePhases(config);
                if (phases.isEmpty()) {
                    // A group config with no phases is a server-side misconfiguration the
                    // caller cannot fix by retrying, not a conversation-state conflict.
                    throw new GroupExecutionException("No discussion phases are defined for this group.");
                }

                // Continuation re-runs the full protocol from the first phase.
                return groupConversationService.executeDiscussion(gc, config, phases, question, listener, 0);
            } catch (Exception e) {
                // executeDiscussion handles its own failures, so this only catches
                // errors from config loading / phase resolution above. If it was never
                // reached, its finally never removed the pre-registered token — drop it
                // here (idempotent: a no-op if executeDiscussion already removed it).
                activeTokens.remove(groupConversationId);
                if (gc.getState() == GroupConversationState.IN_PROGRESS) {
                    failConversation(gc);
                }
                if (e instanceof GroupDiscussionException gde) {
                    throw gde;
                }
                if (e instanceof IResourceStore.ResourceNotFoundException rnfe) {
                    throw rnfe;
                }
                if (e instanceof IResourceStore.ResourceStoreException rse) {
                    throw rse;
                }
                throw new GroupExecutionException("Continue discussion failed: " + e.getMessage(), e);
            }
        } finally {
            operationsInProgress.remove(groupConversationId);
        }
    }

    public GroupConversation closeGroupConversation(String groupConversationId)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        if (!operationsInProgress.add(groupConversationId)) {
            throw new GroupDiscussionException(
                    "Another operation is already in progress for this group conversation");
        }
        try {
            GroupConversation gc = conversationStore.read(groupConversationId);

            // Atomic state transition: try COMPLETED → CLOSED, then FAILED → CLOSED,
            // then CANCELLED → CLOSED. CANCELLED is closeable so an operator can reclaim
            // the ephemeral agents of a discussion cancelled in a window where no running
            // leg cleaned them up (CANCELLED has no follow-up/continue path otherwise).
            boolean transitioned = conversationStore.compareAndSetState(
                    groupConversationId, GroupConversationState.COMPLETED, GroupConversationState.CLOSED);
            if (!transitioned) {
                transitioned = conversationStore.compareAndSetState(
                        groupConversationId, GroupConversationState.FAILED, GroupConversationState.CLOSED);
            }
            if (!transitioned) {
                transitioned = conversationStore.compareAndSetState(
                        groupConversationId, GroupConversationState.CANCELLED, GroupConversationState.CLOSED);
            }
            if (!transitioned) {
                throw new GroupDiscussionException(
                        "Cannot close: conversation is in %s state (expected COMPLETED, FAILED, or CANCELLED)".formatted(gc.getState()));
            }
            counterGroupClose.increment();

            // End all member conversations
            for (String privateConvId : gc.getMemberConversationIds().values()) {
                try {
                    conversationService.endConversation(privateConvId);
                } catch (Exception e) {
                    LOGGER.warnf("Failed to end private conversation %s during close: %s", privateConvId, e.getMessage());
                }
            }

            // Ephemeral agent cleanup (deferred from executeDiscussion)
            cleanupEphemeralAgentsForGroup(gc);

            // I17: close is a lifecycle end — the working artifacts go with it.
            // Their durable trace is the transcript (accepted updates are announced
            // there and a synthesis quotes what mattered), not the working copies.
            deleteArtifactsForGroupConversation(groupConversationId);

            LOGGER.infof("Group conversation %s closed — member conversations ended, ephemeral agents cleaned up",
                    LogSanitizer.sanitize(groupConversationId));

            // Re-read to return the final CLOSED state
            return conversationStore.read(groupConversationId);
        } finally {
            operationsInProgress.remove(groupConversationId);
        }
    }

    /**
     * I17 cascade: removes the discussion's shared artifacts. Warn-and-continue on
     * failure — a broken artifact store must not make discussions undeletable; the
     * user-keyed GDPR erasure sweep (which fails loudly) is the completeness
     * guarantee, this is the tidy path.
     */
    private void deleteArtifactsForGroupConversation(String groupConversationId) {
        if (sharedArtifactStore == null) {
            return;
        }
        try {
            long removed = sharedArtifactStore.deleteByGroupConversationId(groupConversationId);
            if (removed > 0) {
                LOGGER.infof("Removed %d shared artifact(s) of group conversation %s", removed, LogSanitizer.sanitize(groupConversationId));
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to remove shared artifacts of group conversation %s: %s",
                    LogSanitizer.sanitize(groupConversationId), e.getMessage());
        }
    }

    public List<PendingApprovalSummary> listGroupPendingApprovals(String groupId, int limit)
            throws IResourceStore.ResourceStoreException {
        // Bounded summaries — never hand full transcripts to a listing endpoint.
        // The groupId filter is applied in the QUERY (not post-limit), so a busy
        // deployment cannot push this group's items past the limit window.
        int clamped = Math.max(1, Math.min(limit, 1000));
        // I6: pending human turns join the SAME inbox — pauseType "HUMAN_TURN"
        // (plus pendingMemberId) is the kind discriminator; no third inbox. Both
        // states are queried with the FULL limit and merged oldest-pause-first,
        // then capped — filling the window with approvals before ever querying
        // human turns would starve exactly the entries a member's own inbox
        // filter needs to see.
        var pending = new ArrayList<>(
                conversationStore.findByState(GroupConversationState.AWAITING_APPROVAL, groupId, clamped));
        pending.addAll(conversationStore.findByState(GroupConversationState.AWAITING_HUMAN_INPUT, groupId, clamped));
        pending.sort(Comparator.comparing(GroupConversation::getPausedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return pending.stream()
                .limit(clamped)
                .map(gc -> {
                    var summary = new PendingApprovalSummary(
                            gc.getId(), null, gc.getUserId(), gc.getPausedAt(),
                            gc.getHitlPauseReason(),
                            gc.getHitlTimeoutPolicy() != null ? gc.getHitlTimeoutPolicy().name() : null);
                    summary.setGroupId(gc.getGroupId());
                    summary.setApprovalTimeout(gc.getHitlApprovalTimeout());
                    if (gc.getHitlPauseType() != null) {
                        summary.setPauseType(gc.getHitlPauseType().name());
                    }
                    if (gc.getPendingHumanInput() != null) {
                        summary.setPendingMemberId(gc.getPendingHumanInput().memberId());
                    }
                    return summary;
                })
                .toList();
    }

    /**
     * Clean up agents created during a discussion based on the lifecycle policy.
     * Called in the {@code executeDiscussion} finally block.
     */
    public void cleanupEphemeralAgents(GroupConversation gc, AgentGroupConfiguration config) {
        List<String> createdIds = gc.getCreatedAgentIds();
        if (createdIds == null || createdIds.isEmpty()) {
            return;
        }

        var dynamicConfig = config.getDynamicAgents();
        LifecyclePolicy policy = dynamicConfig != null ? dynamicConfig.getLifecyclePolicy() : LifecyclePolicy.EPHEMERAL;

        for (String agentId : createdIds) {
            // 'agent-decides': skip retained agents
            if (policy == LifecyclePolicy.AGENT_DECIDES && gc.getRetainedAgentIds().contains(agentId)) {
                LOGGER.infof("Ephemeral cleanup: agent '%s' retained by creator — skipping", agentId);
                continue;
            }

            // 'keep-deployed': no cleanup
            if (policy == LifecyclePolicy.KEEP_DEPLOYED) {
                continue;
            }

            try {
                boolean shouldDelete = policy == LifecyclePolicy.EPHEMERAL || policy == LifecyclePolicy.AGENT_DECIDES;
                agentFactory.undeployAgent(DEFAULT_ENV, agentId, null);
                LOGGER.infof("Ephemeral cleanup: undeployed agent '%s'", agentId);

                if (shouldDelete) {
                    agentStore.deleteAllPermanently(agentId);
                    retireDeploymentRecords(agentId);
                    LOGGER.infof("Ephemeral cleanup: deleted agent '%s'", agentId);
                }
            } catch (Exception e) {
                LOGGER.warnf("Ephemeral cleanup failed for agent '%s': %s", agentId, e.getMessage());
            }
        }
    }

    /**
     * Load the group config and run ephemeral-agent cleanup for a terminal
     * operation (close / delete). Tolerant of config-load failures.
     */
    private void cleanupEphemeralAgentsForGroup(GroupConversation gc) {
        try {
            IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(gc.getGroupId());
            if (currentGroupId != null) {
                AgentGroupConfiguration config = groupStore.read(gc.getGroupId(), currentGroupId.getVersion());
                cleanupEphemeralAgents(gc, config);
            }
        } catch (Exception e) {
            LOGGER.warnf("Ephemeral agent cleanup failed for group conversation %s: %s",
                    LogSanitizer.sanitize(gc.getId()), e.getMessage());
        }
    }

    /**
     * A deployment record left behind by a deleted ephemeral agent makes the
     * runtime retry a doomed redeploy. Never fatal — the agent is already gone
     * either way, and the startup sweep in AgentDeploymentManagement retires
     * anything missed here.
     */
    private void retireDeploymentRecords(String agentId) {
        if (deploymentStore == null) {
            return;
        }
        try {
            deploymentStore.deleteDeploymentInfos(agentId);
        } catch (Exception e) {
            LOGGER.warnf("Ephemeral cleanup: could not clear deployment record(s) for agent '%s': %s", agentId, e.getMessage());
        }
    }

    /** Terminal states: no further transition may overwrite them. */
    private static boolean isTerminalState(GroupConversationState state) {
        return state == GroupConversationState.COMPLETED
                || state == GroupConversationState.FAILED
                || state == GroupConversationState.CANCELLED
                || state == GroupConversationState.CLOSED;
    }

    public void failConversation(GroupConversation gc) {
        // Never write unconditionally: conversationStore.update() is a whole-document
        // UPSERT, so it would RE-CREATE a conversation another pod deleted and would
        // clobber a terminal state (e.g. a cross-pod CANCELLED) with FAILED.
        //
        // The CAS expectation must come from the PERSISTED state, not the in-memory
        // one:
        // executeDiscussion flips gc to SYNTHESIZING in memory BEFORE the synthesis
        // phase
        // runs and only persists it afterwards, so a CAS on the in-memory value would
        // lose
        // the race and silently strand the conversation IN_PROGRESS forever.
        var inMemoryState = gc.getState();
        GroupConversationState expected;
        try {
            expected = conversationStore.read(gc.getId()).getState();
        } catch (Exception e) {
            expected = inMemoryState; // best effort — the re-read failed
        }
        if (expected == null) {
            expected = inMemoryState;
        }

        gc.setState(GroupConversationState.FAILED);
        gc.setLastModified(Instant.now());
        // Count the failure itself, never the outcome of the race — otherwise a lost
        // CAS
        // would hide the failure from operators entirely.
        counterGroupFailure.increment();

        if (expected == null || isTerminalState(expected)) {
            // Another writer already made it terminal — honor that. Align the in-memory
            // state with it (mirrors persistedTerminalOverride) so executeDiscussion's
            // finally makes the RIGHT ephemeral-agent decision: leaving a stale FAILED
            // here would undeploy the agents of a conversation that is actually COMPLETED
            // (whose agents a follow-up/continue must reuse) or already CLOSED.
            if (expected != null) {
                gc.setState(expected);
            }
            LOGGER.infof("Not failing group conversation %s — it is already terminal (%s)",
                    LogSanitizer.sanitize(gc.getId()), expected);
            return;
        }
        try {
            conversationStore.updateIfState(gc, expected);
        } catch (IResourceStore.ResourceModifiedException | IGroupConversationStore.GroupConversationGoneException e) {
            LOGGER.infof("Not failing group conversation %s — another writer made it terminal or deleted it",
                    LogSanitizer.sanitize(gc.getId()));
        } catch (Exception e) {
            LOGGER.warnf("Failed to update group conversation state to FAILED: %s", e.getMessage());
        }
    }

    /**
     * Reads dynamic agent tracking data from the member's conversation snapshot and
     * propagates it to the group conversation's tracking lists. This bridges the
     * gap between per-turn tool-local tracking lists and the group-level lifecycle
     * tracking in {@link GroupConversation}.
     */
    public static void propagateDynamicAgentTracking(SimpleConversationMemorySnapshot snapshot, GroupConversation gc) {
        if (snapshot == null || snapshot.getConversationSteps() == null) {
            return;
        }
        var steps = snapshot.getConversationSteps();
        if (steps.isEmpty()) {
            return;
        }
        // Check the last step for tracking data
        var lastStep = steps.get(steps.size() - 1);
        if (lastStep == null || lastStep.getConversationStep() == null) {
            return;
        }
        // Collected first, applied after the scan: the same step carries both the
        // cumulative created list (which still names an agent torn down this turn) and
        // the teardown record, and iteration order over step data is not something
        // this should depend on.
        Set<String> created = new LinkedHashSet<>();
        Set<String> retained = new LinkedHashSet<>();
        Set<String> tornDown = new LinkedHashSet<>();
        for (var stepData : lastStep.getConversationStep()) {
            if (stepData == null || stepData.getKey() == null) {
                continue;
            }
            if (MemoryKeys.DYNAMIC_CREATED_AGENT_IDS.equals(stepData.getKey()) && stepData.getValue() instanceof Collection<?> ids) {
                for (Object id : ids) {
                    if (id instanceof String agentId) {
                        created.add(agentId);
                    }
                }
            } else if (MemoryKeys.DYNAMIC_RETAINED_AGENT_IDS.equals(stepData.getKey()) && stepData.getValue() instanceof Collection<?> ids) {
                for (Object id : ids) {
                    if (id instanceof String agentId) {
                        retained.add(agentId);
                    }
                }
            } else if (MemoryKeys.DYNAMIC_TORN_DOWN_AGENT_IDS.equals(stepData.getKey()) && stepData.getValue() instanceof Collection<?> ids) {
                for (Object id : ids) {
                    if (id instanceof String agentId) {
                        tornDown.add(agentId);
                    }
                }
            }
        }

        // Teardowns first, and as tombstones. This snapshot is one member's view and
        // can be stale: member B's turn may still name an agent member A tore down
        // between the two. Recording the teardown before the merge, and having the
        // merge consult the tombstone, makes a teardown final regardless of the order
        // snapshots arrive in — otherwise the id is re-added, keeps occupying a
        // maxCreatedAgentsPerDiscussion slot, and terminal cleanup keeps retrying a
        // deletion that already happened.
        for (String agentId : tornDown) {
            if (gc.recordTeardown(agentId)) {
                LOGGER.debugf("[DYNAMIC] Recorded teardown of agent '%s' in group conversation tracking", agentId);
            }
        }

        // One synchronized region over the created list: CopyOnWriteArrayList makes
        // each add atomic but NOT contains()-then-add(), and these callbacks run on
        // coordinator threads, one per member turn. Two of them observing the same id
        // as absent would both append it, after which a single remove() on teardown
        // leaves a duplicate behind.
        synchronized (gc.getCreatedAgentIds()) {
            for (String agentId : created) {
                if (gc.getTornDownAgentIds().contains(agentId) || gc.getCreatedAgentIds().contains(agentId)) {
                    continue;
                }
                gc.getCreatedAgentIds().add(agentId);
                LOGGER.debugf("[DYNAMIC] Propagated created agent '%s' to group conversation", agentId);
            }
        }
        for (String agentId : retained) {
            if (!gc.getTornDownAgentIds().contains(agentId)) {
                gc.getRetainedAgentIds().add(agentId);
            }
        }
    }
}
