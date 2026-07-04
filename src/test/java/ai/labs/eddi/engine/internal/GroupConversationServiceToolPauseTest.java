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
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgentFactory;
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
 * Task 13: group-member tool-pause policy. When a member conversation TOOL_CALL
 * pauses during a group turn, the group auto-resolves it with a system:group
 * REJECTED resume routed through the NORMAL resume path (the member's LLM
 * receives rejection tool-results and produces a tool-less answer that becomes
 * its turn contribution). If the resume cannot complete within the member-turn
 * budget (or re-pauses), the group falls back to the EXISTING member-pause
 * handling (turn SKIPPED + member_pause_skipped SSE + audited auto-cancel). A
 * RULE member pause is unchanged (SKIP + cancel, no graceful-rejection
 * attempt).
 */
class GroupConversationServiceToolPauseTest {

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
    private ai.labs.eddi.engine.schedule.IScheduleStore scheduleStore;

    private GroupConversationService service;

    private static final int MAX_DEPTH = 3;
    private static final String DEFAULT_TENANT = "default";
    private static final String GROUP_ID = "group-tool-pause";
    private static final String USER_ID = "user-tool-pause";
    private static final String MOD_AGENT = "mod-agent";
    private static final String AGENT_A = "agent-a";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                scheduleStore, nonceCacheService, null, DEFAULT_TENANT, MAX_DEPTH);
    }

    private AgentGroupConfiguration buildConfig(List<DiscussionPhase> phases) {
        var config = new AgentGroupConfiguration();
        config.setName("Tool Pause Test Group");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setPhases(phases);
        config.setMembers(List.of(new GroupMember(AGENT_A, "Agent A", 1, null)));
        config.setModeratorAgentId(MOD_AGENT);
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

    private SimpleConversationMemorySnapshot toolCallPausedSnapshot() {
        var batch = new PendingToolCallBatch();
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setToolName("delete_production_db");
        batch.setCalls(List.of(call));
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPauseType("TOOL_CALL");
        snapshot.setHitlPendingToolCalls(batch);
        return snapshot;
    }

    private SimpleConversationMemorySnapshot answerSnapshot(String text) {
        var snapshot = new SimpleConversationMemorySnapshot();
        var output = new ConversationOutput();
        output.put("output", List.of(text));
        snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
        snapshot.setConversationState(ConversationState.READY);
        return snapshot;
    }

    private List<DiscussionPhase> singleOpinionPhase() {
        return List.of(new DiscussionPhase("Opinion", PhaseType.OPINION,
                "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                false, null, 1, false));
    }

    private double skippedCount() throws Exception {
        var field = GroupConversationService.class.getDeclaredField("counterGroupMemberPauseSkipped");
        field.setAccessible(true);
        var counter = (io.micrometer.core.instrument.Counter) field.get(service);
        return counter.count();
    }

    @Test
    @DisplayName("member TOOL_CALL pause → system:group REJECTED resume produces the turn contribution")
    void toolCallPause_gracefulRejectResumeProducesContribution() throws Exception {
        var config = buildConfig(singleOpinionPhase());
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-tool-graceful").when(conversationStore).create(any());

        doReturn(mock(ai.labs.eddi.engine.runtime.IAgent.class))
                .when(agentFactory).getLatestReadyAgent(any(), eq(AGENT_A));
        doReturn(new IConversationService.ConversationResult("member-conv", null))
                .when(conversationService).startConversation(any(), any(), any(), any());

        // The member's private conversation TOOL_CALL-pauses mid-turn.
        doAnswer(inv -> {
            IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
            handler.onComplete(toolCallPausedSnapshot());
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        // The graceful system:group REJECTED resume completes synchronously with a
        // coherent tool-less answer that becomes the member's contribution.
        var rejectDecision = new java.util.concurrent.atomic.AtomicReference<HitlDecision>();
        doAnswer(inv -> {
            rejectDecision.set(inv.getArgument(1));
            IConversationService.ConversationResponseHandler handler = inv.getArgument(2);
            handler.onComplete(answerSnapshot("I cannot delete the database, but here is my opinion."));
            return null;
        }).when(conversationService).resumeConversation(eq("member-conv"), any(), any());

        GroupConversation gc = service.discuss(GROUP_ID, "Question?", USER_ID, 0);

        // The resume was invoked with a REJECTED, system:group decision (not a cancel).
        verify(conversationService).resumeConversation(eq("member-conv"), any(), any());
        assertNotNull(rejectDecision.get(), "resume must carry a decision");
        assertEquals(HitlDecision.HitlVerdict.REJECTED, rejectDecision.get().getVerdict(),
                "the group must reject the tool call, not approve it");
        assertEquals("system:group", rejectDecision.get().getDecidedBy(),
                "the rejection must be attributed to the group system actor");

        // The member's tool-less answer becomes its turn contribution — NOT a SKIP.
        var memberEntries = gc.getTranscript().stream()
                .filter(e -> AGENT_A.equals(e.speakerAgentId()))
                .toList();
        assertEquals(1, memberEntries.size(), "exactly one member entry expected");
        var entry = memberEntries.get(0);
        assertEquals(GroupConversation.TranscriptEntryType.OPINION, entry.type(),
                "a graceful-rejection resume that completes must produce a real contribution, not SKIPPED");
        assertNotNull(entry.content());
        assertTrue(entry.content().contains("opinion"), entry.content());

        // A graceful resolution must NOT cancel the member conversation nor count a
        // skip.
        verify(conversationService, never()).cancelConversation(eq("member-conv"), any(), any());
        assertEquals(0.0, skippedCount(), "a graceful resume must NOT count a member-pause skip");

        assertEquals(GroupConversationState.COMPLETED, gc.getState());
    }

    @Test
    @DisplayName("member TOOL_CALL pause whose resume re-pauses → falls back to SKIP + cancel")
    void toolCallPause_resumeRepauses_fallsBackToSkipAndCancel() throws Exception {
        var config = buildConfig(singleOpinionPhase());
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-tool-repause").when(conversationStore).create(any());

        doReturn(mock(ai.labs.eddi.engine.runtime.IAgent.class))
                .when(agentFactory).getLatestReadyAgent(any(), eq(AGENT_A));
        doReturn(new IConversationService.ConversationResult("member-conv", null))
                .when(conversationService).startConversation(any(), any(), any(), any());

        doAnswer(inv -> {
            IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
            handler.onComplete(toolCallPausedSnapshot());
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        // The resume itself RE-PAUSES (its snapshot is still AWAITING_HUMAN) — the
        // graceful attempt did not resolve, so the group must fall back to the
        // existing member-pause handling.
        doAnswer(inv -> {
            IConversationService.ConversationResponseHandler handler = inv.getArgument(2);
            handler.onComplete(toolCallPausedSnapshot());
            return null;
        }).when(conversationService).resumeConversation(eq("member-conv"), any(), any());

        GroupConversation gc = service.discuss(GROUP_ID, "Question?", USER_ID, 0);

        // Fallback path: the stranded pause is cancelled (audited system:group) and the
        // turn is recorded SKIPPED with the explanatory note.
        verify(conversationService).cancelConversation(eq("member-conv"),
                eq(ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL), eq("system:group"));
        assertEquals(1.0, skippedCount(), "the fallback must count exactly one member-pause skip");

        var memberEntries = gc.getTranscript().stream()
                .filter(e -> AGENT_A.equals(e.speakerAgentId()))
                .toList();
        assertEquals(1, memberEntries.size());
        var entry = memberEntries.get(0);
        assertEquals(GroupConversation.TranscriptEntryType.SKIPPED, entry.type());
        assertNull(entry.content());
        assertNotNull(entry.errorReason());

        assertEquals(GroupConversationState.COMPLETED, gc.getState());
    }

    @Test
    @DisplayName("member RULE pause → unchanged SKIP + cancel, NO graceful-rejection resume attempt")
    void rulePause_unchangedSkipAndCancel_noResumeAttempt() throws Exception {
        var config = buildConfig(singleOpinionPhase());
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-rule-pause").when(conversationStore).create(any());

        doReturn(mock(ai.labs.eddi.engine.runtime.IAgent.class))
                .when(agentFactory).getLatestReadyAgent(any(), eq(AGENT_A));
        doReturn(new IConversationService.ConversationResult("member-conv", null))
                .when(conversationService).startConversation(any(), any(), any(), any());

        // A RULE pause: AWAITING_HUMAN with pauseType RULE and NO pending tool calls.
        doAnswer(inv -> {
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("RULE");
            IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        GroupConversation gc = service.discuss(GROUP_ID, "Question?", USER_ID, 0);

        // A RULE pause needs a real human — the group must NOT attempt a graceful
        // rejection resume. It stays exactly as before: SKIP + cancel + skip metric.
        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verify(conversationService).cancelConversation(eq("member-conv"),
                eq(ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL), eq("system:group"));
        assertEquals(1.0, skippedCount(), "a RULE member pause still counts a skip");

        var memberEntries = gc.getTranscript().stream()
                .filter(e -> AGENT_A.equals(e.speakerAgentId()))
                .toList();
        assertEquals(1, memberEntries.size());
        assertEquals(GroupConversation.TranscriptEntryType.SKIPPED, memberEntries.get(0).type());

        assertEquals(GroupConversationState.COMPLETED, gc.getState());
    }
}
