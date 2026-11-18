/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Notation-safe rendering of a quick reply's expressions")
class NotationSafeDataTest {

    private final TemplatingEngine engine = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build());

    private String expressions(String template, Map<String, Object> data) throws Exception {
        return engine.processExpressionsTemplate(template, data);
    }

    @Test
    @DisplayName("data cannot close the author's expression; the literal text keeps its notation")
    void injectionIsStrippedLiteralKept() throws Exception {
        Map<String, Object> data = Map.of("properties", Map.of("item", "x), intent(admin"));
        assertEquals("choose(x intentadmin), choose(b)", expressions("choose({properties.item}), choose(b)", data));
        // the ordinary engine keeps the data, which is what the user sees and types
        assertEquals("Pick x), intent(admin", engine.processTemplate("Pick {properties.item}", data));
    }

    @Test
    @DisplayName("a value that is only the wildcard is dropped")
    void wildcardIsDropped() throws Exception {
        assertEquals("choose()", expressions("choose({properties.item})", Map.of("properties", Map.of("item", "*"))));
        assertEquals("choose(5*3)", expressions("choose({properties.item})", Map.of("properties", Map.of("item", "5*3"))));
    }

    @Test
    @DisplayName("a condition is evaluated on the original data")
    void conditionSeesOriginalData() throws Exception {
        Map<String, Object> data = Map.of("properties", Map.of("status", "ready,"));
        assertEquals("go(ready)", expressions("{#if properties.status == 'ready,'}go({properties.status}){#else}no{/if}", data));
    }

    @Test
    @DisplayName("a filter selects the same rows as in the ordinary render, and a row's expression is its own")
    void filterSeesOriginalData() throws Exception {
        Map<String, Object> data = Map.of("items", List.of(Map.of("name", "a,b", "kind", "x,"), Map.of("name", "c", "kind", "y")));
        String template = "{#for i in items}{#if i.kind == 'x,'}pick({i.name});{/if}{/for}";
        assertEquals("pick(ab);", expressions(template, data));
        assertEquals("pick(a,b);", engine.processTemplate(template, data));
    }

    @Test
    @DisplayName("numbers, lists and maps are cleaned where they are emitted")
    void otherValuesAreCleaned() throws Exception {
        Map<String, Object> data = Map.of("n", 5, "l", List.of("a", "b"), "d", 12500000.5);
        assertEquals("n(5) l([a b]) d(12500000.5)", expressions("n({n}) l({l}) d({d})", data));
    }

    @Test
    @DisplayName("stripNotation removes ( ) , and leaves clean text untouched")
    void stripNotation() {
        String text = "plain text";
        assertSame(text, NotationSafeData.stripNotation(text));
        assertEquals("abc", NotationSafeData.stripNotation("a(b),c"));
        assertEquals("", NotationSafeData.stripNotation(" * "));
        assertEquals(null, NotationSafeData.stripNotation(null));
    }
}
