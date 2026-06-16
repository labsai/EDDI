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
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ToolLoadingStrategy;
import ai.labs.eddi.modules.llm.tools.ConversationRecallTool;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.UserMemoryTool;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link AgentOrchestrator}.
 * <p>
 * This test class is in the same package as the target class (package-private
 * access).
 */
class AgentOrchestratorTest {

    // --- Built-in tool mocks ---
    @Mock
    private CalculatorTool calculatorTool;
    @Mock
    private DateTimeTool dateTimeTool;
    @Mock
    private WebSearchTool webSearchTool;
    @Mock
    private DataFormatterTool dataFormatterTool;
    @Mock
    private WebScraperTool webScraperTool;
    @Mock
    private TextSummarizerTool textSummarizerTool;
    @Mock
    private PdfReaderTool pdfReaderTool;
    @Mock
    private WeatherTool weatherTool;
    @Mock
    private FetchToolResponsePageTool fetchToolResponsePageTool;

    // --- Service mocks ---
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private McpToolProviderManager mcpToolProviderManager;
    @Mock
    private A2AToolProviderManager a2aToolProviderManager;
    @Mock
    private IRestAgentStore restAgentStore;
    @Mock
    private IRestWorkflowStore restWorkflowStore;
    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IApiCallExecutor apiCallExecutor;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private IUserMemoryStore userMemoryStore;
    @Mock
    private ToolResponseTruncator toolResponseTruncator;
    @Mock
    private TenantQuotaService tenantQuotaService;
    @Mock
    private MemorySnapshotService memorySnapshotService;

