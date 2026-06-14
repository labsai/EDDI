/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Disabled;
import org.mockito.Mock;

import jakarta.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("RestApiCallsStore Tests")
class RestApiCallsStoreTest {

    @Mock
    private IApiCallsStore httpCallsStore;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IJsonSchemaCreator jsonSchemaCreator;

    private RestApiCallsStore store;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        store = new RestApiCallsStore(httpCallsStore, documentDescriptorStore, jsonSchemaCreator);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Nested
    @DisplayName("discoverEndpoints")
    class DiscoverEndpointsTests {

        @Test
        @DisplayName("null specUrl — returns 400")
        void nullSpecUrl() {
            Response response = store.discoverEndpoints(null, null, null);
            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("blank specUrl — returns 400")
        void blankSpecUrl() {
            Response response = store.discoverEndpoints("  ", null, null);
            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("empty specUrl — returns 400")
        void emptySpecUrl() {
            Response response = store.discoverEndpoints("", null, null);
            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("invalid specUrl — returns error response")
        void invalidSpecUrl() {
            Response response = store.discoverEndpoints("not-a-valid-url", null, null);
            // McpApiToolBuilder.parseAndBuild will throw an exception
            assertTrue(response.getStatus() == 400 || response.getStatus() == 500);
        }

        @Test
        @DisplayName("specUrl with blank apiBaseUrl — handled gracefully")
        void blankApiBaseUrl() {
            Response response = store.discoverEndpoints("http://example.com/api.yaml", "  ", null);
            // Will fail at URL fetch — blank is treated as null
            assertTrue(response.getStatus() >= 400);
        }

        @Test
        @DisplayName("specUrl with blank apiAuth — handled gracefully")
        void blankApiAuth() {
            Response response = store.discoverEndpoints("http://example.com/api.yaml", null, "  ");
            assertTrue(response.getStatus() >= 400);
        }
    }

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchemaTests {

        @Test
        @DisplayName("returns JSON schema successfully")
        void returnsSchema() throws Exception {
            when(jsonSchemaCreator.generateSchema(ApiCallsConfiguration.class))
                    .thenReturn("{\"type\":\"object\"}");
            Response response = store.readJsonSchema();
            assertEquals(200, response.getStatus());
            assertEquals("{\"type\":\"object\"}", response.getEntity());
        }
    }

    @Nested
    @DisplayName("getResourceURI")
    class GetResourceURITests {

        @Test
        @DisplayName("returns non-null resource URI")
        void returnsUri() {
            String uri = store.getResourceURI();
            assertNotNull(uri);
            assertTrue(uri.contains("apicalls"));
        }
    }

    @Nested
    @DisplayName("readApiCallsDescriptors")
    class ReadDescriptorsTests {

        @Test
        @Disabled("Requires full RestVersionInfo mock chain")
        @DisplayName("delegates to restVersionInfo")
        void delegatesToVersionInfo() {
            // This delegates internally — just ensure no exception
            assertDoesNotThrow(() -> store.readApiCallsDescriptors("", 0, 10));
        }
    }

    @Nested
    @DisplayName("createApiCalls")
    class CreateApiCallsTests {

        @Test
        @Disabled("Requires full RestVersionInfo mock chain")
        @DisplayName("delegates to restVersionInfo")
        void delegatesToVersionInfo() {
            var config = new ApiCallsConfiguration();
            // This delegates to RestVersionInfo.create which calls the store
            assertDoesNotThrow(() -> store.createApiCalls(config));
        }
    }
}
