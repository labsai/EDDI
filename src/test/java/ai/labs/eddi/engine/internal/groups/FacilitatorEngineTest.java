/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.FacilitatorCheckpoint;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.FacilitatorConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.FacilitatorMove;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OptionsSource;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.internal.groups.FacilitatorEngine.CheckpointContext;
import ai.labs.eddi.engine.internal.groups.FacilitatorEngine.FacilitatorAction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FacilitatorEngine} (I12): move parse tiers, per-move validation and
 * execution, the two budgets, the caps, briefing boundedness, and the
 * degrade-to-CONTINUE guarantees.
 */
class FacilitatorEngineTest {

    private MemberTurnExecutor memberTurnExecutor;
    private IDeploymentStore deploymentStore;
    private AuditLedgerService auditLedgerService;
    private SimpleMeterRegistry meterRegistry;
    private FacilitatorEngine engine;

    private GroupConversation gc;
    private AgentGroupConfiguration config;
    private DiscussionPhase phase;
    private ProtocolConfig protocol;
    private AtomicInteger turnCounter;

    private static final int MAX_TURNS = 50;

    @BeforeEach
    void setUp() {
        memberTurnExecutor = mock(MemberTurnExecutor.class);
        deploymentStore = mock(IDeploymentStore.class);
        auditLedgerService = mock(AuditLedgerService.class);
        meterRegistry = new SimpleMeterRegistry();
        engine = new FacilitatorEngine(memberTurnExecutor, () -> deploymentStore, auditLedgerService, meterRegistry);

        gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        config = new AgentGroupConfiguration();
        config.setName("G");
        config.setMembers(List.of(new GroupMember("a1", "Alice", 1, null), new GroupMember("a2", "Bob", 2, null)));
        config.setFacilitator(facilitator(FacilitatorMove.values()));
        phase = new DiscussionPhase("Discuss", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                false, null, 3, false);
        protocol = new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                ProtocolConfig.MemberUnavailablePolicy.SKIP);
        turnCounter = new AtomicInteger(0);
    }

    private FacilitatorConfig facilitator(FacilitatorMove... moves) {
        return new FacilitatorConfig(true, "fac", List.of(moves), FacilitatorCheckpoint.EACH_REPEAT, 10, "boss@example.com");
    }

    /** Mid-phase EACH_REPEAT checkpoint with everything still open. */
    private CheckpointContext midPhaseCtx() {
        return new CheckpointContext(1, 0, true, true, false, true);
    }

    /** EACH_PHASE boundary checkpoint. */
    private CheckpointContext boundaryCtx() {
        return new CheckpointContext(1, 2, false, false, false, true);
    }

    private void stubReply(String reply) {
        try {
            when(memberTurnExecutor.executeAgentTurn(any(GroupMember.class), any(GroupConversation.class), anyString(),
                    any(ProtocolConfig.class), anyInt(), any(DiscussionPhase.class), any(), any(), any(),
                    eq(FacilitatorEngine.FACILITATOR_CONVERSATION_KEY)))
                    .thenReturn(new TranscriptEntry("fac", "Facilitator", reply, 1, "Discuss",
                            TranscriptEntryType.OPINION, Instant.now(), null, null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private FacilitatorAction checkpoint(CheckpointContext ctx) {
        return engine.checkpoint(gc, config, phase, ctx, protocol, "The question?", null, turnCounter, MAX_TURNS);
    }

    private List<TranscriptEntry> facilitationEntries() {
        return gc.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.FACILITATION).toList();
    }

    private double counterValue(String move, String outcome) {
        var counter = meterRegistry.find("eddi_group_facilitator_moves_total")
                .tags("move", move, "outcome", outcome).counter();
        return counter != null ? counter.count() : 0.0;
    }

    // =================================================================
    // Reply parsing — three tiers
    // =================================================================

    @Nested
    class Parsing {

        @Test
        void strictJson_parses() {
            var parsed = FacilitatorEngine.parseMove("{\"move\": \"END_PHASE\", \"reason\": \"converged enough\"}");

            assertNotNull(parsed);
            assertEquals(FacilitatorMove.END_PHASE, parsed.move());
            assertEquals("converged enough", parsed.reason());
        }

        @Test
        void jsonEmbeddedInProseOrFence_parses() {
            var parsed = FacilitatorEngine.parseMove(
                    "Here is my decision:\n```json\n{\"move\": \"continue\", \"args\": {}, \"reason\": \"all good\"}\n```");

            assertNotNull(parsed);
            assertEquals(FacilitatorMove.CONTINUE, parsed.move(), "lowercase move names are accepted");
        }

        @Test
        void unknownMoveName_keepsRawForTheRejectionRecord() {
            var parsed = FacilitatorEngine.parseMove("{\"move\": \"DANCE\"}");

            assertNotNull(parsed);
            assertNull(parsed.move());
            assertEquals("DANCE", parsed.rawMove());
        }

        @Test
        void proseWithoutJson_orMissingMove_isUnparseable() {
            assertNull(FacilitatorEngine.parseMove("I think we should continue."));
            assertNull(FacilitatorEngine.parseMove("{\"args\": {}}"));
            assertNull(FacilitatorEngine.parseMove(null));
            assertNull(FacilitatorEngine.parseMove("   "));
        }

        @Test
        void argsDefaultToEmptyObject_whenAbsentOrWrongType() {
            var parsed = FacilitatorEngine.parseMove("{\"move\": \"CONTINUE\", \"args\": \"not an object\"}");

            assertNotNull(parsed);
            assertTrue(parsed.args().isObject());
        }
    }

    // =================================================================
    // Gates before the call
    // =================================================================

    @Test
    void disabledOrAbsentFacilitator_neverCallsTheModel() throws Exception {
        config.setFacilitator(null);
        assertEquals(FacilitatorAction.Kind.NONE, checkpoint(midPhaseCtx()).kind());

        config.setFacilitator(new FacilitatorConfig(false, "fac", null, null, 0, null));
        assertEquals(FacilitatorAction.Kind.NONE, checkpoint(midPhaseCtx()).kind());

        verify(memberTurnExecutor, never()).executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(),
                any(), any(), any(), anyString());
        assertEquals(0, turnCounter.get(), "no call, no turn spent");
    }

    @Test
    void turnBudgetExhausted_skipsTheCheckpointEntirely() throws Exception {
        turnCounter.set(MAX_TURNS);

        assertEquals(FacilitatorAction.Kind.NONE, checkpoint(midPhaseCtx()).kind());

        verify(memberTurnExecutor, never()).executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(),
                any(), any(), any(), anyString());
        assertEquals(MAX_TURNS, turnCounter.get());
    }

    @Test
    void costCeilingBlown_skipsTheCheckpointEntirely() throws Exception {
        protocol = new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                ProtocolConfig.MemberUnavailablePolicy.SKIP, 100, 5.0, ProtocolConfig.CostPolicy.SYNTHESIZE_NOW);
        gc.setTotalCost(6.0);

        assertEquals(FacilitatorAction.Kind.NONE, checkpoint(midPhaseCtx()).kind());

        verify(memberTurnExecutor, never()).executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(),
                any(), any(), any(), anyString());
    }

    @Test
    void facilitatorCallFailure_degradesToContinue_withoutATranscriptEntry() throws Exception {
        when(memberTurnExecutor.executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any(),
                any(), anyString())).thenThrow(new RuntimeException("LLM down"));

        assertEquals(FacilitatorAction.Kind.NONE, checkpoint(midPhaseCtx()).kind());

        assertTrue(facilitationEntries().isEmpty(), "the model never replied — there is no attempt to audit");
        assertEquals(1, turnCounter.get(), "the attempted call still spent its turn slot");
    }

    // =================================================================
    // CONTINUE, rejections, caps
    // =================================================================

    @Test
    void continue_isSilent_spendsNoMoveBudget() {
        stubReply("{\"move\": \"CONTINUE\", \"reason\": \"discussion is healthy\"}");

        assertEquals(FacilitatorAction.Kind.NONE, checkpoint(midPhaseCtx()).kind());

        assertTrue(facilitationEntries().isEmpty(), "CONTINUE is the ambient no-op — silence is its record");
        assertEquals(0, gc.getFacilitatorMoveCount());
        assertEquals(1, turnCounter.get(), "the consult itself is a real turn");
        assertEquals(1.0, counterValue("CONTINUE", "proposed"));
    }

    @Test
    void unparseableReply_recordsARejectedAttempt() {
        stubReply("We should probably wrap this up soon, folks!");

        assertEquals(FacilitatorAction.Kind.NONE, checkpoint(midPhaseCtx()).kind());

        assertEquals(1, facilitationEntries().size());
        assertTrue(facilitationEntries().get(0).content().contains("rejected"), facilitationEntries().get(0).content());
        assertEquals(0, gc.getFacilitatorMoveCount(), "a rejected attempt never consumes the budget");
    }

    @Test
    void unknownMove_recordsTheRawNameInTheRejection() {
        stubReply("{\"move\": \"DANCE\", \"reason\": \"chaos\"}");

        checkpoint(midPhaseCtx());

        assertEquals(1, facilitationEntries().size());
        assertTrue(facilitationEntries().get(0).content().contains("DANCE"));
    }

    @Test
    @DisplayName("mutation-check: a move outside allowedMoves is rejected, never executed")
    void disallowedMove_isRejected_neverExecuted() {
        config.setFacilitator(new FacilitatorConfig(true, "fac", List.of(FacilitatorMove.CONTINUE),
                FacilitatorCheckpoint.EACH_REPEAT, 10, null));
        stubReply("{\"move\": \"END_PHASE\", \"reason\": \"I want to end it\"}");

        var action = checkpoint(midPhaseCtx());

        assertEquals(FacilitatorAction.Kind.NONE, action.kind(), "a disallowed move must degrade to CONTINUE");
        assertEquals(1, facilitationEntries().size());
        assertTrue(facilitationEntries().get(0).content().contains("allowedMoves"));
        assertEquals(0, gc.getFacilitatorMoveCount());
        assertEquals(1.0, counterValue("END_PHASE", "rejected"));
        assertEquals(0.0, counterValue("END_PHASE", "executed"));
    }

    @Test
    void moveBudgetExhausted_rejectsEveryNonContinueMove() {
        gc.setFacilitatorMoveCount(10);
        stubReply("{\"move\": \"END_PHASE\"}");

        assertEquals(FacilitatorAction.Kind.NONE, checkpoint(midPhaseCtx()).kind());

        assertEquals(1, facilitationEntries().size());
        assertTrue(facilitationEntries().get(0).content().contains("budget"));
        assertEquals(10, gc.getFacilitatorMoveCount(), "rejection does not increment");
    }

    // =================================================================
    // END_PHASE / EXTEND_PHASE
    // =================================================================

    @Nested
    class EndAndExtend {

        @Test
        void endPhase_midPhase_executes() {
            stubReply("{\"move\": \"END_PHASE\", \"reason\": \"positions have stabilized\"}");

            var action = checkpoint(midPhaseCtx());

            assertEquals(FacilitatorAction.Kind.END_PHASE, action.kind());
            assertEquals(1, gc.getFacilitatorMoveCount());
            assertEquals(1, facilitationEntries().size());
            assertTrue(facilitationEntries().get(0).content().contains("ended phase"));
            assertEquals(1.0, counterValue("END_PHASE", "executed"));
        }

        @Test
        void endPhase_atPhaseBoundary_isInvalidInContext() {
            stubReply("{\"move\": \"END_PHASE\"}");

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(boundaryCtx()).kind());

            assertTrue(facilitationEntries().get(0).content().contains("mid-phase"));
        }

        @Test
        void endPhase_afterConvergenceExit_neverOverrulesTheJudge() {
            stubReply("{\"move\": \"END_PHASE\"}");
            var ctx = new CheckpointContext(1, 0, true, true, true, true);

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(ctx).kind());
        }

        @Test
        void extendPhase_executes_andCountsPerPhase() {
            stubReply("{\"move\": \"EXTEND_PHASE\", \"reason\": \"one more round\"}");

            var action = checkpoint(midPhaseCtx());

            assertEquals(FacilitatorAction.Kind.EXTEND_PHASE, action.kind());
            assertEquals(1, gc.getFacilitatorExtensions().get("1"));
        }

        @Test
        void extendPhase_capsAtTwoPerPhase() {
            stubReply("{\"move\": \"EXTEND_PHASE\"}");
            gc.recordFacilitatorExtension(1);
            gc.recordFacilitatorExtension(1);

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(midPhaseCtx()).kind());

            assertEquals(2, gc.getFacilitatorExtensions().get("1"), "the cap held");
            assertTrue(facilitationEntries().get(0).content().contains("extended"));
        }

        @Test
        void extendPhase_afterConvergenceExit_isRejected() {
            stubReply("{\"move\": \"EXTEND_PHASE\"}");
            var ctx = new CheckpointContext(1, 2, true, false, true, true);

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(ctx).kind());
        }
    }

    @Test
    void rejectedUnknownMove_boundsTheMetricTag() {
        // Review finding: the raw LLM-authored move string became a metric tag
        // value — unbounded Prometheus label cardinality, one new time series
        // per hallucinated name for the JVM's lifetime.
        stubReply("{\"move\": \"WRAP_UP_2026-08-08T12:00:01\", \"reason\": \"done\"}");

        assertEquals(FacilitatorAction.Kind.NONE, checkpoint(midPhaseCtx()).kind());

        var rejected = meterRegistry.find("eddi_group_facilitator_moves_total")
                .tag("outcome", "rejected").counters();
        assertEquals(1, rejected.size());
        assertEquals("UNKNOWN", rejected.iterator().next().getId().getTag("move"),
                "the tag is bounded to enum names + UNKNOWN — never the raw LLM string");
    }

    // =================================================================
    // CALL_VOTE
    // =================================================================

    @Nested
    class CallVote {

        @Test
        void callVote_buildsAStructurallyIndependentVotePhase() {
            stubReply("{\"move\": \"CALL_VOTE\", \"args\": {\"options\": [\"Ship it\", \"Hold it\"]}, "
                    + "\"reason\": \"the team is split\"}");

            var action = checkpoint(boundaryCtx());

            assertEquals(FacilitatorAction.Kind.INSERT_VOTE, action.kind());
            var votePhase = action.insertPhase();
            assertEquals(PhaseType.VOTE, votePhase.type());
            assertEquals(TurnOrder.PARALLEL, votePhase.turnOrder(), "I14's enforced shape, by construction");
            assertEquals(ContextScope.NONE, votePhase.contextScope());
            assertEquals(1, votePhase.repeats());
            assertEquals(OptionsSource.EXPLICIT, votePhase.voteConfig().optionsSource());
            assertEquals(List.of("Ship it", "Hold it"), votePhase.voteConfig().options());
            assertEquals(TiePolicy.NO_DECISION, votePhase.voteConfig().tiePolicy());
            assertEquals(1, gc.getFacilitatorMoveCount());
        }

        @Test
        void callVote_afterAPhaseEndedBySignal_isRejected() {
            // Review finding: unlike END_PHASE/EXTEND_PHASE, CALL_VOTE had no
            // phaseEndedBySignal guard — a facilitator vote could be appended to
            // a phase that just ended by convergence or unanimous agreement.
            stubReply("{\"move\": \"CALL_VOTE\", \"args\": {\"options\": [\"Ship it\", \"Hold it\"]}}");

            var action = checkpoint(new CheckpointContext(1, 0, true, true, true, true));

            assertEquals(FacilitatorAction.Kind.NONE, action.kind());
            assertTrue(facilitationEntries().get(0).content().contains("rejected"));
            assertEquals(0, gc.getFacilitatorMoveCount(), "a rejected move never consumes budget");
        }

        @Test
        void callVote_whenADecisionAlreadyExists_isRejected() {
            // Review finding: the inserted vote's tally REPLACES gc.decision — a
            // facilitator vote after a signed AGREEMENT or a debate VERDICT would
            // destroy the record, break skipIf=AGREEMENT_REACHED, and un-render
            // the verdict into raw judgment JSON.
            gc.setDecision(new GroupConversation.DecisionRecord(GroupConversation.DecisionType.AGREEMENT,
                    "Deal", null, null, List.of(), "unanimous-acceptance", "Bargain", null));
            stubReply("{\"move\": \"CALL_VOTE\", \"args\": {\"options\": [\"Ship it\", \"Hold it\"]}}");

            var action = checkpoint(boundaryCtx());

            assertEquals(FacilitatorAction.Kind.NONE, action.kind());
            assertTrue(facilitationEntries().get(0).content().contains("decision record"),
                    facilitationEntries().get(0).content());
            assertEquals(GroupConversation.DecisionType.AGREEMENT, gc.getDecision().type(), "the record survives");
        }

        @Test
        void callVote_tooFewOrDuplicateOptions_isRejected() {
            stubReply("{\"move\": \"CALL_VOTE\", \"args\": {\"options\": [\"Only one\", \"Only one\"]}}");

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(boundaryCtx()).kind());

            assertTrue(facilitationEntries().get(0).content().contains("options"));
        }

        @Test
        void callVote_tooManyOptions_isRejected() {
            var options = new StringBuilder();
            for (int i = 0; i < 11; i++) {
                options.append(i > 0 ? ", " : "").append("\"Option ").append(i).append('"');
            }
            stubReply("{\"move\": \"CALL_VOTE\", \"args\": {\"options\": [" + options + "]}}");

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(boundaryCtx()).kind());
        }

        @Test
        void callVote_overlongOptionText_isTruncatedNotRejected() {
            String longOption = "x".repeat(600);
            stubReply("{\"move\": \"CALL_VOTE\", \"args\": {\"options\": [\"" + longOption + "\", \"short\"]}}");

            var action = checkpoint(boundaryCtx());

            assertEquals(FacilitatorAction.Kind.INSERT_VOTE, action.kind());
            assertEquals(FacilitatorEngine.MAX_VOTE_OPTION_LENGTH,
                    action.insertPhase().voteConfig().options().get(0).length());
        }
    }

    // =================================================================
    // RECRUIT
    // =================================================================

    @Nested
    class Recruit {

        private void stubDeployed(String agentId) {
            try {
                var info = new DeploymentInfo();
                info.setAgentId(agentId);
                when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                        .thenReturn(List.of(info));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Test
        void recruit_addsTheAgent_appliedInsideTheEngine() {
            stubDeployed("specialist");
            stubReply("{\"move\": \"RECRUIT\", \"args\": {\"agentId\": \"specialist\"}, \"reason\": \"need security depth\"}");

            var action = checkpoint(boundaryCtx());

            assertEquals(FacilitatorAction.Kind.NONE, action.kind(), "recruit mutates gc directly — no loop action");
            assertTrue(gc.getRecruitedAgentIds().contains("specialist"));
            assertTrue(gc.getDynamicMembers().stream().anyMatch(m -> "specialist".equals(m.agentId())));
            assertEquals(1, gc.getFacilitatorMoveCount());
            assertEquals("specialist", facilitationEntries().get(0).targetAgentId());
        }

        @Test
        void recruit_missingAgentId_isRejected() {
            stubReply("{\"move\": \"RECRUIT\", \"args\": {}}");

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(boundaryCtx()).kind());
            assertTrue(facilitationEntries().get(0).content().contains("agentId"));
        }

        @Test
        void recruit_configuredMember_isRejected() {
            stubDeployed("a1");
            stubReply("{\"move\": \"RECRUIT\", \"args\": {\"agentId\": \"a1\"}}");

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(boundaryCtx()).kind());
            assertTrue(gc.getRecruitedAgentIds().isEmpty());
        }

        @Test
        void recruit_undeployedAgent_isRejected() {
            stubReply("{\"move\": \"RECRUIT\", \"args\": {\"agentId\": \"ghost\"}}");

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(boundaryCtx()).kind());
            assertTrue(facilitationEntries().get(0).content().contains("deployed"));
        }

        @Test
        void recruit_capReached_isRejected() {
            stubDeployed("specialist");
            var dynamicConfig = new AgentGroupConfiguration.DynamicAgentConfig();
            dynamicConfig.setMaxRecruitedAgentsPerDiscussion(1);
            config.setDynamicAgents(dynamicConfig);
            gc.getRecruitedAgentIds().add("earlier-recruit");
            stubReply("{\"move\": \"RECRUIT\", \"args\": {\"agentId\": \"specialist\"}}");

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(boundaryCtx()).kind());
            assertFalse(gc.getRecruitedAgentIds().contains("specialist"));
            assertTrue(facilitationEntries().get(0).content().contains("limit"));
        }
    }

    // =================================================================
    // ESCALATE_HUMAN
    // =================================================================

    @Nested
    class Escalate {

        @Test
        void escalate_returnsThePrincipalAndQuestion() {
            stubReply("{\"move\": \"ESCALATE_HUMAN\", \"args\": {\"question\": \"Which vendor do we commit to?\"}, "
                    + "\"reason\": \"commercial decision\"}");

            var action = checkpoint(boundaryCtx());

            assertEquals(FacilitatorAction.Kind.ESCALATE, action.kind());
            assertEquals("boss@example.com", action.escalation().principalId());
            assertEquals("Which vendor do we commit to?", action.escalation().question());
            assertEquals(1, gc.getFacilitatorMoveCount());
        }

        @Test
        void escalate_withoutConfiguredPrincipal_isRejected() {
            config.setFacilitator(new FacilitatorConfig(true, "fac", List.of(FacilitatorMove.ESCALATE_HUMAN),
                    FacilitatorCheckpoint.EACH_PHASE, 10, null));
            stubReply("{\"move\": \"ESCALATE_HUMAN\", \"args\": {\"question\": \"Q?\"}}");

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(boundaryCtx()).kind());
            assertTrue(facilitationEntries().get(0).content().contains("escalateTo"));
        }

        @Test
        void escalate_withNoResumeTarget_isRejected() {
            stubReply("{\"move\": \"ESCALATE_HUMAN\", \"args\": {\"question\": \"Q?\"}}");
            var lastBoundary = new CheckpointContext(2, 0, false, false, false, false);

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(lastBoundary).kind());
            assertTrue(facilitationEntries().get(0).content().contains("resume"));
        }

        @Test
        void escalate_fallsBackToTheReason_whenArgsHaveNoQuestion() {
            stubReply("{\"move\": \"ESCALATE_HUMAN\", \"reason\": \"Need a human call on budget\"}");

            var action = checkpoint(boundaryCtx());

            assertEquals(FacilitatorAction.Kind.ESCALATE, action.kind());
            assertEquals("Need a human call on budget", action.escalation().question());
        }

        @Test
        void escalate_blankQuestionAndReason_isRejected() {
            stubReply("{\"move\": \"ESCALATE_HUMAN\", \"args\": {\"question\": \"  \"}}");

            assertEquals(FacilitatorAction.Kind.NONE, checkpoint(boundaryCtx()).kind());
        }

        @Test
        void escalate_overlongQuestion_isTruncated() {
            stubReply("{\"move\": \"ESCALATE_HUMAN\", \"args\": {\"question\": \"" + "q".repeat(3000) + "\"}}");

            var action = checkpoint(boundaryCtx());

            assertEquals(FacilitatorEngine.MAX_ESCALATION_QUESTION_LENGTH, action.escalation().question().length());
        }
    }

    // =================================================================
    // Audit + briefing
    // =================================================================

    @Test
    void executedMove_submitsAnAuditEntry() {
        when(auditLedgerService.isEnabled()).thenReturn(true);
        stubReply("{\"move\": \"END_PHASE\", \"reason\": \"done\"}");

        checkpoint(midPhaseCtx());

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLedgerService).submit(captor.capture());
        assertEquals("group.facilitator", captor.getValue().taskId());
        assertEquals("group", captor.getValue().taskType());
        assertEquals("END_PHASE", captor.getValue().output().get("move"));
        assertEquals("EXECUTED", captor.getValue().output().get("outcome"));
    }

    @Test
    @DisplayName("briefing is bounded: derived counts plus capped excerpts, never the transcript")
    void briefing_neverContainsTheFullTranscript() {
        String hugeOpinion = "A".repeat(50_000);
        gc.getTranscript().add(new TranscriptEntry("a1", "Alice", hugeOpinion, 1, "Discuss",
                TranscriptEntryType.OPINION, Instant.now(), null, null));
        gc.getTranscript().add(new TranscriptEntry("a2", "Bob", "B".repeat(50_000), 1, "Discuss",
                TranscriptEntryType.OPINION, Instant.now(), null, null));

        String briefing = engine.buildBriefing(gc, config, config.getFacilitator(), phase, midPhaseCtx(),
                protocol, "Q".repeat(10_000));

        assertTrue(briefing.length() < 5_000, "briefing must stay flat as the transcript grows (was "
                + briefing.length() + " chars)");
        assertFalse(briefing.contains(hugeOpinion), "no full entry content, only capped excerpts");
        assertTrue(briefing.contains("OPINION=2"), "entry counts by type stand in for the content:\n" + briefing);
        assertTrue(briefing.contains("CALL_VOTE"), "the allowed moves and their arg contracts are the instruction set");
    }

    @Test
    void briefing_listsOnlyAllowedMoves() {
        config.setFacilitator(new FacilitatorConfig(true, "fac", List.of(FacilitatorMove.CONTINUE),
                FacilitatorCheckpoint.EACH_PHASE, 10, null));

        String briefing = engine.buildBriefing(gc, config, config.getFacilitator(), phase, boundaryCtx(),
                protocol, "Q?");

        assertTrue(briefing.contains("CONTINUE"));
        assertFalse(briefing.contains("RECRUIT"), "unallowed moves are not offered — an absent option cannot be argued with");
    }

    @Test
    void constants_pinTheCaps() {
        assertEquals(2, FacilitatorEngine.MAX_EXTENSIONS_PER_PHASE);
        assertEquals(2, FacilitatorEngine.MIN_VOTE_OPTIONS);
        assertEquals(10, FacilitatorEngine.MAX_VOTE_OPTIONS);
    }
}
