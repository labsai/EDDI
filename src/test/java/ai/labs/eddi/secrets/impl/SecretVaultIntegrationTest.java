/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests that exercise the full vault stack: SecretResolver →
 * VaultSecretProvider → (mock) persistence.
 * <p>
 * Tests verify end-to-end behavior including:
 * <ul>
 * <li>Negative caching fix — failed resolutions never cached</li>
 * <li>Cache invalidation on store/delete</li>
 * <li>DEK rotation — re-encrypt all secrets with new DEK</li>
 * <li>KEK rotation — re-encrypt all DEKs with new master key</li>
 * <li>Metrics emission — counters and timers fire correctly</li>
 * <li>Exception propagation — persistence failures surface correctly</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
class SecretVaultIntegrationTest {

    private static final String MASTER_KEY = "test-master-key-for-integration-tests";
    private static final String TENANT = "default";
    private static final String KEY_NAME = "openaiKey";
    private static final String SECRET_VALUE = "sk-test-12345";

    private ISecretPersistence persistence;
    private SimpleMeterRegistry meterRegistry;
    private VaultSecretProvider provider;
    private SecretResolver resolver;

    // In-memory stores to simulate real persistence
    private final Map<String, EncryptedDek> dekStore = new HashMap<>();
    private final Map<String, EncryptedSecret> secretStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        persistence = mock(ISecretPersistence.class);
        meterRegistry = new SimpleMeterRegistry();
        dekStore.clear();
        secretStore.clear();

        // Create provider with real crypto, mocked persistence
        var saltManager = new VaultSaltManager(persistence);
        saltManager.initialize(); // Uses legacy salt since mock returns null for meta
        provider = new VaultSecretProvider(Optional.of(MASTER_KEY), persistence, saltManager, meterRegistry);
        provider.initMetrics();
        provider.onStartup(new StartupEvent());

