/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.mongo;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A cascade step of another provider type that would have inherited the task's
 * secret is refused at save time.
 */
@DisplayName("LlmStore — cascade credential isolation")
class LlmStoreCascadeCredentialValidationTest {

    private LlmStore store;
    private IResourceStorage<LlmConfiguration> storage;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        storage = mock(IResourceStorage.class);
        IResourceStorageFactory factory = mock(IResourceStorageFactory.class);
        when(factory.<LlmConfiguration>create(anyString(), any(), any(Class.class))).thenReturn(storage);
        store = new LlmStore(factory, mock(IDocumentBuilder.class));
    }

    private static LlmConfiguration cascade(String stepType, Map<String, String> stepParameters) {
        var step = new CascadeStep();
        step.setType(stepType);
        step.setParameters(stepParameters);
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setSteps(List.of(step));
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setType("openai");
        task.setParameters(Map.of("apiKey", "${eddivault:openai}"));
        task.setModelCascade(cascade);
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("create and update refuse a step of another provider without its own apiKey")
    void refusesInheritance() {
        var bad = cascade("anthropic", null);
        var error = assertThrows(IllegalArgumentException.class, () -> store.create(bad));
        assertTrue(error.getMessage().contains("step 0") && error.getMessage().contains("anthropic"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> store.update("aaaaaaaaaaaaaaaaaaaaaaaa", 1, bad));
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("a step with its own apiKey, or of the same provider, saves")
    void ownCredentialsOrSameProviderSave() {
        assertDoesNotThrow(() -> store.create(cascade("anthropic", Map.of("apiKey", "${eddivault:anthropic}"))));
        assertDoesNotThrow(() -> store.create(cascade("openai", null)));
    }
}
