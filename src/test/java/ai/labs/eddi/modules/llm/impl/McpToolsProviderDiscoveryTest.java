/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.spi.ProviderFailure;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolRequestResolver;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link McpToolsProvider#discover}, which {@code McpToolsProviderTest}
 * left to "indirect" coverage that measurement showed did not exist: these
 * paths sat at 31% instruction coverage, including the tool-name collision
 * defence the class documents at length.
 * <p>
 * The indirect suites stop earlier than they look. {@code
 * AgentOrchestratorExtendedTest} and {@code AgentOrchestratorCoverage2Test}
 * drive discovery with a mocked memory whose {@code getAgentVersion()} is null,
 * so {@code WorkflowTraversal} returns empty before the per-server loop is
 * entered, and the {@code McpToolProviderManager*Test} suites exercise the
 * <em>manager</em> - the filtering, collision and bridge logic tested here
 * lives in the provider.
 * <p>
 * <b>Every test uses a distinct agent id.</b> {@code WorkflowTraversal}
 * memoizes a completed traversal for two seconds, keyed on
 * {@code agentId|version|stepType|configClass}, in a {@code static} map - so
 * sharing an id across tests in one JVM makes a later test silently reuse an
 * earlier one's workflow.
 */
class McpToolsProviderDiscoveryTest {

    private static final String MCPCALLS_TYPE = "eddi://ai.labs.mcpcalls";

    /** Distinct per test - see the class comment on the static traversal cache. */
    private static final AtomicInteger AGENT_SEQ = new AtomicInteger();

    private IConversationMemory memory;
    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IResourceClientLibrary resourceClientLibrary;
    private McpToolProviderManager manager;
    private String agentId;

    @BeforeEach
    void setUp() {
        memory = mock(IConversationMemory.class);
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        manager = mock(McpToolProviderManager.class);
        agentId = "mcp-agent-" + AGENT_SEQ.incrementAndGet();
    }

    private McpToolsProvider provider() {
        return new McpToolsProvider(agentStore, workflowStore, resourceClientLibrary, manager);
    }

    private void wireAgentWithSteps(List<WorkflowStep> steps) throws Exception {
        when(memory.getAgentId()).thenReturn(agentId);
        when(memory.getAgentVersion()).thenReturn(1);

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(
                URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-" + agentId + "?version=1")));
        when(agentStore.read(agentId, 1)).thenReturn(agentConfig);

        var wfConfig = new WorkflowConfiguration();
        wfConfig.setWorkflowSteps(steps);
        when(workflowStore.read("wf-" + agentId, 1)).thenReturn(wfConfig);
    }

    private static WorkflowStep mcpStep(String configId) {
        var step = new WorkflowStep();
        step.setType(URI.create(MCPCALLS_TYPE));
        step.setConfig(Map.of("uri", "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/" + configId + "?version=1"));
        return step;
    }

    /**
     * Wires memory to agent to workflow to one mcpcalls step carrying
     * {@code config}.
     */
    private void wireWorkflowWith(McpCallsConfiguration config) throws Exception {
        wireAgentWithSteps(List.of(mcpStep("mc-1")));
        when(resourceClientLibrary.getResource(any(URI.class), any())).thenReturn(config);
    }

    private static McpCallsConfiguration mcpConfig(String name, String url) {
        var c = new McpCallsConfiguration();
        c.setName(name);
        c.setMcpServerUrl(url);
        c.setTransport("http");
        return c;
    }

    private static ToolSpecification spec(String name) {
        return ToolSpecification.builder().name(name).description("d-" + name).build();
    }

    private static ToolExecutor executor() {
        return (request, memoryId) -> "ok";
    }

    private static List<String> names(List<ToolSpecification> specs) {
        return specs.stream().map(ToolSpecification::name).toList();
    }

    // ==================== whitelist / blacklist ====================

    @Test
    @DisplayName("whitelist keeps only the named tools")
    void whitelistFiltersOutEverythingElse() throws Exception {
        var config = mcpConfig("srv", "http://mcp.example/sse");
        config.setToolsWhitelist(List.of("keep"));
        wireWorkflowWith(config);

        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(spec("keep"), spec("drop")),
                Map.of("keep", executor(), "drop", executor())));

        var result = provider().discover(memory);

