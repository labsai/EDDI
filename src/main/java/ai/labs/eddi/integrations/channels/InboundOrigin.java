/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

/**
 * Who a verified inbound channel request came from — what the adapter's
 * authentication established, before any routing.
 * <p>
 * Slack authenticates with a shared signing secret per app, so its origin is
 * the secret that verified the request (and the integration that holds it, when
 * known). Teams and Discord authenticate per <em>application</em>: a Teams
 * activity carries a JWT whose verified {@code aud} is the bot's app id, a
 * Discord interaction is verified with the public key of the application named
 * in its endpoint path. For those the origin is that application id, and the
 * integration is found by it, never by a field of the request body.
 * <p>
 * May hold a resolved signing secret: {@link #toString()} never prints it, and
 * the record must not be logged or serialized otherwise.
 *
 * @param channelType
 *            the type the request arrived on ({@code slack}, {@code teams}, …)
 * @param integrationName
 *            the integration the adapter already identified, or {@code null}
 * @param verifiedApplicationId
 *            the application id the request was verified for, or {@code null}
 * @param verifiedSigningSecret
 *            the signing secret that verified the request, or {@code null}
 * @since 6.7.0
 */
public record InboundOrigin(String channelType, String integrationName, String verifiedApplicationId, String verifiedSigningSecret) {

    /** A request verified by a shared signing secret (Slack). */
    public static InboundOrigin signedBy(String channelType, String integrationName, String verifiedSigningSecret) {
        return new InboundOrigin(channelType, integrationName, null, verifiedSigningSecret);
    }

    /**
     * A request verified for an application (Teams app id, Discord application id).
     */
    public static InboundOrigin application(String channelType, String verifiedApplicationId) {
        return new InboundOrigin(channelType, null, verifiedApplicationId, null);
    }

    /**
     * Whether this origin carries a verified identity: an application id or a
     * signing secret. A name alone does not count — it may come from a URL path or
     * a request body.
     */
    public boolean isVerified() {
        return notBlank(verifiedApplicationId) || notBlank(verifiedSigningSecret);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return "InboundOrigin[channelType=" + channelType + ", integrationName=" + integrationName + ", verifiedApplicationId="
                + verifiedApplicationId + ", verifiedSigningSecret=" + (verifiedSigningSecret == null ? "null" : "<REDACTED>") + "]";
    }
}
