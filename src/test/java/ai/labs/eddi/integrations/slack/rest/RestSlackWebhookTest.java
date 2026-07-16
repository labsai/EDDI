/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack.rest;

import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.slack.SlackEventHandler;
import ai.labs.eddi.integrations.slack.SlackInteractivityHandler;
import ai.labs.eddi.integrations.slack.SlackSignatureVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestSlackWebhook}. Covers signature verification, URL
 * verification challenge, event dispatching, the interactivity endpoint, and
 * disabled/error paths.
 */
class RestSlackWebhookTest {

    private ChannelTargetRouter channelTargetRouter;
    private SlackSignatureVerifier signatureVerifier;
    private SlackEventHandler eventHandler;
    private SlackInteractivityHandler interactivityHandler;
    private ObjectMapper objectMapper;
    private RestSlackWebhook webhook;

    @BeforeEach
    void setUp() {
        channelTargetRouter = mock(ChannelTargetRouter.class);
        signatureVerifier = mock(SlackSignatureVerifier.class);
        eventHandler = mock(SlackEventHandler.class);
        interactivityHandler = mock(SlackInteractivityHandler.class);
        objectMapper = new ObjectMapper();

        webhook = new RestSlackWebhook(channelTargetRouter, signatureVerifier,
                eventHandler, interactivityHandler, objectMapper);
    }

    // ─── Signature verification ───────────────────────────────────────────────

    @Nested
    @DisplayName("Signature verification")
    class SignatureVerification {

        @Test
        @DisplayName("returns 403 when signature verification fails")
        void returns403OnBadSignature() {
            when(channelTargetRouter.getSigningSecrets("slack")).thenReturn(Set.of("secret"));
            when(signatureVerifier.verify(eq("ts"), eq("{}"), eq("bad-sig"), any()))
                    .thenReturn(false);

            Response response = webhook.handleEvents("{}", "bad-sig", "ts");

            assertEquals(403, response.getStatus());
            verifyNoInteractions(eventHandler);
        }
    }

    // ─── URL verification ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("URL verification challenge")
    class UrlVerification {

        @Test
        @DisplayName("echoes challenge for url_verification type")
        void echoesChallenge() {
            when(channelTargetRouter.getSigningSecrets("slack")).thenReturn(Set.of("secret"));
            when(signatureVerifier.verify(any(), any(), any(), any())).thenReturn(true);

            String body = "{\"type\":\"url_verification\",\"challenge\":\"abc123\"}";

            Response response = webhook.handleEvents(body, "sig", "ts");

            assertEquals(200, response.getStatus());
            String entity = (String) response.getEntity();
            assertTrue(entity.contains("abc123"));
        }

        @Test
        @DisplayName("handles null challenge gracefully")
        void handleNullChallenge() {
            when(channelTargetRouter.getSigningSecrets("slack")).thenReturn(Set.of("secret"));
            when(signatureVerifier.verify(any(), any(), any(), any())).thenReturn(true);

            String body = "{\"type\":\"url_verification\"}";

            Response response = webhook.handleEvents(body, "sig", "ts");

            assertEquals(200, response.getStatus());
        }
    }

    // ─── Event callback ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Event callback")
    class EventCallback {

        @Test
        @DisplayName("delegates event_callback to eventHandler")
        void delegatesEventCallback() {
            when(channelTargetRouter.getSigningSecrets("slack")).thenReturn(Set.of("secret"));
            when(signatureVerifier.verify(any(), any(), any(), any())).thenReturn(true);

            String body = "{\"type\":\"event_callback\",\"event_id\":\"evt-1\",\"event\":{\"type\":\"message\",\"text\":\"hello\"}}";

            Response response = webhook.handleEvents(body, "sig", "ts");

            assertEquals(200, response.getStatus());
            verify(eventHandler).handleEventAsync(eq("evt-1"), any());
        }

        @Test
        @DisplayName("returns 200 even when event is null")
        void nullEvent() {
            when(channelTargetRouter.getSigningSecrets("slack")).thenReturn(Set.of("secret"));
            when(signatureVerifier.verify(any(), any(), any(), any())).thenReturn(true);

            String body = "{\"type\":\"event_callback\",\"event_id\":\"evt-1\"}";

            Response response = webhook.handleEvents(body, "sig", "ts");

            assertEquals(200, response.getStatus());
            verifyNoInteractions(eventHandler);
        }

        @Test
        @DisplayName("unknown type returns 200 (Slack expects it)")
        void unknownType() {
            when(channelTargetRouter.getSigningSecrets("slack")).thenReturn(Set.of("secret"));
            when(signatureVerifier.verify(any(), any(), any(), any())).thenReturn(true);

            String body = "{\"type\":\"something_else\"}";

            Response response = webhook.handleEvents(body, "sig", "ts");

            assertEquals(200, response.getStatus());
        }
    }

    // ─── Malformed payload ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Malformed payload")
    class MalformedPayload {

