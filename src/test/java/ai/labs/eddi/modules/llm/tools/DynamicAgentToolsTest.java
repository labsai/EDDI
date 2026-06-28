/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupException;
import ai.labs.eddi.engine.setup.SetupAgentRequest;
import ai.labs.eddi.engine.setup.SetupResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the Dynamic Agent LLM tools: CreateSubAgentTool,
 * ConverseWithAgentTool, FindAgentsByCapabilityTool, and TeardownAgentTool.
 *
 * @since 6.0.0
 */
class DynamicAgentToolsTest {

    // === CreateSubAgentTool ===

    @Nested
    class CreateSubAgentToolTest {

        private AgentSetupService agentSetupService;
        private IConversationService conversationService;
        private DynamicAgentConfig config;
        private List<String> createdAgentIds;
        private Set<String> retainedAgentIds;
        private CreateSubAgentTool tool;

        @BeforeEach
        void setUp() {
            agentSetupService = mock(AgentSetupService.class);
            conversationService = mock(IConversationService.class);
            config = new DynamicAgentConfig();
            config.setEnabled(true);
            config.setAllowCreation(true);
            config.setMaxCreatedAgentsPerDiscussion(5);
            createdAgentIds = new CopyOnWriteArrayList<>();
            retainedAgentIds = ConcurrentHashMap.newKeySet();
            tool = new CreateSubAgentTool(agentSetupService,
                    conversationService, "parent-agent-1", "user-1", config, createdAgentIds, retainedAgentIds);
        }

        @Test
        void createSubAgent_success() throws Exception {
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/DataAnalyst",
                            "anthropic", "claude-sonnet-4-6", true, "ready", null, null, null, null, null));

            String result = tool.createSubAgent("DataAnalyst", "You analyze data", "anthropic", "claude-sonnet-4-6", null, null);

