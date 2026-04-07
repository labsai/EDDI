package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AgentOrchestrator — tool collection and agent mode behavior.
 */
class AgentOrchestratorTest {

    private AgentOrchestrator orchestrator;

    private CalculatorTool calculatorTool;
    private DateTimeTool dateTimeTool;
    private WebSearchTool webSearchTool;
    private DataFormatterTool dataFormatterTool;
    private WebScraperTool webScraperTool;
    private TextSummarizerTool textSummarizerTool;
    private PdfReaderTool pdfReaderTool;
    private WeatherTool weatherTool;
    private IConversationMemory mockMemory;

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

        orchestrator = new AgentOrchestrator(calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool, textSummarizerTool,
                pdfReaderTool, weatherTool, mock(ToolExecutionService.class), mock(McpToolProviderManager.class), mock(A2AToolProviderManager.class),
                mock(IRestAgentStore.class), mock(IRestWorkflowStore.class), mock(IResourceClientLibrary.class), mock(IApiCallExecutor.class),
                mock(IJsonSerialization.class), mock(IMemoryItemConverter.class), mock(IUserMemoryStore.class), mock(ToolResponseTruncator.class));

        // Mock memory for collectEnabledTools (no user memory config = no
        // UserMemoryTool added)
        mockMemory = mock(IConversationMemory.class);
        when(mockMemory.getUserMemoryConfig()).thenReturn(null);
    }

    // ==================== Tool Collection Tests ====================

    @Test
    @DisplayName("collectEnabledTools should return empty list when tools disabled")
    void testCollectEnabledTools_Disabled() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);

        List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

        assertTrue(tools.isEmpty(), "Should return empty list when tools disabled");
    }

    @Test
    @DisplayName("collectEnabledTools should return empty list when enableBuiltInTools is null")
    void testCollectEnabledTools_NullEnableBuiltInTools() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(null);

        List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

        assertTrue(tools.isEmpty(), "Should return empty list when enableBuiltInTools is null");
    }

    @Test
    @DisplayName("collectEnabledTools should return all tools when enabled without whitelist")
    void testCollectEnabledTools_AllToolsEnabled() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(null);

        List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

        assertEquals(8, tools.size(), "Should return all 8 tools");
        assertTrue(tools.contains(calculatorTool));
        assertTrue(tools.contains(dateTimeTool));
        assertTrue(tools.contains(webSearchTool));
        assertTrue(tools.contains(dataFormatterTool));
        assertTrue(tools.contains(webScraperTool));
        assertTrue(tools.contains(textSummarizerTool));
        assertTrue(tools.contains(pdfReaderTool));
        assertTrue(tools.contains(weatherTool));
    }

    @Test
    @DisplayName("collectEnabledTools should return all tools when whitelist is empty")
    void testCollectEnabledTools_EmptyWhitelist() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of());

        List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

        assertEquals(8, tools.size(), "Should return all tools when whitelist is empty");
    }

    @Test
    @DisplayName("collectEnabledTools should filter by whitelist")
    void testCollectEnabledTools_WithWhitelist() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "datetime"));

        List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

        assertEquals(2, tools.size(), "Should return only whitelisted tools");
        assertTrue(tools.contains(calculatorTool));
        assertTrue(tools.contains(dateTimeTool));
        assertFalse(tools.contains(webSearchTool));
        assertFalse(tools.contains(weatherTool));
    }

    @Test
    @DisplayName("collectEnabledTools should handle single tool in whitelist")
    void testCollectEnabledTools_SingleToolWhitelist() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("weather"));

        List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

        assertEquals(1, tools.size(), "Should return only weather tool");
        assertTrue(tools.contains(weatherTool));
    }

    @Test
    @DisplayName("collectEnabledTools should handle all tools in whitelist")
    void testCollectEnabledTools_AllToolsInWhitelist() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(
                List.of("calculator", "datetime", "websearch", "dataformatter", "webscraper", "textsummarizer", "pdfreader", "weather"));

        List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

        assertEquals(8, tools.size(), "Should return all 8 tools");
    }

    @Test
    @DisplayName("collectEnabledTools should ignore unknown tools in whitelist")
    void testCollectEnabledTools_UnknownToolInWhitelist() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "unknown_tool", "weather"));

        List<Object> tools = orchestrator.collectEnabledTools(task, mockMemory);

        assertEquals(2, tools.size(), "Should return only known whitelisted tools");
        assertTrue(tools.contains(calculatorTool));
        assertTrue(tools.contains(weatherTool));
    }

    // ==================== isAgentMode Tests (on Task itself) ====================

    @Test
    @DisplayName("isAgentMode should return false when no tools configured")
    void testIsAgentMode_False() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setEnableHttpCallTools(false);
        task.setTools(null);

        assertFalse(task.isAgentMode());
    }

    @Test
    @DisplayName("isAgentMode should return true when builtin tools enabled")
    void testIsAgentMode_TrueWithBuiltInTools() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);

        assertTrue(task.isAgentMode());
    }

    @Test
    @DisplayName("isAgentMode should return true when custom tools configured")
    void testIsAgentMode_TrueWithCustomTools() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setTools(List.of("eddi://ai.labs.apicalls/weather?version=1"));

        assertTrue(task.isAgentMode());
    }

    @Test
    @DisplayName("isAgentMode should return false when tools list is empty")
    void testIsAgentMode_FalseWithEmptyToolsList() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setTools(List.of());

        assertFalse(task.isAgentMode());
    }

    // ==================== McpServerConfig Tests ====================

    @Test
    @DisplayName("McpServerConfig should have sensible defaults")
    void testMcpServerConfig_Defaults() {
        var config = new LlmConfiguration.McpServerConfig();
        assertEquals("http", config.getTransport());
        assertEquals(30000L, config.getTimeoutMs());
        assertNull(config.getUrl());
        assertNull(config.getName());
        assertNull(config.getApiKey());
    }

    @Test
    @DisplayName("McpServerConfig should store all fields")
    void testMcpServerConfig_AllFields() {
        var config = new LlmConfiguration.McpServerConfig();
        config.setUrl("http://localhost:8080/mcp");
        config.setName("my-server");
        config.setTransport("sse");
        config.setApiKey("${vault:my-key}");
        config.setTimeoutMs(60000L);

        assertEquals("http://localhost:8080/mcp", config.getUrl());
        assertEquals("my-server", config.getName());
        assertEquals("sse", config.getTransport());
        assertEquals("${vault:my-key}", config.getApiKey());
        assertEquals(60000L, config.getTimeoutMs());
    }

    // ==================== getSystemMessage Tests ====================

    @Test
    @DisplayName("getSystemMessage should return null when parameters is null")
    void testGetSystemMessage_NullParameters() {
        var task = new LlmConfiguration.Task();
        task.setParameters(null);

        assertNull(task.getSystemMessage());
    }

    @Test
    @DisplayName("getSystemMessage should return null when systemMessage not in parameters")
    void testGetSystemMessage_NoSystemMessage() {
        var task = new LlmConfiguration.Task();
        task.setParameters(java.util.Map.of("otherKey", "value"));

        assertNull(task.getSystemMessage());
    }

    @Test
    @DisplayName("getSystemMessage should return systemMessage from parameters")
    void testGetSystemMessage_ReturnsSystemMessage() {
        var task = new LlmConfiguration.Task();
        task.setParameters(java.util.Map.of("systemMessage", "You are a helpful assistant"));

        assertEquals("You are a helpful assistant", task.getSystemMessage());
    }

    // ==================== CDI Proxy Tool Spec Extraction Tests
    // ====================
    // Regression tests for: CDI proxy classes don't carry @Tool annotations,
    // so ToolSpecifications.toolSpecificationsFrom() returns empty list.
    // The fix resolves proxy classes to their superclass before extraction.

    /**
     * Simulates a real @Tool-annotated CDI bean.
     */
    static class SampleToolBean {
        @dev.langchain4j.agent.tool.Tool("Sample tool for testing")
        public String sampleAction(@dev.langchain4j.agent.tool.P("input value") String input) {
            return "result: " + input;
        }
    }

    /**
     * Simulates a CDI ClientProxy — a subclass whose name contains "_ClientProxy".
     * CDI proxies do NOT carry the @Tool annotations from their parent class.
     */
    static class SampleToolBean_ClientProxy extends SampleToolBean {
        // CDI proxies are empty subclasses — no @Tool annotations here
    }

    @Test
    @DisplayName("Tool specs should be extracted from real (non-proxy) tool bean")
    void testToolSpecExtraction_RealBean() {
        var tool = new SampleToolBean();
        Class<?> toolClass = tool.getClass();

        assertFalse(toolClass.getName().contains("_ClientProxy"));

        var specs = dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(toolClass);
        assertEquals(1, specs.size(), "Should find 1 @Tool method");
        assertEquals("sampleAction", specs.get(0).name());
    }

    @Test
    @DisplayName("Tool specs should be extracted from CDI proxy via superclass resolution")
    void testToolSpecExtraction_CdiProxy() {
        var proxy = new SampleToolBean_ClientProxy();
        Class<?> toolClass = proxy.getClass();

        // Proxy class should match the detection pattern
        assertTrue(toolClass.getName().contains("_ClientProxy"), "Test setup: proxy class name must contain '_ClientProxy'");

        // Without the fix: extracting from proxy class returns empty
        var specsFromProxy = dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(toolClass);
        assertTrue(specsFromProxy.isEmpty(), "CDI proxy class should NOT have @Tool annotations (this is the bug scenario)");

        // With the fix: resolve to superclass first
        if (toolClass.getName().contains("_ClientProxy") || toolClass.getName().contains("$$")) {
            toolClass = toolClass.getSuperclass();
        }

        var specsFromSuperclass = dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(toolClass);
        assertEquals(1, specsFromSuperclass.size(), "Superclass should have @Tool annotations after proxy resolution");
        assertEquals("sampleAction", specsFromSuperclass.get(0).name());
    }

    @Test
    @DisplayName("Tool executor should use proxy instance but superclass method")
    void testToolExecutor_CdiProxy() throws Exception {
        var proxy = new SampleToolBean_ClientProxy();
        Class<?> toolClass = proxy.getClass();

        // Resolve proxy to superclass
        if (toolClass.getName().contains("_ClientProxy")) {
            toolClass = toolClass.getSuperclass();
        }

        // Find @Tool method on the superclass
        java.lang.reflect.Method toolMethod = null;
        for (var method : toolClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                toolMethod = method;
                break;
            }
        }

        assertNotNull(toolMethod, "Should find @Tool method on superclass");
        assertEquals("sampleAction", toolMethod.getName());

        // Execute via the proxy instance — method should be callable on the proxy
        String result = (String) toolMethod.invoke(proxy, "test-input");
        assertEquals("result: test-input", result, "Method from superclass should be invocable on proxy instance");
    }

    @Test
    @DisplayName("Mockito mocks should resolve to original class via proxy resolution")
    void testMockitoProxy_Resolution() {
        var mockTool = mock(SampleToolBean.class);
        Class<?> mockClass = mockTool.getClass();

        // Mockito mock must be an instance of the original class
        assertInstanceOf(SampleToolBean.class, mockTool, "Mockito mock should be an instance of the original bean class");

        // The proxy resolution logic should work for any proxy pattern.
        // Resolve using Mockito's own API for mock detection, then fall back to
        // class-name heuristics for CDI proxies.
        Class<?> resolvedClass = mockClass;
        if (org.mockito.Mockito.mockingDetails(mockTool).isMock()) {
            // Mockito inline mocks may not create subclasses — use the mocked type
            resolvedClass = org.mockito.Mockito.mockingDetails(mockTool).getMockCreationSettings().getTypeToMock();
        } else if (resolvedClass.getName().contains("_ClientProxy") || resolvedClass.getName().contains("$$")) {
            resolvedClass = resolvedClass.getSuperclass();
        }

        assertEquals(SampleToolBean.class, resolvedClass, "Proxy resolution should resolve to the original bean class");

        // Verify tool specs can be extracted from resolved class
        var specs = dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(resolvedClass);
        assertEquals(1, specs.size(), "Should find @Tool methods on resolved class");
    }

    // ==================== discoverHttpCallTools Tests ====================

    @Test
    @DisplayName("discoverHttpCallTools should return empty when agentId is null")
    void testDiscoverHttpCallTools_NullAgentId() {
        var memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn(null);
        when(memory.getAgentVersion()).thenReturn(1);

        var result = orchestrator.discoverHttpCallTools(memory);

        assertNotNull(result);
        assertTrue(result.toolSpecs().isEmpty());
        assertTrue(result.executors().isEmpty());
    }

    @Test
    @DisplayName("discoverHttpCallTools should return empty when agentVersion is null")
    void testDiscoverHttpCallTools_NullAgentVersion() {
        var memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn("agent-1");
        when(memory.getAgentVersion()).thenReturn(null);

        var result = orchestrator.discoverHttpCallTools(memory);

        assertNotNull(result);
        assertTrue(result.toolSpecs().isEmpty());
    }

    @Test
    @DisplayName("discoverHttpCallTools should return empty when agent has no workflows")
    void testDiscoverHttpCallTools_NoWorkflows() {
        var restAgentStore = mock(IRestAgentStore.class);
        var restWorkflowStore = mock(IRestWorkflowStore.class);
        var resourceClientLibrary = mock(IResourceClientLibrary.class);

        var testOrchestrator = new AgentOrchestrator(calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool,
                textSummarizerTool, pdfReaderTool, weatherTool, mock(ToolExecutionService.class), mock(McpToolProviderManager.class),
                mock(A2AToolProviderManager.class), restAgentStore, restWorkflowStore, resourceClientLibrary, mock(IApiCallExecutor.class),
                mock(IJsonSerialization.class), mock(IMemoryItemConverter.class), mock(IUserMemoryStore.class), mock(ToolResponseTruncator.class));

        var memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn("agent-1");
        when(memory.getAgentVersion()).thenReturn(1);

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of());
        when(restAgentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);

        var result = testOrchestrator.discoverHttpCallTools(memory);

        assertNotNull(result);
        assertTrue(result.toolSpecs().isEmpty());
    }

    @Test
    @DisplayName("discoverHttpCallTools should discover httpcall tools from workflow")
    void testDiscoverHttpCallTools_HappyPath() throws Exception {
        var restAgentStore = mock(IRestAgentStore.class);
        var restWorkflowStore = mock(IRestWorkflowStore.class);
        var resourceClientLibrary = mock(IResourceClientLibrary.class);

        var testOrchestrator = new AgentOrchestrator(calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool,
                textSummarizerTool, pdfReaderTool, weatherTool, mock(ToolExecutionService.class), mock(McpToolProviderManager.class),
                mock(A2AToolProviderManager.class), restAgentStore, restWorkflowStore, resourceClientLibrary, mock(IApiCallExecutor.class),
                mock(IJsonSerialization.class), mock(IMemoryItemConverter.class), mock(IUserMemoryStore.class), mock(ToolResponseTruncator.class));

        var memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn("agent-1");
        when(memory.getAgentVersion()).thenReturn(1);

        // Build workflow with httpcall step
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.httpcalls"));
        step.setConfig(Map.of("uri", "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/hc-1?version=1"));

        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(List.of(step));

        // Build agent config with workflow URI
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));
        when(restAgentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);
        when(restWorkflowStore.readWorkflow("wf-1", 1)).thenReturn(workflowConfig);

        // Build httpcall config with one API call
        var apiCall = new ApiCall();
        apiCall.setName("getWeather");
        apiCall.setDescription("Get weather for a city");

        var httpCallsConfig = new ApiCallsConfiguration();
        httpCallsConfig.setTargetServerUrl("https://api.weather.com");
        httpCallsConfig.setHttpCalls(List.of(apiCall));

        when(resourceClientLibrary.getResource(URI.create("eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/hc-1?version=1"),
                ApiCallsConfiguration.class)).thenReturn(httpCallsConfig);

        var result = testOrchestrator.discoverHttpCallTools(memory);

        assertEquals(1, result.toolSpecs().size());
        assertEquals("getWeather", result.toolSpecs().get(0).name());
        assertEquals("Get weather for a city", result.toolSpecs().get(0).description());
        assertNotNull(result.executors().get("getWeather"));
    }

    @Test
    @DisplayName("discoverHttpCallTools should generate JSON schema from parameters")
    void testDiscoverHttpCallTools_WithParameters() throws Exception {
        var restAgentStore = mock(IRestAgentStore.class);
        var restWorkflowStore = mock(IRestWorkflowStore.class);
        var resourceClientLibrary = mock(IResourceClientLibrary.class);

        var testOrchestrator = new AgentOrchestrator(calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool,
                textSummarizerTool, pdfReaderTool, weatherTool, mock(ToolExecutionService.class), mock(McpToolProviderManager.class),
                mock(A2AToolProviderManager.class), restAgentStore, restWorkflowStore, resourceClientLibrary, mock(IApiCallExecutor.class),
                mock(IJsonSerialization.class), mock(IMemoryItemConverter.class), mock(IUserMemoryStore.class), mock(ToolResponseTruncator.class));

        var memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn("agent-1");
        when(memory.getAgentVersion()).thenReturn(1);

        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.httpcalls"));
        step.setConfig(Map.of("uri", "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/hc-1?version=1"));

        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(List.of(step));

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));
        when(restAgentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);
        when(restWorkflowStore.readWorkflow("wf-1", 1)).thenReturn(workflowConfig);

        var apiCall = new ApiCall();
        apiCall.setName("searchUsers");
        apiCall.setDescription("Search for users");
        apiCall.setParameters(Map.of("query", "Search term", "limit", "Max results"));

        var httpCallsConfig = new ApiCallsConfiguration();
        httpCallsConfig.setTargetServerUrl("https://api.example.com");
        httpCallsConfig.setHttpCalls(List.of(apiCall));

        when(resourceClientLibrary.getResource(any(URI.class), eq(ApiCallsConfiguration.class))).thenReturn(httpCallsConfig);

        var result = testOrchestrator.discoverHttpCallTools(memory);

        assertEquals(1, result.toolSpecs().size());
        var spec = result.toolSpecs().get(0);
        assertNotNull(spec.parameters(), "Should have parameters schema");
    }

    @Test
    @DisplayName("discoverHttpCallTools should skip workflow with null query string")
    void testDiscoverHttpCallTools_MalformedWorkflowUri() {
        var restAgentStore = mock(IRestAgentStore.class);

        var testOrchestrator = new AgentOrchestrator(calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool,
                textSummarizerTool, pdfReaderTool, weatherTool, mock(ToolExecutionService.class), mock(McpToolProviderManager.class),
                mock(A2AToolProviderManager.class), restAgentStore, mock(IRestWorkflowStore.class), mock(IResourceClientLibrary.class),
                mock(IApiCallExecutor.class), mock(IJsonSerialization.class), mock(IMemoryItemConverter.class), mock(IUserMemoryStore.class),
                mock(ToolResponseTruncator.class));

        var memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn("agent-1");
        when(memory.getAgentVersion()).thenReturn(1);

        var agentConfig = new AgentConfiguration();
        // URI without ?version= query
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1")));
        when(restAgentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);

        // Should not throw, should return empty
        var result = testOrchestrator.discoverHttpCallTools(memory);

        assertNotNull(result);
        assertTrue(result.toolSpecs().isEmpty());
    }

    @Test
    @DisplayName("discoverHttpCallTools should skip ApiCalls with blank names")
    void testDiscoverHttpCallTools_SkipsBlankName() throws Exception {
        var restAgentStore = mock(IRestAgentStore.class);
        var restWorkflowStore = mock(IRestWorkflowStore.class);
        var resourceClientLibrary = mock(IResourceClientLibrary.class);

        var testOrchestrator = new AgentOrchestrator(calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool,
                textSummarizerTool, pdfReaderTool, weatherTool, mock(ToolExecutionService.class), mock(McpToolProviderManager.class),
                mock(A2AToolProviderManager.class), restAgentStore, restWorkflowStore, resourceClientLibrary, mock(IApiCallExecutor.class),
                mock(IJsonSerialization.class), mock(IMemoryItemConverter.class), mock(IUserMemoryStore.class), mock(ToolResponseTruncator.class));

        var memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn("agent-1");
        when(memory.getAgentVersion()).thenReturn(1);

        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.httpcalls"));
        step.setConfig(Map.of("uri", "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/hc-1?version=1"));

        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(List.of(step));

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));
        when(restAgentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);
        when(restWorkflowStore.readWorkflow("wf-1", 1)).thenReturn(workflowConfig);

        // ApiCall with blank name — should be skipped
        var apiCall = new ApiCall();
        apiCall.setName("  ");

        var httpCallsConfig = new ApiCallsConfiguration();
        httpCallsConfig.setTargetServerUrl("https://api.example.com");
        httpCallsConfig.setHttpCalls(List.of(apiCall));

        when(resourceClientLibrary.getResource(any(URI.class), eq(ApiCallsConfiguration.class))).thenReturn(httpCallsConfig);

        var result = testOrchestrator.discoverHttpCallTools(memory);

        assertTrue(result.toolSpecs().isEmpty(), "ApiCalls with blank names should be skipped");
    }
}
