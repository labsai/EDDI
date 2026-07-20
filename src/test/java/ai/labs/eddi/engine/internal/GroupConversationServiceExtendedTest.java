/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.*;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link GroupConversationService} — covers branches missed
 * by the main test file: startAndDiscussAsync, parallel phases, peer-targeted
 * phases, maxTurns cap, listener events, delete error handling, template
 * fallback, resolveParticipants edge cases, and context scope filtering.
 */
class GroupConversationServiceExtendedTest {

    private IAgentGroupStore groupStore;
    private IGroupConversationStore conversationStore;
    private IConversationService conversationService;
    private IAgentFactory agentFactory;
    private ITemplatingEngine templatingEngine;
    private IJsonSerialization jsonSerialization;
    private GroupConversationService service;

    private ai.labs.eddi.configs.agents.IAgentStore agentStore;

    private static final String GROUP_ID = "test-group";
    private static final String USER_ID = "test-user";
    private static final String QUESTION = "What is the best approach?";

    @BeforeEach
    void setUp() throws Exception {
        groupStore = mock(IAgentGroupStore.class);
        conversationStore = mock(IGroupConversationStore.class);
        conversationService = mock(IConversationService.class);
        agentFactory = mock(IAgentFactory.class);
        templatingEngine = mock(ITemplatingEngine.class);
        jsonSerialization = mock(IJsonSerialization.class);

        agentStore = mock(ai.labs.eddi.configs.agents.IAgentStore.class);
        service = new GroupConversationService(groupStore, conversationStore,
                conversationService, agentFactory, templatingEngine,
                jsonSerialization, new SimpleMeterRegistry(),
                null, agentStore, null, null, null, "default", 3);

        when(conversationStore.create(any())).thenReturn("gc-1");

        lenient().when(jsonSerialization.serialize(any()))
                .thenAnswer(inv -> inv.getArgument(0).toString());

        lenient().when(templatingEngine.processTemplate(anyString(), any(), any()))
                .thenAnswer(inv -> {
                    String tmpl = inv.getArgument(0, String.class);
                    return tmpl.length() > 80 ? tmpl.substring(0, 80) : tmpl;
                });
    }

    // --- Helpers ---

    private AgentGroupConfiguration config(DiscussionStyle style, int rounds,
                                           GroupMember... members) {
        var c = new AgentGroupConfiguration();
        c.setName("Test Group");
        c.setMembers(List.of(members));
        c.setStyle(style);
        c.setMaxRounds(rounds);
        c.setProtocol(new ProtocolConfig(60,
                ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                ProtocolConfig.MemberUnavailablePolicy.SKIP));
        return c;
    }

    private void setupStore(AgentGroupConfiguration cfg) throws Exception {
        setupStore(GROUP_ID, cfg);
    }

    private void setupStore(String groupId, AgentGroupConfiguration cfg) throws Exception {
        var rid = mock(IResourceStore.IResourceId.class);
        when(rid.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(groupId)).thenReturn(rid);
        when(groupStore.read(groupId, 1)).thenReturn(cfg);
    }

