/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L12 of the 2026-10-15 security review: an editor who PUT a plaintext
 * {@code apiKey} into an LLM configuration could read it back — and so could
 * every other editor. A non-administrator now reads the document with such
 * literals redacted, as {@code RestConnectionStore.readConnection} does.
 */
class RestLlmStoreReadRedactionTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private ILlmStore llmStore;
    private ResourceAccessGuard accessGuard;
    private RestLlmStore rest;

    @BeforeEach
    void setUp() {
        llmStore = mock(ILlmStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestLlmStore(llmStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), accessGuard);
    }

    private static LlmConfiguration stored() {
        var task = new LlmConfiguration.Task();
        task.setType("anthropic");
        task.setParameters(new LinkedHashMap<>(Map.of("apiKey", "sk-ant-api03-plaintext", "modelName", "claude-sonnet-5", "authToken",
                "${vault:hf-token}", "nonAzureApiKey", "{properties.key}")));
        return new LlmConfiguration(List.of(task));
    }

    private LlmConfiguration read(LlmConfiguration stored, boolean admin) throws Exception {
        when(llmStore.read(ID, 1)).thenReturn(stored);
        when(accessGuard.isAdmin()).thenReturn(admin);
        return rest.readLlm(ID, 1);
    }

    @Test
    @DisplayName("an administrator reads the document as stored, so the literal can be found and moved into the vault")
    void adminReadsRaw() throws Exception {
        LlmConfiguration stored = stored();
        assertSame(stored, read(stored, true));
    }

    @Test
    @DisplayName("an editor reads a copy with the plaintext credential redacted and everything else untouched")
    void editorReadsRedacted() throws Exception {
        LlmConfiguration stored = stored();

        LlmConfiguration seen = read(stored, false);

        Map<String, String> parameters = seen.tasks().get(0).getParameters();
        assertEquals(RestLlmStore.REDACTED, parameters.get("apiKey"));
        assertEquals("claude-sonnet-5", parameters.get("modelName"));
        assertEquals("${vault:hf-token}", parameters.get("authToken"), "a reference is not a plaintext credential");
        assertEquals("{properties.key}", parameters.get("nonAzureApiKey"), "a template is not a plaintext credential");
        assertEquals("sk-ant-api03-plaintext", stored.tasks().get(0).getParameters().get("apiKey"), "the stored object is never touched");
    }

    @Test
    @DisplayName("a document without a plaintext credential is returned as it is")
    void cleanDocumentUnchanged() throws Exception {
        var task = new LlmConfiguration.Task();
        task.setType("anthropic");
        task.setParameters(new LinkedHashMap<>(Map.of("apiKey", "${vault:anthropic-key}")));
        LlmConfiguration stored = new LlmConfiguration(List.of(task));

        assertSame(stored, read(stored, false));
    }
}
