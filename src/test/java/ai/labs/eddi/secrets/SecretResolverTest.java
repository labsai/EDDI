/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import ai.labs.eddi.engine.cluster.events.RecordingEventBus;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecretResolverTest {

    private ISecretProvider secretProvider;
    private SecretResolver resolver;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        secretProvider = mock(ISecretProvider.class);
        meterRegistry = new SimpleMeterRegistry();
        when(secretProvider.isAvailable()).thenReturn(true);
        resolver = new SecretResolver(secretProvider, meterRegistry, 5, 100);
        resolver.init(); // Initialize the Caffeine cache (@PostConstruct)
    }

    @Test
    @DisplayName("a value read while an invalidation ran is not cached: the next read goes back to the vault")
    void resolutionInFlightAcrossAnInvalidationIsNotCached() throws Exception {
        var ref = new SecretReference("default", "rotating-key");
        int[] calls = {0};
        when(secretProvider.resolve(ref)).thenAnswer(invocation -> {
            calls[0]++;
            if (calls[0] == 1) {
                // The rotation (or delete) lands while the old value is being read.
                resolver.invalidateLocal(ref);
                return "old-value";
            }
            return "new-value";
        });

        assertEquals("old-value", resolver.resolveValue("${vault:rotating-key}"), "the in-flight read itself returns what it read");
        assertEquals("new-value", resolver.resolveValue("${vault:rotating-key}"),
                "the stale value must not have been cached for the whole TTL");
    }

    @Test
    void requireResolved_unresolvableReferenceFailsClosedNamingTheReferenceOnly() throws Exception {
        var ref = new SecretReference("default", "missing-key");
        when(secretProvider.resolve(ref)).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        var resolved = resolver.resolveSecrets(Map.of("apiKey", "${vault:missing-key}", "modelName", "text-embedding-3-small"));
        var e = assertThrows(SecretResolver.UnresolvedSecretReferenceException.class,
                () -> SecretResolver.requireResolved(resolved, "embedding model 'openai'"));

        assertTrue(e.getMessage().contains("apiKey"), e.getMessage());
        assertTrue(e.getMessage().contains("${vault:missing-key}"), e.getMessage());
        assertTrue(e.getMessage().contains("embedding model 'openai'"), e.getMessage());
    }

    @Test
    void requireResolved_vaultNotConfiguredFailsClosed() {
        ISecretProvider unavailable = mock(ISecretProvider.class);
        when(unavailable.isAvailable()).thenReturn(false);
        SecretResolver passthroughResolver = new SecretResolver(unavailable, meterRegistry, 5, 100);
        passthroughResolver.init();

        var passedThrough = passthroughResolver.resolveSecrets(Map.of("apiKey", "${vault:anthropic-key}"));

        assertThrows(SecretResolver.UnresolvedSecretReferenceException.class,
                () -> SecretResolver.requireResolved(passedThrough, "LLM provider 'anthropic'"));
    }

    @Test
    void requireResolved_resolvedAndPlainValuesPass() throws Exception {
        var ref = new SecretReference("default", "openaiKey");
        when(secretProvider.resolve(ref)).thenReturn("sk-actual");

        var resolved = resolver.resolveSecrets(Map.of("apiKey", "${vault:openaiKey}", "baseUrl", "https://api.openai.com"));

        assertSame(resolved, SecretResolver.requireResolved(resolved, "LLM provider 'openai'"));
        assertNull(SecretResolver.requireResolved(null, "x"));
        assertTrue(SecretResolver.requireResolved(Map.of(), "x").isEmpty());
    }

    @Test
    void resolveValue_noVaultRef_passthrough() {
        String input = "just a plain string";
        assertEquals(input, resolver.resolveValue(input));
    }

    @Test
    void resolveValue_null_returnsNull() {
        assertNull(resolver.resolveValue(null));
    }

    @Test
    void resolveValue_empty_returnsEmpty() {
        assertEquals("", resolver.resolveValue(""));
    }

    @Test
    void resolveValue_shortForm_resolvesToPlaintext() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        var ref = new SecretReference("default", "openaiKey");
        when(secretProvider.resolve(ref)).thenReturn("sk-actual-secret-key");

        String input = "Bearer ${vault:openaiKey}";
        String result = resolver.resolveValue(input);

        assertEquals("Bearer sk-actual-secret-key", result);
    }

    @Test
    void resolveValue_fullForm_resolvesToPlaintext() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        var ref = new SecretReference("myTenant", "openaiKey");
        when(secretProvider.resolve(ref)).thenReturn("sk-tenant-key");

        String input = "Bearer ${vault:myTenant/openaiKey}";
        String result = resolver.resolveValue(input);

        assertEquals("Bearer sk-tenant-key", result);
    }

    @Test
    void resolveValue_multipleRefs_allResolved() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        when(secretProvider.resolve(new SecretReference("default", "key1"))).thenReturn("val1");
        when(secretProvider.resolve(new SecretReference("default", "key2"))).thenReturn("val2");

        String input = "${vault:key1}:${vault:key2}";
        String result = resolver.resolveValue(input);

        assertEquals("val1:val2", result);
    }

    @Test
    void resolveValue_mixedFormats_allResolved() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        when(secretProvider.resolve(new SecretReference("default", "key1"))).thenReturn("val1");
        when(secretProvider.resolve(new SecretReference("acme", "key2"))).thenReturn("val2");

        String input = "${vault:key1}:${vault:acme/key2}";
        String result = resolver.resolveValue(input);

        assertEquals("val1:val2", result);
    }

    @Test
    void resolveValue_secretNotFound_keepsRef() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        when(secretProvider.resolve(any(SecretReference.class))).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        String input = "Bearer ${vault:missingKey}";
        String result = resolver.resolveValue(input);

        // When secret is not found, the reference should remain as-is
        assertEquals(input, result);
    }

    @Test
    void resolveValue_vaultNotAvailable_passthrough() {
        ISecretProvider unavailable = mock(ISecretProvider.class);
        when(unavailable.isAvailable()).thenReturn(false);
        SecretResolver passthroughResolver = new SecretResolver(unavailable, meterRegistry, 5, 100);
        passthroughResolver.init();

        String input = "Bearer ${vault:key}";
        assertEquals(input, passthroughResolver.resolveValue(input));
    }

    @Test
    void resolveValue_autoVaultKey_withAgentPrefix() throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        var ref = new SecretReference("default", "69c687.userApiKey");
        when(secretProvider.resolve(ref)).thenReturn("user-secret");

        String result = resolver.resolveValue("${vault:69c687.userApiKey}");

        assertEquals("user-secret", result);
    }

    // ─── Finding #3a: reserved namespaces (agent signing keys) never resolve via
    // config ───

    @Test
    void resolveValue_reservedSigningKey_isNeverResolved() throws Exception {
        // An editor who adds ${vault:agent-signing-key:X} to a header must NOT be able
        // to exfiltrate the private key: the reference is left literal, never resolved,
        // and the provider is never even consulted for it.
        String input = "Bearer ${vault:agent-signing-key:agentX}";
        String result = resolver.resolveValue(input);

        assertEquals(input, result, "the reserved reference must remain literal, not be replaced with the key");
        verify(secretProvider, never()).resolve(any(SecretReference.class));
    }

    @Test
    void resolveValue_reservedSigningKey_fullForm_isNeverResolved() throws Exception {
        String input = "${vault:default/agent-signing-key:agentX:v2}";
        assertEquals(input, resolver.resolveValue(input));
        verify(secretProvider, never()).resolve(any(SecretReference.class));
    }

    @Test
    void resolveValue_reservedSigningKey_failsClosedViaRequireResolved() {
        var resolved = resolver.resolveSecrets(Map.of("apiKey", "${vault:agent-signing-key:agentX}"));
        assertThrows(SecretResolver.UnresolvedSecretReferenceException.class,
                () -> SecretResolver.requireResolved(resolved, "LLM provider 'x'"));
    }

    @Nested
    @DisplayName("cluster invalidation")
    class ClusterInvalidation {

        @Test
        void localRotationIsAnnouncedAndRemoteOneIsNotRepublished() {
            var bus = new RecordingEventBus();
            var clustered = new SecretResolver(mock(ISecretProvider.class), new SimpleMeterRegistry(), 5, 100);
            clustered.clusterEvents = bus;
            clustered.init();
            var listened = new ArrayList<SecretReference>();
            clustered.registerInvalidationListener(listened::add);

            clustered.invalidateCache(new SecretReference("t1", "openai"));
            assertEquals(1, bus.ofType(ClusterEvent.SECRET_CHANGED).size());
            assertEquals(1, listened.size());

            bus.deliver(ClusterEvent.SECRET_CHANGED, Map.of("tenantId", "t1", "keyName", "openai"));
            assertEquals(2, listened.size(), "the remote rotation reaches the local listeners (model eviction)");
            assertEquals(1, bus.ofType(ClusterEvent.SECRET_CHANGED).size(), "a received event is never re-published");

            bus.deliver(ClusterEvent.SECRET_CHANGED, Map.of("all", true));
            assertEquals(3, listened.size());
        }
    }

    @Nested
    @DisplayName("an unresolved reference: why, and what the caller is told")
    class UnresolvedReferences {

        private final SecretReference ref = new SecretReference("default", "api-key");

        @Test
        @DisplayName("no such secret in the tenant looked up: NOT_FOUND")
        void notFound() throws Exception {
            when(secretProvider.getMetadata(ref)).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));
            assertEquals(SecretResolver.UnresolvedReason.NOT_FOUND, resolver.diagnoseUnresolved(ref));
        }

        @Test
        @DisplayName("the secret exists, so its resolution failed in the vault: PROVIDER_FAILED")
        void existsButDidNotResolve() throws Exception {
            when(secretProvider.getMetadata(ref)).thenReturn(null);
            assertEquals(SecretResolver.UnresolvedReason.PROVIDER_FAILED, resolver.diagnoseUnresolved(ref));
        }

        @Test
        @DisplayName("the metadata could not be read either: PROVIDER_FAILED")
        void metadataUnreadable() throws Exception {
            when(secretProvider.getMetadata(ref)).thenThrow(new ISecretProvider.SecretProviderException("db down"));
            assertEquals(SecretResolver.UnresolvedReason.PROVIDER_FAILED, resolver.diagnoseUnresolved(ref));
        }

        @Test
        @DisplayName("no vault, or a reserved name, is decided without asking the provider")
        void vaultOffAndReserved() throws Exception {
            assertEquals(SecretResolver.UnresolvedReason.RESERVED,
                    resolver.diagnoseUnresolved(new SecretReference("default", SecretReference.AGENT_SIGNING_KEY_PREFIX + "a1")));
            when(secretProvider.isAvailable()).thenReturn(false);
            assertEquals(SecretResolver.UnresolvedReason.VAULT_NOT_CONFIGURED, resolver.diagnoseUnresolved(ref));
            verify(secretProvider, never()).getMetadata(any());
        }

        @Test
        @DisplayName("a short-form reference that names no secret says it looked in tenant 'default' and how to name another")
        void notFoundMessageNamesTheDefaultTenant() {
            var failure = SecretResolver.unresolvedReferenceFailure("header 'X-Api-Key'", "${vault:api-key}",
                    SecretResolver.UnresolvedReason.NOT_FOUND);

            assertInstanceOf(SecretResolver.MisconfiguredVaultReferenceException.class, failure);
            assertTrue(failure.getMessage().startsWith("header 'X-Api-Key' references ${vault:api-key} (no tenant given, so tenant 'default')"),
                    failure.getMessage());
            assertTrue(failure.getMessage().contains("no secret named 'api-key' exists in tenant 'default'"), failure.getMessage());
            assertTrue(failure.getMessage().contains("${vault:<tenant>/api-key}"), failure.getMessage());
        }

        @Test
        @DisplayName("a full-form reference names its own tenant and gets no short-form hint")
        void notFoundMessageNamesTheExplicitTenant() {
            var failure = SecretResolver.unresolvedReferenceFailure("a request body", "${vault:eddi/api-key}",
                    SecretResolver.UnresolvedReason.NOT_FOUND);

            assertTrue(failure.getMessage().contains("${vault:eddi/api-key} (tenant 'eddi')"), failure.getMessage());
            assertTrue(failure.getMessage().contains("exists in tenant 'eddi'"), failure.getMessage());
            assertFalse(failure.getMessage().contains("<tenant>"), failure.getMessage());
        }

        @Test
        @DisplayName("no vault and a reserved name are configuration refusals too")
        void vaultOffAndReservedAreRefusals() {
            var off = SecretResolver.unresolvedReferenceFailure("x", "${vault:k}", SecretResolver.UnresolvedReason.VAULT_NOT_CONFIGURED);
            var reserved = SecretResolver.unresolvedReferenceFailure("x", "${vault:k}", SecretResolver.UnresolvedReason.RESERVED);

            assertInstanceOf(SecretResolver.MisconfiguredVaultReferenceException.class, off);
            assertTrue(off.getMessage().contains("EDDI_VAULT_MASTER_KEY"), off.getMessage());
            assertInstanceOf(SecretResolver.MisconfiguredVaultReferenceException.class, reserved);
        }

        @Test
        @DisplayName("a vault outage, or an unknown reason, is a plain IllegalArgumentException, not a refusal")
        void outageIsNotARefusal() {
            var outage = SecretResolver.unresolvedReferenceFailure("x", "${vault:k}", SecretResolver.UnresolvedReason.PROVIDER_FAILED);
            var unknown = SecretResolver.unresolvedReferenceFailure("x", "${vault:k}", null);

            assertEquals(IllegalArgumentException.class, outage.getClass());
            assertEquals(IllegalArgumentException.class, unknown.getClass());
            assertTrue(unknown.getMessage().contains("EDDI_VAULT_MASTER_KEY"), unknown.getMessage());
            assertTrue(unknown.getMessage().contains("(no tenant given, so tenant 'default')"), unknown.getMessage());
        }

        @Test
        @DisplayName("requireResolved names the tenant the reference was looked up in")
        void requireResolvedNamesTheTenant() {
            var e = assertThrows(SecretResolver.UnresolvedSecretReferenceException.class,
                    () -> SecretResolver.requireResolved(Map.of("apiKey", "${vault:anthropic-key}"), "chat model 'anthropic'"));

            assertTrue(e.getMessage().contains("${vault:anthropic-key} (no tenant given, so tenant 'default')"), e.getMessage());
        }
    }
}
