/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.llm.impl.builder.OpenAILanguageModelBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.inject.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A configuration stored before the save-time check, carrying a vault reference
 * outside a credential parameter, fails at deploy — where the operator looks —
 * rather than on the first turn.
 */
@DisplayName("LlmTask.configure — vault reference placement at deploy")
class LlmTaskVaultPlacementDeployTest {

    private final VaultReferencePlacementGuard guard = new VaultReferencePlacementGuard(
            Map.<String, Provider<ILanguageModelBuilder>>of("openai", OpenAILanguageModelBuilder::new));

    private static LlmConfiguration config(Map<String, String> parameters) {
        var task = new LlmConfiguration.Task();
        task.setType("openai");
        task.setParameters(parameters);
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("a reference in baseUrl fails the deploy, naming the field")
    void misplacedReferenceFailsTheDeploy() {
        var refusal = assertThrows(WorkflowConfigurationException.class, () -> LlmTask.validateVaultReferencePlacement(guard,
                config(Map.of("apiKey", "${vault:k}", "baseUrl", "https://gw.example/${vault:tokenised-path}"))));

        assertTrue(refusal.getMessage().contains("'baseUrl'"), refusal.getMessage());
    }

    @Test
    @DisplayName("a model-backed guardrail is judged against its own provider's credential keys")
    void guardrailParametersAreChecked() {
        var classifier = new LlmConfiguration.GuardrailConfig();
        classifier.setType("classifier");
        classifier.setParameters(Map.of("provider", "openai", "apiKey", "${vault:k}", "modelName", "${vault:any-secret}"));
        var config = config(Map.of("apiKey", "${vault:k}"));
        config.tasks().getFirst().setOutputGuardrails(List.of(classifier));

        var refusal = assertThrows(WorkflowConfigurationException.class, () -> LlmTask.validateVaultReferencePlacement(guard, config));
        assertTrue(refusal.getMessage().contains("tasks[0].outputGuardrails[0].parameters"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("'modelName'"), refusal.getMessage());

        // The credential, and the type's own keys, are not refused; a type that builds
        // no
        // model is not judged at all.
        classifier.setParameters(Map.of("provider", "openai", "apiKey", "${vault:k}", "promptTemplate", "Rate ${vault:x} text"));
        var regex = new LlmConfiguration.GuardrailConfig();
        regex.setType("regex-filter");
        regex.setParameters(Map.of("pattern", "${vault:not-a-model}"));
        config.tasks().getFirst().setInputGuardrails(List.of(regex));
        assertDoesNotThrow(() -> LlmTask.validateVaultReferencePlacement(guard, config));
    }

    @Test
    @DisplayName("references in credential parameters, customQueryParams included, deploy")
    void credentialReferencesDeploy() {
        assertDoesNotThrow(() -> LlmTask.validateVaultReferencePlacement(guard,
                config(Map.of("apiKey", "${vault:k}", "customQueryParams", "{\"api-key\":\"${vault:gw}\"}"))));
        assertDoesNotThrow(() -> LlmTask.validateVaultReferencePlacement(null, config(Map.of("modelName", "${vault:x}"))));
    }
}
