package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAgentGroupStore}.
 */
class RestAgentGroupStoreTest {

    private IAgentGroupStore groupStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private RestAgentGroupStore restStore;

    @BeforeEach
    void setUp() {
        groupStore = mock(IAgentGroupStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        restStore = new RestAgentGroupStore(groupStore, documentDescriptorStore, jsonSchemaCreator);
    }

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchema {

        @Test
        @DisplayName("should return 200 with schema")
        void returnsSchema() throws Exception {
            when(jsonSchemaCreator.generateSchema(AgentGroupConfiguration.class)).thenReturn("{}");

            Response response = restStore.readJsonSchema();

            assertEquals(200, response.getStatus());
            assertEquals("{}", response.getEntity());
        }
    }

    @Nested
    @DisplayName("readDiscussionStyles")
    class ReadDiscussionStyles {

        @Test
        @DisplayName("should return all discussion styles")
        void returnsAllStyles() {
            Response response = restStore.readDiscussionStyles();

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> styles = (List<Map<String, Object>>) response.getEntity();
            assertEquals(DiscussionStyle.values().length, styles.size());
        }

        @Test
        @DisplayName("should include ROUND_TABLE style")
        void includesRoundTable() {
            Response response = restStore.readDiscussionStyles();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> styles = (List<Map<String, Object>>) response.getEntity();
            boolean found = styles.stream().anyMatch(s -> "ROUND_TABLE".equals(s.get("style")));
            assertTrue(found, "Should include ROUND_TABLE style");
        }

        @Test
        @DisplayName("each style should have description and phases")
        void stylesHaveMetadata() {
            Response response = restStore.readDiscussionStyles();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> styles = (List<Map<String, Object>>) response.getEntity();
            for (var style : styles) {
                assertNotNull(style.get("description"), "Each style should have a description");
                assertNotNull(style.get("phases"), "Each style should have phases");
            }
        }
    }

    @Nested
    @DisplayName("readGroup")
    class ReadGroup {

        @Test
        @DisplayName("should delegate to restVersionInfo")
        void delegatesToVersionInfo() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("Test Group");
            when(groupStore.read(eq("group-1"), eq(1))).thenReturn(config);

            AgentGroupConfiguration result = restStore.readGroup("group-1", 1);

            assertEquals("Test Group", result.getName());
        }
    }

    @Nested
    @DisplayName("deleteGroup")
    class DeleteGroup {

        @Test
        @DisplayName("should delegate to restVersionInfo")
        void delegatesToVersionInfo() throws Exception {
            when(groupStore.getCurrentResourceId("group-1"))
                    .thenReturn(createResourceId("group-1", 1));

            restStore.deleteGroup("group-1", 1, false);

            verify(groupStore).delete("group-1", 1);
        }
    }

    @Nested
    @DisplayName("getCurrentResourceId")
    class GetCurrentResourceId {

        @Test
        @DisplayName("should delegate to group store")
        void delegatesToStore() throws Exception {
            var resourceId = createResourceId("group-1", 3);
            when(groupStore.getCurrentResourceId("group-1")).thenReturn(resourceId);

            var result = restStore.getCurrentResourceId("group-1");

            assertEquals("group-1", result.getId());
            assertEquals(3, result.getVersion());
        }

        @Test
        @DisplayName("should propagate ResourceNotFoundException")
        void propagatesNotFound() throws Exception {
            when(groupStore.getCurrentResourceId("missing"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> restStore.getCurrentResourceId("missing"));
        }
    }

    @Nested
    @DisplayName("getResourceURI")
    class GetResourceURI {

        @Test
        @DisplayName("should return non-null URI")
        void returnsUri() {
            String uri = restStore.getResourceURI();
            assertNotNull(uri);
        }
    }

    private IResourceStore.IResourceId createResourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }
}
