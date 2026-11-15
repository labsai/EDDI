/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AnswerToolConfigValidator — save-time answerMode checks")
class AnswerToolConfigValidatorTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"htmlResponseText\":{\"type\":\"string\"}}}";

    private static LlmConfiguration.Task toolTask(String convertToObject, String schema) {
        var task = new LlmConfiguration.Task();
        task.setAnswerMode("tool");
        Map<String, String> parameters = new HashMap<>();
        if (convertToObject != null) {
            parameters.put("convertToObject", convertToObject);
        }
        if (schema != null) {
            parameters.put("responseSchema", schema);
        }
        task.setParameters(parameters);
        return task;
    }

    private static String refusal(LlmConfiguration.Task task) {
        return assertThrows(IllegalArgumentException.class, () -> AnswerToolConfigValidator.validate(task, "langchain.task[0]")).getMessage();
    }

    @Test
    @DisplayName("an old config without answerMode, and text mode, are valid without any schema")
    void absentAndTextAreValid() {
        assertDoesNotThrow(() -> AnswerToolConfigValidator.validate(new LlmConfiguration.Task(), "t"));
        var text = new LlmConfiguration.Task();
        text.setAnswerMode("text");
        assertDoesNotThrow(() -> AnswerToolConfigValidator.validate(text, "t"));
        assertFalse(AnswerToolConfigValidator.isToolMode(text));
    }

    @Test
    @DisplayName("tool mode with convertToObject and an object-root schema is valid; defaults are reply and required")
    void validToolMode() {
        var task = toolTask("true", SCHEMA);
        assertDoesNotThrow(() -> AnswerToolConfigValidator.validate(task, "t"));
        assertTrue(AnswerToolConfigValidator.isToolMode(task));
        assertEquals("reply", AnswerToolConfigValidator.toolName(task));
        assertTrue(AnswerToolConfigValidator.forcesToolCall(task));
        task.setAnswerToolChoice("auto");
        assertFalse(AnswerToolConfigValidator.forcesToolCall(task));
    }

    @Test
    @DisplayName("unknown tokens are refused")
    void unknownTokens() {
        var task = toolTask("true", SCHEMA);
        task.setAnswerMode("function");
        assertTrue(refusal(task).contains("answerMode"));
        task.setAnswerMode("tool");
        task.setAnswerToolChoice("always");
        assertTrue(refusal(task).contains("answerToolChoice"));
        task.setAnswerToolChoice(null);
        task.setAnswerToolName("my reply!");
        assertTrue(refusal(task).contains("answerToolName"));
    }

    @Test
    @DisplayName("a blank answerToolName saves as the default reply, as the runtime reads it; collisions still use reply")
    void blankNameIsTheDefault() {
        var task = toolTask("true", SCHEMA);
        task.setAnswerToolName("");
        assertDoesNotThrow(() -> AnswerToolConfigValidator.validate(task, "t"));
        task.setAnswerToolName("   ");
        assertDoesNotThrow(() -> AnswerToolConfigValidator.validate(task, "t"));
        assertEquals("reply", AnswerToolConfigValidator.toolName(task));

        task.setToolRateLimits(Map.of("reply", 5));
        assertTrue(refusal(task).contains("toolRateLimits"));
    }

    @Test
    @DisplayName("tool mode without convertToObject, without a schema, or with a non-object root is refused")
    void needsConvertToObjectAndObjectSchema() {
        assertTrue(refusal(toolTask(null, SCHEMA)).contains("convertToObject"));
        assertTrue(refusal(toolTask("false", SCHEMA)).contains("convertToObject"));
        assertTrue(refusal(toolTask("true", null)).contains("responseSchema"));
        assertTrue(refusal(toolTask("true", "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}")).contains("object"));
        assertTrue(refusal(toolTask("true", "{\"type\":\"object\",\"properties\":{\"a\":{\"anyOf\":[]}}}")).contains("object"));
    }

    @Test
    @DisplayName("a reply tool name that collides with another tool of the task is refused")
    void nameCollisionRefused() {
        var task = toolTask("true", SCHEMA);
        task.setAnswerToolName("calculate");
        assertTrue(refusal(task).contains("built-in tool"));

        task.setAnswerToolName("discover_tools");
        assertTrue(refusal(task).contains("built-in tool"));

        task.setAnswerToolName("reply");
        task.setToolRateLimits(Map.of("reply", 5));
        assertTrue(refusal(task).contains("toolRateLimits"));

        task.setToolRateLimits(null);
        task.setBuiltInToolsWhitelist(List.of("reply"));
        assertTrue(refusal(task).contains("builtInToolsWhitelist"));

        task.setBuiltInToolsWhitelist(null);
        task.setToolPricing(Map.of("reply", 0.1));
        assertTrue(refusal(task).contains("toolPricing"));
    }

    @Test
    @DisplayName("the engine tool names are read from the tool classes")
    void engineToolNames() {
        assertTrue(AnswerToolConfigValidator.engineToolNames().contains("calculate"));
        assertTrue(AnswerToolConfigValidator.engineToolNames().contains("getCurrentDateTime"));
        assertFalse(AnswerToolConfigValidator.engineToolNames().contains("reply"));
    }
}
