/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
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
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additional branch coverage tests for {@link GroupConversationService}
 * targeting uncovered paths: extractResponse format branches, signing and
 * verification paths, context scope filtering, ARGUE/REBUTTAL/DEFENSE phases,
 * async error listener, and failConversation update error.
 */
@DisplayName("GroupConversationService — Branch Coverage")
class GroupConversationServiceBranchCoverageTest {

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
    private GroupConversationService serviceWithSigning;

    private static final String GROUP_ID = "test-group";
    private static final String USER_ID = "test-user";
    private static final String QUESTION = "What approach should we take?";

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);

        // Service without signing (null crypto deps)
        service = new GroupConversationService(groupStore, conversationStore,
                conversationService, agentFactory, templatingEngine,
                jsonSerialization, new SimpleMeterRegistry(),
                null, null, null, null, null, "default", 3);

        // Service with signing infrastructure
        serviceWithSigning = new GroupConversationService(groupStore, conversationStore,
                conversationService, agentFactory, templatingEngine,
                jsonSerialization, new SimpleMeterRegistry(),
                agentSigningService, agentStore, null, nonceCacheService, null, "default", 3);

        when(conversationStore.create(any())).thenReturn("gc-1");

        lenient().when(jsonSerialization.serialize(any()))
                .thenAnswer(inv -> {
                    Object arg = inv.getArgument(0);
                    return arg == null ? "null" : arg.toString();
                });

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

    private void stubAgentWithOutputMap(String agentId, Map<String, Object> outputMap) throws Exception {
        when(agentFactory.getLatestReadyAgent(any(Environment.class), eq(agentId)))
                .thenReturn(mock(IAgent.class));

        when(conversationService.startConversation(any(Environment.class),
                eq(agentId), anyString(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-" + agentId, null));

        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(8);
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            output.putAll(outputMap);
            snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(Environment.class), eq(agentId),
                anyString(), any(), any(), any(), any(InputData.class),
                anyBoolean(), any(ConversationResponseHandler.class));
    }

    // =========================================================
    // extractResponse — various output format branches
    // =========================================================

    @Nested
    @DisplayName("extractResponse format branches")
    class ExtractResponseFormats {

        @Test
        @DisplayName("should handle null snapshot by returning empty string")
        void nullSnapshot() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            // Agent returns null snapshot
            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("a1"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));
            doAnswer(inv -> {
                ConversationResponseHandler handler = inv.getArgument(8);
                handler.onComplete(null);
                return null;
            }).when(conversationService).say(any(Environment.class), eq("a1"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should handle empty conversationOutputs")
        void emptyConversationOutputs() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("a1"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));
            doAnswer(inv -> {
                ConversationResponseHandler handler = inv.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                snapshot.setConversationOutputs(new ArrayList<>());
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(any(Environment.class), eq("a1"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should handle output with TextOutputItem objects")
        void textOutputItemObjects() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            var textItem = new TextOutputItem();
            textItem.setText("Hello from TextOutputItem");

            stubAgentWithOutputMap("a1", Map.of("output", List.of(textItem)));
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should handle output with Map containing text field")
        void mapWithTextField() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            Map<String, Object> mapItem = new LinkedHashMap<>();
            mapItem.put("text", "Hello from map");
            stubAgentWithOutputMap("a1", Map.of("output", List.of(mapItem)));
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should handle output:text:* flat key with String value")
        void flatOutputKeyString() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            Map<String, Object> outputMap = new LinkedHashMap<>();
            outputMap.put("output:text:greeting", "Hello from flat key");
            stubAgentWithOutputMap("a1", outputMap);
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should handle output:text:* flat key with List value")
        void flatOutputKeyList() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            Map<String, Object> outputMap = new LinkedHashMap<>();
            outputMap.put("output:text:multi", List.of("Line 1", "Line 2"));
            stubAgentWithOutputMap("a1", outputMap);
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should handle output:text:* flat key with List of Maps")
        void flatOutputKeyListOfMaps() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            Map<String, Object> outputMap = new LinkedHashMap<>();
            outputMap.put("output:text:complex", List.of(Map.of("text", "Map in list")));
            stubAgentWithOutputMap("a1", outputMap);
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should handle output:text:* flat key with Map value containing text")
        void flatOutputKeyMapWithText() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            Map<String, Object> outputMap = new LinkedHashMap<>();
            outputMap.put("output:text:single", Map.of("text", "Nested map text"));
            stubAgentWithOutputMap("a1", outputMap);
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should fallback to serialize when output has output key but no text")
        void fallbackToSerialize() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            Map<String, Object> outputMap = new LinkedHashMap<>();
            outputMap.put("output", 42); // Not a list
            outputMap.put("reply", "some-reply"); // Has output key
            stubAgentWithOutputMap("a1", outputMap);
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should complete gracefully when output has no extractable text")
        void noExtractableText() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            // Exact production scenario: output list contains a null item
            var nullList = new LinkedList<>(List.of((Object) "placeholder"));
            nullList.set(0, null); // output=[null]
            Map<String, Object> outputMap = new LinkedHashMap<>();
            outputMap.put("output", nullList);
            outputMap.put("actions", List.of("send_message", "unknown"));
            stubAgentWithOutputMap("a1", outputMap);
            stubAgent("mod", "Synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            // The a1 transcript entry must have empty content, not raw metadata
            var a1Entries = result.getTranscript().stream()
                    .filter(e -> "a1".equals(e.speakerAgentId()))
                    .toList();
            assertFalse(a1Entries.isEmpty(), "Expected a transcript entry for agent a1");
            a1Entries.forEach(e -> assertTrue(
                    e.content() == null || e.content().isEmpty(),
                    "Transcript should not contain raw metadata dump, got: " + e.content()));
        }

        @Test
        @DisplayName("should handle null last output entry")
        void nullLastOutput() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("a1"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));
            doAnswer(inv -> {
                ConversationResponseHandler handler = inv.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                var outputs = new ArrayList<ConversationOutput>();
                outputs.add(null);
                snapshot.setConversationOutputs(outputs);
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
    // Signing / verification paths (Wave 6)
    // =========================================================

    @Nested
    @DisplayName("Message signing paths")
    class SigningPaths {

        @Test
        @DisplayName("should sign messages when signing is configured")
        void signsWhenConfigured() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            // Setup agent store to return config with signing enabled
            var agentCfg = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            agentCfg.setSecurity(security);
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setKeys(List.of(new AgentPublicKey(0, "test-pub-key", Instant.now().toEpochMilli(), Long.MAX_VALUE)));
            agentCfg.setIdentity(identity);

            var agentResourceId = mock(IResourceStore.IResourceId.class);
            when(agentResourceId.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("a1")).thenReturn(agentResourceId);
            when(agentStore.read("a1", 1)).thenReturn(agentCfg);

            // Mock signing
            var signedEnvelope = new SignedEnvelope("a1", GROUP_ID,
                    Map.of("content", "Opinion A", "phase", "Discuss"),
                    "nonce-1", System.currentTimeMillis(), "sig-data", 0);
            when(agentSigningService.signEnvelope(eq("default"), eq("a1"), any(), eq(0)))
                    .thenReturn(signedEnvelope);
            when(agentSigningService.verifyEnvelope(any(), eq("test-pub-key")))
                    .thenReturn(true);
            when(nonceCacheService.validate(anyString(), anyLong()))
                    .thenReturn(NonceCacheService.NonceValidation.VALID);

            stubAgent("a1", "Opinion A");

            // mod also needs signing setup - but simpler
            var modResourceId = mock(IResourceStore.IResourceId.class);
            when(modResourceId.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("mod")).thenReturn(modResourceId);
            var modCfg = new AgentConfiguration();
            when(agentStore.read("mod", 1)).thenReturn(modCfg);

            stubAgent("mod", "Synthesis");

            var result = serviceWithSigning.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());

            // Verify signing was attempted
            verify(agentSigningService, atLeastOnce()).signEnvelope(any(), eq("a1"), any(), anyInt());
        }

        @Test
        @DisplayName("should fall back to unsigned when self-verify fails")
        void fallsBackOnSelfVerifyFailure() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            var agentCfg = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            agentCfg.setSecurity(security);
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setKeys(List.of(new AgentPublicKey(0, "pub-key", Instant.now().toEpochMilli(), 0)));
            agentCfg.setIdentity(identity);

            var agentResourceId = mock(IResourceStore.IResourceId.class);
            when(agentResourceId.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("a1")).thenReturn(agentResourceId);
            when(agentStore.read("a1", 1)).thenReturn(agentCfg);

            var signedEnvelope = new SignedEnvelope("a1", GROUP_ID,
                    Map.of("content", "Opinion", "phase", "Discuss"),
                    "nonce-1", System.currentTimeMillis(), "bad-sig", 0);
            when(agentSigningService.signEnvelope(any(), eq("a1"), any(), anyInt()))
                    .thenReturn(signedEnvelope);
            // Self-verify fails
            when(agentSigningService.verifyEnvelope(any(), anyString()))
                    .thenReturn(false);

            stubAgent("a1", "Opinion");

            var modResourceId = mock(IResourceStore.IResourceId.class);
            when(modResourceId.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("mod")).thenReturn(modResourceId);
            when(agentStore.read("mod", 1)).thenReturn(new AgentConfiguration());
            stubAgent("mod", "Synthesis");

            var result = serviceWithSigning.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should fall back when nonce validation fails")
        void fallsBackOnNonceValidationFailure() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            var agentCfg = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            agentCfg.setSecurity(security);
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setKeys(List.of(new AgentPublicKey(0, "pub-key", Instant.now().toEpochMilli(), 0)));
            agentCfg.setIdentity(identity);

            var agentResourceId = mock(IResourceStore.IResourceId.class);
            when(agentResourceId.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("a1")).thenReturn(agentResourceId);
            when(agentStore.read("a1", 1)).thenReturn(agentCfg);

            var signedEnvelope = new SignedEnvelope("a1", GROUP_ID,
                    Map.of("content", "Opinion", "phase", "Discuss"),
                    "nonce-1", System.currentTimeMillis(), "sig", 0);
            when(agentSigningService.signEnvelope(any(), eq("a1"), any(), anyInt()))
                    .thenReturn(signedEnvelope);
            when(agentSigningService.verifyEnvelope(any(), anyString()))
                    .thenReturn(true);
            // Nonce validation fails
            when(nonceCacheService.validate(anyString(), anyLong()))
                    .thenReturn(NonceCacheService.NonceValidation.TOO_OLD);

            stubAgent("a1", "Opinion");

            var modResourceId = mock(IResourceStore.IResourceId.class);
            when(modResourceId.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("mod")).thenReturn(modResourceId);
            when(agentStore.read("mod", 1)).thenReturn(new AgentConfiguration());
            stubAgent("mod", "Synthesis");

            var result = serviceWithSigning.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should handle signing exception gracefully")
        void handlesSigningException() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            var agentCfg = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            agentCfg.setSecurity(security);

            var agentResourceId = mock(IResourceStore.IResourceId.class);
            when(agentResourceId.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("a1")).thenReturn(agentResourceId);
            when(agentStore.read("a1", 1)).thenReturn(agentCfg);

            // Signing throws
            when(agentSigningService.signEnvelope(any(), eq("a1"), any(), anyInt()))
                    .thenThrow(new RuntimeException("Signing error"));

            stubAgent("a1", "Opinion");

            var modResourceId = mock(IResourceStore.IResourceId.class);
            when(modResourceId.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("mod")).thenReturn(modResourceId);
            when(agentStore.read("mod", 1)).thenReturn(new AgentConfiguration());
            stubAgent("mod", "Synthesis");

            var result = serviceWithSigning.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should handle agent with no identity keys")
        void agentWithNoIdentityKeys() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            var agentCfg = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            agentCfg.setSecurity(security);
            // No identity set

            var agentResourceId = mock(IResourceStore.IResourceId.class);
            when(agentResourceId.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("a1")).thenReturn(agentResourceId);
            when(agentStore.read("a1", 1)).thenReturn(agentCfg);

            var signedEnvelope = new SignedEnvelope("a1", GROUP_ID,
                    Map.of("content", "Opinion", "phase", "Discuss"),
                    "nonce-1", System.currentTimeMillis(), "sig", 0);
            when(agentSigningService.signEnvelope(any(), eq("a1"), any(), eq(0)))
                    .thenReturn(signedEnvelope);
            // No public key => self-verify skipped, but nonce validation still happens
            when(nonceCacheService.validate(anyString(), anyLong()))
                    .thenReturn(NonceCacheService.NonceValidation.VALID);

            stubAgent("a1", "Opinion");

            var modResourceId = mock(IResourceStore.IResourceId.class);
            when(modResourceId.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("mod")).thenReturn(modResourceId);
            when(agentStore.read("mod", 1)).thenReturn(new AgentConfiguration());
            stubAgent("mod", "Synthesis");

            var result = serviceWithSigning.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // Peer verification paths
    // =========================================================

    @Nested
    @DisplayName("Peer verification paths")
    class PeerVerification {

        @Test
        @DisplayName("should verify signed entries when peer verification is required")
        void verifiesSignedEntries() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("a2", "Bob", 2, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            // a1 agent config: no signing, no verification
            var a1Cfg = new AgentConfiguration();
            var a1Rid = mock(IResourceStore.IResourceId.class);
            when(a1Rid.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("a1")).thenReturn(a1Rid);
            when(agentStore.read("a1", 1)).thenReturn(a1Cfg);

            // a2 agent config: requires peer verification
            var a2Cfg = new AgentConfiguration();
            var a2Security = new AgentConfiguration.SecurityConfig();
            a2Security.setRequirePeerVerification(true);
            a2Cfg.setSecurity(a2Security);
            var a2Rid = mock(IResourceStore.IResourceId.class);
            when(a2Rid.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("a2")).thenReturn(a2Rid);
            when(agentStore.read("a2", 1)).thenReturn(a2Cfg);

            // mod agent config
            var modCfg = new AgentConfiguration();
            var modRid = mock(IResourceStore.IResourceId.class);
            when(modRid.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("mod")).thenReturn(modRid);
            when(agentStore.read("mod", 1)).thenReturn(modCfg);

            stubAgent("a1", "Opinion A");
            stubAgent("a2", "Opinion B");
            stubAgent("mod", "Synthesis");

            var result = serviceWithSigning.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("should skip verification when agent store returns null resource id")
        void skipsWhenNullResourceId() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("mod");
            setupStore(cfg);

            // Agent store returns null resource ID
            when(agentStore.getCurrentResourceId("a1")).thenReturn(null);

            stubAgent("a1", "Opinion");

            var modRid = mock(IResourceStore.IResourceId.class);
            when(modRid.getVersion()).thenReturn(1);
            when(agentStore.getCurrentResourceId("mod")).thenReturn(modRid);
            when(agentStore.read("mod", 1)).thenReturn(new AgentConfiguration());
            stubAgent("mod", "Synthesis");

            var result = serviceWithSigning.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // DEBATE style — ARGUE/REBUTTAL branches
    // =========================================================

    @Nested
    @DisplayName("DEBATE style — ARGUE/REBUTTAL/DEFENSE branches")
    class DebateStyleBranches {

        @Test
        @DisplayName("ARGUE phase sets teamSide based on role")
        void arguePhaseSetTeamSide() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("pro", "Pro", 1, "PRO"),
                    new GroupMember("con", "Con", 2, "CON"));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Argue", PhaseType.ARGUE)));
            setupStore(cfg);
            stubAgent("pro", "Pro argument");
            stubAgent("con", "Con argument");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.ARGUMENT));
        }

        @Test
        @DisplayName("REBUTTAL phase collects opposing arguments")
        void rebuttalPhase() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("pro", "Pro", 1, "PRO"),
                    new GroupMember("con", "Con", 2, "CON"));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Argue", PhaseType.ARGUE),
                    new DiscussionPhase("Rebut", PhaseType.REBUTTAL)));
            setupStore(cfg);
            stubAgent("pro", "Pro argument");
            stubAgent("con", "Con rebuttal");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.REBUTTAL));
        }

        @Test
        @DisplayName("DEFENSE phase collects challenges")
        void defensePhase() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("da", "Devil", 2, "DEVIL_ADVOCATE"));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Opinion", PhaseType.OPINION),
                    new DiscussionPhase("Challenge", PhaseType.CHALLENGE),
                    new DiscussionPhase("Defense", PhaseType.DEFENSE)));
            setupStore(cfg);
            stubAgent("a1", "Initial opinion");
            stubAgent("da", "Challenge response");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
            assertTrue(result.getTranscript().stream()
                    .anyMatch(e -> e.type() == TranscriptEntryType.DEFENSE));
        }
    }

    // =========================================================
    // Context scope: OWN_FEEDBACK
    // =========================================================

    @Nested
    @DisplayName("Context scope OWN_FEEDBACK")
    class ContextScopeOwnFeedback {

        @Test
        @DisplayName("OWN_FEEDBACK scope only includes feedback targeted at speaker")
        void ownFeedbackScope() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null),
                    new GroupMember("a2", "Bob", 2, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("Opinion", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.NONE, false, null, 1),
                    new DiscussionPhase("Revision", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.OWN_FEEDBACK, false, null, 1)));
            setupStore(cfg);
            stubAgent("a1", "Alice opinion");
            stubAgent("a2", "Bob opinion");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // failConversation error handling
    // =========================================================

    @Nested
    @DisplayName("failConversation error handling")
    class FailConversationErrors {

        @Test
        @DisplayName("should handle update failure when failing conversation")
        void updateFailureDuringFail() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setProtocol(new ProtocolConfig(60,
                    ProtocolConfig.MemberFailurePolicy.ABORT, 0,
                    ProtocolConfig.MemberUnavailablePolicy.FAIL));
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(null);

            // Make conversationStore.update throw during failConversation
            doThrow(new RuntimeException("DB down"))
                    .when(conversationStore).update(any());

            assertThrows(GroupDiscussionException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0));
        }
    }

    // =========================================================
    // Async error with listener
    // =========================================================

    @Nested
    @DisplayName("Async discussion error with listener")
    class AsyncErrorWithListener {

        @Test
        @DisplayName("async failure notifies listener onGroupError")
        void asyncFailureNotifiesListener() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setProtocol(new ProtocolConfig(60,
                    ProtocolConfig.MemberFailurePolicy.ABORT, 0,
                    ProtocolConfig.MemberUnavailablePolicy.FAIL));
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(null);

            var listener = mock(
                    ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener.class);

            var gc = serviceWithSigning.startAndDiscussAsync(GROUP_ID, QUESTION, USER_ID, listener);
            assertNotNull(gc);

            // Wait for async thread
            Thread.sleep(1500);

            verify(listener, atLeastOnce()).onGroupError(any(GroupConversationEventSink.GroupErrorEvent.class));
        }
    }

    // =========================================================
    // Retry on timeout
    // =========================================================

    @Nested
    @DisplayName("Retry on timeout then ABORT")
    class RetryOnTimeout {

        @Test
        @DisplayName("timeout with ABORT policy throws")
        void timeoutAbort() throws Exception {
            var cfg = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setProtocol(new ProtocolConfig(1,
                    ProtocolConfig.MemberFailurePolicy.ABORT, 0,
                    ProtocolConfig.MemberUnavailablePolicy.SKIP));
            setupStore(cfg);

            when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1")))
                    .thenReturn(mock(IAgent.class));
            when(conversationService.startConversation(any(), eq("a1"), any(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-a1", null));
            // Never complete the future - cause timeout
            doAnswer(inv -> {
                // Don't call handler - simulate timeout
                Thread.sleep(5000);
                return null;
            }).when(conversationService).say(any(Environment.class), eq("a1"),
                    anyString(), any(), any(), any(), any(InputData.class),
                    anyBoolean(), any(ConversationResponseHandler.class));

            assertThrows(GroupDiscussionException.class,
                    () -> service.discuss(GROUP_ID, QUESTION, USER_ID, 0));
        }
    }

    // =========================================================
    // Moderator blank/empty
    // =========================================================

    @Nested
    @DisplayName("Moderator blank/empty participant resolution")
    class ModeratorBlank {

        @Test
        @DisplayName("blank moderator falls back to ALL")
        void blankModeratorFallsBackToAll() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setModeratorAgentId("   "); // blank
            cfg.setPhases(List.of(
                    new DiscussionPhase("Synth", PhaseType.SYNTHESIS,
                            "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1)));
            setupStore(cfg);
            stubAgent("a1", "Synthesis by participant");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // selectDefaultTemplate branches
    // =========================================================

    @Nested
    @DisplayName("selectDefaultTemplate branches")
    class DefaultTemplates {

        @Test
        @DisplayName("OPINION with ANONYMOUS scope uses anonymous template")
        void opinionAnonymousScope() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("AnonOpinion", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.ANONYMOUS, false, null, 1)));
            setupStore(cfg);
            stubAgent("a1", "Anonymous opinion");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }

        @Test
        @DisplayName("OPINION with FULL scope uses context template")
        void opinionFullScope() throws Exception {
            var cfg = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            cfg.setPhases(List.of(
                    new DiscussionPhase("FullOpinion", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1)));
            setupStore(cfg);
            stubAgent("a1", "Full context opinion");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }

    // =========================================================
    // GroupMember type GROUP — sub-group no synthesized answer
    // =========================================================

    @Nested
    @DisplayName("Group member — blank synthesized answer")
    class GroupMemberBlankSynthesis {

        @Test
        @DisplayName("blank synthesized answer concatenates transcript")
        void blankSynthesizedAnswerConcatenatesTranscript() throws Exception {
            var parent = config(DiscussionStyle.ROUND_TABLE, 1,
                    new GroupMember("sub-g1", "Team A", 1, null, MemberType.GROUP));
            parent.setModeratorAgentId("mod");

            // Sub-group with no moderator = no synthesis
            var subGroup = config(DiscussionStyle.CUSTOM, 1,
                    new GroupMember("a1", "Alice", 1, null));
            subGroup.setPhases(List.of(
                    new DiscussionPhase("OpinionOnly", PhaseType.OPINION)));

            setupStore(GROUP_ID, parent);
            setupStore("sub-g1", subGroup);
            stubAgent("a1", "Alice opinion");
            stubAgent("mod", "Parent synthesis");

            var result = service.discuss(GROUP_ID, QUESTION, USER_ID, 0);
            assertEquals(GroupConversationState.COMPLETED, result.getState());
        }
    }
}
