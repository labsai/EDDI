/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.mongo;

import ai.labs.eddi.modules.llm.model.ToolQuotaConfig;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.config;
import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.rule;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A bad {@code toolQuotas} is rejected when the LLM configuration is saved — on
 * create and on update — and never reaches the storage.
 */
@DisplayName("LlmStore — toolQuotas validation")
class LlmStoreToolQuotaValidationTest {

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

    private static LlmConfiguration withQuotas(ToolQuotaConfig quotas) {
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setToolQuotas(quotas);
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("create rejects a pool whose tools disagree on the limit")
    void createRejectsInconsistentPool() throws Exception {
        var bad = withQuotas(config("a", rule(100, "P1D", "p"), "b", rule(50, "P1D", "p")));
        var error = assertThrows(IllegalArgumentException.class, () -> store.create(bad));
        assertTrue(error.getMessage().startsWith("langchain.task[0].toolQuotas.tools['b']"), error.getMessage());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("update rejects a window outside PT1M..P31D")
    void updateRejectsBadWindow() throws Exception {
        var bad = withQuotas(config("a", rule(5, "PT30S", null)));
        var error = assertThrows(IllegalArgumentException.class, () -> store.update("aaaaaaaaaaaaaaaaaaaaaaaa", 1, bad));
        assertTrue(error.getMessage().contains("shorter than the minimum PT1M"), error.getMessage());
    }
}
