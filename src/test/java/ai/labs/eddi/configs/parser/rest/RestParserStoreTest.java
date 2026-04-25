package ai.labs.eddi.configs.parser.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestParserStore}.
 */
class RestParserStoreTest {

    private IParserStore parserStore;
    private RestParserStore restStore;

    @BeforeEach
    void setUp() {
        parserStore = mock(IParserStore.class);
        var descriptorStore = mock(IDocumentDescriptorStore.class);
        restStore = new RestParserStore(parserStore, descriptorStore);
    }

    @Test
    @DisplayName("readParser delegates to store")
    void readParser() throws Exception {
        var config = new ParserConfiguration();
        when(parserStore.read("p-1", 1)).thenReturn(config);
        assertNotNull(restStore.readParser("p-1", 1));
    }

    @Test
    @DisplayName("createParser returns 201")
    void createParser() throws Exception {
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getId()).thenReturn("new-id");
        when(resourceId.getVersion()).thenReturn(1);
        when(parserStore.create(any())).thenReturn(resourceId);
        assertEquals(201, restStore.createParser(new ParserConfiguration()).getStatus());
    }

    @Test
    @DisplayName("deleteParser delegates")
    void deleteParser() throws Exception {
        restStore.deleteParser("p-1", 1, false);
        verify(parserStore).delete("p-1", 1);
    }

    @Test
    @DisplayName("duplicateParser reads then creates")
    void duplicateParser() throws Exception {
        var config = new ParserConfiguration();
        when(parserStore.read("p-1", 1)).thenReturn(config);
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getId()).thenReturn("dup-id");
        when(resourceId.getVersion()).thenReturn(1);
        when(parserStore.create(any())).thenReturn(resourceId);
        assertEquals(201, restStore.duplicateParser("p-1", 1).getStatus());
    }

    @Test
    @DisplayName("getResourceURI returns non-null")
    void getResourceURI() {
        assertNotNull(restStore.getResourceURI());
    }

    @Test
    @DisplayName("getCurrentResourceId delegates")
    void getCurrentResourceId() throws Exception {
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getVersion()).thenReturn(2);
        when(parserStore.getCurrentResourceId("p-1")).thenReturn(resourceId);
        assertEquals(2, restStore.getCurrentResourceId("p-1").getVersion());
    }
}
