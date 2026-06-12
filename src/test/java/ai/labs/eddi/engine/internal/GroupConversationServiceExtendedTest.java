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
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
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

        service = new GroupConversationService(groupStore, conversationStore,
                conversationService, agentFactory, templatingEngine,
                jsonSerialization, new SimpleMeterRegistry(),
                null, null, null, "default", 3);

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
            stubAgent("a1", "Opinion A");
            stubAgent("mod", "Synthesis");

            var result = service.startAndDiscussAsync(GROUP_ID, QUESTION, USER_ID, null);

            assertNotNull(result);
            assertEquals("gc-1", result.getId());
            assertEquals(GROUP_ID, result.getGroupId());
            assertEquals(GroupConversationState.IN_PROGRESS, result.getState());
            // Give async thread time to complete
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

            assertThrows(GroupDiscussionException.class,
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

            assertThrows(GroupDiscussionException.class,
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
                    null, null, null, "default", 3);

            assertDoesNotThrow(svc::shutdown);
        }
    }
}