        // Create resolver with the real provider — call @PostConstruct via reflection
        // since init() is package-private in ai.labs.eddi.secrets
        resolver = new SecretResolver(provider, meterRegistry, 5, 1000);
        try {
            var initMethod = SecretResolver.class.getDeclaredMethod("init");
            initMethod.setAccessible(true);
            initMethod.invoke(resolver);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SecretResolver for test", e);
        }
    }

    /**
     * Set up mock persistence to simulate a working DEK store.
     */
    private void setupDekMocking() {
        doAnswer(inv -> {
            EncryptedDek dek = inv.getArgument(0);
            dekStore.put(dek.getTenantId(), dek);
            return null;
        }).when(persistence).upsertDek(any());

        when(persistence.findDek(anyString())).thenAnswer(inv -> {
            String tenantId = inv.getArgument(0);
            return Optional.ofNullable(dekStore.get(tenantId));
        });

        lenient().when(persistence.listAllDeks()).thenAnswer(inv -> new ArrayList<>(dekStore.values()));
    }

    /**
     * Set up mock persistence to simulate a working secret store.
     */
    private void setupSecretMocking() {
        doAnswer(inv -> {
            EncryptedSecret secret = inv.getArgument(0);
            secretStore.put(secret.getTenantId() + "/" + secret.getKeyName(), secret);
            return null;
        }).when(persistence).upsertSecret(any());

        when(persistence.findSecret(anyString(), anyString())).thenAnswer(inv -> {
            String tid = inv.getArgument(0);
            String kn = inv.getArgument(1);
            return Optional.ofNullable(secretStore.get(tid + "/" + kn));
        });

        when(persistence.listSecretsByTenant(anyString())).thenAnswer(inv -> {
            String tid = inv.getArgument(0);
            return secretStore.values().stream().filter(s -> s.getTenantId().equals(tid)).toList();
        });

        when(persistence.deleteSecret(anyString(), anyString())).thenAnswer(inv -> {
            String tid = inv.getArgument(0);
            String kn = inv.getArgument(1);
            return secretStore.remove(tid + "/" + kn) != null;
        });
    }

    // ─── Full Round-Trip ───

    @Nested
    @DisplayName("Full Round-Trip: Store → Resolve via SecretResolver")
    class FullRoundTrip {

        @Test
        @DisplayName("store and resolve a secret through the full stack")
        void storeAndResolve() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            var ref = new SecretReference(TENANT, KEY_NAME);
            provider.store(ref, SECRET_VALUE, "Test API key", List.of("*"));

            String resolved = resolver.resolveValue("${eddivault:" + KEY_NAME + "}");
            assertEquals(SECRET_VALUE, resolved);
        }

        @Test
        @DisplayName("resolve full-form reference with tenant")
        void resolveFullForm() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            var ref = new SecretReference("production", KEY_NAME);
            provider.store(ref, SECRET_VALUE, "Prod key", null);

            String resolved = resolver.resolveValue("${eddivault:production/" + KEY_NAME + "}");
            assertEquals(SECRET_VALUE, resolved);
        }

        @Test
        @DisplayName("resolve multiple vault references in one string")
        void resolveMultipleRefs() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, "apiKey"), "key-123", null, null);
            provider.store(new SecretReference(TENANT, "apiSecret"), "secret-456", null, null);

            String input = "key=${eddivault:apiKey}&secret=${eddivault:apiSecret}";
            String resolved = resolver.resolveValue(input);
            assertEquals("key=key-123&secret=secret-456", resolved);
        }
    }

    // ─── Negative Caching Fix ───

    @Nested
    @DisplayName("Negative Caching Fix")
    class NegativeCachingFix {

        @Test
        @DisplayName("failed resolution is NOT cached — subsequent resolve succeeds after store")
        void failedResolutionNotCached() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            // First attempt — secret doesn't exist yet
            String result1 = resolver.resolveValue("${eddivault:" + KEY_NAME + "}");
            // Should pass through unchanged (not found)
            assertEquals("${eddivault:" + KEY_NAME + "}", result1);

            // Now store the secret
            provider.store(new SecretReference(TENANT, KEY_NAME), SECRET_VALUE, null, null);

            // Second attempt — should now resolve successfully
            // The POINT of this test: we DON'T call invalidateAll()
            // because failed resolutions should NEVER be cached.
            String result2 = resolver.resolveValue("${eddivault:" + KEY_NAME + "}");
            assertEquals(SECRET_VALUE, result2);
        }

        @Test
        @DisplayName("successful resolution IS cached — provider only called once for reads")
        void successfulResolutionIsCached() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, KEY_NAME), SECRET_VALUE, null, null);

            // Resolve twice
            resolver.resolveValue("${eddivault:" + KEY_NAME + "}");
            resolver.resolveValue("${eddivault:" + KEY_NAME + "}");

            // Second resolve should use cache — verify cache hit counter
            assertEquals(1.0, meterRegistry.counter("eddi.vault.cache.hits").count());
        }
    }

    // ─── DEK Rotation ───

    @Nested
    @DisplayName("DEK Rotation")
    class DekRotation {

        @Test
        @DisplayName("rotate DEK and verify secrets still resolve")
        void rotateDekAndResolve() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, "key1"), "value1", null, null);
            provider.store(new SecretReference(TENANT, "key2"), "value2", null, null);

            // Verify pre-rotation
            assertEquals("value1", resolver.resolveValue("${eddivault:key1}"));
            assertEquals("value2", resolver.resolveValue("${eddivault:key2}"));

            // Clear cache and rotate DEK
            resolver.invalidateAll();
            int count = provider.rotateDek(TENANT);
            assertEquals(2, count);

            // Clear cache (rotation changes ciphertexts)
            resolver.invalidateAll();

            // Verify post-rotation — secrets should still resolve to same values
            assertEquals("value1", resolver.resolveValue("${eddivault:key1}"));
            assertEquals("value2", resolver.resolveValue("${eddivault:key2}"));
        }

        @Test
        @DisplayName("rotate DEK updates lastRotatedAt on all secrets")
        void rotateDekUpdatesTimestamps() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, KEY_NAME), SECRET_VALUE, null, null);

            Instant before = Instant.now();
            provider.rotateDek(TENANT);

            EncryptedSecret stored = secretStore.get(TENANT + "/" + KEY_NAME);
            assertNotNull(stored.getLastRotatedAt());
            assertTrue(stored.getLastRotatedAt().isAfter(before) || stored.getLastRotatedAt().equals(before));
        }

        @Test
        @DisplayName("rotate DEK for empty tenant returns 0")
        void rotateDekEmptyTenant() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            // Store a secret to create the DEK, then remove it
            provider.store(new SecretReference(TENANT, "setup"), "val", null, null);
            secretStore.clear();
            when(persistence.listSecretsByTenant(TENANT)).thenReturn(List.of());

            int count = provider.rotateDek(TENANT);
            assertEquals(0, count);
        }

        @Test
        @DisplayName("rotate DEK fails if no DEK exists")
        void rotateDekNoDek() {
            setupDekMocking();
            assertThrows(ISecretProvider.SecretProviderException.class, () -> provider.rotateDek(TENANT));
        }
    }

    // ─── KEK Rotation ───

    @Nested
    @DisplayName("KEK Rotation")
    class KekRotation {

        @Test
        @DisplayName("rotate KEK and verify secrets still resolve with new key")
        void rotateKekAndResolve() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, KEY_NAME), SECRET_VALUE, null, null);

            // Rotate KEK
            String newMasterKey = "brand-new-master-key-2024";
            int count = provider.rotateKek(MASTER_KEY, newMasterKey);
            assertEquals(1, count);

            // Clear cache
            resolver.invalidateAll();

            // The in-memory provider now uses the new KEK — secrets should still resolve
            assertEquals(SECRET_VALUE, resolver.resolveValue("${eddivault:" + KEY_NAME + "}"));
        }

        @Test
        @DisplayName("rotate KEK with wrong old key fails")
        void rotateKekWrongOldKey() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, KEY_NAME), SECRET_VALUE, null, null);

            assertThrows(ISecretProvider.SecretProviderException.class, () -> provider.rotateKek("wrong-old-key", "new-key"));
        }
    }

    // ─── Metrics ───

    @Nested
    @DisplayName("Metrics Emission")
    class MetricsEmission {

        @Test
        @DisplayName("store increments store counter")
        void storeMetric() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, KEY_NAME), SECRET_VALUE, null, null);

            assertEquals(1.0, meterRegistry.counter("eddi.vault.store.count").count());
        }

        @Test
        @DisplayName("resolve increments resolve counter and timer")
        void resolveMetric() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, KEY_NAME), SECRET_VALUE, null, null);
            provider.resolve(new SecretReference(TENANT, KEY_NAME));

            assertEquals(1.0, meterRegistry.counter("eddi.vault.resolve.count").count());
            assertTrue(meterRegistry.timer("eddi.vault.resolve.duration").count() > 0);
        }

        @Test
        @DisplayName("failed resolve increments error counter")
        void resolveErrorMetric() {
            setupDekMocking();
            setupSecretMocking();

            assertThrows(ISecretProvider.SecretNotFoundException.class, () -> provider.resolve(new SecretReference(TENANT, "nonexistent")));

            assertEquals(1.0, meterRegistry.counter("eddi.vault.errors.count").count());
        }

        @Test
        @DisplayName("SecretResolver tracks cache hits and misses")
        void cacheMetrics() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, KEY_NAME), SECRET_VALUE, null, null);

            // First resolve — cache miss
            resolver.resolveValue("${eddivault:" + KEY_NAME + "}");
            assertEquals(1.0, meterRegistry.counter("eddi.vault.cache.misses").count());

            // Second resolve — cache hit
            resolver.resolveValue("${eddivault:" + KEY_NAME + "}");
            assertEquals(1.0, meterRegistry.counter("eddi.vault.cache.hits").count());
        }

        @Test
        @DisplayName("DEK rotation increments rotate counter")
        void rotateMetric() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, KEY_NAME), SECRET_VALUE, null, null);
            provider.rotateDek(TENANT);

            assertEquals(1.0, meterRegistry.counter("eddi.vault.rotate.count").count());
        }
    }

    // ─── Exception Propagation ───

    @Nested
    @DisplayName("Exception Propagation")
    class ExceptionPropagation {

        @Test
        @DisplayName("persistence failure on store throws SecretProviderException")
        void persistenceFailureOnStore() {
            setupDekMocking();
            doThrow(new ai.labs.eddi.secrets.persistence.PersistenceException("DB down")).when(persistence).upsertSecret(any());
            when(persistence.findSecret(anyString(), anyString())).thenReturn(Optional.empty());

            assertThrows(ISecretProvider.SecretProviderException.class,
                    () -> provider.store(new SecretReference(TENANT, KEY_NAME), SECRET_VALUE, null, null));
        }

        @Test
        @DisplayName("persistence failure on resolve throws SecretProviderException")
        void persistenceFailureOnResolve() {
            when(persistence.findSecret(anyString(), anyString())).thenThrow(new ai.labs.eddi.secrets.persistence.PersistenceException("DB down"));

            assertThrows(ISecretProvider.SecretProviderException.class, () -> provider.resolve(new SecretReference(TENANT, KEY_NAME)));
        }

        @Test
        @DisplayName("vault unavailable throws SecretProviderException")
        void vaultUnavailable() {
            var sm = new VaultSaltManager(persistence);
            sm.initialize();
            var unavailableProvider = new VaultSecretProvider(Optional.empty(), persistence, sm, meterRegistry);
            unavailableProvider.initMetrics();

            assertThrows(ISecretProvider.SecretProviderException.class, () -> unavailableProvider.resolve(new SecretReference(TENANT, KEY_NAME)));
        }
    }

    // ─── Cache Invalidation ───

    @Nested
    @DisplayName("Cache Invalidation")
    class CacheInvalidation {

        @Test
        @DisplayName("cache is invalidated after secret update via store")
        void cacheInvalidatedOnUpdate() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, KEY_NAME), "old-value", null, null);

            String v1 = resolver.resolveValue("${eddivault:" + KEY_NAME + "}");
            assertEquals("old-value", v1);

            provider.store(new SecretReference(TENANT, KEY_NAME), "new-value", null, null);
            resolver.invalidateCache(new SecretReference(TENANT, KEY_NAME));

            String v2 = resolver.resolveValue("${eddivault:" + KEY_NAME + "}");
            assertEquals("new-value", v2);
        }

        @Test
        @DisplayName("invalidateAll clears entire cache")
        void invalidateAllClearsCache() throws Exception {
            setupDekMocking();
            setupSecretMocking();

            provider.store(new SecretReference(TENANT, "k1"), "v1", null, null);
            provider.store(new SecretReference(TENANT, "k2"), "v2", null, null);

            resolver.resolveValue("${eddivault:k1}");
            resolver.resolveValue("${eddivault:k2}");

            resolver.invalidateAll();
            provider.store(new SecretReference(TENANT, "k1"), "v1-updated", null, null);
            provider.store(new SecretReference(TENANT, "k2"), "v2-updated", null, null);

            assertEquals("v1-updated", resolver.resolveValue("${eddivault:k1}"));
            assertEquals("v2-updated", resolver.resolveValue("${eddivault:k2}"));
        }
    }
}
