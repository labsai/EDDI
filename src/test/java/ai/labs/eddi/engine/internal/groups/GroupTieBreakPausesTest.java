/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OptionsSource;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteMethod;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig.OnTieBreakTimeout;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code tiePolicy: HUMAN_DECIDES}: the pause a tied vote waits in, its
 * resolution from the decider's choice, and its timeouts.
 */
@DisplayName("GroupTieBreakPauses — commit, resolve, timeout")
class GroupTieBreakPausesTest {

    private static final String GC_ID = "gc-1";
    private static final String GROUP_ID = "group-1";
    private static final String DECIDER = "founder@acme.com";
    private static final List<String> OPTIONS = List.of("SEO", "Paid pilot");

    private IGroupConversationStore conversationStore;
    private IAgentGroupStore groupStore;
    private GroupConversationService service;
    private ExecutorService executor;
    private MemberTurnExecutor memberTurnExecutor;
    private AuditLedgerService audit;
    private SimpleMeterRegistry meters;
    private ConcurrentHashMap<String, DiscussionControlToken> controls;
    private GroupHitlCoordinator coordinator;
    private GroupTieBreakPauses pauses;
    private AgentGroupConfiguration config;
    private final List<Runnable> submitted = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        conversationStore = mock(IGroupConversationStore.class);
        groupStore = mock(IAgentGroupStore.class);
        service = mock(GroupConversationService.class);
        executor = mock(ExecutorService.class);
        memberTurnExecutor = mock(MemberTurnExecutor.class);
        audit = mock(AuditLedgerService.class);
        when(audit.isEnabled()).thenReturn(true);
        meters = new SimpleMeterRegistry();
        controls = new ConcurrentHashMap<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(inv -> {
            submitted.add(inv.getArgument(0));
            return null;
        });
        var callers = new CallerIdentityContext(null, null);
        coordinator = new GroupHitlCoordinator(groupStore, conversationStore, mock(IScheduleStore.class), audit,
                new GroupSigningGuard(null, null, "default"), controls, executor, callers, service,
                meters.counter("pause"), meters.counter("resume"), meters.counter("failure"));
        var engine = new PhaseExecutionEngine(memberTurnExecutor, mock(GroupContextBuilder.class), executor, callers);
        pauses = new GroupTieBreakPauses(groupStore, conversationStore, audit, controls, executor, callers, service, coordinator, engine,
                meters, meters.counter("pause"), meters.counter("resume"));
        coordinator.setTieBreakPauses(pauses);

        config = configWith(false, OnTieBreakTimeout.NO_DECISION);
        var rid = mock(IResourceStore.IResourceId.class);
        when(rid.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(rid);
        when(groupStore.read(eq(GROUP_ID), anyInt())).thenAnswer(inv -> config);
        when(service.effectivePhases(any(), any())).thenAnswer(inv -> ((AgentGroupConfiguration) inv.getArgument(1)).getPhases());
    }

