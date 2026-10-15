/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.JudgeModelConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("openai-compatible — deploy-time validation")
class OpenAiCompatibleConfigValidatorTest {

    private static Map<String, String> endpoint(String... kv) {
        Map<String, String> p = new HashMap<>();
        p.put("baseUrl", "http://vllm:8000/v1");
        p.put("modelName", "granite");
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] == null) {
                p.remove(kv[i]);
            } else {
                p.put(kv[i], kv[i + 1]);
            }
        }
        return p;
    }

    private static LlmConfiguration.Task task(String type, Map<String, String> params) {
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setType(type);
        task.setParameters(params);
        return task;
    }

    private static String failure(LlmConfiguration config) {
        return assertThrows(WorkflowConfigurationException.class, () -> OpenAiCompatibleConfigValidator.validate(config)).getMessage();
    }

    @Test
    @DisplayName("a complete endpoint deploys")
    void valid() {
        assertDoesNotThrow(() -> OpenAiCompatibleConfigValidator
                .validate(new LlmConfiguration(List.of(task("openai-compatible", endpoint("jsonMode", "true", "vision", "FALSE"))))));
    }

    @Test
    @DisplayName("a missing baseUrl or modelName fails the deploy, naming the task and the field")
    void missingFields() {
        String noUrl = failure(new LlmConfiguration(List.of(task("openai-compatible", endpoint("baseUrl", null)))));
        assertTrue(noUrl.contains("'t1'") && noUrl.contains("'baseUrl'"), noUrl);

        String noModel = failure(new LlmConfiguration(List.of(task(" OpenAI-Compatible ", endpoint("modelName", "  ")))));
        assertTrue(noModel.contains("'modelName'"), noModel);
    }

    @Test
    @DisplayName("a capability flag that is not a boolean fails the deploy instead of reading as false")
    void badFlag() {
        String message = failure(new LlmConfiguration(List.of(task("openai-compatible", endpoint("jsonModeWithTools", "ture")))));
        assertTrue(message.contains("'jsonModeWithTools'") && message.contains("ture"), message);
    }

    @Test
    @DisplayName("templated values are left for run time")
    void templatedDeferred() {
        assertDoesNotThrow(() -> OpenAiCompatibleConfigValidator.validate(new LlmConfiguration(List.of(task("openai-compatible",
                endpoint("vision", "{properties.vision}", "baseUrl", "${vars.vllmUrl}"))))));
    }

    @Test
    @DisplayName("a ${...} reference in a capability flag fails the deploy: flags are read before references resolve")
    void referenceInFlagRefused() {
        String message = failure(new LlmConfiguration(List.of(task("openai-compatible", endpoint("jsonMode", "${vars:selfHostedJson}")))));
        assertTrue(message.contains("'jsonMode'") && message.contains("references"), message);
    }

    @Test
    @DisplayName("other types are not touched, including type: openai with a baseUrl and these keys")
    void otherTypesUntouched() {
        assertDoesNotThrow(() -> OpenAiCompatibleConfigValidator.validate(new LlmConfiguration(List.of(
                task("openai", Map.of("apiKey", "k", "baseUrl", "http://vllm:8000/v1", "jsonMode", "nonsense")),
                task("ollama", Map.of())))));
        assertDoesNotThrow(() -> OpenAiCompatibleConfigValidator.validate(null));
    }

    @Test
    @DisplayName("a cascade step is checked with the task's parameters underneath, and the judge on its own")
    void cascade() {
        var step = new CascadeStep();
        step.setType("openai-compatible");
        step.setParameters(Map.of("modelName", "step-model", "vision", "maybe"));
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setSteps(List.of(step));
        var task = task("openai", new HashMap<>(Map.of("apiKey", "k", "baseUrl", "http://vllm:8000/v1")));
        task.setModelCascade(cascade);

        String message = failure(new LlmConfiguration(List.of(task)));
        assertTrue(message.contains("cascade step 0") && message.contains("'vision'"), message);
        assertTrue(!message.contains("'baseUrl'"), "the task's baseUrl is merged under the step: " + message);

        step.setParameters(Map.of("modelName", "step-model"));
        var judge = new JudgeModelConfig();
        judge.setType("openai-compatible");
        judge.setParameters(Map.of("modelName", "judge"));
        cascade.setJudgeModel(judge);
        String judgeMessage = failure(new LlmConfiguration(List.of(task)));
        assertTrue(judgeMessage.contains("cascade judge") && judgeMessage.contains("'baseUrl'"), judgeMessage);
    }
}
