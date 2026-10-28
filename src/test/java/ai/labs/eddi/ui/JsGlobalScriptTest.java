/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsGlobalScriptTest {

    @Test
    void buildsTheFamiliarShape() {
        String js = JsGlobalScript.assign("__X__").put("a", "b").put("on", true).put("n", 7L)
                .putObject("o", o -> o.put("k", (String) null)).toJs();
        assertEquals("window.__X__={a:\"b\",on:true,n:7,o:{k:null}};", js);
    }

    @Test
    void familiarEscapesAreKept() {
        assertEquals("\"a\\\\b\\\"c\\nd\\re\\tf\"", JsGlobalScript.stringLiteral("a\\b\"c\nd\re\tf"));
    }

    @Test
    void markupQuotesControlsAndLineSeparatorsAreUnicodeEscaped() {
        assertEquals("\"\\u003c/script\\u003e\"", JsGlobalScript.stringLiteral("</script>"));
        assertEquals("\"\\u0026\\u0027\\u0000\\u001f\\u007f\\u2028\\u2029\"",
                JsGlobalScript.stringLiteral("&'\u0000\u001f\u007f\u2028\u2029"));
        assertEquals("null", JsGlobalScript.stringLiteral(null));
        assertEquals("\"Ünïcødé 名前\"", JsGlobalScript.stringLiteral("Ünïcødé 名前"));
    }

    @Test
    void keysAndGlobalsMustBeIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> JsGlobalScript.assign("x;alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> JsGlobalScript.assign("ok").put("a:b", "c"));
        assertThrows(IllegalArgumentException.class, () -> JsGlobalScript.assign(null));
    }
}
