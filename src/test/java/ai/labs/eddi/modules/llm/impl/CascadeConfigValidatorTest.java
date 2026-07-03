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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CascadeConfigValidator} — deploy-time cascade validation.
 */
@DisplayName("CascadeConfigValidator")
class CascadeConfigValidatorTest {

    private static LlmConfiguration configWith(ModelCascadeConfig cascade) {
        return configWith(cascade, null);
    }

    private static LlmConfiguration configWith(ModelCascadeConfig cascade, Map<String, String> params) {
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setType("openai");
        task.setParameters(params);
        task.setModelCascade(cascade);
        return new LlmConfiguration(List.of(task));
    }

    private static CascadeStep step(String type, Double threshold) {
        var s = new CascadeStep();
        s.setType(type);
        s.setConfidenceThreshold(threshold);
        return s;
    }

    private static ModelCascadeConfig enabled(List<CascadeStep> steps) {
        var c = new ModelCascadeConfig();
        c.setEnabled(true);
        c.setSteps(steps);
        return c;
    }

    @Test
    @DisplayName("disabled cascade — never validated")
    void disabledCascade_ok() {
        var c = new ModelCascadeConfig();
        c.setEnabled(false);
        c.setSteps(null); // would be invalid if validated
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("null config / null tasks — no-op")
    void nullConfig_ok() {
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(null));
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(new LlmConfiguration(null)));
    }

    @Test
    @DisplayName("enabled but empty steps — warns, does not block load (fails at runtime as before)")
    void emptySteps_warnsNotThrows() {
        var c = enabled(List.of());
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("unknown strategy — warns, does not throw (ran sequentially before)")
    void unknownStrategy_warns() {
        var c = enabled(List.of(step("openai", null)));
        c.setStrategy("magic");
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("parallel strategy — allowed (warns, runs sequentially)")
    void parallelStrategy_ok() {
        var c = enabled(List.of(step("openai", null)));
        c.setStrategy("parallel");
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("unknown evaluationStrategy — warns, does not throw (defaulted before)")
    void unknownEvaluationStrategy_warns() {
        var c = enabled(List.of(step("openai", null)));
        c.setEvaluationStrategy("vibes");
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("judge_model without judge config — warns, does not throw (heuristic fallback)")
    void judgeModelWithoutJudge_warns() {
        var c = enabled(List.of(step("openai", null)));
        c.setEvaluationStrategy("judge_model");
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("judge_model with judge config — ok")
    void judgeModelWithJudge_ok() {
        var c = enabled(List.of(step("openai", null)));
        c.setEvaluationStrategy("judge_model");
        var judge = new JudgeModelConfig();
        judge.setType("openai");
        c.setJudgeModel(judge);
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("threshold > 1.0 — warns, does not throw (unreachable but tolerated)")
    void thresholdAboveOne_warns() {
        var c = enabled(List.of(step("openai", 1.5), step("anthropic", null)));
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("negative threshold — warns, does not throw")
    void negativeThreshold_warns() {
        var c = enabled(List.of(step("openai", -0.1), step("anthropic", null)));
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("non-last step with null threshold — warns, does not throw (dead-step trap tolerated)")
    void nonLastNullThreshold_warns() {
        var c = enabled(List.of(step("openai", null), step("anthropic", null)));
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("last step with null threshold — ok")
    void lastNullThreshold_ok() {
        var c = enabled(List.of(step("openai", 0.7), step("anthropic", null)));
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("negative pricing — throws")
    void negativePrice_throws() {
        var s = step("openai", null);
        s.setInputPricePer1M(-1.0);
        assertThrows(WorkflowConfigurationException.class, () -> CascadeConfigValidator.validate(configWith(enabled(List.of(s)))));
    }

    @Test
    @DisplayName("non-positive maxTotalDurationMs — throws")
    void badDurationCeiling_throws() {
        var c = enabled(List.of(step("openai", null)));
        c.setMaxTotalDurationMs(0L);
        assertThrows(WorkflowConfigurationException.class, () -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("negative maxCostPerRun — throws")
    void negativeCostCeiling_throws() {
        var c = enabled(List.of(step("openai", null)));
        c.setMaxCostPerRun(-1.0);
        assertThrows(WorkflowConfigurationException.class, () -> CascadeConfigValidator.validate(configWith(c)));
    }

    @Test
    @DisplayName("non-positive step timeout — warns, does not throw")
    void badStepTimeout_warns() {
        var s = step("openai", null);
        s.setTimeoutMs(0L);
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(enabled(List.of(s)))));
    }

    @Test
    @DisplayName("convertToObject + structured_output — allowed (auto-downgraded, warns)")
    void convertToObjectStructured_ok() {
        var c = enabled(List.of(step("openai", null)));
        c.setEvaluationStrategy("structured_output");
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c, Map.of("convertToObject", "true"))));
    }

    @Test
    @DisplayName("cross-provider step without its own apiKey — validates (warns only)")
    void crossProviderStepMissingApiKey_ok() {
        // task provider is openai (with apiKey); step 2 is anthropic without its own
        // key
        var c = enabled(List.of(step("openai", 0.7), step("anthropic", null)));
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c, Map.of("apiKey", "k"))));
    }

    @Test
    @DisplayName("cross-provider step with its own apiKey — validates cleanly")
    void crossProviderStepWithApiKey_ok() {
        var s2 = new CascadeStep();
        s2.setType("anthropic");
        s2.setParameters(Map.of("apiKey", "anthropic-key"));
        var c = enabled(List.of(step("openai", 0.7), s2));
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c, Map.of("apiKey", "k"))));
    }

    @Test
    @DisplayName("cross-provider judge without apiKey — validates (warns only)")
    void crossProviderJudgeMissingApiKey_ok() {
        var c = enabled(List.of(step("openai", null)));
        c.setEvaluationStrategy("judge_model");
        var judge = new JudgeModelConfig();
        judge.setType("anthropic"); // different provider, no own params
        c.setJudgeModel(judge);
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c, Map.of("apiKey", "k"))));
    }

    @Test
    @DisplayName("valid two-step cascade — ok")
    void validCascade_ok() {
        var c = enabled(List.of(step("openai", 0.7), step("anthropic", null)));
        c.setEvaluationStrategy("heuristic");
        c.setMaxTotalDurationMs(60000L);
        c.setMaxCostPerRun(0.5);
        assertDoesNotThrow(() -> CascadeConfigValidator.validate(configWith(c)));
    }
}
