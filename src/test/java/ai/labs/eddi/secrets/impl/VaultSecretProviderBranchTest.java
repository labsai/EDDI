/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider.SecretProviderException;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import ai.labs.eddi.secrets.model.*;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.persistence.PersistenceException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Extended branch coverage tests for {@link VaultSecretProvider}. Focuses on: -
 * getOrCreateDek (new DEK generation path) - getOrCreateDek persistence failure
 * - rotateDek success - rotateKek success (both legacy and non-legacy salt) -
 * getMetadata persistence error - listKeys persistence error -
 * updateLastAccessed persistence failure - store with encryption failure -
 * resolve with crypto failure (DEK decryption)
 */
@DisplayName("VaultSecretProvider Extended Branch Coverage Tests")
class VaultSecretProviderBranchTest {

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

    private VaultSecretProvider createAvailableProvider() {
        when(saltManager.getSalt()).thenReturn(FIXED_SALT);
        when(saltManager.isUsingLegacySalt()).thenReturn(false);

        VaultSecretProvider provider = new VaultSecretProvider(
                Optional.of(MASTER_KEY), persistence, saltManager, meterRegistry);
        provider.initMetrics();
        provider.onStartup(mock(StartupEvent.class));
        return provider;
    }

    private EncryptedDek createEncryptedDek(byte[] rawDek) {
        byte[] kek = EnvelopeCrypto.deriveKeyFromString(MASTER_KEY, FIXED_SALT);
        EnvelopeCrypto.EncryptionResult encResult = EnvelopeCrypto.encryptDek(rawDek, kek);
        return new EncryptedDek("dek-id-1", TENANT_ID,
                encResult.ciphertext(), encResult.iv(), Instant.now());
    }

    private EncryptedSecret createEncryptedSecret(String plaintext, byte[] dek) {
        EnvelopeCrypto.EncryptionResult encResult = EnvelopeCrypto.encrypt(plaintext, dek);
        String checksum = EnvelopeCrypto.sha256Hex(plaintext);
        return new EncryptedSecret(
                "secret-id-1", TENANT_ID, KEY_NAME,
                encResult.ciphertext(), encResult.iv(),
                TENANT_ID, checksum, "test description",
                List.of("*"), Instant.now(), null, null);
    }

    // ─── getOrCreateDek — new DEK generation ───

    @Nested
    @DisplayName("getOrCreateDek")
    class GetOrCreateDekTests {

        @Test
        @DisplayName("generates new DEK when none exists for tenant")
        void generatesNewDekWhenNoneExists() throws Exception {
            VaultSecretProvider provider = createAvailableProvider();

            // No existing DEK
            when(persistence.findDek(TENANT_ID)).thenReturn(Optional.empty());
            when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.empty());

            // Store should succeed, creating a new DEK along the way
            provider.store(new SecretReference(TENANT_ID, KEY_NAME),
                    "test-value", "desc", null);

            // Verify DEK was upserted
            verify(persistence).upsertDek(any(EncryptedDek.class));
            verify(persistence).upsertSecret(any(EncryptedSecret.class));
        }

