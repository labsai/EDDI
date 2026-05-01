/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables;

import ai.labs.eddi.configs.variables.model.GlobalVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GlobalVariableResolver} — regex resolution, tenant
 * scoping, caching, invalidation listeners, template data injection.
 */
class GlobalVariableResolverTest {

    private static final String DEFAULT = GlobalVariable.DEFAULT_TENANT;

    private IGlobalVariableStore store;
    private GlobalVariableResolver resolver;

    @BeforeEach
    void setUp() {
        store = mock(IGlobalVariableStore.class);
        resolver = new GlobalVariableResolver(store, 2);
        // Manually call init since @PostConstruct doesn't fire in unit tests
        resolver.init();
    }

    // ====================== Short Form (default tenant) ======================

    @Nested
    @DisplayName("Short form: ${vars:key}")
    class ShortForm {

        @Test
        void resolveSimple() {
            when(store.getAll(DEFAULT)).thenReturn(Map.of("default-model", "gpt-4.1"));
            assertEquals("gpt-4.1", resolver.resolveValue("${vars:default-model}"));
        }

        @Test
        void resolveMultiple() {
            when(store.getAll(DEFAULT)).thenReturn(Map.of("model", "gpt-4.1", "temp", "0.7"));
            assertEquals("model=gpt-4.1&temp=0.7",
                    resolver.resolveValue("model=${vars:model}&temp=${vars:temp}"));
        }

        @Test
        void resolveMissing() {
            when(store.getAll(DEFAULT)).thenReturn(Map.of());
            assertEquals("${vars:missing}", resolver.resolveValue("${vars:missing}"),
                    "Missing variables should pass through unchanged");
        }

        @Test
        void resolveNullPassthrough() {
            assertNull(resolver.resolveValue(null));
        }

        @Test
        void resolveNoReferencePassthrough() {
            String plain = "just a plain string";
            assertSame(plain, resolver.resolveValue(plain));
            verifyNoInteractions(store);
        }

        @Test
        void resolveMixedWithText() {
            when(store.getAll(DEFAULT)).thenReturn(Map.of("host", "api.openai.com"));
            assertEquals("https://api.openai.com/v1/chat",
                    resolver.resolveValue("https://${vars:host}/v1/chat"));
        }

        @Test
        void resolveVariousKeyFormats() {
            when(store.getAll(DEFAULT)).thenReturn(Map.of(
                    "model.name", "gpt-4",
                    "api-key", "sk-123",
                    "version_2", "v2"));

            assertEquals("gpt-4", resolver.resolveValue("${vars:model.name}"));
            assertEquals("sk-123", resolver.resolveValue("${vars:api-key}"));
            assertEquals("v2", resolver.resolveValue("${vars:version_2}"));
        }
    }

    // ====================== Full Form (explicit tenant) ======================

    @Nested
    @DisplayName("Full form: ${vars:tenantId/key}")
    class FullForm {

        @Test
        void resolveExplicitTenant() {
            when(store.getAll("tenant-a")).thenReturn(Map.of("model", "gpt-4.1"));
            assertEquals("gpt-4.1", resolver.resolveValue("${vars:tenant-a/model}"));
        }

        @Test
        void resolveExplicitTenantDoesNotUseContextTenant() {
            // Even when context is "tenant-b", explicit tenant wins
            when(store.getAll("tenant-a")).thenReturn(Map.of("key", "val-a"));
            assertEquals("val-a", resolver.resolveValue("${vars:tenant-a/key}", "tenant-b"));
            verify(store, never()).getAll("tenant-b");
        }

        @Test
        void resolveShortFormUsesContextTenant() {
            when(store.getAll("tenant-b")).thenReturn(Map.of("key", "val-b"));
            assertEquals("val-b", resolver.resolveValue("${vars:key}", "tenant-b"));
        }

        @Test
        void resolveMixedShortAndFullForm() {
            when(store.getAll(DEFAULT)).thenReturn(Map.of("global-key", "global-val"));
            when(store.getAll("t1")).thenReturn(Map.of("t1-key", "t1-val"));
            assertEquals("global-val,t1-val",
                    resolver.resolveValue("${vars:global-key},${vars:t1/t1-key}"));
        }

