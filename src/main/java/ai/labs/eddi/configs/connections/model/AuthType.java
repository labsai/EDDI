/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

/**
 * How a connection authenticates to the system it fronts.
 * <p>
 * Deliberately small and closed. A provider is never named here — a connection
 * describes an <em>auth shape</em>, and Jira, Amplitude, Notion and Linear are
 * all the same shape as each other. Adding a provider must be a JSON document,
 * not a Java enum constant (Golden Rule 1).
 */
public enum AuthType {

    /** A fixed header, e.g. {@code X-Api-Key: ${vault:amplitude-key}}. */
    STATIC,

    /**
     * HTTP Basic. EDDI performs the base64 encoding, which is the point: without it
     * an author has to vault a pre-encoded blob, and a pre-encoded blob cannot be
     * rotated field-by-field or read back to check which account it is.
     */
    BASIC,

    /**
     * OAuth 2.0 {@code client_credentials} — a service account. One grant shared by
     * every user of the agent, refreshed lazily.
     */
    OAUTH2_CLIENT_CREDENTIALS,

    /**
     * OAuth 2.0 authorization code with PKCE — a grant per end user. The only type
     * that may carry {@link Binding#PER_USER}.
     */
    OAUTH2_AUTHORIZATION_CODE,

    /**
     * OAuth 2.0 JWT bearer grant (RFC 7523 §2.1) — a service account that proves
     * itself with a signed assertion rather than a client secret. EDDI signs a
     * short-lived RS256 JWT with a vaulted private key and exchanges it for an
     * access token; Google service accounts are the common case. Like
     * {@link #OAUTH2_CLIENT_CREDENTIALS} it needs no human step, so it is
     * {@link Binding#SERVICE} only and the first call mints the grant.
     */
    OAUTH2_JWT_BEARER;

    /**
     * Whether this type completes an OAuth 2.0 flow, and so needs a token endpoint,
     * a client secret and a stored grant.
     * <p>
     * A method rather than the two-term disjunction spelled out at each call site.
     * A third OAuth type — device code, JWT bearer — has to reach every one of
     * them, and the site that gets missed is a connection that saves without a
     * vault and then fails at the moment the token comes back.
     */
    public boolean isOAuth() {
        return this == OAUTH2_AUTHORIZATION_CODE || this == OAUTH2_CLIENT_CREDENTIALS || this == OAUTH2_JWT_BEARER;
    }

    /**
     * Whether this type obtains its token without a human step, so the token
     * service mints the grant on first use and simply re-mints when one is rejected
     * — there is no refresh token to rotate and no user to send back to a consent
     * screen.
     */
    public boolean isServiceAccount() {
        return this == OAUTH2_CLIENT_CREDENTIALS || this == OAUTH2_JWT_BEARER;
    }
}
