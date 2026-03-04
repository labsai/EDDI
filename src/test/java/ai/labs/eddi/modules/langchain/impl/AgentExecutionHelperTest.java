package ai.labs.eddi.modules.langchain.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.modules.langchain.tools.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AgentExecutionHelper.
 * Tests retry logic, tool collection, and error handling.
 */
class AgentExecutionHelperTest {

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
    }

    // ==================== Retry Logic Tests ====================

    @Test
    @DisplayName("executeWithRetry should succeed on first attempt")
    void testExecuteWithRetry_SuccessOnFirstAttempt() throws LifecycleException {
        var task = new LangChainConfiguration.Task();
        AtomicInteger attempts = new AtomicInteger(0);

        String result = AgentExecutionHelper.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return "success";
                },
                task,
                "Test action"
        );

        assertEquals("success", result);
        assertEquals(1, attempts.get(), "Should only attempt once on success");
    }

    @Test
    @DisplayName("executeWithRetry should retry on retryable error")
    void testExecuteWithRetry_RetryOnTimeout() throws LifecycleException {
        var task = new LangChainConfiguration.Task();
        var retryConfig = new LangChainConfiguration.RetryConfiguration();
        retryConfig.setMaxAttempts(3);
        retryConfig.setBackoffDelayMs(10L); // Short delay for testing
        task.setRetry(retryConfig);

        AtomicInteger attempts = new AtomicInteger(0);

        String result = AgentExecutionHelper.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        throw new RuntimeException("Connection timeout");
                    }
                    return "success";
                },
                task,
                "Test action"
        );

        assertEquals("success", result);
        assertEquals(3, attempts.get(), "Should retry until success");
    }

    @Test
    @DisplayName("executeWithRetry should fail after max attempts")
    void testExecuteWithRetry_FailAfterMaxAttempts() {
        var task = new LangChainConfiguration.Task();
        var retryConfig = new LangChainConfiguration.RetryConfiguration();
        retryConfig.setMaxAttempts(2);
        retryConfig.setBackoffDelayMs(10L);
        task.setRetry(retryConfig);

        AtomicInteger attempts = new AtomicInteger(0);

        LifecycleException exception = assertThrows(LifecycleException.class, () ->
                AgentExecutionHelper.executeWithRetry(
                        () -> {
                            attempts.incrementAndGet();
                            throw new RuntimeException("Connection timeout");
                        },
                        task,
                        "Test action"
                )
        );

        assertEquals(2, attempts.get(), "Should attempt max times");
        assertTrue(exception.getMessage().contains("failed after 2 attempts"));
    }

    @Test
    @DisplayName("executeWithRetry should fail immediately on non-retryable error")
    void testExecuteWithRetry_FailImmediatelyOnNonRetryableError() {
        var task = new LangChainConfiguration.Task();
        var retryConfig = new LangChainConfiguration.RetryConfiguration();
        retryConfig.setMaxAttempts(3);
        task.setRetry(retryConfig);

        AtomicInteger attempts = new AtomicInteger(0);

        LifecycleException exception = assertThrows(LifecycleException.class, () ->
                AgentExecutionHelper.executeWithRetry(
                        () -> {
                            attempts.incrementAndGet();
                            throw new RuntimeException("Invalid API key");
                        },
                        task,
                        "Test action"
                )
        );

        assertEquals(1, attempts.get(), "Should fail on first attempt for non-retryable error");
        assertTrue(exception.getMessage().contains("Invalid API key"));
    }

    @Test
    @DisplayName("executeWithRetry should use default config when retry is null")
    void testExecuteWithRetry_DefaultConfig() throws LifecycleException {
        var task = new LangChainConfiguration.Task();
        // retry is null - should use defaults

        String result = AgentExecutionHelper.executeWithRetry(
                () -> "success",
                task,
                "Test action"
        );

        assertEquals("success", result);
    }

    @Test
    @DisplayName("executeWithRetry should handle rate limit error as retryable")
    void testExecuteWithRetry_RateLimitIsRetryable() throws LifecycleException {
        var task = new LangChainConfiguration.Task();
        var retryConfig = new LangChainConfiguration.RetryConfiguration();
        retryConfig.setMaxAttempts(3);
        retryConfig.setBackoffDelayMs(10L);
        task.setRetry(retryConfig);

        AtomicInteger attempts = new AtomicInteger(0);

        String result = AgentExecutionHelper.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        throw new RuntimeException("Rate limit exceeded");
                    }
                    return "success";
                },
                task,
                "Test action"
        );

        assertEquals("success", result);
        assertEquals(2, attempts.get());
    }

    // ==================== Tool Collection Tests ====================

    @Test
    @DisplayName("collectEnabledTools should return empty list when tools disabled")
    void testCollectEnabledTools_Disabled() {
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(false);

        List<Object> tools = AgentExecutionHelper.collectEnabledTools(
                task, calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool
        );

        assertTrue(tools.isEmpty(), "Should return empty list when tools disabled");
    }

    @Test
    @DisplayName("collectEnabledTools should return empty list when enableBuiltInTools is null")
    void testCollectEnabledTools_NullEnableBuiltInTools() {
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(null);

        List<Object> tools = AgentExecutionHelper.collectEnabledTools(
                task, calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool
        );

        assertTrue(tools.isEmpty(), "Should return empty list when enableBuiltInTools is null");
    }

    @Test
    @DisplayName("collectEnabledTools should return all tools when enabled without whitelist")
    void testCollectEnabledTools_AllToolsEnabled() {
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(null);

        List<Object> tools = AgentExecutionHelper.collectEnabledTools(
                task, calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool
        );

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
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of());

        List<Object> tools = AgentExecutionHelper.collectEnabledTools(
                task, calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool
        );

        assertEquals(8, tools.size(), "Should return all tools when whitelist is empty");
    }

    @Test
    @DisplayName("collectEnabledTools should filter by whitelist")
    void testCollectEnabledTools_WithWhitelist() {
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "datetime"));

        List<Object> tools = AgentExecutionHelper.collectEnabledTools(
                task, calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool
        );

        assertEquals(2, tools.size(), "Should return only whitelisted tools");
        assertTrue(tools.contains(calculatorTool));
        assertTrue(tools.contains(dateTimeTool));
        assertFalse(tools.contains(webSearchTool));
        assertFalse(tools.contains(weatherTool));
    }

    @Test
    @DisplayName("collectEnabledTools should handle single tool in whitelist")
    void testCollectEnabledTools_SingleToolWhitelist() {
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("weather"));

        List<Object> tools = AgentExecutionHelper.collectEnabledTools(
                task, calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool
        );

        assertEquals(1, tools.size(), "Should return only weather tool");
        assertTrue(tools.contains(weatherTool));
    }

    @Test
    @DisplayName("collectEnabledTools should handle all tools in whitelist")
    void testCollectEnabledTools_AllToolsInWhitelist() {
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of(
                "calculator", "datetime", "websearch", "dataformatter",
                "webscraper", "textsummarizer", "pdfreader", "weather"
        ));

        List<Object> tools = AgentExecutionHelper.collectEnabledTools(
                task, calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool
        );

        assertEquals(8, tools.size(), "Should return all 8 tools");
    }

    @Test
    @DisplayName("collectEnabledTools should ignore unknown tools in whitelist")
    void testCollectEnabledTools_UnknownToolInWhitelist() {
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "unknown_tool", "weather"));

        List<Object> tools = AgentExecutionHelper.collectEnabledTools(
                task, calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool
        );

        assertEquals(2, tools.size(), "Should return only known whitelisted tools");
        assertTrue(tools.contains(calculatorTool));
        assertTrue(tools.contains(weatherTool));
    }

    // ==================== isAgentMode Tests ====================

    @Test
    @DisplayName("isAgentMode should return false when no tools configured")
    void testIsAgentMode_False() {
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setTools(null);

        assertFalse(task.isAgentMode());
    }

    @Test
    @DisplayName("isAgentMode should return true when builtin tools enabled")
    void testIsAgentMode_TrueWithBuiltInTools() {
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(true);

        assertTrue(task.isAgentMode());
    }

    @Test
    @DisplayName("isAgentMode should return true when custom tools configured")
    void testIsAgentMode_TrueWithCustomTools() {
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setTools(List.of("eddi://ai.labs.httpcalls/weather?version=1"));

        assertTrue(task.isAgentMode());
    }

    @Test
    @DisplayName("isAgentMode should return false when tools list is empty")
    void testIsAgentMode_FalseWithEmptyToolsList() {
        var task = new LangChainConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setTools(List.of());

        assertFalse(task.isAgentMode());
    }

    // ==================== getSystemMessage Tests ====================

    @Test
    @DisplayName("getSystemMessage should return null when parameters is null")
    void testGetSystemMessage_NullParameters() {
        var task = new LangChainConfiguration.Task();
        task.setParameters(null);

        assertNull(task.getSystemMessage());
    }

    @Test
    @DisplayName("getSystemMessage should return null when systemMessage not in parameters")
    void testGetSystemMessage_NoSystemMessage() {
        var task = new LangChainConfiguration.Task();
        task.setParameters(java.util.Map.of("otherKey", "value"));

        assertNull(task.getSystemMessage());
    }

    @Test
    @DisplayName("getSystemMessage should return systemMessage from parameters")
    void testGetSystemMessage_ReturnsSystemMessage() {
        var task = new LangChainConfiguration.Task();
        task.setParameters(java.util.Map.of("systemMessage", "You are a helpful assistant"));

        assertEquals("You are a helpful assistant", task.getSystemMessage());
    }

    // ==================== RetryConfiguration Default Tests ====================

    @Test
    @DisplayName("RetryConfiguration should have correct defaults")
    void testRetryConfigurationDefaults() {
        var retryConfig = new LangChainConfiguration.RetryConfiguration();

        assertEquals(3, retryConfig.getMaxAttempts());
        assertEquals(1000L, retryConfig.getBackoffDelayMs());
        assertEquals(2.0, retryConfig.getBackoffMultiplier());
        assertEquals(10000L, retryConfig.getMaxBackoffDelayMs());
    }
}
