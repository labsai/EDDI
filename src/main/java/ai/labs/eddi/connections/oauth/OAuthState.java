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
 * (by resource id, not only by name) and the principal, and the callback never
 * trusts a request parameter for identity.
 */
public class OAuthState {

    /** Opaque, single-use, high-entropy. Also the primary key. */
    private String state;

    private String tenantId;
    private String connectionName;

    /**
     * The resource id of the connection the flow was started for.
     * <p>
     * The name alone does not identify it. Grants are filed under the name, so if
     * the connection is deleted and a new one created under the same name while the
     * user sits on the provider's consent screen, a name-only binding files a token
     * issued for the old connection's client under the new connection — whose
     * allowlist may send it to a different service. The callback refuses unless the
     * name still belongs to this id.
     * <p>
     * A row written before this field existed carries none, and is refused as an
     * invalid state rather than grandfathered: rows live ten minutes, and the user
     * simply starts again.
     */
    private String connectionId;

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

    /**
     * SHA-256 of the nonce handed to the browser as a cookie when this flow
     * started. Binds the row to the browser that began it.
     * <p>
     * Without it the state is a bearer token that only the ATTACKER needs to know.
     * The attack is the reverse of the one people expect: the attacker starts a
     * flow under their own EDDI account, keeps the state, and sends the victim the
     * provider consent link built around it. The victim consents with their own
     * Google account, the callback stores the resulting tokens against the
     * principal in the row — the attacker — and the attacker's next chat turn reads
     * the victim's mail. Binding the tenant, connection and principal does not stop
     * this, because every one of those fields is exactly what the attacker wants
     * them to be.
     * <p>
     * The hash, not the nonce: this row is readable by anything that can read the
     * database, and a stored nonce would be as good as the cookie.
     */
    private String nonceHash;

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

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
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

    public String getNonceHash() {
        return nonceHash;
    }

    public void setNonceHash(String nonceHash) {
        this.nonceHash = nonceHash;
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
