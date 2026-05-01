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
        String result = resolver.resolveValue("${eddivar:default-model}");
        assertEquals("gpt-4.1", result);
    }

    @Test
    void resolveValueMultiple() {
        when(store.getAll()).thenReturn(Map.of("model", "gpt-4.1", "temp", "0.7"));
        String result = resolver.resolveValue("model=${eddivar:model}&temp=${eddivar:temp}");
        assertEquals("model=gpt-4.1&temp=0.7", result);
    }

    @Test
    void resolveValueMissing() {
        when(store.getAll()).thenReturn(Map.of());
        String result = resolver.resolveValue("${eddivar:missing}");
        assertEquals("${eddivar:missing}", result, "Missing variables should pass through unchanged");
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
        input.put("type", "${eddivar:model}");
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
        resolver.resolveValue("${eddivar:k}");
        resolver.resolveValue("${eddivar:k}");
        // Cache should mean only one call to store
        verify(store, times(1)).getAll();
    }

    @Test
    void invalidateCacheForcesReload() {
        when(store.getAll()).thenReturn(Map.of("k", "v1"));
        resolver.resolveValue("${eddivar:k}");

        when(store.getAll()).thenReturn(Map.of("k", "v2"));
        resolver.invalidateCache();
        String result = resolver.resolveValue("${eddivar:k}");
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
        assertTrue(GlobalVariableResolver.containsReference("${eddivar:x}"));
        assertFalse(GlobalVariableResolver.containsReference("plain text"));
        assertFalse(GlobalVariableResolver.containsReference(null));
        assertFalse(GlobalVariableResolver.containsReference("${eddivault:x}"));
    }

    @Test
    void patternMatchesVariousKeyFormats() {
        when(store.getAll()).thenReturn(Map.of(
                "model.name", "gpt-4",
                "api-key", "sk-123",
                "version_2", "v2"));

        assertEquals("gpt-4", resolver.resolveValue("${eddivar:model.name}"));
        assertEquals("sk-123", resolver.resolveValue("${eddivar:api-key}"));
        assertEquals("v2", resolver.resolveValue("${eddivar:version_2}"));
    }

    @Test
    void resolveValueMixedWithText() {
        when(store.getAll()).thenReturn(Map.of("host", "api.openai.com"));
        String result = resolver.resolveValue("https://${eddivar:host}/v1/chat");
        assertEquals("https://api.openai.com/v1/chat", result);
    }
}
