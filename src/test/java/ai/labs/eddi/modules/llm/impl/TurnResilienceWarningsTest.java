/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.HttpPostResponse;
import ai.labs.eddi.configs.apicalls.model.RetryApiCallInstruction;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurnResilienceWarnings (deploy-time, warning-only)")
class TurnResilienceWarningsTest {

    private static LlmConfiguration.Task task(Map<String, String> params) {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("gemini");
        task.setParameters(new HashMap<>(params));
        return task;
    }

    private static CascadeStep step(long timeoutMs, Map<String, String> params) {
        var step = new CascadeStep();
        step.setType("gemini");
        step.setTimeoutMs(timeoutMs);
        step.setParameters(new HashMap<>(params));
        return step;
    }

    private static void cascade(LlmConfiguration.Task task, Long maxTotal, CascadeStep... steps) {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setMaxTotalDurationMs(maxTotal);
        cascade.setSteps(List.of(steps));
        task.setModelCascade(cascade);
    }

    // ─── model timeout vs step timeout ──────────────────────────────

    @Test
    @DisplayName("warns when the model timeout is longer than the cascade step's timeoutMs")
    void modelTimeoutLongerThanStep() {
        var task = task(Map.of("timeout", "60000"));
        cascade(task, null, step(25_000, Map.of()));

        var warnings = TurnResilienceWarnings.taskWarnings(task);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("60000") && warnings.get(0).contains("25000"), warnings.get(0));
    }

    @Test
    @DisplayName("a step-level timeout parameter overrides the task's when comparing")
    void stepParameterOverrides() {
        var task = task(Map.of("timeout", "60000"));
        cascade(task, null, step(25_000, Map.of("timeout", "20000")));

        assertTrue(TurnResilienceWarnings.taskWarnings(task).isEmpty());
    }

    @Test
    @DisplayName("equal timeouts, an absent timeout and a templated timeout produce no warning")
    void noWarningWhenConsistentOrUnknown() {
        var equal = task(Map.of("timeout", "25000"));
        cascade(equal, null, step(25_000, Map.of()));
        assertTrue(TurnResilienceWarnings.taskWarnings(equal).isEmpty());

        var absent = task(Map.of());
        cascade(absent, null, step(25_000, Map.of()));
        assertTrue(TurnResilienceWarnings.taskWarnings(absent).isEmpty());

        var templated = task(Map.of("timeout", "${vars:model-timeout}"));
        cascade(templated, null, step(25_000, Map.of()));
        assertTrue(TurnResilienceWarnings.taskWarnings(templated).isEmpty());
    }

    // ─── convertToObject without a fallback ─────────────────────────

    @Test
    @DisplayName("warns when convertToObject has no validation fallback and no onError fallback")
    void convertToObjectWithoutFallback() {
        var warnings = TurnResilienceWarnings.taskWarnings(task(Map.of("convertToObject", "true")));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("convertToObject"), warnings.get(0));
    }

    @Test
    @DisplayName("responseValidation with a fallback action counts as a fallback path")
    void validationFallbackSilencesIt() {
        var task = task(Map.of("convertToObject", "true"));
        var validation = new LlmConfiguration.ResponseValidation();
        validation.setEnabled(true);
        validation.setOnEmpty("fallback");
        task.setResponseValidation(validation);

        assertTrue(TurnResilienceWarnings.taskWarnings(task).isEmpty());
    }

    @Test
    @DisplayName("responseValidation that only warns is not a fallback path")
    void validationThatOnlyWarnsDoesNotCount() {
        var task = task(Map.of("convertToObject", "true"));
        var validation = new LlmConfiguration.ResponseValidation();
        validation.setEnabled(true);
        task.setResponseValidation(validation);

        assertEquals(1, TurnResilienceWarnings.taskWarnings(task).size());
    }

    @Test
    @DisplayName("tasks without convertToObject are not warned about")
    void noConvertToObject() {
        assertTrue(TurnResilienceWarnings.taskWarnings(task(Map.of())).isEmpty());
    }

    // ─── worst case ─────────────────────────────────────────────────

    @Test
    @DisplayName("a plain model's worst case is attempts x timeout plus the backoffs")
    void plainWorstCase() {
        var task = task(Map.of("timeout", "10000"));
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(3);
        retry.setBackoffDelayMs(1_000L);
        retry.setBackoffMultiplier(2.0);
        retry.setMaxBackoffDelayMs(10_000L);
        task.setRetry(retry);

        // 3 x 10 s + backoffs of 1 s and 2 s
        assertEquals(33_000, TurnResilienceWarnings.worstCaseLlmMs(task));
    }

    @Test
    @DisplayName("a cascade's worst case is the sum of its steps, each bounded by its timeoutMs, and by maxTotalDurationMs")
    void cascadeWorstCase() {
        var task = task(Map.of("timeout", "25000"));
        cascade(task, null, step(25_000, Map.of()), step(20_000, Map.of()));
        assertEquals(45_000, TurnResilienceWarnings.worstCaseLlmMs(task), "each step is capped by its own timeoutMs");

        cascade(task, 35_000L, step(25_000, Map.of()), step(20_000, Map.of()));
        assertEquals(35_000, TurnResilienceWarnings.worstCaseLlmMs(task), "and the whole by maxTotalDurationMs");
    }

    @Test
    @DisplayName("an httpcall's worst case includes its retries; fire-and-forget calls cost the turn nothing")
    void httpWorstCase() {
        var slow = new ApiCall();
        slow.setName("slow");
        slow.setTimeoutInMillis(10_000);
        var retry = new RetryApiCallInstruction();
        retry.setMaxRetries(1);
        retry.setExponentialBackoffDelayInMillis(1_000);
        var post = new HttpPostResponse();
        post.setRetryApiCallInstruction(retry);
        slow.setPostResponse(post);

        var tracking = new ApiCall();
        tracking.setName("tracking");
        tracking.setFireAndForget(true);
        tracking.setTimeoutInMillis(60_000);

        var config = new ApiCallsConfiguration();
        config.setHttpCalls(List.of(slow, tracking));

        // 10 s + 1 retry x 10 s + 1 s backoff
        assertEquals(21_000, TurnResilienceWarnings.worstCaseApiCallMs(config));
    }

    // ─── the budget rule ────────────────────────────────────────────

    @Test
    @DisplayName("warns when the worst case exceeds turnDeadlineMs minus the reserve, and not otherwise")
    void budgetWarning() {
        assertNull(TurnResilienceWarnings.budgetWarning(55_000, 1_500, 40_000, 10_000));
        assertNull(TurnResilienceWarnings.budgetWarning(55_000, 1_500, 43_500, 10_000), "exactly at the limit still fits");

        String warning = TurnResilienceWarnings.budgetWarning(55_000, 1_500, 45_000, 10_000);
        assertNotNull(warning);
        assertTrue(warning.contains("turnDeadlineMs 55000ms"), warning);
    }
}
