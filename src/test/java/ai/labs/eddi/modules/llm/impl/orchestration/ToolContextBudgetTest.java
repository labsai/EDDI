/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.orchestration;

import ai.labs.eddi.modules.llm.impl.TokenCounterFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link ToolContextBudget}, extracted from {@code
 * AgentOrchestrator} during the R2 (step 3) refactor. Covers {@code
 * resolveToolContextEstimator} directly — it has no reflection dependency in
 * the existing suite and is otherwise only exercised indirectly through the
 * full {@code executeIfToolsEnabled}/{@code runToolCallLoop} flow, so this is
 * genuinely new coverage. {@code enforceToolContextBudget}'s eviction logic
 * (the bulk of this cluster's behavior) is already exhaustively covered by the
 * 462-line {@code AgentOrchestratorToolContextBudgetTest} via the facade's
 * static delegator — re-verified green against this class post-extraction
 * rather than duplicated here.
 *
 * @author tests
 */
class ToolContextBudgetTest {

    private LlmConfiguration.Task task(Map<String, String> parameters) {
        var task = new LlmConfiguration.Task();
        task.setType("anthropic");
        task.setParameters(parameters);
        return task;
    }

    @Test
    void resolveToolContextEstimator_resolvesModelFromParameters() {
        var estimator = mock(TokenCountEstimator.class);
        var factory = mock(TokenCounterFactory.class);
        when(factory.getEstimator("anthropic", "claude-sonnet-5")).thenReturn(estimator);
        var budget = new ToolContextBudget(factory);

        var resolved = budget.resolveToolContextEstimator(task(Map.of("modelName", "claude-sonnet-5")));

        assertSame(estimator, resolved);
    }

    @Test
    void resolveToolContextEstimator_nullParameters_resolvesWithNullModelName() {
        var estimator = mock(TokenCountEstimator.class);
        var factory = mock(TokenCounterFactory.class);
        when(factory.getEstimator("anthropic", null)).thenReturn(estimator);
        var budget = new ToolContextBudget(factory);

        var resolved = budget.resolveToolContextEstimator(task(null));

        assertSame(estimator, resolved);
    }

    @Test
    void resolveToolContextEstimator_factoryThrows_fallsBackToApproximateEstimator() {
        var fallback = mock(TokenCountEstimator.class);
        var factory = mock(TokenCounterFactory.class);
        when(factory.getEstimator("anthropic", "unknown-model")).thenThrow(new RuntimeException("no tokenizer for model"));
        when(factory.getEstimator(null, null)).thenReturn(fallback);
        var budget = new ToolContextBudget(factory);

        var resolved = budget.resolveToolContextEstimator(task(Map.of("modelName", "unknown-model")));

        assertSame(fallback, resolved);
    }

    @Test
    void sumTokens_nullOperands_returnTheOtherUnchanged() {
        var usage = new dev.langchain4j.model.output.TokenUsage(1, 2, 3);

        assertSame(usage, ToolContextBudget.sumTokens(usage, null));
        assertSame(usage, ToolContextBudget.sumTokens(null, usage));
    }

    @Test
    void tokenUsageMap_nullFields_mapToZero() {
        var map = ToolContextBudget.tokenUsageMap(new dev.langchain4j.model.output.TokenUsage(null, null, null));

        assertEquals(0, map.get("inputTokens"));
        assertEquals(0, map.get("outputTokens"));
        assertEquals(0, map.get("totalTokens"));
    }

    @Test
    void constructedWithMockedCollaborator_doesNotThrow() {
        assertDoesNotThrow(() -> new ToolContextBudget(mock(TokenCounterFactory.class)));
    }
}
