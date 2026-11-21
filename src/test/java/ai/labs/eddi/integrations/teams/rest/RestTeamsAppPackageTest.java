/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams.rest;

import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestTeamsAppPackageTest {

    static final String APP = "11111111-2222-3333-4444-555555555555";

    private final ObjectMapper mapper = new ObjectMapper();

    private RestTeamsAppPackage endpoint(String type, String appId) {
        IRestChannelIntegrationStore store = mock(IRestChannelIntegrationStore.class);
        var integration = new ChannelIntegrationConfiguration();
        integration.setName("helpdesk");
        integration.setChannelType(type);
        Map<String, String> pc = new HashMap<>();
        pc.put("appId", appId);
        integration.setPlatformConfig(pc);
        when(store.readChannel("abc", 1)).thenReturn(integration);
        return new RestTeamsAppPackage(store, mapper);
    }

    @Test
    void packageHoldsManifestAndIcons() throws Exception {
        var response = endpoint("teams", APP.toUpperCase()).appPackage("abc", 1, "EDDI", "https://eddi.technology", null, null, "#4F46E5");
        byte[] zip = (byte[]) response.getEntity();
        Map<String, byte[]> entries = new HashMap<>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        assertEquals(3, entries.size());
        JsonNode manifest = mapper.readTree(entries.get("manifest.json"));
        assertEquals("1.29", manifest.get("manifestVersion").asText());
        assertEquals(APP, manifest.get("id").asText());
        assertEquals(APP, manifest.get("bots").get(0).get("botId").asText());
        assertEquals("https://eddi.technology", manifest.get("developer").get("privacyUrl").asText());
        assertNotNull(entries.get("color.png"));
        assertNotNull(entries.get("outline.png"));
    }

    @Test
    void refusesOtherTypesAndPlainHttp() {
        assertThrows(BadRequestException.class,
                () -> endpoint("slack", APP).appPackage("abc", 1, "EDDI", "https://eddi.technology", null, null, "#4F46E5"));
        assertThrows(BadRequestException.class,
                () -> endpoint("teams", APP).appPackage("abc", 1, "EDDI", "http://eddi.technology", null, null, "#4F46E5"));
        assertThrows(BadRequestException.class,
                () -> endpoint("teams", APP).appPackage("abc", 1, "EDDI", "https://eddi.technology", null, null, "red"));
    }

    @Test
    void authMechanismCoversOnlyThePostToTheMessagingEndpoint() {
        assertTrue(TeamsWebhookAuthMechanism.appliesTo(context(HttpMethod.POST, "/integrations/teams/messages")));
        assertFalse(TeamsWebhookAuthMechanism.appliesTo(context(HttpMethod.GET, "/integrations/teams/messages")));
        assertFalse(TeamsWebhookAuthMechanism.appliesTo(context(HttpMethod.POST, "/integrations/teams/messages/x")));
        assertFalse(TeamsWebhookAuthMechanism.appliesTo(context(HttpMethod.POST, "/channelstore/teams/abc/app-package")));
        assertFalse(TeamsWebhookAuthMechanism.appliesTo(context(HttpMethod.POST, "/integrations/teams/../../agentstore/agents")));
    }

    private static RoutingContext context(HttpMethod method, String path) {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(method);
        when(context.request()).thenReturn(request);
        when(context.normalizedPath()).thenReturn(path);
        return context;
    }
}
