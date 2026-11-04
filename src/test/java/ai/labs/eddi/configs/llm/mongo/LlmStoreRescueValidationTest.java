/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.mongo;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code onInvalidJson: "wrap"} without a usable {@code wrapField}, and bad
 * {@code fieldAliases}, are rejected when the LLM configuration is saved
 * (create and update) and never reach the storage.
 */
@DisplayName("LlmStore — wrap / fieldAliases validation")
class LlmStoreRescueValidationTest {

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

    private static LlmConfiguration config(String onInvalidJson, String wrapField, Map<String, String> aliases) {
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        var v = new LlmConfiguration.ResponseValidation();
        v.setEnabled(true);
        v.setOnInvalidJson(onInvalidJson);
        v.setWrapField(wrapField);
        task.setResponseValidation(v);
        task.setFieldAliases(aliases);
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("create rejects wrap without wrapField with a message that says what to set")
    void createRejectsWrapWithoutField() {
        var error = assertThrows(IllegalArgumentException.class, () -> store.create(config("wrap", null, null)));

        assertTrue(error.getMessage().startsWith("langchain.task[0].responseValidation.onInvalidJson is \"wrap\" but wrapField is not set"),
                error.getMessage());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("update rejects a wrapField that is not a plain top-level name")
    void updateRejectsDottedWrapField() {
        var error = assertThrows(IllegalArgumentException.class, () -> store.update("id", 1, config("wrap", "answer.text", null)));

        assertTrue(error.getMessage().contains("wrapField \"answer.text\""), error.getMessage());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("create rejects an alias that maps to a dotted name")
    void createRejectsBadAlias() {
        var error = assertThrows(IllegalArgumentException.class, () -> store.create(config("ignore", null, Map.of("a", "b.c"))));

        assertTrue(error.getMessage().startsWith("langchain.task[0].fieldAliases"), error.getMessage());
        verifyNoInteractions(storage);
    }
}
