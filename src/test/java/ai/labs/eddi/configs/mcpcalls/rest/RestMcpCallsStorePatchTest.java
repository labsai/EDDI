/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.patch.ConfigJsonPatcher;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RestMcpCallsStore PATCH")
class RestMcpCallsStorePatchTest {

    private static final String ID = "5f1c0f3a9d8e4b2a7c6d1e0f";

    private IMcpCallsStore store;
    private RestMcpCallsStore rest;

    @BeforeEach
    void setUp() throws Exception {
        var mapper = new ObjectMapper();
        store = mock(IMcpCallsStore.class);
        rest = new RestMcpCallsStore(store, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(McpToolProviderManager.class), mock(ResourceAccessGuard.class));
        rest.jsonPatcher = new ConfigJsonPatcher(mapper, new StrictConfigurationParser(mapper));

        var current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(1);
        when(store.getCurrentResourceId(ID)).thenReturn(current);
        when(store.read(ID, 1)).thenReturn(mapper.readValue(
                "{\"mcpServerUrl\":\"https://example.invalid/mcp\",\"name\":\"s\",\"apiKey\":\"${vault:mcp-key}\"}", McpCallsConfiguration.class));
        when(store.update(eq(ID), eq(1), any())).thenReturn(2);
    }

    @Test
    @DisplayName("edits a field and keeps the stored credential reference")
    void patches() throws Exception {
        var response = rest.patchMcpCalls(ID, 1, "[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"renamed\"}]");

        assertEquals(200, response.getStatus());
        var written = ArgumentCaptor.forClass(McpCallsConfiguration.class);
        verify(store).update(eq(ID), eq(1), written.capture());
        assertEquals("renamed", written.getValue().getName());
        assertEquals("${vault:mcp-key}", written.getValue().getApiKey());
    }

    @Test
    @DisplayName("runs the same pre-write validation as PUT: a patch that leaves no URL is a 400")
    void validatedLikeAPut() throws Exception {
        assertThrows(BadRequestException.class, () -> rest.patchMcpCalls(ID, 1, "[{\"op\":\"remove\",\"path\":\"/mcpServerUrl\"}]"));
        verify(store, never()).update(anyString(), anyInt(), any());
    }
}
