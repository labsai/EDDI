/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
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
 * Unit tests for {@link RestApiCallsStore}.
 */
class RestApiCallsStoreTest {

    private IApiCallsStore apiCallsStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private RestApiCallsStore restStore;

    @BeforeEach
    void setUp() {
        apiCallsStore = mock(IApiCallsStore.class);
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        restStore = new RestApiCallsStore(apiCallsStore, documentDescriptorStore, jsonSchemaCreator);
    }

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchema {
        @Test
        void returnsSchema() throws Exception {
            when(jsonSchemaCreator.generateSchema(ApiCallsConfiguration.class)).thenReturn("{}");
            assertEquals(200, restStore.readJsonSchema().getStatus());
        }
    }

    @Nested
    @DisplayName("readApiCalls")
    class ReadApiCalls {
        @Test
        void delegatesToStore() throws Exception {
            var config = new ApiCallsConfiguration();
            when(apiCallsStore.read("api-1", 1)).thenReturn(config);
            assertNotNull(restStore.readApiCalls("api-1", 1));
        }
    }

    @Nested
    @DisplayName("createApiCalls")
    class CreateApiCalls {
        @Test
        void creates() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("new-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(apiCallsStore.create(any())).thenReturn(resourceId);
            assertEquals(201, restStore.createApiCalls(new ApiCallsConfiguration()).getStatus());
        }
    }

    @Nested
    @DisplayName("deleteApiCalls")
    class DeleteApiCalls {
        @Test
        void deletes() throws Exception {
            restStore.deleteApiCalls("api-1", 1, false);
            verify(apiCallsStore).delete("api-1", 1);
        }
    }

    @Nested
    @DisplayName("duplicateApiCalls")
    class DuplicateApiCalls {
        @Test
        void duplicates() throws Exception {
            var config = new ApiCallsConfiguration();
            when(apiCallsStore.read("api-1", 1)).thenReturn(config);
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("dup-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(apiCallsStore.create(any())).thenReturn(resourceId);
            assertEquals(201, restStore.duplicateApiCalls("api-1", 1).getStatus());
        }
    }

    @Nested
    @DisplayName("discoverEndpoints")
    class DiscoverEndpoints {
        @Test
        void nullUrl() {
            assertEquals(400, restStore.discoverEndpoints(null, null, null).getStatus());
        }

        @Test
        void blankUrl() {
            assertEquals(400, restStore.discoverEndpoints("  ", null, null).getStatus());
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
            when(resourceId.getVersion()).thenReturn(5);
            when(apiCallsStore.getCurrentResourceId("api-1")).thenReturn(resourceId);
            assertEquals(5, restStore.getCurrentResourceId("api-1").getVersion());
        }
    }
}
