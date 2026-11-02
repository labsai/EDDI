/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two building blocks of model-phase spend accounting: which prices a phase
 * is charged at ({@link ModelPrices}) and how a failed phase carries what it
 * spent ({@link UnrecordedSpend}).
 */
@DisplayName("Model-phase spend: prices and the failure carrier")
class ModelPhaseSpendTest {

    private static final Map<String, Object> ONE_MILLION_IN = Map.of("inputTokens", 1_000_000, "outputTokens", 0);

    @Nested
    @DisplayName("ModelPrices")
    class Prices {

        private LlmConfiguration.Task taskWithCascade() {
            var task = new LlmConfiguration.Task();
            task.setInputPricePer1M(5.0);
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setInputPricePer1M(1.0);
            var priced = new CascadeStep();
            priced.setInputPricePer1M(2.0);
            var unpriced = new CascadeStep();
            cascade.setSteps(List.of(priced, unpriced));
            task.setModelCascade(cascade);
            return task;
        }

        @Test
        @DisplayName("a cascade step is priced at its own rates, else the cascade's — never the task's")
        void cascadeStep() {
            var task = taskWithCascade();
            assertEquals(2.0, ModelPrices.forRun(task, 0).cost(ONE_MILLION_IN), 1e-9);
            assertEquals(1.0, ModelPrices.forRun(task, 1).cost(ONE_MILLION_IN), 1e-9);
        }

        @Test
        @DisplayName("no step, or a step that no longer exists, is the task's own model")
        void taskModel() {
            var task = taskWithCascade();
            assertEquals(5.0, ModelPrices.forRun(task, null).cost(ONE_MILLION_IN), 1e-9);
            assertEquals(5.0, ModelPrices.forRun(task, 7).cost(ONE_MILLION_IN), 1e-9);
            task.getModelCascade().setEnabled(false);
            assertEquals(5.0, ModelPrices.forRun(task, 0).cost(ONE_MILLION_IN), 1e-9);
        }

        @Test
        @DisplayName("the cascade's computeCost is the same decision")
        void computeCostDelegates() {
            var task = taskWithCascade();
            assertEquals(2.0, CascadingModelExecutor.computeCost(task.getModelCascade().getSteps().get(0), task.getModelCascade(), ONE_MILLION_IN),
                    1e-9);
        }
    }

    @Nested
    @DisplayName("UnrecordedSpend")
    class Carrier {

        @Test
        @DisplayName("spend attached to a failure is drained once, also through a wrapping cause")
        void drainedOnceThroughCause() {
            var inner = UnrecordedSpend.attach(new LifecycleException("round 7 failed"), ONE_MILLION_IN, 0.5, 0.25);
            var outer = new LifecycleException("task failed", inner);

            var spend = UnrecordedSpend.drain(outer);
            assertEquals(0.75, spend.totalUsd(), 1e-9);
            assertEquals(1_000_000L, ((Number) spend.tokenUsage().get("inputTokens")).longValue());

            assertTrue(UnrecordedSpend.drain(outer).isEmpty(), "a drained carrier is never counted twice");
        }

        @Test
        @DisplayName("nothing to carry attaches nothing; non-finite and negative amounts count as zero")
        void emptyAndNonFinite() {
            var failure = new RuntimeException("x");
            assertSame(failure, UnrecordedSpend.attach(failure, null, 0.0, 0.0));
            assertEquals(0, failure.getSuppressed().length);

            UnrecordedSpend.attach(failure, null, Double.NaN, -3.0);
            assertEquals(0, failure.getSuppressed().length);
            assertTrue(UnrecordedSpend.drain(failure).isEmpty());
        }

        @Test
        @DisplayName("several carriers in one chain are summed")
        void summed() {
            var inner = UnrecordedSpend.attach(new RuntimeException("a"), Map.of("inputTokens", 10), 0.1, 0.0);
            var outer = UnrecordedSpend.attach(new RuntimeException("b", inner), Map.of("inputTokens", 5), 0.2, 0.05);
            var spend = UnrecordedSpend.drain(outer);
            assertEquals(0.35, spend.totalUsd(), 1e-9);
            assertEquals(15L, ((Number) spend.tokenUsage().get("inputTokens")).longValue());
        }

        @Test
        @DisplayName("a refused spend check can carry the spend of the cascade steps before it")
        void spendRefusalCarries() {
            var refused = UnrecordedSpend.attach(
                    new LlmSpendGate.SpendRefusedException(new LlmSpendGate.Refusal(LlmSpendGate.REASON_TENANT_BUDGET, "spent")), null, 0.4, 0.0);
            assertEquals(0.4, UnrecordedSpend.drain(refused).totalUsd(), 1e-9);
        }
    }
}
