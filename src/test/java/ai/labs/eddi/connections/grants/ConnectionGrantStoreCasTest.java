/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code updateSealedTokens} contract, exercised against
 * {@link InMemoryConnectionGrantStore}.
 * <p>
 * This is a test OF THE DOUBLE, deliberately.
 * {@code ConnectionGrantResealerTest} proves the resealer respects a lost
 * guard, a concurrent refresh and a live lease — but every one of those proofs
 * is only as good as the double's conditional write. Delete the version from
 * the double's condition and the resealer suite still passes while asserting
 * nothing, which is precisely the failure mode this round exists to close.
 * <p>
 * The double is faithful on this method: both real stores issue a single
 * conditional write whose predicate includes {@code version = expectedVersion}
 * and whose assignment list is the five ciphertext columns and nothing else —
 * no version bump, no {@code updatedAt}, no lease column. Mongo reports the
 * outcome as {@code matchedCount == 1} and Postgres as
 * {@code executeUpdate() == 1}; the double returns the same boolean from the
 * same predicate.
 */
class ConnectionGrantStoreCasTest {

    private static final String TENANT = "acme";
    private static final String CONNECTION = "jira";
    private static final String PRINCIPAL = "alice";

    private InMemoryConnectionGrantStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryConnectionGrantStore();
    }

    private static ConnectionGrant grant(String dekId, String suffix) {
        var grant = new ConnectionGrant();
        grant.setTenantId(TENANT);
        grant.setConnectionName(CONNECTION);
        grant.setPrincipal(PRINCIPAL);
        grant.setEncryptedAccessToken(dekId + ":access-" + suffix);
        grant.setAccessTokenIv("iv-" + dekId);
        grant.setEncryptedRefreshToken(dekId + ":refresh-" + suffix);
        grant.setRefreshTokenIv("iv-" + dekId);
        grant.setDekId(dekId);
        grant.setStatus(ConnectionGrant.Status.ACTIVE);
        grant.setExpiresAt(Instant.now().plusSeconds(3600));
        return grant;
    }

    private ConnectionGrant stored() {
        return store.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow();
    }

    @Test
    @DisplayName("a re-seal at the version the row was read at lands")
    void writesAtTheObservedVersion() {
        store.upsert(grant("gen-1", "original"));
        long observed = stored().getVersion();

        assertTrue(store.updateSealedTokens(grant("gen-2", "resealed"), observed), "a re-seal that lost nothing must report that it landed");

        var after = stored();
        assertEquals("gen-2:access-resealed", after.getEncryptedAccessToken());
        assertEquals("gen-2:refresh-resealed", after.getEncryptedRefreshToken());
        assertEquals("iv-gen-2", after.getRefreshTokenIv(), "the IV must travel with the ciphertext or the row cannot be opened");
        assertEquals("gen-2", after.getDekId(), "without the new dekId the next read opens the row with the wrong generation");
    }

    @Test
    @DisplayName("a re-seal at a stale version does not land, and the newer write survives untouched")
    void refusesAStaleVersion() {
        store.upsert(grant("gen-1", "original"));
        long observed = stored().getVersion();
        // A refresh completes between the sweep's read and its write. It sealed with
        // the active generation and holds the token the provider just issued.
        store.upsert(grant("gen-2", "refreshed"));

        assertFalse(store.updateSealedTokens(grant("gen-2", "resealed"), observed), "the guard must report the loss rather than forcing through");

        var after = stored();
        assertEquals("gen-2:refresh-refreshed", after.getEncryptedRefreshToken(),
                "forcing the stale write through would hand back the refresh token the provider has already rotated away, logging the user out");
        assertEquals("gen-2:access-refreshed", after.getEncryptedAccessToken());
    }

    @Test
    @DisplayName("a re-seal leaves the version alone, so a refresh holding it does not lose its CAS")
    void doesNotBumpTheVersion() {
        store.upsert(grant("gen-1", "original"));
        long observed = stored().getVersion();

        assertTrue(store.updateSealedTokens(grant("gen-2", "resealed"), observed));

        assertEquals(observed, stored().getVersion(),
                "a re-seal is invisible to anyone holding a version; bumping it would fail a refresh that had nothing wrong with it");
        // Proved by consequence as well as by reading the field: the same version is
        // still spendable afterwards.
        assertTrue(store.updateSealedTokens(grant("gen-3", "again"), observed), "the version the caller still holds must still be the live one");
    }

    @Test
    @DisplayName("a re-seal neither takes nor clears a refresh lease it does not own")
    void doesNotDisturbTheRefreshLease() {
        store.upsert(grant("gen-1", "original"));
        Instant leaseUntil = Instant.now().plusSeconds(60);
        assertTrue(store.claimRefresh(TENANT, CONNECTION, PRINCIPAL, "another-replica", leaseUntil), "the lease must start out held by somebody");

        assertTrue(store.updateSealedTokens(grant("gen-2", "resealed"), stored().getVersion()));

        var after = stored();
        assertEquals("another-replica", after.getRefreshInProgress(),
                "clearing the claim would reopen the double-refresh window the lease exists to close");
        assertNotNull(after.getRefreshLeaseExpiresAt(), "and dropping the expiry would leave a claim nothing can ever free");
        assertEquals("gen-2:access-resealed", after.getEncryptedAccessToken(), "the re-seal itself must still have happened");
    }

    @Test
    @DisplayName("a re-seal of a grant that is not there creates nothing")
    void doesNotUpsert() {
        // Disconnect, or the whole connection deleted, mid-sweep. An upsert here would
        // resurrect a grant the user revoked — tokens outliving the consent.
        assertFalse(store.updateSealedTokens(grant("gen-2", "resealed"), 1L));

        assertTrue(store.find(TENANT, CONNECTION, PRINCIPAL).isEmpty(), "a conditional write must not be an upsert");
    }
}
