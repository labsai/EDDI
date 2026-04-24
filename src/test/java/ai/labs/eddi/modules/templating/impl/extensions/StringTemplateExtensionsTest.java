/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl.extensions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StringTemplateExtensions} — Qute template extension methods
 * for String operations.
 */
@DisplayName("StringTemplateExtensions")
class StringTemplateExtensionsTest {

    @Nested
    @DisplayName("Case conversion")
    class CaseConversion {
        @Test
        void toLowerCase() {
            assertEquals("hello", StringTemplateExtensions.toLowerCase("HELLO"));
        }
        @Test
        void toLowerCaseNull() {
            assertNull(StringTemplateExtensions.toLowerCase(null));
        }
        @Test
        void toUpperCase() {
            assertEquals("HELLO", StringTemplateExtensions.toUpperCase("hello"));
        }
        @Test
        void toUpperCaseNull() {
            assertNull(StringTemplateExtensions.toUpperCase(null));
        }
    }

    @Nested
    @DisplayName("Search & replace")
    class SearchReplace {
        @Test
        void replace() {
            assertEquals("hi world", StringTemplateExtensions.replace("hello world", "hello", "hi"));
        }
        @Test
        void replaceNull() {
            assertNull(StringTemplateExtensions.replace(null, "a", "b"));
        }
        @Test
        void contains() {
            assertTrue(StringTemplateExtensions.contains("hello world", "world"));
        }
        @Test
        void containsFalse() {
            assertFalse(StringTemplateExtensions.contains("hello", "world"));
        }
        @Test
        void containsNull() {
            assertFalse(StringTemplateExtensions.contains(null, "x"));
        }
        @Test
        void indexOf() {
            assertEquals(6, StringTemplateExtensions.indexOf("hello world", "world"));
        }
        @Test
        void indexOfNull() {
            assertEquals(-1, StringTemplateExtensions.indexOf(null, "x"));
        }
        @Test
        void lastIndexOf() {
            assertEquals(8, StringTemplateExtensions.lastIndexOf("hello x x", "x"));
        }
        @Test
        void lastIndexOfNull() {
            assertEquals(-1, StringTemplateExtensions.lastIndexOf(null, "x"));
        }
        @Test
        void startsWith() {
            assertTrue(StringTemplateExtensions.startsWith("hello", "he"));
        }
        @Test
        void startsWithFalse() {
            assertFalse(StringTemplateExtensions.startsWith("hello", "xx"));
        }
        @Test
        void startsWithNull() {
            assertFalse(StringTemplateExtensions.startsWith(null, "x"));
        }
        @Test
        void endsWith() {
            assertTrue(StringTemplateExtensions.endsWith("hello", "lo"));
        }
        @Test
        void endsWithNull() {
            assertFalse(StringTemplateExtensions.endsWith(null, "x"));
        }
    }

    @Nested
    @DisplayName("Substring")
    class Substring {
        @Test
        void substringFrom() {
            assertEquals("world", StringTemplateExtensions.substring("hello world", 6));
        }
        @Test
        void substringNull() {
            assertNull(StringTemplateExtensions.substring(null, 0));
        }
        @Test
        void substringRange() {
            assertEquals("ell", StringTemplateExtensions.substringRange("hello", 1, 4));
        }
        @Test
        void substringRangeNull() {
            assertNull(StringTemplateExtensions.substringRange(null, 0, 3));
        }
    }

    @Nested
    @DisplayName("Trimming")
    class Trimming {
        @Test
        void trim() {
            assertEquals("hello", StringTemplateExtensions.trim("  hello  "));
        }
        @Test
        void trimNull() {
            assertNull(StringTemplateExtensions.trim(null));
        }
        @Test
        void strip() {
            assertEquals("hello", StringTemplateExtensions.strip("  hello  "));
        }
        @Test
        void stripNull() {
            assertNull(StringTemplateExtensions.strip(null));
        }
    }

    @Nested
    @DisplayName("Length & char access")
    class LengthAndChar {
        @Test
        void length() {
            assertEquals(5, StringTemplateExtensions.length("hello"));
        }
        @Test
        void lengthNull() {
            assertEquals(0, StringTemplateExtensions.length(null));
        }
        @Test
        void isEmpty() {
            assertTrue(StringTemplateExtensions.isEmpty(""));
        }
        @Test
        void isEmptyNull() {
            assertTrue(StringTemplateExtensions.isEmpty(null));
        }
        @Test
        void isEmptyFalse() {
            assertFalse(StringTemplateExtensions.isEmpty("x"));
        }
        @Test
        void charAt() {
            assertEquals('e', StringTemplateExtensions.charAt("hello", 1));
        }
    }

    @Nested
    @DisplayName("Concatenation")
    class Concat {
        @Test
        void concat() {
            assertEquals("helloworld", StringTemplateExtensions.concat("hello", "world"));
        }
        @Test
        void concatNull() {
            assertEquals("world", StringTemplateExtensions.concat(null, "world"));
        }
    }
}
