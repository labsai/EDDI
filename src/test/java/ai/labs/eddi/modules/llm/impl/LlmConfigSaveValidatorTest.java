/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.LenientConfigWrite;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each refusal names the runtime code that fails on the refused value; each
 * "passes" test pins a value the runtime tolerates (M4 of the 2026-11-18
 * review).
 */
@DisplayName("LlmConfigSaveValidator")
class LlmConfigSaveValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private LlmConfiguration read(String json) throws Exception {
        return mapper.readValue(json, LlmConfiguration.class);
    }

    private static String task(String type, String parameters) {
        return "{\"tasks\":[{\"id\":\"t\",\"type\":" + (type == null ? "null" : "\"" + type + "\"") + ",\"parameters\":" + parameters + "}]}";
    }

    private List<String> problems(String json) throws Exception {
        return LlmConfigSaveValidator.findProblems(read(json));
    }

    @Test
    @DisplayName("a config without tasks is not refused: an empty document is a legal starting point")
    void emptyConfigPasses() throws Exception {
        assertTrue(problems("{}").isEmpty());
    }

    @Test
    @DisplayName("an unknown provider is refused: ChatModelRegistry.getOrCreate throws UnsupportedLlmTaskException on every turn")
    void unknownProvider() throws Exception {
        List<String> problems = problems(task("nonexistent-provider", "{}"));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("tasks[0].type 'nonexistent-provider' is not a known LLM provider"), problems.getFirst());
        assertTrue(problems.getFirst().contains("openai"), "the message lists the providers: " + problems.getFirst());
    }

    @Test
    @DisplayName("a missing provider is refused when no cascade is configured: the registry has no default type")
    void missingProvider() throws Exception {
        assertTrue(problems(task(null, "{}")).getFirst().startsWith("tasks[0].type is required"));
    }

    @Test
    @DisplayName("named OpenAI-compatible providers and the built-in types are known; the lookup is exact")
    void knownProviders() {
        assertTrue(LlmConfigSaveValidator.isKnownProvider("openai"));
        assertTrue(LlmConfigSaveValidator.isKnownProvider("anthropic"));
        assertTrue(LlmConfigSaveValidator.isKnownProvider("openai-compatible"));
        assertTrue(LlmConfigSaveValidator.isKnownProvider("deepseek"), "a catalog preset is a provider too");
        assertFalse(LlmConfigSaveValidator.isKnownProvider("OpenAI"), "lookup is exact, like the model registry's");
    }

    @Test
    @DisplayName("a provider type or parameter that is still a template is left for run time")
    void templatesAreNotChecked() throws Exception {
        assertTrue(problems(task("{vars.provider}", "{\"temperature\":\"${vars:temp}\"}")).isEmpty());
        assertTrue(problems(task("openai", "{\"temperature\":\"{properties.t}\"}")).isEmpty());
    }

    @Test
    @DisplayName("a value outside the catalog's range is refused: the builder sends it unchanged and the provider refuses it")
    void outOfRange() throws Exception {
        List<String> problems = problems(task("openai", "{\"modelName\":\"gpt-4o\",\"temperature\":\"5\"}"));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("tasks[0].parameters.temperature must be between 0 and 2"), problems.getFirst());
    }

    @Test
    @DisplayName("NaN is refused (Double.valueOf accepts it and the request carries it); a number inside the range passes")
    void nan() throws Exception {
        assertEquals(1, problems(task("openai", "{\"temperature\":\"NaN\"}")).size());
        assertTrue(problems(task("openai", "{\"temperature\":\"0.7\",\"modelName\":\"gpt-4o\"}")).isEmpty());
    }

    @Test
    @DisplayName("a value that does not parse passes: ModelParameterValues ignores it and the provider default applies")
    void unparsableValuesPass() throws Exception {
        assertTrue(problems(task("openai", "{\"temperature\":\"hot\"}")).isEmpty());
        assertTrue(problems(task("openai", "{\"maxTokens\":\"1.5\"}")).isEmpty(), "Integer.valueOf drops a fraction");
        assertTrue(problems(task("openai", "{\"maxTokens\":\"-5.0\"}")).isEmpty(), "and a whole number written as a double");
    }

    @Test
    @DisplayName("M4b: a blank model key passes, for any key and any provider — every builder treats it as the provider default")
    void blankModelPasses() throws Exception {
        assertTrue(problems(task("openai", "{\"modelName\":\"\"}")).isEmpty());
        assertTrue(problems(task("openai", "{\"modelName\":\"gpt-4o\",\"deploymentName\":\"\"}")).isEmpty());
        assertTrue(problems(task("anthropic", "{\"model\":\" \"}")).isEmpty());
    }

    @Test
    @DisplayName("M4a: a cascade step without a type inherits the task's (CascadingModelExecutor.resolveStepModel) and passes")
    void cascadeStepWithoutTypePasses() throws Exception {
        String json = "{\"tasks\":[{\"id\":\"t\",\"type\":\"openai\",\"parameters\":{\"modelName\":\"gpt-4o\"},"
                + "\"modelCascade\":{\"enabled\":true,\"steps\":[{\"parameters\":{\"modelName\":\"gpt-4o-mini\"}},"
                + "{\"type\":\"anthropic\",\"parameters\":{\"model\":\"claude-sonnet-4-5\"}}]}}]}";
        assertTrue(problems(json).isEmpty(), problems(json).toString());
    }

    @Test
    @DisplayName("an inherited step type is range-checked against the task's provider")
    void inheritedTypeIsRangeChecked() throws Exception {
        String json = "{\"tasks\":[{\"id\":\"t\",\"type\":\"openai\",\"parameters\":{},"
                + "\"modelCascade\":{\"enabled\":true,\"steps\":[{\"parameters\":{\"temperature\":\"9\"}}]}}]}";
        assertTrue(problems(json).stream().anyMatch(p -> p.startsWith("tasks[0].modelCascade.steps[0].parameters.temperature")),
                problems(json).toString());
    }

    @Test
    @DisplayName("a step with an unknown or blank type, or a timeout <= 0, can never answer and is refused")
    void cascadeSteps() throws Exception {
        String json = "{\"tasks\":[{\"id\":\"t\",\"type\":\"openai\",\"parameters\":{\"modelName\":\"gpt-4o\"},"
                + "\"modelCascade\":{\"enabled\":true,\"steps\":[{\"type\":\"bogus\",\"parameters\":{}},"
                + "{\"type\":\"\",\"parameters\":{}},{\"type\":\"openai\",\"timeoutMs\":0,\"parameters\":{}}]}}]}";
        List<String> problems = problems(json);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("tasks[0].modelCascade.steps[0].type 'bogus'")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("tasks[0].modelCascade.steps[1].type is required")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("tasks[0].modelCascade.steps[2].timeoutMs")), problems.toString());
    }

    @Test
    @DisplayName("with a configured cascade the task's own type is not refused: the task model is built only on tool turns")
    void cascadeCoversTheTaskType() throws Exception {
        String json = "{\"tasks\":[{\"id\":\"t\",\"parameters\":{},"
                + "\"modelCascade\":{\"enabled\":true,\"steps\":[{\"type\":\"openai\",\"parameters\":{}}]}}]}";
        assertTrue(problems(json).isEmpty(), problems(json).toString());
    }

    @Test
    @DisplayName("the judge model is not checked: an unknown judge falls back to the heuristic")
    void judgeIsNotChecked() throws Exception {
        String json = "{\"tasks\":[{\"id\":\"t\",\"type\":\"openai\",\"parameters\":{},"
                + "\"modelCascade\":{\"enabled\":true,\"steps\":[{\"type\":\"openai\",\"parameters\":{}}],"
                + "\"judgeModel\":{\"type\":\"bogus\",\"parameters\":{\"temperature\":\"99\"}}}}]}";
        assertTrue(problems(json).isEmpty(), problems(json).toString());
    }

    @Test
    @DisplayName("retry: maxAttempts < 1 is refused (executeWithRetry makes no attempt); negative delays are clamped and pass")
    void retry() throws Exception {
        String json = "{\"tasks\":[{\"id\":\"t\",\"type\":\"openai\",\"parameters\":{},"
                + "\"retry\":{\"maxAttempts\":0,\"backoffDelayMs\":-5,\"maxBackoffDelayMs\":-1,\"backoffMultiplier\":0.5}}]}";
        List<String> problems = problems(json);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("tasks[0].retry.maxAttempts must be at least 1"), problems.toString());
    }

    @Test
    @DisplayName("interactive save throws IllegalArgumentException (400); inside LenientConfigWrite it only logs")
    void strictVersusLenient() throws Exception {
        LlmConfiguration config = read(task("nonexistent-provider", "{}"));

        var refused = assertThrows(IllegalArgumentException.class, () -> LlmConfigSaveValidator.validate(config));
        assertTrue(refused.getMessage().contains("tasks[0].type"), refused.getMessage());

        assertDoesNotThrow(() -> LenientConfigWrite.call(() -> {
            LlmConfigSaveValidator.validate(config);
            return null;
        }));
        assertEquals(List.of("LLM configuration: " + LlmConfigSaveValidator.findProblems(config).getFirst()),
                LlmConfigSaveValidator.problems(config), "problems() is what a lenient caller collects, prefixed with the kind");
    }

    @Test
    @DisplayName("the shipped reference config (written by an earlier release) passes unchanged")
    void shippedFixturePasses() throws Exception {
        Path fixture = Path.of("docs", "agent-configs", "google-workspace-tools", "bbb000000000000000000001", "1",
                "bbb000000000000000000005.langchain.json");
        LlmConfiguration stored = read(Files.readString(fixture));
        assertTrue(LlmConfigSaveValidator.findProblems(stored).isEmpty(), LlmConfigSaveValidator.findProblems(stored).toString());
        assertDoesNotThrow(() -> LlmConfigSaveValidator.validate(stored));
    }
}
