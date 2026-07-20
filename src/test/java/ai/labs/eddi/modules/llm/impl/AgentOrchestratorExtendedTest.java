/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ConversationSummaryConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ToolLoadingStrategy;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended unit tests for {@link AgentOrchestrator} — covers branches not
 * exercised by the base AgentOrchestratorTest: LAZY tool loading,
 * safeTemplateMerge, activateDiscoveredTools, user memory tool, conversation
 * recall tool, counterweight strict mode, and executeIfToolsEnabled return
 * paths.
 */
class AgentOrchestratorExtendedTest {

    private AgentOrchestrator orchestrator;
    private CalculatorTool calculatorTool;
    private DateTimeTool dateTimeTool;
    private WebSearchTool webSearchTool;
    private DataFormatterTool dataFormatterTool;
    private WebScraperTool webScraperTool;
    private TextSummarizerTool textSummarizerTool;
    private PdfReaderTool pdfReaderTool;
    private WeatherTool weatherTool;
    private FetchToolResponsePageTool fetchToolResponsePageTool;
    private ToolExecutionService toolExecutionService;
    private McpToolProviderManager mcpToolProviderManager;
    private A2AToolProviderManager a2aToolProviderManager;
    private IUserMemoryStore userMemoryStore;
    private IConversationMemory mockMemory;
    private MemorySnapshotService memorySnapshotService;

    @BeforeEach
    void setUp() {
        calculatorTool = mock(CalculatorTool.class);
        dateTimeTool = mock(DateTimeTool.class);
        webSearchTool = mock(WebSearchTool.class);
        dataFormatterTool = mock(DataFormatterTool.class);
        webScraperTool = mock(WebScraperTool.class);
        textSummarizerTool = mock(TextSummarizerTool.class);
        pdfReaderTool = mock(PdfReaderTool.class);
        weatherTool = mock(WeatherTool.class);
        fetchToolResponsePageTool = mock(FetchToolResponsePageTool.class);
        toolExecutionService = mock(ToolExecutionService.class);
        mcpToolProviderManager = mock(McpToolProviderManager.class);
        a2aToolProviderManager = mock(A2AToolProviderManager.class);
        userMemoryStore = mock(IUserMemoryStore.class);
        memorySnapshotService = mock(MemorySnapshotService.class);

        orchestrator = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool, toolExecutionService,
                mcpToolProviderManager, a2aToolProviderManager,
                mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                mock(IResourceClientLibrary.class), mock(IApiCallExecutor.class),
                mock(IJsonSerialization.class), mock(IMemoryItemConverter.class),
                userMemoryStore, mock(ToolResponseTruncator.class),
                mock(TenantQuotaService.class), memorySnapshotService,
                null, null, null, null, null,
                mock(ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.class), new ConversationHistoryBuilder());

