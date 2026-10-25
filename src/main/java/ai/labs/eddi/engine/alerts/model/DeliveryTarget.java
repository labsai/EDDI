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
 * <p>
 * <b>Each target has a {@code targetId}</b>, assigned by the server when a rule
 * is saved and kept across updates. It is not secret and every read returns it.
 * An update — or a test send of a saved rule — that sends a masked or empty URL
 * gets the stored URL of the target with the <i>same</i> {@code targetId}, and
 * only that one: never by position or host. A rule stored before ids existed
 * gets them on read, derived from each target (see {@link #derivedTargetId()}),
 * so the same id comes back on every read until the next save stores it.
 *
 * @param type
 *            {@code SLACK} or {@code WEBHOOK}
 * @param channelId
 *            SLACK: the Slack channel id
 * @param url
 *            WEBHOOK: the endpoint (https only)
 * @param signingSecret
 *            WEBHOOK: a {@code ${vault:...}} reference to the signing secret
 * @param urlConfigured
 *            set on reads only: a URL is stored (the read shows it masked);
 *            never stored
 * @param targetId
 *            the target's identity within its rule, assigned by the server
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeliveryTarget(DeliveryType type, String channelId, String url, String signingSecret, Boolean urlConfigured, String targetId) {

    /** What replaces the path and query of a webhook URL on every read. */
    public static final String MASK_SUFFIX = "/***";

    public DeliveryTarget(DeliveryType type, String channelId, String url, String signingSecret) {
        this(type, channelId, url, signingSecret, null, null);
    }

    public DeliveryTarget(DeliveryType type, String channelId, String url, String signingSecret, Boolean urlConfigured) {
        this(type, channelId, url, signingSecret, urlConfigured, null);
    }

    /** The same target under another id ({@code null} to have one assigned). */
    public DeliveryTarget withTargetId(String newTargetId) {
        return new DeliveryTarget(type, channelId, url, signingSecret, urlConfigured, newTargetId);
    }

    /** The same target with another URL, id kept. */
    public DeliveryTarget withUrl(String newUrl) {
        return new DeliveryTarget(type, channelId, newUrl, signingSecret, urlConfigured, targetId);
    }

    /** Without the read-only {@code urlConfigured} flag, which is never stored. */
    public DeliveryTarget forStorage() {
        return new DeliveryTarget(type, channelId, url, signingSecret, null, targetId);
    }

    /**
     * The id a target without one gets: {@code t} plus its {@link #key()}. Stable
     * for the same target, so a rule stored before ids existed shows the same ids
     * on every read, and its first update can match them against the stored
     * targets.
     */
    @JsonIgnore
    public String derivedTargetId() {
        return "t" + key();
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
            return new DeliveryTarget(type, channelId, url, signingSecret, null, targetId);
        }
        if (urlIsVaultReference()) {
            return new DeliveryTarget(type, channelId, url, signingSecret, true, targetId);
        }
        return new DeliveryTarget(type, channelId, origin(url) + MASK_SUFFIX, signingSecret, true, targetId);
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
