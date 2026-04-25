package ai.labs.eddi.configs.propertysetter.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestPropertySetterStore}.
 */
class RestPropertySetterStoreTest {

    private IPropertySetterStore propertySetterStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private RestPropertySetterStore restStore;

    @BeforeEach
    void setUp() {
        propertySetterStore = mock(IPropertySetterStore.class);
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        restStore = new RestPropertySetterStore(propertySetterStore, documentDescriptorStore, jsonSchemaCreator);
    }

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchema {
        @Test
        void returnsSchema() throws Exception {
            when(jsonSchemaCreator.generateSchema(PropertySetterConfiguration.class)).thenReturn("{}");
            assertEquals(200, restStore.readJsonSchema().getStatus());
        }
    }

    @Nested
    @DisplayName("CRUD operations")
    class CrudOps {
        @Test
        void readPropertySetter() throws Exception {
            var config = new PropertySetterConfiguration();
            when(propertySetterStore.read("prop-1", 1)).thenReturn(config);
            assertNotNull(restStore.readPropertySetter("prop-1", 1));
        }

        @Test
        void createPropertySetter() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("new-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(propertySetterStore.create(any())).thenReturn(resourceId);
            assertEquals(201, restStore.createPropertySetter(new PropertySetterConfiguration()).getStatus());
        }

        @Test
        void deletePropertySetter() throws Exception {
            restStore.deletePropertySetter("prop-1", 1, false);
            verify(propertySetterStore).delete("prop-1", 1);
        }

        @Test
        void duplicatePropertySetter() throws Exception {
            var config = new PropertySetterConfiguration();
            when(propertySetterStore.read("prop-1", 1)).thenReturn(config);
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("dup-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(propertySetterStore.create(any())).thenReturn(resourceId);
            assertEquals(201, restStore.duplicatePropertySetter("prop-1", 1).getStatus());
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
            when(propertySetterStore.getCurrentResourceId("prop-1")).thenReturn(resourceId);
            assertEquals(2, restStore.getCurrentResourceId("prop-1").getVersion());
        }
    }
}
