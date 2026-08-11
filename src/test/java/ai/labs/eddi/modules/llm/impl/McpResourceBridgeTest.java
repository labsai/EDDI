/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * The synthesized resource-bridge tools ({@code <server>_list_resources} /
 * {@code <server>_read_resource}) — construction contract only. The executors
 * dial lazily, so everything asserted here happens without a server; the
 * network half is exercised by CI's integration environment.
 */
@DisplayName("McpToolProviderManager resource bridge")
class McpResourceBridgeTest {

    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private SecretResolver secretResolver;

    private McpToolProviderManager manager;

    @BeforeEach
    void setUp() {
        openMocks(this);
        manager = new McpToolProviderManager(globalVariableResolver, secretResolver);
    }

    private static McpServerConfig config(String name, String url) {
        var config = new McpServerConfig();
        config.setName(name);
        config.setUrl(url);
        return config;
    }

    @Test
    @DisplayName("synthesizes exactly two tools, named from the sanitized server name")
    void synthesizesTwoNamedTools() {
        var bridge = manager.resourceBridgeTools(config("eddi-docs", "http://localhost:7070/mcp"));

        assertEquals(List.of("eddi_docs_list_resources", "eddi_docs_read_resource"),
                bridge.toolSpecs().stream().map(ToolSpecification::name).toList());
        // Every spec has a matching executor under the same name — the pairing
        // McpToolsProvider relies on when merging.
        for (ToolSpecification spec : bridge.toolSpecs()) {
            assertNotNull(bridge.executors().get(spec.name()), spec.name());
        }
    }

    @Test
    @DisplayName("falls back to the URL host when the server has no name")
    void fallsBackToHost() {
        var bridge = manager.resourceBridgeTools(config(null, "http://tools.example.com/mcp"));
        assertTrue(bridge.executors().containsKey("tools_example_com_list_resources"));
    }

    @Test
    @DisplayName("read_resource declares a required string 'uri' parameter")
    void readResourceParameter() {
        var bridge = manager.resourceBridgeTools(config("srv", "http://localhost:7070/mcp"));
        ToolSpecification read = bridge.toolSpecs().stream()
                .filter(spec -> spec.name().endsWith("_read_resource")).findFirst().orElseThrow();
        assertNotNull(read.parameters());
        assertTrue(read.parameters().required().contains("uri"));
    }

    @Test
    @DisplayName("rejects the same static misconfigurations discoverTools rejects")
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.resourceBridgeTools(config("bad", null)));
        assertThrows(IllegalArgumentException.class,
                () -> manager.resourceBridgeTools(config("bad", "ftp://example.com/mcp")));
        var badTransport = config("bad", "http://localhost:7070/mcp");
        badTransport.setTransport("stdio");
        assertThrows(IllegalArgumentException.class,
                () -> manager.resourceBridgeTools(badTransport));
    }

    @Test
    @DisplayName("an unreachable server costs an error tool RESULT, not a discovery failure")
    void unreachableServerFailsAtCallTime() {
        // Port 9 (discard) is about as reliably closed as it gets. Construction
        // must succeed; only executing the tool reports the error, as text the
        // model can read and act on.
        var bridge = manager.resourceBridgeTools(config("dead", "http://127.0.0.1:9/mcp"));
        var request = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .name("dead_list_resources").arguments("{}").build();
        String result = bridge.executors().get("dead_list_resources").execute(request, null);
        assertTrue(result.startsWith("Error listing resources"), result);
    }
}
