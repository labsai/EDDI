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

import java.util.ArrayList;
import java.util.List;
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
 * This is the whole fix, and it is a bean rather than a call from the vault
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
     * Prepare fully, then write — the contract
     * {@link SealedDataRotationParticipant} states, for the reason it states it.
     * <p>
     * If a value cannot be re-sealed, the re-sealer throws and rotation aborts with
     * the old DEK still in place and nothing written, so every grant is still
     * readable. Writing as we go would leave a prefix of the tenant's grants on a
     * key that is about to be discarded — the exact corruption this class exists to
     * prevent, just smaller.
     */
    @Override
    public int resealAll(String tenantId, UnaryOperator<SealedValue> resealer) {
        List<ConnectionGrant> grants = grantStore.findByTenant(tenantId);
        if (grants.isEmpty()) {
            return 0;
        }

        var prepared = new ArrayList<ConnectionGrant>(grants.size());
        for (ConnectionGrant grant : grants) {
            SealedValue access = resealer.apply(sealedAccessToken(grant));
            SealedValue refresh = resealer.apply(sealedRefreshToken(grant));
            grant.setEncryptedAccessToken(access == null ? null : access.ciphertext());
            grant.setAccessTokenIv(access == null ? null : access.iv());
            grant.setEncryptedRefreshToken(refresh == null ? null : refresh.ciphertext());
            grant.setRefreshTokenIv(refresh == null ? null : refresh.iv());
            prepared.add(grant);
        }

        int written = 0;
        for (ConnectionGrant grant : prepared) {
            if (grantStore.updateSealedTokens(grant)) {
                written++;
            } else {
                // The row went away between the read and the write — a disconnect, or a
                // connection deleted mid-rotation. Not an error: there is nothing left
                // to re-seal. Worth a line, because the count below will not add up.
                LOGGER.debugf("Grant for connection '%s' vanished during DEK rotation; nothing to re-seal", grant.getConnectionName());
            }
        }
        return written;
    }

    /** Null-safe: a grant may legitimately hold no refresh token. */
    private static SealedValue sealedAccessToken(ConnectionGrant grant) {
        return grant.getEncryptedAccessToken() == null ? null : new SealedValue(grant.getEncryptedAccessToken(), grant.getAccessTokenIv());
    }

    private static SealedValue sealedRefreshToken(ConnectionGrant grant) {
        return grant.getEncryptedRefreshToken() == null ? null : new SealedValue(grant.getEncryptedRefreshToken(), grant.getRefreshTokenIv());
    }
}
