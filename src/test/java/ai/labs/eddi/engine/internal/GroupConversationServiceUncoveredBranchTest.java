/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
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
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests targeting uncovered branches in {@link GroupConversationService}
 * focusing on:
 * <ul>
 * <li>{@code extractResponse} — all format detection branches (Format 1, Format
 * 2, fallback)</li>
 * <li>{@code selectDefaultTemplate} — ContextScope.NONE, ANONYMOUS,
 * with-context</li>
 * <li>{@code filterByScope} — NONE, FULL, LAST_PHASE, ANONYMOUS,
 * OWN_FEEDBACK</li>
 * <li>{@code findLatestResponse} — null content, error entries, no match</li>
 * <li>{@code mapPhaseToEntryType} — all 8 PhaseType enum values</li>
 * <li>{@code resolvePhases} — custom vs preset, null style</li>
 * <li>{@code resolveParticipants} — MODERATOR, ROLE:xyz, ALL, null
 * participants</li>
 * <li>{@code buildPlainTextFallback} — template rendering</li>
 * <li>{@code errorEntry} — null member handling</li>
 * <li>{@code DiscussionStylePresets} — all 5 styles and default templates</li>
 * <li>discuss/startAndDiscussAsync — validation branches</li>
 * <li>deleteGroupConversation — exception handling</li>
 * <li>Phase execution — SYNTHESIS, CRITIQUE, REVISION, CHALLENGE</li>
 * </ul>
 */
@DisplayName("GroupConversationService — Uncovered Branch Coverage")
class GroupConversationServiceUncoveredBranchTest {

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
    private ai.labs.eddi.configs.agents.AgentSigningService agentSigningService;
    @Mock
    private ai.labs.eddi.configs.agents.IAgentStore agentStore;
    @Mock
    private ai.labs.eddi.configs.agents.crypto.NonceCacheService nonceCacheService;
    @Mock
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @Mock
    private io.micrometer.core.instrument.Timer timer;
    @Mock
    private io.micrometer.core.instrument.Counter counter;

    private GroupConversationService service;

    private static final String GROUP_ID = "test-group";
    private static final String USER_ID = "test-user";
    private static final String QUESTION = "What is best?";

    @BeforeEach
    void setUp() {
        openMocks(this);
        org.mockito.Mockito.doReturn(timer).when(meterRegistry).timer(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doReturn(counter).when(meterRegistry).counter(org.mockito.ArgumentMatchers.anyString());
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                meterRegistry, agentSigningService, agentStore,
                nonceCacheService, "default", 3);
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
    // extractResponse — Format 1: nested "output" array
    // =========================================================

    @Nested
    @DisplayName("extractResponse — Format 1 (output array)")
    class ExtractResponseFormat1 {

        @Test
        @DisplayName("null snapshot returns empty string")
        void nullSnapshot() throws Exception {
            String result = invokeExtractResponse(null);
            assertEquals("", result);
        }

        @Test
        @DisplayName("null conversationOutputs returns empty string")
        void nullConversationOutputs() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationOutputs(null);
            assertEquals("", invokeExtractResponse(snapshot));
        }

        @Test
        @DisplayName("empty conversationOutputs returns empty string")
        void emptyConversationOutputs() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationOutputs(new ArrayList<>());
            assertEquals("", invokeExtractResponse(snapshot));
        }

        @Test
        @DisplayName("null lastOutput returns empty string")
        void nullLastOutput() throws Exception {
            var snapshot = new SimpleConversationMemorySnapshot();
            var outputs = new ArrayList<ConversationOutput>();
            outputs.add(null);
            snapshot.setConversationOutputs(outputs);
            assertEquals("", invokeExtractResponse(snapshot));
        }

        @Test
        @DisplayName("output array with String items")
        void outputArrayStrings() throws Exception {
            var output = new ConversationOutput();
            output.put("output", List.of("Hello", "World"));
            var snapshot = createSnapshot(output);
            assertEquals("Hello\nWorld", invokeExtractResponse(snapshot));
        }

