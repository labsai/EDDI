/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("NotationSafeData")
class NotationSafeDataTest {

    @Test
    @DisplayName("strings lose ( ) and , wherever they are nested")
    void stripsNested() {
        Map<String, Object> data = Map.of("properties", Map.of("item", "x), intent(admin"), "list", List.of("a,b", Map.of("k", "(v)")), "n", 5);

        var safe = NotationSafeData.wrap(data);

        assertEquals("x intentadmin", ((Map<?, ?>) safe.get("properties")).get("item"));
        var list = (List<?>) safe.get("list");
        assertEquals("ab", list.get(0));
        assertEquals("v", ((Map<?, ?>) list.get(1)).get("k"));
        assertEquals(5, safe.get("n"));
    }

    @Test
    @DisplayName("the original data is not modified")
    void originalUntouched() {
        Map<String, Object> inner = Map.of("item", "x), y");
        Map<String, Object> data = Map.of("properties", inner);

        NotationSafeData.wrap(data).get("properties");

        assertEquals("x), y", inner.get("item"));
    }

    @Test
    @DisplayName("null and absent values stay null")
    void nulls() {
        assertNull(NotationSafeData.wrap(null));
        assertNull(NotationSafeData.wrap(Map.of()).get("missing"));
    }

    @Test
    @DisplayName("a string without notation is returned as is")
    void unchanged() {
        String text = "plain";
        assertSame(text, NotationSafeData.stripNotation(text));
    }

    @Test
    @DisplayName("a data value that is only the wildcard disappears; a * inside other text stays")
    void wildcard() {
        var safe = NotationSafeData.wrap(Map.of("a", "*", "b", " * ", "c", "5*3", "d", "x), *"));

        assertEquals("", safe.get("a"));
        assertEquals("", safe.get("b"));
        assertEquals("5*3", safe.get("c"));
        assertEquals("x *", safe.get("d"));
    }
}
