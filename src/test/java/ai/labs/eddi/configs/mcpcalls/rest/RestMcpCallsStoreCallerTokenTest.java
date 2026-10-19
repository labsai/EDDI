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
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.security.CallerTokenAuthorPolicy;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An MCP server {@code apiKey} of {@code ${caller:token}} needs an author role.
 */
class RestMcpCallsStoreCallerTokenTest {

    private static final String ID = "aabbccddee1122334455";

    private IMcpCallsStore store;
    private RestMcpCallsStore rest;
    private SecurityIdentity identity;

    @BeforeEach
    void setUp() throws Exception {
        store = mock(IMcpCallsStore.class);
        rest = new RestMcpCallsStore(store, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(McpToolProviderManager.class), mock(ResourceAccessGuard.class));
        identity = mock(SecurityIdentity.class);
        when(identity.hasRole("eddi-editor")).thenReturn(true);
        var mapper = new ObjectMapper();
        var serialization = mock(IJsonSerialization.class);
        when(serialization.serialize(any())).thenAnswer(i -> mapper.writeValueAsString(i.getArgument(0)));
        rest.callerTokenAuthorPolicy = new CallerTokenAuthorPolicy(identity, serialization, true, List.of("eddi-admin"));

        var current = mock(IResourceStore.IResourceId.class);
        when(current.getId()).thenReturn(ID);
        when(current.getVersion()).thenReturn(1);
        when(store.getCurrentResourceId(ID)).thenReturn(current);
        when(store.create(any())).thenReturn(current);
        when(store.update(anyString(), anyInt(), any())).thenReturn(2);
    }

    private static McpCallsConfiguration config(String apiKey) {
        var config = new McpCallsConfiguration();
        config.setName("eddi-self");
        config.setMcpServerUrl("http://127.0.0.1:7070/mcp");
        config.setTransport("http");
        config.setApiKey(apiKey);
        return config;
    }

    @Test
    @DisplayName("create, update and duplicate with apiKey ${caller:token} are refused for an editor")
    void refusedForEditor() throws Exception {
        assertThrows(ForbiddenException.class, () -> rest.createMcpCalls(config("${caller:token}")));
        assertThrows(ForbiddenException.class, () -> rest.updateMcpCalls(ID, 1, config("${caller:token}")));
        when(store.read(ID, 1)).thenReturn(config("${caller:token}"));
        assertThrows(ForbiddenException.class, () -> rest.duplicateMcpCalls(ID, 1));
        verify(store, never()).create(any());
        verify(store, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("an admin may save it; a vault reference needs no author role")
    void allowedCases() {
        assertDoesNotThrow(() -> rest.createMcpCalls(config("${vault:mcp-key}")));
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        assertDoesNotThrow(() -> rest.createMcpCalls(config("${caller:token}")));
    }
}
