/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolGroup;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LazyToolCatalog — which tools LAZY keeps behind discover_tools")
class LazyToolCatalogTest {

    private static final ToolExecutor OK = (request, memoryId) -> "ok";

    private static LlmConfiguration.Task lazy(List<String> sources) {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setToolLoadingStrategy(LlmConfiguration.ToolLoadingStrategy.LAZY);
        task.setLazySources(sources);
        return task;
    }

    private static ToolSourceRegistry.Assembled assemble(boolean withHttp) {
        var merger = ToolSourceRegistry.newMerger();
        merger.addContribution("builtin", new ToolContribution(List.of(spec("calculate")), Map.of("calculate", OK),
                Map.of("calculate", "builtin"), Map.of()));
        merger.addContribution("builtin", LazyToolCatalog.placeholder());
        if (withHttp) {
            merger.addContribution("http", new ToolContribution(List.of(spec("findUsers"), spec("discover_tools")),
                    Map.of("findUsers", OK, "discover_tools", OK), Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                    Map.of("findUsers", new ToolGroup("users", "Users"))));
        }
        return merger.build();
    }

    private static ToolSpecification spec(String name) {
        return ToolSpecification.builder().name(name).description(name + " tool").build();
    }

    @Test
    @DisplayName("lazySources defaults to builtin; unknown entries are dropped; externalLazy only for http/mcp/a2a")
    void sources() {
        assertEquals(Set.of("builtin"), LazyToolCatalog.lazySources(lazy(null)));
        assertEquals(Set.of("http"), LazyToolCatalog.lazySources(lazy(List.of(" HTTP ", "ftp"))));
        assertFalse(LazyToolCatalog.externalLazy(lazy(null)));
        assertTrue(LazyToolCatalog.externalLazy(lazy(List.of("mcp"))));
        var eager = lazy(List.of("http"));
        eager.setToolLoadingStrategy(LlmConfiguration.ToolLoadingStrategy.EAGER);
        assertFalse(LazyToolCatalog.externalLazy(eager), "lazySources means nothing without LAZY");
    }

    @Test
    @DisplayName("lazySources [http]: built-ins stay eager, http tools go behind a grouped discover_tools that no remote tool can replace")
    void httpOnly() throws Exception {
        var setup = LazyToolCatalog.complete(assemble(true), Set.of("calculate"), false, lazy(List.of("http")));

        assertEquals(List.of("findUsers", "discover_tools"), setup.builtInSpecs().stream().map(ToolSpecification::name).toList());
        var initial = ToolLoopRunner.computeInitialActiveSpecs(setup, true).stream().map(ToolSpecification::name).toList();
        assertEquals(List.of("calculate", "discover_tools"), initial);
        var discover = setup.toolSpecs().stream().filter(s -> s.name().equals("discover_tools")).toList();
        assertEquals(1, discover.size(), "the remote 'discover_tools' lost the name to the meta-tool");
        assertTrue(discover.get(0).description().contains("users (1 tools): Users"), discover.get(0).description());
        String result = setup.toolExecutors().get("discover_tools")
                .execute(ToolExecutionRequest.builder().name("discover_tools").arguments("{\"group\":\"users\"}")
                        .build(), null);
        assertTrue(result.contains("findUsers"), result);
    }

    @Test
    @DisplayName("nothing to load lazily: discover_tools is removed again, the request is the eager one")
    void emptyCatalogRemovesMetaTool() {
        var setup = LazyToolCatalog.complete(assemble(false), Set.of("calculate"), false, lazy(List.of("http")));

        assertEquals(List.of("calculate"), setup.toolSpecs().stream().map(ToolSpecification::name).toList());
        assertFalse(setup.toolExecutors().containsKey("discover_tools"));
        assertTrue(setup.builtInSpecs().isEmpty());
    }

    @Test
    @DisplayName("group ids: an httpcalls step is grouped by its resource id, an MCP server by name or host, never the full URL")
    void groupIds() {
        assertEquals("abc123", HttpCallToolsProvider.groupId(Map.of("uri", "eddi://ai.labs.apicalls/apicallstore/apicalls/abc123?version=2")));
        assertEquals("http", HttpCallToolsProvider.groupId(Map.of()));
        var mcp = new McpCallsConfiguration();
        mcp.setMcpServerUrl("https://user:secret@mcp.example.com/sse");
        assertEquals("mcp.example.com", McpToolsProvider.groupId(mcp));
        mcp.setName("crm");
        assertEquals("crm", McpToolsProvider.groupId(mcp));
    }
}
