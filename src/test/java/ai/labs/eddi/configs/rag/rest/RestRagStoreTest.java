package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RestRagStoreTest {

    private static final String RAG_ID = "aabbccddee1122334455";

    @Mock
    private IRagStore ragStore;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IJsonSchemaCreator jsonSchemaCreator;

    private RestRagStore restRagStore;

    @BeforeEach
    void setUp() {
        openMocks(this);
        restRagStore = new RestRagStore(ragStore, documentDescriptorStore, jsonSchemaCreator);
    }

    @Test
    void readJsonSchema_shouldReturnSchema() throws Exception {
        when(jsonSchemaCreator.generateSchema(RagConfiguration.class)).thenReturn("{}");

        Response response = restRagStore.readJsonSchema();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        verify(jsonSchemaCreator).generateSchema(RagConfiguration.class);
    }

    @Test
    void getResourceURI_shouldContainRagsPath() {
        String uri = restRagStore.getResourceURI();

        assertNotNull(uri);
        assertTrue(uri.contains("ragstore/rags"));
    }

    @Test
    void getCurrentResourceId_shouldDelegateToStore() throws Exception {
        var resourceId = new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return RAG_ID;
            }
            @Override
            public Integer getVersion() {
                return 2;
            }
        };
        when(ragStore.getCurrentResourceId(RAG_ID)).thenReturn(resourceId);

        var result = restRagStore.getCurrentResourceId(RAG_ID);

        assertEquals(RAG_ID, result.getId());
        assertEquals(2, result.getVersion());
    }
}