            assertTrue(result.contains("✅"));
            assertTrue(result.contains("sub-agent-1"));
            assertEquals(1, createdAgentIds.size());
            assertEquals("sub-agent-1", createdAgentIds.get(0));
        }

        @Test
        void createSubAgent_creationDisabled() {
            config.setAllowCreation(false);

            String result = tool.createSubAgent("Test", "prompt", null, null, null, null);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not enabled"));
            assertTrue(createdAgentIds.isEmpty());
        }

        @Test
        void createSubAgent_maxReached() throws Exception {
            config.setMaxCreatedAgentsPerDiscussion(2);
            createdAgentIds.add("agent-1");
            createdAgentIds.add("agent-2");

            String result = tool.createSubAgent("Test", "prompt", null, null, null, null);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("Maximum created agents"));
            verify(agentSetupService, never()).setupAgent(any());
        }

        @Test
        void createSubAgent_providerNotAllowed() {
            config.setAllowedProviders(List.of("openai"));

            String result = tool.createSubAgent("Test", "prompt", "anthropic", null, null, null);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not allowed"));
        }

        @Test
        void createSubAgent_modelNotAllowed() {
            config.setAllowedProviders(List.of("openai"));
            config.setAllowedModels(Map.of("openai", List.of("gpt-4o-mini")));

            String result = tool.createSubAgent("Test", "prompt", "openai", "gpt-4o", null, null);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not allowed"));
        }

        @Test
        void createSubAgent_modelNotAllowed_providerOmitted() {
            // When provider is omitted, model must still be checked against all provider
            // lists
            config.setAllowedModels(Map.of("openai", List.of("gpt-4o-mini")));

            String result = tool.createSubAgent("Test", "prompt", null, "unknown-model", null, null);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not in any provider"));
        }

        @Test
        void createSubAgent_quotaEnforcedByConversationStart() throws Exception {
            // Quota is enforced by startConversation() internally, not by
            // CreateSubAgentTool.
            // The tool no longer holds a TenantQuotaService reference at all.
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            null, null, true, "ready", null, null, null, null, null));

            String result = tool.createSubAgent("Test", "prompt", null, null, null, null);

            assertTrue(result.contains("✅"));
        }

        @Test
        void createSubAgent_nameRequired() {
            String result = tool.createSubAgent(null, "prompt", null, null, null, null);
            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("name is required"));
        }

        @Test
        void createSubAgent_promptRequired() {
            String result = tool.createSubAgent("Test", null, null, null, null, null);
            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("prompt is required"));
        }

        @Test
        void createSubAgent_setupFailure() throws Exception {
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenThrow(new AgentSetupException("DB error"));

            String result = tool.createSubAgent("Test", "prompt", null, null, null, null);

            assertTrue(result.contains("❌"));
            assertTrue(result.contains("DB error"));
            assertTrue(createdAgentIds.isEmpty());
        }

        @Test
        void createSubAgent_retainFlag() throws Exception {
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            null, null, true, "ready", null, null, null, null, null));

            String result = tool.createSubAgent("Test", "prompt", null, null, null, true);

            assertTrue(result.contains("✅"));
            assertTrue(result.contains("retained"));
            assertTrue(retainedAgentIds.contains("sub-agent-1"));
        }

        @Test
        void createSubAgent_modelAllowed_caseInsensitiveKey() throws Exception {
            // Config uses mixed-case key "OpenAI", but caller passes lowercase "openai"
            config.setAllowedProviders(List.of("openai"));
            config.setAllowedModels(Map.of("OpenAI", List.of("gpt-4o-mini")));

            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            "openai", "gpt-4o-mini", true, "ready", null, null, null, null, null));

            String result = tool.createSubAgent("Test", "prompt", "openai", "gpt-4o-mini", null, null);

            assertTrue(result.contains("✅"));
        }

        @Test
        void createSubAgent_modelAllowed_providerOmitted_positiveCase() throws Exception {
            // Model exists in one of the provider lists — should be allowed
            config.setAllowedModels(Map.of("openai", List.of("gpt-4o-mini")));

            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            null, "gpt-4o-mini", true, "ready", null, null, null, null, null));

            String result = tool.createSubAgent("Test", "prompt", null, "gpt-4o-mini", null, null);

            assertTrue(result.contains("✅"));
        }

        @Test
        void createSubAgent_configDisabled() {
            config.setEnabled(false);
            config.setAllowCreation(true);

            String result = tool.createSubAgent("Test", "prompt", null, null, null, null);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not enabled"));
        }

        @Test
        void createSubAgent_withInitialMessage_success() throws Exception {
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            "openai", "gpt-4o-mini", true, "ready", null, null, null, null, null));

            // Stub startConversation
            when(conversationService.startConversation(any(Environment.class), eq("sub-agent-1"), eq("user-1"), anyMap()))
                    .thenReturn(new ConversationResult("conv-123", null));

            // Stub say() — callback with snapshot containing output
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationId("conv-123");
            snapshot.setConversationState(ConversationState.READY);
            var output = new ConversationOutput();
            output.put("output", List.of("Hello from sub-agent!"));
            snapshot.setConversationOutputs(List.of(output));

            doAnswer(invocation -> {
                IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(
                    any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

            String result = tool.createSubAgent("Test", "prompt", "openai", "gpt-4o-mini", "hi", null);

            assertTrue(result.contains("✅"));
            assertTrue(result.contains("conv-123"));
            assertTrue(result.contains("Hello from sub-agent!"));
            assertTrue(result.contains("Provider: openai"));
            assertTrue(result.contains("Model: gpt-4o-mini"));
        }

        @Test
        void createSubAgent_withInitialMessage_failure() throws Exception {
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            null, null, true, "ready", null, null, null, null, null));

            // Stub startConversation to throw
            when(conversationService.startConversation(any(Environment.class), eq("sub-agent-1"), eq("user-1"), anyMap()))
                    .thenThrow(new RuntimeException("Not deployed"));

            String result = tool.createSubAgent("Test", "prompt", null, null, "hi", null);

            assertTrue(result.contains("✅")); // Agent was created successfully
            assertTrue(result.contains("Initial message failed"));
        }

        @Test
        void createSubAgent_noProviderNoModel_omitsFromResult() throws Exception {
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            null, null, true, "ready", null, null, null, null, null));

            String result = tool.createSubAgent("Test", "prompt", null, null, null, null);

            assertTrue(result.contains("✅"));
            assertFalse(result.contains("Provider:"));
            assertFalse(result.contains("Model:"));
        }

        @Test
        void createSubAgent_unexpectedException() throws Exception {
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenThrow(new RuntimeException("Unexpected"));

            String result = tool.createSubAgent("Test", "prompt", null, null, null, null);

            assertTrue(result.contains("❌"));
            assertTrue(result.contains("Unexpected"));
        }

        @Test
        void createSubAgent_blankName() {
            String result = tool.createSubAgent("  ", "prompt", null, null, null, null);
            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("name is required"));
        }

        @Test
        void createSubAgent_blankPrompt() {
            String result = tool.createSubAgent("Test", "  ", null, null, null, null);
            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("prompt is required"));
        }

        @Test
        void createSubAgent_emptyProviderAllowList() throws Exception {
            // Empty (not null) allow list should not restrict
            config.setAllowedProviders(List.of());

            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            "anthropic", null, true, "ready", null, null, null, null, null));

            String result = tool.createSubAgent("Test", "prompt", "anthropic", null, null, null);
            assertTrue(result.contains("✅"));
        }

        @Test
        void createSubAgent_emptyModelAllowList() throws Exception {
            // Empty (not null) model allow list should not restrict
            config.setAllowedModels(Map.of());

            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            null, "any-model", true, "ready", null, null, null, null, null));

            String result = tool.createSubAgent("Test", "prompt", null, "any-model", null, null);
            assertTrue(result.contains("✅"));
        }

        @Test
        void createSubAgent_retainFalse_notTracked() throws Exception {
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            null, null, true, "ready", null, null, null, null, null));

            String result = tool.createSubAgent("Test", "prompt", null, null, null, false);

            assertTrue(result.contains("✅"));
            assertFalse(result.contains("retained"));
            assertTrue(retainedAgentIds.isEmpty());
        }

        @Test
        void createSubAgent_withInitialMessage_extractResponseMapFormat() throws Exception {
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            null, null, true, "ready", null, null, null, null, null));

            when(conversationService.startConversation(any(Environment.class), eq("sub-agent-1"), eq("user-1"), anyMap()))
                    .thenReturn(new ConversationResult("conv-123", null));

            // Snapshot with Map-format output items ("text" key)
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationId("conv-123");
            snapshot.setConversationState(ConversationState.READY);
            var output = new ConversationOutput();
            output.put("output", List.of(Map.of("text", "Map-format response")));
            snapshot.setConversationOutputs(List.of(output));

            doAnswer(invocation -> {
                IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(
                    any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

            String result = tool.createSubAgent("Test", "prompt", null, null, "hi", null);

            assertTrue(result.contains("Map-format response"));
        }

        // --- Null-safety tests (Copilot PR review fixes) ---

        @Test
        @DisplayName("Fix 2: null DynamicAgentConfig defaults to disabled config")
        void createSubAgent_nullConfig_defaultsToDisabled() {
            // Construct with null config — should not NPE
            var toolWithNullConfig = new CreateSubAgentTool(agentSetupService,
                    conversationService, "parent-1", "user-1", null, createdAgentIds, retainedAgentIds);

            // Default DynamicAgentConfig has enabled=false, so creation should be blocked
            String result = toolWithNullConfig.createSubAgent("Test", "prompt", null, null, null, null);
            assertTrue(result.contains("⚠️"), "Null config should default to disabled");
            assertTrue(result.contains("not enabled"), "Should indicate feature is not enabled");
        }

        @Test
        @DisplayName("Fix 3: null entries in allowedProviders list don't cause NPE")
        void createSubAgent_nullEntryInProviderAllowList() {
            // Simulates malformed JSON config with null entries
            var providers = new java.util.ArrayList<String>();
            providers.add(null);
            providers.add("openai");
            config.setAllowedProviders(providers);

            String result = tool.createSubAgent("Test", "prompt", "anthropic", null, null, null);
            assertTrue(result.contains("⚠️"), "Should still enforce allow-list");
            assertTrue(result.contains("not allowed"));
        }

        @Test
        @DisplayName("Fix 4: null values in allowedModels map don't cause NPE (with provider)")
        void createSubAgent_nullModelListInAllowedModels_withProvider() throws Exception {
            // Map with a provider key mapping to null list
            var models = new java.util.HashMap<String, List<String>>();
            models.put("openai", null); // null list for provider
            config.setAllowedModels(models);
            config.setAllowedProviders(List.of("openai"));

            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            "openai", "gpt-4o", true, "ready", null, null, null, null, null));

            // Should not NPE — null list is filtered, so model check is skipped
            String result = tool.createSubAgent("Test", "prompt", "openai", "gpt-4o", null, null);
            assertTrue(result.contains("✅"), "Null model list should be treated as no restriction");
        }

        @Test
        @DisplayName("Fix 4: null values in allowedModels map don't cause NPE (without provider)")
        void createSubAgent_nullModelListInAllowedModels_noProvider() {
            // Map with a provider key mapping to null list
            var models = new java.util.HashMap<String, List<String>>();
            models.put("openai", null);
            config.setAllowedModels(models);

            // Without provider, the check iterates ALL provider values.stream().flatMap()
            // A null list would cause NPE without the Objects::nonNull filter.
            String result = tool.createSubAgent("Test", "prompt", null, "some-model", null, null);
            // Should not NPE; model not found in any list → blocked
            assertTrue(result.contains("⚠️"));
        }

        @Test
        @DisplayName("Fix 4: null model entries in allowedModels list don't cause NPE")
        void createSubAgent_nullModelEntryInList() {
            var modelList = new java.util.ArrayList<String>();
            modelList.add(null);
            modelList.add("gpt-4o");
            config.setAllowedModels(Map.of("openai", modelList));

            // Provider omitted — checks all values; null entries should be filtered
            String result = tool.createSubAgent("Test", "prompt", null, "unknown-model", null, null);
            assertTrue(result.contains("⚠️"), "Should still enforce allow-list despite null entries");
        }

        @Test
        @DisplayName("Constructor with null tracking lists defaults to empty collections")
        void createSubAgent_nullTrackingLists() throws Exception {
            var toolNullLists = new CreateSubAgentTool(agentSetupService,
                    conversationService, "parent-1", "user-1", config, null, null);

            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/Test",
                            null, null, true, "ready", null, null, null, null, null));

            // Should not NPE
            String result = toolNullLists.createSubAgent("Test", "prompt", null, null, null, null);
            assertTrue(result.contains("✅"));
        }
    }

    // === ConverseWithAgentTool ===

    @Nested
    @DisplayName("ConverseWithAgentTool")
    class ConverseWithAgentToolTest {

        private IConversationService conversationService;
        private ConverseWithAgentTool tool;

        private static final String USER_ID = "test-user-1";
        private static final String AGENT_ID = "target-agent-1";
        private static final String CONVERSATION_ID = "conv-12345";

        @BeforeEach
        void setUp() {
            conversationService = mock(IConversationService.class);
            tool = new ConverseWithAgentTool(conversationService, USER_ID);
        }

        /**
         * Helper: create a snapshot with the given outputs and conversation state.
         */
        private SimpleConversationMemorySnapshot createSnapshot(ConversationState state,
                                                                List<String> outputTexts) {
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationId(CONVERSATION_ID);
            snapshot.setConversationState(state);

            if (outputTexts != null && !outputTexts.isEmpty()) {
                var output = new ConversationOutput();
                output.put("output", outputTexts);
                snapshot.setConversationOutputs(List.of(output));
            }

            return snapshot;
        }

        /**
         * Helper: stub conversationService.say() to invoke the callback with the given
         * snapshot.
         */
        private void stubSayWithSnapshot(SimpleConversationMemorySnapshot snapshot) throws Exception {
            doAnswer(invocation -> {
                ConversationResponseHandler handler = invocation.getArgument(8);
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(
                    any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("New conversation: starts conversation + sends message + returns response")
        void converseWithAgent_newConversation_success() throws Exception {
            // Arrange — startConversation returns a new conversation
            when(conversationService.startConversation(any(Environment.class), eq(AGENT_ID), eq(USER_ID), anyMap()))
                    .thenReturn(new ConversationResult(CONVERSATION_ID, URI.create("/conversations/" + CONVERSATION_ID)));

            var snapshot = createSnapshot(ConversationState.READY, List.of("Hello from agent!"));
            stubSayWithSnapshot(snapshot);

            // Act
            String result = tool.converseWithAgent(AGENT_ID, "Hi there", null);

            // Assert
            assertTrue(result.contains("✅"), "Should contain success marker");
            assertTrue(result.contains(CONVERSATION_ID), "Should contain conversation ID");
            assertTrue(result.contains("Hello from agent!"), "Should contain agent response text");

            verify(conversationService).startConversation(any(Environment.class), eq(AGENT_ID), eq(USER_ID), anyMap());
            verify(conversationService).say(
                    any(), eq(AGENT_ID), eq(CONVERSATION_ID), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("Existing conversation: reuses conversationId, does not start new")
        void converseWithAgent_existingConversation_success() throws Exception {
            // Arrange — provide an existing conversation ID
            var snapshot = createSnapshot(ConversationState.READY, List.of("Follow-up response"));
            stubSayWithSnapshot(snapshot);

            // Act
            String result = tool.converseWithAgent(AGENT_ID, "Follow-up question", CONVERSATION_ID);

            // Assert
            assertTrue(result.contains("✅"), "Should contain success marker");
            assertTrue(result.contains(CONVERSATION_ID), "Should contain conversation ID");
            assertTrue(result.contains("Follow-up response"), "Should contain agent response text");

            // Must NOT start a new conversation
            verify(conversationService, never()).startConversation(any(), any(), any(), any());
            verify(conversationService).say(
                    any(), eq(AGENT_ID), eq(CONVERSATION_ID), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("Null agentId returns warning")
        void converseWithAgent_nullAgentId_returnsWarning() {
            String result = tool.converseWithAgent(null, "Hello", null);

            assertTrue(result.contains("⚠️"), "Should contain warning marker");
            assertTrue(result.contains("Agent ID is required"), "Should mention agent ID required");
            verifyNoInteractions(conversationService);
        }

        @Test
        @DisplayName("Null message returns warning")
        void converseWithAgent_nullMessage_returnsWarning() {
            String result = tool.converseWithAgent(AGENT_ID, null, null);

            assertTrue(result.contains("⚠️"), "Should contain warning marker");
            assertTrue(result.contains("Message is required"), "Should mention message required");
            verifyNoInteractions(conversationService);
        }

        @Test
        @DisplayName("Timeout waiting for agent response returns warning")
        void converseWithAgent_timeout_returnsWarning() throws Exception {
            // Arrange — startConversation succeeds but say() never invokes the callback
            when(conversationService.startConversation(any(Environment.class), eq(AGENT_ID), eq(USER_ID), anyMap()))
                    .thenReturn(new ConversationResult(CONVERSATION_ID, URI.create("/conversations/" + CONVERSATION_ID)));

            // say() does NOT invoke the callback — the CompletableFuture will time out.
            // We simulate this by making say() throw a TimeoutException wrapped in
            // ExecutionException
            // Actually the production code calls responseFuture.get(60, TimeUnit.SECONDS)
            // which throws TimeoutException.
            // We need say() to simply not call the handler. But we can't wait 60s in a
            // test.
            // Instead, we make say() throw an exception that gets caught as
            // TimeoutException.
            doAnswer(invocation -> {
                // Don't invoke the handler — but we can't wait 60s.
                // Instead, let's directly throw to simulate the scenario via the outer catch.
                throw new java.util.concurrent.TimeoutException("Simulated timeout");
            }).when(conversationService).say(
                    any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

            // Act
            String result = tool.converseWithAgent(AGENT_ID, "Hello", null);

            // Assert — the outer catch handles TimeoutException
            assertTrue(result.contains("⚠️") || result.contains("❌"),
                    "Should contain a warning or error marker");
            assertTrue(result.toLowerCase().contains("timeout") || result.toLowerCase().contains("error"),
                    "Should mention timeout or error");
        }

        @Test
        @DisplayName("Agent ID required")
        void converseWithAgent_agentIdRequired() {
            String result = tool.converseWithAgent(null, "Hello", null);
            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("Agent ID is required"));
        }

        @Test
        @DisplayName("Blank agent ID rejected")
        void converseWithAgent_blankAgentId() {
            String result = tool.converseWithAgent("  ", "Hello", null);
            assertTrue(result.contains("⚠️"));
        }

        @Test
        @DisplayName("Message required")
        void converseWithAgent_messageRequired() {
            String result = tool.converseWithAgent(AGENT_ID, null, null);
            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("Message is required"));
        }

        @Test
        @DisplayName("Blank message rejected")
        void converseWithAgent_blankMessage() {
            String result = tool.converseWithAgent(AGENT_ID, "  ", null);
            assertTrue(result.contains("⚠️"));
        }

        @Test
        @DisplayName("Map-format output items extracted")
        void converseWithAgent_mapFormatOutput() throws Exception {
            when(conversationService.startConversation(any(Environment.class), eq(AGENT_ID), eq(USER_ID), anyMap()))
                    .thenReturn(new ConversationResult(CONVERSATION_ID, URI.create("/conv")));

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationId(CONVERSATION_ID);
            snapshot.setConversationState(ConversationState.READY);
            var output = new ConversationOutput();
            output.put("output", List.of(Map.of("text", "Map text")));
            snapshot.setConversationOutputs(List.of(output));
            stubSayWithSnapshot(snapshot);

            String result = tool.converseWithAgent(AGENT_ID, "Hello", null);
            assertTrue(result.contains("Map text"));
        }

        @Test
        @DisplayName("Non-string/non-map items in output list are skipped")
        void converseWithAgent_mixedOutputItems() throws Exception {
            when(conversationService.startConversation(any(Environment.class), eq(AGENT_ID), eq(USER_ID), anyMap()))
                    .thenReturn(new ConversationResult(CONVERSATION_ID, URI.create("/conv")));

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationId(CONVERSATION_ID);
            snapshot.setConversationState(ConversationState.READY);
            var output = new ConversationOutput();
            output.put("output", List.of("text1", 42, Map.of("text", "text2"), Map.of("other", "no-text-key")));
            snapshot.setConversationOutputs(List.of(output));
            stubSayWithSnapshot(snapshot);

            String result = tool.converseWithAgent(AGENT_ID, "Hello", null);
            assertTrue(result.contains("text1"));
            assertTrue(result.contains("text2"));
            assertFalse(result.contains("42"));
        }

        @Test
        @DisplayName("Null snapshot returns no response")
        void converseWithAgent_nullSnapshot() throws Exception {
            when(conversationService.startConversation(any(Environment.class), eq(AGENT_ID), eq(USER_ID), anyMap()))
                    .thenReturn(new ConversationResult(CONVERSATION_ID, URI.create("/conv")));

            doAnswer(invocation -> {
                ConversationResponseHandler handler = invocation.getArgument(8);
                handler.onComplete(null);
                return null;
            }).when(conversationService).say(
                    any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

            String result = tool.converseWithAgent(AGENT_ID, "Hello", null);
            assertTrue(result.contains("[no response]"));
        }

        @Test
        @DisplayName("Output with no 'output' key returns no response")
        void converseWithAgent_noOutputKey() throws Exception {
            when(conversationService.startConversation(any(Environment.class), eq(AGENT_ID), eq(USER_ID), anyMap()))
                    .thenReturn(new ConversationResult(CONVERSATION_ID, URI.create("/conv")));

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationId(CONVERSATION_ID);
            snapshot.setConversationState(ConversationState.READY);
            var output = new ConversationOutput();
            output.put("someOtherKey", "value");
            snapshot.setConversationOutputs(List.of(output));
            stubSayWithSnapshot(snapshot);

            String result = tool.converseWithAgent(AGENT_ID, "Hello", null);
            assertTrue(result.contains("[no response]"));
        }

        @Test
        @DisplayName("General exception during say returns error")
        void converseWithAgent_generalException() throws Exception {
            when(conversationService.startConversation(any(Environment.class), eq(AGENT_ID), eq(USER_ID), anyMap()))
                    .thenReturn(new ConversationResult(CONVERSATION_ID, URI.create("/conv")));

            doThrow(new RuntimeException("Connection lost")).when(conversationService).say(
                    any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

            String result = tool.converseWithAgent(AGENT_ID, "Hello", null);
            assertTrue(result.contains("❌"));
            assertTrue(result.contains("Connection lost"));
        }

        @Test
        @DisplayName("startConversation failure returns error message")
        void converseWithAgent_startConversationFails_returnsError() throws Exception {
            // Arrange — startConversation throws
            when(conversationService.startConversation(any(Environment.class), eq(AGENT_ID), eq(USER_ID), anyMap()))
                    .thenThrow(new RuntimeException("Agent not deployed"));

            // Act
            String result = tool.converseWithAgent(AGENT_ID, "Hello", null);

            // Assert
            assertTrue(result.contains("❌"), "Should contain error marker");
            assertTrue(result.contains("Agent not deployed"), "Should contain the exception message");
            assertTrue(result.contains(AGENT_ID), "Should mention the agent ID");

            // say() should never be called since startConversation failed
            verify(conversationService, never()).say(
                    any(Environment.class), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("ERROR conversation state returns error message")
        void converseWithAgent_errorState_returnsErrorMessage() throws Exception {
            // Arrange — provide existing conversation ID, snapshot with ERROR state and no
            // output
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationId(CONVERSATION_ID);
            snapshot.setConversationState(ConversationState.ERROR);
            snapshot.setConversationOutputs(new LinkedList<>()); // empty outputs

            stubSayWithSnapshot(snapshot);

            // Act
            String result = tool.converseWithAgent(AGENT_ID, "Hello", CONVERSATION_ID);

            // Assert
            assertTrue(result.contains("✅"), "Should contain success marker (tool itself succeeded)");
            assertTrue(result.contains("ERROR state") || result.contains("failed to produce output"),
                    "Should indicate the agent entered ERROR state");
        }

        @Test
        @DisplayName("Empty response (no output in snapshot) is handled gracefully")
        void converseWithAgent_emptyResponse_handledGracefully() throws Exception {
            // Arrange — snapshot with READY state but no conversation outputs
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationId(CONVERSATION_ID);
            snapshot.setConversationState(ConversationState.READY);
            snapshot.setConversationOutputs(new LinkedList<>()); // no outputs

            stubSayWithSnapshot(snapshot);

            // Act
            String result = tool.converseWithAgent(AGENT_ID, "Hello", CONVERSATION_ID);

            // Assert — extractResponse returns null for empty outputs (C5 fix),
            // so the tool correctly shows "[no response]"
            assertTrue(result.contains("✅"), "Should contain success marker");
            assertTrue(result.contains(CONVERSATION_ID), "Should contain conversation ID");
            assertTrue(result.contains("[no response]"),
                    "Null response from extractResponse should trigger [no response] placeholder");
        }
    }

    // === FindAgentsByCapabilityTool ===

    @Nested
    class FindAgentsByCapabilityToolTest {

        private CapabilityRegistryService registryService;
        private FindAgentsByCapabilityTool tool;

        @BeforeEach
        void setUp() {
            registryService = mock(CapabilityRegistryService.class);
            tool = new FindAgentsByCapabilityTool(registryService);
        }

        @Test
        void findAgentsByCapability_found() {
            when(registryService.findBySkill("translation", "highest_confidence"))
                    .thenReturn(List.of(
                            new CapabilityMatch("agent-1", "translation", "high", Map.of("languages", "de,en")),
                            new CapabilityMatch("agent-2", "translation", "medium", Map.of())));

            String result = tool.findAgentsByCapability("translation", null);

            assertTrue(result.contains("🔍"));
            assertTrue(result.contains("2 agent(s)"));
            assertTrue(result.contains("agent-1"));
            assertTrue(result.contains("agent-2"));
            assertTrue(result.contains("languages=de,en"));
        }

        @Test
        void findAgentsByCapability_noneFound() {
            when(registryService.findBySkill("nonexistent", "highest_confidence"))
                    .thenReturn(List.of());

            String result = tool.findAgentsByCapability("nonexistent", null);

            assertTrue(result.contains("No agents found"));
        }

        @Test
        void findAgentsByCapability_skillRequired() {
            String result = tool.findAgentsByCapability(null, null);
            assertTrue(result.contains("⚠️"));
        }

        @Test
        void findAgentsByCapability_blankSkill() {
            String result = tool.findAgentsByCapability("  ", null);
            assertTrue(result.contains("⚠️"));
        }

        @Test
        void findAgentsByCapability_nullResult() {
            when(registryService.findBySkill("test", "highest_confidence")).thenReturn(null);

            String result = tool.findAgentsByCapability("test", null);
            assertTrue(result.contains("No agents found"));
        }

        @Test
        void findAgentsByCapability_noConfidence() {
            when(registryService.findBySkill("test", "highest_confidence"))
                    .thenReturn(List.of(new CapabilityMatch("agent-1", "test", null, Map.of())));

            String result = tool.findAgentsByCapability("test", null);
            assertTrue(result.contains("agent-1"));
            assertFalse(result.contains("Confidence:"));
        }

        @Test
        void findAgentsByCapability_blankConfidence() {
            when(registryService.findBySkill("test", "highest_confidence"))
                    .thenReturn(List.of(new CapabilityMatch("agent-1", "test", "  ", Map.of())));

            String result = tool.findAgentsByCapability("test", null);
            assertFalse(result.contains("Confidence:"));
        }

        @Test
        void findAgentsByCapability_nullAttributes() {
            when(registryService.findBySkill("test", "highest_confidence"))
                    .thenReturn(List.of(new CapabilityMatch("agent-1", "test", "high", null)));

            String result = tool.findAgentsByCapability("test", null);
            assertFalse(result.contains("Attributes:"));
        }

        @Test
        void findAgentsByCapability_exception() {
            when(registryService.findBySkill("test", "highest_confidence"))
                    .thenThrow(new RuntimeException("Registry down"));

            String result = tool.findAgentsByCapability("test", null);
            assertTrue(result.contains("❌"));
            assertTrue(result.contains("Registry down"));
        }

        @Test
        void findAgentsByCapability_customStrategy() {
            when(registryService.findBySkill("code-review", "round_robin"))
                    .thenReturn(List.of(new CapabilityMatch("agent-1", "code-review", "high", Map.of())));

            String result = tool.findAgentsByCapability("code-review", "round_robin");

            assertTrue(result.contains("round_robin"));
            assertTrue(result.contains("agent-1"));
        }
    }

    // === TeardownAgentTool ===

    @Nested
    class TeardownAgentToolTest {

        private IAgentFactory agentFactory;
        private IAgentStore agentStore;
        private List<String> createdAgentIds;
        private Set<String> retainedAgentIds;
        private TeardownAgentTool tool;

        @BeforeEach
        void setUp() {
            agentFactory = mock(IAgentFactory.class);
            agentStore = mock(IAgentStore.class);
            createdAgentIds = new CopyOnWriteArrayList<>(List.of("created-1", "created-2"));
            retainedAgentIds = ConcurrentHashMap.newKeySet();
            tool = new TeardownAgentTool(agentFactory, agentStore, createdAgentIds, retainedAgentIds);
        }

        @Test
        void teardownAgent_undeploy() throws Exception {
            String result = tool.teardownAgent("created-1", false);

            assertTrue(result.contains("✅"));
            assertTrue(result.contains("undeployed"));
            verify(agentFactory).undeployAgent(any(Environment.class), eq("created-1"), isNull());
            verify(agentStore, never()).deleteAllPermanently(any());
        }

        @Test
        void teardownAgent_undeployAndDelete() throws Exception {
            String result = tool.teardownAgent("created-1", true);

            assertTrue(result.contains("✅"));
            assertTrue(result.contains("deleted"));
            verify(agentFactory).undeployAgent(any(Environment.class), eq("created-1"), isNull());
            verify(agentStore).deleteAllPermanently("created-1");
        }

        @Test
        void teardownAgent_notCreatedByUs() {
            String result = tool.teardownAgent("external-agent", false);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not created"));
            verifyNoInteractions(agentFactory);
        }

        @Test
        void teardownAgent_retained() {
            retainedAgentIds.add("created-1");

            String result = tool.teardownAgent("created-1", false);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("retained"));
            verifyNoInteractions(agentFactory);
        }

        @Test
        void retainAgent_success() {
            String result = tool.retainAgent("created-1");

            assertTrue(result.contains("✅"));
            assertTrue(result.contains("retained"));
            assertTrue(retainedAgentIds.contains("created-1"));
        }

        @Test
        void retainAgent_notCreatedByUs() {
            String result = tool.retainAgent("external-agent");

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not created"));
            assertFalse(retainedAgentIds.contains("external-agent"));
        }

        @Test
        void retainAgent_alreadyRetained() {
            retainedAgentIds.add("created-1");

            String result = tool.retainAgent("created-1");

            assertTrue(result.contains("already"));
        }

        @Test
        void teardownAgent_agentIdRequired() {
            String result = tool.teardownAgent(null, false);
            assertTrue(result.contains("⚠️"));
        }

        @Test
        void teardownAgent_undeployFailure() throws Exception {
            doThrow(new RuntimeException("Agent not found")).when(agentFactory)
                    .undeployAgent(any(Environment.class), eq("created-1"), isNull());

            String result = tool.teardownAgent("created-1", false);

            assertTrue(result.contains("❌"));
            assertTrue(result.contains("Agent not found"));
        }

        @Test
        void teardownAgent_deleteFailure() throws Exception {
            doThrow(new RuntimeException("DB locked")).when(agentStore)
                    .deleteAllPermanently("created-1");

            String result = tool.teardownAgent("created-1", true);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("undeployed"));
            assertTrue(result.contains("deletion failed"));
        }

        @Test
        void unretainAgent_success() {
            retainedAgentIds.add("created-1");

            String result = tool.unretainAgent("created-1");

            assertTrue(result.contains("✅"));
            assertTrue(result.contains("Retention flag removed"));
            assertFalse(retainedAgentIds.contains("created-1"));
        }

        @Test
        void unretainAgent_notRetained() {
            String result = tool.unretainAgent("created-1");

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not currently retained"));
        }

        @Test
        void unretainAgent_agentIdRequired() {
            String result = tool.unretainAgent(null);
            assertTrue(result.contains("⚠️"));
        }

        @Test
        void constructor_nullArgs_doesNotThrow() {
            // Null constructor args should produce safe fallback collections
            var safeTool = new TeardownAgentTool(agentFactory, agentStore, null, null);
            // teardownAgent with unknown agentId returns a warning (doesn't NPE)
            String result = safeTool.teardownAgent("non-existent", false);
            assertTrue(result.contains("⚠️"));
            // unretainAgent also doesn't throw (exercises retainedAgentIds fallback)
            String unretainResult = safeTool.unretainAgent("non-existent");
            assertTrue(unretainResult.contains("⚠️") || unretainResult.contains("not marked"));
        }
    }

    // === DynamicAgentConfig ===

    @Nested
    class DynamicAgentConfigTest {

        @Test
        void defaults() {
            var config = new DynamicAgentConfig();

            assertFalse(config.isEnabled());
            assertFalse(config.isAllowCreation());
            assertFalse(config.isAllowRecruitment());
            assertTrue(config.isAllowDelegation());
            assertEquals(5, config.getMaxCreatedAgentsPerDiscussion());
            assertEquals(10, config.getMaxRecruitedAgentsPerDiscussion());
            assertEquals(3, config.getMaxDelegationsPerTask());
            assertTrue(config.isInheritParentModel());
            assertEquals(AgentGroupConfiguration.LifecyclePolicy.EPHEMERAL, config.getLifecyclePolicy());
            assertNull(config.getAllowedProviders());
            assertNull(config.getAllowedModels());
        }

        @Test
        void setAndGet() {
            var config = new DynamicAgentConfig();
            config.setEnabled(true);
            config.setAllowCreation(true);
            config.setAllowRecruitment(true);
            config.setAllowDelegation(false);
            config.setMaxCreatedAgentsPerDiscussion(3);
            config.setMaxRecruitedAgentsPerDiscussion(7);
            config.setMaxDelegationsPerTask(2);
            config.setAllowedProviders(List.of("openai", "anthropic"));
            config.setAllowedModels(Map.of("openai", List.of("gpt-4o")));
            config.setInheritParentModel(false);
            config.setLifecyclePolicy(AgentGroupConfiguration.LifecyclePolicy.AGENT_DECIDES);

            assertTrue(config.isEnabled());
            assertTrue(config.isAllowCreation());
            assertTrue(config.isAllowRecruitment());
            assertFalse(config.isAllowDelegation());
            assertEquals(3, config.getMaxCreatedAgentsPerDiscussion());
            assertEquals(7, config.getMaxRecruitedAgentsPerDiscussion());
            assertEquals(2, config.getMaxDelegationsPerTask());
            assertEquals(List.of("openai", "anthropic"), config.getAllowedProviders());
            assertEquals(Map.of("openai", List.of("gpt-4o")), config.getAllowedModels());
            assertFalse(config.isInheritParentModel());
            assertEquals(AgentGroupConfiguration.LifecyclePolicy.AGENT_DECIDES, config.getLifecyclePolicy());
        }

        @Test
        void lifecyclePolicyNullSafe() {
            var config = new DynamicAgentConfig();
            config.setLifecyclePolicy(null);
            // null should be coerced to EPHEMERAL
            assertEquals(AgentGroupConfiguration.LifecyclePolicy.EPHEMERAL, config.getLifecyclePolicy());
        }
    }

    // === GroupConversation dynamic member fields ===

    @Nested
    class GroupConversationDynamicFieldsTest {

        @Test
        void addDynamicMember_threadSafe() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();
            var member = new ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember(
                    "dynamic-1", "Dynamic Agent", 99, "specialist");

            gc.addDynamicMember(member);

            assertEquals(1, gc.getDynamicMembers().size());
            assertEquals("dynamic-1", gc.getDynamicMembers().get(0).agentId());
        }

        @Test
        void createdAgentIds_tracking() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();

            gc.getCreatedAgentIds().add("agent-a");
            gc.getCreatedAgentIds().add("agent-b");

            assertEquals(2, gc.getCreatedAgentIds().size());
            assertTrue(gc.getCreatedAgentIds().contains("agent-a"));
        }

        @Test
        void retainedAgentIds_tracking() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();

            gc.getRetainedAgentIds().add("agent-a");

            assertTrue(gc.getRetainedAgentIds().contains("agent-a"));
            assertFalse(gc.getRetainedAgentIds().contains("agent-b"));
        }

        @Test
        void setDynamicMembers_null_safe() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();
            gc.setDynamicMembers(null);
            assertNotNull(gc.getDynamicMembers());
            assertTrue(gc.getDynamicMembers().isEmpty());
        }

        @Test
        void setCreatedAgentIds_null_safe() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();
            gc.setCreatedAgentIds(null);
            assertNotNull(gc.getCreatedAgentIds());
            assertTrue(gc.getCreatedAgentIds().isEmpty());
        }

        @Test
        void setRetainedAgentIds_null_safe() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();
            gc.setRetainedAgentIds(null);
            assertNotNull(gc.getRetainedAgentIds());
            assertTrue(gc.getRetainedAgentIds().isEmpty());
        }
    }
}
