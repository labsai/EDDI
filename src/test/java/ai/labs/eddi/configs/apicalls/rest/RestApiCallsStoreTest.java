/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.apicalls.model.ApiEndpointDiscoveryRequest;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import org.junit.jupiter.api.*;
import org.mockito.Mock;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;
import java.util.Map;

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
    @DisplayName("the deprecated GET refuses a credential in the URL")
    class DeprecatedGetTests {

        private static UriInfo withQuery(String... names) {
            var params = new MultivaluedHashMap<String, String>();
            for (String name : names) {
                params.putSingle(name, "value");
            }
            UriInfo uriInfo = mock(UriInfo.class);
            when(uriInfo.getQueryParameters()).thenReturn(params);
            return uriInfo;
        }

        @Test
        @DisplayName("apiAuth is rejected — it is the parameter this endpoint existed to take")
        void rejectsApiAuth() {
            // The regression this pins: 'apiAuth' normalises to 'apiauth', which
            // matched none of the longer credential words, so the guard silently
            // ignored the one parameter it was written for and a client that had not
            // migrated kept putting a live secret in a URL with no signal.
            Response response = store.discoverEndpointsUnauthenticated("https://example.com/openapi.json", null, withQuery("specUrl", "apiAuth"));

            assertEquals(400, response.getStatus());
            @SuppressWarnings("unchecked")
            var entity = (Map<String, Object>) response.getEntity();
            assertTrue(entity.get("error").toString().contains("apiAuth"), entity.toString());
        }

        @Test
        @DisplayName("other credential spellings are rejected too")
        void rejectsOtherCredentialNames() {
            for (String name : List.of("apiKey", "authorization", "access_token", "clientSecret", "password")) {
                assertEquals(400, store.discoverEndpointsUnauthenticated("https://example.com/o.json", null, withQuery(name)).getStatus(), name);
            }
        }

        @Test
        @DisplayName("an ordinary parameter is not mistaken for a credential")
        void allowsOrdinaryParameters() {
            // Over-rejection is its own failure: a public spec must still be
            // discoverable without a credential.
            Response response = store.discoverEndpointsUnauthenticated("not-a-valid-url", null, withQuery("specUrl", "apiBaseUrl"));

            @SuppressWarnings("unchecked")
            var entity = (Map<String, Object>) response.getEntity();
            assertTrue(!entity.get("error").toString().contains("no longer accepted"), entity.toString());
        }
    }

    @Nested
    @DisplayName("discoverEndpoints")
    class DiscoverEndpointsTests {

        @Test
        @DisplayName("null specUrl — returns 400")
        void nullSpecUrl() {
            Response response = store.discoverEndpoints(null);
            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("blank specUrl — returns 400")
        void blankSpecUrl() {
            Response response = store.discoverEndpoints(new ApiEndpointDiscoveryRequest("  ", null, null));
            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("empty specUrl — returns 400")
        void emptySpecUrl() {
            Response response = store.discoverEndpoints(new ApiEndpointDiscoveryRequest("", null, null));
            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("invalid specUrl — returns error response")
        void invalidSpecUrl() {
            Response response = store.discoverEndpoints(new ApiEndpointDiscoveryRequest("not-a-valid-url", null, null));
            // McpApiToolBuilder.parseAndBuild will throw an exception
            assertTrue(response.getStatus() == 400 || response.getStatus() == 500);
        }

        @Test
        @DisplayName("specUrl with blank apiBaseUrl — handled gracefully")
        void blankApiBaseUrl() {
            Response response = store.discoverEndpoints(new ApiEndpointDiscoveryRequest("file:///nonexistent/spec.yaml", "  ", null));
            // Will fail at URL fetch — blank is treated as null
            assertTrue(response.getStatus() >= 400);
        }

        @Test
        @DisplayName("specUrl with blank authHeaderRef — handled gracefully")
        void blankAuthHeaderRef() {
            Response response = store.discoverEndpoints(new ApiEndpointDiscoveryRequest("file:///nonexistent/spec.yaml", null, "  "));
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
}
