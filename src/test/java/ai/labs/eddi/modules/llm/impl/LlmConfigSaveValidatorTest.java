/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.validation.ConfigValidationPolicy;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LlmConfigSaveValidator")
class LlmConfigSaveValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private LlmConfiguration read(String json) throws Exception {
        return mapper.readValue(json, LlmConfiguration.class);
    }

    private static String task(String type, String parameters) {
        return "{\"tasks\":[{\"id\":\"t\",\"type\":" + (type == null ? "null" : "\"" + type + "\"") + ",\"parameters\":" + parameters + "}]}";
    }

    @Test
    @DisplayName("a config without tasks is not refused: an empty document is a legal starting point")
    void emptyConfigPasses() throws Exception {
        assertTrue(LlmConfigSaveValidator.problems(read("{}")).isEmpty());
    }

    @Test
    @DisplayName("an unknown provider type is refused, naming the field and the known providers (L11)")
    void unknownProvider() throws Exception {
        List<String> problems = LlmConfigSaveValidator.problems(read(task("nonexistent-provider", "{}")));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("tasks[0].type 'nonexistent-provider' is not a known LLM provider"), problems.getFirst());
        assertTrue(problems.getFirst().contains("openai"), "the message lists the providers: " + problems.getFirst());
    }

    @Test
    @DisplayName("a missing provider type is refused")
    void missingProvider() throws Exception {
        List<String> problems = LlmConfigSaveValidator.problems(read(task(null, "{}")));
        assertTrue(problems.getFirst().startsWith("tasks[0].type is required"), problems.toString());
    }

    @Test
    @DisplayName("named OpenAI-compatible providers and the built-in types are known")
    void knownProviders() {
        assertTrue(LlmConfigSaveValidator.isKnownProvider("openai"));
        assertTrue(LlmConfigSaveValidator.isKnownProvider("anthropic"));
        assertTrue(LlmConfigSaveValidator.isKnownProvider("openai-compatible"));
        assertTrue(LlmConfigSaveValidator.isKnownProvider("deepseek"), "a catalog preset is a provider too");
        assertTrue(!LlmConfigSaveValidator.isKnownProvider("OpenAI"), "lookup is exact, like the model registry's");
    }

    @Test
    @DisplayName("a provider type or parameter that is still a template is left for run time")
    void templatesAreNotChecked() throws Exception {
        assertTrue(LlmConfigSaveValidator.problems(read(task("{vars.provider}", "{\"temperature\":\"${vars:temp}\"}"))).isEmpty());
        assertTrue(LlmConfigSaveValidator.problems(read(task("openai", "{\"temperature\":\"{properties.t}\"}"))).isEmpty());
    }

    @Test
    @DisplayName("a numeric parameter outside the range the catalog declares is refused (temperature 5)")
    void outOfRange() throws Exception {
        List<String> problems = LlmConfigSaveValidator.problems(read(task("openai", "{\"modelName\":\"gpt-4o\",\"temperature\":\"5\"}")));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("tasks[0].parameters.temperature must be between 0 and 2"), problems.getFirst());
    }

    @Test
    @DisplayName("NaN and a non-number are refused; a number inside the range passes")
    void nanAndGarbage() throws Exception {
        assertEquals(1, LlmConfigSaveValidator.problems(read(task("openai", "{\"temperature\":\"NaN\"}"))).size());
        assertEquals(1, LlmConfigSaveValidator.problems(read(task("openai", "{\"temperature\":\"hot\"}"))).size());
        assertTrue(LlmConfigSaveValidator.problems(read(task("openai", "{\"temperature\":\"0.7\",\"modelName\":\"gpt-4o\"}"))).isEmpty());
    }

    @Test
    @DisplayName("a blank model name is refused; no model name at all is not")
    void blankModel() throws Exception {
        assertEquals(1, LlmConfigSaveValidator.problems(read(task("openai", "{\"modelName\":\" \"}"))).size());
        assertTrue(LlmConfigSaveValidator.problems(read(task("openai", "{}"))).isEmpty());
    }

    @Test
    @DisplayName("cascade steps are checked for their provider and parameters")
    void cascadeSteps() throws Exception {
        String json = "{\"tasks\":[{\"id\":\"t\",\"type\":\"openai\",\"parameters\":{\"modelName\":\"gpt-4o\"},"
                + "\"modelCascade\":{\"enabled\":true,\"steps\":[{\"type\":\"bogus\",\"parameters\":{}},"
                + "{\"type\":\"openai\",\"parameters\":{\"temperature\":\"9\"}}]}}]}";
        List<String> problems = LlmConfigSaveValidator.problems(read(json));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("tasks[0].modelCascade.steps[0].type 'bogus'")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("tasks[0].modelCascade.steps[1].parameters.temperature")), problems.toString());
    }

    @Test
    @DisplayName("retry settings that can never work are refused")
    void retry() throws Exception {
        String json = "{\"tasks\":[{\"id\":\"t\",\"type\":\"openai\",\"parameters\":{},\"retry\":{\"maxAttempts\":0,\"backoffDelayMs\":-5}}]}";
        List<String> problems = LlmConfigSaveValidator.problems(read(json));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("tasks[0].retry.maxAttempts must be at least 1")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("tasks[0].retry.backoffDelayMs must not be negative")), problems.toString());
    }

    @Test
    @DisplayName("interactive save throws IllegalArgumentException (400); import only warns")
    void strictVersusLenient() throws Exception {
        LlmConfiguration config = read(task("nonexistent-provider", "{}"));

        var refused = assertThrows(IllegalArgumentException.class, () -> LlmConfigSaveValidator.validate(config));
        assertTrue(refused.getMessage().contains("tasks[0].type"), refused.getMessage());

        try (var scope = ConfigValidationPolicy.lenient()) {
            assertDoesNotThrow(() -> LlmConfigSaveValidator.validate(config));
            assertEquals(1, scope.warnings().size());
            assertTrue(scope.warnings().getFirst().startsWith("LLM configuration: tasks[0].type 'nonexistent-provider'"));
        }
    }

    @Test
    @DisplayName("the shipped reference config (written by an earlier release) passes unchanged")
    void shippedFixturePasses() throws Exception {
        Path fixture = Path.of("docs", "agent-configs", "google-workspace-tools", "bbb000000000000000000001", "1",
                "bbb000000000000000000005.langchain.json");
        LlmConfiguration stored = read(Files.readString(fixture));
        assertTrue(LlmConfigSaveValidator.problems(stored).isEmpty(), LlmConfigSaveValidator.problems(stored).toString());
        assertDoesNotThrow(() -> LlmConfigSaveValidator.validate(stored));
    }
}
