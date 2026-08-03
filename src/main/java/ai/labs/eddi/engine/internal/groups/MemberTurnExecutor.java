/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationAwaitingApprovalException;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDepthExceededException;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupTimeoutException;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import io.micrometer.core.instrument.Counter;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Runs one group member's turn: agent-availability check, private-conversation
 * lifecycle, the retryable call through {@link IConversationService#say} (with
 * cooperative cancellation for parallel phases), member-side HITL fallback
 * handling, and nested sub-group discussions for {@code GROUP}-type members.
 * Extracted from {@code GroupConversationService} (Wave R, R1 step 4) as a pure
 * move — no behavior change.
 * <p>
 * Holds a reference back to the owning {@link GroupConversationService} for two
 * things a member turn cannot do standalone: a {@code GROUP}-type member
 * recurses into a nested sub-discussion via the facade's own public
 * {@code discuss}/{@code cancelDiscussion}, and attachment granting reuses the
 * facade's {@code grantAndInjectAttachments} (widened to {@code public} for
 * this) rather than duplicating its field-injected-{@code IAttachmentStore}
 * construction dance here. Both are pre-existing facade methods, not new
 * coupling introduced by this class.
 *
 * @author ginccc
 */
public class MemberTurnExecutor {

    private static final Logger LOGGER = Logger.getLogger(MemberTurnExecutor.class);

    /**
     * Note recorded on the member's tool-less contribution when a group
     * auto-rejects its gated tool call(s). Kept in one place so the transcript
     * entry and docs stay consistent.
     */
    static final String MEMBER_TOOL_REJECTION_NOTE = "tool approval is not available during group discussions in this version";

    /**
     * Explanatory note for a member turn skipped because the member agent's own
     * conversation requested human approval — kept in one place so the transcript
     * entry, the SSE event, and docs stay consistent.
     */
    private static final String MEMBER_PAUSE_NOTE = "member agent requested human approval (PAUSE_CONVERSATION) — not supported inside group "
            + "discussions; configure group-level HITL via requiresApproval instead";

    private static final Environment DEFAULT_ENV = Environment.production;

    private final IConversationService conversationService;
    private final IAgentFactory agentFactory;
    private final GroupSigningGuard signingGuard;
    private final GroupContextBuilder contextBuilder;
    private final GroupConversationService groupConversationService;
    private final Counter counterGroupMemberPauseSkipped;
    private final int defaultAgentTimeoutSeconds;
    private final int defaultMaxRetries;

    public MemberTurnExecutor(IConversationService conversationService, IAgentFactory agentFactory,
            GroupSigningGuard signingGuard, GroupContextBuilder contextBuilder,
            GroupConversationService groupConversationService, Counter counterGroupMemberPauseSkipped,
            int defaultAgentTimeoutSeconds, int defaultMaxRetries) {
        this.conversationService = conversationService;
        this.agentFactory = agentFactory;
        this.signingGuard = signingGuard;
        this.contextBuilder = contextBuilder;
        this.groupConversationService = groupConversationService;
        this.counterGroupMemberPauseSkipped = counterGroupMemberPauseSkipped;
        this.defaultAgentTimeoutSeconds = defaultAgentTimeoutSeconds;
        this.defaultMaxRetries = defaultMaxRetries;
    }

    /**
     * Runs a member turn that cannot be cancelled — sequential phases, where the
     * orchestrator thread <em>is</em> the member turn.
     */
    public TranscriptEntry executeAgentTurn(GroupMember member, GroupConversation gc, String input, ProtocolConfig protocol, int phaseIdx,
                                            DiscussionPhase phase, String targetAgentId, GroupDiscussionEventListener listener)
            throws GroupDiscussionException {
        return executeAgentTurn(member, gc, input, protocol, phaseIdx, phase, targetAgentId, listener, null);
    }