        mockMemory = mock(IConversationMemory.class);
        when(mockMemory.getUserMemoryConfig()).thenReturn(null);
    }

    // ==================== LAZY Tool Loading Tests ====================

    @Nested
    @DisplayName("LAZY tool loading strategy")
    class LazyToolLoadingTests {

        @Test
        @DisplayName("LAZY strategy should include DiscoverToolsTool meta-tool")
        void collectEnabledTools_lazy_includesDiscoverTool() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setToolLoadingStrategy(ToolLoadingStrategy.LAZY);
            task.setBuiltInToolsWhitelist(List.of("calculator"));

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            // Should have the calculator tool + DiscoverToolsTool
            assertTrue(tools.size() >= 2, "Should have at least calculator + discover_tools");
            boolean hasDiscoverTool = tools.stream()
                    .anyMatch(t -> t.getClass().getSimpleName().equals("DiscoverToolsTool"));
            assertTrue(hasDiscoverTool, "Should include DiscoverToolsTool meta-tool in LAZY mode");
        }

        @Test
        @DisplayName("EAGER strategy should NOT include DiscoverToolsTool")
        void collectEnabledTools_eager_noDiscoverTool() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setToolLoadingStrategy(ToolLoadingStrategy.EAGER);

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            boolean hasDiscoverTool = tools.stream()
                    .anyMatch(t -> t.getClass().getSimpleName().equals("DiscoverToolsTool"));
            assertFalse(hasDiscoverTool, "EAGER mode should NOT include DiscoverToolsTool");
        }

        @Test
        @DisplayName("Default tool loading strategy (null) should use EAGER")
        void collectEnabledTools_defaultStrategy() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            // toolLoadingStrategy defaults to EAGER

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            boolean hasDiscoverTool = tools.stream()
                    .anyMatch(t -> t.getClass().getSimpleName().equals("DiscoverToolsTool"));
            assertFalse(hasDiscoverTool, "Default strategy should be EAGER (no discover tool)");
        }
    }

    // ==================== Whitelist fetch_page Alias Tests ====================

    @Nested
    @DisplayName("Whitelist fetch_page alias")
    class FetchPageAliasTests {

        @Test
        @DisplayName("fetch_page in whitelist should add FetchToolResponsePageTool")
        void fetchPageAlias() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("fetch_page"));

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            assertEquals(1, tools.size());
            assertTrue(tools.contains(fetchToolResponsePageTool));
        }

        @Test
        @DisplayName("fetch_tool_response_page in whitelist should also add FetchToolResponsePageTool")
        void fetchToolResponsePageWhitelist() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("fetch_tool_response_page"));

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            assertEquals(1, tools.size());
            assertTrue(tools.contains(fetchToolResponsePageTool));
        }
    }

    // ==================== UserMemoryTool Tests ====================

    @Nested
    @DisplayName("UserMemoryTool integration")
    class UserMemoryToolTests {

        @Test
        @DisplayName("Should add UserMemoryTool when user memory config exists")
        void addUserMemoryTool_enabled() {
            var memConfig = new AgentConfiguration.UserMemoryConfig();
            when(mockMemory.getUserMemoryConfig()).thenReturn(memConfig);
            when(mockMemory.getUserId()).thenReturn("user-1");
            when(mockMemory.getAgentId()).thenReturn("agent-1");
            when(mockMemory.getConversationId()).thenReturn("conv-1");
            when(mockMemory.getConversationProperties()).thenReturn(null);

            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("usermemory"));

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            // Should have 1 tool: UserMemoryTool
            assertEquals(1, tools.size());
        }

        @Test
        @DisplayName("Should NOT add UserMemoryTool when config is null")
        void addUserMemoryTool_nullConfig() {
            when(mockMemory.getUserMemoryConfig()).thenReturn(null);

            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("usermemory"));

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            assertTrue(tools.isEmpty(), "No UserMemoryTool when config is null");
        }

        @Test
        @DisplayName("Should extract groupId from conversation properties")
        void addUserMemoryTool_withGroupId() {
            var memConfig = new AgentConfiguration.UserMemoryConfig();
            when(mockMemory.getUserMemoryConfig()).thenReturn(memConfig);
            when(mockMemory.getUserId()).thenReturn("user-1");
            when(mockMemory.getAgentId()).thenReturn("agent-1");
            when(mockMemory.getConversationId()).thenReturn("conv-1");

            var props = mock(IConversationMemory.IConversationProperties.class);
            var groupProp = new Property();
            groupProp.setValueString("group-42");
            when(props.get("groupId")).thenReturn(groupProp);
            when(mockMemory.getConversationProperties()).thenReturn(props);

            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("usermemory"));

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            assertEquals(1, tools.size(), "Should have UserMemoryTool");
        }

        @Test
        @DisplayName("All tools without whitelist should include UserMemoryTool if config exists")
        void allTools_includesUserMemory() {
            var memConfig = new AgentConfiguration.UserMemoryConfig();
            when(mockMemory.getUserMemoryConfig()).thenReturn(memConfig);
            when(mockMemory.getUserId()).thenReturn("user-1");
            when(mockMemory.getAgentId()).thenReturn("agent-1");
            when(mockMemory.getConversationId()).thenReturn("conv-1");
            when(mockMemory.getConversationProperties()).thenReturn(null);

            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(null); // no whitelist = all tools

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            // Should have 9 built-in + 1 UserMemoryTool = 10
            assertEquals(10, tools.size());
        }
    }

    // ==================== ConversationRecallTool Tests ====================

    @Nested
    @DisplayName("ConversationRecallTool integration")
    class ConversationRecallToolTests {

        @Test
        @DisplayName("Should NOT add recall tool when summary config is null")
        void noRecallTool_nullSummaryConfig() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setConversationSummary(null);
            task.setBuiltInToolsWhitelist(List.of("conversationRecall"));

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            assertTrue(tools.isEmpty(), "No recall tool when summary config is null");
        }

        @Test
        @DisplayName("Should NOT add recall tool when summary is disabled")
        void noRecallTool_summaryDisabled() {
            var summaryConfig = new ConversationSummaryConfig();
            summaryConfig.setEnabled(false);

            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setConversationSummary(summaryConfig);
            task.setBuiltInToolsWhitelist(List.of("conversationRecall"));

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            assertTrue(tools.isEmpty(), "No recall tool when summary is disabled");
        }
    }

    // ==================== executeIfToolsEnabled Tests ====================

    @Nested
    @DisplayName("executeIfToolsEnabled paths")
    class ExecuteIfToolsEnabledTests {

        @Test
        @DisplayName("Should return null when no tools enabled")
        void returnNull_noTools() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);

            // No A2A agents
            task.setA2aAgents(null);

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);

            var result = orchestrator.executeIfToolsEnabled(
                    mock(ChatModel.class), "system msg",
                    List.of(UserMessage.from("hi")), task, mockMemory);

            assertNull(result, "Should return null when no tools are enabled");
        }

        @Test
        @DisplayName("Should return null when A2A agents list is empty")
        void returnNull_emptyA2aAgents() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);
            task.setA2aAgents(List.of());

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);

            var result = orchestrator.executeIfToolsEnabled(
                    mock(ChatModel.class), "system msg",
                    List.of(UserMessage.from("hi")), task, mockMemory);

            assertNull(result, "Should return null when A2A agents list is empty");
        }

        @Test
        @DisplayName("enableHttpCallTools null defaults to true")
        void enableHttpCallToolsDefault() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(null); // default = true
            task.setEnableMcpCallTools(false);

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);

            // Should not throw even though discovery returns empty
            var result = orchestrator.executeIfToolsEnabled(
                    mock(ChatModel.class), "system msg",
                    List.of(UserMessage.from("hi")), task, mockMemory);

            assertNull(result, "Should return null when discovered httpcall tools are also empty");
        }

        @Test
        @DisplayName("enableMcpCallTools null defaults to true")
        void enableMcpCallToolsDefault() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(null); // default = true

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);

            var result = orchestrator.executeIfToolsEnabled(
                    mock(ChatModel.class), "system msg",
                    List.of(UserMessage.from("hi")), task, mockMemory);

            assertNull(result, "Should return null when discovered mcpcall tools are also empty");
        }
    }

    // ==================== safeTemplateMerge Tests ====================

    @Nested
    @DisplayName("safeTemplateMerge reserved key blocking")
    class SafeTemplateMergeTests {

        /**
         * Use reflection to access the private static method.
         */
        private void invokeSafeTemplateMerge(Map<String, Object> templateData, Map<String, Object> args)
                throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "safeTemplateMerge", Map.class, Map.class);
            method.setAccessible(true);
            method.invoke(null, templateData, args);
        }

        @Test
        @DisplayName("Should block reserved key 'context'")
        void blockReservedKey_context() throws Exception {
            Map<String, Object> templateData = new HashMap<>();
            templateData.put("context", "originalContext");

            Map<String, Object> args = new HashMap<>();
            args.put("context", "injected");

            invokeSafeTemplateMerge(templateData, args);

            assertEquals("originalContext", templateData.get("context"),
                    "Reserved key 'context' must not be overwritten");
        }

        @Test
        @DisplayName("Should block reserved key 'userInfo'")
        void blockReservedKey_userInfo() throws Exception {
            Map<String, Object> templateData = new HashMap<>();
            templateData.put("userInfo", Map.of("userId", "real-user"));

            Map<String, Object> args = new HashMap<>();
            args.put("userInfo", Map.of("userId", "attacker"));

            invokeSafeTemplateMerge(templateData, args);

            @SuppressWarnings("unchecked")
            var userInfo = (Map<String, Object>) templateData.get("userInfo");
            assertEquals("real-user", userInfo.get("userId"),
                    "Reserved key 'userInfo' must not be overwritten");
        }

        @Test
        @DisplayName("Should allow non-reserved keys")
        void allowNonReservedKey() throws Exception {
            Map<String, Object> templateData = new HashMap<>();
            Map<String, Object> args = new HashMap<>();
            args.put("city", "Vienna");
            args.put("limit", "10");

            invokeSafeTemplateMerge(templateData, args);

            assertEquals("Vienna", templateData.get("city"));
            assertEquals("10", templateData.get("limit"));
        }

        @Test
        @DisplayName("Should block all reserved keys: context, properties, memory, userInfo, conversationInfo, conversationLog")
        void blockAllReservedKeys() throws Exception {
            Map<String, Object> templateData = new HashMap<>();
            templateData.put("context", "orig");
            templateData.put("properties", "orig");
            templateData.put("memory", "orig");
            templateData.put("userInfo", "orig");
            templateData.put("conversationInfo", "orig");
            templateData.put("conversationLog", "orig");

            Map<String, Object> args = new HashMap<>();
            args.put("context", "injected");
            args.put("properties", "injected");
            args.put("memory", "injected");
            args.put("userInfo", "injected");
            args.put("conversationInfo", "injected");
            args.put("conversationLog", "injected");
            args.put("safeparam", "allowed");

            invokeSafeTemplateMerge(templateData, args);

            assertEquals("orig", templateData.get("context"));
            assertEquals("orig", templateData.get("properties"));
            assertEquals("orig", templateData.get("memory"));
            assertEquals("orig", templateData.get("userInfo"));
            assertEquals("orig", templateData.get("conversationInfo"));
            assertEquals("orig", templateData.get("conversationLog"));
            assertEquals("allowed", templateData.get("safeparam"));
        }
    }

    // ==================== activateDiscoveredTools Tests ====================

    @Nested
    @DisplayName("activateDiscoveredTools")
    class ActivateDiscoveredToolsTests {

        /**
         * Use reflection to access private activateDiscoveredTools method.
         */
        private void invokeActivateDiscoveredTools(String discoverResult,
                                                   List<ToolSpecification> builtInSpecs,
                                                   List<ToolSpecification> activeSpecs)
                throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "activateDiscoveredTools", String.class, List.class, List.class);
            method.setAccessible(true);
            method.invoke(orchestrator, discoverResult, builtInSpecs, activeSpecs);
        }

        @Test
        @DisplayName("Should activate matching built-in tools from discover result")
        void activateMatchingTools() throws Exception {
            var calcSpec = ToolSpecification.builder().name("calculate").description("Calculate").build();
            var weatherSpec = ToolSpecification.builder().name("get_weather").description("Weather").build();
            var discoverSpec = ToolSpecification.builder().name("discover_tools").description("Discover").build();

            List<ToolSpecification> builtInSpecs = new ArrayList<>(List.of(calcSpec, weatherSpec, discoverSpec));
            List<ToolSpecification> activeSpecs = new ArrayList<>(List.of(discoverSpec));

            String result = "{\"tools\": [{\"name\": \"calculate\"}, {\"name\": \"get_weather\"}]}";

            invokeActivateDiscoveredTools(result, builtInSpecs, activeSpecs);

            assertEquals(3, activeSpecs.size(), "Should have discover_tools + 2 activated tools");
            assertTrue(activeSpecs.contains(calcSpec));
            assertTrue(activeSpecs.contains(weatherSpec));
        }

        @Test
        @DisplayName("Should not duplicate already-active specs")
        void noDuplicateActivation() throws Exception {
            var calcSpec = ToolSpecification.builder().name("calculate").description("Calculate").build();
            var discoverSpec = ToolSpecification.builder().name("discover_tools").description("Discover").build();

            List<ToolSpecification> builtInSpecs = new ArrayList<>(List.of(calcSpec, discoverSpec));
            List<ToolSpecification> activeSpecs = new ArrayList<>(List.of(discoverSpec, calcSpec));

            String result = "{\"tools\": [{\"name\": \"calculate\"}]}";

            invokeActivateDiscoveredTools(result, builtInSpecs, activeSpecs);

            assertEquals(2, activeSpecs.size(), "Should not add duplicate 'calculate' spec");
        }

        @Test
        @DisplayName("Should handle invalid JSON gracefully")
        void handleInvalidJson() throws Exception {
            List<ToolSpecification> builtInSpecs = new ArrayList<>();
            List<ToolSpecification> activeSpecs = new ArrayList<>();

            // Should not throw
            invokeActivateDiscoveredTools("not-json{}", builtInSpecs, activeSpecs);

            assertTrue(activeSpecs.isEmpty(), "No specs should be activated on invalid JSON");
        }

        @Test
        @DisplayName("Should handle missing 'tools' key")
        void handleMissingToolsKey() throws Exception {
            List<ToolSpecification> builtInSpecs = new ArrayList<>();
            List<ToolSpecification> activeSpecs = new ArrayList<>();

            invokeActivateDiscoveredTools("{\"other\": 1}", builtInSpecs, activeSpecs);

            assertTrue(activeSpecs.isEmpty(), "No specs activated when 'tools' key is missing");
        }

        @Test
        @DisplayName("Should handle tools array with missing name")
        void handleToolsWithoutName() throws Exception {
            var calcSpec = ToolSpecification.builder().name("calculate").description("Calc").build();
            List<ToolSpecification> builtInSpecs = new ArrayList<>(List.of(calcSpec));
            List<ToolSpecification> activeSpecs = new ArrayList<>();

            String result = "{\"tools\": [{\"description\": \"no name\"}, {\"name\": \"calculate\"}]}";

            invokeActivateDiscoveredTools(result, builtInSpecs, activeSpecs);

            assertEquals(1, activeSpecs.size());
            assertEquals("calculate", activeSpecs.get(0).name());
        }

        @Test
        @DisplayName("Should handle non-array tools node")
        void handleNonArrayToolsNode() throws Exception {
            List<ToolSpecification> builtInSpecs = new ArrayList<>();
            List<ToolSpecification> activeSpecs = new ArrayList<>();

            invokeActivateDiscoveredTools("{\"tools\": \"not-an-array\"}", builtInSpecs, activeSpecs);

            assertTrue(activeSpecs.isEmpty(), "No specs activated when 'tools' is not an array");
        }
    }

    // ==================== ExecutionResult record Tests ====================

    @Nested
    @DisplayName("ExecutionResult record")
    class ExecutionResultTests {

        @Test
        @DisplayName("ExecutionResult stores response and trace")
        void testExecutionResult() {
            var trace = List.<Map<String, Object>>of(Map.of("type", "tool_call", "tool", "calc"));
            var result = new AgentOrchestrator.ExecutionResult("Hello!", trace);

            assertEquals("Hello!", result.response());
            assertEquals(1, result.trace().size());
            assertEquals("tool_call", result.trace().get(0).get("type"));
        }

        @Test
        @DisplayName("ExecutionResult with empty trace")
        void testExecutionResult_emptyTrace() {
            var result = new AgentOrchestrator.ExecutionResult("response", List.of());
            assertEquals("response", result.response());
            assertTrue(result.trace().isEmpty());
        }

        @Test
        @DisplayName("ExecutionResult with null response")
        void testExecutionResult_nullResponse() {
            var result = new AgentOrchestrator.ExecutionResult(null, List.of());
            assertNull(result.response());
        }
    }

    // ==================== HttpCallToolsResult record Tests ====================

    @Nested
    @DisplayName("HttpCallToolsResult record")
    class HttpCallToolsResultTests {

        @Test
        @DisplayName("Should store specs and executors")
        void testRecordFields() {
            var spec = ToolSpecification.builder().name("test").description("test").build();
            var result = new AgentOrchestrator.HttpCallToolsResult(
                    List.of(spec), Map.of());

            assertEquals(1, result.toolSpecs().size());
            assertEquals("test", result.toolSpecs().get(0).name());
            assertTrue(result.executors().isEmpty());
        }
    }

    // ==================== A2A Agent Tool Collection Tests ====================

    @Nested
    @DisplayName("A2A agent tool collection")
    class A2AAgentToolCollectionTests {

        @Test
        @DisplayName("Should call discoverTools when a2aAgents list is non-empty")
        void discoverTools_calledWhenA2aAgentsPresent() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);

            var a2aConfig = new A2AAgentConfig();
            a2aConfig.setUrl("http://remote-agent:9000");
            task.setA2aAgents(List.of(a2aConfig));

            // A2A tools return empty specs → overall should return null
            doReturn(new A2AToolProviderManager.A2AToolsResult(List.of(), Map.of()))
                    .when(a2aToolProviderManager).discoverTools(any());

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);

            var result = orchestrator.executeIfToolsEnabled(
                    mock(ChatModel.class), "sys", List.of(UserMessage.from("hi")),
                    task, mockMemory);

            verify(a2aToolProviderManager).discoverTools(any());
            assertNull(result, "Should return null when A2A discovery returns empty specs");
        }

        @Test
        @DisplayName("Should return ExecutionResult when A2A tools provide specs")
        void returnResult_whenA2aToolsProvideSpecs() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);

            var a2aConfig = new A2AAgentConfig();
            a2aConfig.setUrl("http://remote-agent:9000");
            task.setA2aAgents(List.of(a2aConfig));

            var a2aSpec = ToolSpecification.builder()
                    .name("remote_skill")
                    .description("Remote A2A skill")
                    .build();

            doReturn(new A2AToolProviderManager.A2AToolsResult(
                    List.of(a2aSpec),
                    Map.of("remote_skill", (req, memId) -> "result from A2A")))
                    .when(a2aToolProviderManager).discoverTools(any());

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);
            when(mockMemory.getConversationId()).thenReturn("conv-1");

            // Mock ChatModel to return a simple text AiMessage (no tool calls)
            ChatModel chatModel = mock(ChatModel.class);
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("Hello from agent"))
                    .build();
            doReturn(chatResponse).when(chatModel).chat(any(ChatRequest.class));

            var result = orchestrator.executeIfToolsEnabled(
                    chatModel, "system", List.of(UserMessage.from("hi")),
                    task, mockMemory);

            assertNotNull(result, "Should return non-null when A2A tools are available");
            assertEquals("Hello from agent", result.response());
        }
    }

    // ==================== MCP Tool Collection Tests ====================

    @Nested
    @DisplayName("MCP tool collection via enableMcpCallTools")
    class McpToolCollectionTests {

        @Test
        @DisplayName("Should call discoverMcpCallTools when enableMcpCallTools=true and agentId is set")
        void discoverMcpCallTools_calledWhenEnabled() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(true);

            when(mockMemory.getAgentId()).thenReturn("agent-1");
            when(mockMemory.getAgentVersion()).thenReturn(null);

            // discoverMcpCallTools calls WorkflowTraversal which needs agentVersion
            // With null agentVersion, WorkflowTraversal returns empty results
            var result = orchestrator.executeIfToolsEnabled(
                    mock(ChatModel.class), "sys", List.of(UserMessage.from("hi")),
                    task, mockMemory);

            // Since WorkflowTraversal returns empty (agentVersion is null) and
            // no other tools are enabled, result should be null
            assertNull(result, "Should return null when MCP discovery finds no tools");
        }

        @Test
        @DisplayName("Should skip MCP discovery when enableMcpCallTools=false")
        void skipMcpDiscovery_whenDisabled() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);

            when(mockMemory.getAgentId()).thenReturn("agent-1");
            when(mockMemory.getAgentVersion()).thenReturn(1);

            var result = orchestrator.executeIfToolsEnabled(
                    mock(ChatModel.class), "sys", List.of(UserMessage.from("hi")),
                    task, mockMemory);

            assertNull(result);
        }
    }

    // ==================== executeIfToolsEnabled with Actual Tools Tests
    // ====================

    @Nested
    @DisplayName("executeIfToolsEnabled with non-empty tool list")
    class ExecuteWithActualToolsTests {

        @Test
        @DisplayName("Should return ExecutionResult when collectEnabledTools returns non-empty list")
        void returnExecutionResult_withBuiltInTools() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("calculator"));
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);
            when(mockMemory.getConversationId()).thenReturn("conv-1");

            // Mock ChatModel to return a simple AiMessage without tool calls
            ChatModel chatModel = mock(ChatModel.class);
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("The answer is 42"))
                    .build();
            doReturn(chatResponse).when(chatModel).chat(any(ChatRequest.class));

            var result = orchestrator.executeIfToolsEnabled(
                    chatModel, "You are a calculator", List.of(UserMessage.from("what is 6*7?")),
                    task, mockMemory);

            assertNotNull(result, "Should return ExecutionResult when built-in tools are enabled");
            assertEquals("The answer is 42", result.response());
            assertTrue(result.trace().isEmpty(), "Trace should be empty when no tool calls made");
        }

        @Test
        @DisplayName("Cooperative cancellation — interrupted thread stops the tool loop (plan #9)")
        void cooperativeCancellation_interruptedThreadThrows() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("calculator"));
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);
            var retry = new LlmConfiguration.RetryConfiguration();
            retry.setMaxAttempts(1);
            task.setRetry(retry);

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);
            when(mockMemory.getConversationId()).thenReturn("conv-1");

            // chatModel would return tool calls, but the interrupt check fires first.
            ChatModel chatModel = mock(ChatModel.class);

            Thread.currentThread().interrupt(); // simulate a cascade per-step timeout cancel
            try {
                var ex = assertThrows(LifecycleException.class, () -> orchestrator.executeIfToolsEnabled(
                        chatModel, "sys", List.of(UserMessage.from("hi")), task, mockMemory));
                // The retry helper wraps the cancellation message — search the cause chain.
                boolean mentionsCancel = false;
                for (Throwable t = ex; t != null; t = t.getCause()) {
                    if (t.getMessage() != null && t.getMessage().toLowerCase().contains("cancel")) {
                        mentionsCancel = true;
                        break;
                    }
                }
                assertTrue(mentionsCancel, "expected a cancellation message in the chain, got: " + ex.getMessage());
                // Cancellation must stop before the model is ever called.
                verify(chatModel, never()).chat(any(ChatRequest.class));
            } finally {
                Thread.interrupted(); // clear the flag so it does not leak to other tests
            }
        }

        @Test
        @DisplayName("Should return ExecutionResult with multiple built-in tools")
        void returnExecutionResult_withMultipleTools() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("calculator", "datetime", "weather"));
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);
            when(mockMemory.getConversationId()).thenReturn("conv-1");

            ChatModel chatModel = mock(ChatModel.class);
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("Here is the info"))
                    .build();
            doReturn(chatResponse).when(chatModel).chat(any(ChatRequest.class));

            var result = orchestrator.executeIfToolsEnabled(
                    chatModel, "system", List.of(UserMessage.from("info please")),
                    task, mockMemory);

            assertNotNull(result);
            assertEquals("Here is the info", result.response());
        }
    }

    // ==================== ConversationRecallTool Enabled Tests
    // ====================

    @Nested
    @DisplayName("ConversationRecallTool enabled when summary exists")
    class ConversationRecallToolEnabledTests {

        @Test
        @DisplayName("Should add recall tool when summary config is enabled and summary exists")
        void addRecallTool_summaryEnabledAndExists() {
            var summaryConfig = new ConversationSummaryConfig();
            summaryConfig.setEnabled(true);

            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setConversationSummary(summaryConfig);
            task.setBuiltInToolsWhitelist(List.of("conversationRecall"));

            // Mock conversation properties so readSummary returns non-null
            var props = mock(IConversationMemory.IConversationProperties.class);
            var summaryProp = new Property();
            summaryProp.setValueString("This is a rolling summary of the conversation");
            when(props.get("conversation:running_summary")).thenReturn(summaryProp);

            var stepProp = new Property();
            stepProp.setValueInt(3);
            when(props.get("conversation:summary_through_step")).thenReturn(stepProp);

            when(mockMemory.getConversationProperties()).thenReturn(props);
            when(mockMemory.getConversationOutputs()).thenReturn(List.of());

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            assertEquals(1, tools.size(), "Should have ConversationRecallTool");
            assertEquals("ConversationRecallTool", tools.get(0).getClass().getSimpleName());
        }

        @Test
        @DisplayName("Should NOT add recall tool when summary config enabled but no summary exists yet")
        void noRecallTool_enabledButNoSummaryYet() {
            var summaryConfig = new ConversationSummaryConfig();
            summaryConfig.setEnabled(true);

            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setConversationSummary(summaryConfig);
            task.setBuiltInToolsWhitelist(List.of("conversationRecall"));

            // Mock conversation properties with no running summary
            var props = mock(IConversationMemory.IConversationProperties.class);
            when(props.get("conversation:running_summary")).thenReturn(null);
            when(mockMemory.getConversationProperties()).thenReturn(props);

            List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

            assertTrue(tools.isEmpty(), "No recall tool when summary doesn't exist yet");
        }
    }

    // ==================== HTTP Call Tool Discovery Tests ====================

    @Nested
    @DisplayName("HTTP call tool discovery")
    class HttpCallToolDiscoveryTests {

        @Test
        @DisplayName("Should attempt discovery when enableHttpCallTools=true with agentId and version set")
        void discoverHttpCallTools_withAgentContext() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(true);
            task.setEnableMcpCallTools(false);

            when(mockMemory.getAgentId()).thenReturn("agent-123");
            when(mockMemory.getAgentVersion()).thenReturn(2);

            // WorkflowTraversal.discoverConfigs will try to call restAgentStore.readAgent
            // which returns null → empty configs → empty toolSpecs
            var result = orchestrator.executeIfToolsEnabled(
                    mock(ChatModel.class), "sys", List.of(UserMessage.from("hi")),
                    task, mockMemory);

            assertNull(result, "Should return null when httpcall discovery finds no tools");
        }

        @Test
        @DisplayName("discoverHttpCallTools should return empty result when agentId is null")
        void discoverHttpCallTools_nullAgentId() {
            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);

            var result = orchestrator.discoverHttpCallTools(mockMemory);

            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }

        @Test
        @DisplayName("discoverHttpCallTools should return empty result when version is null")
        void discoverHttpCallTools_nullVersion() {
            when(mockMemory.getAgentId()).thenReturn("agent-1");
            when(mockMemory.getAgentVersion()).thenReturn(null);

            var result = orchestrator.discoverHttpCallTools(mockMemory);

            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }
    }

    // ==================== Counterweight Injection Tests ====================

    @Nested
    @DisplayName("Counterweight strict mode iteration capping")
    class CounterweightTests {

        @Test
        @DisplayName("Strict counterweight should cap tool iterations to 5")
        void strictCounterweight_capsIterations() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("calculator"));
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);
            task.setMaxToolIterations(20);

            var counterweight = new LlmConfiguration.CounterweightConfig();
            counterweight.setEnabled(true);
            counterweight.setLevel("strict");
            task.setCounterweight(counterweight);

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);
            when(mockMemory.getConversationId()).thenReturn("conv-1");

            // Mock ChatModel to return a simple AiMessage (no tool calls)
            ChatModel chatModel = mock(ChatModel.class);
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("Capped response"))
                    .build();
            doReturn(chatResponse).when(chatModel).chat(any(ChatRequest.class));

            var result = orchestrator.executeIfToolsEnabled(
                    chatModel, "system", List.of(UserMessage.from("hi")),
                    task, mockMemory);

            assertNotNull(result, "Should return result even with strict counterweight");
            assertEquals("Capped response", result.response());
            // The chatModel.chat should have been called exactly once
            // (first iteration returns no tool calls → exits loop)
            verify(chatModel, times(1)).chat(any(ChatRequest.class));
        }

        @Test
        @DisplayName("Normal counterweight should NOT cap iterations")
        void normalCounterweight_doesNotCap() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("calculator"));
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);
            task.setMaxToolIterations(20);

            var counterweight = new LlmConfiguration.CounterweightConfig();
            counterweight.setEnabled(true);
            counterweight.setLevel("normal");
            task.setCounterweight(counterweight);

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);
            when(mockMemory.getConversationId()).thenReturn("conv-1");

            ChatModel chatModel = mock(ChatModel.class);
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("Normal response"))
                    .build();
            doReturn(chatResponse).when(chatModel).chat(any(ChatRequest.class));

            var result = orchestrator.executeIfToolsEnabled(
                    chatModel, "system", List.of(UserMessage.from("hi")),
                    task, mockMemory);

            assertNotNull(result);
            assertEquals("Normal response", result.response());
        }

        @Test
        @DisplayName("Disabled counterweight should NOT cap iterations")
        void disabledCounterweight_doesNotCap() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("calculator"));
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);
            task.setMaxToolIterations(20);

            var counterweight = new LlmConfiguration.CounterweightConfig();
            counterweight.setEnabled(false);
            counterweight.setLevel("strict");
            task.setCounterweight(counterweight);

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);
            when(mockMemory.getConversationId()).thenReturn("conv-1");

            ChatModel chatModel = mock(ChatModel.class);
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("Not capped"))
                    .build();
            doReturn(chatResponse).when(chatModel).chat(any(ChatRequest.class));

            var result = orchestrator.executeIfToolsEnabled(
                    chatModel, "system", List.of(UserMessage.from("hi")),
                    task, mockMemory);

            assertNotNull(result);
            assertEquals("Not capped", result.response());
        }
    }

    @Nested
    @DisplayName("Token usage accumulation (cascade cost evidence)")
    class TokenUsageAccumulation {

        @Test
        @DisplayName("a response carrying tokenUsage surfaces it in ExecutionResult.responseMetadata")
        void tokenUsage_singleResponse_populatesResponseMetadata() throws LifecycleException {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("calculator"));
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);
            when(mockMemory.getConversationId()).thenReturn("conv-1");

            ChatModel chatModel = mock(ChatModel.class);
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("done"))
                    .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage(10, 20, 30)).build())
                    .build();
            doReturn(chatResponse).when(chatModel).chat(any(ChatRequest.class));

            var result = orchestrator.executeIfToolsEnabled(
                    chatModel, "sys", List.of(UserMessage.from("hi")), task, mockMemory);

            assertNotNull(result);
            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) result.responseMetadata().get("tokenUsage");
            assertNotNull(usage, "tokenUsage must be surfaced in responseMetadata (feeds cascade cost)");
            assertEquals(10, usage.get("inputTokens"));
            assertEquals(20, usage.get("outputTokens"));
            assertEquals(30, usage.get("totalTokens"));
        }

        @Test
        @DisplayName("sumTokens sums field-by-field and tolerates nulls; tokenUsageMap maps null fields to 0")
        void sumTokens_and_tokenUsageMap_units() {
            var summed = AgentOrchestrator.sumTokens(new TokenUsage(10, 20, 30), new TokenUsage(5, 7, 12));
            assertEquals(15, summed.inputTokenCount());
            assertEquals(27, summed.outputTokenCount());
            assertEquals(42, summed.totalTokenCount());

            var b = new TokenUsage(1, 2, 3);
            assertSame(b, AgentOrchestrator.sumTokens(null, b), "null first operand returns the second unchanged");
            assertSame(b, AgentOrchestrator.sumTokens(b, null), "null second operand returns the first unchanged");

            var zeroMap = AgentOrchestrator.tokenUsageMap(new TokenUsage(null, null, null));
            assertEquals(0, zeroMap.get("inputTokens"));
            assertEquals(0, zeroMap.get("outputTokens"));
            assertEquals(0, zeroMap.get("totalTokens"));

            var map = AgentOrchestrator.tokenUsageMap(new TokenUsage(3, 4, 7));
            assertEquals(3, map.get("inputTokens"));
            assertEquals(4, map.get("outputTokens"));
            assertEquals(7, map.get("totalTokens"));
        }

        @Test
        @DisplayName("cooperative cancellation before a tool call — an interrupt during the model call aborts before the tool runs")
        void cooperativeCancellation_beforeToolExecution() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("calculator"));
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);
            var retry = new LlmConfiguration.RetryConfiguration();
            retry.setMaxAttempts(1);
            task.setRetry(retry);

            when(mockMemory.getAgentId()).thenReturn(null);
            when(mockMemory.getAgentVersion()).thenReturn(null);
            when(mockMemory.getConversationId()).thenReturn("conv-1");

            // The model returns a tool call but sets the interrupt flag as it returns, so
            // the
            // flag is pending when the loop reaches the before-tool cancellation check (the
            // top-of-loop check already ran, before this model call).
            ChatModel chatModel = mock(ChatModel.class);
            var toolCall = ToolExecutionRequest.builder().id("1").name("calculator").arguments("{}").build();
            ChatResponse chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(toolCall)).build();
            doAnswer(inv -> {
                Thread.currentThread().interrupt();
                return chatResponse;
            }).when(chatModel).chat(any(ChatRequest.class));

            try {
                var ex = assertThrows(LifecycleException.class, () -> orchestrator.executeIfToolsEnabled(
                        chatModel, "sys", List.of(UserMessage.from("hi")), task, mockMemory));
                boolean mentionsCancelAndTool = false;
                for (Throwable t = ex; t != null; t = t.getCause()) {
                    String m = t.getMessage();
                    if (m != null && m.toLowerCase().contains("cancel") && m.contains("calculator")) {
                        mentionsCancelAndTool = true;
                        break;
                    }
                }
                assertTrue(mentionsCancelAndTool, "expected 'cancelled ... before tool: calculator' in the chain, got: " + ex.getMessage());
            } finally {
                Thread.interrupted(); // clear the flag so it does not leak to other tests
            }
        }
    }
}
