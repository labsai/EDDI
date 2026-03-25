package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.EddiToolBridge;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

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

        orchestrator = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                mock(EddiToolBridge.class), mock(ToolExecutionService.class),
                mock(McpToolProviderManager.class));
    }

    // ==================== Tool Collection Tests ====================

    @Test
    @DisplayName("collectEnabledTools should return empty list when tools disabled")
    void testCollectEnabledTools_Disabled() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);

        List<Object> tools = orchestrator.collectEnabledTools(task);

        assertTrue(tools.isEmpty(), "Should return empty list when tools disabled");
    }

    @Test
    @DisplayName("collectEnabledTools should return empty list when enableBuiltInTools is null")
    void testCollectEnabledTools_NullEnableBuiltInTools() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(null);

        List<Object> tools = orchestrator.collectEnabledTools(task);

        assertTrue(tools.isEmpty(), "Should return empty list when enableBuiltInTools is null");
    }

    @Test
    @DisplayName("collectEnabledTools should return all tools when enabled without whitelist")
    void testCollectEnabledTools_AllToolsEnabled() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(null);

        List<Object> tools = orchestrator.collectEnabledTools(task);

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

        List<Object> tools = orchestrator.collectEnabledTools(task);

        assertEquals(8, tools.size(), "Should return all tools when whitelist is empty");
    }

    @Test
    @DisplayName("collectEnabledTools should filter by whitelist")
    void testCollectEnabledTools_WithWhitelist() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "datetime"));

        List<Object> tools = orchestrator.collectEnabledTools(task);

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

        List<Object> tools = orchestrator.collectEnabledTools(task);

        assertEquals(1, tools.size(), "Should return only weather tool");
        assertTrue(tools.contains(weatherTool));
    }

    @Test
    @DisplayName("collectEnabledTools should handle all tools in whitelist")
    void testCollectEnabledTools_AllToolsInWhitelist() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of(
                "calculator", "datetime", "websearch", "dataformatter",
                "webscraper", "textsummarizer", "pdfreader", "weather"));

        List<Object> tools = orchestrator.collectEnabledTools(task);

        assertEquals(8, tools.size(), "Should return all 8 tools");
    }

    @Test
    @DisplayName("collectEnabledTools should ignore unknown tools in whitelist")
    void testCollectEnabledTools_UnknownToolInWhitelist() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "unknown_tool", "weather"));

        List<Object> tools = orchestrator.collectEnabledTools(task);

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

    @Test
    @DisplayName("isAgentMode should return true when mcpServers configured")
    void testIsAgentMode_TrueWithMcpServers() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setTools(null);
        var mcpServer = new LlmConfiguration.McpServerConfig();
        mcpServer.setUrl("http://localhost:8080/mcp");
        mcpServer.setName("test-server");
        task.setMcpServers(List.of(mcpServer));

        assertTrue(task.isAgentMode());
    }

    @Test
    @DisplayName("isAgentMode should return false when mcpServers list is empty")
    void testIsAgentMode_FalseWithEmptyMcpServers() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setTools(null);
        task.setMcpServers(List.of());

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

    // ==================== CDI Proxy Tool Spec Extraction Tests ====================
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
        assertTrue(toolClass.getName().contains("_ClientProxy"),
                "Test setup: proxy class name must contain '_ClientProxy'");

        // Without the fix: extracting from proxy class returns empty
        var specsFromProxy = dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(toolClass);
        assertTrue(specsFromProxy.isEmpty(),
                "CDI proxy class should NOT have @Tool annotations (this is the bug scenario)");

        // With the fix: resolve to superclass first
        if (toolClass.getName().contains("_ClientProxy") || toolClass.getName().contains("$$")) {
            toolClass = toolClass.getSuperclass();
        }

        var specsFromSuperclass = dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(toolClass);
        assertEquals(1, specsFromSuperclass.size(),
                "Superclass should have @Tool annotations after proxy resolution");
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
        assertEquals("result: test-input", result,
                "Method from superclass should be invocable on proxy instance");
    }

    @Test
    @DisplayName("Mockito mocks should resolve to original class via superclass")
    void testMockitoProxy_Resolution() {
        var mockTool = mock(SampleToolBean.class);
        Class<?> mockClass = mockTool.getClass();

        // Mockito creates a subclass — its superclass IS the original class
        assertEquals(SampleToolBean.class, mockClass.getSuperclass(),
                "Mockito mock superclass should be the original bean class");

        // The proxy resolution logic should work for any proxy pattern
        // Simulate the resolution: if name matches, use superclass
        Class<?> resolvedClass = mockClass;
        if (resolvedClass.getName().contains("_ClientProxy")
                || resolvedClass.getName().contains("$$")
                || resolvedClass.getName().contains("MockitoMock")) {
            resolvedClass = resolvedClass.getSuperclass();
        }

        assertEquals(SampleToolBean.class, resolvedClass,
                "Proxy resolution should resolve to the original bean class");

        // Verify tool specs can be extracted from resolved class
        var specs = dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(resolvedClass);
        assertEquals(1, specs.size(), "Should find @Tool methods on resolved class");
    }
}
