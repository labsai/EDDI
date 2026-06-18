/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider.SecretNotFoundException;
import ai.labs.eddi.secrets.ISecretProvider.SecretProviderException;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import ai.labs.eddi.secrets.model.*;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.persistence.PersistenceException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VaultSecretProvider}.
 * <p>
 * Uses real AES-256-GCM crypto via {@link EnvelopeCrypto} for encrypt/decrypt
 * roundtrip tests with a fixed master key and salt. Persistence and salt
 * manager are mocked via Mockito.
 */
class VaultSecretProviderTest {

    private static final String MASTER_KEY = "test-master-key-12345678901234";
    private static final byte[] FIXED_SALT = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final String TENANT_ID = "test-tenant";
    private static final String KEY_NAME = "api-key";

    @Mock
    private ISecretPersistence persistence;

    @Mock
    private VaultSaltManager saltManager;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
    }

    // ─── Helper methods ───

    /**
     * Creates an initialized (available) provider with the test master key. Mocks
     * salt manager and triggers startup + metrics initialization.
     */
    private VaultSecretProvider createAvailableProvider() {
        when(saltManager.getSalt()).thenReturn(FIXED_SALT);
        when(saltManager.isUsingLegacySalt()).thenReturn(false);

        VaultSecretProvider provider = new VaultSecretProvider(
                Optional.of(MASTER_KEY), persistence, saltManager, meterRegistry);
        provider.initMetrics();
        provider.onStartup(mock(StartupEvent.class));
        return provider;
    }

    /**
     * Creates a provider that is NOT available (empty master key).
     */
    private VaultSecretProvider createUnavailableProvider() {
        VaultSecretProvider provider = new VaultSecretProvider(
                Optional.empty(), persistence, saltManager, meterRegistry);
        provider.initMetrics();
        return provider;
    }

    /**
     * Derives the real KEK from the test master key and fixed salt, then creates an
     * encrypted DEK entity for use in mocks.
     */
    private EncryptedDek createEncryptedDek(byte[] rawDek) {
        byte[] kek = EnvelopeCrypto.deriveKeyFromString(MASTER_KEY, FIXED_SALT);
        EnvelopeCrypto.EncryptionResult encResult = EnvelopeCrypto.encryptDek(rawDek, kek);
        return new EncryptedDek("dek-id-1", TENANT_ID, encResult.ciphertext(), encResult.iv(), Instant.now());
    }

    /**
     * Creates an encrypted secret using real crypto operations with the given DEK.
     */
    private EncryptedSecret createEncryptedSecret(String plaintext, byte[] dek) {
        EnvelopeCrypto.EncryptionResult encResult = EnvelopeCrypto.encrypt(plaintext, dek);
        String checksum = EnvelopeCrypto.sha256Hex(plaintext);
        return new EncryptedSecret(
                "secret-id-1", TENANT_ID, KEY_NAME,
                encResult.ciphertext(), encResult.iv(),
                TENANT_ID, checksum, "test description",
                List.of("*"), Instant.now(), null, null);
    }

    // ─── 1. isAvailable — not initialized ───

    @Test
    void isAvailable_notInitialized_returnsFalse() {
        VaultSecretProvider provider = createUnavailableProvider();

        assertFalse(provider.isAvailable());
    }

    // ─── 2. ensureAvailable throws when not available ───

    @Test
    void resolve_whenNotAvailable_throwsSecretProviderException() {
        VaultSecretProvider provider = createUnavailableProvider();

        assertThrows(SecretProviderException.class,
                () -> provider.resolve(new SecretReference(TENANT_ID, KEY_NAME)));
    }

    @Test
    void store_whenNotAvailable_throwsSecretProviderException() {
        VaultSecretProvider provider = createUnavailableProvider();

        assertThrows(SecretProviderException.class,
                () -> provider.store(new SecretReference(TENANT_ID, KEY_NAME), "value", null, null));
    }

    @Test
    void delete_whenNotAvailable_throwsSecretProviderException() {
        VaultSecretProvider provider = createUnavailableProvider();

        assertThrows(SecretProviderException.class,
                () -> provider.delete(new SecretReference(TENANT_ID, KEY_NAME)));
    }

    @Test
    void getMetadata_whenNotAvailable_throwsSecretProviderException() {
        VaultSecretProvider provider = createUnavailableProvider();

        assertThrows(SecretProviderException.class,
                () -> provider.getMetadata(new SecretReference(TENANT_ID, KEY_NAME)));
    }

    @Test
    void listKeys_whenNotAvailable_throwsSecretProviderException() {
        VaultSecretProvider provider = createUnavailableProvider();

        assertThrows(SecretProviderException.class,
                () -> provider.listKeys(TENANT_ID));
    }

    // ─── 3. onStartup with empty key ───

    @Test
    void onStartup_withEmptyKey_availableRemainsFalse() {
        VaultSecretProvider provider = new VaultSecretProvider(
                Optional.empty(), persistence, saltManager, meterRegistry);
        provider.initMetrics();
        provider.onStartup(mock(StartupEvent.class));

        assertFalse(provider.isAvailable());
        verify(saltManager, never()).initialize();
    }

    // ─── 4. onStartup with blank key ───

    @Test
    void onStartup_withBlankKey_availableRemainsFalse() {
        VaultSecretProvider provider = new VaultSecretProvider(
                Optional.of("   "), persistence, saltManager, meterRegistry);
        provider.initMetrics();
        provider.onStartup(mock(StartupEvent.class));

        assertFalse(provider.isAvailable());
        verify(saltManager, never()).initialize();
    }

    // ─── 5. onStartup with valid key ───

    @Test
    void onStartup_withValidKey_becomesAvailable() {
        VaultSecretProvider provider = createAvailableProvider();

        assertTrue(provider.isAvailable());
        verify(saltManager).initialize();
        verify(saltManager).getSalt();
    }

    // ─── 6. onStartup with legacy salt ───

    @Test
    void onStartup_withLegacySalt_stillAvailable() {
        when(saltManager.getSalt()).thenReturn(FIXED_SALT);
        when(saltManager.isUsingLegacySalt()).thenReturn(true);

        VaultSecretProvider provider = new VaultSecretProvider(
                Optional.of(MASTER_KEY), persistence, saltManager, meterRegistry);
        provider.initMetrics();
        provider.onStartup(mock(StartupEvent.class));

        assertTrue(provider.isAvailable());
        verify(saltManager).isUsingLegacySalt();
    }

    // ─── 7. resolve — happy path ───

    @Test
    void resolve_happyPath_decryptsAndReturnsPlaintext() throws Exception {
        VaultSecretProvider provider = createAvailableProvider();

        String plaintext = "super-secret-value";
        byte[] dek = EnvelopeCrypto.generateDek();
        EncryptedDek encDek = createEncryptedDek(dek);
        EncryptedSecret encSecret = createEncryptedSecret(plaintext, dek);

        when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.of(encSecret));
        when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(encDek));

        String result = provider.resolve(new SecretReference(TENANT_ID, KEY_NAME));

        assertEquals(plaintext, result);
        // Verify lastAccessedAt update was attempted
        verify(persistence, atLeastOnce()).upsertSecret(any(EncryptedSecret.class));
    }

    // ─── 8. resolve — secret not found ───

    @Test
    void resolve_secretNotFound_throwsSecretNotFoundException() {
        VaultSecretProvider provider = createAvailableProvider();

        when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.empty());

        assertThrows(SecretNotFoundException.class,
                () -> provider.resolve(new SecretReference(TENANT_ID, KEY_NAME)));
    }

    // ─── 9. resolve — persistence failure ───

    @Test
    void resolve_persistenceFailure_throwsSecretProviderException() {
        VaultSecretProvider provider = createAvailableProvider();

        when(persistence.findSecret(TENANT_ID, KEY_NAME))
                .thenThrow(new PersistenceException("DB down"));

        SecretProviderException ex = assertThrows(SecretProviderException.class,
                () -> provider.resolve(new SecretReference(TENANT_ID, KEY_NAME)));
        assertInstanceOf(PersistenceException.class, ex.getCause());
    }

    // ─── 10. store — happy path (new secret) ───

    @Test
    void store_newSecret_encryptsAndUpserts() throws Exception {
        VaultSecretProvider provider = createAvailableProvider();

        String plaintext = "new-api-key-value";
        byte[] dek = EnvelopeCrypto.generateDek();
        EncryptedDek encDek = createEncryptedDek(dek);

        when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(encDek));
        when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.empty());

        provider.store(new SecretReference(TENANT_ID, KEY_NAME), plaintext, "Test API Key", List.of("agent-1"));

        ArgumentCaptor<EncryptedSecret> captor = ArgumentCaptor.forClass(EncryptedSecret.class);
        verify(persistence).upsertSecret(captor.capture());
        EncryptedSecret stored = captor.getValue();

        assertEquals(TENANT_ID, stored.getTenantId());
        assertEquals(KEY_NAME, stored.getKeyName());
        assertEquals("Test API Key", stored.getDescription());
        assertEquals(List.of("agent-1"), stored.getAllowedAgents());
        assertNotNull(stored.getEncryptedValue());
        assertNotNull(stored.getIv());
        assertNotNull(stored.getCreatedAt());
        assertNull(stored.getLastRotatedAt()); // New secret, not a rotation
    }

    // ─── 11. store — update existing ───

    @Test
    void store_updateExisting_reusesIdAndCreatedAt() throws Exception {
        VaultSecretProvider provider = createAvailableProvider();

        String plaintext = "updated-api-key";
        byte[] dek = EnvelopeCrypto.generateDek();
        EncryptedDek encDek = createEncryptedDek(dek);
        Instant originalCreatedAt = Instant.parse("2024-01-15T10:00:00Z");

        EncryptedSecret existing = new EncryptedSecret(
                "existing-id", TENANT_ID, KEY_NAME,
                "old-cipher", "old-iv", TENANT_ID,
                "old-checksum", "old desc", List.of("*"),
                originalCreatedAt, null, null);

        when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(encDek));
        when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.of(existing));

        provider.store(new SecretReference(TENANT_ID, KEY_NAME), plaintext, "Updated", null);

        ArgumentCaptor<EncryptedSecret> captor = ArgumentCaptor.forClass(EncryptedSecret.class);
        verify(persistence).upsertSecret(captor.capture());
        EncryptedSecret stored = captor.getValue();

        assertEquals("existing-id", stored.getId());
        assertEquals(originalCreatedAt, stored.getCreatedAt());
        assertNotNull(stored.getLastRotatedAt()); // Update sets lastRotatedAt
    }

    // ─── 12. store — persistence failure ───

    @Test
    void store_persistenceFailure_throwsSecretProviderException() {
        VaultSecretProvider provider = createAvailableProvider();

        byte[] dek = EnvelopeCrypto.generateDek();
        EncryptedDek encDek = createEncryptedDek(dek);

        when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(encDek));
        when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.empty());
        doThrow(new PersistenceException("Write failed"))
                .when(persistence).upsertSecret(any(EncryptedSecret.class));

        SecretProviderException ex = assertThrows(SecretProviderException.class,
                () -> provider.store(new SecretReference(TENANT_ID, KEY_NAME), "value", null, null));
        assertInstanceOf(PersistenceException.class, ex.getCause());
    }

    // ─── 13. delete — success ───

    @Test
    void delete_success_returnsNormally() throws Exception {
        VaultSecretProvider provider = createAvailableProvider();

        when(persistence.deleteSecret(TENANT_ID, KEY_NAME)).thenReturn(true);

        assertDoesNotThrow(
                () -> provider.delete(new SecretReference(TENANT_ID, KEY_NAME)));
        verify(persistence).deleteSecret(TENANT_ID, KEY_NAME);
    }

    // ─── 14. delete — not found ───

    @Test
    void delete_notFound_throwsSecretNotFoundException() {
        VaultSecretProvider provider = createAvailableProvider();

        when(persistence.deleteSecret(TENANT_ID, KEY_NAME)).thenReturn(false);

        assertThrows(SecretNotFoundException.class,
                () -> provider.delete(new SecretReference(TENANT_ID, KEY_NAME)));
    }

    // ─── 15. delete — persistence error ───

    @Test
    void delete_persistenceError_throwsSecretProviderException() {
        VaultSecretProvider provider = createAvailableProvider();

        when(persistence.deleteSecret(TENANT_ID, KEY_NAME))
                .thenThrow(new PersistenceException("Delete failed"));

        SecretProviderException ex = assertThrows(SecretProviderException.class,
                () -> provider.delete(new SecretReference(TENANT_ID, KEY_NAME)));
        assertInstanceOf(PersistenceException.class, ex.getCause());
    }

    // ─── 16. getMetadata — found ───

    @Test
    void getMetadata_found_returnsSecretMetadata() throws Exception {
        VaultSecretProvider provider = createAvailableProvider();

        Instant createdAt = Instant.parse("2024-06-01T12:00:00Z");
        Instant lastAccessed = Instant.parse("2024-06-10T08:00:00Z");
        EncryptedSecret secret = new EncryptedSecret(
                "meta-id", TENANT_ID, KEY_NAME,
                "cipher", "iv", TENANT_ID,
                "abc123", "My API key", List.of("agent-1", "agent-2"),
                createdAt, lastAccessed, null);

        when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.of(secret));

        SecretMetadata metadata = provider.getMetadata(new SecretReference(TENANT_ID, KEY_NAME));

        assertEquals(TENANT_ID, metadata.tenantId());
        assertEquals(KEY_NAME, metadata.keyName());
        assertEquals(createdAt, metadata.createdAt());
        assertEquals(lastAccessed, metadata.lastAccessedAt());
        assertNull(metadata.lastRotatedAt());
        assertEquals("abc123", metadata.checksum());
        assertEquals("My API key", metadata.description());
        assertEquals(List.of("agent-1", "agent-2"), metadata.allowedAgents());
    }

    // ─── 17. getMetadata — not found ───

    @Test
    void getMetadata_notFound_throwsSecretNotFoundException() {
        VaultSecretProvider provider = createAvailableProvider();

        when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.empty());

        assertThrows(SecretNotFoundException.class,
                () -> provider.getMetadata(new SecretReference(TENANT_ID, KEY_NAME)));
    }

    // ─── 18. listKeys ───

    @Test
    void listKeys_returnsMappedList() throws Exception {
        VaultSecretProvider provider = createAvailableProvider();

        Instant now = Instant.now();
        EncryptedSecret s1 = new EncryptedSecret(
                "id-1", TENANT_ID, "key-a",
                "cipher1", "iv1", TENANT_ID,
                "chk1", "desc-a", List.of("*"),
                now, null, null);
        EncryptedSecret s2 = new EncryptedSecret(
                "id-2", TENANT_ID, "key-b",
                "cipher2", "iv2", TENANT_ID,
                "chk2", "desc-b", List.of("agent-x"),
                now, now, now);

        when(persistence.listSecretsByTenant(TENANT_ID)).thenReturn(List.of(s1, s2));

        List<SecretMetadata> result = provider.listKeys(TENANT_ID);

        assertEquals(2, result.size());
        assertEquals("key-a", result.get(0).keyName());
        assertEquals("key-b", result.get(1).keyName());
        assertEquals("desc-a", result.get(0).description());
        assertEquals(List.of("agent-x"), result.get(1).allowedAgents());
    }

    // ─── 19. rotateDek — no DEK found ───

    @Test
    void rotateDek_noDekFound_throwsSecretProviderException() {
        VaultSecretProvider provider = createAvailableProvider();

        when(persistence.findDek(TENANT_ID)).thenReturn(Optional.empty());

        SecretProviderException ex = assertThrows(SecretProviderException.class,
                () -> provider.rotateDek(TENANT_ID));
        assertTrue(ex.getMessage().contains("No DEK found"));
    }

    // ─── 20. rotateKek — not available ───

    @Test
    void rotateKek_notAvailable_throwsSecretProviderException() {
        VaultSecretProvider provider = createUnavailableProvider();

        SecretProviderException ex = assertThrows(SecretProviderException.class,
                () -> provider.rotateKek("old-key", "new-key"));
        assertTrue(ex.getMessage().contains("not available"));
    }
}
