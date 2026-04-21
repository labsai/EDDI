package ai.labs.eddi.configs.mcpcalls.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestMcpCallsStore}.
 */
class RestMcpCallsStoreTest {

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
    @DisplayName("readJsonSchema")
    class ReadJsonSchema {
        @Test
        void returnsSchema() throws Exception {
            when(jsonSchemaCreator.generateSchema(McpCallsConfiguration.class)).thenReturn("{}");
            assertEquals(200, restStore.readJsonSchema().getStatus());
        }
    }

    @Nested
    @DisplayName("readMcpCalls")
    class ReadMcpCalls {
        @Test
        void delegatesToStore() throws Exception {
            var config = new McpCallsConfiguration();
            when(mcpCallsStore.read("mcp-1", 1)).thenReturn(config);
            assertNotNull(restStore.readMcpCalls("mcp-1", 1));
        }
    }

    @Nested
    @DisplayName("createMcpCalls")
    class CreateMcpCalls {
        @Test
        void creates() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("new-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(mcpCallsStore.create(any())).thenReturn(resourceId);
            assertEquals(201, restStore.createMcpCalls(new McpCallsConfiguration()).getStatus());
        }
    }

    @Nested
    @DisplayName("deleteMcpCalls")
    class DeleteMcpCalls {
        @Test
        void deletes() throws Exception {
            restStore.deleteMcpCalls("mcp-1", 1, false);
            verify(mcpCallsStore).delete("mcp-1", 1);
        }
    }

    @Nested
    @DisplayName("duplicateMcpCalls")
    class DuplicateMcpCalls {
        @Test
        void duplicates() throws Exception {
            var config = new McpCallsConfiguration();
            when(mcpCallsStore.read("mcp-1", 1)).thenReturn(config);
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("dup-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(mcpCallsStore.create(any())).thenReturn(resourceId);
            assertEquals(201, restStore.duplicateMcpCalls("mcp-1", 1).getStatus());
        }
    }

    @Nested
    @DisplayName("discoverTools")
    class DiscoverTools {

        @Test
        @DisplayName("should return BAD_REQUEST for null URL")
        void nullUrl() {
            Response response = restStore.discoverTools(null, null, null);
            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("should return BAD_REQUEST for blank URL")
        void blankUrl() {
            Response response = restStore.discoverTools("  ", null, null);
            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("should return BAD_GATEWAY on connection failure")
        void connectionFailure() throws Exception {
            when(mcpToolProviderManager.discoverTools(any()))
                    .thenThrow(new RuntimeException("Connection refused"));
            Response response = restStore.discoverTools("http://localhost:9999", "http", null);
            assertEquals(502, response.getStatus());
            @SuppressWarnings("unchecked")
            Map<String, Object> entity = (Map<String, Object>) response.getEntity();
            assertTrue(entity.get("error").toString().contains("Connection refused"));
        }
    }

    @Nested
    @DisplayName("getResourceURI / getCurrentResourceId")
    class ResourceInfo {
        @Test
        void returnsUri() {
            assertNotNull(restStore.getResourceURI());
        }

        @Test
        void delegatesCurrentId() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getVersion()).thenReturn(2);
            when(mcpCallsStore.getCurrentResourceId("mcp-1")).thenReturn(resourceId);
            assertEquals(2, restStore.getCurrentResourceId("mcp-1").getVersion());
        }
    }
}
