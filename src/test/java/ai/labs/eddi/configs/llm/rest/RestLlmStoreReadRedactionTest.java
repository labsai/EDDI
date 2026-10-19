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
    @DisplayName("a brace inside a literal does not make it a template: hunter{two is redacted")
    void braceInsideALiteralIsStillRedacted() throws Exception {
        var task = new LlmConfiguration.Task();
        task.setType("anthropic");
        task.setParameters(new LinkedHashMap<>(Map.of("apiKey", "hunter{two", "password", "a}b{c")));

        Map<String, String> parameters = read(new LlmConfiguration(List.of(task)), false).tasks().get(0).getParameters();

        assertEquals(RestLlmStore.REDACTED, parameters.get("apiKey"));
        assertEquals(RestLlmStore.REDACTED, parameters.get("password"));
    }

    @Test
    @DisplayName("M1 (2026-10-17): A2A agent keys and cascade step and judge parameters are redacted too")
    void a2aAndCascadeFieldsAreRedacted() throws Exception {
        var a2a = new LlmConfiguration.A2AAgentConfig();
        a2a.setUrl("https://peer.example.com/a2a/agents/x");
        a2a.setName("peer");
        a2a.setApiKey("a2a-literal-key");
        var referencedA2a = new LlmConfiguration.A2AAgentConfig();
        referencedA2a.setUrl("https://other.example.com/a2a");
        referencedA2a.setApiKey("${vault:a2a-key}");

        var step = new LlmConfiguration.CascadeStep();
        step.setType("openai");
        step.setParameters(new LinkedHashMap<>(Map.of("apiKey", "sk-step-literal", "modelName", "gpt-5")));
        var judge = new LlmConfiguration.JudgeModelConfig();
        judge.setType("anthropic");
        judge.setParameters(new LinkedHashMap<>(Map.of("apiKey", "sk-ant-judge-literal")));
        var cascade = new LlmConfiguration.ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setSteps(List.of(step));
        cascade.setJudgeModel(judge);

        var task = new LlmConfiguration.Task();
        task.setType("openai");
        task.setParameters(new LinkedHashMap<>(Map.of("maxTokens", "4096", "thinkingBudgetTokens", "2048")));
        task.setA2aAgents(List.of(a2a, referencedA2a));
        task.setModelCascade(cascade);
        task.setToolCacheScopes(new LinkedHashMap<>(Map.of("get_token", "conversation")));

        LlmConfiguration seen = read(new LlmConfiguration(List.of(task)), false);
        LlmConfiguration.Task seenTask = seen.tasks().get(0);

        assertEquals(RestLlmStore.REDACTED, seenTask.getA2aAgents().get(0).getApiKey());
        assertEquals("https://peer.example.com/a2a/agents/x", seenTask.getA2aAgents().get(0).getUrl());
        assertEquals("${vault:a2a-key}", seenTask.getA2aAgents().get(1).getApiKey());
        assertEquals(RestLlmStore.REDACTED, seenTask.getModelCascade().getSteps().get(0).getParameters().get("apiKey"));
        assertEquals("gpt-5", seenTask.getModelCascade().getSteps().get(0).getParameters().get("modelName"));
        assertEquals(RestLlmStore.REDACTED, seenTask.getModelCascade().getJudgeModel().getParameters().get("apiKey"));
        assertEquals("4096", seenTask.getParameters().get("maxTokens"), "a count of tokens is not a token");
        assertEquals("2048", seenTask.getParameters().get("thinkingBudgetTokens"));
        assertEquals("conversation", seenTask.getToolCacheScopes().get("get_token"), "tool names are not credential fields");
        assertEquals("a2a-literal-key", task.getA2aAgents().get(0).getApiKey(), "the stored object is never touched");
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