        @Test
        @DisplayName("DEK persistence failure throws SecretProviderException")
        void dekPersistenceFailure() {
            VaultSecretProvider provider = createAvailableProvider();

            when(persistence.findDek(TENANT_ID)).thenReturn(Optional.empty());
            doThrow(new PersistenceException("DEK write failed"))
                    .when(persistence).upsertDek(any(EncryptedDek.class));

            assertThrows(SecretProviderException.class,
                    () -> provider.store(new SecretReference(TENANT_ID, KEY_NAME),
                            "value", null, null));
        }
    }

    // ─── rotateDek success ───

    @Nested
    @DisplayName("rotateDek")
    class RotateDekTests {

        @Test
        @DisplayName("successful DEK rotation re-encrypts all secrets")
        void successfulRotation() throws Exception {
            VaultSecretProvider provider = createAvailableProvider();

            byte[] dek = EnvelopeCrypto.generateDek();
            EncryptedDek encDek = createEncryptedDek(dek);
            EncryptedSecret encSecret = createEncryptedSecret("my-secret", dek);

            when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(encDek));
            when(persistence.listSecretsByTenant(TENANT_ID)).thenReturn(List.of(encSecret));

            int count = provider.rotateDek(TENANT_ID);

            assertEquals(1, count);
            // Old secret was re-encrypted + new DEK stored
            verify(persistence).upsertSecret(any(EncryptedSecret.class));
            verify(persistence).upsertDek(any(EncryptedDek.class));
        }

        @Test
        @DisplayName("persistence error during DEK rotation throws SecretProviderException")
        void persistenceErrorDuringRotation() {
            VaultSecretProvider provider = createAvailableProvider();

            byte[] dek = EnvelopeCrypto.generateDek();
            EncryptedDek encDek = createEncryptedDek(dek);
            EncryptedSecret encSecret = createEncryptedSecret("my-secret", dek);

            when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(encDek));
            when(persistence.listSecretsByTenant(TENANT_ID)).thenReturn(List.of(encSecret));
            doThrow(new PersistenceException("write failed"))
                    .when(persistence).upsertSecret(any(EncryptedSecret.class));

            assertThrows(SecretProviderException.class,
                    () -> provider.rotateDek(TENANT_ID));
        }
    }

    // ─── rotateKek success ───

    @Nested
    @DisplayName("rotateKek")
    class RotateKekTests {

        @Test
        @DisplayName("successful KEK rotation re-encrypts all DEKs")
        void successfulKekRotation() throws Exception {
            VaultSecretProvider provider = createAvailableProvider();

            byte[] dek = EnvelopeCrypto.generateDek();
            EncryptedDek encDek = createEncryptedDek(dek);

            when(persistence.listAllDeks()).thenReturn(List.of(encDek));

            int count = provider.rotateKek(MASTER_KEY, "new-master-key-abc12345678901");

            assertEquals(1, count);
            verify(persistence).upsertDek(any(EncryptedDek.class));
        }

        @Test
        @DisplayName("KEK rotation with legacy salt migration")
        void kekRotationWithLegacySalt() throws Exception {
            when(saltManager.getSalt()).thenReturn(FIXED_SALT);
            when(saltManager.isUsingLegacySalt()).thenReturn(true);

            VaultSecretProvider provider = new VaultSecretProvider(
                    Optional.of(MASTER_KEY), persistence, saltManager, meterRegistry);
            provider.initMetrics();
            provider.onStartup(mock(StartupEvent.class));

            byte[] dek = EnvelopeCrypto.generateDek();
            EncryptedDek encDek = createEncryptedDek(dek);

            when(persistence.listAllDeks()).thenReturn(List.of(encDek));

            int count = provider.rotateKek(MASTER_KEY, "new-master-key-abc12345678901");

            assertEquals(1, count);
            verify(saltManager).migrateSalt(any(byte[].class));
        }

        @Test
        @DisplayName("persistence error during KEK rotation throws SecretProviderException")
        void persistenceErrorDuringKekRotation() {
            VaultSecretProvider provider = createAvailableProvider();

            when(persistence.listAllDeks())
                    .thenThrow(new PersistenceException("DB down"));

            assertThrows(SecretProviderException.class,
                    () -> provider.rotateKek(MASTER_KEY, "new-key-12345678901234567890"));
        }
    }

    // ─── getMetadata persistence error ───

    @Nested
    @DisplayName("getMetadata errors")
    class GetMetadataErrorTests {

        @Test
        @DisplayName("persistence error throws SecretProviderException")
        void persistenceError() {
            VaultSecretProvider provider = createAvailableProvider();

            when(persistence.findSecret(TENANT_ID, KEY_NAME))
                    .thenThrow(new PersistenceException("DB down"));

            assertThrows(SecretProviderException.class,
                    () -> provider.getMetadata(new SecretReference(TENANT_ID, KEY_NAME)));
        }
    }

    // ─── listKeys persistence error ───

    @Nested
    @DisplayName("listKeys errors")
    class ListKeysErrorTests {

        @Test
        @DisplayName("persistence error throws SecretProviderException")
        void persistenceError() {
            VaultSecretProvider provider = createAvailableProvider();

            when(persistence.listSecretsByTenant(TENANT_ID))
                    .thenThrow(new PersistenceException("DB down"));

            assertThrows(SecretProviderException.class,
                    () -> provider.listKeys(TENANT_ID));
        }
    }

    // ─── updateLastAccessed failure path ───

    @Nested
    @DisplayName("updateLastAccessed")
    class UpdateLastAccessedTests {

        @Test
        @DisplayName("successful resolve calls updateLastAccessed via upsertSecret")
        void successfulResolveUpdatesLastAccessed() throws Exception {
            VaultSecretProvider provider = createAvailableProvider();

            String plaintext = "my-secret";
            byte[] dek = EnvelopeCrypto.generateDek();
            EncryptedDek encDek = createEncryptedDek(dek);
            EncryptedSecret encSecret = createEncryptedSecret(plaintext, dek);

            when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.of(encSecret));
            when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(encDek));

            String result = provider.resolve(new SecretReference(TENANT_ID, KEY_NAME));
            assertEquals(plaintext, result);

            // updateLastAccessed should have called upsertSecret
            verify(persistence).upsertSecret(any(EncryptedSecret.class));
        }
    }

    // ─── resolve with new DEK creation (no existing DEK) ───

    @Nested
    @DisplayName("resolve with no existing DEK")
    class ResolveWithNewDek {

        @Test
        @DisplayName("resolve fails when no DEK and no secret")
        void resolveFailsNoSecret() {
            VaultSecretProvider provider = createAvailableProvider();

            when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.empty());

            assertThrows(ai.labs.eddi.secrets.ISecretProvider.SecretNotFoundException.class,
                    () -> provider.resolve(new SecretReference(TENANT_ID, KEY_NAME)));
        }
    }

    // ─── store with null allowedAgents defaults to ["*"] ───

    @Nested
    @DisplayName("store defaults")
    class StoreDefaults {

        @Test
        @DisplayName("null allowedAgents defaults to wildcard")
        void nullAllowedAgentsDefaults() throws Exception {
            VaultSecretProvider provider = createAvailableProvider();

            byte[] dek = EnvelopeCrypto.generateDek();
            EncryptedDek encDek = createEncryptedDek(dek);

            when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(encDek));
            when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.empty());

            provider.store(new SecretReference(TENANT_ID, KEY_NAME),
                    "value", null, null);

            var captor = org.mockito.ArgumentCaptor.forClass(EncryptedSecret.class);
            verify(persistence).upsertSecret(captor.capture());
            assertEquals(List.of("*"), captor.getValue().getAllowedAgents());
        }

        @Test
        @DisplayName("null description is stored as null")
        void nullDescription() throws Exception {
            VaultSecretProvider provider = createAvailableProvider();

            byte[] dek = EnvelopeCrypto.generateDek();
            EncryptedDek encDek = createEncryptedDek(dek);

            when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(encDek));
            when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.empty());

            provider.store(new SecretReference(TENANT_ID, KEY_NAME),
                    "value", null, List.of("agent-1"));

            var captor = org.mockito.ArgumentCaptor.forClass(EncryptedSecret.class);
            verify(persistence).upsertSecret(captor.capture());
            assertNull(captor.getValue().getDescription());
            assertEquals(List.of("agent-1"), captor.getValue().getAllowedAgents());
        }
    }

    // ─── createUnavailableProvider helper ───

    private VaultSecretProvider createUnavailableProvider() {
        VaultSecretProvider provider = new VaultSecretProvider(
                Optional.empty(), persistence, saltManager, meterRegistry);
        provider.initMetrics();
        return provider;
    }

    // ─── resetTenant ───

    @Nested
    @DisplayName("resetTenant")
    class ResetTenantTests {

        @Test
        @DisplayName("happy path deletes all secrets then DEK and returns count")
        void happyPath() throws Exception {
            VaultSecretProvider provider = createAvailableProvider();

            byte[] dek = EnvelopeCrypto.generateDek();
            EncryptedSecret secret1 = createEncryptedSecret("val1", dek);
            EncryptedSecret secret2 = createEncryptedSecret("val2", dek);
            // Give them distinct key names for verification
            secret1.setKeyName("key-1");
            secret2.setKeyName("key-2");

            when(persistence.listSecretsByTenant(TENANT_ID))
                    .thenReturn(List.of(secret1, secret2));

            int result = provider.resetTenant(TENANT_ID);

            assertEquals(2, result);
            verify(persistence).deleteSecret(TENANT_ID, "key-1");
            verify(persistence).deleteSecret(TENANT_ID, "key-2");
            verify(persistence).deleteDek(TENANT_ID);
        }

        @Test
        @DisplayName("empty tenant still deletes DEK and returns 0")
        void emptyTenant() throws Exception {
            VaultSecretProvider provider = createAvailableProvider();

            when(persistence.listSecretsByTenant(TENANT_ID))
                    .thenReturn(List.of());

            int result = provider.resetTenant(TENANT_ID);

            assertEquals(0, result);
            verify(persistence, never()).deleteSecret(anyString(), anyString());
            verify(persistence).deleteDek(TENANT_ID);
        }

        @Test
        @DisplayName("persistence failure wraps as SecretProviderException")
        void persistenceFailure() {
            VaultSecretProvider provider = createAvailableProvider();

            when(persistence.listSecretsByTenant(TENANT_ID))
                    .thenThrow(new PersistenceException("DB down"));

            var ex = assertThrows(SecretProviderException.class,
                    () -> provider.resetTenant(TENANT_ID));
            assertTrue(ex.getMessage().contains("Failed to reset vault"));
        }

        @Test
        @DisplayName("vault unavailable throws SecretProviderException")
        void vaultUnavailable() {
            VaultSecretProvider provider = createUnavailableProvider();

            assertThrows(SecretProviderException.class,
                    () -> provider.resetTenant(TENANT_ID));
        }
    }

    // ─── handleDekDecryptionFailure (triggered via getOrCreateDek) ───

    @Nested
    @DisplayName("handleDekDecryptionFailure")
    class HandleDekDecryptionFailureTests {

        /**
         * Creates a DEK encrypted with a DIFFERENT master key so that decryption with
         * the current KEK fails with AEADBadTagException (wrapped in CryptoException).
         */
        private EncryptedDek createDekEncryptedWithDifferentKey(byte[] rawDek) {
            String differentMasterKey = "DIFFERENT-master-key-9876543210";
            byte[] wrongKek = EnvelopeCrypto.deriveKeyFromString(differentMasterKey, FIXED_SALT);
            EnvelopeCrypto.EncryptionResult encResult = EnvelopeCrypto.encryptDek(rawDek, wrongKek);
            return new EncryptedDek("dek-wrong", TENANT_ID,
                    encResult.ciphertext(), encResult.iv(), Instant.now());
        }

        @Test
        @DisplayName("with secrets stored — error message lists count and recovery options")
        void withSecretsStored() {
            VaultSecretProvider provider = createAvailableProvider();

            byte[] dek = EnvelopeCrypto.generateDek();
            EncryptedDek wrongDek = createDekEncryptedWithDifferentKey(dek);

            when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(wrongDek));
            when(persistence.listSecretsByTenant(TENANT_ID)).thenReturn(
                    List.of(createEncryptedSecret("s1", dek),
                            createEncryptedSecret("s2", dek),
                            createEncryptedSecret("s3", dek)));

            var ex = assertThrows(SecretProviderException.class,
                    () -> provider.store(new SecretReference(TENANT_ID, KEY_NAME),
                            "value", null, null));

            assertTrue(ex.getMessage().contains("3 secret(s) are stored"),
                    "Expected '3 secret(s) are stored' but got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("Set EDDI_VAULT_MASTER_KEY back"));
            assertTrue(ex.getMessage().contains("rotate-kek"));
            assertTrue(ex.getMessage().contains("/reset"));
        }

        @Test
        @DisplayName("with 0 secrets — message says no data would be lost")
        void withZeroSecrets() {
            VaultSecretProvider provider = createAvailableProvider();

            byte[] dek = EnvelopeCrypto.generateDek();
            EncryptedDek wrongDek = createDekEncryptedWithDifferentKey(dek);

            when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(wrongDek));
            when(persistence.listSecretsByTenant(TENANT_ID)).thenReturn(List.of());

            var ex = assertThrows(SecretProviderException.class,
                    () -> provider.store(new SecretReference(TENANT_ID, KEY_NAME),
                            "value", null, null));

            assertTrue(ex.getMessage().contains("No secrets are stored"),
                    "Expected 'No secrets are stored' but got: " + ex.getMessage());
        }

        @Test
        @DisplayName("persistence error counting secrets — message says unable to determine")
        void persistenceErrorCounting() {
            VaultSecretProvider provider = createAvailableProvider();

            byte[] dek = EnvelopeCrypto.generateDek();
            EncryptedDek wrongDek = createDekEncryptedWithDifferentKey(dek);

            when(persistence.findDek(TENANT_ID)).thenReturn(Optional.of(wrongDek));
            when(persistence.listSecretsByTenant(TENANT_ID))
                    .thenThrow(new PersistenceException("DB error"));

            var ex = assertThrows(SecretProviderException.class,
                    () -> provider.store(new SecretReference(TENANT_ID, KEY_NAME),
                            "value", null, null));

            assertTrue(ex.getMessage().contains("Unable to determine"),
                    "Expected 'Unable to determine' but got: " + ex.getMessage());
        }
    }

    // ─── generateAndPersistDek (triggered when no DEK exists) ───

    @Nested
    @DisplayName("generateAndPersistDek")
    class GenerateAndPersistDekTests {

        @Test
        @DisplayName("new DEK generated and stored end-to-end when none exists")
        void newDekGenerated() throws Exception {
            VaultSecretProvider provider = createAvailableProvider();

            // No existing DEK → triggers generateAndPersistDek
            when(persistence.findDek(TENANT_ID)).thenReturn(Optional.empty());
            when(persistence.findSecret(TENANT_ID, KEY_NAME)).thenReturn(Optional.empty());

            // Store a secret — this forces DEK creation
            provider.store(new SecretReference(TENANT_ID, KEY_NAME),
                    "test-secret-value", "desc", null);

            // Verify a new DEK was upserted
            var dekCaptor = org.mockito.ArgumentCaptor.forClass(EncryptedDek.class);
            verify(persistence).upsertDek(dekCaptor.capture());
            EncryptedDek generatedDek = dekCaptor.getValue();
            assertEquals(TENANT_ID, generatedDek.getTenantId());
            assertNotNull(generatedDek.getEncryptedDek());
            assertNotNull(generatedDek.getIv());

            // Verify the secret was also stored
            verify(persistence).upsertSecret(any(EncryptedSecret.class));
        }
    }
}
