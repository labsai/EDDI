/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

import ai.labs.eddi.secrets.ISecretProvider.SealedValue;
import ai.labs.eddi.secrets.model.EncryptedDek;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The defect: DEK rotation re-sealed the vault's secret collection and nothing
 * else, so every OAuth grant in the tenant lost its key and every user silently
 * lost their linked account.
 * <p>
 * Rotation is now additive — it adds a generation and sweeps rows onto it — so
 * these tests are about a migration, not a rescue. {@code resealAll} returns
 * how many rows are still on an OLDER generation, so zero is the success value;
 * a row it leaves behind is not lost, because the generation it names still
 * exists and still decrypts.
 */
class ConnectionGrantResealerTest {

    private static final String TENANT = "acme";
    private static final String GEN_1 = EncryptedDek.dekId(TENANT, 1);
    private static final String GEN_2 = EncryptedDek.dekId(TENANT, 2);

    private InMemoryConnectionGrantStore store;
    private ConnectionGrantResealer resealer;

    @BeforeEach
    void setUp() {
        store = new InMemoryConnectionGrantStore();
        resealer = new ConnectionGrantResealer(store);
    }

    /**
     * Stands in for the vault's re-seal. Ciphertext is written
     * {@code <dekId>:<plaintext>}, so opening a value with the wrong generation is
     * detectable — which is the whole reason a grant carries its own {@code dekId},
     * and the property the assertion inside this operator pins.
     */
    private static UnaryOperator<SealedValue> rekeyTo(String activeDekId) {
        return sealed -> {
            if (sealed == null || sealed.ciphertext() == null) {
                return sealed;
            }
            String[] parts = sealed.ciphertext().split(":", 2);
            assertEquals(parts[0], sealed.dekId(), "a value must be opened with the generation it was actually sealed under");
            return new SealedValue(activeDekId + ":" + parts[1], "iv-" + activeDekId, activeDekId);
        };
    }

    /** Replaces the store with one that writes inside the sweep's guard window. */
    private void raceDuringSweep(int times, Runnable interference) {
        store = new RacingStore(times, interference);
        resealer = new ConnectionGrantResealer(store);
    }

    private ConnectionGrant grant(String connectionName, String principal, String dekId, boolean withRefreshToken) {
        var grant = new ConnectionGrant();
        grant.setTenantId(TENANT);
        grant.setConnectionName(connectionName);
        grant.setPrincipal(principal);
        grant.setEncryptedAccessToken(dekId + ":access-" + principal);
        grant.setAccessTokenIv("iv-" + dekId);
        grant.setEncryptedRefreshToken(withRefreshToken ? dekId + ":refresh-" + principal : null);
        grant.setRefreshTokenIv(withRefreshToken ? "iv-" + dekId : null);
        grant.setDekId(dekId);
        grant.setStatus(ConnectionGrant.Status.ACTIVE);
        grant.setExpiresAt(Instant.now().plusSeconds(3600));
        return grant;
    }

    private ConnectionGrant stored(String connectionName, String principal) {
        return store.find(TENANT, connectionName, principal).orElseThrow();
    }

    @Test
    @DisplayName("a fully swept tenant reports nothing outstanding")
    void movesEveryGrantOntoTheActiveGeneration() {
        store.upsert(grant("jira", "alice", GEN_1, true));
        store.upsert(grant("drive", "bob", GEN_1, true));

        assertEquals(0, resealer.resealAll(TENANT, GEN_2, rekeyTo(GEN_2)), "zero means fully migrated, not zero rows touched");

        var alice = stored("jira", "alice");
        assertEquals(GEN_2 + ":access-alice", alice.getEncryptedAccessToken());
        assertEquals(GEN_2 + ":refresh-alice", alice.getEncryptedRefreshToken());
        assertEquals("iv-" + GEN_2, alice.getRefreshTokenIv(), "the IV must travel with the ciphertext or it cannot be opened");
        assertEquals(GEN_2, alice.getDekId(), "without the new dekId the next read opens it with the wrong key");
        assertEquals(GEN_2 + ":refresh-bob", stored("drive", "bob").getEncryptedRefreshToken());
    }

