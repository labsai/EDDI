/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One stored OAuth grant: the tokens a connection obtained for one principal.
 * <p>
 * Kept in its own collection rather than on the connection document, because a
 * grant is runtime state with a different lifecycle, different access control,
 * and one absolute rule the config does not have: <b>it must never appear in an
 * export.</b> Ciphertext travels in {@code encryptedAccessToken} /
 * {@code encryptedRefreshToken}; the plaintext exists only inside
 * {@code OAuthTokenService} and only for the length of one call.
 */
public class ConnectionGrant {

    /** Whether a grant can still produce a token. */
    public enum Status {
        /** Usable, possibly after a refresh. */
        ACTIVE,
        /** The access token is past {@code expiresAt}; a refresh is due. */
        EXPIRED,
        /** Deliberately revoked. Resolution refuses. */
        REVOKED,
        /**
         * The refresh token was rejected as {@code invalid_grant} — the user revoked
         * consent, or the provider rotated it out from under us. Terminal: the user
         * must reconnect. A transport failure is deliberately NOT this, because
         * treating a provider blip as a revocation logs everybody out.
         */
        REFRESH_FAILED
    }

    private String id;
    private String tenantId;

    /**
     * The connection's {@code name}, not its document id — names are the reference.
     */
    private String connectionName;

    /** A user id, or {@code __service__}. */
    private String principal;

    private String encryptedAccessToken;
    private String accessTokenIv;
    private String encryptedRefreshToken;
    private String refreshTokenIv;

    /** Which DEK the ciphertext above was sealed with. */
    private String dekId;

    private Instant expiresAt;
    private List<String> scopes;
    private Status status = Status.ACTIVE;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastRefreshAt;

    /**
     * Optimistic lock. The final write guard for a refresh — see
     * {@code OAuthTokenService} for why it is the guard and not the gate.
     */
    private long version;

    /**
     * Who is currently refreshing, or null. Set by an atomic conditional update
     * BEFORE the token endpoint is called, which is the whole point: a version CAS
     * is checked at write time, by which point both replicas have already called
     * the endpoint and the provider has already rotated one of the refresh tokens
     * out from under them.
     */
    private String refreshInProgress;

    /** When the claim above expires and may be taken by somebody else. */
    private Instant refreshLeaseExpiresAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getEncryptedAccessToken() {
        return encryptedAccessToken;
    }

    public void setEncryptedAccessToken(String encryptedAccessToken) {
        this.encryptedAccessToken = encryptedAccessToken;
    }

    public String getAccessTokenIv() {
        return accessTokenIv;
    }

    public void setAccessTokenIv(String accessTokenIv) {
        this.accessTokenIv = accessTokenIv;
    }

    public String getEncryptedRefreshToken() {
        return encryptedRefreshToken;
    }

    public void setEncryptedRefreshToken(String encryptedRefreshToken) {
        this.encryptedRefreshToken = encryptedRefreshToken;
    }

    public String getRefreshTokenIv() {
        return refreshTokenIv;
    }

    public void setRefreshTokenIv(String refreshTokenIv) {
        this.refreshTokenIv = refreshTokenIv;
    }

    public String getDekId() {
        return dekId;
    }

    public void setDekId(String dekId) {
        this.dekId = dekId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getLastRefreshAt() {
        return lastRefreshAt;
    }

    public void setLastRefreshAt(Instant lastRefreshAt) {
        this.lastRefreshAt = lastRefreshAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getRefreshInProgress() {
        return refreshInProgress;
    }

    public void setRefreshInProgress(String refreshInProgress) {
        this.refreshInProgress = refreshInProgress;
    }

    public Instant getRefreshLeaseExpiresAt() {
        return refreshLeaseExpiresAt;
    }

    public void setRefreshLeaseExpiresAt(Instant refreshLeaseExpiresAt) {
        this.refreshLeaseExpiresAt = refreshLeaseExpiresAt;
    }

    /**
     * Whether the access token is still good, with a safety margin.
     * <p>
     * The margin matters: a token that expires in 200ms passes a naive check and is
     * then rejected by a provider whose clock differs by a second, producing a
     * failure that looks random and is not reproducible.
     */
    public boolean isAccessTokenUsable(Instant now, Duration margin) {
        return status == Status.ACTIVE && encryptedAccessToken != null && expiresAt != null && expiresAt.isAfter(now.plus(margin));
    }

    /**
     * Never prints token material, not even its length — a length narrows a
     * brute-force meaningfully for some providers.
     */
    @Override
    public String toString() {
        return "ConnectionGrant[tenant=" + tenantId + ", connection=" + connectionName + ", principal=" + principal + ", status=" + status
                + ", expiresAt=" + expiresAt + ", version=" + version + "]";
    }
}
