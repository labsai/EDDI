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
 * <p>
 * <b>A webhook URL is a secret.</b> Many embed a bearer token in the path or
 * query ({@code hooks.slack.com/services/…}). It is never returned by a read —
 * REST, MCP or the Platform Operator, administrators included:
 * {@link #masked()} replaces it with scheme and host plus {@link #MASK_SUFFIX},
 * and sets {@code urlConfigured}. An update that sends the masked value back
 * (or none) keeps the stored URL. The URL may also be a {@code ${vault:...}}
 * reference, resolved only when the alert is sent; such a reference is not a
 * secret and is shown as it is.
 *
 * @param type
 *            {@code SLACK} or {@code WEBHOOK}
 * @param channelId
 *            SLACK: the Slack channel id
 * @param url
 *            WEBHOOK: the endpoint (http or https)
 * @param signingSecret
 *            WEBHOOK: a {@code ${vault:...}} reference to the signing secret
 * @param urlConfigured
 *            set on reads only: a URL is stored (the read shows it masked);
 *            never stored
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeliveryTarget(DeliveryType type, String channelId, String url, String signingSecret, Boolean urlConfigured) {

    /** What replaces the path and query of a webhook URL on every read. */
    public static final String MASK_SUFFIX = "/***";

    public DeliveryTarget(DeliveryType type, String channelId, String url, String signingSecret) {
        this(type, channelId, url, signingSecret, null);
    }

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
     * Whether {@code url} is a {@code ${vault:...}} reference rather than a URL.
     */
    @JsonIgnore
    public boolean urlIsVaultReference() {
        String u = url == null ? "" : url.strip();
        return u.startsWith("${vault:") && u.endsWith("}");
    }

    /** Whether {@code url} is the masked form a read returned. */
    public static boolean isMaskedUrl(String url) {
        return url != null && url.strip().endsWith(MASK_SUFFIX);
    }

    /**
     * The target as a read shows it: a literal webhook URL reduced to scheme and
     * host plus {@link #MASK_SUFFIX}, {@code urlConfigured} set. Slack targets and
     * a vault-reference URL are unchanged (neither is a credential).
     */
    public DeliveryTarget masked() {
        if (type != DeliveryType.WEBHOOK || url == null || url.isBlank()) {
            return new DeliveryTarget(type, channelId, url, signingSecret, null);
        }
        if (urlIsVaultReference()) {
            return new DeliveryTarget(type, channelId, url, signingSecret, true);
        }
        return new DeliveryTarget(type, channelId, origin(url) + MASK_SUFFIX, signingSecret, true);
    }

    /**
     * The host part of a masked or literal URL, lower-case; {@code null} when there
     * is none.
     */
    public static String hostOf(String url) {
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String origin(String url) {
        try {
            URI uri = URI.create(url.strip());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "?" : uri.getHost().toLowerCase(Locale.ROOT);
            return scheme + "://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (RuntimeException e) {
            return "https://?";
        }
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
        if (urlIsVaultReference()) {
            return "Webhook " + url.strip();
        }
        try {
            String host = URI.create(url).getHost();
            return "Webhook " + (host == null ? "?" : host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "Webhook ?";
        }
    }
}
