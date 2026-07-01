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
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDepthExceededException;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link GroupConversationService}. Tests public
 * API methods directly and private helper methods via reflection.
 *
 * @author tests
 */
class GroupConversationServiceTest {

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

    private GroupConversationService service;

    private static final int MAX_DEPTH = 3;
    private static final String DEFAULT_TENANT = "default";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                nonceCacheService, DEFAULT_TENANT, MAX_DEPTH);
    }

    // =================================================================
    // discuss() tests
    // =================================================================

    @Nested
    class DiscussTests {

        @Test
        void discuss_nullGroupId_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.discuss(null, "question", "user1", 0));
        }

        @Test
        void discuss_depthExceedsMaxDepth_throwsGroupDepthExceededException() {
            assertThrows(GroupDepthExceededException.class,
                    () -> service.discuss("group1", "question", "user1", MAX_DEPTH + 1));
        }

        @Test
        void discuss_getCurrentResourceIdReturnsNull_throwsResourceNotFoundException() throws Exception {
            doReturn(null).when(groupStore).getCurrentResourceId("group1");

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> service.discuss("group1", "question", "user1", 0));
        }

        @Test
        void discuss_configIsNull_throwsResourceNotFoundException() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            doReturn(1).when(resourceId).getVersion();
            doReturn(resourceId).when(groupStore).getCurrentResourceId("group1");
            doReturn(null).when(groupStore).read("group1", 1);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> service.discuss("group1", "question", "user1", 0));
        }

        @Test
        void discuss_noPhases_throwsException() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            doReturn(1).when(resourceId).getVersion();
            doReturn(resourceId).when(groupStore).getCurrentResourceId("group1");

            var config = new AgentGroupConfiguration();
            // Empty phases list — code will fail before reaching discussion logic
            config.setPhases(List.of());
            doReturn(config).when(groupStore).read("group1", 1);

            // Empty phases causes NPE or GroupDiscussionException depending on code path
            assertThrows(Exception.class,
                    () -> service.discuss("group1", "question", "user1", 0));
        }
    }

    // =================================================================
    // startAndDiscussAsync() tests
    // =================================================================

    @Nested
    class StartAndDiscussAsyncTests {

        @Test
        void startAndDiscussAsync_nullGroupId_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.startAndDiscussAsync(null, "question", "user1", null));
        }

        @Test
        void startAndDiscussAsync_groupNotFound_throwsResourceNotFoundException() throws Exception {
            doReturn(null).when(groupStore).getCurrentResourceId("group1");

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> service.startAndDiscussAsync("group1", "question", "user1", null));
        }
    }

    // =================================================================
    // readGroupConversation() test
    // =================================================================

    @Nested
    class ReadGroupConversationTests {

        @Test
        void readGroupConversation_delegatesToStore() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            doReturn(gc).when(conversationStore).read("gc-1");

            GroupConversation result = service.readGroupConversation("gc-1");

            assertSame(gc, result);
            verify(conversationStore).read("gc-1");
        }
    }

    // =================================================================
    // deleteGroupConversation() tests
    // =================================================================

    @Nested
    class DeleteGroupConversationTests {

        @Test
        void deleteGroupConversation_endsMemberConversationsAndDeletes() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setMemberConversationIds(new LinkedHashMap<>(Map.of(
                    "agent1", "conv1",
                    "agent2", "conv2")));
            doReturn(gc).when(conversationStore).read("gc-1");

            service.deleteGroupConversation("gc-1");

            verify(conversationService).endConversation("conv1");
            verify(conversationService).endConversation("conv2");
            verify(conversationStore).delete("gc-1");
        }

        @Test
        void deleteGroupConversation_handlesNotFoundGracefully() throws Exception {
            doThrow(new IResourceStore.ResourceNotFoundException("not found"))
                    .when(conversationStore).read("gc-missing");

            // Should not throw
            assertDoesNotThrow(() -> service.deleteGroupConversation("gc-missing"));
        }

        @Test
        void deleteGroupConversation_endConversationFailureDoesNotPreventDeletion() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setMemberConversationIds(new LinkedHashMap<>(Map.of(
                    "agent1", "conv1")));
            doReturn(gc).when(conversationStore).read("gc-1");
            doThrow(new RuntimeException("end failed")).when(conversationService).endConversation("conv1");

            // Should still delete from store despite endConversation failure
            assertDoesNotThrow(() -> service.deleteGroupConversation("gc-1"));
            verify(conversationStore).delete("gc-1");
        }
    }

    // =================================================================
    // listGroupConversations() test
    // =================================================================

    @Nested
    class ListGroupConversationsTests {

        @Test
        void listGroupConversations_delegatesToStore() throws Exception {
            var list = List.of(new GroupConversation(), new GroupConversation());
            doReturn(list).when(conversationStore).listByGroupId("group1", 0, 10);

            List<GroupConversation> result = service.listGroupConversations("group1", 0, 10);

            assertSame(list, result);
            verify(conversationStore).listByGroupId("group1", 0, 10);
        }
    }

    // =================================================================
    // resolveParticipants (private) — tested via reflection
    // =================================================================

    @Nested
    class ResolveParticipantsTests {

        private Method resolveParticipantsMethod;

        @BeforeEach
        void setUp() throws Exception {
            resolveParticipantsMethod = GroupConversationService.class.getDeclaredMethod(
                    "resolveParticipants", DiscussionPhase.class, List.class, String.class);
            resolveParticipantsMethod.setAccessible(true);
        }

        @SuppressWarnings("unchecked")
        private List<GroupMember> invoke(DiscussionPhase phase, List<GroupMember> members, String moderatorId)
                throws Exception {
            return (List<GroupMember>) resolveParticipantsMethod.invoke(service, phase, members, moderatorId);
        }

        @Test
        void moderator_withValidModerator_returnsSingleModeratorMember() throws Exception {
            var phase = new DiscussionPhase("Synth", PhaseType.SYNTHESIS, "MODERATOR",
                    AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL,
                    false, null, 1);
            var members = List.of(
                    new GroupMember("a1", "Alice", 1, "MEMBER"),
                    new GroupMember("a2", "Bob", 2, "MEMBER"));

            List<GroupMember> result = invoke(phase, members, "mod-agent");

            assertEquals(1, result.size());
            assertEquals("mod-agent", result.get(0).agentId());
            assertEquals("Moderator", result.get(0).displayName());
        }

        @Test
        void moderator_withNullModerator_fallsBackToAll() throws Exception {
            var phase = new DiscussionPhase("Synth", PhaseType.SYNTHESIS, "MODERATOR",
                    AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL,
                    false, null, 1);
            var members = List.of(
                    new GroupMember("a1", "Alice", 1, "MEMBER"),
                    new GroupMember("a2", "Bob", 2, "MEMBER"));

            List<GroupMember> result = invoke(phase, members, null);

            assertEquals(2, result.size());
        }

        @Test
        void moderator_withBlankModerator_fallsBackToAll() throws Exception {
            var phase = new DiscussionPhase("Synth", PhaseType.SYNTHESIS, "MODERATOR",
                    AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL,
                    false, null, 1);
            var members = List.of(
                    new GroupMember("a1", "Alice", 1, "MEMBER"));

            List<GroupMember> result = invoke(phase, members, "   ");

            assertEquals(1, result.size());
            assertEquals("a1", result.get(0).agentId());
        }

        @Test
        void role_filtersMatchingMembers() throws Exception {
            var phase = new DiscussionPhase("Challenge", PhaseType.CHALLENGE, "ROLE:DEVIL",
                    AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL,
                    false, null, 1);
            var members = List.of(
                    new GroupMember("a1", "Alice", 1, "DEVIL"),
                    new GroupMember("a2", "Bob", 2, "MEMBER"),
                    new GroupMember("a3", "Charlie", 3, "DEVIL"));

            List<GroupMember> result = invoke(phase, members, null);

            assertEquals(2, result.size());
            assertEquals("a1", result.get(0).agentId());
            assertEquals("a3", result.get(1).agentId());
        }

        @Test
        void role_noMatchingRole_fallsBackToAll() throws Exception {
            var phase = new DiscussionPhase("Phase", PhaseType.OPINION, "ROLE:NONEXISTENT",
                    AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL,
                    false, null, 1);
            var members = List.of(
                    new GroupMember("a1", "Alice", 1, "MEMBER"));

            List<GroupMember> result = invoke(phase, members, null);

            assertEquals(1, result.size());
        }

        @Test
        void all_sortsBySpeakingOrder() throws Exception {
            var phase = new DiscussionPhase("Opinion", PhaseType.OPINION, "ALL",
                    AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL,
                    false, null, 1);
            var members = List.of(
                    new GroupMember("a3", "Charlie", 3, null),
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("a2", "Bob", 2, null));

            List<GroupMember> result = invoke(phase, members, null);

            assertEquals("a1", result.get(0).agentId());
            assertEquals("a2", result.get(1).agentId());
            assertEquals("a3", result.get(2).agentId());
        }

        @Test
        void all_nullSpeakingOrderSortsLast() throws Exception {
            var phase = new DiscussionPhase("Opinion", PhaseType.OPINION);
            var members = List.of(
                    new GroupMember("a2", "Bob", null, null),
                    new GroupMember("a1", "Alice", 1, null));

            List<GroupMember> result = invoke(phase, members, null);

            assertEquals("a1", result.get(0).agentId());
            assertEquals("a2", result.get(1).agentId());
        }

        @Test
        void nullParticipants_defaultsToAll() throws Exception {
            var phase = new DiscussionPhase("Opinion", PhaseType.OPINION, null,
                    AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL,
                    false, null, 1);
            var members = List.of(
                    new GroupMember("a1", "Alice", 1, null));

            List<GroupMember> result = invoke(phase, members, null);

            assertEquals(1, result.size());
            assertEquals("a1", result.get(0).agentId());
        }
    }

    // =================================================================
    // mapPhaseToEntryType (private) — tested via reflection
    // =================================================================

    @Nested
    class MapPhaseToEntryTypeTests {

        private Method mapPhaseToEntryTypeMethod;

        @BeforeEach
        void setUp() throws Exception {
            mapPhaseToEntryTypeMethod = GroupConversationService.class.getDeclaredMethod(
                    "mapPhaseToEntryType", PhaseType.class);
            mapPhaseToEntryTypeMethod.setAccessible(true);
        }

        private TranscriptEntryType invoke(PhaseType type) throws Exception {
            return (TranscriptEntryType) mapPhaseToEntryTypeMethod.invoke(service, type);
        }

        @Test
        void opinion_mapsToOpinion() throws Exception {
            assertEquals(TranscriptEntryType.OPINION, invoke(PhaseType.OPINION));
        }

        @Test
        void critique_mapsToCritique() throws Exception {
            assertEquals(TranscriptEntryType.CRITIQUE, invoke(PhaseType.CRITIQUE));
        }

        @Test
        void revision_mapsToRevision() throws Exception {
            assertEquals(TranscriptEntryType.REVISION, invoke(PhaseType.REVISION));
        }

        @Test
        void challenge_mapsToChallenge() throws Exception {
            assertEquals(TranscriptEntryType.CHALLENGE, invoke(PhaseType.CHALLENGE));
        }

        @Test
        void defense_mapsToDefense() throws Exception {
            assertEquals(TranscriptEntryType.DEFENSE, invoke(PhaseType.DEFENSE));
        }

        @Test
        void argue_mapsToArgument() throws Exception {
            assertEquals(TranscriptEntryType.ARGUMENT, invoke(PhaseType.ARGUE));
        }

        @Test
        void rebuttal_mapsToRebuttal() throws Exception {
            assertEquals(TranscriptEntryType.REBUTTAL, invoke(PhaseType.REBUTTAL));
        }

        @Test
        void synthesis_mapsToSynthesis() throws Exception {
            assertEquals(TranscriptEntryType.SYNTHESIS, invoke(PhaseType.SYNTHESIS));
        }
    }

    // =================================================================
    // errorEntry (private) — tested via reflection
    // =================================================================

    @Nested
    class ErrorEntryTests {

        private Method errorEntryMethod;

        @BeforeEach
        void setUp() throws Exception {
            errorEntryMethod = GroupConversationService.class.getDeclaredMethod(
                    "errorEntry", GroupMember.class, int.class, DiscussionPhase.class, String.class);
            errorEntryMethod.setAccessible(true);
        }

        private TranscriptEntry invoke(GroupMember member, int phaseIdx, DiscussionPhase phase, String message)
                throws Exception {
            return (TranscriptEntry) errorEntryMethod.invoke(service, member, phaseIdx, phase, message);
        }

        @Test
        void nullMember_usesUnknownDefaults() throws Exception {
            var phase = new DiscussionPhase("TestPhase", PhaseType.OPINION);

            TranscriptEntry entry = invoke(null, 0, phase, "some error");

            assertEquals("unknown", entry.speakerAgentId());
            assertEquals("Unknown", entry.speakerDisplayName());
            assertNull(entry.content());
            assertEquals(TranscriptEntryType.ERROR, entry.type());
            assertEquals("some error", entry.errorReason());
            assertEquals(0, entry.phaseIndex());
            assertEquals("TestPhase", entry.phaseName());
        }

        @Test
        void validMember_usesAgentIdAndDisplayName() throws Exception {
            var member = new GroupMember("agent-x", "Agent X", 1, "MEMBER");
            var phase = new DiscussionPhase("Phase1", PhaseType.CRITIQUE);

            TranscriptEntry entry = invoke(member, 2, phase, "timeout occurred");

            assertEquals("agent-x", entry.speakerAgentId());
            assertEquals("Agent X", entry.speakerDisplayName());
            assertNull(entry.content());
            assertEquals(TranscriptEntryType.ERROR, entry.type());
            assertEquals("timeout occurred", entry.errorReason());
            assertEquals(2, entry.phaseIndex());
        }
    }

    // =================================================================
    // extractResponse (private) — tested via reflection
    // =================================================================

    @Nested
    class ExtractResponseTests {

        private Method extractResponseMethod;

        @BeforeEach
        void setUp() throws Exception {
            extractResponseMethod = GroupConversationService.class.getDeclaredMethod(
                    "extractResponse", SimpleConversationMemorySnapshot.class);
            extractResponseMethod.setAccessible(true);
        }

        private String invoke(SimpleConversationMemorySnapshot snapshot) throws Exception {
            return (String) extractResponseMethod.invoke(service, snapshot);
        }

        @Test
        void nullSnapshot_returnsEmptyString() throws Exception {
            assertEquals("", invoke(null));
        }

        @Test
        void emptyOutputs_returnsEmptyString() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationOutputs(new LinkedList<>());

            assertEquals("", invoke(snapshot));
        }

        @Test
        void nullLastOutput_returnsEmptyString() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            var outputs = new LinkedList<ConversationOutput>();
            outputs.add(null);
            snapshot.setConversationOutputs(outputs);

            assertEquals("", invoke(snapshot));
        }

        @Test
        void nestedOutputArrayWithStrings_concatenatesWithNewlines() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            output.put("output", List.of("Hello", "World"));
            snapshot.setConversationOutputs(new LinkedList<>(List.of(output)));

            assertEquals("Hello\nWorld", invoke(snapshot));
        }

        @Test
        void nestedOutputArrayWithMapItems_extractsTextKey() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            output.put("output", List.of(Map.of("text", "from map")));
            snapshot.setConversationOutputs(new LinkedList<>(List.of(output)));

            assertEquals("from map", invoke(snapshot));
        }

        @Test
        void flatOutputTextKeys_extractsValues() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            // No "output" key — use flat keys
            output.put("output:text:greeting", "Hi there");
            snapshot.setConversationOutputs(new LinkedList<>(List.of(output)));

            assertEquals("Hi there", invoke(snapshot));
        }

        @Test
        void flatOutputTextKeys_withListValue() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            output.put("output:text:items", List.of("item1", "item2"));
            snapshot.setConversationOutputs(new LinkedList<>(List.of(output)));

            assertEquals("item1\nitem2", invoke(snapshot));
        }

        @Test
        void flatOutputTextKeys_withMapValue() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            output.put("output:text:single", Map.of("text", "mapped-value"));
            snapshot.setConversationOutputs(new LinkedList<>(List.of(output)));

            assertEquals("mapped-value", invoke(snapshot));
        }

        @Test
        void noOutputKeys_returnsEmptyString() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            // Only pipeline metadata keys — no "output" or "reply" keys
            output.put("actions", List.of("action1"));
            output.put("input", "some input");
            snapshot.setConversationOutputs(new LinkedList<>(List.of(output)));

            // ConversationOutputExtractor returns null for metadata-only;
            // GCS wrapper converts null → "" for backward compat
            assertEquals("", invoke(snapshot));
        }

        @Test
        void hasOutputKeyButNoTexts_fallsBackToToString() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            // Has an "output" key prefix but doesn't yield any text via known formats
            output.put("outputUnknown", "value"); // Doesn't match the patterns
            // But we need a key starting with "output" to pass the hasAnyOutput check
            output.put("output-status", "done");
            snapshot.setConversationOutputs(new LinkedList<>(List.of(output)));

            // ConversationOutputExtractor uses toString() fallback (no
            // jsonSerialization dependency). Just verify non-null and
            // contains output key content.
            String result = invoke(snapshot);
            assertNotNull(result, "Should fall back to toString()");
            assertTrue(result.contains("output-status"), "Fallback should include output key");
            assertTrue(result.contains("done"), "Fallback should include value");
        }

        @Test
        void multipleOutputs_usesLastOutput() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            var first = new ConversationOutput();
            first.put("output", List.of("first response"));
            var second = new ConversationOutput();
            second.put("output", List.of("second response"));
            snapshot.setConversationOutputs(new LinkedList<>(List.of(first, second)));

            assertEquals("second response", invoke(snapshot));
        }
    }

    // =================================================================
    // buildPlainTextFallback (private) — tested via reflection
    // =================================================================

    @Nested
    class BuildPlainTextFallbackTests {

        private Method buildPlainTextFallbackMethod;

        @BeforeEach
        void setUp() throws Exception {
            buildPlainTextFallbackMethod = GroupConversationService.class.getDeclaredMethod(
                    "buildPlainTextFallback", DiscussionPhase.class, GroupMember.class,
                    String.class, List.class);
            buildPlainTextFallbackMethod.setAccessible(true);
        }

        @Test
        void buildPlainTextFallback_containsPhaseNameQuestionAndSpeaker() throws Exception {
            var phase = new DiscussionPhase("Initial Opinions", PhaseType.OPINION);
            var speaker = new GroupMember("a1", "Alice", 1, "MEMBER");
            var transcript = new ArrayList<TranscriptEntry>();

            String result = (String) buildPlainTextFallbackMethod.invoke(
                    service, phase, speaker, "What is AI?", transcript);

            assertTrue(result.contains("Initial Opinions"));
            assertTrue(result.contains("What is AI?"));
            assertTrue(result.contains("Alice"));
            assertTrue(result.contains("please contribute"));
        }
    }

    // =================================================================
    // failConversation (private) — tested via reflection
    // =================================================================

    @Nested
    class FailConversationTests {

        private Method failConversationMethod;

        @BeforeEach
        void setUp() throws Exception {
            failConversationMethod = GroupConversationService.class.getDeclaredMethod(
                    "failConversation", GroupConversation.class);
            failConversationMethod.setAccessible(true);
        }

        @Test
        void failConversation_setsStateToFailed() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setState(GroupConversationState.IN_PROGRESS);

            failConversationMethod.invoke(service, gc);

            assertEquals(GroupConversationState.FAILED, gc.getState());
            assertNotNull(gc.getLastModified());
            verify(conversationStore).update(gc);
        }

        @Test
        void failConversation_handlesUpdateException() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setState(GroupConversationState.IN_PROGRESS);
            doThrow(new IResourceStore.ResourceStoreException("DB error"))
                    .when(conversationStore).update(gc);

            // Should not throw — logs warning instead
            assertDoesNotThrow(() -> failConversationMethod.invoke(service, gc));

            assertEquals(GroupConversationState.FAILED, gc.getState());
        }
    }
}
