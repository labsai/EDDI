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
}
