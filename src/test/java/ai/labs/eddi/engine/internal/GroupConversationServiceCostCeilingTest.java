/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.ConversationStepData;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.SimpleConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end tests for I1's cost ceiling as seen by
 * {@code GroupConversationService.executeDiscussion}'s own phase loop — the
 * policy branches ({@code SYNTHESIZE_NOW} skip-ahead, {@code ABORT}) and nested
 * budget derivation. The gate itself is unit-tested in
 * {@code GroupCostCeilingTest}, and the per-executor wiring in
 * {@code PhaseExecutionEngineTest}; this class covers what only the full loop
 * can show.
 *
 * @author tests
 */
class GroupConversationServiceCostCeilingTest {

    @Mock
    private IAgentGroupStore groupStore;
    @Mock
    private IGroupConversationStore conversationStore;
    @Mock
    private IConversationService conversationService;
    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private AgentSigningService agentSigningService;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private NonceCacheService nonceCacheService;
    @Mock
    private IScheduleStore scheduleStore;

    private GroupConversationService service;

    private static final String GROUP_ID = "group-cost";
    private static final String USER_ID = "user-cost";
    private static final String AGENT_A = "agent-a";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                scheduleStore, nonceCacheService, null, new CallerIdentityContext(null, null), "default", 3);
    }

    private AgentGroupConfiguration buildConfig(List<DiscussionPhase> phases, ProtocolConfig protocol) {
        var config = new AgentGroupConfiguration();
        config.setName("Cost Test Group");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setPhases(phases);
        config.setMembers(List.of(new GroupMember(AGENT_A, "Agent A", 1, null)));
        config.setModeratorAgentId("mod-agent");
        config.setProtocol(protocol);
        return config;
    }

    private IResourceStore.IResourceId mockResourceId() {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }

    private DiscussionPhase phase(String name, PhaseType type) {
        return new DiscussionPhase(name, type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
    }

    private ProtocolConfig protocol(Double ceiling, CostPolicy policy) {
        return new ProtocolConfig(60, MemberFailurePolicy.SKIP, 2, MemberUnavailablePolicy.SKIP, 50, ceiling, policy);
    }

    /**
     * Every member turn reports {@code costPerTurn} as its conversation's
     * cumulative tracked cost, so the group's total climbs turn by turn exactly the
     * way {@code GroupCostLedger} accumulates it in production.
     */
    private void stubAgentSayCosting(double costPerTurn) throws Exception {
        // Without a deployed agent every member turn short-circuits to a SKIPPED
        // "Agent not deployed" entry and never reaches say() — so no cost would ever
        // accumulate and the ceiling could never trip.
        doReturn(mock(ai.labs.eddi.engine.runtime.IAgent.class)).when(agentFactory).getLatestReadyAgent(any(), anyString());
        doReturn(new IConversationService.ConversationResult("member-conv", null))
                .when(conversationService).startConversation(any(), any(), any(), any());
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
            if (handler != null) {
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("output", List.of("Response"));
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
                var step = new SimpleConversationStep();
                // AUDIT_COST is cumulative per conversation, so a member's second turn
                // reports the running total, not that turn's delta.
                step.getConversationStep().add(new ConversationStepData(
                        MemoryKeys.AUDIT_COST, costPerTurn * counter.incrementAndGet(), null, null));
                snapshot.getConversationSteps().add(step);
                handler.onComplete(snapshot);
            }
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("SYNTHESIZE_NOW: non-synthesis phases after the ceiling are skipped, but synthesis still runs")
    void synthesizeNow_skipsRemainingWorkPhases_butRunsSynthesis() throws Exception {
        // TWO non-synthesis phases after the one that trips the ceiling: with only
        // one, the loop's skip-ahead guard and the per-phase gate are
        // indistinguishable (both yield a single entry), so the guard could be
        // deleted without any test noticing.
        var phases = List.of(
                phase("Opinion", PhaseType.OPINION),
                phase("Critique", PhaseType.CRITIQUE),
                phase("Review", PhaseType.REVISION),
                phase("Summary", PhaseType.SYNTHESIS));
        var config = buildConfig(phases, protocol(0.5, CostPolicy.SYNTHESIZE_NOW));
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-synth").when(conversationStore).create(any());
        // The first turn alone blows the $0.50 ceiling.
        stubAgentSayCosting(2.0);

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        var phasesRun = gc.getTranscript().stream()
                .filter(e -> e.type() != TranscriptEntryType.SKIPPED)
                .map(e -> e.phaseName()).distinct().toList();
        assertTrue(phasesRun.contains("Opinion"), "the first phase runs before any cost exists to trip the ceiling");
        assertFalse(phasesRun.contains("Critique"), "the ceiling must skip the remaining non-synthesis phases");
        assertFalse(phasesRun.contains("Review"), "the ceiling must skip the remaining non-synthesis phases");
        assertTrue(phasesRun.contains("Summary"), "SYNTHESIZE_NOW must still reach the synthesis phase — that is the whole point");
        assertNotEquals(GroupConversationState.FAILED, gc.getState(), "SYNTHESIZE_NOW concludes normally, it does not fail");

        // The ceiling is reported ONCE, not once per remaining phase. Without the
        // loop's skip-ahead guard every subsequent non-synthesis phase would re-enter
        // its executor, re-trip the same gate, and append another identical entry
        // (and increment the ceiling-hit metric again) — noise that would make the
        // transcript and the metric both misreport a single overspend as several.
        long ceilingEntries = gc.getTranscript().stream()
                .filter(e -> e.errorReason() != null && e.errorReason().contains("Cost ceiling reached")).count();
        assertEquals(1, ceilingEntries, "one overspend must produce exactly one ceiling entry");
    }

    @Test
    @DisplayName("ABORT: the discussion fails immediately, remaining phases including synthesis never run")
    void abort_failsDiscussionImmediately() throws Exception {
        var phases = List.of(
                phase("Opinion", PhaseType.OPINION),
                phase("Critique", PhaseType.CRITIQUE),
                phase("Summary", PhaseType.SYNTHESIS));
        var config = buildConfig(phases, protocol(0.5, CostPolicy.ABORT));
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-abort").when(conversationStore).create(any());
        stubAgentSayCosting(2.0);

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        assertEquals(GroupConversationState.FAILED, gc.getState());
        var phasesRun = gc.getTranscript().stream()
                .filter(e -> e.type() != TranscriptEntryType.SKIPPED)
                .map(e -> e.phaseName()).distinct().toList();
        assertFalse(phasesRun.contains("Summary"), "ABORT must not run synthesis — it fails outright");
    }

    /**
     * Review finding (Copilot, PR #636): summarization is optional spend and must
     * be ceiling-gated like the convergence judge and the dissent round. The
     * scenario is built so the FIRST phase completes without tripping the per-turn
     * gate (member A passes at $0, member B at $2 under the $3 ceiling) while its
     * cumulative spend ends at $6 — so the SECOND Opinion phase's boundary is
     * reached with the ceiling already blown and two summarizable entries on the
     * transcript (cap 1 → a summarizer call is otherwise guaranteed).
     * Mutation-checked: removing the guard fails this test.
     */
    @Test
    @DisplayName("a blown ceiling also gates the I9 window summarizer — optional spend stops with the budget")
    void blownCeiling_skipsWindowSummarization() throws Exception {
        var phases = List.of(
                phase("Opinion 1", PhaseType.OPINION),
                phase("Opinion 2", PhaseType.OPINION),
                phase("Summary", PhaseType.SYNTHESIS));
        var config = buildConfig(phases, protocol(3.0, CostPolicy.SYNTHESIZE_NOW));
        config.setMembers(List.of(new GroupMember(AGENT_A, "Agent A", 1, null), new GroupMember("agent-b", "Agent B", 2, null)));
        config.setContextWindow(new AgentGroupConfiguration.ContextWindowConfig(true, 1, true, "openai", "gpt-4o-mini", null, null));
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-window").when(conversationStore).create(any());
        stubAgentSayCosting(2.0);
        var summarizer = mock(SummarizationService.class);
        service.summarizationService = summarizer;

        service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        verifyNoInteractions(summarizer);
    }

    @Test
    @DisplayName("no ceiling configured: every phase runs, no SKIPPED cost entry appears")
    void noCeiling_everyPhaseRuns() throws Exception {
        var phases = List.of(phase("Opinion", PhaseType.OPINION), phase("Summary", PhaseType.SYNTHESIS));
        var config = buildConfig(phases, protocol(null, null));
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-nolimit").when(conversationStore).create(any());
        stubAgentSayCosting(99.0);

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        assertTrue(gc.getTranscript().stream().noneMatch(
                e -> e.errorReason() != null && e.errorReason().contains("Cost ceiling")),
                "an unlimited discussion must never gain a cost-ceiling entry");
        assertNotEquals(GroupConversationState.FAILED, gc.getState());
    }

    @Test
    @DisplayName("attribution: a member's tracked cost reaches the group's ledger")
    void attribution_memberCostIsAttributedToThatMember() throws Exception {
        var phases = List.of(phase("Opinion", PhaseType.OPINION));
        var config = buildConfig(phases, protocol(null, null));
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-attr").when(conversationStore).create(any());
        stubAgentSayCosting(0.25);

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        // Attributed to the member that incurred it, by agentId — not merely summed.
        // (Asserting totalCost == sum(memberCosts) would restate GroupCostLedger's
        // own invariant and could never fail.)
        assertEquals(0.25, gc.getMemberCosts().get(AGENT_A), 1e-9);
        assertEquals(0.25, gc.getTotalCost(), 1e-9);
    }

    @Test
    @DisplayName("nested budget wiring: the inherited ceiling reaches the child's own gate")
    void nestedBudget_inheritedCeilingIsApplied() throws Exception {
        var phases = List.of(phase("Opinion", PhaseType.OPINION));
        var config = buildConfig(phases, protocol(null, null));
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-nested").when(conversationStore).create(any());
        stubAgentSayCosting(0.25);

        // A child dispatched with a fully-consumed inherited budget must run nothing,
        // even though its own config has no ceiling at all — proving the inherited
        // value actually reaches the gate rather than being dropped in transit.
        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 1, null, null, 0.0);

        assertEquals(0.0, gc.getTotalCost(), 1e-9, "no paid turn may run under a fully-consumed inherited budget");
        assertTrue(gc.getTranscript().stream().anyMatch(
                e -> e.errorReason() != null && e.errorReason().contains("Cost ceiling reached")),
                "the child must record why it did nothing");
    }

    // =================================================================
    // Nested budget derivation — effectiveCostCeiling
    // =================================================================

    @Test
    @DisplayName("nested budget: the tighter of the child's own ceiling and the parent's remaining wins")
    void effectiveCostCeiling_picksTheTighterBound() {
        assertNull(GroupConversationService.effectiveCostCeiling(null, null),
                "unlimited only when BOTH are unlimited");
        assertEquals(5.0, GroupConversationService.effectiveCostCeiling(5.0, null),
                "an unlimited parent leaves the child's own ceiling intact");
        assertEquals(3.0, GroupConversationService.effectiveCostCeiling(null, 3.0),
                "a child with no ceiling of its own still inherits the parent's remaining budget");
        assertEquals(2.0, GroupConversationService.effectiveCostCeiling(2.0, 7.0),
                "the child's own tighter ceiling wins");
        assertEquals(1.0, GroupConversationService.effectiveCostCeiling(6.0, 1.0),
                "the parent's smaller remaining budget wins — a nested group cannot outspend its parent's cap");
    }
}
