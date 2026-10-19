/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1 of the 2026-10-17 security review: an editor read an MCP server's
 * plaintext {@code apiKey} verbatim from {@code GET
 * /mcpcallsstore/mcpcalls/{id}}.
 */
class RestMcpCallsStoreReadRedactionTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private IMcpCallsStore store;
    private ResourceAccessGuard accessGuard;
    private RestMcpCallsStore rest;

    @BeforeEach
    void setUp() {
        store = mock(IMcpCallsStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestMcpCallsStore(store, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), mock(McpToolProviderManager.class),
                accessGuard);
    }

    private static McpCallsConfiguration config(String apiKey) {
        var config = new McpCallsConfiguration();
        config.setMcpServerUrl("https://mcp.example.com/mcp");
        config.setApiKey(apiKey);
        return config;
    }

    private McpCallsConfiguration read(McpCallsConfiguration stored, boolean admin) throws Exception {
        when(store.read(ID, 2)).thenReturn(stored);
        when(accessGuard.isAdmin()).thenReturn(admin);
        return rest.readMcpCalls(ID, 2);
    }

    @Test
    @DisplayName("an editor reads the plaintext apiKey redacted, the rest untouched, the stored object unchanged")
    void editorReadsRedacted() throws Exception {
        McpCallsConfiguration stored = config("mcp-literal-key-123");

        McpCallsConfiguration seen = read(stored, false);

        assertEquals(ConfigSecretRedactor.REDACTED, seen.getApiKey());
        assertEquals("https://mcp.example.com/mcp", seen.getMcpServerUrl());
        assertEquals("mcp-literal-key-123", stored.getApiKey());
    }

    @Test
    @DisplayName("an administrator reads the document as stored")
    void adminReadsRaw() throws Exception {
        McpCallsConfiguration stored = config("mcp-literal-key-123");
        assertSame(stored, read(stored, true));
    }

    @Test
    @DisplayName("a vault reference is not a secret and reaches the editor unchanged")
    void referencePasses() throws Exception {
        McpCallsConfiguration stored = config("${vault:mcp-key}");
        assertSame(stored, read(stored, false));
    }
}
