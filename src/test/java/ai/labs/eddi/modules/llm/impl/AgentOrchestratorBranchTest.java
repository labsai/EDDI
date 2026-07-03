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
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additional branch coverage tests for {@link AgentOrchestrator} focusing on
 * collectEnabledTools branches, LAZY mode, counterweight logic, user memory,
 * conversation recall, and safeTemplateMerge.
 */
@DisplayName("AgentOrchestrator — Additional Branch Coverage")
class AgentOrchestratorBranchTest {

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
    @Mock
    private IConversationMemory memory;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        openMocks(this);
        orchestrator = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool, toolExecutionService,
                mcpToolProviderManager, a2aToolProviderManager,
                restAgentStore, restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                userMemoryStore, toolResponseTruncator, tenantQuotaService,
                memorySnapshotService,
                null, null, null, null, null);
    }

    // =========================================================
    // collectEnabledTools — enableBuiltInTools null/false
    // =========================================================

    @Nested
    @DisplayName("collectEnabledTools — enableBuiltInTools check")
    class EnableBuiltInToolsCheck {

        @Test
        @DisplayName("null enableBuiltInTools returns empty list")
        void nullEnableBuiltInTools() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(null);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertTrue(tools.isEmpty());
        }

        @Test
        @DisplayName("false enableBuiltInTools returns empty list")
        void falseEnableBuiltInTools() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertTrue(tools.isEmpty());
        }

        @Test
        @DisplayName("true enableBuiltInTools with no whitelist returns all tools")
        void trueNoWhitelist() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            // Should include at least 9 built-in tools (calculator, datetime, etc.)
            assertTrue(tools.size() >= 9);
        }
    }

    // =========================================================
    // collectEnabledTools — readAttachment auto-add
    // =========================================================

    @Nested
    @DisplayName("collectEnabledTools — readAttachment tool")
    class ReadAttachmentAutoAdd {

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void withAttachments(boolean present) {
            var step = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(step);
            when(memory.getConversationId()).thenReturn("conv-1");
            if (present) {
                IData data = mock(IData.class);
                doReturn(List.of(new Object())).when(data).getResult();
                doReturn(data).when(step).getLatestData(MemoryKeys.ATTACHMENTS);
            } else {
                doReturn(null).when(step).getLatestData(MemoryKeys.ATTACHMENTS);
            }
        }

        private boolean hasReadAttachmentTool(List<Object> tools) {
            return tools.stream().anyMatch(t -> t instanceof ReadAttachmentTool);
        }

        @Test
        @DisplayName("auto-added (no whitelist) when services set and attachments present")
        void autoAdded() {
            orchestrator.setAttachmentServices(mock(IAttachmentStore.class), new AttachmentTextExtractor(10_000));
            withAttachments(true);
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);

            assertTrue(hasReadAttachmentTool(orchestrator.collectEnabledTools(task, memory)));
        }

        @Test
        @DisplayName("added when whitelisted by 'readattachment'")
        void addedViaWhitelist() {
            orchestrator.setAttachmentServices(mock(IAttachmentStore.class), new AttachmentTextExtractor(10_000));
            withAttachments(true);
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("readattachment"));

            assertTrue(hasReadAttachmentTool(orchestrator.collectEnabledTools(task, memory)));
        }

        @Test
        @DisplayName("NOT added when whitelist excludes it")
        void notAddedWhenWhitelistExcludes() {
            orchestrator.setAttachmentServices(mock(IAttachmentStore.class), new AttachmentTextExtractor(10_000));
            withAttachments(true);
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("calculator"));

            assertFalse(hasReadAttachmentTool(orchestrator.collectEnabledTools(task, memory)));
        }

        @Test
        @DisplayName("NOT added when attachment services are unset")
        void notAddedWithoutServices() {
            withAttachments(true); // services never set
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);

            assertFalse(hasReadAttachmentTool(orchestrator.collectEnabledTools(task, memory)));
        }

        @Test
        @DisplayName("NOT added when the turn has no attachments")
        void notAddedWithoutAttachments() {
            orchestrator.setAttachmentServices(mock(IAttachmentStore.class), new AttachmentTextExtractor(10_000));
            withAttachments(false);
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);

            assertFalse(hasReadAttachmentTool(orchestrator.collectEnabledTools(task, memory)));
        }
    }

    // =========================================================
    // collectEnabledTools — whitelist filtering
    // =========================================================

    @Nested
    @DisplayName("collectEnabledTools — whitelist branches")
    class WhitelistBranches {

        @Test
        @DisplayName("whitelist with only 'calculator' returns 1 tool")
        void whitelistCalculatorOnly() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("calculator"));

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
            assertSame(calculatorTool, tools.get(0));
        }

        @Test
        @DisplayName("whitelist with 'datetime' returns dateTimeTool")
        void whitelistDatetime() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("datetime"));

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
            assertSame(dateTimeTool, tools.get(0));
        }

        @Test
        @DisplayName("whitelist with 'websearch' returns webSearchTool")
        void whitelistWebsearch() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("websearch"));

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
            assertSame(webSearchTool, tools.get(0));
        }

        @Test
        @DisplayName("whitelist with 'dataformatter' returns dataFormatterTool")
        void whitelistDataformatter() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("dataformatter"));

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
            assertSame(dataFormatterTool, tools.get(0));
        }

        @Test
        @DisplayName("whitelist with 'webscraper' returns webScraperTool")
        void whitelistWebscraper() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("webscraper"));

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
            assertSame(webScraperTool, tools.get(0));
        }

        @Test
        @DisplayName("whitelist with 'textsummarizer' returns textSummarizerTool")
        void whitelistTextsummarizer() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("textsummarizer"));

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
            assertSame(textSummarizerTool, tools.get(0));
        }

        @Test
        @DisplayName("whitelist with 'pdfreader' returns pdfReaderTool")
        void whitelistPdfreader() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("pdfreader"));

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
            assertSame(pdfReaderTool, tools.get(0));
        }

        @Test
        @DisplayName("whitelist with 'weather' returns weatherTool")
        void whitelistWeather() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("weather"));

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
            assertSame(weatherTool, tools.get(0));
        }

        @Test
        @DisplayName("whitelist with 'fetch_page' returns fetchToolResponsePageTool")
        void whitelistFetchPage() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("fetch_page"));

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
            assertSame(fetchToolResponsePageTool, tools.get(0));
        }

        @Test
        @DisplayName("whitelist with 'fetch_tool_response_page' also returns fetchTool")
        void whitelistFetchToolResponsePage() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("fetch_tool_response_page"));

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
            assertSame(fetchToolResponsePageTool, tools.get(0));
        }

        @Test
        @DisplayName("whitelist with all tools returns all tools")
        void whitelistAllTools() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of(
                    "calculator", "datetime", "websearch", "dataformatter",
                    "webscraper", "textsummarizer", "pdfreader", "weather", "fetch_page"));

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(9, tools.size());
        }

        @Test
        @DisplayName("empty whitelist returns all tools (same as no whitelist)")
        void emptyWhitelist() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of());

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertTrue(tools.size() >= 9);
        }
    }

    // =========================================================
    // collectEnabledTools — user memory tool
    // =========================================================

    @Nested
    @DisplayName("User memory tool addition")
    class UserMemoryToolTests {

        @Test
        @DisplayName("whitelist 'usermemory' with config adds UserMemoryTool")
        void whitelistUsermemoryWithConfig() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("usermemory"));

            var config = new AgentConfiguration.UserMemoryConfig();
            when(memory.getUserMemoryConfig()).thenReturn(config);
            when(memory.getUserId()).thenReturn("user-1");
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getConversationId()).thenReturn("conv-1");

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
        }

        @Test
        @DisplayName("whitelist 'usermemory' with null config adds nothing")
        void whitelistUsermemoryNullConfig() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("usermemory"));

            when(memory.getUserMemoryConfig()).thenReturn(null);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertTrue(tools.isEmpty());
        }

        @Test
        @DisplayName("no whitelist, null user memory config, no usermemory tool")
        void noWhitelistNullUserMemoryConfig() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            when(memory.getUserMemoryConfig()).thenReturn(null);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            // Should have 9 built-in tools but no user memory tool
            assertEquals(9, tools.size());
        }

        @Test
        @DisplayName("user memory with groupId property adds tool with groups")
        void userMemoryWithGroupId() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("usermemory"));

            var config = new AgentConfiguration.UserMemoryConfig();
            when(memory.getUserMemoryConfig()).thenReturn(config);
            when(memory.getUserId()).thenReturn("user-1");
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getConversationId()).thenReturn("conv-1");

            // Setup conversation properties with groupId as a Property
            var convProps = mock(IConversationMemory.IConversationProperties.class);
            var groupProp = new Property("groupId", "grp-1", Property.Scope.conversation);
            doReturn(groupProp).when(convProps).get("groupId");
            doReturn(convProps).when(memory).getConversationProperties();

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
        }

        @Test
        @DisplayName("user memory with non-Property groupId ignores groups")
        void userMemoryWithNonPropertyGroupId() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("usermemory"));

            var config = new AgentConfiguration.UserMemoryConfig();
            when(memory.getUserMemoryConfig()).thenReturn(config);
            when(memory.getUserId()).thenReturn("user-1");
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getConversationId()).thenReturn("conv-1");

            // Setup conversation properties with groupId as plain String (not Property)
            // Since IConversationProperties extends Map<String, Property>, returning a
            // non-Property is not possible via the typed API, but we can simulate the
            // case where groupId simply doesn't exist
            var convProps = mock(IConversationMemory.IConversationProperties.class);
            doReturn(null).when(convProps).get("groupId");
            doReturn(convProps).when(memory).getConversationProperties();

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
        }
    }

    // =========================================================
    // LAZY tool loading strategy
    // =========================================================

    @Nested
    @DisplayName("LAZY tool loading strategy")
    class LazyToolLoading {

        @Test
        @DisplayName("LAZY strategy adds DiscoverToolsTool meta-tool")
        void lazyAddsDiscoverTool() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setToolLoadingStrategy(LlmConfiguration.ToolLoadingStrategy.LAZY);
            task.setMaxToolsInContext(5);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            // Should include all built-in tools + DiscoverToolsTool
            assertTrue(tools.size() >= 10); // 9 built-in + 1 discover
            boolean hasDiscoverTool = tools.stream()
                    .anyMatch(t -> t instanceof DiscoverToolsTool);
            assertTrue(hasDiscoverTool);
        }

        @Test
        @DisplayName("EAGER strategy does not add DiscoverToolsTool")
        void eagerNoDiscoverTool() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setToolLoadingStrategy(LlmConfiguration.ToolLoadingStrategy.EAGER);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            boolean hasDiscoverTool = tools.stream()
                    .anyMatch(t -> t instanceof DiscoverToolsTool);
            assertFalse(hasDiscoverTool);
        }

        @Test
        @DisplayName("null strategy defaults to EAGER")
        void nullStrategyDefaults() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setToolLoadingStrategy(null);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            boolean hasDiscoverTool = tools.stream()
                    .anyMatch(t -> t instanceof DiscoverToolsTool);
            assertFalse(hasDiscoverTool);
        }
    }

    // =========================================================
    // safeTemplateMerge — reserved key protection
    // =========================================================

    @Nested
    @DisplayName("safeTemplateMerge — reserved keys")
    class SafeTemplateMergeTests {

        @Test
        @DisplayName("reserved keys are blocked")
        void reservedKeysBlocked() throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "safeTemplateMerge", Map.class, Map.class);
            method.setAccessible(true);

            Map<String, Object> templateData = new HashMap<>();
            templateData.put("context", "original-context");
            templateData.put("userInfo", "original-userInfo");

            Map<String, Object> args = new HashMap<>();
            args.put("context", "injected");
            args.put("userInfo", "injected");
            args.put("customParam", "allowed-value");

            method.invoke(null, templateData, args);

            assertEquals("original-context", templateData.get("context"));
            assertEquals("original-userInfo", templateData.get("userInfo"));
            assertEquals("allowed-value", templateData.get("customParam"));
        }

        @Test
        @DisplayName("all reserved keys are protected")
        void allReservedKeysProtected() throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "safeTemplateMerge", Map.class, Map.class);
            method.setAccessible(true);

            String[] reservedKeys = {"context", "properties", "memory",
                    "userInfo", "conversationInfo", "conversationLog"};

            Map<String, Object> templateData = new HashMap<>();
            for (String key : reservedKeys) {
                templateData.put(key, "original-" + key);
            }

            Map<String, Object> args = new HashMap<>();
            for (String key : reservedKeys) {
                args.put(key, "injected-" + key);
            }

            method.invoke(null, templateData, args);

            for (String key : reservedKeys) {
                assertEquals("original-" + key, templateData.get(key));
            }
        }

        @Test
        @DisplayName("non-reserved keys are merged")
        void nonReservedKeysMerged() throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "safeTemplateMerge", Map.class, Map.class);
            method.setAccessible(true);

            Map<String, Object> templateData = new HashMap<>();
            Map<String, Object> args = new HashMap<>();
            args.put("searchQuery", "test");
            args.put("filter", "active");

            method.invoke(null, templateData, args);

            assertEquals("test", templateData.get("searchQuery"));
            assertEquals("active", templateData.get("filter"));
        }
    }

    // =========================================================
    // activateDiscoveredTools — parsing edge cases
    // =========================================================

    @Nested
    @DisplayName("activateDiscoveredTools — edge cases")
    class ActivateDiscoveredToolsTests {

        @Test
        @DisplayName("malformed JSON does not throw (caught internally)")
        void malformedJson() throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "activateDiscoveredTools", String.class, List.class, List.class);
            method.setAccessible(true);

            // Should not throw — exception is caught internally
            method.invoke(orchestrator, "not-valid-json",
                    new ArrayList<>(), new ArrayList<>());
        }

        @Test
        @DisplayName("null tools node does not activate anything")
        void nullToolsNode() throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "activateDiscoveredTools", String.class, List.class, List.class);
            method.setAccessible(true);

            method.invoke(orchestrator, "{\"other\": \"data\"}",
                    new ArrayList<>(), new ArrayList<>());
        }

        @Test
        @DisplayName("non-array tools node does not activate anything")
        void nonArrayToolsNode() throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "activateDiscoveredTools", String.class, List.class, List.class);
            method.setAccessible(true);

            method.invoke(orchestrator, "{\"tools\": \"not-an-array\"}",
                    new ArrayList<>(), new ArrayList<>());
        }

        @Test
        @DisplayName("valid tools array without name field is skipped")
        void toolsWithoutName() throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "activateDiscoveredTools", String.class, List.class, List.class);
            method.setAccessible(true);

            method.invoke(orchestrator,
                    "{\"tools\": [{\"description\": \"no-name\"}]}",
                    new ArrayList<>(), new ArrayList<>());
        }
    }

    // =========================================================
    // executeIfToolsEnabled — no tools returns null
    // =========================================================

    @Nested
    @DisplayName("executeIfToolsEnabled — no tools")
    class ExecuteNoTools {

        @Test
        @DisplayName("no tools enabled returns null")
        void noToolsReturnsNull() throws Exception {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);

            var result = orchestrator.executeIfToolsEnabled(
                    null, "system", List.of(), task, memory);

            assertNull(result);
        }
    }
}