    /**
     * @param cancellation
     *            cooperative cancellation token for turns that run on a worker
     *            thread, or {@code null} for turns the orchestrator runs itself.
     *            When it is signalled the turn is released from its response wait
     *            and throws
     *            {@link GroupConversationService.MemberTurnCancelledException}
     *            instead of returning an entry — see
     *            {@link GroupConversationService.MemberTurnCancellation}.
     */
    public TranscriptEntry executeAgentTurn(GroupMember member, GroupConversation gc, String input, ProtocolConfig protocol, int phaseIdx,
                                            DiscussionPhase phase, String targetAgentId, GroupDiscussionEventListener listener,
                                            GroupConversationService.MemberTurnCancellation cancellation)
            throws GroupDiscussionException {
        return executeAgentTurn(member, gc, input, protocol, phaseIdx, phase, targetAgentId, listener, cancellation, null);
    }

    /**
     * @param conversationKey
     *            which entry of {@code gc.getMemberConversationIds()} this turn's
     *            private conversation lives under, or {@code null} to use
     *            {@code member.agentId()} — what every ordinary member turn wants.
     *            <p>
     *            Exists for turns that run an <em>existing</em> agent in a role
     *            distinct from its membership, where sharing that agent's
     *            conversation would corrupt it. I2's convergence judge is the
     *            first: it runs the moderator agent, and without a separate key its
     *            "respond with ONLY this JSON" prompts and verdicts land in the
     *            moderator's own history — which a later SYNTHESIS phase (also the
     *            moderator) then reads as recent context, and answers in JSON.
     */
    public TranscriptEntry executeAgentTurn(GroupMember member, GroupConversation gc, String input, ProtocolConfig protocol, int phaseIdx,
                                            DiscussionPhase phase, String targetAgentId, GroupDiscussionEventListener listener,
                                            GroupConversationService.MemberTurnCancellation cancellation, String conversationKey)
            throws GroupDiscussionException {

        if (cancellation != null && cancellation.isCancelled()) {
            throw new GroupConversationService.MemberTurnCancelledException();
        }

        TranscriptEntryType entryType = contextBuilder.mapPhaseToEntryType(phase.type());

        // --- GROUP member: delegate to a nested sub-group discussion ---
        if (member.memberType() == AgentGroupConfiguration.MemberType.GROUP) {
            return executeGroupMemberTurn(member, gc, input, protocol, phaseIdx, phase, entryType, targetAgentId);
        }

        // Check agent availability
        try {
            var agent = agentFactory.getLatestReadyAgent(DEFAULT_ENV, member.agentId());
            if (agent == null) {
                if (protocol.onMemberUnavailable() == ProtocolConfig.MemberUnavailablePolicy.FAIL) {
                    throw new GroupDiscussionException("Agent %s is not deployed and onMemberUnavailable=FAIL".formatted(member.agentId()));
                }
                return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                        Instant.now(), "Agent not deployed", targetAgentId);
            }
        } catch (GroupDiscussionException e) {
            throw e;
        } catch (Exception e) {
            if (protocol.onMemberUnavailable() == ProtocolConfig.MemberUnavailablePolicy.FAIL) {
                throw new GroupDiscussionException("Cannot reach agent %s: %s".formatted(member.agentId(), e.getMessage()), e);
            }
            return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                    Instant.now(), "Agent unavailable: " + e.getMessage(), targetAgentId);
        }

        // Get or create private conversation
        String convKey = conversationKey != null ? conversationKey : member.agentId();
        String privateConvId = gc.getMemberConversationIds().get(convKey);
        boolean firstMemberTurn = privateConvId == null;
        if (privateConvId == null) {
            try {
                Map<String, Context> groupContext = new LinkedHashMap<>();
                groupContext.put("groupId", new Context(Context.ContextType.string, gc.getGroupId()));
                groupContext.put("groupConversationId", new Context(Context.ContextType.string, gc.getId()));
                groupContext.put("groupDepth", new Context(Context.ContextType.string, String.valueOf(gc.getDepth())));
                var result = conversationService.startConversation(DEFAULT_ENV, member.agentId(), gc.getUserId(), groupContext);
                privateConvId = result.conversationId();
                gc.getMemberConversationIds().put(convKey, privateConvId);
            } catch (QuotaExceededException qe) {
                throw new GroupDiscussionException("Tenant quota exceeded: " + qe.getMessage(), qe);
            } catch (Exception e) {
                return handleAgentFailure(member, phaseIdx, phase, protocol, e, "Failed to start conversation", targetAgentId);
            }
        }

        // Build InputData with context
        InputData inputData = new InputData();
        inputData.setInput(input);
        Map<String, Context> context = new LinkedHashMap<>();
        // Snapshot instead of handing out the live list: this context is serialised
        // on the member conversation's own thread while the orchestrator keeps
        // appending entries. Collections.synchronizedList makes add() safe but NOT
        // iteration — publishing it by reference produced intermittent
        // ConcurrentModificationExceptions that failed a member turn at random.
        List<TranscriptEntry> transcriptSnapshot;
        synchronized (gc.getTranscript()) {
            transcriptSnapshot = List.copyOf(gc.getTranscript());
        }
        context.put("groupTranscript", new Context(Context.ContextType.object, transcriptSnapshot));
        context.put("groupId", new Context(Context.ContextType.string, gc.getGroupId()));
        context.put("groupConversationId", new Context(Context.ContextType.string, gc.getId()));
        context.put("groupDepth", new Context(Context.ContextType.string, String.valueOf(gc.getDepth())));

        // Pass group-level dynamic agent guardrails to member agents so that
        // AgentOrchestrator can enforce caps, allowed providers/models, etc.
        if (gc.getDynamicAgentConfig() != null) {
            context.put("dynamicAgentConfig", new Context(Context.ContextType.object, gc.getDynamicAgentConfig()));
        }

        // Share discussion attachments with this member on its first turn: grant the
        // member conversation access to group-owned blobs and inject attachment_*.
        // Later phases rely on extraction-in-history and the readAttachment tool.
        if (firstMemberTurn) {
            groupConversationService.grantAndInjectAttachments(gc, privateConvId, context);
        }

        // Wave 6: Peer verification — if the receiving agent requires it,
        // verify all signed entries from prior speakers before sending context
        signingGuard.verifyPriorEntriesIfRequired(member.agentId(), gc);

        inputData.setContext(context);

        // Call through ConversationService with retry
        int retries = 0;
        // Keep both normalisations in step with parallelBatchBudgetSeconds(), which
        // sizes the orchestrator's batch deadline from exactly these two values.
        int maxRetries = protocol.maxRetries() > 0 ? protocol.maxRetries() : defaultMaxRetries;
        int timeout = protocol.agentTimeoutSeconds() > 0 ? protocol.agentTimeoutSeconds() : defaultAgentTimeoutSeconds;

        while (true) {
            if (cancellation != null && cancellation.isCancelled()) {
                throw new GroupConversationService.MemberTurnCancelledException();
            }
            try {
                CompletableFuture<String> responseFuture = new CompletableFuture<>();
                final String convId = privateConvId;
                // #3: a member agent's own behavior rule may emit PAUSE_CONVERSATION,
                // pausing its private conversation (AWAITING_HUMAN). Member-level HITL
                // is not supported inside a group — flag it here and resolve it after
                // the say callback returns, rather than recording an empty contribution.
                final boolean[] memberPaused = {false};
                // Task 13: capture the paused snapshot so we can branch on its HITL
                // pause type after the callback returns — a TOOL_CALL pause is
                // auto-resolved gracefully (system:group REJECTED), a RULE pause needs
                // a real human and stays SKIP+cancel.
                final SimpleConversationMemorySnapshot[] pausedSnapshot = {null};

                conversationService.say(DEFAULT_ENV, member.agentId(), convId, true, true, null, inputData, false, snapshot -> {
                    if (snapshot != null && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
                        memberPaused[0] = true;
                        pausedSnapshot[0] = snapshot;
                    }

                    String response = contextBuilder.extractResponse(snapshot);
                    // When the agent pipeline fails (e.g. LLM unreachable), extractResponse
                    // returns empty because there are no output keys — only pipeline metadata.
                    // Surface the failure as explicit content so the transcript entry is not empty.
                    if ((response == null || response.isEmpty()) && snapshot != null
                            && snapshot.getConversationState() == ConversationState.ERROR) {
                        response = "[Agent failed to produce output — conversation entered ERROR state]";
                    }

                    // Propagate dynamic agent tracking data from the member's conversation
                    // memory to the GroupConversation for lifecycle cleanup.
                    GroupConversationService.propagateDynamicAgentTracking(snapshot, gc);

                    // Wave 0, F5: harvest this member's tracked cost so far.
                    // Attributed under the CONVERSATION key, not the agent id: costs
                    // are per-conversation (AUDIT_COST is that conversation's running
                    // total), and GroupCostLedger replaces rather than adds. A turn in
                    // a separate conversation attributed to the agent's own key would
                    // overwrite that agent's real accumulated cost with this
                    // conversation's smaller one — silently shrinking totalCost and
                    // loosening I1's ceiling.
                    GroupCostLedger.accumulateMemberCost(gc, convKey, snapshot);

                    responseFuture.complete(response);
                });

                // The only await point of a member turn — and therefore the lever for
                // cancelling it. Registering the future means an aborting orchestrator
                // completes it exceptionally and releases this turn at once, instead of
                // the turn running to completion and writing into a group document the
                // orchestrator has already persisted.
                String response;
                try {
                    if (cancellation != null) {
                        cancellation.register(responseFuture);
                    }
                    response = responseFuture.get(timeout, TimeUnit.SECONDS);
                } catch (ExecutionException ee) {
                    if (cancellation != null && cancellation.isCancelled()) {
                        throw new GroupConversationService.MemberTurnCancelledException();
                    }
                    throw ee;
                } finally {
                    if (cancellation != null) {
                        cancellation.unregister(responseFuture);
                    }
                }

                // #3 / Task 13: member requested human approval mid-turn. A TOOL_CALL
                // pause is auto-resolved gracefully — the group rejects the gated
                // tool(s) (system:group REJECTED) via the NORMAL resume path so the
                // member's LLM receives rejection tool-results and produces a coherent
                // tool-less answer that becomes its contribution. Only if that resume
                // cannot complete (times out / re-pauses) do we fall back to the
                // RULE-pause behavior: cancel the stranded pause + record SKIPPED.
                if (memberPaused[0]) {
                    if (pausedSnapshot[0] != null && "TOOL_CALL".equals(pausedSnapshot[0].getHitlPauseType())) {
                        TranscriptEntry graceful = tryResolveMemberToolPause(
                                member, gc, convId, input, timeout, phaseIdx, phase, entryType, targetAgentId);
                        if (graceful != null) {
                            return graceful;
                        }
                    }
                    // RULE pause (needs a real human) or a TOOL_CALL graceful attempt
                    // that could not complete → existing SKIP + cancel handling.
                    return handleMemberPause(member, gc, convId, phaseIdx, phase, targetAgentId, listener);
                }

                // I4: an abstention replaces the typed contribution entirely — the
                // member said it has nothing to add, so recording "PASS" as its
                // OPINION would put a non-answer into the record that peers, the
                // synthesizer and the convergence judge all then read as a position.
                if (AbstentionDetector.isEnabledFor(phase) && AbstentionDetector.isAbstention(response)) {
                    return abstentionEntry(member, phaseIdx, phase, targetAgentId);
                }

                // Wave 6: Sign inter-agent messages with full envelope if configured
                GroupSigningGuard.SigningResult signing = signingGuard.signOutgoingMessage(
                        member.agentId(), gc.getGroupId(), response, phase.name());

                var entry = new TranscriptEntry(
                        member.agentId(), member.displayName(), response,
                        phaseIdx, phase.name(), entryType, Instant.now(),
                        null, targetAgentId, signing.signature(),
                        signing.nonce(), signing.timestampMs(), signing.keyVersion());
                return entry;

            } catch (GroupConversationService.MemberTurnCancelledException e) {
                // Cooperative cancellation is not a member failure: never retried, never
                // turned into a transcript entry by this thread.
                throw e;

            } catch (TimeoutException e) {
                if (cancellation != null && cancellation.isCancelled()) {
                    throw new GroupConversationService.MemberTurnCancelledException();
                }
                if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.RETRY && retries < maxRetries) {
                    retries++;
                    LOGGER.warnf("Agent %s timed out (attempt %d/%d), retrying...", member.agentId(), retries, maxRetries);
                    continue;
                }
                if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT) {
                    // A member-agent timeout — surface as GroupTimeoutException so it maps
                    // to 504 at REST (executeDiscussion's re-wrap preserves the subtype).
                    throw new GroupTimeoutException(
                            "Agent %s timed out and onAgentFailure=ABORT".formatted(member.agentId()), null);
                }
                return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                        Instant.now(), "Timeout after " + timeout + "s", targetAgentId);

            } catch (ConversationAwaitingApprovalException e) {
                // Member's private conversation is (or became) AWAITING_HUMAN before the say
                // callback ran — member-level HITL is unsupported inside a group. Cancel the
                // stranded pause and record SKIPPED (mirrors the memberPaused[0] path).
                // convId is try-scoped, so pass the method-level privateConvId (same value).
                return handleMemberPause(member, gc, privateConvId, phaseIdx, phase, targetAgentId, listener);
            } catch (Exception e) {
                if (cancellation != null && cancellation.isCancelled()) {
                    throw new GroupConversationService.MemberTurnCancelledException();
                }
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                // Quota errors are non-retryable and affect all agents — abort immediately
                if (cause instanceof QuotaExceededException) {
                    throw new GroupDiscussionException("Tenant quota exceeded: " + cause.getMessage(), cause);
                }
                if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.RETRY && retries < maxRetries) {
                    retries++;
                    LOGGER.warnf("Agent %s failed (attempt %d/%d): %s", member.agentId(), retries, maxRetries, cause.getMessage());
                    continue;
                }
                return handleAgentFailure(member, phaseIdx, phase, protocol, cause, "Agent execution failed", targetAgentId);
            }
        }
    }

    /**
     * Task 13 — graceful resolution of a member TOOL_CALL pause inside a group. The
     * group has no human reviewer, so it auto-rejects the gated tool call(s) with a
     * {@code system:group} REJECTED decision routed through the NORMAL resume path:
     * the member's LLM receives rejection tool-results (Task 9) and produces a
     * coherent tool-less answer, which becomes this turn's contribution.
     * <p>
     * The resume is driven synchronously within the member-turn budget. If it
     * cannot complete in time, or the member re-pauses (its resumed snapshot is
     * still {@code AWAITING_HUMAN}), this returns {@code null} — the caller then
     * falls back to the existing member-pause handling (SKIP + cancel). A resume
     * infrastructure failure likewise returns {@code null} so the fallback still
     * terminates the turn cleanly.
     *
     * {@code input} is unused and stays: static analysis flags it every run, but
     * the caller passes the member's turn input positionally alongside eight other
     * arguments, and this method is the natural home for a retry that re-sends it —
     * which the plan's I7 (delegation hardening) will need. Dropping one parameter
     * from the middle of a nine-argument call is also the shape of change most
     * likely to be silently mis-applied at the call site.
     *
     * @return a real contribution entry on graceful success, or {@code null} to
     *         signal "fall back to SKIP + cancel"
     */
    public TranscriptEntry tryResolveMemberToolPause(GroupMember member, GroupConversation gc, String convId,
                                                     String input, int timeoutSeconds, int phaseIdx,
                                                     DiscussionPhase phase, TranscriptEntryType entryType,
                                                     String targetAgentId) {
        LOGGER.infof("Member agent '%s' TOOL_CALL-paused during group discussion %s (phase %d) — "
                + "auto-rejecting the gated tool call(s) (system:group) and resuming for a tool-less answer",
                member.agentId(), gc.getId(), phaseIdx);

        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.REJECTED);
        decision.setDecidedBy("system:group");
        decision.setNote(MEMBER_TOOL_REJECTION_NOTE);

        var resumeFuture = new CompletableFuture<SimpleConversationMemorySnapshot>();
        try {
            conversationService.resumeConversation(convId, decision,
                    new ConversationResponseHandler() {
                        @Override
                        public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                            resumeFuture.complete(snapshot);
                        }

                        @Override
                        public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                            // Dropped without producing a fresh answer — treat as
                            // "could not complete" so the caller falls back.
                            resumeFuture.complete(null);
                        }
                    });
        } catch (Exception e) {
            LOGGER.warnf("Graceful tool-pause resume failed to start for member '%s' (conv %s): %s — falling back to SKIP+cancel",
                    member.agentId(), convId, e.getMessage());
            return null;
        }

        SimpleConversationMemorySnapshot resumed;
        try {
            resumed = resumeFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOGGER.warnf("Graceful tool-pause resume did not complete within %ds for member '%s' (conv %s) — falling back to SKIP+cancel",
                    timeoutSeconds, member.agentId(), convId);
            return null;
        } catch (Exception e) {
            LOGGER.warnf("Graceful tool-pause resume errored for member '%s' (conv %s): %s — falling back to SKIP+cancel",
                    member.agentId(), convId, e instanceof ExecutionException && e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return null;
        }

        // Re-paused (still awaiting a human) → the graceful attempt did not resolve.
        if (resumed == null || resumed.getConversationState() == ConversationState.AWAITING_HUMAN) {
            LOGGER.warnf("Member '%s' re-paused after graceful tool rejection (conv %s) — falling back to SKIP+cancel",
                    member.agentId(), convId);
            return null;
        }

        GroupConversationService.propagateDynamicAgentTracking(resumed, gc);

        String response = contextBuilder.extractResponse(resumed);
        if ((response == null || response.isEmpty())
                && resumed.getConversationState() == ConversationState.ERROR) {
            response = "[Agent failed to produce output — conversation entered ERROR state]";
        }

        LOGGER.infof("Member '%s' produced a tool-less contribution after group auto-rejection (conv %s)",
                member.agentId(), convId);

        // Signed exactly like the normal path above: this is a real member
        // contribution with the same role in the transcript, and peers read it
        // through the same verifyPriorEntriesIfRequired. Returning it unsigned meant
        // a signing-enabled agent silently produced an unverifiable entry whenever
        // its tool pause was auto-rejected — the one branch where an entry's
        // provenance matters most, since its content was shaped by a rejection the
        // agent did not choose. signOutgoingMessage yields UNSIGNED when signing is
        // off, so this is a no-op for agents that never sign.
        // I4: same abstention rule as the normal path. A member whose gated tool call
        // was auto-rejected can legitimately conclude it has nothing to add; without
        // this the identical reply becomes a real contribution on one path and an
        // abstention on the other, purely by whether a tool happened to be called.
        if (AbstentionDetector.isEnabledFor(phase) && AbstentionDetector.isAbstention(response)) {
            return abstentionEntry(member, phaseIdx, phase, targetAgentId);
        }

        GroupSigningGuard.SigningResult signing = signingGuard.signOutgoingMessage(
                member.agentId(), gc.getGroupId(), response, phase.name());

        return new TranscriptEntry(member.agentId(), member.displayName(), response,
                phaseIdx, phase.name(), entryType, Instant.now(),
                null, targetAgentId, signing.signature(),
                signing.nonce(), signing.timestampMs(), signing.keyVersion());
    }

    /**
     * The {@code ABSTAINED} record for a member that passed (I4).
     * <p>
     * Content is deliberately {@code null}, not the literal "PASS": every reader of
     * the transcript ({@code GroupContextBuilder}'s peer view, the synthesizer,
     * I2's convergence judge) treats content as a position, and the whole point of
     * an abstention is that there is no position. The type carries the meaning;
     * F4's visibility matrix already hides the entry from peers. Unsigned for the
     * same reason — there is no member-authored content to attest to.
     */
    private TranscriptEntry abstentionEntry(GroupMember member, int phaseIdx, DiscussionPhase phase, String targetAgentId) {
        LOGGER.debugf("Member '%s' abstained in phase '%s'", member.agentId(), phase.name());
        return new TranscriptEntry(member.agentId(), member.displayName(), null,
                phaseIdx, phase.name(), TranscriptEntryType.ABSTAINED, Instant.now(),
                null, targetAgentId);
    }

    /**
     * Resolves a member turn whose private conversation paused for human approval
     * (#3). Member-level HITL is unsupported inside a group in v1: leaving the
     * pause armed strands an approval no human can meaningfully resolve, and
     * recording the empty snapshot as a real contribution poisons later phases. So
     * we cancel the member's pause (disarming its timeout schedule and removing it
     * from the regular pending-approvals surface), count a metric, notify
     * observers, and return a SKIPPED entry with an actionable note.
     */
    public TranscriptEntry handleMemberPause(GroupMember member, GroupConversation gc, String convId,
                                             int phaseIdx, DiscussionPhase phase, String targetAgentId,
                                             GroupDiscussionEventListener listener) {
        LOGGER.warnf("Member agent '%s' paused for human approval during group discussion %s (phase %d) — "
                + "member-level HITL is unsupported inside a group; skipping the turn and cancelling the pause",
                member.agentId(), gc.getId(), phaseIdx);
        try {
            conversationService.cancelConversation(convId, ControlSignal.CANCEL_GRACEFUL, "system:group");
        } catch (Exception e) {
            // Best-effort — still record SKIPPED so the discussion terminates cleanly.
            LOGGER.warnf("Failed to cancel stranded member pause %s: %s", convId, e.getMessage());
        }
        counterGroupMemberPauseSkipped.increment();
        if (listener != null) {
            listener.onMemberPauseSkipped(new GroupConversationEventSink.MemberPauseSkippedEvent(
                    member.agentId(), member.displayName(), phaseIdx, phase.name(), MEMBER_PAUSE_NOTE));
        }
        return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(),
                TranscriptEntryType.SKIPPED, Instant.now(), MEMBER_PAUSE_NOTE, targetAgentId);
    }

    /**
     * Executes a GROUP member's turn by running a nested sub-group discussion. The
     * sub-group's synthesized answer (or full transcript if no moderator) becomes
     * this member's response in the parent group.
     */
    public TranscriptEntry executeGroupMemberTurn(GroupMember member, GroupConversation gc, String input, ProtocolConfig protocol, int phaseIdx,
                                                  DiscussionPhase phase, TranscriptEntryType entryType, String targetAgentId)
            throws GroupDiscussionException {
        try {
            // member.agentId() is actually a groupId for GROUP members
            String subGroupId = member.agentId();
            int nextDepth = gc.getDepth() + 1;

            LOGGER.infof("Executing sub-group '%s' (depth %d) as member of parent group '%s'", subGroupId, nextDepth, gc.getGroupId());

            // Propagate the parent's attachments to the nested group so its members
            // receive them too (each nested member conversation is granted in turn).
            // I1: also hand down what is LEFT of this discussion's budget, so a nested
            // group cannot spend past a ceiling its parent is already bound by. Null
            // ceiling → null remaining (unlimited); computed here because only the
            // parent knows both its ceiling and its spend so far.
            //
            // KNOWN BOUND, not a bug to fix here: in a PARALLEL phase, N nested GROUP
            // members are dispatched together and each reads the same "remaining R"
            // (a child's spend only rolls into the parent when it RETURNS), so the
            // batch can collectively reach N×R against a parent budget of R. This is
            // the same accepted-overshoot shape the spec already documents for a
            // single in-flight turn, scaled by the batch. Bounding it properly needs
            // budget RESERVATION at dispatch (each child claiming a slice up front)
            // rather than a read of the current remainder — a design change, not a
            // tweak, and one that would also have to decide how to return unspent
            // slices. Sequential nesting — the common shape — is exact.
            Double remainingBudget = protocol.maxCostPerDiscussion() == null
                    ? null
                    : Math.max(0.0, protocol.maxCostPerDiscussion() - gc.getTotalCost());
            GroupConversation subConversation = groupConversationService.discuss(subGroupId, input, gc.getUserId(), nextDepth, null,
                    gc.getAttachments(), remainingBudget);

            // Wave 0, F5: roll the child's cost up into this group's attribution —
            // before the AWAITING_APPROVAL branch below, so a nested pause that gets
            // cancelled still counts the real spend its members already incurred.
            GroupCostLedger.accumulateNestedGroupCost(gc, member, subConversation);

            // Phase 5d: Nested group HITL guard — if the sub-group paused for
            // approval, don't extract a partial answer. Nested HITL is not
            // supported in v1; cancel the stranded sub-pause (releases its timeout
            // schedule and removes it from pending-approval listings) and return a
            // SKIPPED entry with explanation.
            if (subConversation.getState() == GroupConversationState.AWAITING_APPROVAL) {
                LOGGER.warnf("Sub-group '%s' is awaiting approval — nested HITL not supported in v1; cancelling sub-pause",
                        subGroupId);
                try {
                    groupConversationService.cancelDiscussion(subConversation.getId(), ControlSignal.CANCEL_GRACEFUL);
                } catch (Exception cancelEx) {
                    // best-effort cleanup — still return the SKIPPED entry below
                    LOGGER.warnf("Failed to cancel stranded sub-group pause %s: %s",
                            subConversation.getId(), cancelEx.getMessage());
                }
                return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(),
                        TranscriptEntryType.SKIPPED, Instant.now(),
                        "Sub-group awaiting approval — nested HITL not supported in v1", targetAgentId);
            }

            // Extract the synthesized answer, or concatenate all responses
            String response = subConversation.getSynthesizedAnswer();
            if (response == null || response.isBlank()) {
                response = subConversation.getTranscript().stream().filter(e -> e.content() != null)
                        .map(e -> "%s: %s".formatted(e.speakerDisplayName(), e.content())).collect(Collectors.joining("\n\n"));
            }

            return new TranscriptEntry(member.agentId(), member.displayName(), response, phaseIdx, phase.name(), entryType, Instant.now(), null,
                    targetAgentId);

        } catch (GroupDepthExceededException e) {
            return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                    Instant.now(), "Sub-group depth exceeded: " + e.getMessage(), targetAgentId);
        } catch (Exception e) {
            return handleAgentFailure(member, phaseIdx, phase, protocol, e, "Sub-group discussion failed", targetAgentId);
        }
    }

    public TranscriptEntry handleAgentFailure(GroupMember member, int phaseIdx, DiscussionPhase phase, ProtocolConfig protocol, Throwable cause,
                                              String prefix, String targetAgentId)
            throws GroupDiscussionException {

        if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT) {
            throw new GroupDiscussionException(
                    "%s for agent %s and onAgentFailure=ABORT: %s".formatted(prefix, member.agentId(), cause.getMessage()));
        }
        return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED, Instant.now(),
                prefix + ": " + cause.getMessage(), targetAgentId);
    }

    public TranscriptEntry errorEntry(GroupMember member, int phaseIdx, DiscussionPhase phase, String message) {
        String agentId = member != null ? member.agentId() : "unknown";
        String displayName = member != null ? member.displayName() : "Unknown";
        return new TranscriptEntry(agentId, displayName, null, phaseIdx, phase.name(), TranscriptEntryType.ERROR, Instant.now(), message, null);
    }
}