        assertEquals(List.of("keep"), names(result.toolSpecs()));
        assertTrue(result.executors().containsKey("keep"));
        assertFalse(result.executors().containsKey("drop"));
    }

    @Test
    @DisplayName("blacklist removes a tool the server still advertises")
    void blacklistRemovesNamedTool() throws Exception {
        var config = mcpConfig("srv", "http://mcp.example/sse");
        config.setToolsBlacklist(List.of("dangerous"));
        wireWorkflowWith(config);

        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(spec("safe"), spec("dangerous")),
                Map.of("safe", executor(), "dangerous", executor())));

        var result = provider().discover(memory);

        assertEquals(List.of("safe"), names(result.toolSpecs()));
        assertFalse(result.executors().containsKey("dangerous"),
                "a blacklisted tool must not reach the model - this is an operator security control");
    }

    @Test
    @DisplayName("an empty whitelist is not treated as allow-nothing")
    void emptyWhitelistKeepsEverything() throws Exception {
        var config = mcpConfig("srv", "http://mcp.example/sse");
        config.setToolsWhitelist(List.of());
        wireWorkflowWith(config);

        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(spec("a")), Map.of("a", executor())));

        assertEquals(1, provider().discover(memory).toolSpecs().size());
    }

    // ==================== executor pairing / collisions ====================

    @Test
    @DisplayName("a spec with no executor is skipped rather than shipped unpaired")
    void specWithoutExecutorIsSkipped() throws Exception {
        wireWorkflowWith(mcpConfig("srv", "http://mcp.example/sse"));

        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(spec("orphan")), Map.of()));

        var result = provider().discover(memory);

        assertTrue(result.toolSpecs().isEmpty());
        assertTrue(result.executors().isEmpty());
    }

    @Test
    @DisplayName("tool-name collision keeps the first server's spec AND its executor")
    void collisionKeepsFirstSpecAndItsExecutor() throws Exception {
        wireAgentWithSteps(List.of(mcpStep("a"), mcpStep("b")));

        when(resourceClientLibrary.getResource(any(URI.class), any())).thenReturn(
                mcpConfig("first", "http://first.example/sse"),
                mcpConfig("second", "http://second.example/sse"));

        ToolSpecification firstSpec = spec("shared");
        ToolExecutor firstExecutor = executor();
        ToolExecutor secondExecutor = executor();
        when(manager.discoverTools(anyList()))
                .thenReturn(new McpToolProviderManager.McpToolsResult(
                        List.of(firstSpec), Map.of("shared", firstExecutor)))
                .thenReturn(new McpToolProviderManager.McpToolsResult(
                        List.of(spec("shared")), Map.of("shared", secondExecutor)));

        var result = provider().discover(memory);

        // One entry only, and crucially the spec and the executor come from the SAME
        // server. The bug this guards against paired the first server's spec with the
        // last server's executor: the model is shown one signature while a different
        // server's tool runs.
        assertEquals(1, result.toolSpecs().size());
        assertSame(firstSpec, result.toolSpecs().get(0));
        assertSame(firstExecutor, result.executors().get("shared"));
    }

    // ==================== resource bridge ====================

    @Test
    @DisplayName("resource bridge tools are added when exposeResources is true")
    void resourceBridgeIsOptIn() throws Exception {
        var config = mcpConfig("srv", "http://mcp.example/sse");
        config.setExposeResources(Boolean.TRUE);
        wireWorkflowWith(config);

        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(spec("normal")), Map.of("normal", executor())));
        when(manager.resourceBridgeTools(any())).thenReturn(new McpToolProviderManager.McpResourceBridge(
                List.of(spec("srv_list_resources")), Map.of("srv_list_resources", executor())));

        var discovered = names(provider().discover(memory).toolSpecs());

        assertTrue(discovered.contains("srv_list_resources"));
        assertTrue(discovered.contains("normal"));
    }

    @Test
    @DisplayName("resource bridge is skipped when exposeResources is absent")
    void resourceBridgeSkippedWhenNotOptedIn() throws Exception {
        wireWorkflowWith(mcpConfig("srv", "http://mcp.example/sse"));

        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(spec("normal")), Map.of("normal", executor())));

        assertEquals(List.of("normal"), names(provider().discover(memory).toolSpecs()));
    }

    @Test
    @DisplayName("resource-bridge tools bypass the whitelist (EDDI synthesizes them)")
    void resourceBridgeIgnoresWhitelist() throws Exception {
        var config = mcpConfig("srv", "http://mcp.example/sse");
        config.setExposeResources(Boolean.TRUE);
        config.setToolsWhitelist(List.of("nothing-matches"));
        wireWorkflowWith(config);

        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(spec("normal")), Map.of("normal", executor())));
        when(manager.resourceBridgeTools(any())).thenReturn(new McpToolProviderManager.McpResourceBridge(
                List.of(spec("srv_read_resource")), Map.of("srv_read_resource", executor())));

        assertEquals(List.of("srv_read_resource"), names(provider().discover(memory).toolSpecs()));
    }

    @Test
    @DisplayName("a rejected bridge config becomes an INVALID_CONFIGURATION failure, not a thrown exception")
    void resourceBridgeRejectionIsReportedAsFailure() throws Exception {
        var config = mcpConfig("srv", "http://mcp.example/sse");
        config.setExposeResources(Boolean.TRUE);
        wireWorkflowWith(config);

        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(spec("normal")), Map.of("normal", executor())));
        when(manager.resourceBridgeTools(any())).thenThrow(new IllegalArgumentException("bad bridge"));

        var result = provider().discover(memory);

        // The server's own tools survive a bad bridge config.
        assertEquals(List.of("normal"), names(result.toolSpecs()));
        assertEquals(1, result.failures().size());
        assertEquals(McpToolProviderManager.McpFailureKind.INVALID_CONFIGURATION, result.failures().get(0).kind());
        assertEquals("srv", result.failures().get(0).serverName());
    }

    // ==================== failures / resolvers ====================

    @Test
    @DisplayName("per-server failures are carried out of discovery")
    void serverFailuresArePropagated() throws Exception {
        wireWorkflowWith(mcpConfig("srv", "http://mcp.example/sse"));

        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(), Map.of(),
                List.of(new McpToolProviderManager.McpServerFailure("srv", "http://mcp.example/sse",
                        McpToolProviderManager.McpFailureKind.CONNECTION_FAILURE, "refused"))));

        var result = provider().discover(memory);

        assertEquals(1, result.failures().size());
        assertEquals(McpToolProviderManager.McpFailureKind.CONNECTION_FAILURE, result.failures().get(0).kind());
    }

    @Test
    @DisplayName("contribute maps every failure kind onto ProviderFailure")
    void contributeMapsAllFailureKinds() throws Exception {
        wireWorkflowWith(mcpConfig("srv", "http://mcp.example/sse"));

        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(), Map.of(),
                List.of(
                        new McpToolProviderManager.McpServerFailure("s1", "u",
                                McpToolProviderManager.McpFailureKind.INVALID_CONFIGURATION, "m1"),
                        new McpToolProviderManager.McpServerFailure("s2", "u",
                                McpToolProviderManager.McpFailureKind.CONNECTION_FAILURE, "m2"),
                        new McpToolProviderManager.McpServerFailure("s3", "u",
                                McpToolProviderManager.McpFailureKind.CIRCUIT_OPEN, "m3"),
                        new McpToolProviderManager.McpServerFailure("s4", "u",
                                McpToolProviderManager.McpFailureKind.AUTHENTICATION_REQUIRED, "m4"))));

        var task = new LlmConfiguration.Task();
        var ctx = new ToolAssemblyContext(memory, task, null, null, "user-1", agentId, null);

        var contribution = provider().contribute(ctx);

        assertEquals(List.of(
                ProviderFailure.Kind.INVALID_CONFIGURATION,
                ProviderFailure.Kind.CONNECTION_FAILURE,
                ProviderFailure.Kind.CIRCUIT_OPEN,
                ProviderFailure.Kind.AUTHENTICATION_REQUIRED),
                contribution.failures().stream().map(ProviderFailure::kind).toList());
        assertTrue(contribution.failures().stream().allMatch(f -> "mcp".equals(f.source())));
    }

    @Test
    @DisplayName("a resolver is pinned for every surviving tool, and only those")
    void resolversArePinnedForSurvivingTools() throws Exception {
        var config = mcpConfig("srv", "http://mcp.example/sse");
        config.setToolsBlacklist(List.of("dropped"));
        wireWorkflowWith(config);

        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(spec("kept"), spec("dropped")),
                Map.of("kept", executor(), "dropped", executor())));

        var resolvers = new HashMap<String, ToolRequestResolver>();
        provider().discover(memory, resolvers);

        assertEquals(Set.of("kept"), resolvers.keySet());
    }

    @Test
    @DisplayName("a store failure degrades to an empty result instead of propagating")
    void storeFailureIsSwallowed() throws Exception {
        when(memory.getAgentId()).thenReturn(agentId);
        when(memory.getAgentVersion()).thenReturn(1);
        when(agentStore.read(agentId, 1)).thenThrow(new RuntimeException("store down"));

        var result = provider().discover(memory);

        assertTrue(result.toolSpecs().isEmpty());
        assertTrue(result.failures().isEmpty());
    }
}
