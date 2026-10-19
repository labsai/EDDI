/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider.SecretNotFoundException;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The provider end of M1: no write path grants a secret to every agent by
 * default any more, and {@code allowedHosts} rides along with the row. Real
 * crypto over the in-memory persistence, as in
 * {@code VaultSecretProviderGrantTest}.
 */
class VaultSecretProviderAllowedHostsTest {

    private static final String MASTER_KEY = "test-master-key-12345678901234";
    private static final byte[] FIXED_SALT = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final SecretReference REF = new SecretReference("test-tenant", "llm-api-key");

    private InMemorySecretPersistence persistence;
    private VaultSecretProvider provider;

    @BeforeEach
    void setUp() {
        persistence = new InMemorySecretPersistence();
        VaultSaltManager saltManager = mock(VaultSaltManager.class);
        when(saltManager.getSalt()).thenReturn(FIXED_SALT);
        when(saltManager.isUsingLegacySalt()).thenReturn(false);
        provider = new VaultSecretProvider(Optional.of(MASTER_KEY), persistence, saltManager, new SimpleMeterRegistry());
        provider.initMetrics();
        provider.onStartup(mock(StartupEvent.class));
    }

    @Test
    @DisplayName("a create without a grant is refused by both write paths — the wildcard is said, never defaulted")
    void createNeedsAnExplicitGrant() {
        var error = assertThrows(IllegalArgumentException.class, () -> provider.store(REF, "value", "d", null));
        assertTrue(error.getMessage().contains("allowedAgents"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> provider.storeIfAbsent(REF, "value", "d", null));
        assertTrue(persistence.secrets.isEmpty(), "nothing may be written without a grant");
    }

    @Test
    @DisplayName("storeIfAbsent writes allowedHosts in the same insert, so the secret is never visible unrestricted")
    void storeIfAbsentCarriesHosts() throws Exception {
        assertTrue(provider.storeIfAbsent(REF, "value", "d", List.of("*"), List.of("api.anthropic.com")));

        assertEquals(List.of("api.anthropic.com"), provider.getMetadata(REF).allowedHosts());
        assertEquals("value", provider.resolve(REF));
    }

    @Test
    @DisplayName("a rotation without a grant keeps the stored one")
    void rotationKeepsGrant() throws Exception {
        provider.store(REF, "value", "d", List.of("agent-a"));

        provider.store(REF, "value-2", null, null);

        assertEquals(List.of("agent-a"), provider.getMetadata(REF).allowedAgents());
        assertEquals("value-2", provider.resolve(REF));
    }

    @Test
    @DisplayName("allowedHosts is stored with the secret, kept on a rotation that omits it, and read back in the metadata")
    void hostsRoundTrip() throws Exception {
        provider.store(REF, "value", "d", List.of("*"), List.of("api.openai.com"));
        assertEquals(List.of("api.openai.com"), provider.getMetadata(REF).allowedHosts());
        assertEquals(List.of("api.openai.com"), provider.listKeys("test-tenant").get(0).allowedHosts());

        provider.store(REF, "value-2", null, null, null);
        assertEquals(List.of("api.openai.com"), provider.getMetadata(REF).allowedHosts());

        provider.store(REF, "value-3", null, null, List.of());
        assertEquals(List.of(), provider.getMetadata(REF).allowedHosts());
    }

    @Test
    @DisplayName("a secret created without a list has none — the pre-existing shape")
    void noListByDefault() throws Exception {
        provider.store(REF, "value", "d", List.of("*"));
        assertNull(provider.getMetadata(REF).allowedHosts());
    }

    @Test
    @DisplayName("updateAllowedHosts changes the list without the value, and the value still decrypts")
    void updateAllowedHosts() throws Exception {
        provider.store(REF, "value", "d", List.of("agent-a"));

        var after = provider.updateAllowedHosts(REF, List.of("api.openai.com:443"));

        assertEquals(List.of("api.openai.com:443"), after.allowedHosts());
        assertEquals(List.of("agent-a"), after.allowedAgents());
        assertEquals(List.of("api.openai.com:443"), provider.getMetadata(REF).allowedHosts());
        assertEquals("value", provider.resolve(REF));
        assertEquals(List.of(), provider.updateAllowedHosts(REF, null).allowedHosts());
    }

    @Test
    @DisplayName("updateAllowedHosts on a missing secret is not-found, and creates nothing")
    void updateAllowedHostsMissing() {
        assertThrows(SecretNotFoundException.class, () -> provider.updateAllowedHosts(REF, List.of("api.openai.com")));
        assertTrue(persistence.secrets.isEmpty());
    }
}
