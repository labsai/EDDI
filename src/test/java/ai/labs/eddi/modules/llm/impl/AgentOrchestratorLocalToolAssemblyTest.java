/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.CalculatorTool;
import ai.labs.eddi.modules.llm.tools.impl.DataFormatterTool;
import ai.labs.eddi.modules.llm.tools.impl.DateTimeTool;
import ai.labs.eddi.modules.llm.tools.impl.FetchToolResponsePageTool;
import ai.labs.eddi.modules.llm.tools.impl.PdfReaderTool;
import ai.labs.eddi.modules.llm.tools.impl.TextSummarizerTool;
import ai.labs.eddi.modules.llm.tools.impl.WeatherTool;
import ai.labs.eddi.modules.llm.tools.impl.WebScraperTool;
import ai.labs.eddi.modules.llm.tools.impl.WebSearchTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pins that {@code collectEnabledTools} and the provider assembly inside
 * {@code buildToolSetup} agree, tool for tool.
 * <p>
 * The R2 rewiring left {@code collectEnabledTools} with no production caller —
 * {@code buildToolSetup} assembles the same sources through
 * {@code ToolSourceRegistry} instead. The method survives only because several
 * characterization tests call it directly, and a test-only lookalike of a live
 * code path is a liability: it keeps the suite green while production drifts
 * away from it. Both paths route through the same provider instances, and this
 * test is what proves it stays that way.
 * <p>
 * Compares by <em>spec name</em> rather than by object identity because the two
 * paths legitimately differ in shape — one yields tool beans, the other the
 * specs reflected from them.
 *
 * @author tests
 */
class AgentOrchestratorLocalToolAssemblyTest {

    private final CalculatorTool calculator = new CalculatorTool();
    private final DateTimeTool dateTime = new DateTimeTool();
    private final WebSearchTool webSearch = mock(WebSearchTool.class);
    private final DataFormatterTool dataFormatter = new DataFormatterTool();
    private final WebScraperTool webScraper = mock(WebScraperTool.class);
    private final TextSummarizerTool textSummarizer = mock(TextSummarizerTool.class);
    private final PdfReaderTool pdfReader = mock(PdfReaderTool.class);
    private final WeatherTool weather = mock(WeatherTool.class);
    private final FetchToolResponsePageTool fetchPage = mock(FetchToolResponsePageTool.class);

    private AgentOrchestrator orchestrator() {
        return new AgentOrchestrator(calculator, dateTime, webSearch, dataFormatter, webScraper, textSummarizer,
                pdfReader, weather, fetchPage,
                mock(ToolExecutionService.class), mock(McpToolProviderManager.class), mock(A2AToolProviderManager.class),
                mock(IWorkflowStore.class), mock(IResourceClientLibrary.class),
                mock(IApiCallExecutor.class), mock(IJsonSerialization.class), mock(IMemoryItemConverter.class),
                mock(IUserMemoryStore.class), mock(ToolResponseTruncator.class), mock(TenantQuotaService.class),
                mock(MemorySnapshotService.class), mock(AgentSetupService.class), mock(CapabilityRegistryService.class),
                mock(IConversationService.class), mock(IAgentFactory.class), mock(IAgentStore.class),
                mock(IHitlToolJournalStore.class), mock(ConversationHistoryBuilder.class),
                mock(TokenCounterFactory.class));
    }

    private IConversationMemory memory() {
        var memory = mock(IConversationMemory.class);
        // No current step: keeps the attachment and dynamic-agent sources quiet, so
        // the comparison isolates the paths themselves rather than their inputs.
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
        when(memory.getAgentId()).thenReturn("agent-1");
        when(memory.getUserId()).thenReturn("user-1");
        return memory;
    }

    private LlmConfiguration.Task task(Boolean enableBuiltInTools, List<String> whitelist) {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(enableBuiltInTools);
        task.setBuiltInToolsWhitelist(whitelist);
        // The external sources are irrelevant here and would need network doubles.
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        return task;
    }

    /** Spec names of a list of tool objects, in order. */
    private static List<String> specNamesOf(List<Object> tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        for (Object tool : tools) {
            specs.addAll(ToolSpecifications.toolSpecificationsFrom(ToolObjectReflector.unwrapProxy(tool.getClass())));
        }
        return specs.stream().map(ToolSpecification::name).toList();
    }

    private void assertPathsAgree(Boolean enableBuiltInTools, List<String> whitelist) {
        var orchestrator = orchestrator();
        var memory = memory();
        var task = task(enableBuiltInTools, whitelist);

        List<String> viaObjects = specNamesOf(orchestrator.collectEnabledTools(task, memory));
        List<String> viaProviders = orchestrator.buildToolSetup(task, memory).builtInSpecs().stream()
                .map(ToolSpecification::name).toList();

        assertEquals(viaObjects, viaProviders,
                "collectEnabledTools and the provider assembly must stay in lockstep — same tools, same order");
    }