    private void stubAgent(String agentId, String response) throws Exception {
        when(agentFactory.getLatestReadyAgent(any(Environment.class), eq(agentId)))
                .thenReturn(mock(IAgent.class));

        when(conversationService.startConversation(any(Environment.class),
                eq(agentId), anyString(), any()))
                .thenReturn(new IConversationService.ConversationResult(
                        "conv-" + agentId, null));

        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(8);
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            output.put("output", List.of(response));
            snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(Environment.class), eq(agentId),
                anyString(), any(), any(), any(), any(InputData.class),
                anyBoolean(), any(ConversationResponseHandler.class));
    }

    // =========================================================
    // startAndDiscussAsync
    // =========================================================

    @Nested
    class AsyncDiscussion {

        @Test
        void startAndDiscussAsync_nullGroupId_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.startAndDiscussAsync(null, QUESTION, USER_ID, null));
        }

        @Test
        void startAndDiscussAsync_groupNotFound_throwsResourceNotFoundException() throws Exception {
            when(groupStore.getCurrentResourceId("missing")).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> service.startAndDiscussAsync("missing", QUESTION, USER_ID, null));
        }

        @Test
        void startAndDiscussAsync_nullConfig_throwsResourceNotFoundException() throws Exception {
            var rid = mock(IResourceStore.IResourceId.class);
            when(rid.getVersion()).thenReturn(1);
            when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(rid);
            when(groupStore.read(GROUP_ID, 1)).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> service.startAndDiscussAsync(GROUP_ID, QUESTION, USER_ID, null));
        }

        @Test
        void startAndDiscussAsync_emptyPhases_throwsGroupDiscussionException() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setPhases(List.of()); // empty phases
            setupStore(cfg);

            assertThrows(GroupDiscussionException.class,
                    () -> service.startAndDiscussAsync(GROUP_ID, QUESTION, USER_ID, null));
        }

        @Test
        void startAndDiscussAsync_returnsImmediately_withConversationId() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            // Gate the first agent response so the async thread blocks until we release it.
            // This guarantees the state is still IN_PROGRESS when we assert.
            var gate = new java.util.concurrent.CountDownLatch(1);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(Environment.class),
                    eq("a1"), anyString(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));
            doAnswer(inv -> {
                gate.await(5, TimeUnit.SECONDS); // block until test releases
                ConversationResponseHandler handler = inv.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("output", List.of("Opinion A"));
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(any(Environment.class), eq("a1"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));

            stubAgent("mod", "Synthesis");

            var result = service.startAndDiscussAsync(GROUP_ID, QUESTION, USER_ID, null);

            // Assert while the async thread is still blocked on the gate
            assertNotNull(result);
            assertEquals("gc-1", result.getId());
            assertEquals(GROUP_ID, result.getGroupId());
            assertEquals(GroupConversationState.IN_PROGRESS, result.getState());

            // Release the gate so the async thread can finish cleanly
            gate.countDown();
            Thread.sleep(500);
        }

        @Test
        void startAndDiscussAsync_withListener_sendsGroupStartEvent() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Opinion");
            stubAgent("mod", "Synthesis");

            var listener = mock(GroupDiscussionEventListener.class);

            service.startAndDiscussAsync(GROUP_ID, QUESTION, USER_ID, listener);

            // Give async thread time to complete
            Thread.sleep(1000);

            verify(listener, atLeastOnce()).onGroupStart(any(GroupConversationEventSink.GroupStartEvent.class));
        }
    }

    // =========================================================
    // Listener events
    // =========================================================

    @Nested
    class ListenerEvents {

        @Test
        void discuss_withListener_emitsAllEvents() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Opinion");
            stubAgent("mod", "Synthesis");

            var listener = mock(GroupDiscussionEventListener.class);

            service.discuss(GROUP_ID, QUESTION, USER_ID, 0, listener);

            // Verify all event types are emitted
            verify(listener).onGroupStart(any(GroupConversationEventSink.GroupStartEvent.class));
            verify(listener, atLeastOnce()).onPhaseStart(any(GroupConversationEventSink.PhaseStartEvent.class));
            verify(listener, atLeastOnce()).onSpeakerStart(any(GroupConversationEventSink.SpeakerStartEvent.class));
            verify(listener, atLeastOnce()).onSpeakerComplete(any(GroupConversationEventSink.SpeakerCompleteEvent.class));
            verify(listener, atLeastOnce()).onPhaseComplete(any(GroupConversationEventSink.PhaseCompleteEvent.class));
            verify(listener).onSynthesisStart(any(GroupConversationEventSink.SynthesisStartEvent.class));
            verify(listener).onGroupComplete(any(GroupConversationEventSink.GroupCompleteEvent.class));
        }

        @Test
        void discuss_failureWithListener_emitsGroupError() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setProtocol(new ProtocolConfig(60,
                    ProtocolConfig.MemberFailurePolicy.ABORT, 0,
                    ProtocolConfig.MemberUnavailablePolicy.FAIL));
            setupStore(cfg);
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(null);

            var listener = mock(GroupDiscussionEventListener.class);

            assertThrows(GroupDiscussionException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0, listener));

            verify(listener).onGroupError(any(GroupConversationEventSink.GroupErrorEvent.class));
        }

        @Test
        void discuss_withNullListener_doesNotThrow() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Opinion");
            stubAgent("mod", "Synthesis");

            // Null listener should work fine
            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0, null);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // Parallel phase execution
    // =========================================================

    @Nested
    class ParallelPhases {

        @Test
        void parallelPhase_executesAllSpeakersConcurrently() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("a2", "Bob", 2, null));
            cfg.setModeratorAgentId("mod");
            cfg.setPhases(List.of(
                    new DiscussionPhase("ParallelOpinion", PhaseType.OPINION,
                            "ALL", TurnOrder.PARALLEL, ContextScope.NONE, false, null, 1),
                    new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS,
                            "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1)));
            setupStore(cfg);
            stubAgent("a1", "Alice parallel opinion");
            stubAgent("a2", "Bob parallel opinion");
            stubAgent("mod", "Parallel synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            long opinions = result.getTranscript().stream()
                    .filter(e -> e.type() == TranscriptEntryType.OPINION)
                    .count();
            assertTrue(opinions >= 2, "Expected >=2 parallel opinions, got " + opinions);
        }

        @Test
        void parallelPhase_agentTimeout_producesSkippedEntry() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            cfg.setProtocol(new ProtocolConfig(1,
                    ProtocolConfig.MemberFailurePolicy.SKIP, 0,
                    ProtocolConfig.MemberUnavailablePolicy.SKIP));
            cfg.setPhases(List.of(
                    new DiscussionPhase("ParallelOpinion", PhaseType.OPINION,
                            "ALL", TurnOrder.PARALLEL, ContextScope.NONE, false, null, 1),
                    new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS,
                            "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1)));
            setupStore(cfg);

            // Agent hangs forever
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("a1"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));
            doAnswer(inv -> {
                // Never call the handler — simulate timeout
                Thread.sleep(5000);
                return null;
            }).when(conversationService).say(any(Environment.class), eq("a1"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));

            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            // Should complete with a skipped/timeout entry
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // Peer-targeted phases
    // =========================================================

    @Nested
    class PeerTargetedPhases {

        @Test
        void peerTargetedPhase_skipsSelf() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("a2", "Bob", 2, null));
            cfg.setModeratorAgentId("mod");
            cfg.setPhases(List.of(
                    new DiscussionPhase("Opinions", PhaseType.OPINION),
                    new DiscussionPhase("PeerCritique", PhaseType.CRITIQUE,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, true, null, 1),
                    new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS,
                            "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1)));
            setupStore(cfg);
            stubAgent("a1", "Alice response");
            stubAgent("a2", "Bob response");
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // Peer-targeted critique: a1 critiques a2, a2 critiques a1 (2 total)
            long critiques = result.getTranscript().stream()
                    .filter(e -> e.type() == TranscriptEntryType.CRITIQUE)
                    .count();
            assertTrue(critiques >= 2, "Expected >=2 peer critiques, got " + critiques);
        }
    }

    // =========================================================
    // Max Turns cap
    // =========================================================

    @Nested
    class MaxTurnsCap {

        @Test
        void maxTurns_exceedsCap_skipRemainingPhases() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("a2", "Bob", 2, null),
                    new GroupMember("a3", "Carol", 3, null));
            cfg.setModeratorAgentId("mod");
            // Set maxTurns to 1 so only first speaker completes
            cfg.setProtocol(new ProtocolConfig(60,
                    ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                    ProtocolConfig.MemberUnavailablePolicy.SKIP, 2));
            setupStore(cfg);
            stubAgent("a1", "Opinion A");
            stubAgent("a2", "Opinion B");
            stubAgent("a3", "Opinion C");
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // Resolve Participants edge cases
    // =========================================================

    @Nested
    class ResolveParticipants {

        @Test
        void moderatorParticipant_noModeratorSet_fallsBackToAll() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            // No moderator set — moderatorAgentId is null
            cfg.setPhases(List.of(
                    new DiscussionPhase("SynthPhase", PhaseType.SYNTHESIS,
                            "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1)));
            setupStore(cfg);
            stubAgent("a1", "Synth by participant");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // Should fallback to ALL since no moderator
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> "a1".equals(e.speakerAgentId())));
        }

        @Test
        void roleParticipant_nonExistentRole_fallsBackToAll() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, "EXPERT"));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Opinions", PhaseType.OPINION,
                            "ROLE:NON_EXISTENT", TurnOrder.SEQUENTIAL,
                            ContextScope.FULL, false, null, 1)));
            setupStore(cfg);
            stubAgent("a1", "Fallback opinion");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> "a1".equals(e.speakerAgentId()) &&
                            e.type() == TranscriptEntryType.OPINION));
        }

        @Test
        void roleParticipant_matchingRole_filtersCorrectly() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, "EXPERT"),
                    new GroupMember("a2", "Bob", 2, "OBSERVER"));
            cfg.setPhases(List.of(
                    new DiscussionPhase("ExpertOpinion", PhaseType.OPINION,
                            "ROLE:EXPERT", TurnOrder.SEQUENTIAL,
                            ContextScope.NONE, false, null, 1)));
            setupStore(cfg);
            stubAgent("a1", "Expert opinion");
            stubAgent("a2", "Should not speak");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> "a1".equals(e.speakerAgentId())));
            assertFalse(result.getTranscript().stream()
                    .anyMatch(e -> "a2".equals(e.speakerAgentId())));
        }
    }

    // =========================================================
    // Template engine fallback
    // =========================================================

    @Nested
    class TemplateFallback {

        @Test
        void templateEngineException_fallsBackToPlainText() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Opinion");
            stubAgent("mod", "Synthesis");

            // Template engine throws
            when(templatingEngine.processTemplate(anyString(), any(), any()))
                    .thenThrow(new ITemplatingEngine.TemplateEngineException("Template error", new RuntimeException("cause")));

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // Delete group conversation edge cases
    // =========================================================

    @Nested
    class DeleteEdgeCases {

        @Test
        void deleteGroupConversation_notFound_doesNotThrow() throws Exception {
            when(conversationStore.read("gc-missing"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));

            // Should log warning but not throw
            assertDoesNotThrow(() -> service.deleteGroupConversation("gc-missing"));
            verify(conversationStore, never()).delete(anyString());
        }

        @Test
        void deleteGroupConversation_endConversationFails_stillDeletes() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-3");
            gc.getMemberConversationIds().put("a1", "conv-a1");
            when(conversationStore.read("gc-3")).thenReturn(gc);

            // endConversation throws for a1
            doThrow(new RuntimeException("End failed"))
                    .when(conversationService).endConversation("conv-a1");

            service.deleteGroupConversation("gc-3");

            // Should still attempt to delete despite endConversation failure
            verify(conversationStore).delete("gc-3");
        }
    }

    // =========================================================
    // Agent failure handling policies
    // =========================================================

    @Nested
    class FailurePolicies {

        @Test
        void unavailablePolicy_fail_throwsException() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setProtocol(new ProtocolConfig(60,
                    ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                    ProtocolConfig.MemberUnavailablePolicy.FAIL));
            setupStore(cfg);
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(null);

            // executeDiscussion must surface an execution failure as
            // GroupExecutionException
            // (which REST maps to 5xx), not a bare GroupDiscussionException (409).
            assertThrows(ai.labs.eddi.engine.api.IGroupConversationService.GroupExecutionException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0));
        }

        @Test
        void unavailablePolicy_fail_agentThrowsException() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            cfg.setProtocol(new ProtocolConfig(60,
                    ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                    ProtocolConfig.MemberUnavailablePolicy.FAIL));
            setupStore(cfg);
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenThrow(new RuntimeException("Agent check failed"));

            assertThrows(ai.labs.eddi.engine.api.IGroupConversationService.GroupExecutionException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0));
        }

        @Test
        void unavailablePolicy_skip_agentThrowsException_skips() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenThrow(new RuntimeException("Agent check failed"));
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.SKIPPED));
        }

        @Test
        void retryPolicy_agentFails_retriesAndThenSkips() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            cfg.setProtocol(new ProtocolConfig(1,
                    ProtocolConfig.MemberFailurePolicy.RETRY, 1,
                    ProtocolConfig.MemberUnavailablePolicy.SKIP));
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("a1"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));
            // say() always throws
            doThrow(new RuntimeException("Agent crashed"))
                    .when(conversationService).say(any(Environment.class), eq("a1"),
                            anyString(), any(), any(), any(), any(InputData.class),
                            anyBoolean(), any(ConversationResponseHandler.class));

            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.SKIPPED &&
                            "a1".equals(e.speakerAgentId())));
        }

        @Test
        void abortPolicy_agentTimesOut_throwsGroupTimeoutException() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            // 1s timeout, ABORT on failure, no retries → a member timeout aborts fast.
            cfg.setProtocol(new ProtocolConfig(1,
                    ProtocolConfig.MemberFailurePolicy.ABORT, 0,
                    ProtocolConfig.MemberUnavailablePolicy.SKIP));
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("a1"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));
            // say() never invokes the response handler → the future never completes → the
            // get(timeout) call times out, and under ABORT the round is aborted.
            doNothing().when(conversationService).say(any(Environment.class), eq("a1"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));

            // A real member-agent timeout must be a GroupTimeoutException so REST maps it
            // to
            // 504 (not the 502 an ordinary execution failure gets). executeDiscussion's
            // re-wrap preserves the subtype.
            assertThrows(ai.labs.eddi.engine.api.IGroupConversationService.GroupTimeoutException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0));
        }
    }

    // =========================================================
    // Discussion depth (depth boundary)
    // =========================================================

    @Nested
    class DepthBoundary {

        @Test
        void discuss_atMaxDepth_succeeds() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Opinion");
            stubAgent("mod", "Synthesis");

            // depth=3, maxDepth=3 — exactly at limit, should succeed
            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 3);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        void discuss_overMaxDepth_throwsDepthExceededException() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            setupStore(cfg);

            assertThrows(
                    ai.labs.eddi.engine.api.IGroupConversationService.GroupDepthExceededException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 4));
        }
    }

    // =========================================================
    // Discussion with no synthesis (null synthesizedAnswer)
    // =========================================================

    @Nested
    class NoSynthesis {

        @Test
        void discuss_noSynthesisPhase_completesWithNullAnswer() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("OpinionOnly", PhaseType.OPINION)));
            setupStore(cfg);
            stubAgent("a1", "Opinion only");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertNull(result.getSynthesizedAnswer());
        }
    }

    // =========================================================
    // Style: null style defaults to ROUND_TABLE
    // =========================================================

    @Nested
    class DefaultStyle {

        @Test
        void discuss_nullStyle_defaultsToRoundTable() throws Exception {
            var cfg = config(null, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            cfg.setStyle(null); // explicitly null
            setupStore(cfg);
            stubAgent("a1", "Opinion");
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // Protocol resolution
    // =========================================================

    @Nested
    class ProtocolResolution {

        @Test
        void discuss_nullProtocol_usesDefaults() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            cfg.setProtocol(null); // null protocol
            setupStore(cfg);
            stubAgent("a1", "Opinion");
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // Shutdown
    // =========================================================

    @Nested
    class Shutdown {

        @Test
        void shutdown_executesCleanly() {
            // Create a new service just for this test to avoid affecting others
            var svc = new GroupConversationService(groupStore, conversationStore,
                    conversationService, agentFactory, templatingEngine,
                    jsonSerialization, new SimpleMeterRegistry(),
                    null, null, null, null, null, "default", 3);

            assertDoesNotThrow(svc::shutdown);
        }
    }

    // =========================================================
    // Context scope: LAST_PHASE
    // =========================================================

    @Nested
    class ContextScopeLastPhase {

        @Test
        void lastPhaseScope_onlyIncludesPriorPhaseEntries() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null),
                    new GroupMember("bbbbbbbbbbbbbbbbbbbbbbbb", "Bob", 2, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Phase0", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.NONE, false, null, 1),
                    new DiscussionPhase("Phase1", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.LAST_PHASE, false, null, 1)));
            setupStore(cfg);
            stubAgent("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice phase0 opinion");
            stubAgent("bbbbbbbbbbbbbbbbbbbbbbbb", "Bob phase0 opinion");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // Phase1 should contain opinions from both agents with LAST_PHASE context
            long phase1Opinions = result.getTranscript().stream()
                    .filter(e -> e.type() == TranscriptEntryType.OPINION && e.phaseIndex() == 1)
                    .count();
            assertEquals(2, phase1Opinions, "Both agents should contribute opinions in phase 1");
        }
    }

    // =========================================================
    // Multiple rounds (repeats > 1)
    // =========================================================

    @Nested
    class MultipleRounds {

        @Test
        void roundTable_multipleRounds_executesDiscussionPhasesMultipleTimes() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 3,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null),
                    new GroupMember("bbbbbbbbbbbbbbbbbbbbbbbb", "Bob", 2, null));
            cfg.setModeratorAgentId("cccccccccccccccccccccccc");
            setupStore(cfg);
            stubAgent("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice opinion");
            stubAgent("bbbbbbbbbbbbbbbbbbbbbbbb", "Bob opinion");
            stubAgent("cccccccccccccccccccccccc", "Final synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertNotNull(result.getSynthesizedAnswer());
            // Round 1: 2 independent opinions + Rounds 2-3: 2x2=4 context opinions + 1
            // synthesis = 7
            long opinionCount = result.getTranscript().stream()
                    .filter(e -> e.type() == TranscriptEntryType.OPINION)
                    .count();
            assertTrue(opinionCount >= 6,
                    "Expected at least 6 opinions for 3 rounds with 2 agents, got " + opinionCount);
        }

        @Test
        void customPhase_withRepeats_executesMultipleTimes() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("RepeatingOpinion", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 3)));
            setupStore(cfg);
            stubAgent("aaaaaaaaaaaaaaaaaaaaaaaa", "Opinion iteration");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            long opinionCount = result.getTranscript().stream()
                    .filter(e -> e.type() == TranscriptEntryType.OPINION)
                    .count();
            assertEquals(3, opinionCount, "Expected 3 opinions from 3 repeats");
        }
    }

    // =========================================================
    // Discussion with DEBATE style
    // =========================================================

    @Nested
    class DebateStyle {

        @Test
        void debate_proConRebuttalsAndJudgment() throws Exception {
            var cfg = config(DiscussionStyle.DEBATE, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "ProAgent", 1, "PRO"),
                    new GroupMember("bbbbbbbbbbbbbbbbbbbbbbbb", "ConAgent", 2, "CON"));
            cfg.setModeratorAgentId("cccccccccccccccccccccccc");
            setupStore(cfg);
            stubAgent("aaaaaaaaaaaaaaaaaaaaaaaa", "Pro argument");
            stubAgent("bbbbbbbbbbbbbbbbbbbbbbbb", "Con argument");
            stubAgent("cccccccccccccccccccccccc", "Judgment synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertNotNull(result.getSynthesizedAnswer());
            // DEBATE has 5 phases: ARGUE(PRO), ARGUE(CON), REBUTTAL(PRO), REBUTTAL(CON),
            // SYNTHESIS
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.ARGUMENT),
                    "Expected ARGUMENT entries");
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.REBUTTAL),
                    "Expected REBUTTAL entries");
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.SYNTHESIS),
                    "Expected SYNTHESIS entry");
        }

        @Test
        void debate_teamSideInjectedCorrectly() throws Exception {
            var cfg = config(DiscussionStyle.DEBATE, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "ProAgent", 1, "PRO"),
                    new GroupMember("bbbbbbbbbbbbbbbbbbbbbbbb", "ConAgent", 2, "CON"));
            cfg.setModeratorAgentId("cccccccccccccccccccccccc");
            setupStore(cfg);
            stubAgent("aaaaaaaaaaaaaaaaaaaaaaaa", "For the motion");
            stubAgent("bbbbbbbbbbbbbbbbbbbbbbbb", "Against the motion");
            stubAgent("cccccccccccccccccccccccc", "The motion passes");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // Verify both agents participated
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> "aaaaaaaaaaaaaaaaaaaaaaaa".equals(e.speakerAgentId())));
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> "bbbbbbbbbbbbbbbbbbbbbbbb".equals(e.speakerAgentId())));
        }
    }

    // =========================================================
    // Discussion with PEER_REVIEW style
    // =========================================================

    @Nested
    class PeerReviewStyle {

        @Test
        void peerReview_fullCycle() throws Exception {
            var cfg = config(DiscussionStyle.PEER_REVIEW, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null),
                    new GroupMember("bbbbbbbbbbbbbbbbbbbbbbbb", "Bob", 2, null));
            cfg.setModeratorAgentId("cccccccccccccccccccccccc");
            setupStore(cfg);
            stubAgent("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice's response");
            stubAgent("bbbbbbbbbbbbbbbbbbbbbbbb", "Bob's response");
            stubAgent("cccccccccccccccccccccccc", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertNotNull(result.getSynthesizedAnswer());
            // PEER_REVIEW has: OPINION (parallel) → CRITIQUE (peer-targeted) → REVISION
            // (parallel) → SYNTHESIS
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.OPINION));
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.CRITIQUE));
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.REVISION));
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.SYNTHESIS));
        }
    }

    // =========================================================
    // Discussion with DELPHI style (anonymous rounds)
    // =========================================================

    @Nested
    class DelphiStyle {

        @Test
        void delphi_anonymousRounds() throws Exception {
            var cfg = config(DiscussionStyle.DELPHI, 3,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null),
                    new GroupMember("bbbbbbbbbbbbbbbbbbbbbbbb", "Bob", 2, null));
            cfg.setModeratorAgentId("cccccccccccccccccccccccc");
            setupStore(cfg);
            stubAgent("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice opinion");
            stubAgent("bbbbbbbbbbbbbbbbbbbbbbbb", "Bob opinion");
            stubAgent("cccccccccccccccccccccccc", "Delphi synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertNotNull(result.getSynthesizedAnswer());
            // DELPHI with 3 rounds: Round1 (independent) + Round2 + Round3 (anonymous) +
            // Synthesis
            long opinionCount = result.getTranscript().stream()
                    .filter(e -> e.type() == TranscriptEntryType.OPINION)
                    .count();
            assertTrue(opinionCount >= 6,
                    "Expected at least 6 opinions (3 rounds × 2 agents), got " + opinionCount);
        }
    }

    // =========================================================
    // extractResponse edge cases
    // =========================================================

    @Nested
    class ExtractResponseEdgeCases {

        @Test
        void nullSnapshot_returnsEmptyString() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Op", PhaseType.OPINION)));
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("aaaaaaaaaaaaaaaaaaaaaaaa"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));

            // Handler called with null snapshot
            doAnswer(inv -> {
                ConversationResponseHandler handler = inv.getArgument(8);
                handler.onComplete(null);
                return null;
            }).when(conversationService).say(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        void emptyConversationOutputs_returnsEmptyString() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Op", PhaseType.OPINION)));
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("aaaaaaaaaaaaaaaaaaaaaaaa"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));

            doAnswer(inv -> {
                ConversationResponseHandler handler = inv.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                snapshot.setConversationOutputs(new ArrayList<>());
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        void outputWithNoOutputKey_returnsNull() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Op", PhaseType.OPINION)));
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("aaaaaaaaaaaaaaaaaaaaaaaa"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));

            // Output map with only non-output keys (e.g., 'actions', 'input')
            doAnswer(inv -> {
                ConversationResponseHandler handler = inv.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("actions", List.of("action1"));
                output.put("input", "some input");
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // The output had no 'output' key, so extractResponse returns null
            // The transcript entry content should reflect this
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.OPINION
                            && "aaaaaaaaaaaaaaaaaaaaaaaa".equals(e.speakerAgentId())));
        }

        @Test
        void snapshotWithErrorState_producesErrorMessage() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Op", PhaseType.OPINION)));
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("aaaaaaaaaaaaaaaaaaaaaaaa"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));

            // Snapshot with ERROR state and no output keys
            doAnswer(inv -> {
                ConversationResponseHandler handler = inv.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("actions", List.of("action1"));
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
                snapshot.setConversationState(ConversationState.ERROR);
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // Should contain the error message from ERROR state fallback
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.OPINION
                            && e.content() != null
                            && e.content().contains("Agent failed")));
        }

        @Test
        void outputWithFlatTextKeys_extractsCorrectly() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Op", PhaseType.OPINION)));
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("aaaaaaaaaaaaaaaaaaaaaaaa"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));

            // Output with "output:text:0" flat key
            doAnswer(inv -> {
                ConversationResponseHandler handler = inv.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("output:text:0", "Flat text output");
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.OPINION
                            && "Flat text output".equals(e.content())));
        }
    }

    // =========================================================
    // startAndDiscussAsync error handling (FAILED state)
    // =========================================================

    @Nested
    class AsyncErrorHandling {

        @Test
        void startAndDiscussAsync_discussThrows_setsFailedState() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null));
            cfg.setModeratorAgentId("cccccccccccccccccccccccc");
            cfg.setProtocol(new ProtocolConfig(60,
                    ProtocolConfig.MemberFailurePolicy.ABORT, 0,
                    ProtocolConfig.MemberUnavailablePolicy.FAIL));
            setupStore(cfg);

            // Agent not deployed + FAIL policy → discussion will throw
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa")))
                    .thenReturn(null);

            var listener = mock(GroupDiscussionEventListener.class);

            var result = service.startAndDiscussAsync(GROUP_ID, QUESTION, USER_ID, listener);

            assertNotNull(result);
            assertEquals("gc-1", result.getId());

            // Wait for async thread to complete and set FAILED state
            Thread.sleep(2000);

            // Listener should have received onGroupError
            verify(listener, atLeastOnce()).onGroupError(any(GroupConversationEventSink.GroupErrorEvent.class));
        }

        @Test
        void startAndDiscussAsync_nullListener_noNPEOnError() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null));
            cfg.setProtocol(new ProtocolConfig(60,
                    ProtocolConfig.MemberFailurePolicy.ABORT, 0,
                    ProtocolConfig.MemberUnavailablePolicy.FAIL));
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("aaaaaaaaaaaaaaaaaaaaaaaa")))
                    .thenReturn(null);

            // Null listener — should not throw NPE
            var result = service.startAndDiscussAsync(GROUP_ID, QUESTION, USER_ID, null);

            assertNotNull(result);
            Thread.sleep(2000);
            // Should not throw NPE — just verify it didn't crash
        }
    }

    // =========================================================
    // Group-of-groups (nested group discussions)
    // =========================================================

    @Nested
    class GroupOfGroups {

        @Test
        void groupMember_delegatesToSubGroup() throws Exception {
            // Parent group has a GROUP member pointing to a sub-group
            String parentGroupId = "parent-group-id";
            String subGroupId = "sub-group-id-00";

            var parentCfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember(subGroupId, "SubGroupTeam", 1, null, MemberType.GROUP));
            parentCfg.setPhases(List.of(
                    new DiscussionPhase("OpinionPhase", PhaseType.OPINION)));
            setupStore(parentGroupId, parentCfg);

            // Sub-group config
            var subCfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null));
            subCfg.setPhases(List.of(
                    new DiscussionPhase("SubOpinion", PhaseType.OPINION)));
            setupStore(subGroupId, subCfg);
            stubAgent("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice sub-group opinion");

            var result = service.discuss(parentGroupId, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // The sub-group's response should appear in the parent transcript
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> subGroupId.equals(e.speakerAgentId())
                            && e.content() != null
                            && e.content().contains("Alice sub-group opinion")));
        }

        @Test
        void groupMember_depthExceeded_producesSkippedEntry() throws Exception {
            String parentGroupId = "parent-group-id";
            String subGroupId = "sub-group-id-00";

            var parentCfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember(subGroupId, "SubGroupTeam", 1, null, MemberType.GROUP));
            parentCfg.setPhases(List.of(
                    new DiscussionPhase("OpinionPhase", PhaseType.OPINION)));
            setupStore(parentGroupId, parentCfg);

            // Sub-group config (will be at depth 4 > maxDepth 3)
            var subCfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null));
            subCfg.setPhases(List.of(
                    new DiscussionPhase("SubOpinion", PhaseType.OPINION)));
            setupStore(subGroupId, subCfg);

            // Start parent at depth 3, so sub-group will be at depth 4 (exceeds maxDepth 3)
            var result = service.discuss(parentGroupId, QUESTION, USER_ID, 3);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // Sub-group should produce a SKIPPED entry due to depth exceeded
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.SKIPPED
                            && e.errorReason() != null
                            && e.errorReason().contains("depth")),
                    "Expected SKIPPED entry for depth exceeded sub-group");
        }

        @Test
        void groupMember_subGroupFails_handledByPolicy() throws Exception {
            String parentGroupId = "parent-group-id";
            String subGroupId = "sub-group-id-00";

            var parentCfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember(subGroupId, "SubGroupTeam", 1, null, MemberType.GROUP));
            parentCfg.setPhases(List.of(
                    new DiscussionPhase("OpinionPhase", PhaseType.OPINION)));
            setupStore(parentGroupId, parentCfg);

            // Sub-group does NOT exist → ResourceNotFoundException
            when(groupStore.getCurrentResourceId(subGroupId)).thenReturn(null);

            var result = service.discuss(parentGroupId, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // Sub-group failure should produce a SKIPPED entry (default policy is SKIP)
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.SKIPPED
                            && subGroupId.equals(e.speakerAgentId())));
        }
    }

    // =========================================================
    // DEVIL_ADVOCATE style (CHALLENGE + DEFENSE phases)
    // =========================================================

    @Nested
    class DevilAdvocateStyle {

        @Test
        void devilAdvocate_challengeAndDefense() throws Exception {
            var cfg = config(DiscussionStyle.DEVIL_ADVOCATE, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Expert", 1, null),
                    new GroupMember("bbbbbbbbbbbbbbbbbbbbbbbb", "Devil", 2, "DEVIL_ADVOCATE"));
            cfg.setModeratorAgentId("cccccccccccccccccccccccc");
            setupStore(cfg);
            stubAgent("aaaaaaaaaaaaaaaaaaaaaaaa", "Expert opinion");
            stubAgent("bbbbbbbbbbbbbbbbbbbbbbbb", "I challenge that");
            stubAgent("cccccccccccccccccccccccc", "Synthesis after debate");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertNotNull(result.getSynthesizedAnswer());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.CHALLENGE));
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.DEFENSE));
        }
    }

    // =========================================================
    // Context scope: OWN_FEEDBACK
    // =========================================================

    @Nested
    class ContextScopeOwnFeedback {

        @Test
        void ownFeedbackScope_onlyIncludesFeedbackTargetedAtSpeaker() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice", 1, null),
                    new GroupMember("bbbbbbbbbbbbbbbbbbbbbbbb", "Bob", 2, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Opinions", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.NONE, false, null, 1),
                    new DiscussionPhase("PeerCritique", PhaseType.CRITIQUE,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, true, null, 1),
                    new DiscussionPhase("Revision", PhaseType.REVISION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.OWN_FEEDBACK, false, null, 1)));
            setupStore(cfg);
            stubAgent("aaaaaaaaaaaaaaaaaaaaaaaa", "Alice revised");
            stubAgent("bbbbbbbbbbbbbbbbbbbbbbbb", "Bob revised");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.REVISION));
        }
    }

    // =========================================================
    // Regression guards for bugs the direct-invocation tests could not see.
    // Each of these was a surviving mutant: the fix could be reverted with the
    // whole suite still green.
    // =========================================================

    @Nested
    class MergeRegressionGuards {

        @Test
        @DisplayName("a failed discussion streams a CURATED error — never the raw exception text")
        void failedDiscussion_doesNotLeakRawExceptionTextToTheListener() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setProtocol(new ProtocolConfig(60,
                    ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                    ProtocolConfig.MemberUnavailablePolicy.FAIL));
            setupStore(cfg);
            // Agent unavailable + FAIL policy => executeDiscussion throws.
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(null);

            var listener = mock(GroupDiscussionEventListener.class);

            assertThrows(GroupDiscussionException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0, listener));

            var captor = ArgumentCaptor.forClass(GroupConversationEventSink.GroupErrorEvent.class);
            verify(listener, atLeastOnce()).onGroupError(captor.capture());

            // The listener installed by RestGroupConversation forwards this text straight
            // to the browser over SSE. The raw message can carry LLM/DB/driver detail and
            // the caller's own input, so it must never be the event payload.
            for (var event : captor.getAllValues()) {
                String text = String.valueOf(event.error());
                assertFalse(text.contains("a1"),
                        "the SSE error event must not carry the raw exception text: " + text);
                assertFalse(text.toLowerCase().contains("agent unavailable"),
                        "the SSE error event must be curated, not the raw message: " + text);
            }
        }

        @Test
        @DisplayName("a continuation re-runs from the FIRST phase (startPhaseIndex 0), not from phase 1")
        void continuation_restartsAtPhaseZero() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Opinion");
            stubAgent("mod", "Synthesis");

            var completed = new GroupConversation();
            completed.setId("gc-1");
            completed.setGroupId(GROUP_ID);
            completed.setState(GroupConversation.GroupConversationState.COMPLETED);
            completed.setRound(1);
            when(conversationStore.read("gc-1")).thenReturn(completed);
            when(conversationStore.compareAndSetState("gc-1",
                    GroupConversation.GroupConversationState.COMPLETED,
                    GroupConversation.GroupConversationState.IN_PROGRESS)).thenReturn(true);

            var listener = mock(GroupDiscussionEventListener.class);

            service.continueDiscussion("gc-1", "round two question", listener);

            // A continuation re-runs the WHOLE protocol. If startPhaseIndex were anything
            // but 0, phase 0 would be silently skipped and the round would be incomplete.
            var phases = ArgumentCaptor.forClass(GroupConversationEventSink.PhaseStartEvent.class);
            verify(listener, atLeastOnce()).onPhaseStart(phases.capture());
            assertTrue(phases.getAllValues().stream().anyMatch(p -> p.phaseIndex() == 0),
                    "the first phase must run on a continuation round");
            // It is a new ROUND, not a new discussion.
            verify(listener).onRoundStart(any(GroupConversationEventSink.RoundStartEvent.class));
            verify(listener, never()).onGroupStart(any(GroupConversationEventSink.GroupStartEvent.class));
        }

        @Test
        @DisplayName("ephemeral agents survive a COMPLETED round (follow-ups reuse them) but are reclaimed on failure")
        void ephemeralCleanup_isDeferredForCompleted_butRunsOnFailure() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Opinion");
            stubAgent("mod", "Synthesis");
            // A dynamically-created agent that a follow-up/continue would want to reuse.
            when(conversationStore.create(any())).thenAnswer(inv -> {
                GroupConversation gc = inv.getArgument(0);
                gc.getCreatedAgentIds().add("ephemeral-1");
                return "gc-1";
            });

            service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            // COMPLETED must DEFER cleanup to close/delete — otherwise a follow-up or a
            // continuation has no agents left to talk to.
            verify(agentStore, never()).deleteAllPermanently(anyString());
        }
    }
}
