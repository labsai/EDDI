/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider.SecretProviderException;
import ai.labs.eddi.secrets.SealedDataRotationParticipant;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Key-material safety of the vault: races on the salt and on a tenant's first
 * DEK, KEK rotation that is interrupted and re-run, replicas left on a retired
 * master key, ciphertext bound to its row, and what a tenant reset leaves
 * behind.
 * <p>
 * Real AES-256-GCM and PBKDF2 over a stateful in-memory persistence with the
 * production stores' conditional-write semantics, and a real
 * {@link VaultSaltManager}: every claim here is "this still decrypts
 * afterwards" (or "this refuses"), which only a real decrypt can show.
 */
class VaultKeySafetyTest {

    private static final String MASTER = "master-key-one-1234567890";
    private static final String NEW_MASTER = "master-key-two-0987654321";
    private static final byte[] LEGACY_SALT = "eddi-vault-kek-v1".getBytes(StandardCharsets.UTF_8);

    private InMemorySecretPersistence persistence;

    @BeforeEach
    void setUp() {
        persistence = new InMemorySecretPersistence();
    }

    private VaultSecretProvider provider(String masterKey) {
        return provider(masterKey, null);
    }

    private VaultSecretProvider provider(String masterKey, Instance<SealedDataRotationParticipant> participants) {
        var provider = new VaultSecretProvider(Optional.of(masterKey), persistence, new VaultSaltManager(persistence), new SimpleMeterRegistry(),
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

    private static SecretReference ref(String tenant, String key) {
        return new SecretReference(tenant, key);
    }

    private byte[] persistedSalt() {
        return Base64.getDecoder().decode(persistence.meta.get("vault-kek-salt"));
    }

    // ─── H6b ───

    @Nested
    @DisplayName("a tenant's first DEK")
    class FirstDek {

        /**
         * H6b: two stores for a brand-new tenant race to create generation 1. The first
         * DEK used to be written with an upsert, so the second writer replaced the
         * first one's key and the secret already sealed with it was lost for good.
         */
        @Test
        @DisplayName("two concurrent first stores end up on one DEK, and both secrets decrypt")
        void concurrentFirstStoresShareOneDek() throws Exception {
            var first = provider(MASTER);
            var second = provider(MASTER);

            // The second replica creates the tenant's DEK and seals its secret with it
            // while the first is between generating a DEK and writing it.
            persistence.beforeNextInsertDek = () -> {
                try {
                    second.store(ref("acme", "key-b"), "value-b", null, null);
                } catch (SecretProviderException e) {
                    throw new IllegalStateException(e);
                }
            };
            first.store(ref("acme", "key-a"), "value-a", null, null);

            assertEquals(1, persistence.listDeks("acme").size());
            assertEquals("value-a", first.resolve(ref("acme", "key-a")));
            assertEquals("value-b", first.resolve(ref("acme", "key-b")), "the race winner's secret must survive the loser's store");
            assertEquals("value-a", second.resolve(ref("acme", "key-a")));
        }
    }

    // ─── H6a ───

    @Test
    @DisplayName("replicas booting against an empty database agree on one salt")
    void replicasAgreeOnOneSalt() throws Exception {
        var first = provider(MASTER);
        byte[] salt = persistedSalt();
        var second = provider(MASTER);

        assertArrayEquals(salt, persistedSalt());
        first.store(ref("acme", "key"), "value", null, null);
        assertEquals("value", second.resolve(ref("acme", "key")));
    }

    // ─── H6c ───

    @Nested
    @DisplayName("KEK rotation")
    class KekRotation {

        /**
         * H6c: a rotation that stopped part-way used to leave DEKs on the new KEK that
         * phase 1 of a retry could not open with the old one — so the documented "the
         * operator can retry" failed every time.
         */
        @Test
        @DisplayName("an interrupted rotation is completed by re-running it with the same keys")
        void interruptedRotationIsCompletedByARetry() throws Exception {
            var provider = provider(MASTER);
            provider.store(ref("t1", "key"), "one", null, null);
            provider.store(ref("t2", "key"), "two", null, null);
            provider.store(ref("t3", "key"), "three", null, null);

            persistence.failDekRewrapsAfter = 1;
            var failure = assertThrows(SecretProviderException.class, () -> provider.rotateKek(MASTER, NEW_MASTER));
            assertTrue(failure.getMessage().contains("re-running the rotation"), failure.getMessage());

            persistence.failDekRewrapsAfter = -1;
            assertEquals(3, provider.rotateKek(MASTER, NEW_MASTER));

            var restarted = provider(NEW_MASTER);
            assertEquals("one", restarted.resolve(ref("t1", "key")));
            assertEquals("two", restarted.resolve(ref("t2", "key")));
            assertEquals("three", restarted.resolve(ref("t3", "key")));
        }

        /**
         * H6c, the worse half: migrating off the legacy salt. The new salt used to live
         * in memory until the very end, so a rotation that failed after re-wrapping
         * some DEKs left them under a KEK derived from a salt nobody had kept —
         * unrecoverable even with both master keys.
         */
        @Test
        @DisplayName("an interrupted legacy-salt migration persisted its salt first, so a retry recovers every DEK")
        void interruptedLegacySaltMigrationIsRecoverable() throws Exception {
            seedLegacyTenant("t1", "one");
            seedLegacyTenant("t2", "two");
            var provider = provider(MASTER);
            assertEquals("one", provider.resolve(ref("t1", "key")), "the seeded deployment must be on the legacy salt");

            persistence.failDekRewrapsAfter = 1;
            assertThrows(SecretProviderException.class, () -> provider.rotateKek(MASTER, NEW_MASTER));
            String pending = persistence.meta.get("vault-kek-salt-pending");
            assertTrue(pending != null, "the new salt must be persisted before any DEK is wrapped under it");
            assertNull(persistence.meta.get("vault-kek-salt"), "not promoted until every DEK is re-wrapped");

            persistence.failDekRewrapsAfter = -1;
            assertEquals(2, provider.rotateKek(MASTER, NEW_MASTER));
            assertEquals(pending, persistence.meta.get("vault-kek-salt"), "the retry must promote the salt the first run wrapped DEKs under");
            assertNull(persistence.meta.get("vault-kek-salt-pending"));

            var restarted = provider(NEW_MASTER);
            assertEquals("one", restarted.resolve(ref("t1", "key")));
            assertEquals("two", restarted.resolve(ref("t2", "key")));
        }

        @Test
        @DisplayName("a replica restarted with the new key mid-migration opens re-wrapped DEKs and wraps new ones under the new KEK")
        void restartMidLegacyMigrationUsesTheAnnouncedKek() throws Exception {
            seedLegacyTenant("t1", "one");
            seedLegacyTenant("t2", "two");
            var provider = provider(MASTER);
            persistence.failDekRewrapsAfter = 1;
            assertThrows(SecretProviderException.class, () -> provider.rotateKek(MASTER, NEW_MASTER));
            persistence.failDekRewrapsAfter = -1;

            var restarted = provider(NEW_MASTER);
            // A new tenant is not refused — this node's KEK is the announced one — and
            // its DEK is wrapped under the KEK the rotation is moving to.
            restarted.store(ref("t9", "key"), "nine", null, null);

            assertEquals(3, restarted.rotateKek(MASTER, NEW_MASTER));
            var again = provider(NEW_MASTER);
            assertEquals("one", again.resolve(ref("t1", "key")));
            assertEquals("two", again.resolve(ref("t2", "key")));
            assertEquals("nine", again.resolve(ref("t9", "key")));
        }

        /**
         * H6c: other replicas keep the old master key until they are restarted. One of
         * them creating a tenant's DEK after the rotation wrapped it under the retired
         * KEK, and that tenant's secrets were lost at the replica's restart.
         */
        @Test
        @DisplayName("a replica still on the old master key refuses to wrap a new DEK after a rotation")
        void staleReplicaRefusesToWrapANewDek() throws Exception {
            var rotating = provider(MASTER);
            var stale = provider(MASTER);
            rotating.store(ref("t1", "key"), "one", null, null);

            rotating.rotateKek(MASTER, NEW_MASTER);

            var refusal = assertThrows(SecretProviderException.class, () -> stale.store(ref("t9", "key"), "nine", null, null));
            assertTrue(refusal.getMessage().contains("no longer the vault's master key"), refusal.getMessage());
            assertTrue(persistence.listDeks("t9").isEmpty(), "nothing may be wrapped under the retired KEK");
        }

        @Test
        @DisplayName("a DEK another replica wrapped under the old KEK during the rotation is caught up")
        void dekCreatedDuringRotationIsCaughtUp() throws Exception {
            var provider = provider(MASTER);
            provider.store(ref("t1", "key"), "one", null, null);
            byte[] oldKek = EnvelopeCrypto.deriveKeyFromString(MASTER, persistedSalt());

            // A replica that read the KEK check just before the announcement, and wrapped
            // a DEK under the old KEK after the rotation listed the DEKs.
            persistence.beforeNextDekRewrap = () -> seedTenant("t2", "two", oldKek);
            provider.rotateKek(MASTER, NEW_MASTER);

            assertEquals("two", provider(NEW_MASTER).resolve(ref("t2", "key")));
        }

        @Test
        @DisplayName("a DEK that opens with neither key stops the rotation before anything is written")
        void unopenableDekRefusesUpFront() throws Exception {
            var provider = provider(MASTER);
            provider.store(ref("t1", "key"), "one", null, null);
            String checkBefore = persistence.meta.get("vault-kek-check");
            seedTenant("t2", "two", EnvelopeCrypto.deriveKeyFromString("somebody-else-entirely", persistedSalt()));

            assertThrows(SecretProviderException.class, () -> provider.rotateKek(MASTER, NEW_MASTER));

            assertEquals(checkBefore, persistence.meta.get("vault-kek-check"), "nothing announced");
            assertEquals("one", provider(MASTER).resolve(ref("t1", "key")), "nothing re-wrapped");
        }
    }

    // ─── S1 (backend half) ───

    @Nested
    @DisplayName("storing a new value for an existing secret")
    class ValueRotation {

        @Test
        @DisplayName("keeps a narrowed grant and the description when the request does not restate them")
        void keepsGrantAndDescription() throws Exception {
            var provider = provider(MASTER);
            var key = ref("acme", "llm-key");
            provider.store(key, "v1", "production LLM key", List.of("agent-a"));

            provider.store(key, "v2", null, null);

            var metadata = provider.getMetadata(key);
            assertEquals(List.of("agent-a"), metadata.allowedAgents(), "a value rotation must never widen a grant to every agent");
            assertEquals("production LLM key", metadata.description());
            assertEquals("v2", provider.resolve(key));
        }

        @Test
        @DisplayName("a grant edit landing between the store's read and its write survives")
        void concurrentGrantEditSurvives() throws Exception {
            var provider = provider(MASTER);
            var key = ref("acme", "llm-key");
            provider.store(key, "v1", "desc", List.of("agent-a"));

            persistence.afterNextFind = () -> {
                try {
                    provider.updateGrant(key, List.of("agent-b"), null);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            provider.store(key, "v2", null, null);

            assertEquals(List.of("agent-b"), provider.getMetadata(key).allowedAgents());
        }

        @Test
        @DisplayName("a new secret stored without a grant still defaults to every agent")
        void newSecretDefaultsToWildcard() throws Exception {
            var provider = provider(MASTER);
            provider.store(ref("acme", "fresh"), "v1", null, null);
            assertEquals(List.of("*"), provider.getMetadata(ref("acme", "fresh")).allowedAgents());
        }
    }

    // ─── L-S1 ───

    @Nested
    @DisplayName("ciphertext bound to its row")
    class RowBinding {

        @Test
        @DisplayName("a secret's ciphertext copied into another secret's row does not decrypt there")
        void swappedCiphertextIsRefused() throws Exception {
            var provider = provider(MASTER);
            provider.store(ref("acme", "admin-key"), "admin-secret", null, null);
            provider.store(ref("acme", "public-key"), "public-value", null, null);

            EncryptedSecret admin = persistence.secrets.get("acme/admin-key");
            EncryptedSecret victim = persistence.secrets.get("acme/public-key");
            assertTrue(EnvelopeCrypto.isBound(admin.getEncryptedValue()));
            victim.setEncryptedValue(admin.getEncryptedValue());
            victim.setIv(admin.getIv());

            assertThrows(SecretProviderException.class, () -> provider.resolve(ref("acme", "public-key")));
        }

        @Test
        @DisplayName("a DEK wrapping copied onto another tenant's row does not open")
        void swappedDekIsRefused() throws Exception {
            var provider = provider(MASTER);
            provider.store(ref("t1", "key"), "one", null, null);
            provider.store(ref("t2", "key"), "two", null, null);

            EncryptedDek t1 = persistence.deks.get("t1/1");
            EncryptedDek t2 = persistence.deks.get("t2/1");
            t2.setEncryptedDek(t1.getEncryptedDek());
            t2.setIv(t1.getIv());

            assertThrows(SecretProviderException.class, () -> provider.resolve(ref("t2", "key")));
        }

        @Test
        @DisplayName("a row written before AAD existed still resolves, and a DEK rotation moves it onto the bound form")
        void legacyRowsKeepWorkingAndMigrate() throws Exception {
            var provider = provider(MASTER);
            provider.store(ref("acme", "bound"), "bound-value", null, null);
            byte[] kek = EnvelopeCrypto.deriveKeyFromString(MASTER, persistedSalt());
            EncryptedDek dek = persistence.deks.get("acme/1");
            byte[] rawDek = EnvelopeCrypto.decryptDek(dek.getEncryptedDek(), dek.getIv(), kek, VaultSecretProvider.dekAad("acme", 1));
            var legacy = EnvelopeCrypto.encrypt("legacy-value", rawDek);
            persistence.secrets.put("acme/legacy", new EncryptedSecret(UUID.randomUUID().toString(), "acme", "legacy", legacy.ciphertext(),
                    legacy.iv(), dek.dekId(), null, null, List.of("*"), Instant.now(), null, null));

            assertEquals("legacy-value", provider.resolve(ref("acme", "legacy")));

            provider.rotateDek("acme");

            assertTrue(EnvelopeCrypto.isBound(persistence.secrets.get("acme/legacy").getEncryptedValue()));
            assertEquals("legacy-value", provider.resolve(ref("acme", "legacy")));
        }
    }

    // ─── L-S3 ───

    @Nested
    @DisplayName("tenant reset")
    class TenantReset {

        @Test
        @DisplayName("other sealed data is discarded while the DEKs still exist, then the DEKs go")
        void participantsDiscardBeforeTheDeksAreDeleted() throws Exception {
            var participant = mock(SealedDataRotationParticipant.class);
            when(participant.discardAll("acme")).thenAnswer(inv -> {
                assertFalse(persistence.listDeks("acme").isEmpty(), "participants must run before the DEKs are deleted");
                return 3;
            });
            var provider = provider(MASTER, participants(participant));
            provider.store(ref("acme", "key"), "value", null, null);

            assertEquals(1, provider.resetTenant("acme"));

            assertTrue(persistence.listDeks("acme").isEmpty());
        }

        @Test
        @DisplayName("a participant that cannot discard stops the reset with the DEKs in place")
        void failingParticipantKeepsTheDeks() throws Exception {
            var participant = mock(SealedDataRotationParticipant.class);
            when(participant.discardAll(anyString())).thenThrow(new IllegalStateException("grant store down"));
            when(participant.sealedDataDescription()).thenReturn("OAuth connection grants");
            var provider = provider(MASTER, participants(participant));
            provider.store(ref("acme", "key"), "value", null, null);

            var failure = assertThrows(SecretProviderException.class, () -> provider.resetTenant("acme"));

            assertTrue(failure.getMessage().contains("re-run"), failure.getMessage());
            assertFalse(persistence.listDeks("acme").isEmpty(), "rows must not be stranded under deleted keys");
        }

        @Test
        @DisplayName("the system tenant cannot be reset")
        void systemTenantIsRefused() {
            var provider = provider(MASTER);
            assertThrows(SecretProviderException.class, () -> provider.resetTenant(VaultSecretProvider.SYSTEM_TENANT));
        }
    }

    // ─── pinned system values (H6d's storage) ───

    @Test
    @DisplayName("a pinned system value is insert-if-absent and survives a KEK rotation")
    void pinnedSystemValueSurvivesKekRotation() throws Exception {
        var provider = provider(MASTER);
        assertEquals("first", provider.pinSystemValue("audit-hmac-key", "first"));
        assertEquals("first", provider.pinSystemValue("audit-hmac-key", "second"), "a later candidate must adopt the pinned value");

        provider.rotateKek(MASTER, NEW_MASTER);

        assertEquals("first", provider(NEW_MASTER).pinSystemValue("audit-hmac-key", "third"));
    }

    // ─── helpers ───

    /**
     * A tenant as a pre-6.0.2 deployment left it: a DEK wrapped under the KEK from
     * the legacy fixed salt, no salt in the metadata, and one secret — all written
     * without associated data.
     */
    private void seedLegacyTenant(String tenant, String value) {
        byte[] kek = EnvelopeCrypto.deriveKeyFromString(MASTER, LEGACY_SALT);
        byte[] dek = EnvelopeCrypto.generateDek();
        var wrapped = EnvelopeCrypto.encryptDek(dek, kek);
        persistence.deks.put(tenant + "/1", new EncryptedDek(UUID.randomUUID().toString(), tenant, 1, wrapped.ciphertext(), wrapped.iv(),
                Instant.now()));
        var sealed = EnvelopeCrypto.encrypt(value, dek);
        persistence.secrets.put(tenant + "/key", new EncryptedSecret(UUID.randomUUID().toString(), tenant, "key", sealed.ciphertext(), sealed.iv(),
                EncryptedDek.dekId(tenant, 1), null, null, List.of("*"), Instant.now(), null, null));
    }

    /**
     * A tenant whose DEK is wrapped under {@code kek}, in the current bound form.
     */
    private void seedTenant(String tenant, String value, byte[] kek) {
        byte[] dek = EnvelopeCrypto.generateDek();
        var wrapped = EnvelopeCrypto.encryptDek(dek, kek, VaultSecretProvider.dekAad(tenant, 1));
        persistence.deks.put(tenant + "/1", new EncryptedDek(UUID.randomUUID().toString(), tenant, 1, wrapped.ciphertext(), wrapped.iv(),
                Instant.now()));
        var sealed = EnvelopeCrypto.encrypt(value, dek, VaultSecretProvider.secretAad(tenant, "key"));
        persistence.secrets.put(tenant + "/key", new EncryptedSecret(UUID.randomUUID().toString(), tenant, "key", sealed.ciphertext(), sealed.iv(),
                EncryptedDek.dekId(tenant, 1), null, null, List.of("*"), Instant.now(), null, null));
    }
}
