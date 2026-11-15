/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.mongo;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.ToolCallGuardrailConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ai.labs.eddi.modules.llm.tools.guardrail.ToolCallGuardrailConfigValidatorTest.entry;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A bad {@code toolCallGuardrails} is rejected when the LLM configuration is
 * saved — on create and on update — and never reaches the storage.
 */
@DisplayName("LlmStore — toolCallGuardrails validation")
class LlmStoreToolCallGuardrailValidationTest {

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

    private static LlmConfiguration with(ToolCallGuardrailConfig entry) {
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setToolCallGuardrails(List.of(entry));
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("create rejects an unknown action")
    void createRejectsUnknownAction() {
        var bad = with(entry("regex-filter", "deny", List.of("mcp:add_issue_comment"), null));
        var error = assertThrows(IllegalArgumentException.class, () -> store.create(bad));
        assertTrue(error.getMessage().startsWith("langchain.task[0].toolCallGuardrails[0].action"), error.getMessage());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("update rejects an invalid field pointer")
    void updateRejectsBadPointer() {
        var bad = with(entry("regex-filter", "block", List.of("mcp:add_issue_comment"), List.of("body")));
        var error = assertThrows(IllegalArgumentException.class, () -> store.update("aaaaaaaaaaaaaaaaaaaaaaaa", 1, bad));
        assertTrue(error.getMessage().startsWith("langchain.task[0].toolCallGuardrails[0].fields[0]"), error.getMessage());
        verifyNoInteractions(storage);
    }
}
