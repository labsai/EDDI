/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.RedirectPolicy;
import ai.labs.eddi.engine.httpclient.outbound.RequestBody;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.HmacSignatures;
import ai.labs.eddi.integrations.slack.SlackDeliveryException;
import ai.labs.eddi.integrations.slack.SlackWebApiClient;
import ai.labs.eddi.integrations.webhook.WebhookSignatureVerifier;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Sends one alert to one target. Throws {@link AlertDeliveryException} with a
 * caller-safe message on failure — the message is stored on the event and shown
 * in the inbox, so it never carries a token, a resolved secret or a response
 * body.
 */
@ApplicationScoped
public class AlertSenders {

    /** The largest Slack section text; longer messages are cut. */
    static final int MAX_SLACK_TEXT = 2900;
    /** How much of a webhook's answer is read (and then discarded). */
    static final long MAX_WEBHOOK_RESPONSE_BYTES = 16 * 1024;
    /** The largest webhook body EDDI sends. */
    static final int MAX_WEBHOOK_BODY_BYTES = 64 * 1024;

    private final ChannelTargetRouter channelTargetRouter;
    private final SlackWebApiClient slackApi;
    private final OutboundHttp http;
    private final SecretResolver secretResolver;
    private final AlertSettings settings;
    private final Clock clock;

    @Inject
    public AlertSenders(ChannelTargetRouter channelTargetRouter, SlackWebApiClient slackApi, OutboundHttp http, SecretResolver secretResolver,
            AlertSettings settings) {
        this(channelTargetRouter, slackApi, http, secretResolver, settings, Clock.systemUTC());
    }

    AlertSenders(ChannelTargetRouter channelTargetRouter, SlackWebApiClient slackApi, OutboundHttp http, SecretResolver secretResolver,
            AlertSettings settings, Clock clock) {
        this.channelTargetRouter = channelTargetRouter;
        this.slackApi = slackApi;
        this.http = http;
        this.secretResolver = secretResolver;
        this.settings = settings;
        this.clock = clock;
    }

    /** A delivery that did not happen, with a message fit for the inbox. */
    public static class AlertDeliveryException extends Exception {
        public AlertDeliveryException(String message) {
            super(message, null, false, false);
        }
    }

    public void send(AlertEvent event, DeliveryTarget target) throws AlertDeliveryException {
        switch (target.type()) {
            case SLACK -> sendSlack(event, target);
            case WEBHOOK -> sendWebhook(event, target);
        }
    }

    void sendSlack(AlertEvent event, DeliveryTarget target) throws AlertDeliveryException {
        String token = channelTargetRouter.getBotToken("slack", target.channelId());
        if (token == null || token.isBlank()) {
            throw new AlertDeliveryException("No Slack channel integration serves channel " + target.channelId());
        }
        String text = AlertPayloads.slackText(event, settings.managerUrl());
        if (text.length() > MAX_SLACK_TEXT) {
            text = text.substring(0, MAX_SLACK_TEXT) + "…";
        }
        List<Map<String, Object>> blocks = List.of(Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", text)));
        try {
            String ts = slackApi.postBlocksMessage("Bearer " + token, target.channelId(), null, blocks,
                    AlertPayloads.escape(event.summary()));
            if (ts == null) {
                throw new AlertDeliveryException("Slack refused the message (see the server log for the API error)");
            }
        } catch (SlackDeliveryException e) {
            throw new AlertDeliveryException("Slack could not be reached or is rate limiting");
        }
    }

    void sendWebhook(AlertEvent event, DeliveryTarget target) throws AlertDeliveryException {
        String secret = target.signingSecret() == null ? null : secretResolver.resolveValue(target.signingSecret());
        if (secret == null || secret.isBlank() || SecretReference.isVaultReference(secret)) {
            throw new AlertDeliveryException("The webhook signing secret could not be resolved from the vault");
        }
        byte[] body = AlertJson.write(AlertPayloads.webhook(event, settings.managerUrl())).getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_WEBHOOK_BODY_BYTES) {
            throw new AlertDeliveryException("The alert is larger than " + MAX_WEBHOOK_BODY_BYTES + " bytes");
        }
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String signature = HmacSignatures.hmacSha256Hex(secret, timestamp.getBytes(StandardCharsets.UTF_8),
                ".".getBytes(StandardCharsets.UTF_8), body);
        String url = target.url();
        if (target.urlIsVaultReference()) {
            // Resolved only now, never stored or shown resolved; the same rule as the
            // signing secret: unresolvable means no delivery.
            url = secretResolver.resolveValue(url.strip());
            if (url == null || url.isBlank() || SecretReference.isVaultReference(url)) {
                throw new AlertDeliveryException("The webhook URL could not be resolved from the vault");
            }
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new AlertDeliveryException("The webhook URL is not valid");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https") || uri.getHost() == null) {
            // Checked again here because a vault-reference URL is only known now.
            throw new AlertDeliveryException("The webhook URL is not an https URL");
        }
        OutboundRequest request = OutboundRequest.builder(Profile.DEFAULT, uri).method("POST", RequestBody.ofBytes(body))
                .header("Content-Type", "application/json").header(WebhookSignatureVerifier.EDDI_SIGNATURE_HEADER,
                        "t=" + timestamp + ",v1=" + signature)
                .header("X-Eddi-Alert-Id", event.id()).ssrf(SsrfPolicy.STRICT).redirects(RedirectPolicy.NEVER)
                .timeout(settings.webhookTimeout()).build();
        OutboundResponse<byte[]> response;
        try {
            response = http.send(request, BodyMode.bytes(MAX_WEBHOOK_RESPONSE_BYTES, BodyMode.OnOverflow.TRUNCATE));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AlertDeliveryException("Interrupted while calling the webhook");
        } catch (Exception e) {
            throw new AlertDeliveryException("The webhook could not be reached (" + e.getClass().getSimpleName() + ")");
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new AlertDeliveryException("The webhook answered HTTP " + response.status());
        }
    }
}
