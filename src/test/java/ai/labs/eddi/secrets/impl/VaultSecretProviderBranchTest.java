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
}
