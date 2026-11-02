/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ArgumentFields")
class ArgumentFieldsTest {

    private static List<String> texts(List<ArgumentFields.Field> fields) {
        return fields.stream().map(ArgumentFields.Field::text).toList();
    }

    @Nested
    @DisplayName("selection")
    class Selection {

        @Test
        @DisplayName("no fields configured: every string leaf, nested ones included, numbers and booleans not")
        void allStringLeaves() throws Exception {
            var doc = ArgumentFields.parse("{\"title\":\"t\",\"n\":3,\"ok\":true,\"body\":{\"parts\":[\"a\",\"b\",7]}}");
            assertEquals(List.of("t", "a", "b"), texts(doc.select(null)));
            assertEquals(List.of("t", "a", "b"), texts(doc.select(List.of())));
        }

        @Test
        @DisplayName("a pointer selects its target; a missing path selects nothing")
        void pointerAndMissingPath() throws Exception {
            var doc = ArgumentFields.parse("{\"body\":\"hello\",\"x\":{\"a/b\":\"slash\",\"c~d\":\"tilde\"}}");
            assertEquals(List.of("hello"), texts(doc.select(List.of("/body"))));
            assertEquals(List.of(), texts(doc.select(List.of("/nope", "/body/deeper"))));
            assertEquals(List.of("slash", "tilde"), texts(doc.select(List.of("/x/a~1b", "/x/c~0d"))));
        }

        @Test
        @DisplayName("'*' is every element of an array and every member of an object")
        void wildcard() throws Exception {
            var doc = ArgumentFields.parse("{\"items\":[{\"text\":\"one\"},{\"text\":\"two\"},{\"other\":\"x\"}],\"m\":{\"a\":\"A\",\"b\":\"B\"}}");
            assertEquals(List.of("one", "two"), texts(doc.select(List.of("/items/*/text"))));
            assertEquals(List.of("A", "B"), texts(doc.select(List.of("/m/*"))));
        }

        @Test
        @DisplayName("an array index pointer")
        void arrayIndex() throws Exception {
            var doc = ArgumentFields.parse("{\"a\":[\"zero\",\"one\"]}");
            assertEquals(List.of("one"), texts(doc.select(List.of("/a/1"))));
            assertEquals(List.of(), texts(doc.select(List.of("/a/2", "/a/01"))));
        }

        @Test
        @DisplayName("\"\" is the whole document, serialized; a non-string target is its JSON")
        void wholeDocumentAndNonString() throws Exception {
            var doc = ArgumentFields.parse("{\"a\":1.50,\"b\":{\"c\":true}}");
            assertEquals(List.of("{\"a\":1.50,\"b\":{\"c\":true}}"), texts(doc.select(List.of(""))));
            assertEquals(List.of("{\"c\":true}"), texts(doc.select(List.of("/b"))));
        }

        @Test
        @DisplayName("blank arguments are the empty object; text that is not JSON is unparseable")
        void blankAndUnparseable() throws Exception {
            assertEquals(List.of(), texts(ArgumentFields.parse("  ").select(null)));
            assertThrows(ArgumentFields.UnparseableException.class, () -> ArgumentFields.parse("{not json"));
        }
    }

    @Nested
    @DisplayName("caps")
    class Caps {

        private String array(int n, int length) {
            StringBuilder sb = new StringBuilder("{\"a\":[");
            for (int i = 0; i < n; i++) {
                sb.append(i > 0 ? "," : "").append('"').append("x".repeat(length)).append('"');
            }
            return sb.append("]}").toString();
        }

        @Test
        @DisplayName("MAX_LEAVES fields pass, one more is too large")
        void leafCap() throws Exception {
            assertEquals(ArgumentFields.MAX_LEAVES, ArgumentFields.parse(array(ArgumentFields.MAX_LEAVES, 1)).select(null).size());
            assertThrows(ArgumentFields.TooLargeException.class, () -> ArgumentFields.parse(array(ArgumentFields.MAX_LEAVES + 1, 1)).select(null));
        }

        @Test
        @DisplayName("MAX_CHARS characters pass, one more is too large")
        void charCap() throws Exception {
            assertEquals(1, ArgumentFields.parse(array(1, ArgumentFields.MAX_CHARS)).select(null).size());
            assertThrows(ArgumentFields.TooLargeException.class, () -> ArgumentFields.parse(array(1, ArgumentFields.MAX_CHARS + 1)).select(null));
        }
    }

    @Nested
    @DisplayName("rewrite")
    class Rewrite {

        @Test
        @DisplayName("unchanged arguments are returned as written, byte for byte")
        void unchangedIsRaw() throws Exception {
            String raw = "{ \"b\" : 1.0,  \"a\":\"x\" }";
            var doc = ArgumentFields.parse(raw);
            doc.select(null);
            assertFalse(doc.changed());
            assertSame(raw, doc.arguments());
        }

        @Test
        @DisplayName("a rewrite keeps key order and exact numbers")
        void rewriteKeepsOrderAndNumbers() throws Exception {
            var doc = ArgumentFields.parse("{\"z\":0.10,\"body\":\"secret\",\"big\":12345678901234567890123,\"a\":1e2}");
            var body = doc.select(List.of("/body")).get(0);
            assertTrue(body.replace("[REDACTED]"));
            assertTrue(doc.changed());
            assertEquals("{\"z\":0.10,\"body\":\"[REDACTED]\",\"big\":12345678901234567890123,\"a\":1E+2}", doc.arguments());
        }

        @Test
        @DisplayName("a wildcard element is rewritten in its array")
        void rewriteArrayElement() throws Exception {
            var doc = ArgumentFields.parse("{\"items\":[\"a\",\"b\"]}");
            doc.select(List.of("/items/*")).get(1).replace("B");
            assertEquals("{\"items\":[\"a\",\"B\"]}", doc.arguments());
        }

        @Test
        @DisplayName("a non-string target cannot take a text; the whole document only JSON")
        void rewriteRefusals() throws Exception {
            var doc = ArgumentFields.parse("{\"n\":3}");
            assertFalse(doc.select(List.of("/n")).get(0).replace("three"));
            var whole = doc.select(List.of("")).get(0);
            assertFalse(whole.replace("not json {"));
            assertFalse(doc.changed());
            assertTrue(whole.replace("{\"n\":4}"));
            assertEquals("{\"n\":4}", doc.arguments());
        }
    }

    @Nested
    @DisplayName("pointer validation")
    class PointerValidation {

        @Test
        void valid() {
            assertNull(ArgumentFields.pointerProblem(""));
            assertNull(ArgumentFields.pointerProblem("/body"));
            assertNull(ArgumentFields.pointerProblem("/items/*/text"));
            assertNull(ArgumentFields.pointerProblem("/a~1b/c~0d"));
        }

        @Test
        void invalid() {
            assertNotNull(ArgumentFields.pointerProblem("body"));
            assertNotNull(ArgumentFields.pointerProblem("/a~2"));
            assertNotNull(ArgumentFields.pointerProblem("/a~"));
            assertTrue(ArgumentFields.pointerProblem("/items/x*").contains("whole segment"));
        }
    }
}
