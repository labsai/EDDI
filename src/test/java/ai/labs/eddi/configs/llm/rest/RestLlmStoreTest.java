/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestLlmStore}.
 */
class RestLlmStoreTest {

    private ILlmStore llmStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private RestLlmStore restStore;

    @BeforeEach
    void setUp() {
        llmStore = mock(ILlmStore.class);
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        restStore = new RestLlmStore(llmStore, documentDescriptorStore, jsonSchemaCreator);
    }

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchema {
        @Test
        void returnsSchema() throws Exception {
            when(jsonSchemaCreator.generateSchema(LlmConfiguration.class)).thenReturn("{}");
            assertEquals(200, restStore.readJsonSchema().getStatus());
        }
    }

    @Nested
    @DisplayName("CRUD operations")
    class CrudOps {
        @Test
        void readLlm() throws Exception {
            var config = mock(LlmConfiguration.class);
            when(llmStore.read("llm-1", 1)).thenReturn(config);
            assertNotNull(restStore.readLlm("llm-1", 1));
        }

        @Test
        void createLlm() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("new-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(llmStore.create(any())).thenReturn(resourceId);
            assertEquals(201, restStore.createLlm(mock(LlmConfiguration.class)).getStatus());
        }

        @Test
        void deleteLlm() throws Exception {
            restStore.deleteLlm("llm-1", 1, false);
            verify(llmStore).delete("llm-1", 1);
        }

        @Test
        void duplicateLlm() throws Exception {
            var config = mock(LlmConfiguration.class);
            when(llmStore.read("llm-1", 1)).thenReturn(config);
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("dup-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(llmStore.create(any())).thenReturn(resourceId);
            assertEquals(201, restStore.duplicateLlm("llm-1", 1).getStatus());
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
            when(resourceId.getVersion()).thenReturn(4);
            when(llmStore.getCurrentResourceId("llm-1")).thenReturn(resourceId);
            assertEquals(4, restStore.getCurrentResourceId("llm-1").getVersion());
        }
    }
}
