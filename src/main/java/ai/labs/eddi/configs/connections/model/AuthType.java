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
    OAUTH2_AUTHORIZATION_CODE
}
