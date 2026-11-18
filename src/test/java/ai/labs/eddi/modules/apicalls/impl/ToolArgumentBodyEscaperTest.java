/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import io.quarkus.qute.Engine;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArgumentBodyEscaperTest {

    private static final Engine QUTE = Engine.builder().addDefaults().build();

    private static String render(String body, Map<String, Object> data, Set<String> names, Map<String, Object> schema) {
        Map<String, Object> copy = new HashMap<>(data);
        String template = ToolArgumentBodyEscaper.prepare(body, copy, names, schema);
        return QUTE.parse(template != null ? template : body).data(copy).render();
    }

    @Test
    void jsonMediaTypes() {
        assertTrue(ToolArgumentBodyEscaper.isJsonContentType("application/json"));
        assertTrue(ToolArgumentBodyEscaper.isJsonContentType("Application/JSON; charset=utf-8"));
        assertTrue(ToolArgumentBodyEscaper.isJsonContentType("application/problem+json"));
        assertTrue(ToolArgumentBodyEscaper.isJsonContentType("text/json"));
        assertFalse(ToolArgumentBodyEscaper.isJsonContentType("text/plain"));
        assertFalse(ToolArgumentBodyEscaper.isJsonContentType("multipart/related; type=\"application/json\""));
        assertFalse(ToolArgumentBodyEscaper.isJsonContentType(null));
    }

    @Test
    void escapesInsideALiteralAndLeavesTheRestAlone() {
        String out = render("{\"a\":\"{x}\",\"b\":\"{properties.p}\"}",
                Map.of("x", "q\"\\\n\u0000é", "properties", Map.of("p", "raw\"")), Set.of("x"), null);
        assertEquals("{\"a\":\"q\\\"\\\\\\n\\u0000é\",\"b\":\"raw\"\"}", out);
    }

    @Test
    void escapedEscapeInTheTemplateDoesNotFlipLiteralState() {
        // the \" inside the literal is part of the author's text; {x} after it is still
        // inside
        String out = render("{\"a\":\"say \\\"hi\\\" {x}\"}", Map.of("x", "\","), Set.of("x"), null);
        assertEquals("{\"a\":\"say \\\"hi\\\" \\\",\"}", out);
    }

    @Test
    void bareValuesFollowTheDeclaredType() {
        Map<String, Object> schema = Map.of("properties", Map.of("n", Map.of("type", "integer"), "s", Map.of("type", "string"),
                "o", Map.of("type", "object")));
        assertEquals("{\"n\":7,\"s\":\"1,2\",\"o\":{\"k\":[1,2]}}",
                render("{\"n\":{n},\"s\":{s},\"o\":{o}}", Map.of("n", "7", "s", "1,2", "o", "{ \"k\" : [1, 2] }"), Set.of("n", "s", "o"), schema));
        assertThrows(ToolArgumentBodyEscaper.ArgumentRefusedException.class,
                () -> render("{\"n\":{n}}", Map.of("n", "7,\"role\":\"admin\""), Set.of("n"), schema));
        assertThrows(ToolArgumentBodyEscaper.ArgumentRefusedException.class,
                () -> render("{\"n\":{n}}", Map.of("n", "7.5"), Set.of("n"), schema));
        assertThrows(ToolArgumentBodyEscaper.ArgumentRefusedException.class,
                () -> render("{\"o\":{o}}", Map.of("o", "[1]"), Set.of("o"), schema));
    }

    @Test
    void refusalNeverEchoesTheValue() {
        Map<String, Object> schema = Map.of("properties", Map.of("n", Map.of("type", "integer")));
        var refused = assertThrows(ToolArgumentBodyEscaper.ArgumentRefusedException.class,
                () -> render("{\"n\":{n}}", Map.of("n", "SUPERSECRET"), Set.of("n"), schema));
        assertFalse(refused.getMessage().contains("SUPERSECRET"));
        assertTrue(refused.getMessage().contains("'n'"));
    }

    @Test
    void headerValuesWithLineBreaksAreRefusedOnlyWhereTheArgumentIsRead() {
        Map<String, Object> data = Map.of("trace", "a\r\nX: 1", "other", "fine");
        assertThrows(ToolArgumentBodyEscaper.ArgumentRefusedException.class,
                () -> ToolArgumentBodyEscaper.rejectControlCharactersInHeaders(Map.of("X-Trace", "id-{trace}"), data, Set.of("trace", "other")));
        ToolArgumentBodyEscaper.rejectControlCharactersInHeaders(Map.of("X-Trace", "id-{other}"), data, Set.of("trace", "other"));
        ToolArgumentBodyEscaper.rejectControlCharactersInHeaders(Map.of("X-Trace", "id-{tracer}"), data, Set.of("trace"));
        assertNotNull(ToolArgumentBodyEscaper.expressionRoots("{\"a\":\"{x.y}\"}"));
    }
}
