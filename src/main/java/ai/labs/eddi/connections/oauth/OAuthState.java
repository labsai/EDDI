/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import java.time.Instant;

/**
 * One in-flight authorization-code flow.
 * <p>
 * Persisted, not held in memory. Behind a load balancer the provider's redirect
 * routinely lands on a different replica than the one that issued the state,
 * and an in-memory map turns that into an intermittent "invalid state" that
 * only reproduces under load.
 * <p>
 * This row is the callback's <em>only</em> guard: the redirect arrives as a
 * top-level browser GET with no bearer token, so the endpoint cannot be
 * {@code @Authenticated}. The row therefore binds the tenant, the connection
 * and the principal, and the callback never trusts a request parameter for
 * identity.
 */
public class OAuthState {

    /** Opaque, single-use, high-entropy. Also the primary key. */
    private String state;

    private String tenantId;
    private String connectionName;

    /**
     * Who will own the resulting grant. Read from the row, never from the query.
     */
    private String principal;

    /** RFC 7636 verifier. The provider only ever sees its S256 challenge. */
    private String codeVerifier;

    /** The exact redirect_uri sent to the provider; replayed on exchange. */
    private String redirectUri;

    /** Where to send the browser afterwards. Validated before it is stored. */
    private String returnTo;

    private Instant createdAt;
    private Instant expiresAt;

    /**
     * When this state was redeemed. The presence of a value is what makes the row
     * single-use, and it is set by the same conditional update that claims it —
     * validating and then marking consumed lets two concurrent callbacks both
     * observe it unconsumed and both redeem the code.
     */
    private Instant consumedAt;

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getPrincipal() {
        return principal;
    }

    public void setPrincipal(String principal) {
        this.principal = principal;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public void setCodeVerifier(String codeVerifier) {
        this.codeVerifier = codeVerifier;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getReturnTo() {
        return returnTo;
    }

    public void setReturnTo(String returnTo) {
        this.returnTo = returnTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }

    /**
     * Neither the state token nor the verifier is printable — both are credentials.
     */
    @Override
    public String toString() {
        return "OAuthState[tenant=" + tenantId + ", connection=" + connectionName + ", expiresAt=" + expiresAt + "]";
    }
}
