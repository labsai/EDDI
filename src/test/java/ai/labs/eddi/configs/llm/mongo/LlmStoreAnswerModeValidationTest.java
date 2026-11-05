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

import java.util.HashMap;
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
 * An {@code answerMode: "tool"} that cannot work is refused when the LLM
 * configuration is saved, on create and on update.
 */
@DisplayName("LlmStore — answerMode validation")
class LlmStoreAnswerModeValidationTest {

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

    private static LlmConfiguration toolMode(String toolName) {
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setAnswerMode("tool");
        task.setAnswerToolName(toolName);
        Map<String, String> parameters = new HashMap<>();
        parameters.put("convertToObject", "true");
        parameters.put("responseSchema", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
        task.setParameters(parameters);
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("create refuses a reply tool named like a built-in tool")
    void createRefusesNameCollision() {
        var error = assertThrows(IllegalArgumentException.class, () -> store.create(toolMode("calculate")));
        assertTrue(error.getMessage().startsWith("langchain.task[0].answerToolName"), error.getMessage());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("update refuses tool mode without convertToObject")
    void updateRefusesWithoutConvertToObject() {
        var config = toolMode(null);
        config.tasks().get(0).getParameters().remove("convertToObject");
        var error = assertThrows(IllegalArgumentException.class, () -> store.update("aaaaaaaaaaaaaaaaaaaaaaaa", 1, config));
        assertTrue(error.getMessage().contains("convertToObject"), error.getMessage());
        verifyNoInteractions(storage);
    }
}
