/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables;

import ai.labs.eddi.configs.variables.model.GlobalVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GlobalVariableResolver} — regex resolution, caching,
 * invalidation listeners, template data injection.
 */
class GlobalVariableResolverTest {

    private IGlobalVariableStore store;
    private GlobalVariableResolver resolver;

    @BeforeEach
    void setUp() {
        store = mock(IGlobalVariableStore.class);
        resolver = new GlobalVariableResolver(store, 2);
        // Manually call init since @PostConstruct doesn't fire in unit tests
        resolver.init();
    }

    @Test
    void resolveValueSimple() {
        when(store.getAll()).thenReturn(Map.of("default-model", "gpt-4.1"));
        String result = resolver.resolveValue("${vars:default-model}");
        assertEquals("gpt-4.1", result);
    }

    @Test
    void resolveValueMultiple() {
        when(store.getAll()).thenReturn(Map.of("model", "gpt-4.1", "temp", "0.7"));
        String result = resolver.resolveValue("model=${vars:model}&temp=${vars:temp}");
        assertEquals("model=gpt-4.1&temp=0.7", result);
    }

    @Test
    void resolveValueMissing() {
        when(store.getAll()).thenReturn(Map.of());
        String result = resolver.resolveValue("${vars:missing}");
        assertEquals("${vars:missing}", result, "Missing variables should pass through unchanged");
    }

    @Test
    void resolveValueNullPassthrough() {
        assertNull(resolver.resolveValue(null));
    }

    @Test
    void resolveValueNoReferencePassthrough() {
        String plain = "just a plain string";
        assertSame(plain, resolver.resolveValue(plain));
        verifyNoInteractions(store); // Should not touch the store
    }

    @Test
    void resolveAllMap() {
        when(store.getAll()).thenReturn(Map.of("model", "gpt-4.1"));
        Map<String, String> input = new HashMap<>();
        input.put("type", "${vars:model}");
        input.put("plain", "no-change");

        Map<String, String> result = resolver.resolveAll(input);
        assertEquals("gpt-4.1", result.get("type"));
        assertEquals("no-change", result.get("plain"));
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

    @Test
    void getTemplateData() {
        when(store.getAll()).thenReturn(Map.of("k1", "v1", "k2", "v2"));
        Map<String, Object> data = resolver.getTemplateData();
        assertEquals("v1", data.get("k1"));
        assertEquals("v2", data.get("k2"));
    }

    @Test
    void cachingAvoidsDuplicateLoads() {
        when(store.getAll()).thenReturn(Map.of("k", "v"));
        resolver.resolveValue("${vars:k}");
        resolver.resolveValue("${vars:k}");
        // Cache should mean only one call to store
        verify(store, times(1)).getAll();
    }

    @Test
    void invalidateCacheForcesReload() {
        when(store.getAll()).thenReturn(Map.of("k", "v1"));
        resolver.resolveValue("${vars:k}");

        when(store.getAll()).thenReturn(Map.of("k", "v2"));
        resolver.invalidateCache();
        String result = resolver.resolveValue("${vars:k}");
        assertEquals("v2", result);
        verify(store, times(2)).getAll();
    }

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
        // Should not throw
        assertDoesNotThrow(() -> resolver.invalidateCache());
    }

    @Test
    void containsReference() {
        assertTrue(GlobalVariableResolver.containsReference("${vars:x}"));
        assertFalse(GlobalVariableResolver.containsReference("plain text"));
        assertFalse(GlobalVariableResolver.containsReference(null));
        assertFalse(GlobalVariableResolver.containsReference("${vault:x}"));
    }

    @Test
    void patternMatchesVariousKeyFormats() {
        when(store.getAll()).thenReturn(Map.of(
                "model.name", "gpt-4",
                "api-key", "sk-123",
                "version_2", "v2"));

        assertEquals("gpt-4", resolver.resolveValue("${vars:model.name}"));
        assertEquals("sk-123", resolver.resolveValue("${vars:api-key}"));
        assertEquals("v2", resolver.resolveValue("${vars:version_2}"));
    }

    @Test
    void resolveValueMixedWithText() {
        when(store.getAll()).thenReturn(Map.of("host", "api.openai.com"));
        String result = resolver.resolveValue("https://${vars:host}/v1/chat");
        assertEquals("https://api.openai.com/v1/chat", result);
    }
}
