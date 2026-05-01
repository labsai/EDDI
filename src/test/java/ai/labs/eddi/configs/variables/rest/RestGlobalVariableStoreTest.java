/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables.rest;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestGlobalVariableStore} — CRUD operations with tenant
 * scoping, validation, and cache invalidation.
 */
class RestGlobalVariableStoreTest {

    private static final String DEFAULT = GlobalVariable.DEFAULT_TENANT;

    private IGlobalVariableStore store;
    private GlobalVariableResolver resolver;
    private RestGlobalVariableStore rest;

    @BeforeEach
    void setUp() {
        store = mock(IGlobalVariableStore.class);
        resolver = mock(GlobalVariableResolver.class);
        rest = new RestGlobalVariableStore(store, resolver);
    }

    @Test
    void listVariables() {
        var expected = List.of(new GlobalVariable("k", "v"));
        when(store.listAll(DEFAULT)).thenReturn(expected);
        assertEquals(expected, rest.listVariables(DEFAULT));
    }

    @Test
    void getVariableFound() {
        var gv = new GlobalVariable("k", "v");
        when(store.get(DEFAULT, "k")).thenReturn(gv);
        assertEquals(gv, rest.getVariable(DEFAULT, "k"));
    }

    @Test
    void getVariableNotFound() {
        when(store.get(DEFAULT, "missing")).thenReturn(null);
        assertThrows(NotFoundException.class, () -> rest.getVariable(DEFAULT, "missing"));
    }

    @Test
    void upsertVariable() {
        var input = new GlobalVariable(DEFAULT, "model", "gpt-4.1", "default model", true);
        Response response = rest.upsertVariable(DEFAULT, "model", input);
        assertEquals(200, response.getStatus());
        verify(store).upsert(any(GlobalVariable.class));
        verify(resolver).invalidateCache();
    }

    @Test
    void upsertVariablePathOverridesBody() {
        // Path tenantId + key should take precedence over body values
        var input = new GlobalVariable("wrong-tenant", "wrong-key", "val");
        rest.upsertVariable(DEFAULT, "correct-key", input);

        var captor = org.mockito.ArgumentCaptor.forClass(GlobalVariable.class);
        verify(store).upsert(captor.capture());
        assertEquals(DEFAULT, captor.getValue().tenantId());
        assertEquals("correct-key", captor.getValue().key());
    }

    @Test
    void upsertVariableInvalidKey() {
        var input = new GlobalVariable("bad key!", "val");
        assertThrows(IllegalArgumentException.class, () -> rest.upsertVariable(DEFAULT, "bad key!", input));
        verifyNoInteractions(store);
    }

    @Test
    void upsertVariableInvalidTenantId() {
        var input = new GlobalVariable("model", "val");
        assertThrows(IllegalArgumentException.class, () -> rest.upsertVariable("bad tenant!", "model", input));
        verifyNoInteractions(store);
    }

    @Test
    void deleteVariable() {
        Response response = rest.deleteVariable(DEFAULT, "k");
        assertEquals(204, response.getStatus());
        verify(store).delete(DEFAULT, "k");
        verify(resolver).invalidateCache();
    }

    @Test
    void validKeyPatterns() {
        for (String key : List.of("model", "default-model", "api.key", "version_2", "A-Z.test_123")) {
            assertDoesNotThrow(() -> rest.upsertVariable(DEFAULT, key, new GlobalVariable(key, "v")),
                    "Key should be valid: " + key);
        }
    }

    @Test
    void validTenantIdPatterns() {
        for (String tenant : List.of("default", "tenant-a", "org.example", "t_1")) {
            assertDoesNotThrow(() -> rest.upsertVariable(tenant, "key", new GlobalVariable("key", "v")),
                    "Tenant should be valid: " + tenant);
        }
    }

    @Test
    void invalidKeyPatterns() {
        for (String key : List.of("", "has space", "key=value", "key/path")) {
            assertThrows(Exception.class, () -> rest.upsertVariable(DEFAULT, key, new GlobalVariable(key, "v")),
                    "Key should be invalid: " + key);
        }
        assertThrows(Exception.class, () -> rest.upsertVariable(DEFAULT, null, new GlobalVariable(null, "v")),
                "Null key should be invalid");
    }
}
