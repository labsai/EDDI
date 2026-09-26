/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider.GrantConflictException;
import ai.labs.eddi.secrets.ISecretProvider.SecretNotFoundException;
import ai.labs.eddi.secrets.ISecretProvider.SecretProviderException;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    // ─── S6: an optional precondition on the grant the editor loaded ───

    @Test
    @DisplayName("a conditional edit whose precondition still holds is applied — order and wildcard spelling do not matter")
    void conditionalEditApplies() throws Exception {
        storeSecret(List.of("agent-one", "agent-two"));

        SecretMetadata after = provider.updateGrant(REF, List.of("agent-one"), null, List.of("agent-two", "agent-one"));

        assertEquals(List.of("agent-one"), after.allowedAgents());
        assertEquals(List.of("agent-one"), provider.getMetadata(REF).allowedAgents());

        // [] and ["*"] both mean every agent, so either spelling matches the other.
        provider.updateGrant(REF, List.of("*"), null);
        assertEquals(List.of("agent-two"), provider.updateGrant(REF, List.of("agent-two"), null, List.of()).allowedAgents());
    }

    /**
     * S6: two operators editing the same grant from what they each loaded. Without
     * a precondition the later write silently reinstated the agent the earlier one
     * had just removed.
     */
    @Test
    @DisplayName("a conditional edit built on a stale grant is refused with the current grant, and writes nothing")
    void staleConditionalEditIsRefused() throws Exception {
        storeSecret(List.of("agent-one", "agent-two"));
        provider.updateGrant(REF, List.of("agent-one"), null); // the other operator's narrowing

        var conflict = assertThrows(GrantConflictException.class,
                () -> provider.updateGrant(REF, List.of("agent-one", "agent-two", "agent-three"), null, List.of("agent-one", "agent-two")));

        assertEquals(List.of("agent-one"), conflict.getCurrentAllowedAgents());
        assertEquals(List.of("agent-one"), provider.getMetadata(REF).allowedAgents());
    }

    @Test
    @DisplayName("an edit landing between the conditional edit's read and its write is still detected")
    void conditionalEditLosesARaceCleanly() throws Exception {
        storeSecret(List.of("agent-one", "agent-two"));

        persistence.afterNextFind = () -> {
            try {
                provider.updateGrant(REF, List.of("agent-two"), null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        var conflict = assertThrows(GrantConflictException.class,
                () -> provider.updateGrant(REF, List.of("agent-one", "agent-two", "agent-three"), null, List.of("agent-one", "agent-two")));

        assertEquals(List.of("agent-two"), conflict.getCurrentAllowedAgents());
        assertEquals(List.of("agent-two"), provider.getMetadata(REF).allowedAgents(), "the concurrent edit must survive");
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
