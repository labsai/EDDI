/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.modules.llm.guardrails.GuardrailService;
import ai.labs.eddi.modules.llm.impl.CascadeCredentialPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.ISecretProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The setup wizard saves its LLM config through the loopback REST client — a
 * separate HTTP request, where an import's or upgrade's lenient mode cannot
 * reach — so every LLM config it generates must pass the save-time checks this
 * release made strict: no unknown input/output guardrail type, and no
 * cross-provider cascade step that would need the task's credentials. The
 * wizard writes neither guardrails nor a cascade today; this fails the day it
 * starts writing one that the store would refuse.
 */
class SetupWizardLlmConfigsPassLlmChecksTest {

    private final AgentSetupService wizard = new AgentSetupService(mock(IRestInterfaceFactory.class), mock(IRestAgentAdministration.class),
            mock(ISecretProvider.class), "http://localhost:11434");

    /** No guardrail is registered: any guardrail type at all would be unknown. */
    private final GuardrailService noGuardrails = new GuardrailService(List.of(), new SimpleMeterRegistry());

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"anthropic", "openai", "gemini", "mistral", "azure-openai", "bedrock", "oracle-genai", "ollama", "jlama",
            "huggingface", "openai-compatible", "xai", "deepseek", "openrouter", "groq"})
    void generatedLlmConfigPassesTheStrictChecks(String provider) {
        for (boolean tooling : new boolean[]{true, false}) {
            LlmConfiguration config = wizard.createLlmConfig(provider, "some-model", "${vault:llm-key}", "You are a test assistant.", tooling, null,
                    "https://llm.example.com/v1", null, false, false, List.of());

            List<String> problems = new ArrayList<>(CascadeCredentialPolicy.problems(config));
            for (LlmConfiguration.Task task : config.tasks()) {
                problems.addAll(noGuardrails.unknownTypeProblems(GuardrailService.LIST_INPUT, task.getInputGuardrails()));
                problems.addAll(noGuardrails.unknownTypeProblems("outputGuardrails", task.getOutputGuardrails()));
            }
            assertTrue(problems.isEmpty(),
                    provider + " (tooling " + tooling + "): the wizard generates an LLM config its own save refuses: " + problems);
        }
    }
}
