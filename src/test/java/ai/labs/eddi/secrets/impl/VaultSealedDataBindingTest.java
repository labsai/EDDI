/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The generic {@code seal}/{@code unseal} API used for OAuth grant tokens:
 * context binding (AAD), backward compatibility with values sealed before it,
 * one DEK generation per row, and the rotation sweep re-sealing in the bound
 * form. Real AES-GCM over the in-memory persistence — every claim is "this
 * opens" or "this refuses", which only a real decrypt shows.
 */
class VaultSealedDataBindingTest {

    private static final String MASTER = "master-key-one-1234567890";
    private static final String TENANT = "acme";
    private static final String CONTEXT_A = "connection-grant|access|4:jira|5:alice";
    private static final String CONTEXT_B = "connection-grant|access|4:jira|3:bob";

    private InMemorySecretPersistence persistence;

    @BeforeEach
    void setUp() {
        persistence = new InMemorySecretPersistence();
    }

    private VaultSecretProvider provider(Instance<SealedDataRotationParticipant> participants) {
        var provider = new VaultSecretProvider(Optional.of(MASTER), persistence, new VaultSaltManager(persistence), new SimpleMeterRegistry(),
                participants);
        provider.initMetrics();
        provider.onStartup(mock(StartupEvent.class));
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static Instance<SealedDataRotationParticipant> participants(SealedDataRotationParticipant... participants) {
        Instance<SealedDataRotationParticipant> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.iterator()).thenAnswer(inv -> List.of(participants).iterator());
        return instance;
    }

    @Test
    @DisplayName("a bound value opens with its own context and refuses another row's")
    void boundValueRefusesAnotherContext() throws Exception {
        var vault = provider(null);
        SealedValue sealed = vault.seal(TENANT, "token-a", CONTEXT_A);

        assertEquals("token-a", vault.unseal(TENANT, sealed, CONTEXT_A));
        // Copied into bob's grant row: must not open there.
        assertThrows(SecretProviderException.class, () -> vault.unseal(TENANT, sealed, CONTEXT_B));
        // Nor through the unbound API.
        assertThrows(SecretProviderException.class, () -> vault.unseal(TENANT, sealed));
    }

    @Test
    @DisplayName("the context's length prefix counts UTF-8 bytes, the unit the AAD is encoded in")
    void contextLengthPrefixIsInBytes() throws Exception {
        String aad = new String(VaultSecretProvider.sealedDataAad(TENANT, "acme#g1", "jürgen"), StandardCharsets.UTF_8);
        assertTrue(aad.endsWith("|7:jürgen"), aad);

        var vault = provider(null);
        SealedValue sealed = vault.seal(TENANT, "token", "connection-grant|access|4:jira|6:jürgen");
        assertEquals("token", vault.unseal(TENANT, sealed, "connection-grant|access|4:jira|6:jürgen"));
    }

    @Test
    @DisplayName("a value sealed before binding existed still opens through the bound API")
    void legacyUnboundValueStillOpens() throws Exception {
        var vault = provider(null);
        SealedValue legacy = vault.seal(TENANT, "old-token");

        assertEquals("old-token", vault.unseal(TENANT, legacy, CONTEXT_A));
    }

    @Test
    @DisplayName("sealAll seals every field of a row under one generation, even across a rotation")
    void sealAllUsesOneGenerationForTheRow() throws Exception {
        var vault = provider(null);
        vault.seal(TENANT, "warm-up", CONTEXT_A);
        vault.rotateDek(TENANT);

        List<SealedValue> sealed = vault.sealAll(TENANT, Arrays.asList("access", "refresh", null),
                List.of(CONTEXT_A, CONTEXT_A + "|refresh", CONTEXT_B));

        assertEquals(sealed.get(0).dekId(), sealed.get(1).dekId());
        assertNull(sealed.get(2), "a null value stays absent rather than sealing to something");
        assertEquals("refresh", vault.unseal(TENANT, sealed.get(1), CONTEXT_A + "|refresh"));
    }

    @Test
    @DisplayName("a DEK rotation re-seals participant data in the bound form")
    void rotationResealsBound() throws Exception {
        List<SealedValue> rows = new ArrayList<>();
        SealedDataRotationParticipant participant = new SealedDataRotationParticipant() {
            @Override
            public String sealedDataDescription() {
                return "test rows";
            }

            @Override
            public int resealAll(String tenantId, String activeDekId, Resealer resealer) {
                rows.set(0, resealer.reseal(rows.get(0), CONTEXT_A));
                return 0;
            }

            @Override
            public int discardAll(String tenantId) {
                return 0;
            }
        };
        var vault = provider(participants(participant));
        SealedValue legacy = vault.seal(TENANT, "old-token");
        rows.add(legacy);

        vault.rotateDek(TENANT);

        SealedValue migrated = rows.getFirst();
        assertNotEquals(legacy.dekId(), migrated.dekId());
        assertEquals("old-token", vault.unseal(TENANT, migrated, CONTEXT_A));
        assertThrows(SecretProviderException.class, () -> vault.unseal(TENANT, migrated),
                "after the sweep the value is bound — the unbound form no longer opens it");
    }
}
