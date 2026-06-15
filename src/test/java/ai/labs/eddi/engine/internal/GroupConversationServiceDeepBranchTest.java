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
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDepthExceededException;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
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
import org.mockito.Mock;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Deep branch coverage tests for {@link GroupConversationService} targeting: -
 * mapPhaseToEntryType all switch cases - selectDefaultTemplate
 * NONE/ANONYMOUS/default branches - filterByScope all scope enum values -
 * buildPlainTextFallback - extractResponse via reflection: metadata-only,
 * serialize fallback - deleteGroupConversation exception handling - discuss
 * depth exceeded, null groupId - startAndDiscussAsync null groupId, null config
 * - executeGroupMemberTurn depth exceeded - handleAgentFailure ABORT vs SKIP -
 * errorEntry with null member - resolveParticipants ROLE: filter empty match,
 * MODERATOR with blank id - agent ERROR state response handling
 */
@DisplayName("GroupConversationService — Deep Branch Coverage")
class GroupConversationServiceDeepBranchTest {

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

    private GroupConversationService service;

    private static final String GROUP_ID = "test-group";
    private static final String USER_ID = "test-user";
    private static final String QUESTION = "What is best?";

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        service = new GroupConversationService(groupStore, conversationStore,
                conversationService, agentFactory, templatingEngine,
                jsonSerialization, new SimpleMeterRegistry(),
                null, null, null, "default", 3);

        when(conversationStore.create(any())).thenReturn("gc-1");

