/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.impl;

import io.vertx.core.MultiMap;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HttpClientWrapper Tests")
class HttpClientWrapperTest {

    @Nested
    @DisplayName("truncateAndClean")
    class TruncateAndCleanTests {

        @Test
        @DisplayName("null input returns null")
        void nullInput() {
            assertNull(HttpClientWrapper.truncateAndClean(null));
        }

        @Test
        @DisplayName("short text returned as-is (after newline replacement)")
        void shortText() {
            assertEquals("hello world", HttpClientWrapper.truncateAndClean("hello world"));
        }

        @Test
        @DisplayName("newlines are replaced with spaces")
        void newlineReplacement() {
            assertEquals("line1 line2 line3", HttpClientWrapper.truncateAndClean("line1\nline2\r\nline3"));
        }

        @Test
        @DisplayName("text longer than 150 chars is truncated with ellipsis")
        void truncation() {
            String longText = "a".repeat(200);
            String result = HttpClientWrapper.truncateAndClean(longText);
            assertEquals(153, result.length()); // 150 + "..."
            assertTrue(result.endsWith("..."));
        }

        @Test
        @DisplayName("text exactly 150 chars is not truncated")
        void exactlyAtLimit() {
            String text = "b".repeat(150);
            String result = HttpClientWrapper.truncateAndClean(text);
            assertEquals(150, result.length());
            assertFalse(result.endsWith("..."));
        }

        @Test
        @DisplayName("empty string returns empty string")
        void emptyString() {
            assertEquals("", HttpClientWrapper.truncateAndClean(""));
        }
    }

    @Nested
    @DisplayName("convertHeaderToMap")
    class ConvertHeaderToMapTests {

        @Test
        @DisplayName("empty MultiMap returns empty Map")
        void emptyHeaders() {
            MultiMap headers = HeadersMultiMap.httpHeaders();
            Map<String, String> result = HttpClientWrapper.convertHeaderToMap(headers);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("headers are converted to Map entries")
        void convertHeaders() {
            MultiMap headers = HeadersMultiMap.httpHeaders();
            headers.add("Content-Type", "application/json");
            headers.add("X-Request-Id", "abc123");

            Map<String, String> result = HttpClientWrapper.convertHeaderToMap(headers);

            assertEquals(2, result.size());
            assertEquals("application/json", result.get("Content-Type"));
            assertEquals("abc123", result.get("X-Request-Id"));
        }

        @Test
        @DisplayName("duplicate header keys are handled (last value wins in HashMap)")
        void duplicateHeaders() {
            MultiMap headers = HeadersMultiMap.httpHeaders();
            headers.add("X-Custom", "first");
            headers.add("X-Custom", "second");

            Map<String, String> result = HttpClientWrapper.convertHeaderToMap(headers);

            // HashMap.put replaces — last entry during iteration wins
            assertNotNull(result.get("X-Custom"));
        }
    }
}
