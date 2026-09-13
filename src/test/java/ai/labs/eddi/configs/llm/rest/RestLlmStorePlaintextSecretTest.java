/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.rest;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plaintext provider key in an LLM configuration is returned verbatim by the
 * read endpoint and lands in exports. The store warns about it (it cannot
 * reject: setup falls back to plaintext on a vault-less instance) — this pins
 * what counts.
 */
class RestLlmStorePlaintextSecretTest {

    private static LlmConfiguration.Task task(Map<String, String> parameters) {
        var task = new LlmConfiguration.Task();
        task.setType("anthropic");
        task.setParameters(new LinkedHashMap<>(parameters));
        return task;
    }

    @Test
    @DisplayName("plaintext credentials are reported by parameter name, never by value")
    void plaintextCredentialsAreReported() {
        var config = new LlmConfiguration(List.of(
                task(Map.of("apiKey", "sk-ant-api03-real")),
                task(Map.of("secretAccessKey", "wJalrXUtnFEMI"))));

        assertEquals(List.of("tasks[0].parameters.apiKey", "tasks[1].parameters.secretAccessKey"),
                RestLlmStore.plaintextSecretParameters(config));
    }

    @Test
    @DisplayName("references, templates, blanks and non-credential parameters are not plaintext secrets")
    void referencesAndTemplatesAreFine() {
        var config = new LlmConfiguration(List.of(
                task(Map.of("apiKey", "${vault:anthropic-key}")),
                task(Map.of("authToken", "{properties.userToken}")),
                task(Map.of("apiKey", " ")),
                task(Map.of("modelName", "claude-sonnet-5", "password", "${connection:db}"))));

        assertTrue(RestLlmStore.plaintextSecretParameters(config).isEmpty());
    }

    @Test
    @DisplayName("an empty or absent configuration reports nothing")
    void nullSafe() {
        assertTrue(RestLlmStore.plaintextSecretParameters(null).isEmpty());
        assertTrue(RestLlmStore.plaintextSecretParameters(new LlmConfiguration(List.of(new LlmConfiguration.Task()))).isEmpty());
    }
}
