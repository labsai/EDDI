/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider.SecretNotFoundException;
import ai.labs.eddi.secrets.ISecretProvider.SecretProviderException;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for {@link VaultSecretProvider#updateGrant}, the write path
 * that changes who may use a secret without being given its value.
 * <p>
 * Real AES-256-GCM crypto over a stateful in-memory {@link ISecretPersistence},
 * rather than a Mockito mock per call. The central claim — "the value is
 * untouched" — is only worth anything if the secret can actually be decrypted
 * again afterwards, and a mock that returns whatever the test told it to return
 * cannot demonstrate that.
 */
class VaultSecretProviderGrantTest {

    private static final String MASTER_KEY = "test-master-key-12345678901234";
    private static final byte[] FIXED_SALT = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final String TENANT_ID = "test-tenant";
    private static final String KEY_NAME = "llm-api-key";
    private static final String PLAINTEXT = "AIzaSy-super-secret-value";
    private static final SecretReference REF = new SecretReference(TENANT_ID, KEY_NAME);

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

    /** A secret granted to exactly {@code allowedAgents}, stored for real. */
    private void storeSecret(List<String> allowedAgents) throws Exception {
        provider.store(REF, PLAINTEXT, "LLM provider key", allowedAgents);
    }

    // ─── Widening ───

    @Test
    @DisplayName("widening a grant adds the agent, without the caller ever holding the plaintext")
    void widenGrant() throws Exception {
        storeSecret(List.of("agent-one", "agent-two"));

        // The operational case this exists for: a third agent is deployed and
        // references the same key. Nothing here supplies a value.
        SecretMetadata updated = provider.updateGrant(REF, List.of("agent-one", "agent-two", "agent-three"), null);

        assertEquals(List.of("agent-one", "agent-two", "agent-three"), updated.allowedAgents());
        assertEquals(List.of("agent-one", "agent-two", "agent-three"), provider.getMetadata(REF).allowedAgents());
    }

    @Test
    @DisplayName("a grant edit leaves the description alone when none is given")
    void widenGrantKeepsDescription() throws Exception {
        storeSecret(List.of("agent-one"));

        SecretMetadata updated = provider.updateGrant(REF, List.of("agent-one", "agent-two"), null);

        assertEquals("LLM provider key", updated.description());
        assertEquals("LLM provider key", provider.getMetadata(REF).description());
    }

    @Test
    @DisplayName("a grant edit can also replace the description")
    void grantEditReplacesDescription() throws Exception {
        storeSecret(List.of("agent-one"));

        provider.updateGrant(REF, List.of("agent-one"), "LLM provider key (production)");

        assertEquals("LLM provider key (production)", provider.getMetadata(REF).description());
    }

    @Test
    @DisplayName("an empty description clears it (null keeping it is covered above)")
    void emptyDescriptionClears() throws Exception {
        storeSecret(List.of("agent-one"));

        provider.updateGrant(REF, List.of("agent-one"), "");

        assertEquals("", provider.getMetadata(REF).description());
    }

    // ─── Tightening ───

    @Test
    @DisplayName("tightening a grant removes agents, and the secret still resolves for the ones left")
    void tightenGrant() throws Exception {
        storeSecret(List.of("agent-one", "agent-two", "agent-three"));

        SecretMetadata updated = provider.updateGrant(REF, List.of("agent-one"), null);

        assertEquals(List.of("agent-one"), updated.allowedAgents());
        assertEquals(List.of("agent-one"), provider.getMetadata(REF).allowedAgents());
        // Narrowing a grant is not a revocation of the value: resolution is not
        // grant-aware, which is exactly why the REST layer has to warn about
        // deployed agents rather than rely on resolution failing.
        assertEquals(PLAINTEXT, provider.resolve(REF));
    }

    // ─── The wildcard ───

    @Test
    @DisplayName("the wildcard can be set on a narrowed secret")
    void setWildcard() throws Exception {
        storeSecret(List.of("agent-one"));

        SecretMetadata updated = provider.updateGrant(REF, List.of("*"), null);

        assertEquals(List.of("*"), updated.allowedAgents());
        assertTrue(SecretMetadata.grantsAllAgents(provider.getMetadata(REF).allowedAgents()));
    }

    @Test
    @DisplayName("the wildcard can be cleared, replacing it with a specific list")
    void clearWildcard() throws Exception {
        storeSecret(List.of("*"));

        SecretMetadata updated = provider.updateGrant(REF, List.of("agent-one"), null);

        assertEquals(List.of("agent-one"), updated.allowedAgents());
        assertEquals(List.of("agent-one"), provider.getMetadata(REF).allowedAgents());
        // Not "everyone" any more — the check that gates deployments will now say no
        // to every other agent.
        assertFalse(SecretMetadata.grantsAllAgents(provider.getMetadata(REF).allowedAgents()));
    }

    @Test
    @DisplayName("every shape that means 'all agents' is stored as the one documented spelling")
    void everyoneHasOneSpelling() throws Exception {
        // null, empty and a wildcard mixed with real ids all mean unrestricted to
        // VaultGrantChecker. They must not each get their own storage form, or a
        // narrow-looking list would behave as an open one. Passed raw, so the
        // canonicalisation being tested is the provider's and not the test's.
        storeSecret(List.of("agent-one"));
        assertEquals(List.of("*"), provider.updateGrant(REF, null, null).allowedAgents());

        provider.updateGrant(REF, List.of("agent-one"), null);
        assertEquals(List.of("*"), provider.updateGrant(REF, List.of(), null).allowedAgents());

        provider.updateGrant(REF, List.of("agent-one"), null);
        assertEquals(List.of("*"), provider.updateGrant(REF, List.of("*", "agent-one"), null).allowedAgents());
        assertEquals(List.of("*"), provider.getMetadata(REF).allowedAgents());
    }

    // ─── The value is untouched ───

    @Test
    @DisplayName("the value is unchanged by a grant edit — it still resolves to the same plaintext")
    void valueSurvivesGrantEdit() throws Exception {
        storeSecret(List.of("agent-one"));
        assertEquals(PLAINTEXT, provider.resolve(REF));
        String ciphertextBefore = persistence.findSecret(TENANT_ID, KEY_NAME).orElseThrow().getEncryptedValue();
        String ivBefore = persistence.findSecret(TENANT_ID, KEY_NAME).orElseThrow().getIv();
        String checksumBefore = provider.getMetadata(REF).checksum();

        provider.updateGrant(REF, List.of("agent-one", "agent-two"), "a new description too");

        EncryptedSecret after = persistence.findSecret(TENANT_ID, KEY_NAME).orElseThrow();
        assertEquals(ciphertextBefore, after.getEncryptedValue());
        assertEquals(ivBefore, after.getIv());
        assertEquals(checksumBefore, provider.getMetadata(REF).checksum());
        // And it decrypts, which is the assertion that would survive somebody
        // "fixing" the three above by copying stale fields around.
        assertEquals(PLAINTEXT, provider.resolve(REF));
    }

    @Test
    @DisplayName("a grant edit goes through updateSecretGrant only — never through the whole-row write")
    void grantEditNeverWritesTheWholeRow() throws Exception {
        storeSecret(List.of("agent-one"));
        int upsertsBefore = persistence.upsertSecretCalls;

        provider.updateGrant(REF, List.of("agent-one", "agent-two"), null);

        // upsertSecret is the only call that can carry ciphertext. Reaching for it
        // here is how a grant edit would become able to blank a value, so the
        // absence is asserted rather than assumed.
        assertEquals(upsertsBefore, persistence.upsertSecretCalls);
        assertEquals(1, persistence.updateSecretGrantCalls);
    }

    @Test
    @DisplayName("a grant edit does not look like a rotation: createdAt and lastRotatedAt stand still")
    void grantEditIsNotARotation() throws Exception {
        storeSecret(List.of("agent-one"));
        SecretMetadata before = provider.getMetadata(REF);
        assertNotNull(before.createdAt());
        // Freshly stored, never updated: the store path leaves lastRotatedAt null,
        // and a grant edit must not be what finally sets it.
        assertNull(before.lastRotatedAt());

        SecretMetadata after = provider.updateGrant(REF, List.of("agent-one", "agent-two"), null);

        assertEquals(before.createdAt(), after.createdAt());
        assertNull(after.lastRotatedAt());
        assertEquals(before.createdAt(), provider.getMetadata(REF).createdAt());
        assertNull(provider.getMetadata(REF).lastRotatedAt());
    }

    @Test
    @DisplayName("a grant edit does not need the DEK — it reads no key and seals nothing")
    void grantEditDoesNotTouchTheDek() throws Exception {
        storeSecret(List.of("agent-one"));
        int dekLookupsBefore = persistence.findDekCalls;

        provider.updateGrant(REF, List.of("agent-one", "agent-two"), null);

        assertEquals(dekLookupsBefore, persistence.findDekCalls);
    }

    // ─── Concurrency ───

    @Test
    @DisplayName("a resolve that read the row before a grant edit does not write the old grant back")
    void concurrentResolveDoesNotRevertAGrantEdit() throws Exception {
        storeSecret(List.of("agent-one"));

        // The grant edit lands after resolve() has read the row and before it records
        // the access. When access was recorded by re-upserting that whole row, the old
        // allowedAgents went straight back over the new ones.
        persistence.afterNextFind = () -> {
            try {
                provider.updateGrant(REF, List.of("agent-one", "agent-two"), null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertEquals(PLAINTEXT, provider.resolve(REF));

        assertEquals(List.of("agent-one", "agent-two"), provider.getMetadata(REF).allowedAgents());
        assertNotNull(provider.getMetadata(REF).lastAccessedAt(), "the access is still recorded");
    }

    // ─── Absent secrets ───

    @Test
    @DisplayName("a grant edit on an unknown key is not found — and creates nothing")
    void unknownKeyIsNotFound() {
        assertThrows(SecretNotFoundException.class, () -> provider.updateGrant(new SecretReference(TENANT_ID, "no-such-key"), List.of("*"), null));

        // The important half: an upsert would have left a row with a grant and no
        // value, which resolves to a decryption failure at the worst moment.
        assertTrue(persistence.findSecret(TENANT_ID, "no-such-key").isEmpty());
    }

    @Test
    @DisplayName("a grant edit needs a live vault, as every other write does")
    void unavailableVaultRefuses() {
        VaultSecretProvider unavailable = new VaultSecretProvider(Optional.empty(), persistence, mock(VaultSaltManager.class),
                new SimpleMeterRegistry());
        unavailable.initMetrics();

        assertThrows(SecretProviderException.class, () -> unavailable.updateGrant(REF, List.of("*"), null));
    }

    /**
     * A persistence that actually stores things, so a grant edit can be followed by
     * a real decrypt. Counts the calls the tests above assert on.
     * <p>
     * {@link #updateSecretGrant} mirrors the production implementations by writing
     * exactly two fields; that narrowness is independently pinned against the real
     * Mongo {@code $set} in {@code MongoSecretPersistenceTest}.
     */
    private static final class InMemorySecretPersistence implements ISecretPersistence {

        private final Map<String, EncryptedSecret> secrets = new LinkedHashMap<>();
        private final Map<String, EncryptedDek> deks = new LinkedHashMap<>();
        private final Map<String, String> meta = new LinkedHashMap<>();

        int upsertSecretCalls;
        int updateSecretGrantCalls;
        int findDekCalls;

        /**
         * Runs once, immediately after the next {@link #findSecret} has taken its copy
         * — i.e. between a reader's read and whatever that reader writes next. That is
         * the window a concurrent writer lands in, made deterministic.
         */
        Runnable afterNextFind;

        private static String key(String tenantId, String keyName) {
            return tenantId + "/" + keyName;
        }

        /**
         * A copy, as a database read is. Handing back the live instance would let a
         * caller mutate storage by accident and make "the value is unchanged" pass for
         * the wrong reason.
         */
        private static EncryptedSecret copyOf(EncryptedSecret s) {
            return new EncryptedSecret(s.getId(), s.getTenantId(), s.getKeyName(), s.getEncryptedValue(), s.getIv(), s.getDekId(), s.getChecksum(),
                    s.getDescription(), s.getAllowedAgents() == null ? null : List.copyOf(s.getAllowedAgents()), s.getCreatedAt(),
                    s.getLastAccessedAt(), s.getLastRotatedAt());
        }

        @Override
        public void upsertSecret(EncryptedSecret secret) {
            upsertSecretCalls++;
            secrets.put(key(secret.getTenantId(), secret.getKeyName()), copyOf(secret));
        }

        @Override
        public Optional<EncryptedSecret> findSecret(String tenantId, String keyName) {
            Optional<EncryptedSecret> read = Optional.ofNullable(secrets.get(key(tenantId, keyName))).map(InMemorySecretPersistence::copyOf);
            Runnable hook = afterNextFind;
            afterNextFind = null;
            if (hook != null) {
                hook.run();
            }
            return read;
        }

        @Override
        public boolean deleteSecret(String tenantId, String keyName) {
            return secrets.remove(key(tenantId, keyName)) != null;
        }

        @Override
        public List<EncryptedSecret> listSecretsByTenant(String tenantId) {
            var result = new ArrayList<EncryptedSecret>();
            for (var entry : secrets.entrySet()) {
                if (entry.getValue().getTenantId().equals(tenantId)) {
                    result.add(copyOf(entry.getValue()));
                }
            }
            return result;
        }

        @Override
        public boolean updateSecretSealing(EncryptedSecret secret, String expectedDekId) {
            EncryptedSecret stored = secrets.get(key(secret.getTenantId(), secret.getKeyName()));
            if (stored == null || !Objects.equals(stored.getDekId(), expectedDekId)) {
                return false;
            }
            stored.setEncryptedValue(secret.getEncryptedValue());
            stored.setIv(secret.getIv());
            stored.setDekId(secret.getDekId());
            stored.setLastRotatedAt(secret.getLastRotatedAt());
            return true;
        }

        @Override
        public boolean updateSecretGrant(String tenantId, String keyName, List<String> allowedAgents, String description) {
            updateSecretGrantCalls++;
            EncryptedSecret stored = secrets.get(key(tenantId, keyName));
            if (stored == null) {
                return false;
            }
            stored.setAllowedAgents(allowedAgents == null ? null : List.copyOf(allowedAgents));
            stored.setDescription(description);
            return true;
        }

        @Override
        public void touchLastAccessed(String tenantId, String keyName, Instant lastAccessedAt) {
            EncryptedSecret stored = secrets.get(key(tenantId, keyName));
            if (stored != null) {
                stored.setLastAccessedAt(lastAccessedAt);
            }
        }

        @Override
        public void upsertDek(EncryptedDek dek) {
            deks.put(dek.getTenantId() + "/" + dek.getGeneration(), dek);
        }

        @Override
        public boolean insertDek(EncryptedDek dek) {
            return deks.putIfAbsent(dek.getTenantId() + "/" + dek.getGeneration(), dek) == null;
        }

        @Override
        public Optional<EncryptedDek> findDek(String tenantId) {
            findDekCalls++;
            return listDeks(tenantId).stream().reduce((first, second) -> second);
        }

        @Override
        public Optional<EncryptedDek> findDek(String tenantId, int generation) {
            findDekCalls++;
            return Optional.ofNullable(deks.get(tenantId + "/" + generation));
        }

        @Override
        public List<EncryptedDek> listDeks(String tenantId) {
            return deks.values().stream().filter(d -> d.getTenantId().equals(tenantId))
                    .sorted(Comparator.comparingInt(EncryptedDek::getGeneration)).toList();
        }

        @Override
        public void deleteDek(String tenantId) {
            deks.entrySet().removeIf(e -> e.getValue().getTenantId().equals(tenantId));
        }

        @Override
        public List<EncryptedDek> listAllDeks() {
            return List.copyOf(deks.values());
        }

        @Override
        public String getMetaValue(String key) {
            return meta.get(key);
        }

        @Override
        public void setMetaValue(String key, String value) {
            meta.put(key, value);
        }
    }

    @Test
    @DisplayName("the in-memory persistence really does round-trip a stored secret")
    void selfCheckOfTheDouble() {
        // Guards the tests above from passing because the double never stored
        // anything in the first place.
        assertDoesNotThrow(() -> {
            storeSecret(List.of("agent-one"));
            assertEquals(PLAINTEXT, provider.resolve(REF));
            assertEquals(EnvelopeCrypto.sha256Hex(PLAINTEXT), provider.getMetadata(REF).checksum());
        });
    }
}