    // --- Memory mock ---
    @Mock
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IConversationProperties conversationProperties;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orchestrator = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restAgentStore, restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                userMemoryStore, toolResponseTruncator, tenantQuotaService,
                memorySnapshotService);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 1. collectEnabledTools — enableBuiltInTools null/false/true
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void collectEnabledTools_enableBuiltInToolsNull_returnsEmpty() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(null);

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectEnabledTools_enableBuiltInToolsFalse_returnsEmpty() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectEnabledTools_trueNoWhitelist_returnsAllBuiltInTools() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);

        // Memory stubs for addUserMemoryToolIfEnabled /
        // addConversationRecallToolIfEnabled
        doReturn(null).when(memory).getUserMemoryConfig();

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        // 9 built-in tools (calculator, datetime, websearch, dataformatter, webscraper,
        // textsummarizer, pdfreader, weather, fetchToolResponsePage)
        // UserMemory returns early (null config), ConversationRecall returns early
        // (null summary config)
        assertEquals(9, result.size());
        assertTrue(result.contains(calculatorTool));
        assertTrue(result.contains(dateTimeTool));
        assertTrue(result.contains(webSearchTool));
        assertTrue(result.contains(dataFormatterTool));
        assertTrue(result.contains(webScraperTool));
        assertTrue(result.contains(textSummarizerTool));
        assertTrue(result.contains(pdfReaderTool));
        assertTrue(result.contains(weatherTool));
        assertTrue(result.contains(fetchToolResponsePageTool));
    }

    @Test
    void collectEnabledTools_trueWithWhitelist_filtersCorrectly() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "weather"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(2, result.size());
        assertTrue(result.contains(calculatorTool));
        assertTrue(result.contains(weatherTool));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 2. collectEnabledTools — LAZY strategy adds DiscoverToolsTool
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void collectEnabledTools_lazyStrategy_addsDiscoverToolsTool() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setToolLoadingStrategy(ToolLoadingStrategy.LAZY);
        task.setBuiltInToolsWhitelist(List.of("calculator"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        // calculator + DiscoverToolsTool
        assertEquals(2, result.size());
        assertTrue(result.contains(calculatorTool));
        assertTrue(result.stream().anyMatch(t -> t instanceof DiscoverToolsTool));
    }

    @Test
    void collectEnabledTools_eagerStrategy_doesNotAddDiscoverToolsTool() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setToolLoadingStrategy(ToolLoadingStrategy.EAGER);
        task.setBuiltInToolsWhitelist(List.of("calculator"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertTrue(result.contains(calculatorTool));
        assertTrue(result.stream().noneMatch(t -> t instanceof DiscoverToolsTool));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 3. executeIfToolsEnabled — returns null when no tools enabled
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void executeIfToolsEnabled_noToolsEnabled_returnsNull() throws Exception {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        task.setA2aAgents(null);

        var result = orchestrator.executeIfToolsEnabled(null, "sys", List.of(), task, memory);

        assertNull(result);
    }

    @Test
    void executeIfToolsEnabled_emptyBuiltInAndNoExternal_returnsNull() throws Exception {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(null);
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        task.setA2aAgents(List.of());

        var result = orchestrator.executeIfToolsEnabled(null, "sys", List.of(), task, memory);

        assertNull(result);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 4. activateDiscoveredTools — via reflection (private method)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void activateDiscoveredTools_validJson_activatesMatchingSpecs() throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "activateDiscoveredTools", String.class, List.class, List.class);
        method.setAccessible(true);

        ToolSpecification calcSpec = ToolSpecification.builder()
                .name("calculate").description("math").build();
        ToolSpecification weatherSpec = ToolSpecification.builder()
                .name("get_weather").description("weather").build();
        ToolSpecification discoverSpec = ToolSpecification.builder()
                .name("discover_tools").description("discover").build();

        List<ToolSpecification> builtInSpecs = List.of(calcSpec, weatherSpec, discoverSpec);
        List<ToolSpecification> activeSpecs = new ArrayList<>();
        activeSpecs.add(discoverSpec);

        String discoverResult = "{\"tools\": [{\"name\": \"calculate\"}, {\"name\": \"get_weather\"}]}";

        method.invoke(orchestrator, discoverResult, builtInSpecs, activeSpecs);

        // discover_tools was already active, calculate and get_weather should be added
        assertEquals(3, activeSpecs.size());
        assertTrue(activeSpecs.stream().anyMatch(s -> "calculate".equals(s.name())));
        assertTrue(activeSpecs.stream().anyMatch(s -> "get_weather".equals(s.name())));
    }

    @Test
    void activateDiscoveredTools_invalidJson_handledGracefully() throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "activateDiscoveredTools", String.class, List.class, List.class);
        method.setAccessible(true);

        List<ToolSpecification> builtInSpecs = List.of();
        List<ToolSpecification> activeSpecs = new ArrayList<>();

        // Should not throw — invalid JSON is caught internally
        assertDoesNotThrow(() -> method.invoke(orchestrator, "not valid json {{{", builtInSpecs, activeSpecs));
        assertTrue(activeSpecs.isEmpty());
    }

    @Test
    void activateDiscoveredTools_toolsArrayMissing_noActivation() throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "activateDiscoveredTools", String.class, List.class, List.class);
        method.setAccessible(true);

        ToolSpecification calcSpec = ToolSpecification.builder()
                .name("calculate").description("math").build();
        List<ToolSpecification> builtInSpecs = List.of(calcSpec);
        List<ToolSpecification> activeSpecs = new ArrayList<>();

        // JSON with no "tools" key
        String discoverResult = "{\"categories\": [\"math\"]}";

        method.invoke(orchestrator, discoverResult, builtInSpecs, activeSpecs);

        assertTrue(activeSpecs.isEmpty());
    }

    @Test
    void activateDiscoveredTools_alreadyActive_noDuplicates() throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "activateDiscoveredTools", String.class, List.class, List.class);
        method.setAccessible(true);

        ToolSpecification calcSpec = ToolSpecification.builder()
                .name("calculate").description("math").build();
        List<ToolSpecification> builtInSpecs = List.of(calcSpec);
        List<ToolSpecification> activeSpecs = new ArrayList<>();
        activeSpecs.add(calcSpec); // already active

        String discoverResult = "{\"tools\": [{\"name\": \"calculate\"}]}";

        method.invoke(orchestrator, discoverResult, builtInSpecs, activeSpecs);

        // Should not duplicate — still 1
        assertEquals(1, activeSpecs.size());
    }

    // ═══════════════════════════════════════════════════════════════════
    // 5. collectAllBuiltInTools whitelist variations
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void collectEnabledTools_whitelistCalculator_onlyCalculator() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertSame(calculatorTool, result.get(0));
    }

    @Test
    void collectEnabledTools_whitelistDatetime_onlyDatetime() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("datetime"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertSame(dateTimeTool, result.get(0));
    }

    @Test
    void collectEnabledTools_whitelistWebsearch_onlyWebsearch() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("websearch"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertSame(webSearchTool, result.get(0));
    }

    @Test
    void collectEnabledTools_whitelistDataformatter_onlyDataformatter() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("dataformatter"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertSame(dataFormatterTool, result.get(0));
    }

    @Test
    void collectEnabledTools_whitelistWebscraper_onlyWebscraper() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("webscraper"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertSame(webScraperTool, result.get(0));
    }

    @Test
    void collectEnabledTools_whitelistTextsummarizer_onlyTextsummarizer() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("textsummarizer"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertSame(textSummarizerTool, result.get(0));
    }

    @Test
    void collectEnabledTools_whitelistPdfreader_onlyPdfreader() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("pdfreader"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertSame(pdfReaderTool, result.get(0));
    }

    @Test
    void collectEnabledTools_whitelistWeather_onlyWeather() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("weather"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertSame(weatherTool, result.get(0));
    }

    @Test
    void collectEnabledTools_whitelistFetchPage_addsFetchTool() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("fetch_page"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertSame(fetchToolResponsePageTool, result.get(0));
    }

    @Test
    void collectEnabledTools_whitelistFetchToolResponsePage_addsFetchTool() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("fetch_tool_response_page"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertSame(fetchToolResponsePageTool, result.get(0));
    }

    @Test
    void collectEnabledTools_whitelistUsermemory_addsUserMemoryTool() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("usermemory"));

        var config = new AgentConfiguration.UserMemoryConfig();
        doReturn(config).when(memory).getUserMemoryConfig();
        doReturn(conversationProperties).when(memory).getConversationProperties();
        doReturn("user-1").when(memory).getUserId();
        doReturn("agent-1").when(memory).getAgentId();
        doReturn("conv-1").when(memory).getConversationId();

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof UserMemoryTool);
    }

    @Test
    void collectEnabledTools_whitelistConversationRecall_addsRecallTool() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("conversationRecall"));

        var summaryConfig = new LlmConfiguration.ConversationSummaryConfig();
        summaryConfig.setEnabled(true);
        task.setConversationSummary(summaryConfig);

        // Set up memory to have a summary
        doReturn(conversationProperties).when(memory).getConversationProperties();
        var summaryProp = new Property("conversation:running_summary", "Some summary", Property.Scope.conversation);
        doReturn(summaryProp).when(conversationProperties).get("conversation:running_summary");
        var throughStepProp = new Property("conversation:summary_through_step", 5, Property.Scope.conversation);
        doReturn(throughStepProp).when(conversationProperties).get("conversation:summary_through_step");
        doReturn(List.of(new ConversationOutput())).when(memory).getConversationOutputs();

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof ConversationRecallTool);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 6. addUserMemoryToolIfEnabled — null config, null store, groupId
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void collectEnabledTools_userMemoryNullConfig_notAdded() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("usermemory"));

        doReturn(null).when(memory).getUserMemoryConfig();

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectEnabledTools_userMemoryNullStore_notAdded() {
        // Create orchestrator with null userMemoryStore
        var orchestratorNoStore = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restAgentStore, restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                null, // null userMemoryStore
                toolResponseTruncator, tenantQuotaService, memorySnapshotService);

        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("usermemory"));

        var config = new AgentConfiguration.UserMemoryConfig();
        doReturn(config).when(memory).getUserMemoryConfig();

        List<Object> result = orchestratorNoStore.collectEnabledTools(task, memory);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectEnabledTools_userMemoryWithGroupIdProperty_addsToolWithGroupIds() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("usermemory"));

        var config = new AgentConfiguration.UserMemoryConfig();
        doReturn(config).when(memory).getUserMemoryConfig();
        doReturn(conversationProperties).when(memory).getConversationProperties();
        doReturn("user-1").when(memory).getUserId();
        doReturn("agent-1").when(memory).getAgentId();
        doReturn("conv-1").when(memory).getConversationId();

        var groupIdProp = new Property("groupId", "group-42", Property.Scope.longTerm);
        doReturn(groupIdProp).when(conversationProperties).get("groupId");

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof UserMemoryTool);
    }

    @Test
    void collectEnabledTools_userMemoryNoGroupIdProperty_addsToolWithEmptyGroupIds() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("usermemory"));

        var config = new AgentConfiguration.UserMemoryConfig();
        doReturn(config).when(memory).getUserMemoryConfig();
        doReturn(conversationProperties).when(memory).getConversationProperties();
        doReturn("user-1").when(memory).getUserId();
        doReturn("agent-1").when(memory).getAgentId();
        doReturn("conv-1").when(memory).getConversationId();
        doReturn(null).when(conversationProperties).get("groupId");

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof UserMemoryTool);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 7. addConversationRecallToolIfEnabled — null/disabled/enabled
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void collectEnabledTools_conversationRecall_nullSummaryConfig_notAdded() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("conversationRecall"));
        task.setConversationSummary(null);

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectEnabledTools_conversationRecall_disabledSummaryConfig_notAdded() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("conversationRecall"));

        var summaryConfig = new LlmConfiguration.ConversationSummaryConfig();
        summaryConfig.setEnabled(false);
        task.setConversationSummary(summaryConfig);

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectEnabledTools_conversationRecall_enabledNoSummary_notAdded() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("conversationRecall"));

        var summaryConfig = new LlmConfiguration.ConversationSummaryConfig();
        summaryConfig.setEnabled(true);
        task.setConversationSummary(summaryConfig);

        // readSummary calls
        // memory.getConversationProperties().get(PROP_RUNNING_SUMMARY) → null
        doReturn(conversationProperties).when(memory).getConversationProperties();
        doReturn(null).when(conversationProperties).get("conversation:running_summary");

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectEnabledTools_conversationRecall_enabledWithSummary_added() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("conversationRecall"));

        var summaryConfig = new LlmConfiguration.ConversationSummaryConfig();
        summaryConfig.setEnabled(true);
        task.setConversationSummary(summaryConfig);

        doReturn(conversationProperties).when(memory).getConversationProperties();
        var summaryProp = new Property("conversation:running_summary", "Previous summary", Property.Scope.conversation);
        doReturn(summaryProp).when(conversationProperties).get("conversation:running_summary");
        var throughStepProp = new Property("conversation:summary_through_step", 10, Property.Scope.conversation);
        doReturn(throughStepProp).when(conversationProperties).get("conversation:summary_through_step");
        doReturn(List.of(new ConversationOutput())).when(memory).getConversationOutputs();

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof ConversationRecallTool);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 8. safeTemplateMerge — reserved keys blocked, normal keys merged
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void safeTemplateMerge_reservedKeysBlocked() throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "safeTemplateMerge", Map.class, Map.class);
        method.setAccessible(true);

        Map<String, Object> templateData = new HashMap<>();
        templateData.put("context", "original-context");
        templateData.put("properties", "original-properties");
        templateData.put("memory", "original-memory");
        templateData.put("userInfo", "original-userInfo");
        templateData.put("conversationInfo", "original-conversationInfo");
        templateData.put("conversationLog", "original-conversationLog");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("context", "INJECTED");
        args.put("properties", "INJECTED");
        args.put("memory", "INJECTED");
        args.put("userInfo", "INJECTED");
        args.put("conversationInfo", "INJECTED");
        args.put("conversationLog", "INJECTED");

        method.invoke(null, templateData, args);

        // All reserved keys should retain their original values
        assertEquals("original-context", templateData.get("context"));
        assertEquals("original-properties", templateData.get("properties"));
        assertEquals("original-memory", templateData.get("memory"));
        assertEquals("original-userInfo", templateData.get("userInfo"));
        assertEquals("original-conversationInfo", templateData.get("conversationInfo"));
        assertEquals("original-conversationLog", templateData.get("conversationLog"));
    }

    @Test
    void safeTemplateMerge_normalKeysMerged() throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "safeTemplateMerge", Map.class, Map.class);
        method.setAccessible(true);

        Map<String, Object> templateData = new HashMap<>();
        templateData.put("existingKey", "existing");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("city", "Vienna");
        args.put("language", "German");

        method.invoke(null, templateData, args);

        assertEquals("existing", templateData.get("existingKey"));
        assertEquals("Vienna", templateData.get("city"));
        assertEquals("German", templateData.get("language"));
    }

    @Test
    void safeTemplateMerge_mixedReservedAndNormal_onlyNormalMerged() throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "safeTemplateMerge", Map.class, Map.class);
        method.setAccessible(true);

        Map<String, Object> templateData = new HashMap<>();
        templateData.put("context", "protected");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("context", "hacked");
        args.put("query", "find restaurants");

        method.invoke(null, templateData, args);

        assertEquals("protected", templateData.get("context"));
        assertEquals("find restaurants", templateData.get("query"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 9. HttpCallToolsResult and ExecutionResult record creation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void httpCallToolsResult_recordCreation() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("test_tool").description("desc").build();
        List<ToolSpecification> specs = List.of(spec);
        Map<String, dev.langchain4j.service.tool.ToolExecutor> executors = Map.of();

        var result = new AgentOrchestrator.HttpCallToolsResult(specs, executors);

        assertNotNull(result);
        assertEquals(1, result.toolSpecs().size());
        assertEquals("test_tool", result.toolSpecs().get(0).name());
        assertTrue(result.executors().isEmpty());
    }

    @Test
    void executionResult_recordCreation() {
        Map<String, Object> traceEntry = Map.of("type", "tool_call", "tool", "calc");
        List<Map<String, Object>> trace = List.of(traceEntry);

        var result = new AgentOrchestrator.ExecutionResult("Hello world", trace);

        assertEquals("Hello world", result.response());
        assertEquals(1, result.trace().size());
        assertEquals("tool_call", result.trace().get(0).get("type"));
    }

    @Test
    void executionResult_nullValues() {
        var result = new AgentOrchestrator.ExecutionResult(null, null);

        assertNull(result.response());
        assertNull(result.trace());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Edge cases and combinations
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void collectEnabledTools_emptyWhitelist_returnsAllTools() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of()); // empty list

        doReturn(null).when(memory).getUserMemoryConfig();

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        // Empty whitelist is treated same as null — returns all built-in tools
        assertEquals(9, result.size());
    }

    @Test
    void collectEnabledTools_whitelistMultipleTools_returnsAll() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "datetime", "websearch", "pdfreader"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(4, result.size());
        assertTrue(result.contains(calculatorTool));
        assertTrue(result.contains(dateTimeTool));
        assertTrue(result.contains(webSearchTool));
        assertTrue(result.contains(pdfReaderTool));
    }

    @Test
    void collectEnabledTools_whitelistUnknownTool_returnsEmpty() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("nonexistent_tool"));

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectEnabledTools_lazyWithNoWhitelist_includesAllPlusDiscoverTool() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setToolLoadingStrategy(ToolLoadingStrategy.LAZY);
        // No whitelist

        doReturn(null).when(memory).getUserMemoryConfig();

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        // 9 built-in + DiscoverToolsTool = 10
        assertEquals(10, result.size());
        assertTrue(result.stream().anyMatch(t -> t instanceof DiscoverToolsTool));
    }

    @Test
    void activateDiscoveredTools_toolsArrayNotArray_noActivation() throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "activateDiscoveredTools", String.class, List.class, List.class);
        method.setAccessible(true);

        ToolSpecification calcSpec = ToolSpecification.builder()
                .name("calculate").description("math").build();
        List<ToolSpecification> builtInSpecs = List.of(calcSpec);
        List<ToolSpecification> activeSpecs = new ArrayList<>();

        // "tools" key exists but is not an array
        String discoverResult = "{\"tools\": \"not_an_array\"}";

        method.invoke(orchestrator, discoverResult, builtInSpecs, activeSpecs);

        assertTrue(activeSpecs.isEmpty());
    }

    @Test
    void activateDiscoveredTools_toolEntryWithoutName_skipped() throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "activateDiscoveredTools", String.class, List.class, List.class);
        method.setAccessible(true);

        ToolSpecification calcSpec = ToolSpecification.builder()
                .name("calculate").description("math").build();
        List<ToolSpecification> builtInSpecs = List.of(calcSpec);
        List<ToolSpecification> activeSpecs = new ArrayList<>();

        // Tool entry has no "name" field
        String discoverResult = "{\"tools\": [{\"description\": \"no name here\"}]}";

        method.invoke(orchestrator, discoverResult, builtInSpecs, activeSpecs);

        assertTrue(activeSpecs.isEmpty());
    }

    @Test
    void activateDiscoveredTools_noMatchingBuiltIn_noActivation() throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "activateDiscoveredTools", String.class, List.class, List.class);
        method.setAccessible(true);

        ToolSpecification calcSpec = ToolSpecification.builder()
                .name("calculate").description("math").build();
        List<ToolSpecification> builtInSpecs = List.of(calcSpec);
        List<ToolSpecification> activeSpecs = new ArrayList<>();

        // Discovered tool name doesn't match any built-in
        String discoverResult = "{\"tools\": [{\"name\": \"totally_different\"}]}";

        method.invoke(orchestrator, discoverResult, builtInSpecs, activeSpecs);

        assertTrue(activeSpecs.isEmpty());
    }

    @Test
    void safeTemplateMerge_emptyArgs_noChange() throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "safeTemplateMerge", Map.class, Map.class);
        method.setAccessible(true);

        Map<String, Object> templateData = new HashMap<>();
        templateData.put("context", "original");

        method.invoke(null, templateData, Map.of());

        assertEquals(1, templateData.size());
        assertEquals("original", templateData.get("context"));
    }

    @Test
    void httpCallToolsResult_emptySpecs() {
        var result = new AgentOrchestrator.HttpCallToolsResult(List.of(), Map.of());

        assertTrue(result.toolSpecs().isEmpty());
        assertTrue(result.executors().isEmpty());
    }

    @Test
    void executionResult_emptyTrace() {
        var result = new AgentOrchestrator.ExecutionResult("response text", List.of());

        assertEquals("response text", result.response());
        assertTrue(result.trace().isEmpty());
    }

    @Test
    void collectEnabledTools_userMemoryPropsNull_addsToolWithEmptyGroupIds() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("usermemory"));

        var config = new AgentConfiguration.UserMemoryConfig();
        doReturn(config).when(memory).getUserMemoryConfig();
        doReturn(null).when(memory).getConversationProperties();
        doReturn("user-1").when(memory).getUserId();
        doReturn("agent-1").when(memory).getAgentId();
        doReturn("conv-1").when(memory).getConversationId();

        List<Object> result = orchestrator.collectEnabledTools(task, memory);

        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof UserMemoryTool);
    }
}
