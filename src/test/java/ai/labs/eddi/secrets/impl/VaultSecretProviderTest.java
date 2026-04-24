/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VaultSecretProvider}. Uses a mock
 * {@link ISecretPersistence} to verify encryption, decryption,
 * store/delete/list logic, tenant-scoped DEK management, and metadata
 * round-trips.
 */
class VaultSecretProviderTest {

    private ISecretPersistence persistence;
    private SimpleMeterRegistry meterRegistry;
    private VaultSaltManager saltManager;
    private VaultSecretProvider provider;

    @BeforeEach
    void setUp() {
        persistence = mock(ISecretPersistence.class);
        meterRegistry = new SimpleMeterRegistry();
        saltManager = createLegacySaltManager();
        provider = new VaultSecretProvider(Optional.of("test-master-key-32chars!!"), persistence, saltManager, meterRegistry);
        provider.initMetrics();
        // Simulate startup
        provider.onStartup(new io.quarkus.runtime.StartupEvent());
    }

    /**
     * Creates a VaultSaltManager that uses the legacy fixed salt (backward compat).
     */
    private VaultSaltManager createLegacySaltManager() {
        var sm = new VaultSaltManager(persistence);
        // Initialize will use legacy salt since persistence mock returns null for meta
        sm.initialize();
        return sm;
    }

    @Test
    void isAvailable_whenMasterKeySet() {
        assertTrue(provider.isAvailable());
    }

    @Test
    void isAvailable_whenMasterKeyNotSet() {
        var disabledProvider = new VaultSecretProvider(Optional.empty(), persistence, saltManager, meterRegistry);
        disabledProvider.initMetrics();
        disabledProvider.onStartup(new io.quarkus.runtime.StartupEvent());
        assertFalse(disabledProvider.isAvailable());
    }

    @Test
    void store_createsNewDekForNewTenant() throws Exception {
        when(persistence.findDek("default")).thenReturn(Optional.empty());
        when(persistence.findSecret("default", "myKey")).thenReturn(Optional.empty());

        provider.store(new SecretReference("default", "myKey"), "mySecretValue", "test description", List.of("*"));

        // Should create a new DEK
        verify(persistence).upsertDek(any(EncryptedDek.class));
        // Should store the encrypted secret
        ArgumentCaptor<EncryptedSecret> captor = ArgumentCaptor.forClass(EncryptedSecret.class);
        verify(persistence).upsertSecret(captor.capture());

        EncryptedSecret stored = captor.getValue();
        assertEquals("default", stored.getTenantId());
        assertEquals("myKey", stored.getKeyName());
        assertEquals("test description", stored.getDescription());
        assertEquals(List.of("*"), stored.getAllowedAgents());
        assertNotNull(stored.getCreatedAt());
        assertNull(stored.getLastRotatedAt()); // New secret, not rotated
        assertNotNull(stored.getChecksum());
    }

    @Test
    void store_rotationSetsLastRotatedAt() throws Exception {
        Instant createdAt = Instant.now().minusSeconds(86400);
        var existing = new EncryptedSecret("id-1", "default", "myKey", "encVal", "iv", "default", "oldChecksum", "old desc", List.of("*"), createdAt,
                null, null);
        when(persistence.findDek("default")).thenReturn(Optional.empty());
        when(persistence.findSecret("default", "myKey")).thenReturn(Optional.of(existing));

        provider.store(new SecretReference("default", "myKey"), "newValue", "updated desc", List.of("agent1"));

        ArgumentCaptor<EncryptedSecret> captor = ArgumentCaptor.forClass(EncryptedSecret.class);
        // upsertSecret is called once for the secret, and once more internally
        verify(persistence, atLeastOnce()).upsertSecret(captor.capture());

        EncryptedSecret stored = captor.getValue();
        assertEquals("id-1", stored.getId()); // Preserves original ID
        assertEquals(createdAt, stored.getCreatedAt()); // Preserves original createdAt
        assertNotNull(stored.getLastRotatedAt()); // Rotation timestamp set
        assertEquals("updated desc", stored.getDescription());
        assertEquals(List.of("agent1"), stored.getAllowedAgents());
    }

