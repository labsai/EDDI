/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

import ai.labs.eddi.secrets.ISecretProvider.SealedValue;
import ai.labs.eddi.secrets.SealedDataRotationParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.function.UnaryOperator;

/**
 * Carries OAuth grants across a DEK rotation.
 * <p>
 * Grant tokens are sealed with the tenant's DEK — the same key the vault uses
 * for named secrets, deliberately, so there is only ever one key hierarchy to
 * rotate and lose. Nothing taught rotation about them, so replacing the DEK
 * orphaned every grant in the tenant: an operator running a routine compliance
 * rotation disconnected every linked SaaS account and learned about it one
 * {@code invalid_grant} at a time, days later, with no way back.
 * <p>
 * Rotation no longer replaces anything — it adds a DEK generation and sweeps
 * rows onto it — so this class is a migration, not a rescue: falling behind
 * costs a re-run, never a token. It is a bean rather than a call from the vault
 * because {@code ai.labs.eddi.secrets} must not depend on its consumers.
 */
@ApplicationScoped
public class ConnectionGrantResealer implements SealedDataRotationParticipant {

    private static final Logger LOGGER = Logger.getLogger(ConnectionGrantResealer.class);

    private final IConnectionGrantStore grantStore;

    @Inject
    public ConnectionGrantResealer(IConnectionGrantStore grantStore) {
        this.grantStore = grantStore;
    }

    @Override
    public String sealedDataDescription() {
        return "OAuth connection grants";
    }

    /**
     * Row by row, each write guarded on the version the row was read at.
     * <p>
     * The old shape — prepare every grant in memory, then write them all — was
     * built for a rotation that replaced the key underneath the data, where a
     * half-written tenant meant a half-destroyed one. It no longer is: the new
     * generation is already committed when this runs and the old one still exists,
     * so a grant left behind is merely still on an older key, which opens it
     * perfectly well until the next rotation moves it.
     * <p>
     * The guard is what matters instead. A refresh completing during the sweep
     * writes new tokens sealed with the active DEK; overwriting that with a re-seal
     * of the tokens it replaced would hand back a refresh token the provider has
     * already rotated away, and log the user out.
     */
    @Override
    public int resealAll(String tenantId, String activeDekId, UnaryOperator<SealedValue> resealer) {
        int outstanding = 0;
        for (ConnectionGrant grant : grantStore.findByTenant(tenantId)) {
            if (!migrate(tenantId, grant, activeDekId, resealer)) {
                outstanding++;
                LOGGER.debugf("Grant for connection '%s' stayed on an older DEK generation; the next rotation will move it",
                        grant.getConnectionName());
            }
        }
        return outstanding;
    }

    /**
     * Moves one grant onto the active generation.
     * <p>
     * On a lost guard the row is re-read once. If it now names the active
     * generation, a concurrent refresh sealed it under the new key and there is
     * nothing left to do. Otherwise one retry is enough: a grant losing twice is
     * one being refreshed continuously, and leaving it is free — it still names a
     * generation that decrypts.
     *
     * @return whether the grant is on the active generation when this returns
     */
    private boolean migrate(String tenantId, ConnectionGrant grant, String activeDekId, UnaryOperator<SealedValue> resealer) {
        ConnectionGrant current = grant;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (current == null) {
                // Disconnected mid-sweep, or the whole connection was deleted. There is
                // nothing left to re-seal, which is not a failure.
                return true;
            }
            if (activeDekId.equals(current.getDekId())) {
                return true;
            }
            long expectedVersion = current.getVersion();
            SealedValue access = resealer.apply(sealedAccessToken(current));
            SealedValue refresh = resealer.apply(sealedRefreshToken(current));
            current.setEncryptedAccessToken(access == null ? null : access.ciphertext());
            current.setAccessTokenIv(access == null ? null : access.iv());
            current.setEncryptedRefreshToken(refresh == null ? null : refresh.ciphertext());
            current.setRefreshTokenIv(refresh == null ? null : refresh.iv());
            current.setDekId(activeDekId);
            if (grantStore.updateSealedTokens(current, expectedVersion)) {
                return true;
            }
            current = grantStore.find(tenantId, grant.getConnectionName(), grant.getPrincipal()).orElse(null);
        }
        return false;
    }

    /**
     * Null-safe: a grant may legitimately hold no refresh token. The row's dekId
     * travels with the ciphertext so the re-sealer opens it with the generation it
     * was actually sealed under — which, after an interrupted rotation, is not
     * necessarily the same for every grant in the tenant.
     */
    private static SealedValue sealedAccessToken(ConnectionGrant grant) {
        return grant.getEncryptedAccessToken() == null
                ? null
                : new SealedValue(grant.getEncryptedAccessToken(), grant.getAccessTokenIv(), grant.getDekId());
    }

    private static SealedValue sealedRefreshToken(ConnectionGrant grant) {
        return grant.getEncryptedRefreshToken() == null
                ? null
                : new SealedValue(grant.getEncryptedRefreshToken(), grant.getRefreshTokenIv(), grant.getDekId());
    }
}