    @Test
    @DisplayName("a tenant holding both generations has only its older rows swept")
    void leavesRowsAlreadyOnTheActiveGeneration() {
        // The normal state after an interrupted rotation, and the case where
        // re-sealing indiscriminately destroys a row: the second grant would be
        // opened with the active key and then sealed with it a second time.
        store.upsert(grant("jira", "alice", GEN_1, true));
        store.upsert(grant("drive", "bob", GEN_2, true));
        String untouched = stored("drive", "bob").getEncryptedAccessToken();

        assertEquals(0, resealer.resealAll(TENANT, GEN_2, rekeyTo(GEN_2)));

        assertEquals(GEN_2 + ":access-alice", stored("jira", "alice").getEncryptedAccessToken(), "the older row must be migrated");
        assertEquals(untouched, stored("drive", "bob").getEncryptedAccessToken(), "a row already on the active generation must be left alone");
    }

    @Test
    @DisplayName("a re-seal is invisible to a refresh: no version bump, no lease change")
    void doesNotDisturbLifecycleFields() {
        store.upsert(grant("jira", "alice", GEN_1, true));
        store.claimRefresh(TENANT, "jira", "alice", "another-replica", Instant.now().plusSeconds(60));
        long versionBefore = stored("jira", "alice").getVersion();

        assertEquals(0, resealer.resealAll(TENANT, GEN_2, rekeyTo(GEN_2)));

        var after = stored("jira", "alice");
        assertEquals(versionBefore, after.getVersion(), "bumping the version would fail a refresh CAS that had nothing wrong with it");
        assertEquals("another-replica", after.getRefreshInProgress(), "the sweep must not steal or clear a lease it does not own");
        assertNotNull(after.getRefreshLeaseExpiresAt());
    }

    @Test
    @DisplayName("a refresh that lands mid-sweep keeps its own tokens")
    void doesNotClobberAConcurrentRefresh() {
        // A refresh completing between the sweep's read and its guarded write. It
        // sealed with the ACTIVE generation, so the row is already where the sweep
        // wanted it — and re-sealing over it would hand back the refresh token the
        // provider has just rotated away, logging the user out.
        raceDuringSweep(1, () -> {
            var refreshed = grant("jira", "alice", GEN_2, true);
            refreshed.setEncryptedRefreshToken(GEN_2 + ":refresh-rotated");
            store.completeRefresh(refreshed, stored("jira", "alice").getVersion());
        });
        store.upsert(grant("jira", "alice", GEN_1, true));

        assertEquals(0, resealer.resealAll(TENANT, GEN_2, rekeyTo(GEN_2)),
                "the refresh already moved the row onto the active generation, so nothing is outstanding");

        assertEquals(GEN_2 + ":refresh-rotated", stored("jira", "alice").getEncryptedRefreshToken(),
                "the sweep must not replace a live refresh token with a re-seal of the one it replaced");
    }

    @Test
    @DisplayName("a row being rewritten continuously is left behind and counted, not forced through")
    void reportsARowItCannotWin() {
        // Loses the guard on both attempts. The row is still on an older generation
        // afterwards, which is exactly what the return value says.
        raceDuringSweep(2, () -> store.upsert(grant("jira", "alice", GEN_1, true)));
        store.upsert(grant("jira", "alice", GEN_1, true));

        assertEquals(1, resealer.resealAll(TENANT, GEN_2, rekeyTo(GEN_2)));

        var after = stored("jira", "alice");
        assertEquals(GEN_1, after.getDekId(), "the row keeps naming a generation that still exists, so it still opens");
        assertEquals(GEN_1 + ":access-alice", after.getEncryptedAccessToken());
    }

