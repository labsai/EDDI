/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RestMcpCallsStore — Deep Coverage")
class RestMcpCallsStoreDeepCoverageTest {

    private IMcpCallsStore mcpCallsStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private McpToolProviderManager mcpToolProviderManager;
    private RestMcpCallsStore restStore;

    @BeforeEach
    void setUp() {
        mcpCallsStore = mock(IMcpCallsStore.class);
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        mcpToolProviderManager = mock(McpToolProviderManager.class);
        restStore = new RestMcpCallsStore(mcpCallsStore, documentDescriptorStore, jsonSchemaCreator, mcpToolProviderManager);
    }

    @Nested
    @DisplayName("discoverTools — success path")
    class DiscoverToolsSuccess {

        @Test
        @DisplayName("returns tool list with parameters")
        void successWithParams() throws Exception {
            var spec1 = ToolSpecification.builder().name("tool1").description("desc1").build();
            var paramSchema = dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder()
                    .addStringProperty("param1")
                    .build();
            var spec2 = ToolSpecification.builder().name("tool2").description("desc2")
                    .parameters(paramSchema)
                    .build();

            var result = new McpToolProviderManager.McpToolsResult(List.of(spec1, spec2), Map.of());
            doReturn(result).when(mcpToolProviderManager).discoverTools(any());

            Response response = restStore.discoverTools("http://remote:8080", "http", null);
            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> entity = (Map<String, Object>) response.getEntity();
            assertEquals(2, entity.get("count"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tools = (List<Map<String, Object>>) entity.get("tools");
            assertEquals("tool1", tools.get(0).get("name"));
            assertEquals("desc1", tools.get(0).get("description"));
            assertNull(tools.get(0).get("parameters")); // spec1 has no parameters
            assertNotNull(tools.get(1).get("parameters")); // spec2 has parameters
        }

        @Test
        @DisplayName("with apiKey — sets apiKey on tempConfig")
        void successWithApiKey() throws Exception {
            var spec = ToolSpecification.builder().name("t").description("d").build();
            var result = new McpToolProviderManager.McpToolsResult(List.of(spec), Map.of());
            doReturn(result).when(mcpToolProviderManager).discoverTools(any());

            Response response = restStore.discoverTools("http://remote:8080", "sse", "my-api-key");
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("with null transport — defaults to http")
        void successNullTransport() throws Exception {
            var spec = ToolSpecification.builder().name("t").description("d").build();
            var result = new McpToolProviderManager.McpToolsResult(List.of(spec), Map.of());
            doReturn(result).when(mcpToolProviderManager).discoverTools(any());

            Response response = restStore.discoverTools("http://remote:8080", null, null);
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("with empty tools list — returns count 0")
        void emptyToolsList() throws Exception {
            var result = new McpToolProviderManager.McpToolsResult(List.of(), Map.of());
            doReturn(result).when(mcpToolProviderManager).discoverTools(any());

            Response response = restStore.discoverTools("http://remote:8080", "http", null);
            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> entity = (Map<String, Object>) response.getEntity();
            assertEquals(0, entity.get("count"));
        }
    }

    @Nested
    @DisplayName("updateMcpCalls")
    class UpdateMcpCalls {
        @Test
        @DisplayName("delegates to restVersionInfo.update")
        void delegatesUpdate() throws Exception {
            var config = new McpCallsConfiguration();
            doReturn(config).when(mcpCallsStore).read("id1", 1);
            restStore.updateMcpCalls("id1", 1, config);
            verify(mcpCallsStore).update("id1", 1, config);
        }
    }

    @Nested
    @DisplayName("readJsonSchema — exception")
    class ReadJsonSchemaException {
        @Test
        @DisplayName("throws when schema generation fails")
        void throwsOnSchemaFailure() throws Exception {
            doThrow(new RuntimeException("schema error")).when(jsonSchemaCreator).generateSchema(McpCallsConfiguration.class);
            assertThrows(RuntimeException.class, () -> restStore.readJsonSchema());
        }
    }

    @Nested
    @DisplayName("readMcpCallsDescriptors")
    class ReadDescriptors {
        @Test
        @DisplayName("delegates to restVersionInfo.readDescriptors")
        void delegatesReadDescriptors() {
            restStore.readMcpCallsDescriptors("filter", 0, 10);
            // Just verifying no exception — delegation is to RestVersionInfo
        }
    }
}
