/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.modules.llm.model.ToolDisplayConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolDisplayConfigValidator")
class ToolDisplayConfigValidatorTest {

    private static final String PATH = "langchain.task[0].toolDisplay";

    private static ToolDisplayConfig config(String label, String... fields) {
        var config = new ToolDisplayConfig();
        config.setLabel(label);
        config.setFields(fields.length == 0 ? null : List.of(fields));
        return config;
    }

    private static String refusal(String tool, ToolDisplayConfig config) {
        Map<String, ToolDisplayConfig> map = new HashMap<>();
        map.put(tool, config);
        return assertThrows(IllegalArgumentException.class, () -> ToolDisplayConfigValidator.validate(map, PATH)).getMessage();
    }

    @Test
    @DisplayName("absent and empty settings are valid")
    void absentIsValid() {
        assertDoesNotThrow(() -> ToolDisplayConfigValidator.validate(null, PATH));
        assertDoesNotThrow(() -> ToolDisplayConfigValidator.validate(Map.of(), PATH));
    }

    @Test
    @DisplayName("a label, fields, or both are valid")
    void validShapes() {
        assertDoesNotThrow(() -> ToolDisplayConfigValidator.validate(Map.of("a", config("Creating an action")), PATH));
        assertDoesNotThrow(() -> ToolDisplayConfigValidator.validate(Map.of("a", config(null, "title")), PATH));
        assertDoesNotThrow(() -> ToolDisplayConfigValidator.validate(Map.of("a", config("L", "x", "y", "z")), PATH));
        assertDoesNotThrow(() -> ToolDisplayConfigValidator.validate(Map.of("a", config("x".repeat(120))), PATH));
    }

    @Test
    @DisplayName("a label over 120 characters is refused")
    void longLabel() {
        assertTrue(refusal("a", config("x".repeat(121))).contains("label is longer than 120"));
    }

    @Test
    @DisplayName("a blank label is refused")
    void blankLabel() {
        assertTrue(refusal("a", config("   ", "title")).contains("label is blank"));
    }

    @Test
    @DisplayName("more than 3 fields is refused")
    void tooManyFields() {
        assertTrue(refusal("a", config("L", "a", "b", "c", "d")).contains("at most 3"));
    }

    @Test
    @DisplayName("a blank or duplicate field name is refused")
    void badFieldNames() {
        assertTrue(refusal("a", config("L", "title", " ")).contains("blank argument name"));
        assertTrue(refusal("a", config("L", "title", "title")).contains("twice"));
        var withNull = new ToolDisplayConfig();
        withNull.setFields(Arrays.asList("title", null));
        assertTrue(refusal("a", withNull).contains("blank argument name"));
    }

    @Test
    @DisplayName("an over-long field or tool name is refused")
    void longNames() {
        assertTrue(refusal("a", config("L", "f".repeat(129))).contains("argument name longer than 128"));
        assertTrue(refusal("t".repeat(129), config("L")).contains("longer than 128"));
    }

    @Test
    @DisplayName("a blank tool name, a null entry, and an entry that shows nothing are refused")
    void structuralProblems() {
        assertTrue(refusal(" ", config("L")).contains("blank tool name"));
        assertTrue(refusal("a", null).contains("must not be null"));
        assertTrue(refusal("a", config(null)).contains("shows nothing"));
        var emptyFields = new ToolDisplayConfig();
        emptyFields.setFields(List.of());
        assertTrue(refusal("a", emptyFields).contains("shows nothing"));
    }

    @Test
    @DisplayName("the number of entries is bounded at 100")
    void entryCap() {
        Map<String, ToolDisplayConfig> hundred = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            hundred.put("tool" + i, config("L"));
        }
        assertDoesNotThrow(() -> ToolDisplayConfigValidator.validate(hundred, PATH));
        hundred.put("one-too-many", config("L"));
        var message = assertThrows(IllegalArgumentException.class, () -> ToolDisplayConfigValidator.validate(hundred, PATH)).getMessage();
        assertTrue(message.contains("101 entries") && message.contains("at most 100"), message);
    }

    @Test
    @DisplayName("the message names the field path")
    void messageNamesThePath() {
        assertTrue(refusal("createAction", config("x".repeat(121))).contains(PATH + "['createAction']"));
    }
}
