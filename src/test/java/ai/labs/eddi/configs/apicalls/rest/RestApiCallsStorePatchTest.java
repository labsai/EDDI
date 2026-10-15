/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.patch.ConfigJsonPatcher;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RestApiCallsStore PATCH")
class RestApiCallsStorePatchTest {

    private static final String ID = "5f1c0f3a9d8e4b2a7c6d1e0f";

    private IApiCallsStore store;
    private RestApiCallsStore rest;

    @BeforeEach
    void setUp() throws Exception {
        var mapper = new ObjectMapper();
        store = mock(IApiCallsStore.class);
        rest = new RestApiCallsStore(store, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), mock(ResourceAccessGuard.class));
        rest.jsonPatcher = new ConfigJsonPatcher(mapper, new StrictConfigurationParser(mapper));

        var current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(1);
        when(store.getCurrentResourceId(ID)).thenReturn(current);
        when(store.read(ID, 1)).thenReturn(mapper.readValue("""
                {"targetServerUrl":"https://api.example.invalid",
                 "httpCalls":[{"name":"c","request":{"path":"/a","headers":{"Authorization":"Bearer ${vault:api-token}"}}}]}
                """, ApiCallsConfiguration.class));
        when(store.update(eq(ID), eq(1), any())).thenReturn(2);
    }

    @Test
    @DisplayName("edits a call and keeps the header's credential reference exactly as stored")
    void patches() throws Exception {
        Response response = rest.patchApiCalls(ID, 1, "[{\"op\":\"replace\",\"path\":\"/httpCalls/0/request/path\",\"value\":\"/b\"}]");

        assertEquals(200, response.getStatus());
        var written = ArgumentCaptor.forClass(ApiCallsConfiguration.class);
        verify(store).update(eq(ID), eq(1), written.capture());
        var request = written.getValue().getHttpCalls().get(0).getRequest();
        assertEquals("/b", request.getPath());
        assertEquals("Bearer ${vault:api-token}", request.getHeaders().get("Authorization"));
    }

    @Test
    @DisplayName("runs the same pre-write check as PUT: a batch size above the ceiling is refused with the same 400")
    void validatedLikeAPut() throws Exception {
        Response response = rest.patchApiCalls(ID, 1,
                "[{\"op\":\"add\",\"path\":\"/httpCalls/0/preRequest\",\"value\":{\"batchRequests\":{\"maxBatchSize\":1001}}}]");

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("ceiling"), response.getEntity().toString());
        verify(store, never()).update(anyString(), anyInt(), any());
    }
}
