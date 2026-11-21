/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.integrations.channels.ChannelConnectionCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamsConnectorClientTest {

    static final String SERVICE_URL = "https://smba.trafficmanager.net/emea/";

    private OutboundHttp http;
    private ChannelConnectionCredentials credentials;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Long> sleeps = new ArrayList<>();
    private TeamsConnectorClient client;
    private ChannelIntegrationConfiguration integration;

    @BeforeEach
    void setUp() {
        http = mock(OutboundHttp.class);
        credentials = mock(ChannelConnectionCredentials.class);
        when(credentials.resolve(any(), any())).thenReturn(new ResolvedCredential("Authorization", "Bearer t"));
        client = new TeamsConnectorClient(http, credentials, new ObjectMapper(), now::get, millis -> {
            sleeps.add(millis);
            now.addAndGet(millis);
        });
        integration = new ChannelIntegrationConfiguration();
        integration.setName("teams");
        integration.setPlatformConfig(Map.of("appId", "11111111-2222-3333-4444-555555555555"));
    }

    @SuppressWarnings("unchecked")
    private void respond(int... statuses) throws Exception {
        var stubbing = when(http.send(any(OutboundRequest.class), any(BodyMode.class)));
        for (int status : statuses) {
            stubbing = stubbing.thenReturn(new OutboundResponse<>(status, "", Map.of(), URI.create(SERVICE_URL), List.of(),
                    "{\"id\":\"m" + status + "\"}", false));
        }
    }

    @Test
    @DisplayName("a reply goes to the conversation's activity, with a credential resolved for that URL")
    void reply() throws Exception {
        respond(201);
        assertEquals("m201", client.sendText(integration, SERVICE_URL, "19:a@thread.tacv2;messageid=1", "act 1", "hi"));
        URI expected = URI.create(SERVICE_URL + "v3/conversations/19%3Aa%40thread.tacv2%3Bmessageid%3D1/activities/act%201");
        verify(credentials).resolve(integration, expected);
        ArgumentCaptor<OutboundRequest> request = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(http).send(request.capture(), any());
        assertEquals(expected, request.getValue().uri());
    }

    @Test
    @DisplayName("429, 412, 502 and 504 are retried with backoff; the credential is resolved per attempt")
    void retries() throws Exception {
        respond(429, 412, 502, 200);
        client.sendText(integration, SERVICE_URL, "a:1", null, "hi");
        verify(credentials, times(4)).resolve(any(), any());
        assertEquals(3, sleeps.size());
        assertTrue(sleeps.get(0) >= 1_600 && sleeps.get(0) <= 2_400, "first backoff around 2 s: " + sleeps);
    }

    @Test
    @DisplayName("retries give up after three; other errors are not retried")
    void givesUp() throws Exception {
        respond(504, 504, 504, 504);
        assertThrows(TeamsDeliveryException.class, () -> client.sendText(integration, SERVICE_URL, "a:1", null, "hi"));
        verify(http, times(4)).send(any(), any());
        respond(403);
        assertThrows(TeamsDeliveryException.class, () -> client.sendText(integration, SERVICE_URL, "a:2", null, "hi"));
    }

    @Test
    @DisplayName("eight sends in one second wait for the 7/s window")
    void pacingPerSecond() throws Exception {
        respond(200);
        for (int i = 0; i < 8; i++) {
            client.sendText(integration, SERVICE_URL, "a:1", null, "hi");
        }
        assertEquals(1, sleeps.size());
        assertTrue(sleeps.get(0) <= 1_001);
    }

    @Test
    @DisplayName("the hourly cap refuses instead of waiting")
    void hourlyCap() {
        for (int i = 0; i < 1_800; i++) {
            client.pace("k");
            now.addAndGet(1_000);
        }
        assertThrows(TeamsDeliveryException.class, () -> client.pace("k"));
    }

    @Test
    void serviceUrlMustBeHttps() {
        assertThrows(TeamsDeliveryException.class, () -> TeamsConnectorClient.activityUri("http://smba.trafficmanager.net/", "a:1", null));
        assertThrows(TeamsDeliveryException.class, () -> TeamsConnectorClient.activityUri("https://u:p@host/", "a:1", null));
    }

    @Test
    void chunking() {
        assertEquals(List.of(), TeamsConnectorClient.chunk(" "));
        String longText = "x".repeat(TeamsConnectorClient.MAX_CHUNK_CHARS) + "y";
        var chunks = TeamsConnectorClient.chunk(longText);
        assertEquals(2, chunks.size());
        assertEquals(longText, String.join("", chunks));
        String lines = ("a".repeat(30_000) + "\n").repeat(3);
        var lineChunks = TeamsConnectorClient.chunk(lines);
        assertTrue(lineChunks.get(0).endsWith("\n"));
        assertEquals(lines, String.join("", lineChunks));
        String emoji = "x".repeat(TeamsConnectorClient.MAX_CHUNK_CHARS - 1) + "😀";
        assertEquals(emoji, String.join("", TeamsConnectorClient.chunk(emoji)));
        assertTrue(Character.isHighSurrogate(TeamsConnectorClient.chunk(emoji).get(1).charAt(0)));
    }

    @Test
    @DisplayName("messages are sent as extendedmarkdown")
    void textFormat() throws Exception {
        respond(200);
        client.sendText(integration, SERVICE_URL, "a:1", null, "**hi**");
        ArgumentCaptor<OutboundRequest> request = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(http).send(request.capture(), any());
        assertTrue(String.valueOf(request.getValue().body()).contains("extendedmarkdown"), String.valueOf(request.getValue().body()));
    }
}
