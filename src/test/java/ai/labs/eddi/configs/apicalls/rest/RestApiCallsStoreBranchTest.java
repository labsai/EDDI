/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("RestApiCallsStore — Branch Coverage Tests")
class RestApiCallsStoreBranchTest {

    @Mock
    private IApiCallsStore apiCallsStore;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IJsonSchemaCreator jsonSchemaCreator;

    private RestApiCallsStore restApiCallsStore;

    @BeforeEach
    void setUp() {
        openMocks(this);
        restApiCallsStore = new RestApiCallsStore(apiCallsStore, documentDescriptorStore, jsonSchemaCreator);
    }

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchema {

        @Test
        @DisplayName("returns schema successfully")
        void returnsSchemaSuccessfully() throws Exception {
            when(jsonSchemaCreator.generateSchema(ApiCallsConfiguration.class)).thenReturn("{}");

            Response response = restApiCallsStore.readJsonSchema();

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("exception is thrown sneakily")
        void exceptionThrown() throws Exception {
            when(jsonSchemaCreator.generateSchema(any())).thenThrow(new RuntimeException("schema error"));

            assertThrows(RuntimeException.class, () -> restApiCallsStore.readJsonSchema());
        }
    }

    @Nested
    @DisplayName("readApiCallsDescriptors")
    class ReadDescriptors {

        @Test
        @DisplayName("returns descriptors list")
        void returnsDescriptors() throws Exception {
            var desc = new DocumentDescriptor();
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.httpcalls"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of(desc));

            List<DocumentDescriptor> result = restApiCallsStore.readApiCallsDescriptors("", 0, 10);

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("readApiCalls")
    class ReadApiCalls {

        @Test
        @DisplayName("reads configuration by id and version")
        void readsConfig() throws Exception {
            var config = new ApiCallsConfiguration();
            when(apiCallsStore.read("id1", 1)).thenReturn(config);

            ApiCallsConfiguration result = restApiCallsStore.readApiCalls("id1", 1);

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("updateApiCalls")
    class UpdateApiCalls {

        @Test
        @DisplayName("updates and returns ok response")
        void updatesConfig() throws Exception {
            when(apiCallsStore.update(eq("id1"), eq(1), any())).thenReturn(2);

            Response result = restApiCallsStore.updateApiCalls("id1", 1, new ApiCallsConfiguration());

            assertEquals(200, result.getStatus());
        }
    }

    @Nested
    @DisplayName("createApiCalls")
    class CreateApiCalls {

        @Test
        @DisplayName("creates and returns created response")
        void createsConfig() throws Exception {
            IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("newId");
            when(resourceId.getVersion()).thenReturn(1);
            when(apiCallsStore.create(any())).thenReturn(resourceId);

            Response result = restApiCallsStore.createApiCalls(new ApiCallsConfiguration());

            assertEquals(201, result.getStatus());
        }
    }

    @Nested
    @DisplayName("deleteApiCalls")
    class DeleteApiCalls {

        @Test
        @DisplayName("deletes (non-permanent) and returns ok")
        void deletesNonPermanent() throws Exception {
            when(apiCallsStore.getCurrentResourceId("id1")).thenReturn(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return "id1";
                }

                @Override
                public Integer getVersion() {
                    return 1;
                }
            });

            Response result = restApiCallsStore.deleteApiCalls("id1", 1, false);

            assertEquals(200, result.getStatus());
        }
    }

    @Nested
    @DisplayName("duplicateApiCalls")
    class DuplicateApiCalls {

        @Test
        @DisplayName("reads and re-creates the configuration")
        void duplicates() throws Exception {
            var config = new ApiCallsConfiguration();
            when(apiCallsStore.read("id1", 1)).thenReturn(config);

            IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("newId");
            when(resourceId.getVersion()).thenReturn(1);
            when(apiCallsStore.create(any())).thenReturn(resourceId);

            Response result = restApiCallsStore.duplicateApiCalls("id1", 1);

            assertEquals(201, result.getStatus());
        }
    }

    @Nested
    @DisplayName("discoverEndpoints")
    class DiscoverEndpoints {

        @Test
        @DisplayName("null specUrl returns 400")
        void nullSpecUrl() {
            Response response = restApiCallsStore.discoverEndpoints(null, null, null);
            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("blank specUrl returns 400")
        void blankSpecUrl() {
            Response response = restApiCallsStore.discoverEndpoints("   ", null, null);
            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("blank apiBaseUrl and apiAuth are treated as null")
        void blankApiBaseUrlAndAuth() {
            // This will fail on parseAndBuild since the URL is invalid, but tests the
            // effectiveBaseUrl/effectiveAuth branches
            Response response = restApiCallsStore.discoverEndpoints("http://invalid-spec-url.test/nonexistent", "   ", "   ");
            // Will return either 400 or 500 depending on the exception type
            assertTrue(response.getStatus() >= 400);
        }
    }

    @Nested
    @DisplayName("getResourceURI")
    class GetResourceURI {

        @Test
        @DisplayName("returns correct resource URI")
        void returnsResourceUri() {
            String uri = restApiCallsStore.getResourceURI();
            assertNotNull(uri);
            assertTrue(uri.contains("apicallstore"));
        }
    }

    @Nested
    @DisplayName("getCurrentResourceId")
    class GetCurrentResourceId {

        @Test
        @DisplayName("delegates to httpCallsStore")
        void delegates() throws Exception {
            IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
            when(apiCallsStore.getCurrentResourceId("id1")).thenReturn(resourceId);

            IResourceStore.IResourceId result = restApiCallsStore.getCurrentResourceId("id1");

            assertSame(resourceId, result);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when not found")
        void throwsNotFound() throws Exception {
            when(apiCallsStore.getCurrentResourceId("id1"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> restApiCallsStore.getCurrentResourceId("id1"));
        }
    }
}
