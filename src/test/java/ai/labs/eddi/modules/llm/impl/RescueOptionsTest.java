/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RescueOptions — resolution from a task and save-time validation")
class RescueOptionsTest {

    private static ResponseValidation validation(String onInvalidJson, String wrapField) {
        var v = new ResponseValidation();
        v.setEnabled(true);
        v.setOnInvalidJson(onInvalidJson);
        v.setWrapField(wrapField);
        return v;
    }

    @Test
    @DisplayName("a task without the new fields resolves to NONE")
    void unconfigured() {
        assertSame(RescueOptions.NONE, RescueOptions.of(new Task()));
        assertSame(RescueOptions.NONE, RescueOptions.of((Task) null));
        var task = new Task();
        task.setResponseValidation(new ResponseValidation());
        assertSame(RescueOptions.NONE, RescueOptions.of(task));
        assertTrue(RescueOptions.NONE.isEmpty());
        assertFalse(RescueOptions.NONE.wraps());
    }

    @Test
    @DisplayName("the shipped defaults of the response validation: no wrap field, cap 20,000")
    void defaults() {
        var v = new ResponseValidation();
        assertNull(v.getWrapField());
        assertNull(v.getWrapDefaults());
        assertEquals(20_000, v.getWrapMaxChars());
        assertEquals("ignore", v.getOnInvalidJson());
    }

    @Test
    @DisplayName("wrap turns on wrapping and the double-escape repair")
    void wrapResolves() {
        var v = validation("wrap", "htmlResponseText");
        v.setWrapDefaults(Map.of("quickReplies", List.of()));
        v.setWrapMaxChars(500);

        RescueOptions options = RescueOptions.of(v, null);

        assertEquals("htmlResponseText", options.wrapField());
        assertEquals(Map.of("quickReplies", List.of()), options.wrapDefaults());
        assertEquals(500, options.wrapMaxChars());
        assertTrue(options.doubleEscape());
    }

    @Test
    @DisplayName("retry turns on only the double-escape repair")
    void retryResolves() {
        RescueOptions options = RescueOptions.of(validation("retry", "htmlResponseText"), null);

        assertTrue(options.doubleEscape());
        assertFalse(options.wraps(), "a wrap field without onInvalidJson=wrap does nothing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ignore", "warn", "fallback", "error"})
    @DisplayName("any other onInvalidJson leaves the double-escape repair off")
    void otherActionsAreOff(String action) {
        assertSame(RescueOptions.NONE, RescueOptions.of(validation(action, "htmlResponseText"), null));
    }

    @Test
    @DisplayName("a disabled response validation switches wrap and the repair off, aliases stay")
    void disabledValidation() {
        var v = validation("wrap", "htmlResponseText");
        v.setEnabled(false);

        RescueOptions options = RescueOptions.of(v, Map.of("htmlResponseStr", "htmlResponseText"));

        assertFalse(options.wraps());
        assertFalse(options.doubleEscape());
        assertEquals(Map.of("htmlResponseStr", "htmlResponseText"), options.fieldAliases());
    }

    @Test
    @DisplayName("save: wrap without wrapField is an actionable error")
    void wrapNeedsField() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> RescueOptions.validate(validation("wrap", null), null, "langchain.task[2]"));
        assertTrue(error.getMessage().startsWith("langchain.task[2].responseValidation.onInvalidJson is \"wrap\" but wrapField is not set"),
                error.getMessage());
        assertTrue(error.getMessage().contains("htmlResponseText"), "names an example");
        assertThrows(IllegalArgumentException.class, () -> RescueOptions.validate(validation("wrap", "  "), null, "p"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b", "a b", "a[0]", "$.a", "1abc", "x\"y", "a/b"})
    @DisplayName("save: wrapField must be a plain top-level name")
    void wrapFieldMustBePlain(String name) {
        var error = assertThrows(IllegalArgumentException.class, () -> RescueOptions.validate(validation("wrap", name), null, "p"));
        assertTrue(error.getMessage().contains("plain top-level property name"), error.getMessage());
    }

    @Test
    @DisplayName("save: a plain wrapField, wrapDefaults object and aliases are accepted")
    void validConfigs() {
        var v = validation("wrap", "htmlResponseText");
        v.setWrapDefaults(Map.of("quickReplies", List.of(), "meta", Map.of("a", 1)));
        assertDoesNotThrow(() -> RescueOptions.validate(v, Map.of("htmlResponseStr", "htmlResponseText"), "p"));
        assertDoesNotThrow(() -> RescueOptions.validate(null, null, "p"));
        assertDoesNotThrow(() -> RescueOptions.validate(new ResponseValidation(), Map.of(), "p"));
        assertDoesNotThrow(() -> RescueOptions.validate(validation("wrap", "html_response-Text"), null, "p"));
    }

    @Test
    @DisplayName("save: wrapDefaults may not carry the wrap field, or a blank name; the cap must be positive")
    void wrapDefaultsAndCap() {
        var withField = validation("wrap", "htmlResponseText");
        withField.setWrapDefaults(Map.of("htmlResponseText", "x"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RescueOptions.validate(withField, null, "p")).getMessage()
                .contains("must not contain wrapField"));

        var blank = validation("wrap", "htmlResponseText");
        blank.setWrapDefaults(Map.of(" ", "x"));
        assertThrows(IllegalArgumentException.class, () -> RescueOptions.validate(blank, null, "p"));

        var cap = validation("wrap", "htmlResponseText");
        cap.setWrapMaxChars(0);
        assertThrows(IllegalArgumentException.class, () -> RescueOptions.validate(cap, null, "p"));
    }

    @Test
    @DisplayName("save: aliases map plain names to different plain names")
    void aliasValidation() {
        assertThrows(IllegalArgumentException.class, () -> RescueOptions.validate(null, Map.of("a.b", "c"), "p"));
        assertThrows(IllegalArgumentException.class, () -> RescueOptions.validate(null, Map.of("a", "b.c"), "p"));
        assertThrows(IllegalArgumentException.class, () -> RescueOptions.validate(null, Map.of("a", "a"), "p"));
        assertThrows(IllegalArgumentException.class, () -> RescueOptions.validate(null, Map.of("", "a"), "p"));
    }
}