        lenient().when(templatingEngine.processTemplate(anyString(), any(), any()))
                .thenAnswer(inv -> {
                    String tmpl = inv.getArgument(0, String.class);
                    return tmpl.length() > 80 ? tmpl.substring(0, 80) : tmpl;
                });
    }

    // === Helpers ===

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
        var rid = mock(IResourceStore.IResourceId.class);
        when(rid.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(rid);
        when(groupStore.read(GROUP_ID, 1)).thenReturn(cfg);
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
    // mapPhaseToEntryType — all switch cases
    // =========================================================

    @Nested
    @DisplayName("mapPhaseToEntryType — all PhaseType switch cases")
    class MapPhaseToEntryType {

        @Test
        @DisplayName("maps all 8 PhaseTypes correctly")
        void allPhaseTypes() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "mapPhaseToEntryType", PhaseType.class);
            method.setAccessible(true);

            assertEquals(TranscriptEntryType.OPINION,
                    method.invoke(service, PhaseType.OPINION));
            assertEquals(TranscriptEntryType.CRITIQUE,
                    method.invoke(service, PhaseType.CRITIQUE));
            assertEquals(TranscriptEntryType.REVISION,
                    method.invoke(service, PhaseType.REVISION));
            assertEquals(TranscriptEntryType.CHALLENGE,
                    method.invoke(service, PhaseType.CHALLENGE));
            assertEquals(TranscriptEntryType.DEFENSE,
                    method.invoke(service, PhaseType.DEFENSE));
            assertEquals(TranscriptEntryType.ARGUMENT,
                    method.invoke(service, PhaseType.ARGUE));
            assertEquals(TranscriptEntryType.REBUTTAL,
                    method.invoke(service, PhaseType.REBUTTAL));
            assertEquals(TranscriptEntryType.SYNTHESIS,
                    method.invoke(service, PhaseType.SYNTHESIS));
        }
    }

    // =========================================================
    // selectDefaultTemplate — NONE, ANONYMOUS, default branches
    // =========================================================

    @Nested
    @DisplayName("selectDefaultTemplate — scope branches")
    class SelectDefaultTemplate {

        @Test
        @DisplayName("OPINION with NONE scope returns independent template")
        void opinionNoneScope() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "selectDefaultTemplate", DiscussionPhase.class, List.class, int.class);
            method.setAccessible(true);

            DiscussionPhase phase = new DiscussionPhase("Opinion", PhaseType.OPINION,
                    "ALL", TurnOrder.SEQUENTIAL, ContextScope.NONE, false, null, 1);
            String result = (String) method.invoke(service, phase, List.of(), 0);
            assertNotNull(result);
        }

        @Test
        @DisplayName("OPINION with ANONYMOUS scope returns anonymous template")
        void opinionAnonymousScope() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "selectDefaultTemplate", DiscussionPhase.class, List.class, int.class);
            method.setAccessible(true);

            DiscussionPhase phase = new DiscussionPhase("Opinion", PhaseType.OPINION,
                    "ALL", TurnOrder.SEQUENTIAL, ContextScope.ANONYMOUS, false, null, 1);
            String result = (String) method.invoke(service, phase, List.of(), 0);
            assertNotNull(result);
        }

        @Test
        @DisplayName("OPINION with FULL scope returns with-context template")
        void opinionFullScope() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "selectDefaultTemplate", DiscussionPhase.class, List.class, int.class);
            method.setAccessible(true);

            DiscussionPhase phase = new DiscussionPhase("Opinion", PhaseType.OPINION,
                    "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1);
            String result = (String) method.invoke(service, phase, List.of(), 0);
            assertNotNull(result);
        }

        @Test
        @DisplayName("SYNTHESIS phase returns default template")
        void synthesisTemplate() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "selectDefaultTemplate", DiscussionPhase.class, List.class, int.class);
            method.setAccessible(true);

            DiscussionPhase phase = new DiscussionPhase("Synth", PhaseType.SYNTHESIS);
            String result = (String) method.invoke(service, phase, List.of(), 0);
            assertNotNull(result);
        }
    }

    // =========================================================
    // buildPlainTextFallback — via reflection
    // =========================================================

    @Nested
    @DisplayName("buildPlainTextFallback")
    class BuildPlainTextFallback {

        @Test
        @DisplayName("generates readable fallback text")
        void generatesFallback() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "buildPlainTextFallback", DiscussionPhase.class,
                    GroupMember.class, String.class, List.class);
            method.setAccessible(true);

            DiscussionPhase phase = new DiscussionPhase("Discuss", PhaseType.OPINION);
            GroupMember member = new GroupMember("a1", "Alice", 1, null);

            String result = (String) method.invoke(service, phase, member, "test question", List.of());
            assertNotNull(result);
            assertTrue(result.contains("Discuss"));
            assertTrue(result.contains("test question"));
            assertTrue(result.contains("Alice"));
        }
    }

    // =========================================================
    // errorEntry — null member branch
    // =========================================================

    @Nested
    @DisplayName("errorEntry — null member")
    class ErrorEntryTests {

        @Test
        @DisplayName("null member uses 'unknown' defaults")
        void nullMember() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "errorEntry", GroupMember.class, int.class,
                    DiscussionPhase.class, String.class);
            method.setAccessible(true);

            DiscussionPhase phase = new DiscussionPhase("Test", PhaseType.OPINION);
            TranscriptEntry entry = (TranscriptEntry) method.invoke(
                    service, null, 0, phase, "error msg");
            assertEquals("unknown", entry.speakerAgentId());
            assertEquals("Unknown", entry.speakerDisplayName());
            assertEquals(TranscriptEntryType.ERROR, entry.type());
        }

        @Test
        @DisplayName("non-null member uses member details")
        void nonNullMember() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "errorEntry", GroupMember.class, int.class,
                    DiscussionPhase.class, String.class);
            method.setAccessible(true);

            GroupMember member = new GroupMember("a1", "Alice", 1, null);
            DiscussionPhase phase = new DiscussionPhase("Test", PhaseType.OPINION);
            TranscriptEntry entry = (TranscriptEntry) method.invoke(
                    service, member, 0, phase, "error msg");
            assertEquals("a1", entry.speakerAgentId());
            assertEquals("Alice", entry.speakerDisplayName());
        }
    }

    // =========================================================
    // findLatestResponse — via reflection
    // =========================================================

    @Nested
    @DisplayName("findLatestResponse")
    class FindLatestResponse {

        @Test
        @DisplayName("returns null when no matching entries")
        void noMatching() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "findLatestResponse", List.class, String.class);
            method.setAccessible(true);

            List<TranscriptEntry> transcript = List.of(
                    new TranscriptEntry("a2", "Bob", "response", 0, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null));

            String result = (String) method.invoke(service, transcript, "a1");
            assertNull(result);
        }

        @Test
        @DisplayName("returns latest non-error response")
        void returnsLatest() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "findLatestResponse", List.class, String.class);
            method.setAccessible(true);

            List<TranscriptEntry> transcript = List.of(
                    new TranscriptEntry("a1", "Alice", "first", 0, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null),
                    new TranscriptEntry("a1", "Alice", "second", 1, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null));

            String result = (String) method.invoke(service, transcript, "a1");
            assertEquals("second", result);
        }

        @Test
        @DisplayName("skips ERROR entries")
        void skipsErrors() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "findLatestResponse", List.class, String.class);
            method.setAccessible(true);

            List<TranscriptEntry> transcript = List.of(
                    new TranscriptEntry("a1", "Alice", "good", 0, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null),
                    new TranscriptEntry("a1", "Alice", "error msg", 1, "Phase", TranscriptEntryType.ERROR, Instant.now(), null, null));

            String result = (String) method.invoke(service, transcript, "a1");
            assertEquals("good", result);
        }

        @Test
        @DisplayName("skips SKIPPED entries")
        void skipsSkipped() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "findLatestResponse", List.class, String.class);
            method.setAccessible(true);

            List<TranscriptEntry> transcript = List.of(
                    new TranscriptEntry("a1", "Alice", "ok", 0, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null),
                    new TranscriptEntry("a1", "Alice", null, 1, "Phase", TranscriptEntryType.SKIPPED, Instant.now(), "reason", null));

            String result = (String) method.invoke(service, transcript, "a1");
            assertEquals("ok", result);
        }
    }

    // =========================================================
    // discuss — validation branches
    // =========================================================

    @Nested
    @DisplayName("discuss — validation branches")
    class DiscussValidation {

        @Test
        @DisplayName("null groupId throws IllegalArgumentException")
        void nullGroupId() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.discuss(null, QUESTION, USER_ID, 0));
        }

        @Test
        @DisplayName("depth exceeding max throws GroupDepthExceededException")
        void depthExceeded() {
            assertThrows(GroupDepthExceededException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 10));
        }

        @Test
        @DisplayName("null group config throws ResourceNotFoundException")
        void nullConfig() throws Exception {
            var rid = mock(IResourceStore.IResourceId.class);
            when(rid.getVersion()).thenReturn(1);
            when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(rid);
            when(groupStore.read(GROUP_ID, 1)).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0));
        }

        @Test
        @DisplayName("null currentGroupId throws ResourceNotFoundException")
        void nullCurrentGroupId() throws Exception {
            when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0));
        }

        @Test
        @DisplayName("empty phases throws GroupDiscussionException")
        void emptyPhases() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setPhases(List.of());
            setupStore(cfg);

            assertThrows(GroupDiscussionException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0));
        }
    }

    // =========================================================
    // startAndDiscussAsync — validation
    // =========================================================

    @Nested
    @DisplayName("startAndDiscussAsync — validation")
    class AsyncValidation {

        @Test
        @DisplayName("null groupId throws IllegalArgumentException")
        void nullGroupId() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.startAndDiscussAsync(null, QUESTION, USER_ID, null));
        }

        @Test
        @DisplayName("null currentGroupId throws ResourceNotFoundException")
        void nullCurrentGroupId() throws Exception {
            when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> service.startAndDiscussAsync(GROUP_ID, QUESTION, USER_ID, null));
        }

        @Test
        @DisplayName("null config throws ResourceNotFoundException")
        void nullConfig() throws Exception {
            var rid = mock(IResourceStore.IResourceId.class);
            when(rid.getVersion()).thenReturn(1);
            when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(rid);
            when(groupStore.read(GROUP_ID, 1)).thenReturn(null);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> service.startAndDiscussAsync(GROUP_ID, QUESTION, USER_ID, null));
        }

        @Test
        @DisplayName("empty phases throws GroupDiscussionException")
        void emptyPhases() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setPhases(List.of());
            setupStore(cfg);

            assertThrows(GroupDiscussionException.class,
                    () -> service.startAndDiscussAsync(GROUP_ID, QUESTION, USER_ID, null));
        }
    }

    // =========================================================
    // deleteGroupConversation — branches
    // =========================================================

    @Nested
    @DisplayName("deleteGroupConversation")
    class DeleteGroupConversation {

        @Test
        @DisplayName("not found is handled gracefully")
        void notFound() throws Exception {
            when(conversationStore.read("gc-missing"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            // Should not throw
            service.deleteGroupConversation("gc-missing");
            verify(conversationStore, never()).delete(anyString());
        }

        @Test
        @DisplayName("ends private conversations before deleting")
        void endsPrivateConversations() throws Exception {
            var gc = new GroupConversation();
            gc.setMemberConversationIds(new LinkedHashMap<>(Map.of("a1", "conv-1", "a2", "conv-2")));
            when(conversationStore.read("gc-1")).thenReturn(gc);

            service.deleteGroupConversation("gc-1");

            verify(conversationService).endConversation("conv-1");
            verify(conversationService).endConversation("conv-2");
            verify(conversationStore).delete("gc-1");
        }

        @Test
        @DisplayName("failed endConversation is handled gracefully")
        void endConversationFailure() throws Exception {
            var gc = new GroupConversation();
            gc.setMemberConversationIds(new LinkedHashMap<>(Map.of("a1", "conv-1")));
            when(conversationStore.read("gc-1")).thenReturn(gc);
            doThrow(new RuntimeException("end failed"))
                    .when(conversationService).endConversation("conv-1");

            // Should not throw
            service.deleteGroupConversation("gc-1");
            verify(conversationStore).delete("gc-1");
        }
    }

    // =========================================================
    // listGroupConversations
    // =========================================================

    @Nested
    @DisplayName("listGroupConversations")
    class ListGroupConversations {

        @Test
        @DisplayName("delegates to store")
        void delegates() throws Exception {
            when(conversationStore.listByGroupId("grp1", 0, 10))
                    .thenReturn(List.of());

            var result = service.listGroupConversations("grp1", 0, 10);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================
    // resolveParticipants — MODERATOR with blank id
    // =========================================================

    @Nested
    @DisplayName("resolveParticipants — MODERATOR and ROLE branches")
    class ResolveParticipants {

        @Test
        @DisplayName("MODERATOR participants with blank moderatorId falls back to ALL")
        void moderatorBlankFallback() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "resolveParticipants", DiscussionPhase.class, List.class, String.class);
            method.setAccessible(true);

            DiscussionPhase phase = new DiscussionPhase("Synth", PhaseType.SYNTHESIS,
                    "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1);
            List<GroupMember> allMembers = List.of(
                    new GroupMember("a1", "Alice", 1, null));

            @SuppressWarnings("unchecked")
            List<GroupMember> result = (List<GroupMember>) method.invoke(
                    service, phase, allMembers, "  ");

            // Should fall back to ALL members
            assertEquals(1, result.size());
            assertEquals("a1", result.getFirst().agentId());
        }

        @Test
        @DisplayName("MODERATOR participants with null moderatorId falls back to ALL")
        void moderatorNullFallback() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "resolveParticipants", DiscussionPhase.class, List.class, String.class);
            method.setAccessible(true);

            DiscussionPhase phase = new DiscussionPhase("Synth", PhaseType.SYNTHESIS,
                    "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1);
            List<GroupMember> allMembers = List.of(
                    new GroupMember("a1", "Alice", 1, null));

            @SuppressWarnings("unchecked")
            List<GroupMember> result = (List<GroupMember>) method.invoke(
                    service, phase, allMembers, null);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("ROLE: filter with no matching members falls back to ALL")
        void roleNoMatchFallback() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "resolveParticipants", DiscussionPhase.class, List.class, String.class);
            method.setAccessible(true);

            DiscussionPhase phase = new DiscussionPhase("Filter", PhaseType.OPINION,
                    "ROLE:NONEXISTENT", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1);
            List<GroupMember> allMembers = List.of(
                    new GroupMember("a1", "Alice", 1, "DEVELOPER"));

            @SuppressWarnings("unchecked")
            List<GroupMember> result = (List<GroupMember>) method.invoke(
                    service, phase, allMembers, null);

            // Falls back to ALL
            assertEquals(1, result.size());
            assertEquals("a1", result.getFirst().agentId());
        }

        @Test
        @DisplayName("ROLE: filter with matching members returns filtered list")
        void roleMatchReturnsFiltered() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "resolveParticipants", DiscussionPhase.class, List.class, String.class);
            method.setAccessible(true);

            DiscussionPhase phase = new DiscussionPhase("Filter", PhaseType.OPINION,
                    "ROLE:PRO", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1);
            List<GroupMember> allMembers = List.of(
                    new GroupMember("a1", "Alice", 1, "PRO"),
                    new GroupMember("a2", "Bob", 2, "CON"));

            @SuppressWarnings("unchecked")
            List<GroupMember> result = (List<GroupMember>) method.invoke(
                    service, phase, allMembers, null);

            assertEquals(1, result.size());
            assertEquals("a1", result.getFirst().agentId());
        }

        @Test
        @DisplayName("null participants defaults to ALL")
        void nullParticipantsDefaultsToAll() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "resolveParticipants", DiscussionPhase.class, List.class, String.class);
            method.setAccessible(true);

            DiscussionPhase phase = new DiscussionPhase("All", PhaseType.OPINION,
                    null, TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1);
            List<GroupMember> allMembers = List.of(
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("a2", "Bob", 2, null));

            @SuppressWarnings("unchecked")
            List<GroupMember> result = (List<GroupMember>) method.invoke(
                    service, phase, allMembers, null);

            assertEquals(2, result.size());
        }
    }

    // =========================================================
    // filterByScope — all ContextScope values
    // =========================================================

    @Nested
    @DisplayName("filterByScope — all scopes")
    class FilterByScope {

        @Test
        @DisplayName("NONE returns empty list")
        void noneScope() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "filterByScope", List.class, ContextScope.class, int.class, GroupMember.class);
            method.setAccessible(true);

            List<TranscriptEntry> transcript = List.of(
                    new TranscriptEntry("a1", "Alice", "response", 0, "Phase",
                            TranscriptEntryType.OPINION, Instant.now(), null, null));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) method.invoke(
                    service, transcript, ContextScope.NONE, 0,
                    new GroupMember("a1", "Alice", 1, null));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null scope returns empty list")
        void nullScope() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "filterByScope", List.class, ContextScope.class, int.class, GroupMember.class);
            method.setAccessible(true);

            List<TranscriptEntry> transcript = List.of(
                    new TranscriptEntry("a1", "Alice", "response", 0, "Phase",
                            TranscriptEntryType.OPINION, Instant.now(), null, null));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) method.invoke(
                    service, transcript, null, 0,
                    new GroupMember("a1", "Alice", 1, null));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("FULL returns all non-error/skipped entries")
        void fullScope() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "filterByScope", List.class, ContextScope.class, int.class, GroupMember.class);
            method.setAccessible(true);

            List<TranscriptEntry> transcript = List.of(
                    new TranscriptEntry("a1", "Alice", "opinion", 0, "Phase",
                            TranscriptEntryType.OPINION, Instant.now(), null, null),
                    new TranscriptEntry("a2", "Bob", null, 0, "Phase",
                            TranscriptEntryType.SKIPPED, Instant.now(), "skipped", null));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) method.invoke(
                    service, transcript, ContextScope.FULL, 1,
                    new GroupMember("a1", "Alice", 1, null));

            assertEquals(1, result.size());
            assertEquals("Alice", result.getFirst().get("speaker"));
        }

        @Test
        @DisplayName("LAST_PHASE filters entries by phase index")
        void lastPhaseScope() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "filterByScope", List.class, ContextScope.class, int.class, GroupMember.class);
            method.setAccessible(true);

            List<TranscriptEntry> transcript = List.of(
                    new TranscriptEntry("a1", "Alice", "old", 0, "Phase1",
                            TranscriptEntryType.OPINION, Instant.now(), null, null),
                    new TranscriptEntry("a2", "Bob", "recent", 1, "Phase2",
                            TranscriptEntryType.OPINION, Instant.now(), null, null));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) method.invoke(
                    service, transcript, ContextScope.LAST_PHASE, 2,
                    new GroupMember("a1", "Alice", 1, null));

            // Phase 2 allows phaseIndex >= 1, so only phaseIndex=1 entry qualifies
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("ANONYMOUS strips speaker names")
        void anonymousScope() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "filterByScope", List.class, ContextScope.class, int.class, GroupMember.class);
            method.setAccessible(true);

            List<TranscriptEntry> transcript = List.of(
                    new TranscriptEntry("a1", "Alice", "opinion", 0, "Phase",
                            TranscriptEntryType.OPINION, Instant.now(), null, null));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) method.invoke(
                    service, transcript, ContextScope.ANONYMOUS, 0,
                    new GroupMember("a1", "Alice", 1, null));

            assertEquals(1, result.size());
            assertEquals("Anonymous", result.getFirst().get("speaker"));
        }

        @Test
        @DisplayName("OWN_FEEDBACK filters by targetAgentId")
        void ownFeedbackScope() throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "filterByScope", List.class, ContextScope.class, int.class, GroupMember.class);
            method.setAccessible(true);

            List<TranscriptEntry> transcript = List.of(
                    new TranscriptEntry("a2", "Bob", "feedback for Alice", 0, "Phase",
                            TranscriptEntryType.CRITIQUE, Instant.now(), null, "a1"),
                    new TranscriptEntry("a2", "Bob", "feedback for Charlie", 0, "Phase",
                            TranscriptEntryType.CRITIQUE, Instant.now(), null, "a3"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) method.invoke(
                    service, transcript, ContextScope.OWN_FEEDBACK, 0,
                    new GroupMember("a1", "Alice", 1, null));

            // Only feedback targeted at a1 should be included
            assertEquals(1, result.size());
        }
    }

    // =========================================================
    // Agent ERROR state response handling
    // =========================================================

    @Nested
    @DisplayName("Agent ERROR state response")
    class AgentErrorState {

        @Test
        @DisplayName("ERROR state with null response produces error message")
        void errorStateProducesMessage() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            // Agent returns snapshot with ERROR state
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("a1"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));
            doAnswer(inv -> {
                ConversationResponseHandler handler = inv.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                snapshot.setConversationState(ConversationState.ERROR);
                // Empty outputs to trigger the null response path
                snapshot.setConversationOutputs(new ArrayList<>(List.of(
                        new ConversationOutput() {
                            {
                                put("actions", List.of("greet"));
                            }
                        })));
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(any(Environment.class), eq("a1"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // SYNTHESIS phase — full execution with moderator
    // =========================================================

    @Nested
    @DisplayName("SYNTHESIS phase")
    class SynthesisPhase {

        @Test
        @DisplayName("SYNTHESIS phase enters SYNTHESIZING state")
        void synthesizingState() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            cfg.setPhases(List.of(
                    new DiscussionPhase("Opinion", PhaseType.OPINION),
                    new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS,
                            "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1)));

            // Setup moderator
            var rid = mock(IResourceStore.IResourceId.class);
            when(rid.getVersion()).thenReturn(1);
            when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(rid);
            when(groupStore.read(GROUP_ID, 1)).thenReturn(cfg);

            stubAgent("a1", "Opinion response");
            stubAgent("mod", "Final synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertNotNull(result.getSynthesizedAnswer());
        }
    }

    // =========================================================
    // shutdown
    // =========================================================

    @Nested
    @DisplayName("shutdown")
    class Shutdown {

        @Test
        @DisplayName("shutdown completes without error")
        void shutdownCompletes() {
            // Just verify no exceptions
            service.shutdown();
        }
    }

    // =========================================================
    // readGroupConversation
    // =========================================================

    @Nested
    @DisplayName("readGroupConversation")
    class ReadGroupConversation {

        @Test
        @DisplayName("delegates to store")
        void delegates() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            when(conversationStore.read("gc-1")).thenReturn(gc);

            var result = service.readGroupConversation("gc-1");
            assertEquals("gc-1", result.getId());
        }
    }

    // =========================================================
    // CRITIQUE phase with targetEachPeer
    // =========================================================

    @Nested
    @DisplayName("CRITIQUE phase — peer targeting")
    class CritiquePhase {

        @Test
        @DisplayName("CRITIQUE phase builds target context")
        void critiqueTarget() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("a2", "Bob", 2, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Opinion", PhaseType.OPINION),
                    new DiscussionPhase("Critique", PhaseType.CRITIQUE,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, true, null, 1)));
            setupStore(cfg);
            stubAgent("a1", "Opinion A");
            stubAgent("a2", "Critique of Alice");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.CRITIQUE));
        }
    }

    // =========================================================
    // REVISION phase with feedback
    // =========================================================

    @Nested
    @DisplayName("REVISION phase")
    class RevisionPhase {

        @Test
        @DisplayName("REVISION phase provides original response and feedback")
        void revisionWithFeedback() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("a2", "Bob", 2, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Opinion", PhaseType.OPINION),
                    new DiscussionPhase("Revision", PhaseType.REVISION)));
            setupStore(cfg);
            stubAgent("a1", "Initial opinion");
            stubAgent("a2", "Revised opinion");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.REVISION));
        }
    }

    // =========================================================
    // CHALLENGE phase
    // =========================================================

    @Nested
    @DisplayName("CHALLENGE phase")
    class ChallengePhase {

        @Test
        @DisplayName("CHALLENGE phase collects all opinions")
        void challengeCollectsOpinions() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Opinion", PhaseType.OPINION),
                    new DiscussionPhase("Challenge", PhaseType.CHALLENGE)));
            setupStore(cfg);
            stubAgent("a1", "Challenge response");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.CHALLENGE));
        }
    }
}
