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
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ConvergenceConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end tests for I2's early exit as seen by
 * {@code GroupConversationService.executeDiscussion}'s phase loop. The judgment
 * logic itself is unit-tested in {@code ConvergenceDetectorTest}; this class
 * covers what only the real loop can show — that a converged phase actually
 * stops repeating, that a non-converged one does not, and that the converged
 * repeat is still persisted and completed rather than abandoned.
 *
 * @author tests
 */
class GroupConversationServiceConvergenceTest {

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

    private static final String GROUP_ID = "group-conv";
    private static final String USER_ID = "user-conv";
    private static final String AGENT_A = "agent-a";
    private static final String MODERATOR = "mod-agent";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                scheduleStore, nonceCacheService, null, new CallerIdentityContext(null, null), "default", 3);
    }

    private AgentGroupConfiguration buildConfig(List<DiscussionPhase> phases) {
        var config = new AgentGroupConfiguration();
        config.setName("Convergence Test Group");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setPhases(phases);
        config.setMembers(List.of(new GroupMember(AGENT_A, "Agent A", 1, null)));
        config.setModeratorAgentId(MODERATOR);
        config.setProtocol(new ProtocolConfig(60, MemberFailurePolicy.SKIP, 2, MemberUnavailablePolicy.SKIP, 50));
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

    private DiscussionPhase repeatingPhase(int repeats, ConvergenceConfig convergence) {
        return new DiscussionPhase("Discussion", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                false, null, repeats, false, convergence);
    }

    /**
     * Every turn returns {@code response}. The judge is the moderator, so its turns
     * are distinguished by agent id — a member turn returns an opinion, a moderator
     * turn returns the verdict JSON.
     */
    private void stubTurns(String memberResponse, String judgeVerdictJson) throws Exception {
        doReturn(mock(IAgent.class)).when(agentFactory).getLatestReadyAgent(any(), anyString());
        doReturn(new IConversationService.ConversationResult("member-conv", null))
                .when(conversationService).startConversation(any(), any(), any(), any());
        doAnswer(inv -> {
            String agentId = inv.getArgument(1);
            IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
            if (handler != null) {
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("output", List.of(MODERATOR.equals(agentId) ? judgeVerdictJson : memberResponse));
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
                handler.onComplete(snapshot);
            }
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    private GroupDiscussionEventListener eventCollectingListener(List<String> events) {
        return new GroupDiscussionEventListener() {
            @Override
            public void onConvergenceChecked(GroupConversationEventSink.ConvergenceCheckedEvent event) {
                events.add("checked:" + event.repeat() + ":" + event.converged());
            }

            @Override
            public void onConvergenceReached(GroupConversationEventSink.ConvergenceReachedEvent event) {
                events.add("reached:" + event.repeat() + ":skipped=" + event.repeatsSkipped());
            }
        };
    }

    @Test
    @DisplayName("judge above threshold stops the repeats early; the remaining rounds never run")
    void convergedJudge_endsRepeatsEarly() throws Exception {
        // 4 repeats configured. minRepeats=2 means the first check runs after repeat
        // index 1, so a converged verdict there should leave repeats 2 and 3 unrun.
        var phases = List.of(repeatingPhase(4, new ConvergenceConfig(true, 2, 0.8, "MODERATOR")));
        var config = buildConfig(phases);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-converged").when(conversationStore).create(any());
        stubTurns("My position", "{\"agreementScore\": 0.95, \"converged\": true, \"summary\": \"aligned\"}");

        var events = Collections.synchronizedList(new ArrayList<String>());
        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, eventCollectingListener(events));

        long memberTurns = gc.getTranscript().stream()
                .filter(e -> AGENT_A.equals(e.speakerAgentId()))
                .count();
        assertEquals(2, memberTurns, "repeats 0 and 1 run, then convergence stops the phase — repeats 2 and 3 must not run");
        assertTrue(events.stream().anyMatch(e -> e.startsWith("reached:1:")), "convergence_reached must fire: " + events);
        assertTrue(events.contains("reached:1:skipped=2"), "two of the four repeats were skipped: " + events);
    }

    @Test
    @DisplayName("judge below threshold lets every configured repeat run")
    void nonConvergedJudge_runsAllRepeats() throws Exception {
        var phases = List.of(repeatingPhase(3, new ConvergenceConfig(true, 2, 0.8, "MODERATOR")));
        var config = buildConfig(phases);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-diverged").when(conversationStore).create(any());
        stubTurns("My position", "{\"agreementScore\": 0.2, \"converged\": false, \"summary\": \"still split\"}");

        var events = Collections.synchronizedList(new ArrayList<String>());
        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, eventCollectingListener(events));

        long memberTurns = gc.getTranscript().stream()
                .filter(e -> AGENT_A.equals(e.speakerAgentId()))
                .count();
        assertEquals(3, memberTurns, "a phase that never converges must run every repeat it was configured for");
        assertTrue(events.stream().noneMatch(e -> e.startsWith("reached:")), "convergence_reached must not fire: " + events);
        assertTrue(events.stream().anyMatch(e -> e.startsWith("checked:")),
                "convergence_checked still fires on a non-converged check, so observers can watch it approach: " + events);
    }

    @Test
    @DisplayName("an unparseable judge verdict never converges and never fails the discussion")
    void unparseableVerdict_runsAllRepeatsAndCompletes() throws Exception {
        var phases = List.of(repeatingPhase(3, new ConvergenceConfig(true, 2, 0.8, "MODERATOR")));
        var config = buildConfig(phases);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-garbage").when(conversationStore).create(any());
        stubTurns("My position", "I think they mostly agree, honestly.");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        long memberTurns = gc.getTranscript().stream()
                .filter(e -> AGENT_A.equals(e.speakerAgentId()))
                .count();
        assertEquals(3, memberTurns, "an unreadable verdict must leave the discussion exactly as it would have been");
        assertNotEquals(GroupConversation.GroupConversationState.FAILED, gc.getState(),
                "a convergence check is an optimization — it must never be why a discussion dies");
    }

    @Test
    @DisplayName("minRepeats is honored: no judge runs before it elapses")
    void minRepeats_gatesTheJudge() throws Exception {
        // minRepeats=3 with 3 repeats: the first check can only happen after repeat
        // index 2, which is the last one — so the judge runs at most once and can
        // never save a round here.
        var phases = List.of(repeatingPhase(3, new ConvergenceConfig(true, 3, 0.8, "MODERATOR")));
        var config = buildConfig(phases);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-minrepeats").when(conversationStore).create(any());
        stubTurns("My position", "{\"agreementScore\": 0.99, \"converged\": true}");

        var events = Collections.synchronizedList(new ArrayList<String>());
        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, eventCollectingListener(events));

        long memberTurns = gc.getTranscript().stream()
                .filter(e -> AGENT_A.equals(e.speakerAgentId()))
                .count();
        assertEquals(3, memberTurns, "all three repeats run — the judge cannot fire before minRepeats");
        assertEquals(1, events.stream().filter(e -> e.startsWith("checked:")).count(),
                "exactly one check, after the final repeat: " + events);
        assertTrue(events.contains("checked:2:true"), "the one check happens at repeat index 2: " + events);
    }

    @Test
    @DisplayName("no convergence block configured means no judge call at all")
    void convergenceAbsent_neverCallsTheJudge() throws Exception {
        assertJudgeNeverRuns(repeatingPhase(3, null));
    }

    @Test
    @DisplayName("convergence explicitly disabled means no judge call at all")
    void convergenceExplicitlyDisabled_neverCallsTheJudge() throws Exception {
        // Distinct from the absent case above, and NOT redundant: an operator who
        // writes {"enabled": false} rather than omitting the block gets a non-null
        // config, which only the enabled() check stops. Without this test that check
        // can be deleted and every other test still passes — verified by mutation.
        assertJudgeNeverRuns(repeatingPhase(3, new ConvergenceConfig(false, 2, 0.8, "MODERATOR")));
    }

    private void assertJudgeNeverRuns(DiscussionPhase phase) throws Exception {
        var config = buildConfig(List.of(phase));
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-off").when(conversationStore).create(any());
        stubTurns("My position", "{\"agreementScore\": 0.99, \"converged\": true}");

        var events = Collections.synchronizedList(new ArrayList<String>());
        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, eventCollectingListener(events));

        // Asserted against the SERVICE call, not the transcript: runJudge discards
        // the entry it gets back rather than appending it, so counting moderator
        // transcript entries would read 0 whether or not the judge ran — a vacuous
        // assertion that passes for the wrong reason.
        verify(conversationService, never()).say(any(), eq(MODERATOR), any(), any(), any(), any(), any(), anyBoolean(), any());
        assertTrue(events.isEmpty(), "no convergence events when the feature is off: " + events);
        long memberTurns = gc.getTranscript().stream()
                .filter(e -> AGENT_A.equals(e.speakerAgentId()))
                .count();
        assertEquals(3, memberTurns, "and every configured repeat still runs");
    }

    @Test
    @DisplayName("the judge runs in its own conversation, never the moderator's")
    void judge_doesNotShareTheModeratorsConversation() throws Exception {
        // The judge runs the moderator AGENT. If it also shared the moderator's
        // CONVERSATION, its "reply with ONLY this JSON" prompts would become the
        // recent history a later SYNTHESIS turn (same agent) reads — and the
        // synthesized answer comes back as JSON.
        var phases = List.of(repeatingPhase(2, new ConvergenceConfig(true, 2, 0.8, "MODERATOR")));
        var config = buildConfig(phases);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-judge-conv").when(conversationStore).create(any());
        stubTurns("My position", "{\"agreementScore\": 0.95, \"converged\": true}");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        var keys = gc.getMemberConversationIds().keySet();
        assertTrue(keys.contains("__convergence_judge"),
                "the judge must get its own conversation key: " + keys);
        assertFalse(keys.contains(MODERATOR),
                "and must NOT have opened or reused the moderator's own conversation: " + keys);
    }

    @Test
    @DisplayName("a repeat that produced nothing is not sent to the judge")
    void emptyRepeat_skipsTheJudge() throws Exception {
        // maxTurns=2 with 3 repeats: repeats 0 and 1 consume the budget, so repeat 2
        // produces no entries at all. A judge handed "(no contributions)" can read
        // silence as agreement and record a convergence for a phase that actually ran
        // out of turns.
        var phases = List.of(repeatingPhase(3, new ConvergenceConfig(true, 2, 0.8, "MODERATOR")));
        var config = buildConfig(phases);
        config.setProtocol(new ProtocolConfig(60, MemberFailurePolicy.SKIP, 2, MemberUnavailablePolicy.SKIP, 2));
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-empty-repeat").when(conversationStore).create(any());
        stubTurns("My position", "{\"agreementScore\": 0.99, \"converged\": true}");

        var events = Collections.synchronizedList(new ArrayList<String>());
        service.discuss(GROUP_ID, "Q?", USER_ID, 0, eventCollectingListener(events));

        assertTrue(events.stream().noneMatch(e -> e.startsWith("reached:")),
                "a phase that ran out of turn budget must not be recorded as converged: " + events);
    }

    @Test
    @DisplayName("the converged repeat still writes a CONVERGENCE entry and completes normally")
    void convergedRepeat_isStillPersistedAndCompleted() throws Exception {
        var phases = List.of(repeatingPhase(4, new ConvergenceConfig(true, 2, 0.8, "MODERATOR")));
        var config = buildConfig(phases);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-persisted").when(conversationStore).create(any());
        stubTurns("My position", "{\"agreementScore\": 0.95, \"converged\": true, \"summary\": \"aligned\"}");

        var phaseCompletes = new AtomicInteger();
        var listener = new GroupDiscussionEventListener() {
            @Override
            public void onPhaseComplete(GroupConversationEventSink.PhaseCompleteEvent event) {
                phaseCompletes.incrementAndGet();
            }
        };

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, listener);

        assertTrue(gc.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.CONVERGENCE),
                "the convergence decision belongs in the audit trail");
        assertEquals(2, phaseCompletes.get(),
                "the converged repeat is a completed repeat — it must still fire onPhaseComplete, unlike a cost-ceiling abort");
    }
}
