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
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.groups.budget.InMemoryTeamBudgetStore;
import ai.labs.eddi.engine.groups.budget.TeamBudgetAudit;
import ai.labs.eddi.engine.groups.budget.TeamBudgetRefusedException;
import ai.labs.eddi.engine.groups.budget.TeamBudgetService;
import ai.labs.eddi.engine.groups.budget.TeamSpendMeter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F6 end to end through {@code GroupConversationService}: the admission gate on
 * a new root discussion, the team ceiling inside the phase loop, and what gets
 * charged to the team.
 */
class GroupConversationServiceTeamBudgetTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);
    private static final String MONTH = "2026-11";
    private static final String GROUP_ID = "group-team";
    private static final String USER_ID = "user-1";
    private static final String AGENT_A = "agent-a";

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
    @Mock
    private TenantQuotaService tenant;

    private final InMemoryTeamBudgetStore budgetStore = new InMemoryTeamBudgetStore();
    private GroupConversationService service;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        var registry = new SimpleMeterRegistry();
        service = new GroupConversationService(groupStore, conversationStore, conversationService, agentFactory, templatingEngine, jsonSerialization,
                registry, agentSigningService, agentStore, scheduleStore, nonceCacheService, null, new CallerIdentityContext(null, null), "default",
                3);
        when(tenant.getDefaultTenantId()).thenReturn("default");
        when(tenant.remainingCostUsd(anyString())).thenReturn(OptionalDouble.empty());
        var budgets = new TeamBudgetService(budgetStore, groupStore, tenant, registry, mock(TeamBudgetAudit.class), CLOCK);
        service.teamBudgetService = budgets;
        service.teamSpendMeter = new TeamSpendMeter(budgets, registry);
        doReturn(new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        }).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn("gc-1").when(conversationStore).create(any());
    }

    private AgentGroupConfiguration config(Double teamCap, CostPolicy onExhausted, ProtocolConfig protocol) throws Exception {
        var config = new AgentGroupConfiguration();
        config.setName("Content Studio");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setPhases(List.of(phase("Opinion", PhaseType.OPINION), phase("Critique", PhaseType.CRITIQUE), phase("Review", PhaseType.REVISION),
                phase("Summary", PhaseType.SYNTHESIS)));
        config.setMembers(List.of(new GroupMember(AGENT_A, "Agent A", 1, null)));
        config.setModeratorAgentId("mod-agent");
        config.setProtocol(protocol);
        if (teamCap != null) {
            config.setTeamBudget(new TeamBudgetConfig(teamCap, onExhausted, null));
        }
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        return config;
    }

    private static DiscussionPhase phase(String name, PhaseType type) {
        return new DiscussionPhase(name, type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
    }

    private static ProtocolConfig protocol(Double ceiling, CostPolicy policy) {
        return new ProtocolConfig(60, MemberFailurePolicy.SKIP, 2, MemberUnavailablePolicy.SKIP, 50, ceiling, policy);
    }

    private void stubAgentSayCosting(double costPerTurn) throws Exception {
        doReturn(mock(IAgent.class)).when(agentFactory).getLatestReadyAgent(any(), anyString());
        doReturn(new IConversationService.ConversationResult("member-conv", null)).when(conversationService).startConversation(any(), any(), any(),
                any());
        var counter = new AtomicInteger();
        doAnswer(inv -> {
            IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
            if (handler != null) {
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("output", List.of("Response"));
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
                snapshot.setConversationCost(costPerTurn * counter.incrementAndGet());
                handler.onComplete(snapshot);
            }
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    private double teamSpent() {
        return budgetStore.read(GROUP_ID, MONTH).map(m -> m.spentUsd()).orElse(0.0);
    }

    @Test
    @DisplayName("a team cap reached mid-run skips the remaining work but still synthesizes, and the spend is charged to the team")
    void teamCapMidRun_synthesizesAndCharges() throws Exception {
        config(1.0, CostPolicy.SYNTHESIZE_NOW, protocol(null, null));
        stubAgentSayCosting(2.0);

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        var phasesRun = gc.getTranscript().stream().filter(e -> e.type() != TranscriptEntryType.SKIPPED).map(e -> e.phaseName()).distinct().toList();
        assertTrue(phasesRun.contains("Opinion"));
        assertFalse(phasesRun.contains("Critique"), "the team cap must skip the remaining work phases");
        assertTrue(phasesRun.contains("Summary"), "SYNTHESIZE_NOW still delivers its synthesis");
        assertNotEquals(GroupConversationState.FAILED, gc.getState());
        assertTrue(gc.getTranscript().stream().anyMatch(e -> e.errorReason() != null && e.errorReason().startsWith("Team budget reached (2026-11)")),
                "the transcript names the team budget, not the group ceiling");
        assertEquals(gc.getTotalCost(), teamSpent(), 1e-9, "everything the run spent is charged to the team");
        assertTrue(gc.getTotalCost() > 0.0);
    }

    @Test
    @DisplayName("onExhausted ABORT on the team budget fails the run even though the protocol says SYNTHESIZE_NOW")
    void teamPolicyOverridesTheProtocolPolicyWhenTheTeamCapBinds() throws Exception {
        config(1.0, CostPolicy.ABORT, protocol(null, CostPolicy.SYNTHESIZE_NOW));
        stubAgentSayCosting(2.0);

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        assertEquals(GroupConversationState.FAILED, gc.getState());
    }

    @Test
    @DisplayName("the group's own tighter ceiling still binds, with its own message")
    void groupCeilingTighterThanTheTeamKeepsItsMessage() throws Exception {
        config(100.0, CostPolicy.ABORT, protocol(0.5, CostPolicy.SYNTHESIZE_NOW));
        stubAgentSayCosting(2.0);

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        assertTrue(gc.getTranscript().stream().anyMatch(e -> e.errorReason() != null && e.errorReason().startsWith("Cost ceiling reached")));
        assertNotEquals(GroupConversationState.FAILED, gc.getState(), "the protocol's SYNTHESIZE_NOW applies, not the team's ABORT");
    }

    @Test
    @DisplayName("an exhausted team is refused before a conversation is created")
    void exhaustedTeam_isRefusedBeforeAnythingIsCreated() throws Exception {
        config(10.0, null, protocol(null, null));
        budgetStore.addSpend(GROUP_ID, MONTH, 10.0, Instant.parse("2027-12-01T00:00:00Z"));

        var e = assertThrows(TeamBudgetRefusedException.class, () -> service.discuss(GROUP_ID, "Q?", USER_ID, 0));

        assertEquals("exhausted", e.reason());
        verify(conversationStore, never()).create(any());
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("startAndDiscussAsync is refused the same way")
    void exhaustedTeam_asyncStartIsRefused() throws Exception {
        config(10.0, null, protocol(null, null));
        budgetStore.addSpend(GROUP_ID, MONTH, 12.0, Instant.parse("2027-12-01T00:00:00Z"));

        assertThrows(TeamBudgetRefusedException.class, () -> service.startAndDiscussAsync(GROUP_ID, "Q?", USER_ID, null));
        verify(conversationStore, never()).create(any());
    }

    @Test
    @DisplayName("only a root discussion is gated: a nested child of an exhausted team still starts")
    void nestedChildIsNeverGated() throws Exception {
        config(10.0, null, protocol(null, null));
        budgetStore.addSpend(GROUP_ID, MONTH, 12.0, Instant.parse("2027-12-01T00:00:00Z"));
        stubAgentSayCosting(0.0);

        GroupConversation child = service.discuss(GROUP_ID, "Q?", USER_ID, 1);

        assertNotNull(child);
        assertNull(child.getTeamBudgetState(), "a child charges nothing; its parent pays");
        assertEquals(12.0, teamSpent(), 1e-9);
    }

    @Test
    @DisplayName("a continuation of an existing root discussion is gated, a resume is not")
    void continueIsGated() throws Exception {
        config(10.0, null, protocol(null, null));
        budgetStore.addSpend(GROUP_ID, MONTH, 10.0, Instant.parse("2027-12-01T00:00:00Z"));
        var existing = new GroupConversation();
        existing.setId("gc-existing");
        existing.setGroupId(GROUP_ID);
        existing.setDepth(0);
        doReturn(existing).when(conversationStore).read("gc-existing");

        var e = assertThrows(TeamBudgetRefusedException.class, () -> service.continueDiscussion("gc-existing", "more", null));
        assertEquals("exhausted", e.reason());
        assertThrows(TeamBudgetRefusedException.class, () -> service.followUpWithMember("gc-existing", AGENT_A, "why?"));
    }

    @Test
    @DisplayName("a group without a teamBudget runs exactly as before, uncapped, but is metered")
    void unbudgetedGroupIsMeteredNotCapped() throws Exception {
        config(null, null, protocol(null, null));
        stubAgentSayCosting(2.0);

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        var phasesRun = gc.getTranscript().stream().filter(e -> e.type() != TranscriptEntryType.SKIPPED).map(e -> e.phaseName()).distinct().toList();
        assertTrue(phasesRun.containsAll(List.of("Opinion", "Critique", "Review", "Summary")));
        assertEquals(gc.getTotalCost(), teamSpent(), 1e-9, "metered so that a cap added later sees the month to date");
    }
}
