package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.engine.model.BotDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.Deployment.Status;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for McpAdminTools — MCP tools for bot administration.
 */
class McpAdminToolsTest {

    private static final String BOT_ID = "test-bot-id";

    private IRestBotAdministration botAdmin;
    private IRestBotStore botStore;
    private IRestPackageStore packageStore;
    private IJsonSerialization jsonSerialization;
    private McpAdminTools tools;

    @BeforeEach
    void setUp() throws IOException {
        botAdmin = mock(IRestBotAdministration.class);
        botStore = mock(IRestBotStore.class);
        packageStore = mock(IRestPackageStore.class);
        jsonSerialization = mock(IJsonSerialization.class);
        // Default stub for error serialization path — overridden by specific tests
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        tools = new McpAdminTools(botAdmin, botStore, packageStore, jsonSerialization);
    }

    // --- deployBot ---

    @Test
    void deployBot_success() throws IOException {
        when(botAdmin.deployBot(Environment.unrestricted, BOT_ID, 1, true))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deployed\"}");

        String result = tools.deployBot(BOT_ID, "1", "unrestricted");

        assertNotNull(result);
        verify(botAdmin).deployBot(Environment.unrestricted, BOT_ID, 1, true);
    }

    @Test
    void deployBot_defaultsToUnrestricted() throws IOException {
        when(botAdmin.deployBot(Environment.unrestricted, BOT_ID, 2, true))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deployed\"}");

        tools.deployBot(BOT_ID, "2", null);

        verify(botAdmin).deployBot(Environment.unrestricted, BOT_ID, 2, true);
    }

    @Test
    void deployBot_handlesException() throws IOException {
        when(botAdmin.deployBot(any(), any(), anyInt(), anyBoolean()))
                .thenThrow(new RuntimeException("Deploy failed"));
        when(jsonSerialization.serialize(any())).thenReturn("{\"error\":\"Failed to deploy bot: Deploy failed\"}");

        String result = tools.deployBot(BOT_ID, "1", null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Deploy failed"));
    }

    // --- undeployBot ---

    @Test
    void undeployBot_success() throws IOException {
        when(botAdmin.undeployBot(Environment.unrestricted, BOT_ID, 1, false, false))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"undeployed\"}");

        String result = tools.undeployBot(BOT_ID, "1", "unrestricted", "false");

        assertNotNull(result);
        verify(botAdmin).undeployBot(Environment.unrestricted, BOT_ID, 1, false, false);
    }

    @Test
    void undeployBot_withEndConversations() throws IOException {
        when(botAdmin.undeployBot(Environment.unrestricted, BOT_ID, 1, true, false))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"undeployed\"}");

        tools.undeployBot(BOT_ID, "1", null, "true");

        verify(botAdmin).undeployBot(Environment.unrestricted, BOT_ID, 1, true, false);
    }

    // --- getDeploymentStatus ---

    @Test
    void getDeploymentStatus_returnsStatus() throws IOException {
        var status = new BotDeploymentStatus(Environment.unrestricted, BOT_ID, 1,
                Status.READY, null);
        when(botAdmin.getDeploymentStatus(Environment.unrestricted, BOT_ID, 1, "json"))
                .thenReturn(Response.ok(status).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"status\":\"READY\"}");

        String result = tools.getDeploymentStatus(BOT_ID, "1", "unrestricted");

        assertNotNull(result);
        verify(botAdmin).getDeploymentStatus(Environment.unrestricted, BOT_ID, 1, "json");
    }

    // --- listPackages ---

    @Test
    void listPackages_returnsDescriptors() throws IOException {
        when(packageStore.readPackageDescriptors("", 0, 20))
                .thenReturn(List.of(new DocumentDescriptor()));
        when(jsonSerialization.serialize(any())).thenReturn("[{\"name\":\"TestPkg\"}]");

        String result = tools.listPackages(null, null);

        assertNotNull(result);
        verify(packageStore).readPackageDescriptors("", 0, 20);
    }

    @Test
    void listPackages_withFilterAndLimit() throws IOException {
        when(packageStore.readPackageDescriptors("greetings", 0, 10))
                .thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any())).thenReturn("[]");

        tools.listPackages("greetings", "10");

        verify(packageStore).readPackageDescriptors("greetings", 0, 10);
    }

    // --- createBot ---

    @Test
    void createBot_returnsLocationUri() throws IOException {
        when(botStore.createBot(any(BotConfiguration.class)))
                .thenReturn(Response.created(java.net.URI.create("/botstore/bots/" + BOT_ID))
                        .build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\",\"location\":\"/botstore/bots/test-bot-id\"}");

        String result = tools.createBot("My Bot", "Test description", null);

        assertNotNull(result);
        assertTrue(result.contains("created"));
        verify(botStore).createBot(any(BotConfiguration.class));
    }

    // --- deleteBot ---

    @Test
    void deleteBot_success() throws IOException {
        when(botStore.deleteBot(BOT_ID, 1, false, false))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        String result = tools.deleteBot(BOT_ID, "1", "false", "false");

        assertNotNull(result);
        verify(botStore).deleteBot(BOT_ID, 1, false, false);
    }

    @Test
    void deleteBot_permanentCascade() throws IOException {
        when(botStore.deleteBot(BOT_ID, 2, true, true))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        tools.deleteBot(BOT_ID, "2", "true", "true");

        verify(botStore).deleteBot(BOT_ID, 2, true, true);
    }

    @Test
    void deleteBot_handlesException() throws IOException {
        when(botStore.deleteBot(any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenThrow(new RuntimeException("Not found"));
        when(jsonSerialization.serialize(any())).thenReturn("{\"error\":\"Failed to delete bot: Not found\"}");

        String result = tools.deleteBot(BOT_ID, "1", null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Not found"));
    }
}