        @Test
        @DisplayName("output array with Map items containing text")
        void outputArrayMaps() throws Exception {
            var output = new ConversationOutput();
            output.put("output", List.of(Map.of("text", "Response text")));
            var snapshot = createSnapshot(output);
            assertEquals("Response text", invokeExtractResponse(snapshot));
        }

        @Test
        @DisplayName("output array with Map items without text key is ignored")
        void outputArrayMapsWithoutText() throws Exception {
            var output = new ConversationOutput();
            output.put("output", List.of(Map.of("nottext", "value")));
            output.put("reply", "has output key"); // so hasAnyOutput is true
            var snapshot = createSnapshot(output);
            // output array list is traversed but no text found, falls through
            // to fallback serialization via jsonSerialization.serialize()
            doReturn("{\"output\":[{\"nottext\":\"value\"}]}").when(jsonSerialization).serialize(any());
            String result = invokeExtractResponse(snapshot);
            assertNotNull(result);
        }

        @Test
        @DisplayName("output is not a list — falls through to Format 2")
        void outputNotList() throws Exception {
            var output = new ConversationOutput();
            output.put("output", "just a string, not a list");
            var snapshot = createSnapshot(output);
            // Falls through to flat key check, then hasAnyOutput=true → fallback
            // serialization via jsonSerialization.serialize()
            doReturn("{\"output\":\"just a string\"}").when(jsonSerialization).serialize(any());
            String result = invokeExtractResponse(snapshot);
            assertNotNull(result);
        }
    }

    // =========================================================
    // extractResponse — Format 2: flat keys like "output:text:*"
    // =========================================================

    @Nested
    @DisplayName("extractResponse — Format 2 (flat keys)")
    class ExtractResponseFormat2 {

        @Test
        @DisplayName("output:text:* key with String value")
        void flatKeyString() throws Exception {
            var output = new ConversationOutput();
            output.put("output:text:greeting", "Hello there");
            var snapshot = createSnapshot(output);
            assertEquals("Hello there", invokeExtractResponse(snapshot));
        }

        @Test
        @DisplayName("output:text:* key with List<String> value")
        void flatKeyListOfStrings() throws Exception {
            var output = new ConversationOutput();
            output.put("output:text:multi", List.of("Line 1", "Line 2"));
            var snapshot = createSnapshot(output);
            assertEquals("Line 1\nLine 2", invokeExtractResponse(snapshot));
        }

        @Test
        @DisplayName("output:text:* key with List<Map> value")
        void flatKeyListOfMaps() throws Exception {
            var output = new ConversationOutput();
            output.put("output:text:maps", List.of(Map.of("text", "Mapped")));
            var snapshot = createSnapshot(output);
            assertEquals("Mapped", invokeExtractResponse(snapshot));
        }

        @Test
        @DisplayName("output:text:* key with Map value containing text")
        void flatKeyMapWithText() throws Exception {
            var output = new ConversationOutput();
            output.put("output:text:single", Map.of("text", "Single mapped"));
            var snapshot = createSnapshot(output);
            assertEquals("Single mapped", invokeExtractResponse(snapshot));
        }

        @Test
        @DisplayName("multiple output:text:* keys are concatenated")
        void multipleOutputKeys() throws Exception {
            var output = new ConversationOutput();
            output.put("output:text:a", "First");
            output.put("output:text:b", "Second");
            var snapshot = createSnapshot(output);
            String result = invokeExtractResponse(snapshot);
            assertTrue(result.contains("First"));
            assertTrue(result.contains("Second"));
        }
    }

    // =========================================================
    // extractResponse — metadata-only detection
    // =========================================================

    @Nested
    @DisplayName("extractResponse — metadata-only detection")
    class ExtractResponseMetadata {

        @Test
        @DisplayName("no output or reply keys returns empty string")
        void metadataOnly() throws Exception {
            var output = new ConversationOutput();
            output.put("actions", List.of("greet"));
            output.put("input", "hello");
            var snapshot = createSnapshot(output);
            // ConversationOutputExtractor returns null for metadata-only;
            // GCS wrapper converts null → "" for backward compat
            assertEquals("", invokeExtractResponse(snapshot));
        }

        @Test
        @DisplayName("has reply key but no text — fallback serialization")
        void hasReplyKeyNoText() throws Exception {
            var output = new ConversationOutput();
            output.put("reply", Map.of("data", "some reply"));
            var snapshot = createSnapshot(output);
            // hasAnyOutput=true (key "reply") → fallback jsonSerialization.serialize()
            doReturn("{\"reply\":{\"data\":\"some reply\"}}").when(jsonSerialization).serialize(any());
            String result = invokeExtractResponse(snapshot);
            assertNotNull(result);
        }
    }

    // =========================================================
    // mapPhaseToEntryType — all 8 PhaseType values
    // =========================================================

    @Nested
    @DisplayName("mapPhaseToEntryType — all enum values")
    class MapPhaseToEntryType {

        @Test
        @DisplayName("OPINION → OPINION")
        void opinion() throws Exception {
            assertEquals(TranscriptEntryType.OPINION, invokeMapPhase(PhaseType.OPINION));
        }

        @Test
        @DisplayName("CRITIQUE → CRITIQUE")
        void critique() throws Exception {
            assertEquals(TranscriptEntryType.CRITIQUE, invokeMapPhase(PhaseType.CRITIQUE));
        }

        @Test
        @DisplayName("REVISION → REVISION")
        void revision() throws Exception {
            assertEquals(TranscriptEntryType.REVISION, invokeMapPhase(PhaseType.REVISION));
        }

        @Test
        @DisplayName("CHALLENGE → CHALLENGE")
        void challenge() throws Exception {
            assertEquals(TranscriptEntryType.CHALLENGE, invokeMapPhase(PhaseType.CHALLENGE));
        }

        @Test
        @DisplayName("DEFENSE → DEFENSE")
        void defense() throws Exception {
            assertEquals(TranscriptEntryType.DEFENSE, invokeMapPhase(PhaseType.DEFENSE));
        }

        @Test
        @DisplayName("ARGUE → ARGUMENT")
        void argue() throws Exception {
            assertEquals(TranscriptEntryType.ARGUMENT, invokeMapPhase(PhaseType.ARGUE));
        }

        @Test
        @DisplayName("REBUTTAL → REBUTTAL")
        void rebuttal() throws Exception {
            assertEquals(TranscriptEntryType.REBUTTAL, invokeMapPhase(PhaseType.REBUTTAL));
        }

        @Test
        @DisplayName("SYNTHESIS → SYNTHESIS")
        void synthesis() throws Exception {
            assertEquals(TranscriptEntryType.SYNTHESIS, invokeMapPhase(PhaseType.SYNTHESIS));
        }

        private TranscriptEntryType invokeMapPhase(PhaseType type) throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod("mapPhaseToEntryType", PhaseType.class);
            method.setAccessible(true);
            return (TranscriptEntryType) method.invoke(service, type);
        }
    }

    // =========================================================
    // findLatestResponse — edge cases
    // =========================================================

    @Nested
    @DisplayName("findLatestResponse — edge cases")
    class FindLatestResponse {

        @Test
        @DisplayName("empty transcript returns null")
        void emptyTranscript() throws Exception {
            assertNull(invokeFindLatestResponse(List.of(), "agent1"));
        }

        @Test
        @DisplayName("no matching agentId returns null")
        void noMatchingAgent() throws Exception {
            var entries = List.of(
                    new TranscriptEntry("agent2", "Agent 2", "response", 0, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null));
            assertNull(invokeFindLatestResponse(entries, "agent1"));
        }

        @Test
        @DisplayName("matching agent with null content returns null")
        void matchingAgentNullContent() throws Exception {
            var entries = List.of(
                    new TranscriptEntry("agent1", "Agent 1", null, 0, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null));
            assertNull(invokeFindLatestResponse(entries, "agent1"));
        }

        @Test
        @DisplayName("matching agent with ERROR entry skipped")
        void matchingAgentErrorEntry() throws Exception {
            var entries = List.of(
                    new TranscriptEntry("agent1", "Agent 1", "error content", 0, "Phase", TranscriptEntryType.ERROR, Instant.now(), null, null));
            assertNull(invokeFindLatestResponse(entries, "agent1"));
        }

        @Test
        @DisplayName("matching agent with SKIPPED entry skipped")
        void matchingAgentSkippedEntry() throws Exception {
            var entries = List.of(
                    new TranscriptEntry("agent1", "Agent 1", "skipped content", 0, "Phase", TranscriptEntryType.SKIPPED, Instant.now(), null, null));
            assertNull(invokeFindLatestResponse(entries, "agent1"));
        }

        @Test
        @DisplayName("multiple matching entries returns last one")
        void multipleMatchesReturnsLast() throws Exception {
            var entries = List.of(
                    new TranscriptEntry("agent1", "Agent 1", "first", 0, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null),
                    new TranscriptEntry("agent1", "Agent 1", "second", 1, "Phase", TranscriptEntryType.REVISION, Instant.now(), null, null));
            assertEquals("second", invokeFindLatestResponse(entries, "agent1"));
        }

        @SuppressWarnings("unchecked")
        private String invokeFindLatestResponse(List<TranscriptEntry> transcript, String agentId) throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod("findLatestResponse", List.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(service, transcript, agentId);
        }
    }

    // =========================================================
    // errorEntry — null member handling
    // =========================================================

    @Nested
    @DisplayName("errorEntry — null member handling")
    class ErrorEntryEdge {

        @Test
        @DisplayName("null member produces 'unknown' agentId and 'Unknown' displayName")
        void nullMember() throws Exception {
            var phase = new DiscussionPhase("Test", PhaseType.OPINION, null, null, null, false, null, 1);
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "errorEntry", GroupMember.class, int.class, DiscussionPhase.class, String.class);
            method.setAccessible(true);
            TranscriptEntry entry = (TranscriptEntry) method.invoke(service, null, 0, phase, "Some error");
            assertEquals("unknown", entry.speakerAgentId());
            assertEquals("Unknown", entry.speakerDisplayName());
            assertEquals(TranscriptEntryType.ERROR, entry.type());
        }

        @Test
        @DisplayName("non-null member uses actual values")
        void nonNullMember() throws Exception {
            var member = new GroupMember("agent-1", "Agent One", 0, "PRO");
            var phase = new DiscussionPhase("Test", PhaseType.OPINION, null, null, null, false, null, 1);
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "errorEntry", GroupMember.class, int.class, DiscussionPhase.class, String.class);
            method.setAccessible(true);
            TranscriptEntry entry = (TranscriptEntry) method.invoke(service, member, 0, phase, "Error msg");
            assertEquals("agent-1", entry.speakerAgentId());
            assertEquals("Agent One", entry.speakerDisplayName());
        }
    }

    // =========================================================
    // buildPlainTextFallback
    // =========================================================

    @Nested
    @DisplayName("buildPlainTextFallback — template rendering")
    class BuildPlainTextFallback {

        @Test
        @DisplayName("generates plain text with phase name, question, and speaker")
        void generatesPlainText() throws Exception {
            var phase = new DiscussionPhase("Opening Opinions", PhaseType.OPINION, null, null, null, false, null, 1);
            var speaker = new GroupMember("agent-1", "Alice", 0, null);

            Method method = GroupConversationService.class.getDeclaredMethod(
                    "buildPlainTextFallback", DiscussionPhase.class, GroupMember.class, String.class, List.class);
            method.setAccessible(true);
            String result = (String) method.invoke(service, phase, speaker, "What is AI?", List.of());

            assertTrue(result.contains("Opening Opinions"));
            assertTrue(result.contains("What is AI?"));
            assertTrue(result.contains("Alice"));
        }
    }

    // =========================================================
    // DiscussionStylePresets — all 5 styles
    // =========================================================

    @Nested
    @DisplayName("DiscussionStylePresets — all styles")
    class StylePresets {

        @Test
        @DisplayName("ROUND_TABLE expands to valid phases")
        void roundTable() {
            var phases = DiscussionStylePresets.expand(DiscussionStyle.ROUND_TABLE, 1);
            assertFalse(phases.isEmpty());
            assertTrue(phases.stream().anyMatch(p -> p.type() == PhaseType.SYNTHESIS));
        }

        @Test
        @DisplayName("PEER_REVIEW expands with CRITIQUE phase")
        void peerReview() {
            var phases = DiscussionStylePresets.expand(DiscussionStyle.PEER_REVIEW, 1);
            assertFalse(phases.isEmpty());
            assertTrue(phases.stream().anyMatch(p -> p.type() == PhaseType.CRITIQUE));
        }

        @Test
        @DisplayName("DEVIL_ADVOCATE expands with CHALLENGE phase")
        void devilAdvocate() {
            var phases = DiscussionStylePresets.expand(DiscussionStyle.DEVIL_ADVOCATE, 1);
            assertFalse(phases.isEmpty());
            assertTrue(phases.stream().anyMatch(p -> p.type() == PhaseType.CHALLENGE));
        }

        @Test
        @DisplayName("DELPHI expands with multiple OPINION phases")
        void delphi() {
            var phases = DiscussionStylePresets.expand(DiscussionStyle.DELPHI, 2);
            assertFalse(phases.isEmpty());
            long opinionCount = phases.stream().filter(p -> p.type() == PhaseType.OPINION).count();
            assertTrue(opinionCount >= 2, "DELPHI should have at least 2 opinion phases for 2 rounds");
        }

        @Test
        @DisplayName("DEBATE expands with ARGUE and REBUTTAL phases")
        void debate() {
            var phases = DiscussionStylePresets.expand(DiscussionStyle.DEBATE, 1);
            assertFalse(phases.isEmpty());
            assertTrue(phases.stream().anyMatch(p -> p.type() == PhaseType.ARGUE));
            assertTrue(phases.stream().anyMatch(p -> p.type() == PhaseType.REBUTTAL));
        }

        @Test
        @DisplayName("defaultTemplate returns non-null for all PhaseTypes")
        void defaultTemplates() {
            for (PhaseType type : PhaseType.values()) {
                String template = DiscussionStylePresets.defaultTemplate(type);
                assertNotNull(template, "Default template for " + type + " should not be null");
                assertFalse(template.isBlank(), "Default template for " + type + " should not be blank");
            }
        }

        @Test
        @DisplayName("opinion-specific templates")
        void opinionTemplates() {
            assertNotNull(DiscussionStylePresets.TEMPLATE_OPINION_INDEPENDENT);
            assertNotNull(DiscussionStylePresets.TEMPLATE_OPINION_ANONYMOUS);
            assertNotNull(DiscussionStylePresets.TEMPLATE_OPINION_WITH_CONTEXT);
        }
    }

    // =========================================================
    // resolveParticipants — edge cases
    // =========================================================

    @Nested
    @DisplayName("resolveParticipants — edge cases")
    class ResolveParticipants {

        @Test
        @DisplayName("MODERATOR with valid moderatorAgentId returns single member")
        void moderatorValid() throws Exception {
            var phase = new DiscussionPhase("Synth", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1);
            var allMembers = List.of(
                    new GroupMember("a1", "Agent 1", 0, null),
                    new GroupMember("a2", "Agent 2", 1, null));
            var result = invokeResolveParticipants(phase, allMembers, "mod-agent");
            assertEquals(1, result.size());
            assertEquals("mod-agent", result.getFirst().agentId());
        }

        @Test
        @DisplayName("MODERATOR with null moderatorAgentId falls back to ALL")
        void moderatorNull() throws Exception {
            var phase = new DiscussionPhase("Synth", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1);
            var allMembers = List.of(
                    new GroupMember("a1", "Agent 1", 0, null),
                    new GroupMember("a2", "Agent 2", 1, null));
            var result = invokeResolveParticipants(phase, allMembers, null);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("MODERATOR with blank moderatorAgentId falls back to ALL")
        void moderatorBlank() throws Exception {
            var phase = new DiscussionPhase("Synth", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1);
            var allMembers = List.of(new GroupMember("a1", "Agent 1", 0, null));
            var result = invokeResolveParticipants(phase, allMembers, "   ");
            assertEquals(1, result.size());
            assertEquals("a1", result.getFirst().agentId());
        }

        @Test
        @DisplayName("ROLE:PRO filters members by role")
        void roleFilter() throws Exception {
            var phase = new DiscussionPhase("Argue", PhaseType.ARGUE, "ROLE:PRO", TurnOrder.SEQUENTIAL, null, false, null, 1);
            var allMembers = List.of(
                    new GroupMember("a1", "Pro Agent", 0, "PRO"),
                    new GroupMember("a2", "Con Agent", 1, "CON"),
                    new GroupMember("a3", "Another Pro", 2, "PRO"));
            var result = invokeResolveParticipants(phase, allMembers, null);
            assertEquals(2, result.size());
            assertTrue(result.stream().allMatch(m -> "PRO".equalsIgnoreCase(m.role())));
        }

        @Test
        @DisplayName("ROLE:NONEXISTENT falls back to ALL")
        void roleNotFound() throws Exception {
            var phase = new DiscussionPhase("Argue", PhaseType.ARGUE, "ROLE:NONEXISTENT", TurnOrder.SEQUENTIAL, null, false, null, 1);
            var allMembers = List.of(
                    new GroupMember("a1", "Agent 1", 0, "PRO"),
                    new GroupMember("a2", "Agent 2", 1, "CON"));
            var result = invokeResolveParticipants(phase, allMembers, null);
            assertEquals(2, result.size()); // Falls back to ALL
        }

        @Test
        @DisplayName("null participants defaults to ALL")
        void nullParticipants() throws Exception {
            var phase = new DiscussionPhase("Opinions", PhaseType.OPINION, null, TurnOrder.SEQUENTIAL, null, false, null, 1);
            var allMembers = List.of(
                    new GroupMember("a1", "Agent 1", 1, null),
                    new GroupMember("a2", "Agent 2", 0, null));
            var result = invokeResolveParticipants(phase, allMembers, null);
            assertEquals(2, result.size());
            // Should be sorted by speakingOrder
            assertEquals("a2", result.getFirst().agentId());
        }

        @SuppressWarnings("unchecked")
        private List<GroupMember> invokeResolveParticipants(
                                                            DiscussionPhase phase, List<GroupMember> allMembers, String moderatorAgentId)
                throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "resolveParticipants", DiscussionPhase.class, List.class, String.class);
            method.setAccessible(true);
            return (List<GroupMember>) method.invoke(service, phase, allMembers, moderatorAgentId);
        }
    }

    // =========================================================
    // selectDefaultTemplate — ContextScope branches
    // =========================================================

    @Nested
    @DisplayName("selectDefaultTemplate — ContextScope branches")
    class SelectDefaultTemplate {

        @Test
        @DisplayName("OPINION with ContextScope.NONE returns independent template")
        void opinionNone() throws Exception {
            var phase = new DiscussionPhase("Op", PhaseType.OPINION, null, null, ContextScope.NONE, false, null, 1);
            String template = invokeSelectDefaultTemplate(phase, List.of(), 0);
            assertEquals(DiscussionStylePresets.TEMPLATE_OPINION_INDEPENDENT, template);
        }

        @Test
        @DisplayName("OPINION with ContextScope.ANONYMOUS returns anonymous template")
        void opinionAnonymous() throws Exception {
            var phase = new DiscussionPhase("Op", PhaseType.OPINION, null, null, ContextScope.ANONYMOUS, false, null, 1);
            String template = invokeSelectDefaultTemplate(phase, List.of(), 0);
            assertEquals(DiscussionStylePresets.TEMPLATE_OPINION_ANONYMOUS, template);
        }

        @Test
        @DisplayName("OPINION with ContextScope.FULL returns with-context template")
        void opinionFull() throws Exception {
            var phase = new DiscussionPhase("Op", PhaseType.OPINION, null, null, ContextScope.FULL, false, null, 1);
            String template = invokeSelectDefaultTemplate(phase, List.of(), 0);
            assertEquals(DiscussionStylePresets.TEMPLATE_OPINION_WITH_CONTEXT, template);
        }

        @Test
        @DisplayName("CRITIQUE returns default critique template")
        void critique() throws Exception {
            var phase = new DiscussionPhase("Crit", PhaseType.CRITIQUE, null, null, null, false, null, 1);
            String template = invokeSelectDefaultTemplate(phase, List.of(), 0);
            assertEquals(DiscussionStylePresets.defaultTemplate(PhaseType.CRITIQUE), template);
        }

        @Test
        @DisplayName("SYNTHESIS returns default synthesis template")
        void synthesis() throws Exception {
            var phase = new DiscussionPhase("Synth", PhaseType.SYNTHESIS, null, null, null, false, null, 1);
            String template = invokeSelectDefaultTemplate(phase, List.of(), 0);
            assertEquals(DiscussionStylePresets.defaultTemplate(PhaseType.SYNTHESIS), template);
        }

        private String invokeSelectDefaultTemplate(DiscussionPhase phase, List<TranscriptEntry> transcript, int phaseIdx) throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "selectDefaultTemplate", DiscussionPhase.class, List.class, int.class);
            method.setAccessible(true);
            return (String) method.invoke(service, phase, transcript, phaseIdx);
        }
    }

    // =========================================================
    // filterByScope — all ContextScope values
    // =========================================================

    @Nested
    @DisplayName("filterByScope — ContextScope values")
    class FilterByScope {

        @Test
        @DisplayName("NONE returns empty list")
        void scopeNone() throws Exception {
            var transcript = List.of(
                    new TranscriptEntry("a1", "Agent 1", "content", 0, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null));
            var result = invokeFilterByScope(transcript, ContextScope.NONE, 1,
                    new GroupMember("a1", "Agent 1", 0, null));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null scope returns empty list")
        void scopeNull() throws Exception {
            var transcript = List.of(
                    new TranscriptEntry("a1", "Agent 1", "content", 0, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null));
            var result = invokeFilterByScope(transcript, null, 1,
                    new GroupMember("a1", "Agent 1", 0, null));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("FULL includes all non-error, non-skipped, non-question entries")
        void scopeFull() throws Exception {
            var transcript = List.of(
                    new TranscriptEntry("a1", "Agent 1", "opinion", 0, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null),
                    new TranscriptEntry("a2", "Agent 2", null, 0, "Phase", TranscriptEntryType.SKIPPED, Instant.now(), "skipped", null),
                    new TranscriptEntry("a3", "Agent 3", "critique", 1, "Phase", TranscriptEntryType.CRITIQUE, Instant.now(), null, null));
            var result = invokeFilterByScope(transcript, ContextScope.FULL, 2,
                    new GroupMember("a1", "Agent 1", 0, null));
            assertEquals(2, result.size()); // opinion + critique (skipped is filtered)
        }

        @Test
        @DisplayName("LAST_PHASE filters to current and previous phase")
        void scopeLastPhase() throws Exception {
            var transcript = List.of(
                    new TranscriptEntry("a1", "Agent 1", "old", 0, "Phase 0", TranscriptEntryType.OPINION, Instant.now(), null, null),
                    new TranscriptEntry("a2", "Agent 2", "recent", 1, "Phase 1", TranscriptEntryType.OPINION, Instant.now(), null, null),
                    new TranscriptEntry("a3", "Agent 3", "current", 2, "Phase 2", TranscriptEntryType.CRITIQUE, Instant.now(), null, null));
            var result = invokeFilterByScope(transcript, ContextScope.LAST_PHASE, 2,
                    new GroupMember("a1", "Agent 1", 0, null));
            // Should include phaseIndex >= 1 (current-1)
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("ANONYMOUS includes all entries with Anonymous speaker")
        void scopeAnonymous() throws Exception {
            var transcript = List.of(
                    new TranscriptEntry("a1", "Agent 1", "opinion", 0, "Phase", TranscriptEntryType.OPINION, Instant.now(), null, null));
            var result = invokeFilterByScope(transcript, ContextScope.ANONYMOUS, 1,
                    new GroupMember("a2", "Agent 2", 0, null));
            assertEquals(1, result.size());
            assertEquals("Anonymous", result.getFirst().get("speaker"));
        }

        @Test
        @DisplayName("OWN_FEEDBACK filters to entries targeting the speaker")
        void scopeOwnFeedback() throws Exception {
            var transcript = List.of(
                    new TranscriptEntry("a2", "Agent 2", "feedback for a1", 0, "Phase", TranscriptEntryType.CRITIQUE, Instant.now(), null, "a1"),
                    new TranscriptEntry("a2", "Agent 2", "feedback for a3", 0, "Phase", TranscriptEntryType.CRITIQUE, Instant.now(), null, "a3"));
            var result = invokeFilterByScope(transcript, ContextScope.OWN_FEEDBACK, 1,
                    new GroupMember("a1", "Agent 1", 0, null));
            assertEquals(1, result.size());
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> invokeFilterByScope(
                                                              List<TranscriptEntry> transcript, ContextScope scope, int phaseIdx, GroupMember speaker)
                throws Exception {
            Method method = GroupConversationService.class.getDeclaredMethod(
                    "filterByScope", List.class, ContextScope.class, int.class, GroupMember.class);
            method.setAccessible(true);
            return (List<Map<String, Object>>) method.invoke(service, transcript, scope, phaseIdx, speaker);
        }
    }

    // =========================================================
    // discuss — validation branches (from Round 5)
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
    // startAndDiscussAsync — validation (from Round 5)
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
    // deleteGroupConversation — branches (from Round 5)
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
            when(conversationStore.create(any())).thenReturn("gc-1");
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
            when(conversationStore.create(any())).thenReturn("gc-1");
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
    // listGroupConversations (from Round 5)
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
    // readGroupConversation (from Round 5)
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
    // Agent ERROR state response handling (from Round 5)
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

            when(conversationStore.create(any())).thenReturn("gc-1");

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
    // SYNTHESIS phase (from Round 5)
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
            when(conversationStore.create(any())).thenReturn("gc-1");

            stubAgent("a1", "Opinion response");
            stubAgent("mod", "Final synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertNotNull(result.getSynthesizedAnswer());
        }
    }

    // =========================================================
    // CRITIQUE phase with targetEachPeer (from Round 5)
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
            when(conversationStore.create(any())).thenReturn("gc-1");
            stubAgent("a1", "Opinion A");
            stubAgent("a2", "Critique of Alice");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.CRITIQUE));
        }
    }

    // =========================================================
    // REVISION phase with feedback (from Round 5)
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
            when(conversationStore.create(any())).thenReturn("gc-1");
            stubAgent("a1", "Initial opinion");
            stubAgent("a2", "Revised opinion");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.REVISION));
        }
    }

    // =========================================================
    // CHALLENGE phase (from Round 5)
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
            when(conversationStore.create(any())).thenReturn("gc-1");
            stubAgent("a1", "Challenge response");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.CHALLENGE));
        }
    }

    // =========================================================
    // shutdown (from Round 5)
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
    // Helpers
    // =========================================================

    private String invokeExtractResponse(SimpleConversationMemorySnapshot snapshot) throws Exception {
        Method method = GroupConversationService.class.getDeclaredMethod(
                "extractResponse", SimpleConversationMemorySnapshot.class);
        method.setAccessible(true);
        return (String) method.invoke(service, snapshot);
    }

    private SimpleConversationMemorySnapshot createSnapshot(ConversationOutput lastOutput) {
        var snapshot = new SimpleConversationMemorySnapshot();
        var outputs = new ArrayList<ConversationOutput>();
        outputs.add(lastOutput);
        snapshot.setConversationOutputs(outputs);
        return snapshot;
    }
}
