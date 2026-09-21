/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /**
     * Deliberately shaped so it cannot be mistaken for a live credential: a
     * realistic-looking "sk-live-..." fixture is exactly the pattern a real leaked
     * key takes, and it tripped the gitleaks generic-api-key rule in CI. Nothing
     * here depends on the value beyond it being distinct and stable.
     */
    private static final String KEY = "test-provider-key-not-a-real-secret";

    @Mock
    private IRestInterfaceFactory restInterfaceFactory;
    @Mock
    private IRestAgentAdministration agentAdmin;
    @Mock
    private ISecretProvider secretProvider;

    private AgentSetupService service;
    private Map<String, Object> createdResources;

    @Mock
    private SecretResolver secretResolver;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        service = new AgentSetupService(restInterfaceFactory, agentAdmin, secretProvider, "http://localhost:11434");
        service.vaultKeyReuse = AgentSetupService.VAULT_KEY_REUSE_CHECKSUM;
        service.secretResolver = secretResolver;
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

        /**
         * Pass-through has always been accepted as-is and still is — a caller may vault
         * the key after setup. But the entry is looked up so a typo'd paste is at least
         * visible in the log rather than surfacing as an agent that deploys and then
         * fails on its first turn.
         */
        @Test
        @DisplayName("a reference to a missing key is still accepted, and looked up")
        void danglingReferenceIsAcceptedButChecked() throws Exception {
            when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("nope"));

            assertEquals("${vault:typo-key}", vaultApiKey("${vault:typo-key}", null));

            verify(secretProvider).getMetadata(new SecretReference(SecretReference.DEFAULT_TENANT, "typo-key"));
            verify(secretProvider, never()).store(any(), anyString(), anyString(), any());
            // A log line does not reach the caller. Both this and the restricted-grant
            // case end in an agent that was created and cannot use its credential.
            assertTrue(String.valueOf(createdResources.get(AgentSetupService.VAULT_WARNING)).contains("does not exist"),
                    String.valueOf(createdResources.get(AgentSetupService.VAULT_WARNING)));
        }

        /**
         * A brand-new agent's ID cannot be in any grant list that already exists, so
         * under enforce the deployment will be blocked. Not a reason to refuse — the
         * legitimate flow is setup without deploy, widen the grant, deploy — but a
         * reason to tell the caller in the response, not only in the server log.
         */
        @Test
        @DisplayName("a reference to a narrowly granted key is accepted, with a warning in the result")
        void restrictedReferenceWarnsInResult() throws Exception {
            when(secretProvider.getMetadata(any())).thenReturn(entry("scoped", KEY, Instant.EPOCH, List.of("agent-42")));

            assertEquals("${vault:scoped}", vaultApiKey("${vault:scoped}", null));

            String warning = String.valueOf(createdResources.get(AgentSetupService.VAULT_WARNING));
            assertTrue(warning.contains("agent-42") && warning.contains("blocked"), warning);
        }

        @Test
        @DisplayName("an unrestricted reference carries no warning")
        void unrestrictedReferenceIsQuiet() throws Exception {
            when(secretProvider.getMetadata(any())).thenReturn(entry("open", KEY, Instant.EPOCH, List.of("*")));

            vaultApiKey("${vault:open}", null);

            assertFalse(createdResources.containsKey(AgentSetupService.VAULT_WARNING));
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
                    .thenReturn(List.of(entry("other.key", "test-a-different-key", Instant.EPOCH, List.of("*"))));

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

        /**
         * Under checksum reuse a freshly created entry is a shared resource the moment
         * a second setup with the same key runs — parallel bulk provisioning does
         * exactly that. Recording it for rollback would let the FIRST agent's failure
         * delete the key every later one already points at, and the growth rollback
         * exists to prevent cannot happen here anyway: a retry finds this entry by
         * value and reuses it.
         */
        @Test
        @DisplayName("under checksum reuse, a fresh entry is NOT registered for rollback")
        void freshEntryNotRollbackFodderUnderChecksum() throws Exception {
            vaultApiKey(KEY, null);

            verify(secretProvider).store(any(), eq(KEY), anyString(), any());
            assertFalse(createdResources.containsKey(AgentSetupService.VAULTED_SECRET_KEY));
        }

        /**
         * Under `never` nobody else can find the entry, so the original rollback still
         * applies.
         */
        @Test
        @DisplayName("under never, a fresh entry IS registered for rollback")
        void freshEntryIsRollbackFodderUnderNever() throws Exception {
            service.vaultKeyReuse = AgentSetupService.VAULT_KEY_REUSE_NEVER;

            vaultApiKey(KEY, null);

            assertTrue(String.valueOf(createdResources.get(AgentSetupService.VAULTED_SECRET_KEY)).startsWith("setup.my-agent."));
        }

        /**
         * The timestamp alone did not make the name unique: two setups for agents with
         * the same name in the same millisecond produced the same key, and store is an
         * UPSERT, so one silently overwrote the other's credential.
         */
        @Test
        @DisplayName("two same-named agents in the same millisecond get different keys")
        void generatedNamesDoNotCollide() throws Exception {
            var names = new HashSet<String>();
            for (int i = 0; i < 50; i++) {
                createdResources.clear();
                service.vaultKeyReuse = AgentSetupService.VAULT_KEY_REUSE_NEVER;
                names.add(vaultApiKey(KEY, null));
            }

            assertEquals(50, names.size(), "generated vault key names must be unique: " + names);
        }

        /**
         * The counter-intuitive half of this: it matters most on CREATION. An agent set
         * up against a key that did not exist yet — which this service allows, warning
         * rather than refusing — may already have a cached model holding the unresolved
         * "${vault:...}" literal as its API key. Filling that key in later without
         * invalidating leaves it broken until the model cache expires. RestSecretStore
         * invalidates for exactly this reason; a direct write here has to as well.
         */
        @Test
        @DisplayName("storing a secret invalidates the resolver cache")
        void storeInvalidatesTheResolverCache() throws Exception {
            vaultApiKey(KEY, null);

            verify(secretResolver).invalidateCache(any(SecretReference.class));
        }

        @Test
        @DisplayName("the stored value is trimmed, not the raw paste")
        void storesTheTrimmedKey() throws Exception {
            vaultApiKey("  " + KEY + "\n", null);

            verify(secretProvider).store(any(), eq(KEY), anyString(), any());
        }

        /**
         * A name recorded before the write would name a secret that does not exist, and
         * rollback would log a "could not remove" warning chasing it.
         */
        @Test
        @DisplayName("a failed store records nothing for rollback")
        void failedStoreRecordsNothing() throws Exception {
            service.vaultKeyReuse = AgentSetupService.VAULT_KEY_REUSE_NEVER; // the mode that DOES record on success
            doThrow(new ISecretProvider.SecretProviderException("disk full")).when(secretProvider).store(any(), anyString(), anyString(),
                    any());

            assertEquals(KEY, vaultApiKey(KEY, null), "falls back to plaintext");
            assertFalse(createdResources.containsKey(AgentSetupService.VAULTED_SECRET_KEY));
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
        @DisplayName("naming a narrowly granted key is accepted, with a warning in the result")
        void restrictedNamedKeyWarnsInResult() throws Exception {
            when(secretProvider.getMetadata(any())).thenReturn(entry("scoped", KEY, Instant.EPOCH, List.of("agent-42")));

            assertEquals("${vault:scoped}", vaultApiKey(null, "scoped"));

            assertTrue(String.valueOf(createdResources.get(AgentSetupService.VAULT_WARNING)).contains("agent-42"));
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
            when(secretProvider.getMetadata(any())).thenReturn(entry("openai-prod", "test-the-previously-stored-key", Instant.EPOCH, List.of("*")));

            var e = assertThrows(AgentSetupService.AgentSetupException.class, () -> vaultApiKey(KEY, "openai-prod"));

            assertTrue(e.getMessage().contains("does not match"), e.getMessage());
            verify(secretProvider, never()).store(any(), anyString(), anyString(), any());
        }

        /**
         * Rollback deletes what it finds under VAULTED_SECRET_KEY. A caller-chosen name
         * cannot grow the vault on retry (the retry reuses the name), while a
         * concurrent setup may already have reused the entry — so deleting it is the
         * riskier of the two options, not the safer one.
         */
        @Test
        @DisplayName("a newly created NAMED entry is left alone by rollback")
        void namedEntryIsNotRollbackFodder() throws Exception {
            when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("nope"));

            vaultApiKey(KEY, "openai-prod");

            assertFalse(createdResources.containsKey(AgentSetupService.VAULTED_SECRET_KEY));
        }

        /**
         * Honouring the name and dropping the reference silently would deploy the agent
         * against a credential the caller did not pick.
         */
        @Test
        @DisplayName("apiKey and vaultKeyName naming DIFFERENT keys is refused")
        void contradictoryNamesAreRefused() throws Exception {
            var e = assertThrows(AgentSetupService.AgentSetupException.class,
                    () -> vaultApiKey("${vault:openai-dev}", "openai-prod"));

            assertTrue(e.getMessage().contains("Pass one or the other"), e.getMessage());
        }

        @Test
        @DisplayName("apiKey and vaultKeyName naming the SAME key is fine")
        void agreeingNamesAreAccepted() throws Exception {
            when(secretProvider.getMetadata(any())).thenReturn(entry("openai-prod", KEY, Instant.EPOCH, List.of("*")));

            assertEquals("${vault:openai-prod}", vaultApiKey("${vault:openai-prod}", "openai-prod"));
        }

        /**
         * The name is about to be embedded in "${vault:<tenant>/<key>}". A bare name
         * containing '/' would re-parse as a tenant separator and a '}' would truncate
         * the reference, so the agent would resolve a different secret — or none.
         */
        @Test
        @DisplayName("a name that would not survive the reference syntax is refused")
        void malformedNamesAreRefused() {
            for (String bad : List.of("tenant/key", "key}", "key with spaces")) {
                assertThrows(Exception.class, () -> vaultApiKey(KEY, bad), "should have refused: " + bad);
            }
        }

        /** An empty field is "not supplied", not a name of zero characters. */
        @Test
        @DisplayName("blank vaultKeyName is treated as absent")
        void blankNameIsAbsent() throws Exception {
            assertTrue(vaultApiKey(KEY, "   ").startsWith("${vault:setup.my-agent."));
        }

        /**
         * The reference is parsed for its tenant, so the create must honour it too. The
         * first cut looked the entry up under "acme" and then created it under
         * "default" — a setup that "worked" and left the agent referencing a secret
         * that did not exist where it pointed.
         */
        @Test
        @DisplayName("a tenant-qualified name is created in that tenant")
        void tenantQualifiedNameStaysInItsTenant() throws Exception {
            when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("nope"));

            assertEquals("${vault:acme/openai-prod}", vaultApiKey(KEY, "${vault:acme/openai-prod}"));

            var ref = ArgumentCaptor.forClass(SecretReference.class);
            verify(secretProvider).store(ref.capture(), eq(KEY), anyString(), any());
            assertEquals("acme", ref.getValue().tenantId());
            assertEquals("openai-prod", ref.getValue().keyName());
        }

        /**
         * store is an UPSERT and the absent-then-create sequence is not atomic, so two
         * setups naming one key with different values can both find it missing and both
         * write. Reading back turns the common interleaving into a loud failure before
         * any document exists, instead of an agent provisioned against the other
         * caller's credential.
         */
        @Test
        @DisplayName("a value overwritten concurrently is detected, not accepted")
        void concurrentOverwriteIsDetected() throws Exception {
            when(secretProvider.getMetadata(any()))
                    .thenThrow(new ISecretProvider.SecretNotFoundException("absent"))
                    .thenReturn(entry("openai-prod", "the-other-callers-key", Instant.EPOCH, List.of("*")));

            var e = assertThrows(AgentSetupService.AgentSetupException.class, () -> vaultApiKey(KEY, "openai-prod"));

            assertTrue(e.getMessage().contains("written concurrently"), e.getMessage());
        }

        @Test
        @DisplayName("a missing entry is created under exactly that name")
        void createsUnderTheGivenName() throws Exception {
            when(secretProvider.getMetadata(any()))
                    .thenThrow(new ISecretProvider.SecretNotFoundException("nope"))
                    .thenReturn(entry("openai-prod", KEY, Instant.EPOCH, List.of("*")));

            assertEquals("${vault:openai-prod}", vaultApiKey(KEY, "openai-prod"));

            var ref = ArgumentCaptor.forClass(SecretReference.class);
            verify(secretProvider).store(ref.capture(), eq(KEY), anyString(), any());
            assertEquals("openai-prod", ref.getValue().keyName());
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

    // ─── what the response body is allowed to carry ──────────────────────

    @Nested
    @DisplayName("SetupResult.apiKeyVaultReference")
    class ResultReference {

        private String vaultReferenceOrNull(String effectiveApiKey) throws Exception {
            Method method = AgentSetupService.class.getDeclaredMethod("vaultReferenceOrNull", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, effectiveApiKey);
        }

        @Test
        @DisplayName("hands back the reference, so the next agent can name it")
        void exposesTheReference() throws Exception {
            assertEquals("${vault:openai-prod}", vaultReferenceOrNull("${vault:openai-prod}"));
        }

        /**
         * With the vault disabled the "effective api key" IS the caller's secret.
         * Echoing it in a response body would put it through every proxy log between
         * here and the client.
         */
        @Test
        @DisplayName("never echoes a plaintext key")
        void neverEchoesPlaintext() throws Exception {
            assertNull(vaultReferenceOrNull(KEY));
            assertNull(vaultReferenceOrNull(null));
        }
    }

    // ─── eddi.setup.vault-key-reuse ──────────────────────────────────────

    @Nested
    @DisplayName("eddi.setup.vault-key-reuse")
    class ReuseMode {

        /**
         * Same convention as eddi.vault.grant-enforcement: an unrecognised value fails
         * startup. Degrading would mean "no reuse" — the original bug back, with every
         * visible sign saying dedup is on.
         */
        @Test
        @DisplayName("an unrecognised value fails startup instead of silently disabling reuse")
        void unknownModeFailsFast() {
            service.vaultKeyReuse = "checksumm";

            var e = assertThrows(IllegalArgumentException.class, service::validateVaultKeyReuse);

            assertTrue(e.getMessage().contains("checksumm"), e.getMessage());
        }

        @Test
        @DisplayName("both documented values pass, case-insensitively")
        void knownModesPass() {
            for (String ok : List.of("checksum", "never", "CHECKSUM", "Never")) {
                service.vaultKeyReuse = ok;
                service.validateVaultKeyReuse();
            }
        }
    }

    // ─── ordering against the rest of setup ──────────────────────────────

    @Nested
    @DisplayName("createApiAgent ordering")
    class ApiAgentOrdering {

        /**
         * Key resolution runs ahead of every store call so an unusable vaultKeyName
         * fails while rollback has nothing to undo. That put it ahead of the OpenAPI
         * parse too — and the parse is the likeliest way this call fails. Under
         * vault-key-reuse=never the secret was written first and then leaked, because
         * createApiAgent's AgentSetupException arm rethrows unwrapped.
         *
         * Parsing creates nothing, so it belongs first: an unparseable spec must not
         * reach the vault at all rather than rely on rollback to undo a write that
         * should never have happened.
         */
        @Test
        @DisplayName("an unparseable spec never reaches the vault")
        void invalidSpecDoesNotTouchTheVault() {
            service.vaultKeyReuse = AgentSetupService.VAULT_KEY_REUSE_NEVER;
            var request = new CreateApiAgentRequest("Api Agent", "be helpful", "this is not an OpenAPI document", "anthropic",
                    "claude-sonnet-5", KEY, null, null, null, null, null, false, null, null, null, null, null, null, null);

            var e = assertThrows(AgentSetupService.AgentSetupException.class, () -> service.createApiAgent(request));

            assertTrue(e.getMessage().contains("OpenAPI"), e.getMessage());
            verifyNoInteractions(secretProvider);
        }
    }
}
