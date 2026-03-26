package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CascadingModelExecutor} — param merging and config
 * defaults.
 * <p>
 * Note: Full cascade execution tests require mocked ChatModel/AgentOrchestrator
 * and are covered by integration tests. These tests cover the utility methods
 * and configuration model behavior.
 */
class CascadingModelExecutorTest {

    // ==================== Parameter Merging ====================

    @Nested
    @DisplayName("mergeParams")
    class MergeParamsTests {

        @Test
        @DisplayName("should prefer step params over base params")
        void testStepOverridesBase() {
            var base = Map.of("model", "gpt-4o-mini", "apiKey", "key123");
            var step = Map.of("model", "gpt-4o");

            var merged = CascadingModelExecutor.mergeParams(base, step);

            assertEquals("gpt-4o", merged.get("model"), "Step params should override base");
            assertEquals("key123", merged.get("apiKey"), "Base params should be preserved");
        }

        @Test
        @DisplayName("should handle null base params")
        void testNullBase() {
            var step = Map.of("model", "gpt-4o");
            var merged = CascadingModelExecutor.mergeParams(null, step);

            assertEquals("gpt-4o", merged.get("model"));
            assertEquals(1, merged.size());
        }

        @Test
        @DisplayName("should handle null step params")
        void testNullStep() {
            var base = Map.of("model", "gpt-4o-mini");
            var merged = CascadingModelExecutor.mergeParams(base, null);

            assertEquals("gpt-4o-mini", merged.get("model"));
            assertEquals(1, merged.size());
        }

        @Test
        @DisplayName("should handle both null")
        void testBothNull() {
            var merged = CascadingModelExecutor.mergeParams(null, null);
            assertTrue(merged.isEmpty());
        }

        @Test
        @DisplayName("should merge disjoint keys")
        void testDisjointKeys() {
            var base = Map.of("apiKey", "key123", "temperature", "0.5");
            var step = Map.of("model", "claude-sonnet-4-20250514", "maxTokens", "1000");

            var merged = CascadingModelExecutor.mergeParams(base, step);

            assertEquals(4, merged.size());
            assertEquals("key123", merged.get("apiKey"));
            assertEquals("0.5", merged.get("temperature"));
            assertEquals("claude-sonnet-4-20250514", merged.get("model"));
            assertEquals("1000", merged.get("maxTokens"));
        }
    }

    // ==================== ModelCascadeConfig Defaults ====================

    @Nested
    @DisplayName("ModelCascadeConfig defaults")
    class ConfigDefaultsTests {

        @Test
        @DisplayName("should have sensible defaults")
        void testDefaults() {
            var config = new ModelCascadeConfig();

            assertFalse(config.isEnabled());
            assertEquals("cascade", config.getStrategy());
            assertEquals("structured_output", config.getEvaluationStrategy());
            assertTrue(config.isEnableInAgentMode());
            assertNull(config.getSteps());
        }

        @Test
        @DisplayName("should store all fields")
        void testAllFields() {
            var config = new ModelCascadeConfig();
            config.setEnabled(true);
            config.setStrategy("parallel");
            config.setEvaluationStrategy("heuristic");
            config.setEnableInAgentMode(false);

            var step = new CascadeStep();
            step.setType("openai");
            step.setConfidenceThreshold(0.7);
            config.setSteps(List.of(step));

            assertTrue(config.isEnabled());
            assertEquals("parallel", config.getStrategy());
            assertEquals("heuristic", config.getEvaluationStrategy());
            assertFalse(config.isEnableInAgentMode());
            assertEquals(1, config.getSteps().size());
        }
    }

    // ==================== CascadeStep Defaults ====================

    @Nested
    @DisplayName("CascadeStep defaults")
    class StepDefaultsTests {

        @Test
        @DisplayName("should have default timeout of 30000ms")
        void testDefaultTimeout() {
            var step = new CascadeStep();
            assertEquals(30000L, step.getTimeoutMs());
        }

        @Test
        @DisplayName("should have null confidence threshold by default (= final step)")
        void testDefaultThreshold() {
            var step = new CascadeStep();
            assertNull(step.getConfidenceThreshold());
        }

        @Test
        @DisplayName("should store all fields")
        void testAllFields() {
            var step = new CascadeStep();
            step.setType("anthropic");
            step.setParameters(Map.of("model", "claude-sonnet-4-20250514"));
            step.setConfidenceThreshold(0.8);
            step.setTimeoutMs(60000L);

            assertEquals("anthropic", step.getType());
            assertEquals("claude-sonnet-4-20250514", step.getParameters().get("model"));
            assertEquals(0.8, step.getConfidenceThreshold(), 0.001);
            assertEquals(60000L, step.getTimeoutMs());
        }
    }

    // ==================== Task modelCascade integration ====================

    @Nested
    @DisplayName("Task modelCascade field")
    class TaskModelCascadeTests {

        @Test
        @DisplayName("should be null by default (backward compatible)")
        void testDefaultNull() {
            var task = new LlmConfiguration.Task();
            assertNull(task.getModelCascade());
        }

        @Test
        @DisplayName("should store and retrieve modelCascade config")
        void testSetAndGet() {
            var task = new LlmConfiguration.Task();
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setSteps(List.of(new CascadeStep()));

            task.setModelCascade(cascade);

            assertNotNull(task.getModelCascade());
            assertTrue(task.getModelCascade().isEnabled());
            assertEquals(1, task.getModelCascade().getSteps().size());
        }

        @Test
        @DisplayName("cascade gate should be false when config is null")
        void testCascadeGateNull() {
            var task = new LlmConfiguration.Task();
            // This matches the condition in LlmTask.executeTask()
            var cascadeConfig = task.getModelCascade();
            boolean shouldCascade = cascadeConfig != null && cascadeConfig.isEnabled() && cascadeConfig.getSteps() != null
                    && !cascadeConfig.getSteps().isEmpty();

            assertFalse(shouldCascade);
        }

        @Test
        @DisplayName("cascade gate should be false when disabled")
        void testCascadeGateDisabled() {
            var task = new LlmConfiguration.Task();
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(false);
            cascade.setSteps(List.of(new CascadeStep()));
            task.setModelCascade(cascade);

            boolean shouldCascade = task.getModelCascade() != null && task.getModelCascade().isEnabled() && task.getModelCascade().getSteps() != null
                    && !task.getModelCascade().getSteps().isEmpty();

            assertFalse(shouldCascade);
        }

        @Test
        @DisplayName("cascade gate should be false when steps are empty")
        void testCascadeGateEmptySteps() {
            var task = new LlmConfiguration.Task();
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setSteps(List.of());
            task.setModelCascade(cascade);

            boolean shouldCascade = task.getModelCascade() != null && task.getModelCascade().isEnabled() && task.getModelCascade().getSteps() != null
                    && !task.getModelCascade().getSteps().isEmpty();

            assertFalse(shouldCascade);
        }

        @Test
        @DisplayName("cascade gate should be true when properly configured")
        void testCascadeGateTrue() {
            var task = new LlmConfiguration.Task();
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setSteps(List.of(new CascadeStep()));
            task.setModelCascade(cascade);

            boolean shouldCascade = task.getModelCascade() != null && task.getModelCascade().isEnabled() && task.getModelCascade().getSteps() != null
                    && !task.getModelCascade().getSteps().isEmpty();

            assertTrue(shouldCascade);
        }
    }
}
