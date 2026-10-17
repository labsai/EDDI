/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An http call with {@code saveResponse: true} and no
 * {@code responseObjectName} was accepted at save time and then failed every
 * turn with "Null key for a Map not allowed in JSON", leaving the conversation
 * in ERROR. It is refused when the config is saved.
 */
@DisplayName("RestApiCallsStore — saveResponse without responseObjectName is refused at save time")
class RestApiCallsStoreSaveResponseTest {

    private static final String ID = "aabbccddeeff112233445566";

    private IApiCallsStore apiCallsStore;
    private RestApiCallsStore store;

    @BeforeEach
    void setUp() {
        apiCallsStore = mock(IApiCallsStore.class);
        store = new RestApiCallsStore(apiCallsStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class));
    }

    @Test
    void createIsRefused() throws Exception {
        Response response = store.createApiCalls(configWith("weather", true, null));

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("http call 'weather'"), response.getEntity().toString());
        assertTrue(response.getEntity().toString().contains("responseObjectName"), response.getEntity().toString());
        verify(apiCallsStore, never()).create(any());
    }

    @Test
    void updateIsRefused() throws Exception {
        Response response = store.updateApiCalls(ID, 1, configWith("weather", true, ""));

        assertEquals(400, response.getStatus());
        verify(apiCallsStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    void duplicateIsRefused() throws Exception {
        when(apiCallsStore.read(ID, 1)).thenReturn(configWith("weather", true, null));
        var created = mock(IResourceStore.IResourceId.class);
        when(created.getId()).thenReturn("bbccddeeff112233445566aa");
        when(created.getVersion()).thenReturn(1);
        when(apiCallsStore.create(any())).thenReturn(created);

        Response response = store.duplicateApiCalls(ID, 1);

        assertEquals(400, response.getStatus());
        verify(apiCallsStore, never()).create(any());
    }

    @Test
    void consistentConfigIsCreated() throws Exception {
        var created = mock(IResourceStore.IResourceId.class);
        when(created.getId()).thenReturn(ID);
        when(created.getVersion()).thenReturn(1);
        when(apiCallsStore.create(any())).thenReturn(created);

        Response response = store.createApiCalls(configWith("weather", true, "weather"));

        assertEquals(201, response.getStatus());
        verify(apiCallsStore).create(any());
    }

    private static ApiCallsConfiguration configWith(String name, boolean saveResponse, String responseObjectName) {
        ApiCall call = new ApiCall();
        call.setName(name);
        call.setSaveResponse(saveResponse);
        call.setResponseObjectName(responseObjectName);
        ApiCallsConfiguration configuration = new ApiCallsConfiguration();
        configuration.setHttpCalls(new ArrayList<>(List.of(call)));
        return configuration;
    }
}
