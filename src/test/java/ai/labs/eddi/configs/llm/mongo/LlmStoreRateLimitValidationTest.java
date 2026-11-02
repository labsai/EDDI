/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.mongo;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link LlmStore} refuses a negative rate limit on create and update — before
 * anything reaches storage.
 */
class LlmStoreRateLimitValidationTest {

    @SuppressWarnings("unchecked")
    private final IResourceStorage<LlmConfiguration> storage = mock(IResourceStorage.class);

    private LlmStore store() {
        // The factory's only method is create(...): answer every call with the storage.
        var factory = mock(IResourceStorageFactory.class, invocation -> storage);
        return new LlmStore(factory, mock(IDocumentBuilder.class));
    }

    private static LlmConfiguration withLimits(Integer defaultLimit, Map<String, Integer> perTool) {
        var task = new LlmConfiguration.Task();
        task.setDefaultRateLimit(defaultLimit);
        task.setToolRateLimits(perTool);
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("a negative defaultRateLimit is refused on create and update, storage untouched")
    void negativeDefaultLimitIsRefused() {
        var store = store();

        var onCreate = assertThrows(IllegalArgumentException.class, () -> store.create(withLimits(-1, null)));
        assertTrue(onCreate.getMessage().contains("langchain.task[0].defaultRateLimit"), onCreate.getMessage());
        assertThrows(IllegalArgumentException.class, () -> store.update("id", 1, withLimits(null, Map.of("websearch", -5))));
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("a config stored before the check, with -1, still loads: validation runs on save only")
    void storedNegativeLimitStillLoads() throws Exception {
        @SuppressWarnings("unchecked")
        IResourceStorage.IResource<LlmConfiguration> stored = mock(IResourceStorage.IResource.class);
        when(stored.getData()).thenReturn(withLimits(-1, Map.of("websearch", -1)));
        when(storage.read("legacy", 1)).thenReturn(stored);

        LlmConfiguration loaded = store().read("legacy", 1);

        assertEquals(-1, loaded.tasks().get(0).getDefaultRateLimit());
    }
}
