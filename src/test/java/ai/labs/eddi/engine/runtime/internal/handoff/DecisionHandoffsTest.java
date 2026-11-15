/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.handoff;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionRecord;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptPolicy;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptRule;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.DecisionRule;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.GroupHandoffConfig;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.HandoffTarget;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.IntakeMode;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.TeamHandoffEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionHandoffsTest {

    static final String TARGET = "target-group";

    HandoffFakes.WorkspaceStore workspaces;
    DecisionHandoffs decisionHandoffs;
    AgentGroupConfiguration sourceConfig;
    List<TeamHandoffEvent> events;
    GroupDiscussionEventListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        workspaces = new HandoffFakes.WorkspaceStore();
        var groupStore = mock(IAgentGroupStore.class);
        var targetConfig = new AgentGroupConfiguration();
        targetConfig.setHandoffConfig(new GroupHandoffConfig(null, null, 0, 0, 0, null,
                new AcceptPolicy(List.of(new AcceptRule("group:source-group", IntakeMode.AUTO, null)), 0, 0)));
        when(groupStore.getCurrentResourceId(TARGET)).thenReturn(new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return TARGET;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        });
        when(groupStore.read(any(), any())).thenReturn(targetConfig);
        Instance<HandoffAdmission> admissions = mock(Instance.class);
        when(admissions.isUnsatisfied()).thenReturn(true);
        var service = new TeamHandoffService(workspaces, groupStore, new HandoffFakes.QuotaStore(), mock(AuditLedgerService.class),
                new SimpleMeterRegistry(),
                admissions, 3, Duration.ofDays(30), Clock.systemUTC());
        decisionHandoffs = new DecisionHandoffs(service);
        events = new ArrayList<>();
        listener = new GroupDiscussionEventListener() {
            @Override
            public void onTeamHandoff(TeamHandoffEvent event) {
                events.add(event);
            }
        };
        sourceConfig = configWith(new DecisionRule("desk", List.of("VOTE"), true, "Research account: {decision.winner}", "{decision.outcome}", 7));
    }

    AgentGroupConfiguration configWith(DecisionRule... rules) {
        var config = new AgentGroupConfiguration();
        config.setHandoffConfig(new GroupHandoffConfig(List.of(new HandoffTarget("desk", TARGET, null, 3)), null, 0, 0, 0, List.of(rules), null));
        return config;
    }

    GroupConversation discussion(DecisionRecord decision) {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("source-group");
        gc.setUserId("user-1");
        gc.setDecision(decision);
        return gc;
    }

    static DecisionRecord vote(String winner, String outcome) {
        return new DecisionRecord(DecisionType.VOTE, outcome, winner, Map.of(), List.of(), "PLURALITY", "Vote", null);
    }

    int backlogSize() throws Exception {
        return workspaces.readOrCreate(TARGET).getBacklog().size();
    }

    @Test
    void aMatchingVoteFilesATaskWithTheRenderedTemplates() throws Exception {
        decisionHandoffs.afterCompleted(discussion(vote("ACME", "ACME won 5 to 2")), sourceConfig, listener);
        var ws = workspaces.readOrCreate(TARGET);
        var task = ws.getBacklog().getTasks().get(0);
        assertEquals("Research account: ACME", task.subject());
        assertEquals("ACME won 5 to 2", task.description());
        assertEquals(7, task.priority());
        var origin = ws.getHandoffs().originOf(task.id());
        assertEquals("DECISION", origin.trigger().name());
        assertEquals("VOTE@Vote", origin.decisionRef());
        assertEquals(1, events.size());
        assertEquals("FILED", events.get(0).outcome());
        assertEquals("DECISION", events.get(0).trigger());
    }

    @Test
    void theRulePriorityDefaultsToTheTargets() throws Exception {
        sourceConfig = configWith(new DecisionRule("desk", List.of("VOTE"), true, "s {decision.winner}", null, null));
        decisionHandoffs.afterCompleted(discussion(vote("X", "o")), sourceConfig, listener);
        assertEquals(3, workspaces.readOrCreate(TARGET).getBacklog().getTasks().get(0).priority());
    }

    @Test
    void aDecisionTypeThatIsNotListedFilesNothing() throws Exception {
        var verdict = new DecisionRecord(DecisionType.VERDICT, "o", "w", Map.of(), List.of(), "m", "p", null);
        decisionHandoffs.afterCompleted(discussion(verdict), sourceConfig, listener);
        assertEquals(0, backlogSize());
        assertTrue(events.isEmpty());
    }

    @Test
    void requireWinnerSkipsATie() throws Exception {
        decisionHandoffs.afterCompleted(discussion(vote(null, "tie")), sourceConfig, listener);
        decisionHandoffs.afterCompleted(discussion(vote("  ", "tie")), sourceConfig, listener);
        assertEquals(0, backlogSize());
        var lenient = configWith(new DecisionRule("desk", List.of("VOTE"), false, "Tie: {decision.outcome}", null, null));
        decisionHandoffs.afterCompleted(discussion(vote(null, "tie")), lenient, listener);
        assertEquals(1, backlogSize());
    }

    @Test
    void noDecisionOrNoRulesIsANoOp() throws Exception {
        decisionHandoffs.afterCompleted(discussion(null), sourceConfig, listener);
        decisionHandoffs.afterCompleted(discussion(vote("W", "o")), new AgentGroupConfiguration(), listener);
        decisionHandoffs.afterCompleted(discussion(vote("W", "o")), null, listener);
        decisionHandoffs.afterCompleted(null, sourceConfig, listener);
        assertEquals(0, backlogSize());
    }

    @Test
    void completingTwiceFilesOnce() throws Exception {
        var gc = discussion(vote("ACME", "o"));
        decisionHandoffs.afterCompleted(gc, sourceConfig, listener);
        decisionHandoffs.afterCompleted(gc, sourceConfig, listener);
        assertEquals(1, backlogSize());
        assertEquals("DUPLICATE", events.get(1).outcome());
    }

    @Test
    void aNewRoundIsANewDecision() throws Exception {
        var gc = discussion(vote("ACME", "o"));
        decisionHandoffs.afterCompleted(gc, sourceConfig, listener);
        gc.setRound(2);
        gc.setDecision(vote("Globex", "o2"));
        decisionHandoffs.afterCompleted(gc, sourceConfig, listener);
        assertEquals(2, backlogSize());
    }

    @Test
    void decisionDataIsSubstitutedAsDataAndNeverReEvaluated() throws Exception {
        String hostile = "{decision.outcome} {#include foo} {config:secret} {inject:x}";
        decisionHandoffs.afterCompleted(discussion(vote(hostile, "OUTCOME-TEXT")), sourceConfig, listener);
        var task = workspaces.readOrCreate(TARGET).getBacklog().getTasks().get(0);
        assertEquals("Research account: " + hostile, task.subject(),
                "the winner's own braces must come through literally, not be expanded a second time");
    }

    @Test
    void overlongValuesAreBoundedNotRefusedOutright() throws Exception {
        decisionHandoffs.afterCompleted(discussion(vote("W", "x".repeat(10_000))), sourceConfig, listener);
        var task = workspaces.readOrCreate(TARGET).getBacklog().getTasks().get(0);
        assertTrue(task.description().length() <= DecisionHandoffs.MAX_SUBSTITUTED_CHARS);
    }

    @Test
    void aTargetTheTeamDoesNotAcceptIsRecordedAsRefusedNotThrown() throws Exception {
        var stranger = new AgentGroupConfiguration();
        stranger.setHandoffConfig(new GroupHandoffConfig(List.of(new HandoffTarget("desk", TARGET, null, null)), null, 0, 0, 0,
                List.of(new DecisionRule("desk", List.of("VOTE"), true, "s", null, null)), null));
        var gc = discussion(vote("W", "o"));
        gc.setGroupId("not-listed-group");
        decisionHandoffs.afterCompleted(gc, stranger, listener);
        assertEquals(0, backlogSize());
        assertEquals("NOT_ACCEPTED", events.get(0).outcome());
        assertEquals(1, gc.getHandoffState().getFiled().size());
    }

    @Test
    void theDiscussionsLineageContinuesTheChain() throws Exception {
        var gc = discussion(vote("W", "o"));
        var state = gc.handoffStateOrCreate();
        state.setHop(1);
        state.setLineage(List.of("agent:first"));
        decisionHandoffs.afterCompleted(gc, sourceConfig, listener);
        var ws = workspaces.readOrCreate(TARGET);
        var origin = ws.getHandoffs().getOrigins().values().iterator().next();
        assertEquals(2, origin.hop());
        assertEquals(List.of("agent:first", "group:source-group"), origin.lineage());
    }

    @Test
    void aListenerThatThrowsDoesNotBreakCompletion() {
        var angry = new GroupDiscussionEventListener() {
            @Override
            public void onTeamHandoff(TeamHandoffEvent event) {
                throw new IllegalStateException("client went away");
            }
        };
        assertDoesNotThrow(() -> decisionHandoffs.afterCompleted(discussion(vote("W", "o")), sourceConfig, angry));
    }

    @Test
    void renderOnlyKnowsTheDecisionPlaceholders() {
        var d = vote("W", "O");
        assertEquals("W / O / VOTE / PLURALITY / Vote",
                DecisionHandoffs.render("{decision.winner} / {decision.outcome} / {decision.type} / {decision.method} / {decision.phase}", d));
        assertEquals("{decision.secret} {other.winner}", DecisionHandoffs.render("{decision.secret} {other.winner}", d));
        assertEquals("", DecisionHandoffs.render(null, d));
    }
}