    @Test
    void resolve_roundTrip() throws Exception {
        // Store a secret first
        when(persistence.findDek("default")).thenReturn(Optional.empty());
        when(persistence.findSecret("default", "roundtrip")).thenReturn(Optional.empty());

        provider.store(new SecretReference("default", "roundtrip"), "secretPlaintext", null, null);

        // Capture what was stored
        ArgumentCaptor<EncryptedSecret> secretCaptor = ArgumentCaptor.forClass(EncryptedSecret.class);
        verify(persistence, atLeastOnce()).upsertSecret(secretCaptor.capture());
        EncryptedSecret storedSecret = secretCaptor.getValue();

        ArgumentCaptor<EncryptedDek> dekCaptor = ArgumentCaptor.forClass(EncryptedDek.class);
        verify(persistence).upsertDek(dekCaptor.capture());
        EncryptedDek storedDek = dekCaptor.getValue();

        // Now resolve — mock returns the captured values
        when(persistence.findSecret("default", "roundtrip")).thenReturn(Optional.of(storedSecret));
        when(persistence.findDek("default")).thenReturn(Optional.of(storedDek));

        String resolved = provider.resolve(new SecretReference("default", "roundtrip"));
        assertEquals("secretPlaintext", resolved);
    }

    @Test
    void resolve_throwsSecretNotFoundException() {
        when(persistence.findSecret("default", "nonexistent")).thenReturn(Optional.empty());
        assertThrows(ISecretProvider.SecretNotFoundException.class, () -> provider.resolve(new SecretReference("default", "nonexistent")));
    }

    @Test
    void delete_success() throws Exception {
        when(persistence.deleteSecret("default", "myKey")).thenReturn(true);
        assertDoesNotThrow(() -> provider.delete(new SecretReference("default", "myKey")));
        verify(persistence).deleteSecret("default", "myKey");
    }

    @Test
    void delete_throwsNotFound() {
        when(persistence.deleteSecret("default", "missing")).thenReturn(false);
        assertThrows(ISecretProvider.SecretNotFoundException.class, () -> provider.delete(new SecretReference("default", "missing")));
    }

    @Test
    void getMetadata_returnsMappedFields() throws Exception {
        Instant now = Instant.now();
        var secret = new EncryptedSecret("id-1", "default", "apiKey", "enc", "iv", "default", "checksum123", "my API key",
                List.of("agent1", "agent2"), now.minusSeconds(100), now, now.minusSeconds(50));
        when(persistence.findSecret("default", "apiKey")).thenReturn(Optional.of(secret));

        SecretMetadata meta = provider.getMetadata(new SecretReference("default", "apiKey"));

        assertEquals("default", meta.tenantId());
        assertEquals("apiKey", meta.keyName());
        assertEquals("checksum123", meta.checksum());
        assertEquals("my API key", meta.description());
        assertEquals(List.of("agent1", "agent2"), meta.allowedAgents());
        assertNotNull(meta.createdAt());
        assertNotNull(meta.lastAccessedAt());
        assertNotNull(meta.lastRotatedAt());
    }

    @Test
    void listKeys_returnsMetadataList() throws Exception {
        var secret1 = new EncryptedSecret("id-1", "default", "key1", "enc", "iv", "default", "cs1", "desc1", List.of("*"), Instant.now(), null, null);
        var secret2 = new EncryptedSecret("id-2", "default", "key2", "enc", "iv", "default", "cs2", "desc2", List.of("agent1"), Instant.now(), null,
                null);
        when(persistence.listSecretsByTenant("default")).thenReturn(List.of(secret1, secret2));

        List<SecretMetadata> result = provider.listKeys("default");

        assertEquals(2, result.size());
        assertEquals("key1", result.get(0).keyName());
        assertEquals("key2", result.get(1).keyName());
        assertEquals("desc1", result.get(0).description());
        assertEquals(List.of("agent1"), result.get(1).allowedAgents());
    }

    @Test
    void unavailable_throwsOnResolve() {
        var disabledProvider = new VaultSecretProvider(Optional.empty(), persistence, saltManager, meterRegistry);
        disabledProvider.initMetrics();
        disabledProvider.onStartup(new io.quarkus.runtime.StartupEvent());
        assertThrows(ISecretProvider.SecretProviderException.class, () -> disabledProvider.resolve(new SecretReference("default", "key")));
    }

    @Test
    void unavailable_throwsOnStore() {
        var disabledProvider = new VaultSecretProvider(Optional.empty(), persistence, saltManager, meterRegistry);
        disabledProvider.initMetrics();
        disabledProvider.onStartup(new io.quarkus.runtime.StartupEvent());
        assertThrows(ISecretProvider.SecretProviderException.class,
                () -> disabledProvider.store(new SecretReference("default", "key"), "val", null, null));
    }
}
