/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static ai.labs.eddi.configs.rest.RedactionMarkerAssertions.assertRefusedNaming;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The MCP-calls store refuses to store the read-redaction marker. */
class RestMcpCallsStoreRedactionMarkerWriteTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private IMcpCallsStore store;
    private ResourceAccessGuard accessGuard;
    private RestMcpCallsStore rest;

    @BeforeEach
    void setUp() throws Exception {
        store = mock(IMcpCallsStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestMcpCallsStore(store, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), mock(McpToolProviderManager.class),
                accessGuard);
        var current = mock(IResourceStore.IResourceId.class);
        when(current.getId()).thenReturn(ID);
        when(current.getVersion()).thenReturn(1);
        when(store.getCurrentResourceId(ID)).thenReturn(current);
        when(store.update(eq(ID), eq(1), any())).thenReturn(2);
    }

    private static McpCallsConfiguration config(String apiKey) {
        var config = new McpCallsConfiguration();
        config.setMcpServerUrl("https://mcp.example.com/mcp");
        config.setApiKey(apiKey);
        return config;
    }

    @Test
    @DisplayName("an editor's read-then-save round trip is refused and nothing is written")
    void editorRoundTripIsRefused() throws Exception {
        when(store.read(ID, 1)).thenReturn(config("mcp-literal-key-123"));
        McpCallsConfiguration seen = rest.readMcpCalls(ID, 1);

        assertRefusedNaming("apiKey", () -> rest.updateMcpCalls(ID, 1, seen));
        verify(store, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("an administrator is refused the marker on create, and a stored marker is not duplicated")
    void adminCreateAndDuplicateAreRefused() throws Exception {
        when(accessGuard.isAdmin()).thenReturn(true);
        assertRefusedNaming("apiKey", () -> rest.createMcpCalls(config(ConfigSecretRedactor.REDACTED)));

        when(store.read(ID, 1)).thenReturn(config(ConfigSecretRedactor.REDACTED));
        assertRefusedNaming("apiKey", () -> rest.duplicateMcpCalls(ID, 1));
        verify(store, never()).create(any());
    }

    @Test
    @DisplayName("a reference is written")
    void cleanWritePasses() throws Exception {
        assertEquals(200, rest.updateMcpCalls(ID, 1, config("${vault:mcp}")).getStatus());
    }
}
