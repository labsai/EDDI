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
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code McpCallsTask.configure()} was changed to only <em>log</em> an invalid
 * stored configuration instead of throwing, because throwing at workflow load
 * made one bad MCP config take down an entire already-deployed workflow.
 * <p>
 * That leniency is only safe if the strict check moved rather than vanished.
 * These tests pin the write boundary, which is the half that makes the
 * load-time leniency defensible — without them, "we relaxed load-time
 * validation" and "we stopped validating" are indistinguishable.
 */
class RestMcpCallsStoreWriteValidationTest {

    private static final String MCP_ID = "aabbccddee1122334455";

    private IMcpCallsStore mcpCallsStore;
    private RestMcpCallsStore restMcpCallsStore;

    @BeforeEach
    void setUp() throws Exception {
        mcpCallsStore = mock(IMcpCallsStore.class);
        restMcpCallsStore = new RestMcpCallsStore(mcpCallsStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(McpToolProviderManager.class));

        when(mcpCallsStore.create(any())).thenReturn(resourceId(MCP_ID, 1));
        when(mcpCallsStore.update(anyString(), anyInt(), any())).thenReturn(2);
    }

    @Test
    @DisplayName("create refuses a non-http MCP server URL instead of storing it")
    void createRefusesNonHttpUrl() throws Exception {
        var config = validConfig();
        config.setMcpServerUrl("ftp://mcp.example.com/tools");

        var thrown = assertThrows(BadRequestException.class, () -> restMcpCallsStore.createMcpCalls(config));

        assertTrue(thrown.getMessage().contains("http"), thrown.getMessage());
        verify(mcpCallsStore, never()).create(any());
    }

    @Test
    @DisplayName("create refuses a missing MCP server URL instead of storing it")
    void createRefusesMissingUrl() throws Exception {
        var config = validConfig();
        config.setMcpServerUrl("   ");

        var thrown = assertThrows(BadRequestException.class, () -> restMcpCallsStore.createMcpCalls(config));

        assertTrue(thrown.getMessage().contains("mcpServerUrl"), thrown.getMessage());
        verify(mcpCallsStore, never()).create(any());
    }

    @Test
    @DisplayName("create refuses a transport the client cannot speak instead of storing it")
    void createRefusesUnsupportedTransport() throws Exception {
        var config = validConfig();
        config.setTransport("stdio");

        var thrown = assertThrows(BadRequestException.class, () -> restMcpCallsStore.createMcpCalls(config));

        assertTrue(thrown.getMessage().contains("stdio"), thrown.getMessage());
        verify(mcpCallsStore, never()).create(any());
    }

    @Test
    @DisplayName("update refuses an invalid configuration instead of overwriting a good one")
    void updateRefusesInvalidConfiguration() throws Exception {
        var config = validConfig();
        config.setMcpServerUrl("not-a-url");

        assertThrows(BadRequestException.class, () -> restMcpCallsStore.updateMcpCalls(MCP_ID, 1, config));

        verify(mcpCallsStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a valid configuration still reaches the store")
    void validConfigurationIsStored() throws Exception {
        var response = restMcpCallsStore.createMcpCalls(validConfig());

        assertEquals(201, response.getStatus());
        verify(mcpCallsStore).create(any());
    }

    @Test
    @DisplayName("a valid update still reaches the store")
    void validUpdateIsStored() throws Exception {
        var response = restMcpCallsStore.updateMcpCalls(MCP_ID, 1, validConfig());

        assertEquals(200, response.getStatus());
        verify(mcpCallsStore).update(anyString(), anyInt(), any());
    }

    private static McpCallsConfiguration validConfig() {
        var config = new McpCallsConfiguration();
        config.setName("weather-tools");
        config.setMcpServerUrl("https://mcp.example.com/tools");
        config.setTransport("http");
        return config;
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }
}
