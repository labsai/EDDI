/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import ai.labs.eddi.engine.alerts.AlertSenders.AlertDeliveryException;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.RedirectPolicy;
import ai.labs.eddi.engine.httpclient.outbound.RequestBody;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.slack.SlackDeliveryException;
import ai.labs.eddi.integrations.slack.SlackWebApiClient;
import ai.labs.eddi.integrations.webhook.WebhookSignatureVerifier;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.alerts.AlertTestData.errorRule;
import static ai.labs.eddi.engine.alerts.AlertTestData.event;
import static ai.labs.eddi.engine.alerts.AlertTestData.pending;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AlertSenders — Slack and signed webhooks")
class AlertSendersTest {

    private static final String SECRET = "whsec-abcdefghijklmnop";

    private ChannelTargetRouter router;
    private SlackWebApiClient slack;
    private OutboundHttp http;
    private SecretResolver secrets;
    private AlertSenders senders;
    private AlertEvent event;

    @BeforeEach
    void setUp() {
        router = mock(ChannelTargetRouter.class);
        slack = mock(SlackWebApiClient.class);
        http = mock(OutboundHttp.class);
        secrets = mock(SecretResolver.class);
        senders = new AlertSenders(router, slack, http, secrets, AlertSettings.defaults(), Clock.systemUTC());
        AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR));
        event = event("6aba70011a25f8be7920e0f1", rule, AlertEvent.State.PENDING, pending(rule));
    }

    @Test
    @DisplayName("a webhook POST is signed eddi-v1 — EDDI's own inbound verifier accepts it — strict SSRF, no redirects")
    @SuppressWarnings("unchecked")
    void signedWebhook() throws Exception {
        when(secrets.resolveValue("${vault:hook}")).thenReturn(SECRET);
        when(http.send(any(), any())).thenReturn(new OutboundResponse<>(204, "No Content", Map.of(), URI.create("https://hooks.example.com"),
                List.of(), new byte[0], false));

        senders.send(event, DeliveryTarget.webhook("https://hooks.example.com/eddi", "${vault:hook}"));

        ArgumentCaptor<OutboundRequest> sent = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(http).send(sent.capture(), any());
        OutboundRequest request = sent.getValue();
        assertEquals("POST", request.method());
        assertSame(SsrfPolicy.STRICT, request.ssrfOverride());
        assertSame(RedirectPolicy.NEVER, request.redirectOverride());
        byte[] body = ((RequestBody.BytesBody) request.body()).bytes();
        String signature = request.headers().get(WebhookSignatureVerifier.EDDI_SIGNATURE_HEADER).get(0);
        var verification = new WebhookSignatureVerifier().verify(WebhookChannelSettings.from(Map.of("signingSecret", SECRET)), body,
                name -> WebhookSignatureVerifier.EDDI_SIGNATURE_HEADER.equalsIgnoreCase(name) ? signature : null);
        assertEquals(WebhookSignatureVerifier.Result.VALID, verification);
        String json = new String(body, StandardCharsets.UTF_8);
        assertFalse(json.contains(SECRET), "the secret must never be in the body");
        assertTrue(json.contains("\"schema\":\"eddi.alert.v1\""), json);
    }

    @Test
    @DisplayName("an unresolvable signing secret refuses to send — nothing unsigned goes out")
    void unresolvedSecret() throws Exception {
        when(secrets.resolveValue("${vault:hook}")).thenReturn("${vault:hook}");
        AlertDeliveryException e = assertThrows(AlertDeliveryException.class,
                () -> senders.send(event, DeliveryTarget.webhook("https://hooks.example.com/eddi", "${vault:hook}")));
        assertTrue(e.getMessage().contains("vault"));
        verify(http, never()).send(any(), any());
    }

    @Test
    @DisplayName("a non-2xx answer or an unreachable host is a failure whose message carries no response body")
    void webhookFailures() throws Exception {
        when(secrets.resolveValue(anyString())).thenReturn(SECRET);
        when(http.send(any(), any())).thenReturn(new OutboundResponse<>(500, "Error", Map.of(), URI.create("https://h.example.com"), List.of(),
                "stack trace with internals".getBytes(StandardCharsets.UTF_8), false));
        AlertDeliveryException http500 = assertThrows(AlertDeliveryException.class,
                () -> senders.send(event, DeliveryTarget.webhook("https://h.example.com/x", "${vault:hook}")));
        assertEquals("The webhook answered HTTP 500", http500.getMessage());

        when(http.send(any(), any())).thenThrow(new IOException("connect to 10.0.0.7 refused"));
        AlertDeliveryException io = assertThrows(AlertDeliveryException.class,
                () -> senders.send(event, DeliveryTarget.webhook("https://h.example.com/x", "${vault:hook}")));
        assertFalse(io.getMessage().contains("10.0.0.7"), io.getMessage());
    }

    @Test
    @DisplayName("Slack posts with the bot token of the integration serving the channel, as a Bearer token")
    void slack() throws Exception {
        when(router.getBotToken("slack", "C0123ABC")).thenReturn("xoxb-token");
        when(slack.postBlocksMessage(eq("Bearer xoxb-token"), eq("C0123ABC"), isNull(), anyList(), anyString())).thenReturn("123.456");
        senders.send(event, DeliveryTarget.slack("C0123ABC"));
        verify(slack).postBlocksMessage(eq("Bearer xoxb-token"), eq("C0123ABC"), isNull(), anyList(), anyString());
    }

    @Test
    @DisplayName("no integration for the channel, a refused post or a Slack outage are failures")
    void slackFailures() {
        assertThrows(AlertDeliveryException.class, () -> senders.send(event, DeliveryTarget.slack("C0NOPE")));
        when(router.getBotToken("slack", "C0123ABC")).thenReturn("xoxb-token");
        when(slack.postBlocksMessage(anyString(), anyString(), isNull(), anyList(), anyString())).thenReturn(null);
        assertThrows(AlertDeliveryException.class, () -> senders.send(event, DeliveryTarget.slack("C0123ABC")));
        when(slack.postBlocksMessage(anyString(), anyString(), isNull(), anyList(), anyString())).thenThrow(new SlackDeliveryException("429"));
        assertThrows(AlertDeliveryException.class, () -> senders.send(event, DeliveryTarget.slack("C0123ABC")));
    }

    @Test
    @DisplayName("a vault-reference URL is resolved only at send time; an unresolvable one sends nothing")
    @SuppressWarnings("unchecked")
    void vaultReferenceUrl() throws Exception {
        when(secrets.resolveValue("${vault:hook}")).thenReturn(SECRET);
        when(secrets.resolveValue("${vault:hook-url}")).thenReturn("https://hooks.example.com/services/T/B/token");
        when(http.send(any(), any())).thenReturn(new OutboundResponse<>(200, "OK", Map.of(), URI.create("https://hooks.example.com"),
                List.of(), new byte[0], false));

        senders.send(event, DeliveryTarget.webhook("${vault:hook-url}", "${vault:hook}"));
        ArgumentCaptor<OutboundRequest> sent = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(http).send(sent.capture(), any());
        assertEquals("https://hooks.example.com/services/T/B/token", sent.getValue().uri().toString());

        when(secrets.resolveValue("${vault:missing}")).thenReturn("${vault:missing}");
        AlertDeliveryException e = assertThrows(AlertDeliveryException.class,
                () -> senders.send(event, DeliveryTarget.webhook("${vault:missing}", "${vault:hook}")));
        assertTrue(e.getMessage().contains("URL could not be resolved"));
        verify(http).send(any(), any());
    }

    @Test
    @DisplayName("only https is delivered to — a vault-reference URL that resolves to http sends nothing")
    void httpsOnly() throws Exception {
        when(secrets.resolveValue("${vault:hook}")).thenReturn(SECRET);
        when(secrets.resolveValue("${vault:plain-url}")).thenReturn("http://hooks.example.com/services/T/B/token");
        AlertDeliveryException e = assertThrows(AlertDeliveryException.class,
                () -> senders.send(event, DeliveryTarget.webhook("${vault:plain-url}", "${vault:hook}")));
        assertTrue(e.getMessage().contains("https"));
        assertThrows(AlertDeliveryException.class, () -> senders.send(event, DeliveryTarget.webhook("http://hooks.example.com/x", "${vault:hook}")));
        verify(http, never()).send(any(), any());
    }

    @Test
    @DisplayName("an inbox label of a webhook names the host only, never the path or token")
    void labelsNameTheHostOnly() {
        assertEquals("Webhook hooks.example.com", DeliveryTarget.webhook("https://hooks.example.com/services/T/B/s3cr3t?x=1", "${vault:k}").label());
        assertEquals("https://hooks.example.com/***",
                DeliveryTarget.webhook("https://hooks.example.com/services/T/B/s3cr3t?x=1", "${vault:k}").masked().url());
    }
}
