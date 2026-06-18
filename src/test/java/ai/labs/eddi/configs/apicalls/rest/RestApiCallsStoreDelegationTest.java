/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.*;
import org.mockito.Mock;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additional tests for {@link RestApiCallsStore} — covering delegation methods
 * not covered by the existing test file (readApiCallsDescriptors, readApiCalls,
 * updateApiCalls, createApiCalls, deleteApiCalls, duplicateApiCalls,
 * getCurrentResourceId).
 */
@DisplayName("RestApiCallsStore — Delegation Tests")
class RestApiCallsStoreDelegationTest {

    @Mock
    private IApiCallsStore httpCallsStore;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IJsonSchemaCreator jsonSchemaCreator;

    private RestApiCallsStore store;
    private RestVersionInfo<ApiCallsConfiguration> restVersionInfoSpy;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = openMocks(this);
        store = new RestApiCallsStore(httpCallsStore, documentDescriptorStore, jsonSchemaCreator);

        // Access the restVersionInfo field to spy on it
        Field rvField = RestApiCallsStore.class.getDeclaredField("restVersionInfo");
        rvField.setAccessible(true);
        RestVersionInfo<ApiCallsConfiguration> original = (RestVersionInfo<ApiCallsConfiguration>) rvField.get(store);
        restVersionInfoSpy = spy(original);
        rvField.set(store, restVersionInfoSpy);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    // ==================== readApiCallsDescriptors ====================

    @Nested
    @DisplayName("readApiCallsDescriptors")
    class ReadDescriptorsTests {

        @Test
        @DisplayName("delegates with correct type, filter, index, limit")
        void delegatesCorrectly() throws Exception {
            List<DocumentDescriptor> expected = List.of(new DocumentDescriptor());
            doReturn(expected).when(documentDescriptorStore)
                    .readDescriptors("ai.labs.httpcalls", "myFilter", 0, 10, false);

            List<DocumentDescriptor> result = store.readApiCallsDescriptors("myFilter", 0, 10);

            assertEquals(expected, result);
        }

        @Test
        @DisplayName("empty filter — delegates empty string")
        void emptyFilter() throws Exception {
            List<DocumentDescriptor> expected = List.of();
            doReturn(expected).when(documentDescriptorStore)
                    .readDescriptors("ai.labs.httpcalls", "", 0, 20, false);

            List<DocumentDescriptor> result = store.readApiCallsDescriptors("", 0, 20);

            assertEquals(expected, result);
        }
    }

    // ==================== readApiCalls ====================

    @Nested
    @DisplayName("readApiCalls")
    class ReadApiCallsTests {

        @Test
        @DisplayName("delegates to restVersionInfo.read")
        void delegatesCorrectly() throws Exception {
            ApiCallsConfiguration expected = new ApiCallsConfiguration();
            doReturn(expected).when(httpCallsStore).read("id-1", 1);

            ApiCallsConfiguration result = store.readApiCalls("id-1", 1);

            assertSame(expected, result);
        }
    }

    // ==================== updateApiCalls ====================

    @Nested
    @DisplayName("updateApiCalls")
    class UpdateApiCallsTests {

        @Test
        @DisplayName("delegates to restVersionInfo.update")
        void delegatesCorrectly() throws Exception {
            ApiCallsConfiguration config = new ApiCallsConfiguration();
            IResourceStore.IResourceId currentId = mock(IResourceStore.IResourceId.class);
            doReturn("id-1").when(currentId).getId();
            doReturn(1).when(currentId).getVersion();
            doReturn(currentId).when(httpCallsStore).getCurrentResourceId("id-1");
            doReturn(2).when(httpCallsStore).update("id-1", 1, config);

            Response response = store.updateApiCalls("id-1", 1, config);

            assertNotNull(response);
        }
    }

    // ==================== createApiCalls ====================

    @Nested
    @DisplayName("createApiCalls")
    class CreateApiCallsTests {

