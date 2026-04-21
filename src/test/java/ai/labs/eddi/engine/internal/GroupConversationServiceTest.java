package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.*;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDepthExceededException;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GroupConversationService} — the phase-based orchestration
 * engine. Covers the full discuss() flow, all 5 discussion styles,
 * group-of-groups recursion, error handling, and lifecycle operations.
 */
class GroupConversationServiceTest {

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

        service = new GroupConversationService(groupStore, conversationStore, conversationService, agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), 3);

        when(conversationStore.create(any())).thenReturn("gc-1");

        // jsonSerialization: extractResponse calls serialize() on ConversationOutput
        lenient().when(jsonSerialization.serialize(any())).thenAnswer(inv -> inv.getArgument(0).toString());

        // Template engine: pass through (strip to first 80 chars)
        lenient().when(templatingEngine.processTemplate(anyString(), any(), any())).thenAnswer(inv -> {
            String tmpl = inv.getArgument(0, String.class);
            return tmpl.length() > 80 ? tmpl.substring(0, 80) : tmpl;
        });
    }

    // --- Helpers ---

    private AgentGroupConfiguration config(DiscussionStyle style, int rounds, GroupMember... members) {
        var c = new AgentGroupConfiguration();
        c.setName("Test Group");
        c.setMembers(List.of(members));
        c.setStyle(style);
        c.setMaxRounds(rounds);
        c.setProtocol(new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 2, ProtocolConfig.MemberUnavailablePolicy.SKIP));
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

    /**
     * Stubs an agent so it responds with the given text. Sets up both agentFactory
     * and conversationService mocks.
     */
    private void stubAgent(String agentId, String response) throws Exception {
        when(agentFactory.getLatestReadyAgent(any(Environment.class), eq(agentId))).thenReturn(mock(IAgent.class));

        when(conversationService.startConversation(any(Environment.class), eq(agentId), anyString(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-" + agentId, null));

        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(8);
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            output.put("output", List.of(response));
            snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(Environment.class), eq(agentId), anyString(), any(), any(), any(), any(InputData.class), anyBoolean(),
                any(ConversationResponseHandler.class));
    }

    // =========================================================
    // discuss() — Main orchestration flow
    // =========================================================

    @Nested
    class MainFlow {

        @Test
        void roundTable_producesTranscriptAndCompletes() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1, new GroupMember("a1", "Alice", 1, null), new GroupMember("a2", "Bob", 2, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Opinion A");
            stubAgent("a2", "Opinion B");
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.QUESTION));
            assertTrue(result.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.OPINION));
            assertTrue(result.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.SYNTHESIS));
            verify(conversationStore).create(any());
            verify(conversationStore, atLeast(2)).update(any());
        }

        @Test
        void synthesizedAnswer_isExtracted() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1, new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Opinion");
            stubAgent("mod", "Final synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertNotNull(result.getSynthesizedAnswer());
        }

        @Test
        void depthExceeded_throws() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1, new GroupMember("a1", "Alice", 1, null));
            setupStore(cfg);

            assertThrows(GroupDepthExceededException.class, () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 4));
        }

        @Test
        void nullConfig_throws() throws Exception {
            var rid = mock(IResourceStore.IResourceId.class);
            when(rid.getVersion()).thenReturn(1);
            when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(rid);
            when(groupStore.read(GROUP_ID, 1)).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class, () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0));
        }

        @Test
        void unavailableAgent_skipped_continuesDiscussion() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1, new GroupMember("a1", "Alice", 1, null), new GroupMember("a2", "Bob", 2, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Opinion A");
            // a2 is not deployed
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a2"))).thenReturn(null);
            stubAgent("mod", "Partial synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.SKIPPED && "a2".equals(e.speakerAgentId())));
        }
    }

    // =========================================================
    // Discussion Styles
    // =========================================================

    @Nested
    class Styles {

        @Test
        void peerReview_generatesCritiquesAndRevisions() throws Exception {
            var cfg = config(DiscussionStyle.PEER_REVIEW, 1, new GroupMember("a1", "Alice", 1, null), new GroupMember("a2", "Bob", 2, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Alice response");
            stubAgent("a2", "Bob response");
            stubAgent("mod", "Moderator synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            long critiques = result.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.CRITIQUE).count();
            long revisions = result.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.REVISION).count();
            assertTrue(critiques >= 2, "Expected >=2 critiques, got " + critiques);
            assertTrue(revisions >= 2, "Expected >=2 revisions, got " + revisions);
        }

        @Test
        void devilAdvocate_producesChallenges() throws Exception {
            var cfg = config(DiscussionStyle.DEVIL_ADVOCATE, 1, new GroupMember("a1", "Optimist", 1, null),
                    new GroupMember("da", "Devil", 2, "DEVIL_ADVOCATE"));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Positive view");
            stubAgent("da", "I disagree because...");
            stubAgent("mod", "Balanced conclusion");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertTrue(result.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.CHALLENGE));
        }

        @Test
        void debate_producesArgumentsAndRebuttals() throws Exception {
            var cfg = config(DiscussionStyle.DEBATE, 1, new GroupMember("pro", "Pro", 1, "PRO"), new GroupMember("con", "Con", 2, "CON"));
            cfg.setModeratorAgentId("judge");
            setupStore(cfg);
            stubAgent("pro", "Pro argument");
            stubAgent("con", "Con argument");
            stubAgent("judge", "Verdict");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            long args = result.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.ARGUMENT).count();
            assertTrue(args >= 2, "Expected >=2 arguments, got " + args);
        }

        @Test
        void customPhases_takePriorityOverStyle() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1, new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            cfg.setPhases(List.of(new DiscussionPhase("CustomOpinion", PhaseType.OPINION),
                    new DiscussionPhase("CustomSynth", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1)));
            setupStore(cfg);
            stubAgent("a1", "Custom opinion");
            stubAgent("mod", "Custom synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertTrue(result.getTranscript().stream().anyMatch(e -> "CustomOpinion".equals(e.phaseName())));
            assertTrue(result.getTranscript().stream().anyMatch(e -> "CustomSynth".equals(e.phaseName())));
        }
    }

    // =========================================================
    // Group-of-Groups
    // =========================================================

    @Nested
    class NestedGroups {

        @Test
        void groupMember_delegatesToSubGroup() throws Exception {
            var parent = config(DiscussionStyle.ROUND_TABLE, 1, new GroupMember("sub-g1", "Team A", 1, null, MemberType.GROUP));
            parent.setModeratorAgentId("mod");

            var subGroup = config(DiscussionStyle.ROUND_TABLE, 1, new GroupMember("a1", "Alice", 1, null));
            subGroup.setModeratorAgentId("sub-mod");

            setupStore(GROUP_ID, parent);
            setupStore("sub-g1", subGroup);
            stubAgent("a1", "Alice opinion");
            stubAgent("sub-mod", "Sub-group synthesis");
            stubAgent("mod", "Parent synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            verify(groupStore).getCurrentResourceId("sub-g1");
        }

        @Test
        void groupMember_depthExceeded_skipsGracefully() throws Exception {
            var shallow = new GroupConversationService(groupStore, conversationStore, conversationService, agentFactory, templatingEngine,
                    jsonSerialization, new SimpleMeterRegistry(), 0);

            var parent = config(DiscussionStyle.ROUND_TABLE, 1, new GroupMember("sub-g1", "Team A", 1, null, MemberType.GROUP));
            parent.setModeratorAgentId("mod");

            setupStore(GROUP_ID, parent);
            stubAgent("mod", "Synthesis");

            var result = shallow.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertTrue(result.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.SKIPPED));
        }
    }

    // =========================================================
    // Error Handling
    // =========================================================

    @Nested
    class ErrorHandling {

        @Test
        void abortPolicy_throwsAndFailsConversation() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1, new GroupMember("a1", "Alice", 1, null));
            cfg.setProtocol(new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.ABORT, 0, ProtocolConfig.MemberUnavailablePolicy.FAIL));
            setupStore(cfg);
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1"))).thenReturn(null);

            assertThrows(GroupDiscussionException.class, () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0));

            var captor = ArgumentCaptor.forClass(GroupConversation.class);
            verify(conversationStore, atLeast(1)).update(captor.capture());
            assertEquals(GroupConversationState.FAILED, captor.getValue().getState());
        }

        @Test
        void startConversationFails_skipsAgent() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1, new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1"))).thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("a1"), any(), any())).thenThrow(new RuntimeException("Agent crashed"));
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.SKIPPED && "a1".equals(e.speakerAgentId())));
        }
    }

    // =========================================================
    // Lifecycle
    // =========================================================

    @Test
    void readGroupConversation_delegates() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        when(conversationStore.read("gc-1")).thenReturn(gc);

        assertEquals("gc-1", service.readGroupConversation("gc-1").getId());
    }

    @Test
    void deleteGroupConversation_cascadesEnd() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.getMemberConversationIds().put("a1", "conv-a1");
        gc.getMemberConversationIds().put("a2", "conv-a2");
        when(conversationStore.read("gc-1")).thenReturn(gc);

        service.deleteGroupConversation("gc-1");

        verify(conversationService).endConversation("conv-a1");
        verify(conversationService).endConversation("conv-a2");
        verify(conversationStore).delete("gc-1");
    }

    @Test
    void listGroupConversations_delegates() throws Exception {
        when(conversationStore.listByGroupId("g1", 0, 20)).thenReturn(List.of(new GroupConversation()));

        assertEquals(1, service.listGroupConversations("g1", 0, 20).size());
    }

    // =========================================================
    // Additional Style Coverage
    // =========================================================

    @Nested
    class AdditionalStyles {

        @Test
        void delphi_producesMultiRoundOpinions() throws Exception {
            var cfg = config(DiscussionStyle.DELPHI, 2,
                    new GroupMember("a1", "Expert1", 1, null),
                    new GroupMember("a2", "Expert2", 2, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Expert1 opinion");
            stubAgent("a2", "Expert2 opinion");
            stubAgent("mod", "Delphi synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // Delphi produces multiple rounds of anonymous opinions
            long opinions = result.getTranscript().stream()
                    .filter(e -> e.type() == TranscriptEntryType.OPINION)
                    .count();
            assertTrue(opinions >= 2, "Expected >=2 opinions, got " + opinions);
        }

        @Test
        void noModerator_completesSuccessfully() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("a2", "Bob", 2, null));
            // No moderatorAgentId set
            setupStore(cfg);
            stubAgent("a1", "Opinion A");
            stubAgent("a2", "Opinion B");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // Should still have opinions from both agents
            long opinions = result.getTranscript().stream()
                    .filter(e -> e.type() == TranscriptEntryType.OPINION)
                    .count();
            assertTrue(opinions >= 2, "Expected >=2 opinions, got " + opinions);
        }
    }

    // =========================================================
    // Additional Error Handling
    // =========================================================

    @Nested
    class AdditionalErrors {

        @Test
        void groupNotFound_nullResourceId_throwsResourceNotFoundException() throws Exception {
            when(groupStore.getCurrentResourceId("unknown-group")).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> service.discuss("unknown-group", QUESTION, USER_ID, 0));
        }

        @Test
        void nullGroupId_throwsException() {
            assertThrows(Exception.class,
                    () -> service.discuss(null, QUESTION, USER_ID, 0));
        }

        @Test
        void emptyQuestion_producesTranscript() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Response");
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, "", USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        void sayFails_agentSkippedAndDiscussionContinues() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("a2", "Bob", 2, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            // a1 starts conversation but say() fails
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1"))).thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("a1"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));
            doThrow(new RuntimeException("say failed"))
                    .when(conversationService).say(any(Environment.class), eq("a1"), anyString(),
                            any(), any(), any(), any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

            stubAgent("a2", "Bob response");
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.SKIPPED && "a1".equals(e.speakerAgentId())));
        }
    }

    // =========================================================
    // Multi-Round
    // =========================================================

    @Nested
    class MultiRound {

        @Test
        void multipleRounds_producesMultipleOpinionSets() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 3,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);
            stubAgent("a1", "Round opinion");
            stubAgent("mod", "Final synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, result.getState());
            long opinions = result.getTranscript().stream()
                    .filter(e -> e.type() == TranscriptEntryType.OPINION)
                    .count();
            // 3 rounds × 1 agent = 3 opinions
            assertTrue(opinions >= 3, "Expected >=3 opinions for 3 rounds, got " + opinions);
        }
    }

    // =========================================================
    // Additional Lifecycle
    // =========================================================

    @Nested
    class AdditionalLifecycle {

        @Test
        void deleteGroupConversation_noMemberConversations_deletesOnly() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-2");
            // No member conversations
            when(conversationStore.read("gc-2")).thenReturn(gc);

            service.deleteGroupConversation("gc-2");

            verify(conversationService, never()).endConversation(anyString());
            verify(conversationStore).delete("gc-2");
        }

        @Test
        void readGroupConversation_notFound_returnsNull() throws Exception {
            when(conversationStore.read("gc-missing")).thenReturn(null);

            assertNull(service.readGroupConversation("gc-missing"));
        }

        @Test
        void listGroupConversations_emptyResult() throws Exception {
            when(conversationStore.listByGroupId("g-empty", 0, 20)).thenReturn(List.of());

            assertTrue(service.listGroupConversations("g-empty", 0, 20).isEmpty());
        }
    }
}
