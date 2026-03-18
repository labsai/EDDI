package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.engine.model.BotDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.Deployment.Status;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private IRestDocumentDescriptorStore descriptorStore;
    private IJsonSerialization jsonSerialization;
    private McpAdminTools tools;

    @BeforeEach
    void setUp() throws IOException {
        botAdmin = mock(IRestBotAdministration.class);
        botStore = mock(IRestBotStore.class);
        packageStore = mock(IRestPackageStore.class);
        descriptorStore = mock(IRestDocumentDescriptorStore.class);
        jsonSerialization = mock(IJsonSerialization.class);
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        tools = new McpAdminTools(botAdmin, botStore, packageStore, descriptorStore, jsonSerialization);
    }

    // --- deployBot ---

    @Test
    void deployBot_success() throws IOException {
        when(botAdmin.deployBot(Environment.unrestricted, BOT_ID, 1, true, true))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deployed\"}");

        String result = tools.deployBot(BOT_ID, 1, "unrestricted");

        assertNotNull(result);
        verify(botAdmin).deployBot(Environment.unrestricted, BOT_ID, 1, true, true);
    }

    @Test
    void deployBot_defaultsToUnrestricted() throws IOException {
        when(botAdmin.deployBot(Environment.unrestricted, BOT_ID, 2, true, true))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deployed\"}");

        tools.deployBot(BOT_ID, 2, null);

        verify(botAdmin).deployBot(Environment.unrestricted, BOT_ID, 2, true, true);
    }

    @Test
    void deployBot_handlesException() {
        when(botAdmin.deployBot(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenThrow(new RuntimeException("Deploy failed"));

        String result = tools.deployBot(BOT_ID, 1, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Failed to deploy bot"));
    }

    // --- undeployBot ---

    @Test
    void undeployBot_success() throws IOException {
        when(botAdmin.undeployBot(Environment.unrestricted, BOT_ID, 1, false, false))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"undeployed\"}");

        String result = tools.undeployBot(BOT_ID, 1, "unrestricted", false);

        assertNotNull(result);
        verify(botAdmin).undeployBot(Environment.unrestricted, BOT_ID, 1, false, false);
    }

    @Test
    void undeployBot_withEndConversations() throws IOException {
        when(botAdmin.undeployBot(Environment.unrestricted, BOT_ID, 1, true, false))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"undeployed\"}");

        tools.undeployBot(BOT_ID, 1, null, true);

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

        String result = tools.getDeploymentStatus(BOT_ID, 1, "unrestricted");

        assertNotNull(result);
        verify(botAdmin).getDeploymentStatus(Environment.unrestricted, BOT_ID, 1, "json");
    }

    @Test
    void getDeploymentStatus_nullEntity_returnsFallback() throws IOException {
        when(botAdmin.getDeploymentStatus(Environment.unrestricted, BOT_ID, 1, "json"))
                .thenReturn(Response.noContent().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"status_check\",\"httpStatus\":204}");

        String result = tools.getDeploymentStatus(BOT_ID, 1, "unrestricted");

        assertNotNull(result);
        // Should not throw on null entity
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

        tools.listPackages("greetings", 10);

        verify(packageStore).readPackageDescriptors("greetings", 0, 10);
    }

    // --- createBot ---

    @Test
    void createBot_createsAndPatchesDescriptor() throws IOException {
        when(botStore.createBot(any(BotConfiguration.class)))
                .thenReturn(Response.created(java.net.URI.create("/botstore/bots/" + BOT_ID + "?version=1"))
                        .build());
        when(jsonSerialization.serialize(any()))
                .thenReturn("{\"action\":\"created\",\"botId\":\"test-bot-id\",\"name\":\"My Bot\"}");

        String result = tools.createBot("My Bot", "Test description", null);

        assertNotNull(result);
        assertTrue(result.contains("created"));
        verify(botStore).createBot(any(BotConfiguration.class));

        // Verify descriptor was patched with name and description
        @SuppressWarnings("unchecked")
        ArgumentCaptor<PatchInstruction<DocumentDescriptor>> patchCaptor =
                ArgumentCaptor.forClass(PatchInstruction.class);
        verify(descriptorStore).patchDescriptor(eq(BOT_ID), eq(1), patchCaptor.capture());

        var patch = patchCaptor.getValue();
        assertEquals(PatchInstruction.PatchOperation.SET, patch.getOperation());
        assertEquals("My Bot", patch.getDocument().getName());
        assertEquals("Test description", patch.getDocument().getDescription());
    }

    @Test
    void createBot_withPackageUris() throws IOException {
        when(botStore.createBot(any(BotConfiguration.class)))
                .thenReturn(Response.created(java.net.URI.create("/botstore/bots/" + BOT_ID + "?version=1"))
                        .build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        tools.createBot("Bot", null, "eddi://ai.labs.package/packagestore/packages/pkg1?version=1");

        ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
        verify(botStore).createBot(configCaptor.capture());
        assertEquals(1, configCaptor.getValue().getPackages().size());
    }

    @Test
    void createBot_descriptorPatchFailure_stillReturnsSuccess() throws IOException {
        when(botStore.createBot(any(BotConfiguration.class)))
                .thenReturn(Response.created(java.net.URI.create("/botstore/bots/" + BOT_ID + "?version=1"))
                        .build());
        doThrow(new RuntimeException("Patch failed"))
                .when(descriptorStore).patchDescriptor(any(), anyInt(), any());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        String result = tools.createBot("My Bot", null, null);

        // Bot creation should still be reported as successful
        assertNotNull(result);
        assertTrue(result.contains("created"));
    }

    // --- deleteBot ---

    @Test
    void deleteBot_success() throws IOException {
        when(botStore.deleteBot(BOT_ID, 1, false, false))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        String result = tools.deleteBot(BOT_ID, 1, false, false);

        assertNotNull(result);
        verify(botStore).deleteBot(BOT_ID, 1, false, false);
    }

    @Test
    void deleteBot_permanentCascade() throws IOException {
        when(botStore.deleteBot(BOT_ID, 2, true, true))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        tools.deleteBot(BOT_ID, 2, true, true);

        verify(botStore).deleteBot(BOT_ID, 2, true, true);
    }

    @Test
    void deleteBot_handlesException() {
        when(botStore.deleteBot(any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenThrow(new RuntimeException("Not found"));

        String result = tools.deleteBot(BOT_ID, 1, null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Not found"));
    }
}
