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
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolSpecification;
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
 * Extended branch coverage for AgentOrchestrator — covers conversation recall
 * tool, user memory with null valueString, LAZY mode CDI proxy resolution,
 * activateDiscoveredTools with matching specs, and executeIfToolsEnabled with
 * null enableHttpCallTools/enableMcpCallTools.
 */
@DisplayName("AgentOrchestrator — Extended Branch Coverage")
class AgentOrchestratorExtendedBranchTest {

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
                null, null, null, null, null,
                mock(ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.class), new ConversationHistoryBuilder());
    }

    // =========================================================
    // conversationRecall tool branches
    // =========================================================

    @Nested
    @DisplayName("addConversationRecallToolIfEnabled branches")
    class ConversationRecallTests {

        @Test
        @DisplayName("whitelist 'conversationRecall' with null summary config returns no tool")
        void whitelistConversationRecallNullSummaryConfig() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("conversationRecall"));
            task.setConversationSummary(null);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertTrue(tools.isEmpty());
        }

        @Test
        @DisplayName("whitelist 'conversationRecall' with disabled summary returns no tool")
        void whitelistConversationRecallDisabledSummary() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("conversationRecall"));
            var summaryConfig = new LlmConfiguration.ConversationSummaryConfig();
            summaryConfig.setEnabled(false);
            task.setConversationSummary(summaryConfig);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertTrue(tools.isEmpty());
        }

        @Test
        @DisplayName("no whitelist with null summary config does not add recall tool")
        void noWhitelistNullSummaryConfig() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setConversationSummary(null);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            // Should have 9 built-in tools but no conversation recall tool
            assertEquals(9, tools.size());
        }
    }

    // =========================================================
    // User memory with null valueString on Property
    // =========================================================

    @Nested
    @DisplayName("User memory tool — null Property valueString")
    class UserMemoryNullValueString {

        @Test
        @DisplayName("groupId Property with null valueString uses empty groups")
        void groupIdPropertyNullValueString() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("usermemory"));

            var config = new AgentConfiguration.UserMemoryConfig();
            when(memory.getUserMemoryConfig()).thenReturn(config);
            when(memory.getUserId()).thenReturn("user-1");
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getConversationId()).thenReturn("conv-1");

            var convProps = mock(IConversationMemory.IConversationProperties.class);
            // Property with null valueString
            var groupProp = new Property("groupId", (String) null, Property.Scope.conversation);
            doReturn(groupProp).when(convProps).get("groupId");
            doReturn(convProps).when(memory).getConversationProperties();

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
        }

        @Test
        @DisplayName("null conversation properties still adds user memory tool")
        void nullConversationProperties() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("usermemory"));

            var config = new AgentConfiguration.UserMemoryConfig();
            when(memory.getUserMemoryConfig()).thenReturn(config);
            when(memory.getUserId()).thenReturn("user-1");
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getConversationId()).thenReturn("conv-1");
            when(memory.getConversationProperties()).thenReturn(null);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);
            assertEquals(1, tools.size());
        }
    }

    // =========================================================
    // Orchestrator without userMemoryStore (null)
    // =========================================================

    @Nested
    @DisplayName("Null userMemoryStore skips user memory tool")
    class NullUserMemoryStore {

        @Test
        @DisplayName("null userMemoryStore does not add UserMemoryTool")
        void nullStoreSkipsTool() {
            var orchestratorNoStore = new AgentOrchestrator(
                    calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                    webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                    fetchToolResponsePageTool, toolExecutionService,
                    mcpToolProviderManager, a2aToolProviderManager,
                    restAgentStore, restWorkflowStore, resourceClientLibrary,
                    apiCallExecutor, jsonSerialization, memoryItemConverter,
                    null, // null userMemoryStore
                    toolResponseTruncator, tenantQuotaService, memorySnapshotService,
                    null, null, null, null, null,
                    mock(ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.class), new ConversationHistoryBuilder());

            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("usermemory"));

            var config = new AgentConfiguration.UserMemoryConfig();
            when(memory.getUserMemoryConfig()).thenReturn(config);

            List<Object> tools = orchestratorNoStore.collectEnabledTools(task, memory);
            assertTrue(tools.isEmpty());
        }
    }

    // =========================================================
    // executeIfToolsEnabled — null enableHttpCallTools/enableMcpCallTools
    // =========================================================

    @Nested
    @DisplayName("executeIfToolsEnabled — null enable* defaults to true")
    class ExecuteNullEnableDefaults {

        @Test
        @DisplayName("null enableHttpCallTools defaults to true (enabled)")
        void nullEnableHttpCallToolsDefaultsTrue() throws Exception {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(null); // null → default true
            task.setEnableMcpCallTools(false);

            // No tools → returns null since httpcall discover returns empty
            var result = orchestrator.executeIfToolsEnabled(
                    null, "system", List.of(), task, memory);
            assertNull(result);
        }

        @Test
        @DisplayName("null enableMcpCallTools defaults to true (enabled)")
        void nullEnableMcpCallToolsDefaultsTrue() throws Exception {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(null); // null → default true

            var result = orchestrator.executeIfToolsEnabled(
                    null, "system", List.of(), task, memory);
            assertNull(result);
        }
    }

    // =========================================================
    // activateDiscoveredTools — actual activation
    // =========================================================

    @Nested
    @DisplayName("activateDiscoveredTools — spec activation")
    class ActivateToolsTests {

        @Test
        @DisplayName("valid discovery result activates matching built-in specs")
        void validDiscoveryActivatesSpecs() throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "activateDiscoveredTools", String.class, List.class, List.class);
            method.setAccessible(true);

            // Build built-in specs
            ToolSpecification calcSpec = ToolSpecification.builder()
                    .name("calculate").description("Calculator").build();
            ToolSpecification weatherSpec = ToolSpecification.builder()
                    .name("get_weather").description("Weather").build();
            List<ToolSpecification> builtInSpecs = new ArrayList<>(List.of(calcSpec, weatherSpec));

            // discover_tools already active
            ToolSpecification discoverSpec = ToolSpecification.builder()
                    .name("discover_tools").description("Discover").build();
            List<ToolSpecification> activeSpecs = new ArrayList<>(List.of(discoverSpec));

            // Discovery result requests "calculate" tool
            String discoverResult = "{\"tools\": [{\"name\": \"calculate\"}]}";

            method.invoke(orchestrator, discoverResult, builtInSpecs, activeSpecs);

            // activeSpecs should now contain discover_tools + calculate
            assertEquals(2, activeSpecs.size());
            assertTrue(activeSpecs.stream().anyMatch(s -> "calculate".equals(s.name())));
        }

        @Test
        @DisplayName("already-active specs are not duplicated")
        void alreadyActiveNotDuplicated() throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "activateDiscoveredTools", String.class, List.class, List.class);
            method.setAccessible(true);

            ToolSpecification calcSpec = ToolSpecification.builder()
                    .name("calculate").description("Calculator").build();
            List<ToolSpecification> builtInSpecs = new ArrayList<>(List.of(calcSpec));
            // calculate is already active
            List<ToolSpecification> activeSpecs = new ArrayList<>(List.of(calcSpec));

            String discoverResult = "{\"tools\": [{\"name\": \"calculate\"}]}";

            method.invoke(orchestrator, discoverResult, builtInSpecs, activeSpecs);

            // Should still be just 1 — not duplicated
            assertEquals(1, activeSpecs.size());
        }

        @Test
        @DisplayName("discovery result with no matching specs activates nothing")
        void noMatchingSpecsActivatesNothing() throws Exception {
            Method method = AgentOrchestrator.class.getDeclaredMethod(
                    "activateDiscoveredTools", String.class, List.class, List.class);
            method.setAccessible(true);

            ToolSpecification calcSpec = ToolSpecification.builder()
                    .name("calculate").description("Calculator").build();
            List<ToolSpecification> builtInSpecs = new ArrayList<>(List.of(calcSpec));
            List<ToolSpecification> activeSpecs = new ArrayList<>();

            String discoverResult = "{\"tools\": [{\"name\": \"unknown_tool\"}]}";

            method.invoke(orchestrator, discoverResult, builtInSpecs, activeSpecs);

            assertTrue(activeSpecs.isEmpty());
        }
    }

    // =========================================================
    // executeIfToolsEnabled — A2A agents branch
    // =========================================================

    @Nested
    @DisplayName("executeIfToolsEnabled — A2A agents branch")
    class A2aAgentsBranch {

        @Test
        @DisplayName("null a2aAgents list does not trigger A2A discovery")
        void nullA2aAgents() throws Exception {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);
            task.setA2aAgents(null);

            var result = orchestrator.executeIfToolsEnabled(
                    null, "system", List.of(), task, memory);
            assertNull(result);

            verify(a2aToolProviderManager, never()).discoverTools(any());
        }

        @Test
        @DisplayName("empty a2aAgents list does not trigger A2A discovery")
        void emptyA2aAgents() throws Exception {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);
            task.setA2aAgents(List.of());

            var result = orchestrator.executeIfToolsEnabled(
                    null, "system", List.of(), task, memory);
            assertNull(result);

            verify(a2aToolProviderManager, never()).discoverTools(any());
        }
    }

    // =========================================================
    // LAZY mode whitelist tool specs
    // =========================================================

    @Nested
    @DisplayName("LAZY mode with whitelist — only specified tools + discover meta-tool")
    class LazyWithWhitelist {

        @Test
        @DisplayName("LAZY with whitelist includes DiscoverToolsTool with correct allSpecs")
        void lazyWithWhitelistIncludesDiscover() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            task.setBuiltInToolsWhitelist(List.of("calculator"));
            task.setToolLoadingStrategy(LlmConfiguration.ToolLoadingStrategy.LAZY);
            task.setMaxToolsInContext(3);

            List<Object> tools = orchestrator.collectEnabledTools(task, memory);

            // calculator + DiscoverToolsTool
            assertEquals(2, tools.size());
            assertTrue(tools.stream().anyMatch(t -> t instanceof DiscoverToolsTool));
            assertSame(calculatorTool, tools.get(0));
        }
    }
}
