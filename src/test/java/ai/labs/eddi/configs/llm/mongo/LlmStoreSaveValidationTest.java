/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.mongo;

import ai.labs.eddi.configs.validation.ConfigValidationPolicy;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An LLM config that can never work is refused when it is saved by hand, and
 * only warned about when an import or upgrade writes it (L11).
 */
@DisplayName("LlmStore — save-time structure validation")
class LlmStoreSaveValidationTest {

    private LlmStore store;
    private IResourceStorage<LlmConfiguration> storage;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        storage = mock(IResourceStorage.class);
        IResourceStorageFactory factory = mock(IResourceStorageFactory.class);
        when(factory.<LlmConfiguration>create(anyString(), any(), any(Class.class))).thenReturn(storage);
        store = new LlmStore(factory, mock(IDocumentBuilder.class));
    }

    private static LlmConfiguration withProvider(String type) {
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setType(type);
        task.setParameters(Map.of("modelName", "gpt-4o"));
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("create and update refuse an unknown provider, storage untouched")
    void unknownProviderIsRefused() {
        var onCreate = assertThrows(IllegalArgumentException.class, () -> store.create(withProvider("nonexistent-provider")));
        assertTrue(onCreate.getMessage().contains("tasks[0].type 'nonexistent-provider'"), onCreate.getMessage());
        assertThrows(IllegalArgumentException.class, () -> store.update("aaaaaaaaaaaaaaaaaaaaaaaa", 1, withProvider(null)));
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("inside an import scope the same config is written and the finding is a warning")
    void importOnlyWarns() throws Exception {
        @SuppressWarnings("unchecked")
        IResourceStorage.IResource<LlmConfiguration> resource = mock(IResourceStorage.IResource.class);
        when(resource.getId()).thenReturn("id1");
        when(resource.getVersion()).thenReturn(1);
        when(storage.newResource(any())).thenReturn(resource);
        when(storage.getCurrentVersion("id1")).thenReturn(1);

        try (var scope = ConfigValidationPolicy.lenient()) {
            store.create(withProvider("nonexistent-provider"));
            assertEquals(1, scope.warnings().size());
            assertTrue(scope.warnings().getFirst().contains("nonexistent-provider"), scope.warnings().getFirst());
        }
        verify(storage).store(resource);
    }
}