    @Test
    @DisplayName("a grant deleted mid-sweep is not counted as outstanding")
    void aDisconnectMidSweepIsNotAFailure() {
        raceDuringSweep(1, () -> store.delete(TENANT, "jira", "alice"));
        store.upsert(grant("jira", "alice", GEN_1, true));

        assertEquals(0, resealer.resealAll(TENANT, GEN_2, rekeyTo(GEN_2)),
                "there is nothing left to re-seal, which is not a row left behind");
        assertTrue(store.find(TENANT, "jira", "alice").isEmpty());
    }

    @Test
    @DisplayName("a grant with no refresh token keeps having none, rather than gaining an empty one")
    void toleratesAMissingRefreshToken() {
        store.upsert(grant("analytics", "__service__", GEN_1, false));

        assertEquals(0, resealer.resealAll(TENANT, GEN_2, rekeyTo(GEN_2)));

        var grant = stored("analytics", "__service__");
        assertEquals(GEN_2 + ":access-__service__", grant.getEncryptedAccessToken());
        assertNull(grant.getEncryptedRefreshToken(), "client_credentials grants legitimately have no refresh token");
        assertNull(grant.getRefreshTokenIv());
    }

    @Test
    @DisplayName("another tenant's grants are left alone")
    void isScopedToOneTenant() {
        store.upsert(grant("jira", "alice", GEN_1, true));
        var other = grant("jira", "carol", GEN_1, true);
        other.setTenantId("other-tenant");
        store.upsert(other);

        resealer.resealAll(TENANT, GEN_2, rekeyTo(GEN_2));

        var carol = store.find("other-tenant", "jira", "carol").orElseThrow();
        assertEquals(GEN_1 + ":refresh-carol", carol.getEncryptedRefreshToken(),
                "a DEK is per tenant; re-sealing another tenant's rows with it would destroy them");
        assertEquals(GEN_1, carol.getDekId());
    }

    @Test
    @DisplayName("a re-seal failure ends the sweep, and every row it did not reach still opens")
    void aFailedResealLeavesEveryRowReadable() {
        // Deliberately NOT the old prepare-then-commit contract. The new generation
        // is already committed when this runs and the old one is never deleted, so a
        // half-swept tenant is one part-way through a migration, not one whose
        // grants no key opens. Rotation catches this, counts the participant as
        // incomplete, and reports that re-running is safe.
        store.upsert(grant("jira", "alice", GEN_1, true));
        store.upsert(grant("drive", "bob", GEN_1, true));

        UnaryOperator<SealedValue> rekey = rekeyTo(GEN_2);
        // The third call is the second grant's access token, whichever grant the
        // store happens to hand over first — so this does not depend on iteration
        // order to leave exactly one row migrated.
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> resealer.resealAll(TENANT, GEN_2, sealed -> {
            if (calls.incrementAndGet() == 3) {
                throw new IllegalStateException("crypto failure");
            }
            return rekey.apply(sealed);
        }));

        long migrated = store.findByTenant(TENANT).stream().filter(g -> GEN_2.equals(g.getDekId())).count();
        assertEquals(1, migrated, "a row the sweep already moved stays moved");
        for (ConnectionGrant grant : store.findByTenant(TENANT)) {
            assertTrue(grant.getEncryptedAccessToken().startsWith(grant.getDekId() + ":"),
                    "every row must still name the generation its ciphertext is sealed under: " + grant);
        }
    }

    @Test
    @DisplayName("an empty tenant is a no-op, not an error")
    void emptyTenantIsFine() {
        assertEquals(0, resealer.resealAll(TENANT, GEN_2, rekeyTo(GEN_2)));
    }

    /**
     * Lands a concurrent write in the window between the sweep's read and its
     * guarded write — the only window the version guard exists to cover.
     */
    private static final class RacingStore extends InMemoryConnectionGrantStore {

        private final Runnable interference;
        private int remaining;

        private RacingStore(int times, Runnable interference) {
            this.remaining = times;
            this.interference = interference;
        }

        @Override
        public synchronized boolean updateSealedTokens(ConnectionGrant grant, long expectedVersion) {
            if (remaining > 0) {
                remaining--;
                interference.run();
            }
            return super.updateSealedTokens(grant, expectedVersion);
        }
    }
}
