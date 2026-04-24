/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack.rest;

import ai.labs.eddi.integrations.slack.SlackChannelRouter;
import ai.labs.eddi.integrations.slack.SlackEventHandler;
import ai.labs.eddi.integrations.slack.SlackIntegrationConfig;
import ai.labs.eddi.integrations.slack.SlackSignatureVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestSlackWebhook}.
 */
class RestSlackWebhookTest {

    private SlackIntegrationConfig config;
    private SlackChannelRouter channelRouter;
    private SlackSignatureVerifier signatureVerifier;
    private SlackEventHandler eventHandler;
    private ObjectMapper objectMapper;
    private RestSlackWebhook webhook;

    @BeforeEach
    void setUp() {
        config = mock(SlackIntegrationConfig.class);
        channelRouter = mock(SlackChannelRouter.class);
        signatureVerifier = mock(SlackSignatureVerifier.class);
        eventHandler = mock(SlackEventHandler.class);
        objectMapper = new ObjectMapper();
        webhook = new RestSlackWebhook(config, channelRouter, signatureVerifier, eventHandler, objectMapper);
    }

    @Nested
    @DisplayName("When Slack integration is disabled")
    class Disabled {

        @Test
        @DisplayName("should return 404 when disabled")
        void returns404WhenDisabled() {
            when(config.enabled()).thenReturn(false);

            Response response = webhook.handleEvents("{}", "sig", "ts");

            assertEquals(404, response.getStatus());
        }
    }

    @Nested
    @DisplayName("Signature verification")
    class SignatureVerification {

        @Test
        @DisplayName("should return 403 when signature verification fails")
        void returns403OnBadSignature() {
            when(config.enabled()).thenReturn(true);
            when(channelRouter.getAllSigningSecrets()).thenReturn(Set.of("secret1"));
            when(signatureVerifier.verify("ts", "{}", "bad-sig", Set.of("secret1")))
                    .thenReturn(false);

            Response response = webhook.handleEvents("{}", "bad-sig", "ts");

            assertEquals(403, response.getStatus());
        }
    }

    @Nested
    @DisplayName("URL Verification challenge")
    class UrlVerification {

        @Test
        @DisplayName("should echo challenge for url_verification type")
        void echoesChallenge() throws Exception {
            when(config.enabled()).thenReturn(true);
            when(channelRouter.getAllSigningSecrets()).thenReturn(Set.of("secret1"));
            when(signatureVerifier.verify(anyString(), anyString(), anyString(), any()))
                    .thenReturn(true);

            String body = objectMapper.writeValueAsString(
                    Map.of("type", "url_verification", "challenge", "abc123"));

            Response response = webhook.handleEvents(body, "sig", "ts");

            assertEquals(200, response.getStatus());
            String entity = (String) response.getEntity();
            assertTrue(entity.contains("abc123"));
        }

        @Test
        @DisplayName("should handle null challenge gracefully")
        void handlesNullChallenge() throws Exception {
            when(config.enabled()).thenReturn(true);
            when(channelRouter.getAllSigningSecrets()).thenReturn(Set.of("s1"));
            when(signatureVerifier.verify(anyString(), anyString(), anyString(), any()))
                    .thenReturn(true);

            String body = objectMapper.writeValueAsString(
                    Map.of("type", "url_verification"));

            Response response = webhook.handleEvents(body, "sig", "ts");

            assertEquals(200, response.getStatus());
        }
    }

    @Nested
    @DisplayName("Event callbacks")
    class EventCallbacks {

        @Test
        @DisplayName("should delegate event_callback to handler and return 200")
        void delegatesEventCallback() throws Exception {
            when(config.enabled()).thenReturn(true);
            when(channelRouter.getAllSigningSecrets()).thenReturn(Set.of("s1"));
            when(signatureVerifier.verify(anyString(), anyString(), anyString(), any()))
                    .thenReturn(true);

            String body = objectMapper.writeValueAsString(Map.of(
                    "type", "event_callback",
                    "event_id", "ev-1",
                    "event", Map.of("type", "app_mention", "text", "hello")));

            Response response = webhook.handleEvents(body, "sig", "ts");

            assertEquals(200, response.getStatus());
            verify(eventHandler).handleEventAsync(eq("ev-1"), any());
        }

        @Test
        @DisplayName("should handle event_callback with null event gracefully")
        void handlesNullEvent() throws Exception {
            when(config.enabled()).thenReturn(true);
            when(channelRouter.getAllSigningSecrets()).thenReturn(Set.of("s1"));
            when(signatureVerifier.verify(anyString(), anyString(), anyString(), any()))
                    .thenReturn(true);

            // event_callback without "event" key
            String body = objectMapper.writeValueAsString(Map.of(
                    "type", "event_callback",
                    "event_id", "ev-1"));

            Response response = webhook.handleEvents(body, "sig", "ts");

            assertEquals(200, response.getStatus());
            verifyNoInteractions(eventHandler);
        }
    }

    @Nested
    @DisplayName("Unknown event types")
    class UnknownTypes {

        @Test
        @DisplayName("should return 200 for unknown event types")
        void returns200ForUnknown() throws Exception {
            when(config.enabled()).thenReturn(true);
            when(channelRouter.getAllSigningSecrets()).thenReturn(Set.of("s1"));
            when(signatureVerifier.verify(anyString(), anyString(), anyString(), any()))
                    .thenReturn(true);

            String body = objectMapper.writeValueAsString(Map.of("type", "unknown_type"));

            Response response = webhook.handleEvents(body, "sig", "ts");

            assertEquals(200, response.getStatus());
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should return 400 for invalid JSON")
        void returns400ForBadJson() {
            when(config.enabled()).thenReturn(true);
            when(channelRouter.getAllSigningSecrets()).thenReturn(Set.of("s1"));
            when(signatureVerifier.verify(anyString(), anyString(), anyString(), any()))
                    .thenReturn(true);

            Response response = webhook.handleEvents("not-json{{{", "sig", "ts");

            assertEquals(400, response.getStatus());
        }
    }
}