        @Test
        @DisplayName("returns 400 for invalid JSON")
        void invalidJson() {
            when(channelTargetRouter.getSigningSecrets("slack")).thenReturn(Set.of("secret"));
            when(signatureVerifier.verify(any(), any(), any(), any())).thenReturn(true);

            Response response = webhook.handleEvents("not json", "sig", "ts");

            assertEquals(400, response.getStatus());
        }
    }

    // ─── Interactivity endpoint ───────────────────────────────────────────────

    @Nested
    @DisplayName("Interactivity endpoint")
    class Interactivity {

        private static final String FORM_BODY = "payload=%7B%22type%22%3A%22block_actions%22%7D"; // {"type":"block_actions"}
        private static final String DECODED = "{\"type\":\"block_actions\"}";

        @Test
        @DisplayName("returns 403 + does not verify/dispatch when decision cannot be bound to an integration")
        void rejectsUnbindableDecision() {
            // Legacy/unknown decision → no owning secret → reject before any verify.
            when(interactivityHandler.resolveSigningSecretForDecision(DECODED)).thenReturn(null);

            Response response = webhook.handleInteractive(FORM_BODY, "sig", "ts");

            assertEquals(403, response.getStatus());
            verify(signatureVerifier, never()).verifyWithSecret(any(), any(), any(), any());
            verify(interactivityHandler, never()).handlePayloadAsync(any());
        }

        @Test
        @DisplayName("returns 403 + does not dispatch when signature does not match the owning secret")
        void rejectsBadSignature() {
            when(interactivityHandler.resolveSigningSecretForDecision(DECODED)).thenReturn("owning-secret");
            when(signatureVerifier.verifyWithSecret("ts", FORM_BODY, "bad-sig", "owning-secret"))
                    .thenReturn(false);

            Response response = webhook.handleInteractive(FORM_BODY, "bad-sig", "ts");

            assertEquals(403, response.getStatus());
            verify(interactivityHandler, never()).handlePayloadAsync(any());
        }

        @Test
        @DisplayName("returns 403 when signature header is absent")
        void rejectsAbsentSignature() {
            when(interactivityHandler.resolveSigningSecretForDecision(DECODED)).thenReturn("owning-secret");
            when(signatureVerifier.verifyWithSecret(isNull(), eq(FORM_BODY), isNull(), eq("owning-secret")))
                    .thenReturn(false);

            Response response = webhook.handleInteractive(FORM_BODY, null, null);

            assertEquals(403, response.getStatus());
            verify(interactivityHandler, never()).handlePayloadAsync(any());
        }

        @Test
        @DisplayName("verifies raw body against the OWNING secret, then dispatches decoded payload async")
        void dispatchesDecodedPayload() {
            when(interactivityHandler.resolveSigningSecretForDecision(DECODED)).thenReturn("owning-secret");
            when(signatureVerifier.verifyWithSecret("ts", FORM_BODY, "sig", "owning-secret"))
                    .thenReturn(true);

            Response response = webhook.handleInteractive(FORM_BODY, "sig", "ts");

            assertEquals(200, response.getStatus());
            verify(interactivityHandler).handlePayloadAsync(DECODED);
        }

        @Test
        @DisplayName("returns 400 when payload param is missing (before any secret resolution)")
        void missingPayload() {
            Response response = webhook.handleInteractive("foo=bar", "sig", "ts");

            assertEquals(400, response.getStatus());
            verify(interactivityHandler, never()).resolveSigningSecretForDecision(any());
            verify(interactivityHandler, never()).handlePayloadAsync(any());
        }

        @Test
        @DisplayName("extractPayloadParam decodes the URL-encoded payload")
        void extractPayloadParamDecodes() {
            String body = "payload=%7B%22a%22%3A1%7D&other=x";
            assertEquals("{\"a\":1}", RestSlackWebhook.extractPayloadParam(body));
        }

        @Test
        @DisplayName("extractPayloadParam returns null when absent")
        void extractPayloadParamAbsent() {
            assertNull(RestSlackWebhook.extractPayloadParam("foo=bar"));
            assertNull(RestSlackWebhook.extractPayloadParam(""));
            assertNull(RestSlackWebhook.extractPayloadParam(null));
        }

        @Test
        @DisplayName("extractPayloadParam returns null for malformed percent-encoding (does not throw)")
        void extractPayloadParamMalformed() {
            // URLDecoder throws IllegalArgumentException on "%zz"; extractPayloadParam
            // now catches it and returns null rather than letting it bubble to a 500.
            assertNull(RestSlackWebhook.extractPayloadParam("payload=%zz"));
        }

        @Test
        @DisplayName("returns 400 (not 500) for a malformed URL-encoded payload")
        void malformedPayloadYields400() {
            Response response = webhook.handleInteractive("payload=%zz", "sig", "ts");

            // Malformed payload → extractPayloadParam returns null → 400 client error;
            // never reaches secret resolution or dispatch.
            assertEquals(400, response.getStatus());
            verify(interactivityHandler, never()).resolveSigningSecretForDecision(any());
            verify(interactivityHandler, never()).handlePayloadAsync(any());
        }
    }
}
