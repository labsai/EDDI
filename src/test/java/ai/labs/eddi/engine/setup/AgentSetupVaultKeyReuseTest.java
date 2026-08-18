/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Setting up several agents against ONE provider key must leave ONE secret in
 * the vault. These tests cover the three ways that is expressed — an existing
 * {@code ${vault:...}} reference, an explicit {@code vaultKeyName}, and the
 * same plaintext key pasted twice — plus the two ways it must NOT happen: a
 * reused entry is never recorded for rollback, and a named entry is never
 * silently overwritten.
 */
@DisplayName("AgentSetupService — vault key reuse")
class AgentSetupVaultKeyReuseTest {

    private static final String KEY = "sk-live-abcdef0123456789";

    @Mock
    private IRestInterfaceFactory restInterfaceFactory;
    @Mock
    private IRestAgentAdministration agentAdmin;
    @Mock
    private ISecretProvider secretProvider;

    private AgentSetupService service;
    private Map<String, Object> createdResources;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        service = new AgentSetupService(restInterfaceFactory, agentAdmin, secretProvider, "http://localhost:11434");
        service.vaultKeyReuse = AgentSetupService.VAULT_KEY_REUSE_CHECKSUM;
        createdResources = new LinkedHashMap<>();
        when(secretProvider.isAvailable()).thenReturn(true);
        when(secretProvider.listKeys(anyString())).thenReturn(List.of());
    }

    private String vaultApiKey(String apiKey, String vaultKeyName) throws Exception {
        Method method = AgentSetupService.class.getDeclaredMethod("vaultApiKey", String.class, String.class, String.class, Map.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(service, apiKey, "My Agent", vaultKeyName, createdResources);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static SecretMetadata entry(String keyName, String value, Instant createdAt, List<String> allowedAgents) {
        return new SecretMetadata(SecretReference.DEFAULT_TENANT, keyName, createdAt, null, null, EnvelopeCrypto.sha256Hex(value),
                "test", allowedAgents);
    }

    // ─── an apiKey that is already a reference ───────────────────────────

    @Nested
    @DisplayName("apiKey is already a vault reference")
    class AlreadyAReference {

        /**
         * The regression this whole change starts from. The reference check is a
         * full-string match, so a value pasted out of a UI list — trailing newline
         * included — used to miss it and get vaulted as plaintext whose content is a
         * reference: a brand-new, useless key on every single setup.
         */
        @Test
        @DisplayName("survives the whitespace a paste carries with it")
        void trimmedBeforeTheReferenceCheck() throws Exception {
            String result = vaultApiKey("  ${vault:openai-prod}\n", null);

            assertEquals("${vault:openai-prod}", result);
            verify(secretProvider, never()).store(any(), anyString(), anyString(), any());
            assertFalse(createdResources.containsKey(AgentSetupService.VAULTED_SECRET_KEY));
        }

        @Test
        @DisplayName("a value merely CONTAINING a reference is not one")
        void embeddedReferenceIsNotAReference() throws Exception {
            String result = vaultApiKey("Bearer ${vault:openai-prod}", null);

            assertTrue(result.startsWith("${vault:setup.my-agent."), "Expected a freshly vaulted key, got: " + result);
            verify(secretProvider).store(any(), eq("Bearer ${vault:openai-prod}"), anyString(), any());
        }
    }

    // ─── plaintext the vault already holds ───────────────────────────────

    @Nested
    @DisplayName("plaintext the vault already holds")
    class ChecksumReuse {

        @Test
        @DisplayName("references the existing entry instead of storing a second copy")
        void reusesByChecksum() throws Exception {
            when(secretProvider.listKeys(SecretReference.DEFAULT_TENANT))
                    .thenReturn(List.of(entry("setup.first-agent.1.apiKey", KEY, Instant.EPOCH, List.of("*"))));

            assertEquals("${vault:setup.first-agent.1.apiKey}", vaultApiKey(KEY, null));
            verify(secretProvider, never()).store(any(), anyString(), anyString(), any());
        }

        /**
         * Rollback deletes whatever it finds under {@code VAULTED_SECRET_KEY}. A reused
         * entry belongs to the agent that created it, so recording it here would let
         * the failure of a LATER agent delete a key the earlier one is still pointing
         * at.
         */
        @Test
        @DisplayName("a reused entry is never recorded for rollback")
        void reusedEntryIsNotRollbackFodder() throws Exception {
            when(secretProvider.listKeys(SecretReference.DEFAULT_TENANT))
                    .thenReturn(List.of(entry("shared.key", KEY, Instant.EPOCH, null)));

            vaultApiKey(KEY, null);

            assertFalse(createdResources.containsKey(AgentSetupService.VAULTED_SECRET_KEY));
        }

        /**
         * A narrowed grant was narrowed deliberately. Referencing it from a new agent
         * produces a config VaultGrantGate rejects at deploy time — a reuse that
         * "works" right up until it matters.
         */
        @Test
        @DisplayName("a narrowly granted entry is not a reuse candidate")
        void skipsRestrictedGrants() throws Exception {
            when(secretProvider.listKeys(SecretReference.DEFAULT_TENANT))
                    .thenReturn(List.of(entry("scoped.key", KEY, Instant.EPOCH, List.of("agent-42"))));

            assertTrue(vaultApiKey(KEY, null).startsWith("${vault:setup.my-agent."));
            verify(secretProvider).store(any(), eq(KEY), anyString(), any());
        }

        @Test
        @DisplayName("a different value is not a match")
        void differentValueIsNotAMatch() throws Exception {
            when(secretProvider.listKeys(SecretReference.DEFAULT_TENANT))
                    .thenReturn(List.of(entry("other.key", "sk-some-other-key", Instant.EPOCH, List.of("*"))));

            assertTrue(vaultApiKey(KEY, null).startsWith("${vault:setup.my-agent."));
        }

        /**
         * Repeated setups with one key must converge on ONE entry, so the winner cannot
         * depend on the order the store happens to list in.
         */
        @Test
        @DisplayName("picks the oldest match, whatever order the vault lists in")
        void deterministicWinner() throws Exception {
            when(secretProvider.listKeys(SecretReference.DEFAULT_TENANT)).thenReturn(List.of(
                    entry("newer.key", KEY, Instant.ofEpochSecond(2000), List.of("*")),
                    entry("older.key", KEY, Instant.ofEpochSecond(1000), List.of("*"))));

            assertEquals("${vault:older.key}", vaultApiKey(KEY, null));
        }

        @Test
        @DisplayName("eddi.setup.vault-key-reuse=never restores per-agent keys")
        void reuseCanBeSwitchedOff() throws Exception {
            service.vaultKeyReuse = AgentSetupService.VAULT_KEY_REUSE_NEVER;
            when(secretProvider.listKeys(SecretReference.DEFAULT_TENANT))
                    .thenReturn(List.of(entry("shared.key", KEY, Instant.EPOCH, List.of("*"))));

            assertTrue(vaultApiKey(KEY, null).startsWith("${vault:setup.my-agent."));
            verify(secretProvider).store(any(), eq(KEY), anyString(), any());
        }

        /** A listing failure must not fail the setup — reuse is an optimisation. */
        @Test
        @DisplayName("an unlistable vault falls back to storing a new entry")
        void listFailureDegradesToStore() throws Exception {
            when(secretProvider.listKeys(SecretReference.DEFAULT_TENANT))
                    .thenThrow(new ISecretProvider.SecretProviderException("backend down"));

            assertTrue(vaultApiKey(KEY, null).startsWith("${vault:setup.my-agent."));
        }

        @Test
        @DisplayName("the stored value is trimmed, not the raw paste")
        void storesTheTrimmedKey() throws Exception {
            vaultApiKey("  " + KEY + "\n", null);

            verify(secretProvider).store(any(), eq(KEY), anyString(), any());
        }
    }

    // ─── an explicit vaultKeyName ────────────────────────────────────────

    @Nested
    @DisplayName("explicit vaultKeyName")
    class NamedKey {

        @Test
        @DisplayName("names an existing entry, needing no apiKey at all")
        void reusesExistingWithoutPlaintext() throws Exception {
            when(secretProvider.getMetadata(new SecretReference(SecretReference.DEFAULT_TENANT, "openai-prod")))
                    .thenReturn(entry("openai-prod", KEY, Instant.EPOCH, List.of("*")));

            assertEquals("${vault:openai-prod}", vaultApiKey(null, "openai-prod"));
            verify(secretProvider, never()).store(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("accepts the reference form as well as the bare name")
        void acceptsReferenceForm() throws Exception {
            when(secretProvider.getMetadata(new SecretReference(SecretReference.DEFAULT_TENANT, "openai-prod")))
                    .thenReturn(entry("openai-prod", KEY, Instant.EPOCH, List.of("*")));

            assertEquals("${vault:openai-prod}", vaultApiKey(null, "${vault:openai-prod}"));
        }

        @Test
        @DisplayName("an existing entry holding the SAME value is reused, not rewritten")
        void sameValueIsNotRewritten() throws Exception {
            when(secretProvider.getMetadata(any())).thenReturn(entry("openai-prod", KEY, Instant.EPOCH, List.of("*")));

            assertEquals("${vault:openai-prod}", vaultApiKey(KEY, "openai-prod"));
            verify(secretProvider, never()).store(any(), anyString(), anyString(), any());
        }

        /**
         * Overwriting here would silently rotate the credential of every OTHER agent
         * already pointing at that name.
         */
        @Test
        @DisplayName("an existing entry holding a DIFFERENT value is refused, not overwritten")
        void differentValueIsRefused() throws Exception {
            when(secretProvider.getMetadata(any())).thenReturn(entry("openai-prod", "sk-the-old-one", Instant.EPOCH, List.of("*")));

            var e = assertThrows(AgentSetupService.AgentSetupException.class, () -> vaultApiKey(KEY, "openai-prod"));

            assertTrue(e.getMessage().contains("already holds a different value"), e.getMessage());
            verify(secretProvider, never()).store(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a missing entry is created under exactly that name")
        void createsUnderTheGivenName() throws Exception {
            when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("nope"));

            assertEquals("${vault:openai-prod}", vaultApiKey(KEY, "openai-prod"));

            var ref = ArgumentCaptor.forClass(SecretReference.class);
            verify(secretProvider).store(ref.capture(), eq(KEY), anyString(), any());
            assertEquals("openai-prod", ref.getValue().keyName());
            assertEquals("openai-prod", createdResources.get(AgentSetupService.VAULTED_SECRET_KEY),
                    "A newly created entry IS this setup's to roll back");
        }

        @Test
        @DisplayName("a missing entry with no apiKey to create it from is an error")
        void missingAndNothingToCreateItFrom() throws Exception {
            when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("nope"));

            var e = assertThrows(AgentSetupService.AgentSetupException.class, () -> vaultApiKey(null, "openai-prod"));

            assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        }

        /**
         * The generated-name path degrades to plaintext when the vault is off, because
         * dev mode must keep working. A caller who NAMED a key asked for a specific
         * shared secret, and silently writing their key in plaintext instead is not a
         * smaller version of that request.
         */
        @Test
        @DisplayName("fails loudly when the vault is unavailable, rather than degrading to plaintext")
        void refusesWithoutAVault() throws Exception {
            when(secretProvider.isAvailable()).thenReturn(false);

            var e = assertThrows(AgentSetupService.AgentSetupException.class, () -> vaultApiKey(KEY, "openai-prod"));

            assertTrue(e.getMessage().contains("vault is unavailable"), e.getMessage());
        }
    }
}
