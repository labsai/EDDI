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
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1 of the 2026-10-17 security review: an editor read a literal
 * {@code Authorization} header, and keys in {@code request.path} and
 * {@code request.body}, verbatim from {@code GET /apicallstore/apicalls/{id}}.
 */
class RestApiCallsStoreReadRedactionTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private IApiCallsStore store;
    private ResourceAccessGuard accessGuard;
    private RestApiCallsStore rest;

    @BeforeEach
    void setUp() {
        store = mock(IApiCallsStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestApiCallsStore(store, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), accessGuard);
    }

    private static ApiCallsConfiguration config() {
        var request = new Request();
        request.setMethod("post");
        request.setPath("/v1/search?key=AIzaSyLiteralLiteralLiteral&q={properties.q}&api_key=${vault:k}");
        request.setHeaders(new LinkedHashMap<>(Map.of("Authorization", "Bearer literal-token-value", "X-Api-Key", "acme-literal",
                "X-Trace", "${vars:trace}", "Accept", "application/json", "X-Ref", "Bearer ${vault:svc}")));
        request.setBody("{\"query\":\"{properties.q}\",\"password\":\"body-literal-password\"}");

        var call = new ApiCall();
        call.setName("search");
        call.setActions(List.of("search"));
        call.setParameters(new LinkedHashMap<>(Map.of("token", "The pagination token the previous page returned")));
        call.setRequest(request);

        var config = new ApiCallsConfiguration();
        config.setTargetServerUrl("https://svc:url-literal-pw@api.example.com");
        config.setHttpCalls(List.of(call));
        return config;
    }

    private ApiCallsConfiguration read(ApiCallsConfiguration stored, boolean admin) throws Exception {
        when(store.read(ID, 1)).thenReturn(stored);
        when(accessGuard.isAdmin()).thenReturn(admin);
        return rest.readApiCalls(ID, 1);
    }

    @Test
    @DisplayName("an editor reads literal credential headers, path keys, body secrets and userinfo redacted")
    void editorReadsRedacted() throws Exception {
        ApiCallsConfiguration stored = config();

        ApiCallsConfiguration seen = read(stored, false);
        Request request = seen.getHttpCalls().get(0).getRequest();

        assertEquals(ConfigSecretRedactor.REDACTED, request.getHeaders().get("Authorization"));
        assertEquals(ConfigSecretRedactor.REDACTED, request.getHeaders().get("X-Api-Key"));
        assertEquals("${vars:trace}", request.getHeaders().get("X-Trace"));
        assertEquals("Bearer ${vault:svc}", request.getHeaders().get("X-Ref"));
        assertEquals("application/json", request.getHeaders().get("Accept"));
        assertEquals("/v1/search?key=" + ConfigSecretRedactor.IN_STRING_REDACTED + "&q={properties.q}&api_key=${vault:k}", request.getPath());
        assertFalse(request.getBody().contains("body-literal-password"), request.getBody());
        assertTrue(request.getBody().contains("{properties.q}"), request.getBody());
        assertEquals("https://svc:" + ConfigSecretRedactor.IN_STRING_REDACTED + "@api.example.com", seen.getTargetServerUrl());
        assertEquals("The pagination token the previous page returned", seen.getHttpCalls().get(0).getParameters().get("token"),
                "tool parameter descriptions are not configuration");
        assertEquals("Bearer literal-token-value", stored.getHttpCalls().get(0).getRequest().getHeaders().get("Authorization"),
                "the stored object is never touched");
    }

    @Test
    @DisplayName("an administrator reads the document as stored")
    void adminReadsRaw() throws Exception {
        ApiCallsConfiguration stored = config();
        assertSame(stored, read(stored, true));
    }
}
