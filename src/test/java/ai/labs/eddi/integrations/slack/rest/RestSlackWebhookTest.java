package ai.labs.eddi.integrations.slack.rest;

import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.slack.SlackEventHandler;
import ai.labs.eddi.integrations.slack.SlackIntegrationConfig;
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
 * verification challenge, event dispatching, and disabled/error paths.
 */
class RestSlackWebhookTest {

    private SlackIntegrationConfig config;
    private ChannelTargetRouter channelTargetRouter;
    private SlackSignatureVerifier signatureVerifier;
    private SlackEventHandler eventHandler;
    private ObjectMapper objectMapper;
    private RestSlackWebhook webhook;

    @BeforeEach
    void setUp() {
        config = mock(SlackIntegrationConfig.class);
        channelTargetRouter = mock(ChannelTargetRouter.class);
        signatureVerifier = mock(SlackSignatureVerifier.class);
        eventHandler = mock(SlackEventHandler.class);
        objectMapper = new ObjectMapper();

        webhook = new RestSlackWebhook(config, channelTargetRouter, signatureVerifier,
                eventHandler, objectMapper);
    }

    // ─── Disabled ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Disabled integration")
    class Disabled {

        @Test
        @DisplayName("returns 404 when slack integration is disabled")
        void returns404WhenDisabled() {
            when(config.enabled()).thenReturn(false);

            Response response = webhook.handleEvents("{}", "sig", "ts");

            assertEquals(404, response.getStatus());
            verifyNoInteractions(signatureVerifier, eventHandler);
        }
    }

    // ─── Signature verification ───────────────────────────────────────────────

    @Nested
    @DisplayName("Signature verification")
    class SignatureVerification {

        @Test
        @DisplayName("returns 403 when signature verification fails")
        void returns403OnBadSignature() {
            when(config.enabled()).thenReturn(true);
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
            when(config.enabled()).thenReturn(true);
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
            when(config.enabled()).thenReturn(true);
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
            when(config.enabled()).thenReturn(true);
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
            when(config.enabled()).thenReturn(true);
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
            when(config.enabled()).thenReturn(true);
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
            when(config.enabled()).thenReturn(true);
            when(channelTargetRouter.getSigningSecrets("slack")).thenReturn(Set.of("secret"));
            when(signatureVerifier.verify(any(), any(), any(), any())).thenReturn(true);

            Response response = webhook.handleEvents("not json", "sig", "ts");

            assertEquals(400, response.getStatus());
        }
    }
}