    @Test
    void agree_whenBuiltInToolsAreDisabled() {
        assertPathsAgree(false, null);
    }

    @Test
    void agree_whenTheEnableFlagIsNull() {
        assertPathsAgree(null, null);
    }

    @Test
    void agree_withNoWhitelist() {
        assertPathsAgree(true, null);
    }

    @Test
    void agree_withAnEmptyWhitelist() {
        assertPathsAgree(true, List.of());
    }

    @Test
    void agree_withAPartialWhitelist() {
        assertPathsAgree(true, List.of("calculator", "weather", "datetime"));
    }

    @Test
    void agree_withABothAliasWhitelist() {
        assertPathsAgree(true, List.of("fetch_page", "fetch_tool_response_page"));
    }

    @Test
    void agree_withAWhitelistNamingNothingReal() {
        assertPathsAgree(true, List.of("no_such_tool"));
    }

    /**
     * The regression this test exists for: if the provider path lost a source that
     * {@code collectEnabledTools} still adds, the assembled set would silently
     * shrink. Assert the no-whitelist case is actually non-empty so an "empty ==
     * empty" pass cannot masquerade as agreement.
     */
    @Test
    void theNoWhitelistCaseIsNonEmpty_soAgreementIsNotVacuous() {
        var orchestrator = orchestrator();
        var setup = orchestrator.buildToolSetup(task(true, null), memory());

        assertFalse(setup.builtInSpecs().isEmpty());
    }

    /**
     * A memory that actually enables the contextual and dynamic-agent sources.
     * <p>
     * The shared {@link #memory()} deliberately keeps them quiet so the old-vs-new
     * comparison isolates the paths — but the consequence was that every test
     * compared only the nine plain built-in beans, and deleting
     * {@code contextualToolsProvider()} and {@code dynamicAgentToolsProvider()}
     * from production assembly passed all of them. Verified by mutation.
     */
    private IConversationMemory memoryWithUserMemory() {
        var memory = mock(IConversationMemory.class);
        var step = mock(IWritableConversationStep.class);
        lenient().when(memory.getCurrentStep()).thenReturn(step);
        lenient().when(memory.getAgentId()).thenReturn("agent-1");
        lenient().when(memory.getUserId()).thenReturn("user-1");
        lenient().when(memory.getConversationId()).thenReturn("conv-1");
        return memory;
    }

    @Test
    void assemblyIncludesEveryLocalSource_notJustThePlainBuiltIns() {
        // Pins the PROVIDER SET, not just the bean list. The whitelist names one
        // tool from the dynamic-agent source, which no other test in this class
        // does — so a provider dropped from buildToolSetup's phase-1 list fails
        // here instead of passing silently.
        var whitelist = List.of("calculator", "converse_with_agent");

        var specs = orchestrator().buildToolSetup(task(true, whitelist), memoryWithUserMemory()).builtInSpecs();
        var names = specs.stream().map(ToolSpecification::name).toList();

        // The DISPATCH name, not the canonical slug: the whitelist is keyed by slug
        // ("calculator") but the assembled spec carries the @Tool method name.
        assertTrue(names.contains("calculate"), "BuiltinToolsProvider dropped: " + names);
        assertTrue(names.contains("converseWithAgent"), "DynamicAgentToolsProvider dropped from assembly: " + names);
    }

    @Test
    void lazyStrategyWithBuiltInsDisabled_assemblesNothing() {
        // Fidelity with the pre-R2 path: collectEnabledTools returned BEFORE its
        // LAZY branch when enableBuiltInTools was null/false. Without that gate an
        // agent with built-ins off, LAZY, and no http/mcp/a2a tools goes from an
        // empty toolSpecs — which makes buildToolList return null and the turn fall
        // back to legacy non-tool completion — to a single discover_tools spec,
        // entering the full tool loop to be offered a meta-tool that can activate
        // nothing. Different request shape, different cost.
        var task = task(false, null);
        task.setToolLoadingStrategy(LlmConfiguration.ToolLoadingStrategy.LAZY);

        var setup = orchestrator().buildToolSetup(task, memory());

        assertTrue(setup.toolSpecs().isEmpty(),
                "an agent with built-ins disabled must not be handed discover_tools: " + setup.toolSpecs());
    }

    @Test
    void lazyStrategyWithBuiltInsEnabled_stillOffersDiscoverTools() {
        // The positive case, so the gate above cannot pass by LAZY never
        // contributing at all.
        var task = task(true, List.of("calculator"));
        task.setToolLoadingStrategy(LlmConfiguration.ToolLoadingStrategy.LAZY);

        var names = orchestrator().buildToolSetup(task, memory()).toolSpecs().stream()
                .map(ToolSpecification::name).toList();

        assertTrue(names.contains("discover_tools"), "LAZY must still advertise the meta-tool: " + names);
    }
}
