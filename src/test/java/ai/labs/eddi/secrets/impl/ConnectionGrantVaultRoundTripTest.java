/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.connections.grants.ConnectionGrant;
import ai.labs.eddi.connections.grants.ConnectionGrantResealer;
import ai.labs.eddi.connections.grants.InMemoryConnectionGrantStore;
import ai.labs.eddi.secrets.ISecretProvider.SealedValue;
import ai.labs.eddi.secrets.ISecretProvider.SecretProviderException;
import ai.labs.eddi.secrets.SealedDataRotationParticipant;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The grant contexts, the real vault and the real resealer together. The unit
 * tests of each use a stand-in for the other two, so none of them would notice
 * the three call sites (seal, open, rotation) disagreeing about a context — the
 * grant would then simply stop opening after the first rotation. Real AES-GCM
 * throughout.
 */
class ConnectionGrantVaultRoundTripTest {

    private static final String MASTER = "master-key-one-1234567890";
    private static final String TENANT = "acme";

    private InMemoryConnectionGrantStore store;
    private VaultSecretProvider vault;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new InMemoryConnectionGrantStore();
        Instance<SealedDataRotationParticipant> participants = mock(Instance.class);
        when(participants.isUnsatisfied()).thenReturn(false);
        when(participants.iterator())
                .thenAnswer(inv -> List.<SealedDataRotationParticipant>of(new ConnectionGrantResealer(store, new SimpleMeterRegistry())).iterator());
        var persistence = new InMemorySecretPersistence();
        vault = new VaultSecretProvider(Optional.of(MASTER), persistence, new VaultSaltManager(persistence), new SimpleMeterRegistry(), participants);
        vault.initMetrics();
        vault.onStartup(mock(StartupEvent.class));
    }

    private ConnectionGrant boundGrant(String connection, String principal, String access, String refresh) throws Exception {
        List<SealedValue> sealed = vault.sealAll(TENANT, List.of(access, refresh),
                List.of(ConnectionGrant.accessTokenContext(connection, principal), ConnectionGrant.refreshTokenContext(connection, principal)));
        return grant(connection, principal, sealed.get(0), sealed.get(1));
    }

    private ConnectionGrant legacyGrant(String connection, String principal, String access, String refresh) throws Exception {
        return grant(connection, principal, vault.seal(TENANT, access), vault.seal(TENANT, refresh));
    }

    private static ConnectionGrant grant(String connection, String principal, SealedValue access, SealedValue refresh) {
        var grant = new ConnectionGrant();
        grant.setTenantId(TENANT);
        grant.setConnectionName(connection);
        grant.setPrincipal(principal);
        grant.setEncryptedAccessToken(access.ciphertext());
        grant.setAccessTokenIv(access.iv());
        grant.setEncryptedRefreshToken(refresh.ciphertext());
        grant.setRefreshTokenIv(refresh.iv());
        grant.setDekId(access.dekId());
        grant.setStatus(ConnectionGrant.Status.ACTIVE);
        grant.setExpiresAt(Instant.now().plusSeconds(3600));
        return grant;
    }

    private String openAccess(ConnectionGrant row, String asConnection, String asPrincipal) throws SecretProviderException {
        return vault.unseal(TENANT, new SealedValue(row.getEncryptedAccessToken(), row.getAccessTokenIv(), row.getDekId()),
                ConnectionGrant.accessTokenContext(asConnection, asPrincipal));
    }

    private String openRefresh(ConnectionGrant row, String asConnection, String asPrincipal) throws SecretProviderException {
        return vault.unseal(TENANT, new SealedValue(row.getEncryptedRefreshToken(), row.getRefreshTokenIv(), row.getDekId()),
                ConnectionGrant.refreshTokenContext(asConnection, asPrincipal));
    }

    @Test
    @DisplayName("after a rotation every grant — legacy or bound — opens under its own row and field only")
    void rotationKeepsGrantsOpenAndBound() throws Exception {
        store.upsert(legacyGrant("jira", "alice", "alice-access", "alice-refresh"));
        store.upsert(boundGrant("jira", "bob", "bob-access", "bob-refresh"));

        vault.rotateDek(TENANT);

        ConnectionGrant alice = store.find(TENANT, "jira", "alice").orElseThrow();
        ConnectionGrant bob = store.find(TENANT, "jira", "bob").orElseThrow();
        assertEquals("alice-access", openAccess(alice, "jira", "alice"));
        assertEquals("alice-refresh", openRefresh(alice, "jira", "alice"));
        assertEquals("bob-access", openAccess(bob, "jira", "bob"));
        assertEquals("bob-refresh", openRefresh(bob, "jira", "bob"));

        // Alice's sealed tokens copied into Bob's row (or into another connection's,
        // or her access token into the refresh field) must not open there — also for
        // the grant that was legacy before the sweep.
        assertThrows(SecretProviderException.class, () -> openAccess(alice, "jira", "bob"));
        assertThrows(SecretProviderException.class, () -> openAccess(alice, "drive", "alice"));
        assertThrows(SecretProviderException.class, () -> openRefresh(alice, "jira", "bob"));
        assertThrows(SecretProviderException.class,
                () -> vault.unseal(TENANT, new SealedValue(alice.getEncryptedAccessToken(), alice.getAccessTokenIv(), alice.getDekId()),
                        ConnectionGrant.refreshTokenContext("jira", "alice")));
    }

    @Test
    @DisplayName("a grant nobody can open is skipped: the rotation reports it, and every other grant still moves")
    void unopenableGrantDoesNotStrandTheRest() throws Exception {
        ConnectionGrant broken = legacyGrant("jira", "aaron", "x", "y");
        broken.setEncryptedAccessToken("bm90LWEtdmFsaWQtY2lwaGVydGV4dA==");
        store.upsert(broken);
        store.upsert(legacyGrant("jira", "zed", "zed-access", "zed-refresh"));
        String before = store.find(TENANT, "jira", "zed").orElseThrow().getDekId();

        assertThrows(SecretProviderException.class, () -> vault.rotateDek(TENANT), "the unopenable grant is still outstanding");

        ConnectionGrant zed = store.find(TENANT, "jira", "zed").orElseThrow();
        assertNotEquals(before, zed.getDekId(), "the grant after the broken one must have moved to the new generation");
        assertEquals("zed-access", openAccess(zed, "jira", "zed"));
    }
}
