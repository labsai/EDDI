/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.dictionary.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestDictionaryStore}.
 */
class RestDictionaryStoreTest {

    private IDictionaryStore dictionaryStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private RestDictionaryStore restStore;

    @BeforeEach
    void setUp() {
        dictionaryStore = mock(IDictionaryStore.class);
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        restStore = new RestDictionaryStore(dictionaryStore, documentDescriptorStore, jsonSchemaCreator);
    }

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchema {
        @Test
        void returnsSchema() throws Exception {
            when(jsonSchemaCreator.generateSchema(DictionaryConfiguration.class)).thenReturn("{}");
            Response response = restStore.readJsonSchema();
            assertEquals(200, response.getStatus());
        }
    }

    @Nested
    @DisplayName("readRegularDictionary")
    class ReadRegularDictionary {
        @Test
        void delegatesToStore() throws Exception {
            var config = new DictionaryConfiguration();
            when(dictionaryStore.read("dict-1", 1)).thenReturn(config);
            DictionaryConfiguration result = restStore.readRegularDictionary("dict-1", 1, "", "", 0, 10);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("readExpressions")
    class ReadExpressions {
        @Test
        void returnsExpressions() throws Exception {
            when(dictionaryStore.readExpressions("dict-1", 1, "greet", "", 0, 10))
                    .thenReturn(List.of("greeting(hello)", "greeting(hi)"));
            List<String> result = restStore.readExpressions("dict-1", 1, "greet", "", 0, 10);
            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("createRegularDictionary")
    class CreateRegularDictionary {
        @Test
        void creates() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("new-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(dictionaryStore.create(any())).thenReturn(resourceId);

            Response response = restStore.createRegularDictionary(new DictionaryConfiguration());
            assertEquals(201, response.getStatus());
        }
    }

    @Nested
    @DisplayName("deleteRegularDictionary")
    class DeleteRegularDictionary {
        @Test
        void deletes() throws Exception {
            restStore.deleteRegularDictionary("dict-1", 1, false);
            verify(dictionaryStore).delete("dict-1", 1);
        }
    }

    @Nested
    @DisplayName("duplicateRegularDictionary")
    class DuplicateRegularDictionary {
        @Test
        void duplicates() throws Exception {
            var config = new DictionaryConfiguration();
            when(dictionaryStore.read("dict-1", 1)).thenReturn(config);
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("dup-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(dictionaryStore.create(any())).thenReturn(resourceId);

            Response response = restStore.duplicateRegularDictionary("dict-1", 1);
            assertEquals(201, response.getStatus());
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
            when(resourceId.getVersion()).thenReturn(3);
            when(dictionaryStore.getCurrentResourceId("dict-1")).thenReturn(resourceId);
            assertEquals(3, restStore.getCurrentResourceId("dict-1").getVersion());
        }
    }
}
