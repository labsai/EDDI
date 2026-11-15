/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RescueOptions — recoverFields and onUnwrappable")
class RescueOptionsRecoveryTest {

    private static ResponseValidation validation(String onInvalidJson) {
        var v = new ResponseValidation();
        v.setEnabled(true);
        v.setOnInvalidJson(onInvalidJson);
        return v;
    }

    private static String message(Runnable r) {
        return assertThrows(IllegalArgumentException.class, r::run).getMessage();
    }

    @Test
    @DisplayName("recoverFields resolve into the options, without wrap, and keep wrapDefaults")
    void resolves() {
        var v = validation("ignore");
        v.setRecoverFields(List.of("htmlResponseText", "htmlResponseText", "summary"));
        v.setWrapDefaults(Map.of("quickReplies", List.of()));

        RescueOptions options = RescueOptions.of(v, null);

        assertTrue(options.recovers());
        assertTrue(options.rewritesReply());
        assertFalse(options.wraps());
        assertFalse(options.isEmpty());
        assertEquals(List.of("htmlResponseText", "summary"), options.recoverFields(), "duplicates collapse, order is kept");
        assertEquals(Map.of("quickReplies", List.of()), options.wrapDefaults());
    }

    @Test
    @DisplayName("no recoverFields: NONE, as before; a disabled validation recovers nothing")
    void unset() {
        assertSame(RescueOptions.NONE, RescueOptions.of(validation("ignore"), null));
        var empty = validation("ignore");
        empty.setRecoverFields(List.of());
        assertSame(RescueOptions.NONE, RescueOptions.of(empty, null));
        var disabled = validation("ignore");
        disabled.setEnabled(false);
        disabled.setRecoverFields(List.of("a"));
        assertSame(RescueOptions.NONE, RescueOptions.of(disabled, null));
    }

    @Test
    @DisplayName("save: recoverFields are plain names, at most ten, and need convertToObject")
    void recoverFieldsValidation() {
        var dotted = validation("wrap");
        dotted.setWrapField("a");
        dotted.setRecoverFields(List.of("answer.text"));
        assertTrue(message(() -> RescueOptions.validate(dotted, null, "p")).contains("recoverFields entry \"answer.text\""));

        var many = validation("ignore");
        var names = new ArrayList<String>();
        for (int i = 0; i < 11; i++) {
            names.add("f" + i);
        }
        many.setRecoverFields(names);
        assertTrue(message(() -> RescueOptions.validate(many, null, "p")).contains("the limit is 10"));

        var ok = validation("ignore");
        ok.setRecoverFields(List.of("htmlResponseText"));
        assertDoesNotThrow(() -> RescueOptions.validate(ok, null, "p"));
        assertTrue(message(() -> RescueOptions.validate(ok, null, "p", false)).contains("needs the task parameter convertToObject"));
    }

    @Test
    @DisplayName("save: onUnwrappable is fallback or retry, and retry needs wrap and convertToObject")
    void onUnwrappableValidation() {
        var unknown = validation("wrap");
        unknown.setWrapField("a");
        unknown.setOnUnwrappable("error");
        assertTrue(message(() -> RescueOptions.validate(unknown, null, "p")).contains("is not one of"));

        var notWrap = validation("retry");
        notWrap.setOnUnwrappable("retry");
        assertTrue(message(() -> RescueOptions.validate(notWrap, null, "p")).contains("only applies with onInvalidJson \"wrap\""));

        var ignore = validation("ignore");
        ignore.setOnUnwrappable("retry");
        assertThrows(IllegalArgumentException.class, () -> RescueOptions.validate(ignore, null, "p"));

        var ok = validation("wrap");
        ok.setWrapField("a");
        ok.setOnUnwrappable("RETRY");
        assertDoesNotThrow(() -> RescueOptions.validate(ok, null, "p"));
        assertTrue(message(() -> RescueOptions.validate(ok, null, "p", false)).contains("needs the task parameter convertToObject"));

        var fallback = validation("ignore");
        fallback.setOnUnwrappable("fallback");
        assertDoesNotThrow(() -> RescueOptions.validate(fallback, null, "p", false));
    }
}
