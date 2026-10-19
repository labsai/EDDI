/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.patch.ConfigJsonPatcher;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.configs.rest.RedactionMarkerAssertions.assertRefusedNaming;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The httpcalls store refuses to store the read-redaction marker — whole (a
 * credential header) or in place (a key in {@code request.path}).
 */
class RestApiCallsStoreRedactionMarkerWriteTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private IApiCallsStore store;
    private ResourceAccessGuard accessGuard;
    private RestApiCallsStore rest;

    @BeforeEach
    void setUp() throws Exception {
        store = mock(IApiCallsStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestApiCallsStore(store, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), accessGuard);
        var mapper = new ObjectMapper();
        rest.jsonPatcher = new ConfigJsonPatcher(mapper, new StrictConfigurationParser(mapper));
        var current = mock(IResourceStore.IResourceId.class);
        when(current.getId()).thenReturn(ID);
        when(current.getVersion()).thenReturn(1);
        when(store.getCurrentResourceId(ID)).thenReturn(current);
        when(store.update(eq(ID), eq(1), any())).thenReturn(2);
    }

    private static ApiCallsConfiguration config(String authorization, String path) {
        var request = new Request();
        request.setMethod("get");
        request.setPath(path);
        request.setHeaders(new LinkedHashMap<>(Map.of("Authorization", authorization)));
        var call = new ApiCall();
        call.setName("search");
        call.setActions(List.of("search"));
        call.setRequest(request);
        var config = new ApiCallsConfiguration();
        config.setTargetServerUrl("https://api.example.com");
        config.setHttpCalls(List.of(call));
        return config;
    }

    @Test
    @DisplayName("an editor's read-then-save round trip is refused, naming every marked field")
    void editorRoundTripIsRefused() throws Exception {
        when(store.read(ID, 1)).thenReturn(config("Bearer literal-token", "/v1/search?key=AIzaSyLiteralLiteralLiteral"));
        ApiCallsConfiguration seen = rest.readApiCalls(ID, 1);

        assertRefusedNaming("httpCalls[0].request.headers.Authorization", () -> rest.updateApiCalls(ID, 1, seen));
        assertRefusedNaming("httpCalls[0].request.path", () -> rest.updateApiCalls(ID, 1, seen));
        verify(store, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("an administrator is refused the marker on create and patch, and a stored marker is not duplicated")
    void adminCreatePatchAndDuplicateAreRefused() throws Exception {
        when(accessGuard.isAdmin()).thenReturn(true);
        assertRefusedNaming("httpCalls[0].request.headers.Authorization",
                () -> rest.createApiCalls(config(ConfigSecretRedactor.REDACTED, "/v1")));

        when(store.read(ID, 1)).thenReturn(config("${vault:k}", "/v1"));
        String patch = "[{\"op\":\"replace\",\"path\":\"/httpCalls/0/request/path\",\"value\":\"/v1?key="
                + ConfigSecretRedactor.IN_STRING_REDACTED + "\"}]";
        assertRefusedNaming("httpCalls[0].request.path", () -> rest.patchApiCalls(ID, 1, patch));

        when(store.read(ID, 1)).thenReturn(config(ConfigSecretRedactor.REDACTED, "/v1"));
        assertRefusedNaming("httpCalls[0].request.headers.Authorization", () -> rest.duplicateApiCalls(ID, 1));
        verify(store, never()).create(any());
        verify(store, never()).update(anyString(), anyInt(), any());
    }
}