        @Test
        @DisplayName("delegates to restVersionInfo.create")
        void delegatesCorrectly() throws Exception {
            ApiCallsConfiguration config = new ApiCallsConfiguration();
            IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
            doReturn("new-id").when(resourceId).getId();
            doReturn(1).when(resourceId).getVersion();
            doReturn(resourceId).when(httpCallsStore).create(config);

            Response response = store.createApiCalls(config);

            assertEquals(201, response.getStatus());
        }
    }

    // ==================== deleteApiCalls ====================

    @Nested
    @DisplayName("deleteApiCalls")
    class DeleteApiCallsTests {

        @Test
        @DisplayName("non-permanent delete — delegates to restVersionInfo.delete")
        void nonPermanentDelete() throws Exception {
            IResourceStore.IResourceId currentId = mock(IResourceStore.IResourceId.class);
            doReturn("id-1").when(currentId).getId();
            doReturn(1).when(currentId).getVersion();
            doReturn(currentId).when(httpCallsStore).getCurrentResourceId("id-1");

            Response response = store.deleteApiCalls("id-1", 1, false);

            assertNotNull(response);
            assertEquals(200, response.getStatus());
            verify(httpCallsStore).delete("id-1", 1);
        }

        @Test
        @DisplayName("permanent delete — calls deleteAllPermanently")
        void permanentDelete() throws Exception {
            IResourceStore.IResourceId currentId = mock(IResourceStore.IResourceId.class);
            doReturn("id-1").when(currentId).getId();
            doReturn(1).when(currentId).getVersion();
            doReturn(currentId).when(httpCallsStore).getCurrentResourceId("id-1");

            Response response = store.deleteApiCalls("id-1", 1, true);

            assertNotNull(response);
            assertEquals(200, response.getStatus());
            verify(httpCallsStore).deleteAllPermanently("id-1");
        }
    }

    // ==================== duplicateApiCalls ====================

    @Nested
    @DisplayName("duplicateApiCalls")
    class DuplicateApiCallsTests {

        @Test
        @DisplayName("reads then creates — returns 201")
        void duplicatesCorrectly() throws Exception {
            ApiCallsConfiguration config = new ApiCallsConfiguration();
            doReturn(config).when(httpCallsStore).read("id-1", 1);

            IResourceStore.IResourceId newId = mock(IResourceStore.IResourceId.class);
            doReturn("new-id").when(newId).getId();
            doReturn(1).when(newId).getVersion();
            doReturn(newId).when(httpCallsStore).create(config);

            Response response = store.duplicateApiCalls("id-1", 1);

            assertEquals(201, response.getStatus());
            verify(httpCallsStore).read("id-1", 1);
            verify(httpCallsStore).create(config);
        }
    }

    // ==================== getCurrentResourceId ====================

    @Nested
    @DisplayName("getCurrentResourceId")
    class GetCurrentResourceIdTests {

        @Test
        @DisplayName("delegates to httpCallsStore")
        void delegatesCorrectly() throws Exception {
            IResourceStore.IResourceId expected = mock(IResourceStore.IResourceId.class);
            doReturn(expected).when(httpCallsStore).getCurrentResourceId("id-1");

            IResourceStore.IResourceId result = store.getCurrentResourceId("id-1");

            assertSame(expected, result);
            verify(httpCallsStore).getCurrentResourceId("id-1");
        }

        @Test
        @DisplayName("not found — propagates exception")
        void notFound() throws Exception {
            doThrow(new IResourceStore.ResourceNotFoundException("not found"))
                    .when(httpCallsStore).getCurrentResourceId("missing");

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.getCurrentResourceId("missing"));
        }
    }

    // ==================== readJsonSchema error path ====================

    @Nested
    @DisplayName("readJsonSchema — error path")
    class ReadJsonSchemaErrorTests {

        @Test
        @DisplayName("exception from schema creator — propagates")
        void exceptionPropagated() throws Exception {
            doThrow(new RuntimeException("Schema generation failed"))
                    .when(jsonSchemaCreator).generateSchema(ApiCallsConfiguration.class);

            assertThrows(RuntimeException.class, () -> store.readJsonSchema());
        }
    }
}
