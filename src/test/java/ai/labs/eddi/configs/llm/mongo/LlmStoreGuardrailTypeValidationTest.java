/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.mongo;

import ai.labs.eddi.configs.LenientConfigWrite;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.llm.guardrails.GuardrailService;
import ai.labs.eddi.modules.llm.guardrails.impl.PiiRedactionGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.GuardrailConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An unknown input or output guardrail type is refused when the LLM
 * configuration is saved: a typo must not deploy and disable the protection.
 */
@DisplayName("LlmStore — guardrail type validation")
class LlmStoreGuardrailTypeValidationTest {

    private LlmStore store;
    private IResourceStorage<LlmConfiguration> storage;
    private IResourceStorageFactory factory;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        storage = mock(IResourceStorage.class);
        factory = mock(IResourceStorageFactory.class);
        when(factory.<LlmConfiguration>create(anyString(), any(), any(Class.class))).thenReturn(storage);
        var service = new GuardrailService(List.of(new PiiRedactionGuardrail()), new SimpleMeterRegistry());
        store = new LlmStore(factory, mock(IDocumentBuilder.class), service);
    }

    private static GuardrailConfig guardrail(String type) {
        var config = new GuardrailConfig();
        config.setType(type);
        return config;
    }

    private static LlmConfiguration withInput(String type) {
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setInputGuardrails(List.of(guardrail("pii-redaction"), guardrail(type)));
        return new LlmConfiguration(List.of(task));
    }

    private static LlmConfiguration withOutput(String type) {
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setOutputGuardrails(List.of(guardrail(type)));
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("create rejects a mistyped input guardrail and names list, index, type and known types")
    void createRejectsUnknownInputType() {
        var error = assertThrows(IllegalArgumentException.class, () -> store.create(withInput("pii-redacton")));
        assertTrue(error.getMessage().contains("langchain.task[0].inputGuardrails[1]"), error.getMessage());
        assertTrue(error.getMessage().contains("'pii-redacton'"), error.getMessage());
        assertTrue(error.getMessage().contains("pii-redaction"), error.getMessage());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("update rejects an unknown output guardrail")
    void updateRejectsUnknownOutputType() {
        var error = assertThrows(IllegalArgumentException.class, () -> store.update("aaaaaaaaaaaaaaaaaaaaaaaa", 1, withOutput("nope")));
        assertTrue(error.getMessage().contains("langchain.task[0].outputGuardrails[0]"), error.getMessage());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("a missing type is unknown too")
    void missingTypeRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.create(withOutput(null)));
    }

    @Test
    @DisplayName("known types pass the check")
    void knownTypesPass() {
        var task = new LlmConfiguration.Task();
        task.setInputGuardrails(List.of(guardrail("pii-redaction")));
        assertDoesNotThrow(() -> store.create(new LlmConfiguration(List.of(task))));
    }

    @Test
    @DisplayName("an import, sync, upgrade or copy (LenientConfigWrite) is accepted with a warning; an interactive save is refused")
    void lenientWriteAcceptsWhatAnInteractiveSaveRefuses() throws Exception {
        var bad = withInput("pii-redacton");
        assertThrows(IllegalArgumentException.class, () -> store.create(bad));
        assertThrows(IllegalArgumentException.class, () -> store.update("aaaaaaaaaaaaaaaaaaaaaaaa", 1, bad));

        assertDoesNotThrow(() -> LenientConfigWrite.call(() -> store.create(bad)));
        // an update gets past the validation too: the mock storage then has nothing to
        // update
        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> LenientConfigWrite.call(() -> store.update("aaaaaaaaaaaaaaaaaaaaaaaa", 1, bad)));
        // the marker ends with the call
        assertThrows(IllegalArgumentException.class, () -> store.create(bad));
    }

    @Test
    @DisplayName("without a guardrail service the check is skipped")
    void nullServiceSkips() {
        var plain = new LlmStore(factory, mock(IDocumentBuilder.class));
        assertDoesNotThrow(() -> plain.create(withInput("whatever")));
    }
}