        @Test
        void resolveNullTenantIdDefaultsToDefault() {
            when(store.getAll(DEFAULT)).thenReturn(Map.of("key", "val"));
            assertEquals("val", resolver.resolveValue("${vars:key}", null),
                    "null tenantId should fall back to 'default'");
        }
    }

    // ====================== resolveAll ======================

    @Test
    void resolveAllMap() {
        when(store.getAll(DEFAULT)).thenReturn(Map.of("model", "gpt-4.1"));
        Map<String, String> input = new HashMap<>();
        input.put("type", "${vars:model}");
        input.put("plain", "no-change");

        Map<String, String> result = resolver.resolveAll(input);
        assertEquals("gpt-4.1", result.get("type"));
        assertEquals("no-change", result.get("plain"));
    }

    @Test
    void resolveAllWithTenant() {
        when(store.getAll("t1")).thenReturn(Map.of("model", "claude"));
        Map<String, String> input = new HashMap<>();
        input.put("type", "${vars:model}");

        Map<String, String> result = resolver.resolveAll(input, "t1");
        assertEquals("claude", result.get("type"));
    }

    @Test
    void resolveAllNullMap() {
        assertNull(resolver.resolveAll(null));
    }

    @Test
    void resolveAllEmptyMap() {
        Map<String, String> empty = Map.of();
        assertSame(empty, resolver.resolveAll(empty));
    }

    // ====================== Template Data ======================

    @Test
    void getTemplateData_defaultTenant() {
        when(store.getAll(DEFAULT)).thenReturn(Map.of("k1", "v1", "k2", "v2"));
        Map<String, Object> data = resolver.getTemplateData();
        assertEquals("v1", data.get("k1"));
        assertEquals("v2", data.get("k2"));
    }

    @Test
    void getTemplateData_explicitTenant() {
        when(store.getAll("t1")).thenReturn(Map.of("k1", "t1-v1"));
        Map<String, Object> data = resolver.getTemplateData("t1");
        assertEquals("t1-v1", data.get("k1"));
    }

    // ====================== Caching ======================

    @Test
    void cachingAvoidsDuplicateLoads() {
        when(store.getAll(DEFAULT)).thenReturn(Map.of("k", "v"));
        resolver.resolveValue("${vars:k}");
        resolver.resolveValue("${vars:k}");
        verify(store, times(1)).getAll(DEFAULT);
    }

    @Test
    void cachingIsPerTenant() {
        when(store.getAll(DEFAULT)).thenReturn(Map.of("k", "v-default"));
        when(store.getAll("t1")).thenReturn(Map.of("k", "v-t1"));

        assertEquals("v-default", resolver.resolveValue("${vars:k}"));
        assertEquals("v-t1", resolver.resolveValue("${vars:t1/k}"));
        verify(store, times(1)).getAll(DEFAULT);
        verify(store, times(1)).getAll("t1");
    }

    @Test
    void invalidateCacheForcesReload() {
        when(store.getAll(DEFAULT)).thenReturn(Map.of("k", "v1"));
        resolver.resolveValue("${vars:k}");

        when(store.getAll(DEFAULT)).thenReturn(Map.of("k", "v2"));
        resolver.invalidateCache();
        assertEquals("v2", resolver.resolveValue("${vars:k}"));
        verify(store, times(2)).getAll(DEFAULT);
    }

    // ====================== Listeners ======================

    @Test
    void invalidationListenerFires() {
        Runnable listener = mock(Runnable.class);
        resolver.registerInvalidationListener(listener);
        resolver.invalidateCache();
        verify(listener, times(1)).run();
    }

    @Test
    void invalidationListenerExceptionDoesNotPropagate() {
        Runnable badListener = mock(Runnable.class);
        doThrow(new RuntimeException("boom")).when(badListener).run();
        resolver.registerInvalidationListener(badListener);
        assertDoesNotThrow(() -> resolver.invalidateCache());
    }

    // ====================== Static Helpers ======================

    @Test
    void containsReference() {
        assertTrue(GlobalVariableResolver.containsReference("${vars:x}"));
        assertTrue(GlobalVariableResolver.containsReference("${vars:t/x}"));
        assertFalse(GlobalVariableResolver.containsReference("plain text"));
        assertFalse(GlobalVariableResolver.containsReference(null));
        assertFalse(GlobalVariableResolver.containsReference("${vault:x}"));
    }
}
