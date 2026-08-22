/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

import ai.labs.eddi.secrets.ISecretProvider.SealedValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The defect: DEK rotation re-encrypted the vault's secret collection and
 * nothing else, then replaced the DEK — so every OAuth grant in the tenant
 * became permanently undecryptable and every user silently lost their linked
 * account. These tests are about what is in the store afterwards.
 */
class ConnectionGrantResealerTest {

    private static final String TENANT = "acme";

    /** Stands in for the vault's old-DEK-to-new-DEK transform. */
    private static final UnaryOperator<SealedValue> REKEY = sealed -> sealed == null
            ? null
            : new SealedValue(sealed.ciphertext().replace("old:", "new:"), "iv2");

    private InMemoryConnectionGrantStore store;
    private ConnectionGrantResealer resealer;

    @BeforeEach
    void setUp() {
        store = new InMemoryConnectionGrantStore();
        resealer = new ConnectionGrantResealer(store);
    }

    private ConnectionGrant grant(String connectionName, String principal, String refreshCiphertext) {
        var grant = new ConnectionGrant();
        grant.setTenantId(TENANT);
        grant.setConnectionName(connectionName);
        grant.setPrincipal(principal);
        grant.setEncryptedAccessToken("old:access-" + principal);
        grant.setAccessTokenIv("iv1");
        grant.setEncryptedRefreshToken(refreshCiphertext);
        grant.setRefreshTokenIv(refreshCiphertext == null ? null : "iv1");
        grant.setStatus(ConnectionGrant.Status.ACTIVE);
        grant.setExpiresAt(Instant.now().plusSeconds(3600));
        return grant;
    }

    @Test
    @DisplayName("every grant in the tenant is carried across to the new key")
    void resealsEveryGrant() {
        store.upsert(grant("jira", "alice", "old:refresh-alice"));
        store.upsert(grant("drive", "bob", "old:refresh-bob"));

        assertEquals(2, resealer.resealAll(TENANT, REKEY));

        var alice = store.find(TENANT, "jira", "alice").orElseThrow();
        assertEquals("new:access-alice", alice.getEncryptedAccessToken());
        assertEquals("new:refresh-alice", alice.getEncryptedRefreshToken());
        assertEquals("iv2", alice.getRefreshTokenIv(), "the IV must travel with the ciphertext or it cannot be opened");
        assertEquals("new:refresh-bob", store.find(TENANT, "drive", "bob").orElseThrow().getEncryptedRefreshToken());
    }

    @Test
    @DisplayName("a grant with no refresh token keeps having none, rather than gaining an empty one")
    void tolerAtesAMissingRefreshToken() {
        store.upsert(grant("analytics", "__service__", null));

        assertEquals(1, resealer.resealAll(TENANT, REKEY));

        var stored = store.find(TENANT, "analytics", "__service__").orElseThrow();
        assertEquals("new:access-__service__", stored.getEncryptedAccessToken());
        assertNull(stored.getEncryptedRefreshToken(), "client_credentials grants legitimately have no refresh token");
    }

    @Test
    @DisplayName("another tenant's grants are left alone")
    void isScopedToOneTenant() {
        store.upsert(grant("jira", "alice", "old:refresh-alice"));
        var other = grant("jira", "carol", "old:refresh-carol");
        other.setTenantId("other-tenant");
        store.upsert(other);

        resealer.resealAll(TENANT, REKEY);

        assertEquals("old:refresh-carol", store.find("other-tenant", "jira", "carol").orElseThrow().getEncryptedRefreshToken(),
                "a DEK is per tenant; re-sealing another tenant's rows with it would destroy them");
    }

    @Test
    @DisplayName("nothing is written when a value cannot be re-sealed")
    void writesNothingIfAnyValueFails() {
        // Prepare-then-commit is the contract, and this is why: rotation aborts with
        // the OLD DEK still in place, so a half-written tenant would be a tenant
        // whose grants neither key opens.
        store.upsert(grant("jira", "alice", "old:refresh-alice"));
        store.upsert(grant("drive", "bob", "old:refresh-bob"));

        assertThrows(IllegalStateException.class, () -> resealer.resealAll(TENANT, sealed -> {
            throw new IllegalStateException("crypto failure");
        }));

        assertTrue(store.find(TENANT, "jira", "alice").orElseThrow().getEncryptedAccessToken().startsWith("old:"));
        assertTrue(store.find(TENANT, "drive", "bob").orElseThrow().getEncryptedAccessToken().startsWith("old:"));
    }

    @Test
    @DisplayName("an empty tenant is a no-op, not an error")
    void emptyTenantIsFine() {
        assertEquals(0, resealer.resealAll(TENANT, REKEY));
    }
}