    private static AgentGroupConfiguration configWith(boolean voteRequiresApproval, OnTieBreakTimeout onTimeout) {
        var c = new AgentGroupConfiguration();
        c.setName("Board");
        c.setModeratorAgentId("mod");
        var vote = new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, OPTIONS, 0.5, Map.of(), false, TiePolicy.HUMAN_DECIDES,
                new HumanTieBreakConfig(DECIDER, "PT24H", onTimeout));
        c.setPhases(List.of(
                new DiscussionPhase("Opinions", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false),
                new DiscussionPhase("Board vote", PhaseType.VOTE, "ALL", TurnOrder.PARALLEL, ContextScope.NONE, false, null, 1,
                        voteRequiresApproval, null, false, vote),
                new DiscussionPhase("Wrap-up", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1,
                        false)));
        c.setProtocol(new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 0, ProtocolConfig.MemberUnavailablePolicy.SKIP, 10,
                null, ProtocolConfig.CostPolicy.SYNTHESIZE_NOW));
        return c;
    }

    /**
     * A discussion whose VOTE phase (index 1) just tied 1:1, as recordVoteDecision
     * leaves it.
     */
    private GroupConversation tiedRunning() {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId("owner");
        gc.setOriginalQuestion("Which bet?");
        gc.setState(GroupConversationState.IN_PROGRESS);
        gc.getTranscript().add(new TranscriptEntry("a1", "Alice", "We should grow", 0, "Opinions", TranscriptEntryType.OPINION,
                Instant.now(), null, null));
        gc.getTranscript().add(new TranscriptEntry("a1", "Alice", "{\"vote\":\"SEO\",\"statement\":\"compounding\"}", 1, "Board vote",
                TranscriptEntryType.VOTE, Instant.now(), null, null));
        gc.getTranscript().add(new TranscriptEntry("a2", "Bob", "{\"vote\":\"Paid pilot\",\"statement\":\"speed\"}", 1, "Board vote",
                TranscriptEntryType.VOTE, Instant.now(), null, null));
        var phase = config.getPhases().get(1);
        var outcome = VoteTallyEngine.tally(gc.getTranscript().subList(1, 3), 2, OPTIONS, phase.voteConfig(), phase.name());
        gc.setDecision(outcome.decision());
        return gc;
    }

    private HumanTieBreakEngine.PendingTieBreak tieOf(GroupConversation gc) {
        var phase = config.getPhases().get(1);
        var outcome = VoteTallyEngine.tally(gc.getTranscript().subList(1, 3), 2, OPTIONS, phase.voteConfig(), phase.name());
        return HumanTieBreakEngine.onTie(gc, config, phase, phase.voteConfig(), outcome, 1);
    }

    /** Commits the tie-break pause and returns the paused conversation. */
    private GroupConversation paused(GroupDiscussionEventListener listener) throws Exception {
        var gc = tiedRunning();
        coordinator.commitTieBreakPause(gc, 1, 0, config.getPhases().get(1), tieOf(gc), 7, listener);
        when(conversationStore.read(GC_ID)).thenReturn(gc);
        return gc;
    }

    @Nested
    @DisplayName("commit")
    class Commit {

        @Test
        @DisplayName("pauses as a non-speaker human turn of kind TIE_BREAK, charging no turn")
        void commitFields() throws Exception {
            var listener = mock(GroupDiscussionEventListener.class);

            var gc = paused(listener);

            assertEquals(GroupConversationState.AWAITING_HUMAN_INPUT, gc.getState());
            assertEquals(HitlPauseType.HUMAN_TURN, gc.getHitlPauseType());
            assertEquals(new GroupConversation.ResumePoint(1, 0, -1, GroupConversation.RESUME_KIND_TIE_BREAK), gc.getResumePoint());
            var pending = gc.getPendingHumanInput();
            assertEquals(DECIDER, pending.memberId());
            assertEquals(-1, pending.speakerIdx());
            assertEquals("SKIP_TURN", pending.onTimeout(), "the handler-facing policy stays one every pod knows");
            assertTrue(pending.detail().isTieBreak());
            assertEquals(OPTIONS, pending.detail().options());
            assertEquals("NO_DECISION", pending.detail().timeoutResolution());
            assertEquals(7, gc.getPausedTurnCount(), "a human choice spends no turn");
            assertEquals("PT24H", gc.getHitlApprovalTimeout());
            assertEquals(DecisionType.NONE, gc.getDecision().type(), "the tally stays visible while it waits");
            var event = ArgumentCaptor.forClass(GroupConversationEventSink.HumanInputRequestedEvent.class);
            verify(listener).onHumanInputRequested(event.capture());
            assertEquals(OPTIONS, event.getValue().detail().options());
        }

        @Test
        @DisplayName("ABORT on timeout rides the plain ABORT policy the timeout handler already cancels on")
        void abortPolicy() throws Exception {
            config = configWith(false, OnTieBreakTimeout.ABORT);

            var gc = paused(null);

            assertEquals("ABORT", gc.getPendingHumanInput().onTimeout());
        }

        @Test
        @DisplayName("a deferred facilitator escalation yields to the tie-break, with a note")
        void escalationYields() {
            var gc = tiedRunning();
            var escalate = new FacilitatorEngine.FacilitatorAction(FacilitatorEngine.FacilitatorAction.Kind.ESCALATE, null,
                    new FacilitatorEngine.FacilitatorAction.Escalation("cto@acme.com", "Should we pause?"));
            var insert = new FacilitatorEngine.FacilitatorAction(FacilitatorEngine.FacilitatorAction.Kind.INSERT_VOTE,
                    config.getPhases().get(1), null);

            assertNull(GroupTieBreakPauses.yieldEscalation(gc, tieOf(gc), escalate, 1, config.getPhases().get(1)));
            assertEquals(TranscriptEntryType.FACILITATION, gc.getTranscript().getLast().type());
            assertSame(insert, GroupTieBreakPauses.yieldEscalation(gc, tieOf(gc), insert, 1, config.getPhases().get(1)));
            assertSame(escalate, GroupTieBreakPauses.yieldEscalation(gc, null, escalate, 1, config.getPhases().get(1)));
        }
    }

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("a valid choice records a human VOTE decision, a transcript note, and resumes after the vote")
        void validChoice() throws Exception {
            var gc = paused(null);

            coordinator.submitHumanInput(GC_ID, DECIDER, "Revenue first", "Paid pilot", DECIDER, null);

            verify(conversationStore).updateIfStateAndPause(eq(gc), eq(GroupConversationState.AWAITING_HUMAN_INPUT), any());
            assertEquals(DecisionType.VOTE, gc.getDecision().type());
            assertEquals("Paid pilot", gc.getDecision().winner());
            assertEquals(HumanTieBreakEngine.METHOD_HUMAN_TIEBREAK, gc.getDecision().method());
            assertEquals("compounding", gc.getDecision().dissents().getFirst().position());
            var note = gc.getTranscript().getLast();
            assertEquals(TranscriptEntryType.HUMAN_INPUT, note.type());
            assertTrue(note.content().contains("\"Paid pilot\" — Revenue first"), note.content());
            assertNull(gc.getResumePoint());
            assertNull(gc.getPendingHumanInput());
            assertEquals(1, submitted.size());

            submitted.getFirst().run();

            verify(service).executeDiscussion(eq(gc), eq(config), eq(config.getPhases()), eq("Which bet?"), any(), eq(2));
            assertEquals(1.0, meters.get(GroupTieBreakPauses.METRIC_TIEBREAKS).tag("resolution", "human").counter().count());
            var entry = ArgumentCaptor.forClass(AuditEntry.class);
            verify(audit).submit(entry.capture());
            assertEquals("group.vote_tiebreak", entry.getValue().output().get("approvalCategory"));
            assertEquals("HUMAN_CHOICE", entry.getValue().output().get("reasonCode"));
            assertEquals("Paid pilot", entry.getValue().output().get("chosen"));
            assertTrue(!entry.getValue().output().containsKey("decisionOutcome"), "a human turn carries no decisionOutcome");
        }

        @Test
        @DisplayName("an invalid choice is refused before anything changes")
        void invalidChoice() throws Exception {
            var gc = paused(null);

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> coordinator.submitHumanInput(GC_ID, DECIDER, null, "Both", DECIDER, null));
            assertTrue(ex.getMessage().startsWith(HumanTieBreakEngine.INVALID_CHOICE_PREFIX));
            assertThrows(IllegalArgumentException.class,
                    () -> coordinator.submitHumanInput(GC_ID, DECIDER, "I pick the pilot", null, DECIDER, null),
                    "free text without a choice is no answer to a tie");

            verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
            assertEquals(GroupConversationState.AWAITING_HUMAN_INPUT, gc.getState());
            assertEquals(DecisionType.NONE, gc.getDecision().type());
        }

        @Test
        @DisplayName("a second submit loses the compare-and-swap (409)")
        void secondSubmitConflicts() throws Exception {
            paused(null);
            doThrow(new IResourceStore.ResourceModifiedException("raced")).when(conversationStore)
                    .updateIfStateAndPause(any(), eq(GroupConversationState.AWAITING_HUMAN_INPUT), any());

            assertThrows(IResourceStore.ResourceModifiedException.class,
                    () -> coordinator.submitHumanInput(GC_ID, DECIDER, null, "SEO", DECIDER, null));
            assertTrue(submitted.isEmpty());
        }

        @Test
        @DisplayName("a vote phase with its own approval gate pauses for approval after the decision")
        void approvalAfterDecision() throws Exception {
            config = configWith(true, OnTieBreakTimeout.NO_DECISION);
            var gc = paused(null);

            coordinator.submitHumanInput(GC_ID, DECIDER, null, "SEO", DECIDER, null);
            submitted.getFirst().run();

            assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
            assertEquals(1, gc.getPausedAtPhaseIndex());
            assertEquals("SEO", gc.getDecision().winner());
            verify(service, never()).executeDiscussion(any(), any(), any(), anyString(), any(), anyInt());
        }

        @Test
        @DisplayName("a resume that fails before the discussion owns the leg drops the token and fails the conversation")
        void resumeFailureCleansUp() throws Exception {
            paused(null);
            doThrow(new IllegalStateException("store down")).when(service).executeDiscussion(any(), any(), any(),
                    anyString(), any(), anyInt());

            coordinator.submitHumanInput(GC_ID, DECIDER, null, "SEO", DECIDER, null);
            assertTrue(controls.containsKey(GC_ID), "the resume registered its token");
            submitted.getFirst().run();

            assertTrue(!controls.containsKey(GC_ID), "the token does not linger");
            verify(conversationStore).compareAndSetState(GC_ID, GroupConversationState.IN_PROGRESS, GroupConversationState.FAILED);
        }

        @Test
        @DisplayName("an executor that refuses the resume restores the pause")
        void rollback() throws Exception {
            var gc = paused(null);
            when(executor.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("full"));

            assertThrows(Exception.class, () -> coordinator.submitHumanInput(GC_ID, DECIDER, null, "SEO", DECIDER, null));

            assertEquals(GroupConversationState.AWAITING_HUMAN_INPUT, gc.getState());
            assertNotNull(gc.getPendingHumanInput());
            assertEquals(DecisionType.NONE, gc.getDecision().type());
            assertEquals(TranscriptEntryType.VOTE, gc.getTranscript().getLast().type());
        }
    }

    @Nested
    @DisplayName("timeout")
    class Timeout {

        @Test
        @DisplayName("NO_DECISION keeps an honest NONE naming the decider, and moves on")
        void noDecision() throws Exception {
            var gc = paused(null);

            coordinator.skipHumanTurnOnTimeout(GC_ID, null);
            submitted.getFirst().run();

            assertEquals(DecisionType.NONE, gc.getDecision().type());
            assertTrue(gc.getDecision().outcome().contains("No decision from Founder")
                    || gc.getDecision().outcome().contains("No decision from " + DECIDER), gc.getDecision().outcome());
            assertEquals(TranscriptEntryType.SKIPPED, gc.getTranscript().getLast().type());
            verify(service).executeDiscussion(eq(gc), any(), any(), anyString(), any(), eq(2));
            assertEquals(1.0, meters.get(GroupTieBreakPauses.METRIC_TIEBREAKS).tag("resolution", "timeout_none").counter().count());
        }

        @Test
        @DisplayName("MODERATOR_DECIDES with the turn budget spent keeps NONE and makes no model call")
        void moderatorBudgetExhausted() throws Exception {
            config = configWith(false, OnTieBreakTimeout.MODERATOR_DECIDES);
            var gc = paused(null);
            gc.setPausedTurnCount(10);

            coordinator.skipHumanTurnOnTimeout(GC_ID, null);
            submitted.getFirst().run();

            assertEquals(DecisionType.NONE, gc.getDecision().type());
            verify(memberTurnExecutor, never()).executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(),
                    any());
            verify(service).executeDiscussion(eq(gc), any(), any(), anyString(), any(), eq(2));
        }

        @Test
        @DisplayName("MODERATOR_DECIDES honours an inherited cost ceiling when the group has none of its own")
        void moderatorInheritedCeiling() throws Exception {
            config = configWith(false, OnTieBreakTimeout.MODERATOR_DECIDES);
            var gc = paused(null);
            gc.setInheritedCostCeiling(1.0);
            gc.setTotalCost(2.0);

            coordinator.skipHumanTurnOnTimeout(GC_ID, null);
            submitted.getFirst().run();

            assertEquals(DecisionType.NONE, gc.getDecision().type());
            verify(memberTurnExecutor, never()).executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(),
                    any());
        }

        @Test
        @DisplayName("MODERATOR_DECIDES asks the moderator and charges one turn")
        void moderatorDecides() throws Exception {
            config = configWith(false, OnTieBreakTimeout.MODERATOR_DECIDES);
            var gc = paused(null);
            when(memberTurnExecutor.executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(), any()))
                    .thenReturn(new TranscriptEntry("mod", "Vote Tiebreaker", "SEO", 1, "Board vote", TranscriptEntryType.OPINION,
                            Instant.now(), null, null));

            coordinator.skipHumanTurnOnTimeout(GC_ID, null);
            submitted.getFirst().run();

            assertEquals(DecisionType.VOTE, gc.getDecision().type());
            assertEquals("SEO", gc.getDecision().winner());
            assertEquals(8, gc.getPausedTurnCount(), "the moderator's call is a turn");
        }
    }
}
