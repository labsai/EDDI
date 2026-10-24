/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Where a rule delivers, besides the Manager inbox (which is always).
 *
 * <h3>{@code SLACK}</h3> {@code channelId} must be a Slack channel an existing
 * Slack channel integration serves; the message is posted with that
 * integration's bot token. No credential lives on the rule.
 *
 * <h3>{@code WEBHOOK}</h3> A JSON {@code POST} to {@code url} through
 * {@code OutboundHttp} (strict SSRF policy, no redirects, a timeout and a body
 * cap), signed with {@code X-Eddi-Signature: t=<unix>,v1=<hex>} — HMAC-SHA256
 * over {@code t + "." + body} keyed by {@code signingSecret}, which must be a
 * {@code ${vault:...}} reference. Webhook targets are an administrator's: the
 * URL is arbitrary and the signature is computed with a vault secret.
 *
 * @param type
 *            {@code SLACK} or {@code WEBHOOK}
 * @param channelId
 *            SLACK: the Slack channel id
 * @param url
 *            WEBHOOK: the endpoint (http or https)
 * @param signingSecret
 *            WEBHOOK: a {@code ${vault:...}} reference to the signing secret
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeliveryTarget(DeliveryType type, String channelId, String url, String signingSecret) {

    public enum DeliveryType {
        SLACK, WEBHOOK
    }

    public static DeliveryTarget slack(String channelId) {
        return new DeliveryTarget(DeliveryType.SLACK, channelId, null, null);
    }

    public static DeliveryTarget webhook(String url, String signingSecret) {
        return new DeliveryTarget(DeliveryType.WEBHOOK, null, url, signingSecret);
    }

    /**
     * A stable identity of the target, recorded on each delivery so a retry finds
     * the same target on the rule. A hash, because the webhook URL itself can be a
     * credential (an incoming-webhook URL is) and alert events are shown to
     * editors.
     */
    @JsonIgnore
    public String key() {
        String raw = type + "|" + (type == DeliveryType.SLACK ? channelId : url);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What the inbox shows for the target: the Slack channel, or the webhook's host
     * only — never its path or query.
     */
    @JsonIgnore
    public String label() {
        if (type == DeliveryType.SLACK) {
            return "Slack " + channelId;
        }
        try {
            String host = URI.create(url).getHost();
            return "Webhook " + (host == null ? "?" : host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "Webhook ?";
        }
    }
}
