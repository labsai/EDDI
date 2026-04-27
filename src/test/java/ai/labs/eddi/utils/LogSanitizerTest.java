package ai.labs.eddi.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LogSanitizer} — CWE-117 log injection prevention.
 * <p>
 * Targets >90% instruction and branch coverage for the sanitize() method.
 */
@DisplayName("LogSanitizer")
class LogSanitizerTest {

    @Nested
    @DisplayName("null handling")
    class NullHandling {

        @Test
        @DisplayName("should return string 'null' for null input")
        void returnsNullString() {
            assertEquals("null", sanitize(null));
        }
    }

    @Nested
    @DisplayName("newline replacement")
    class NewlineReplacement {

        @Test
        @DisplayName("should replace LF with underscore")
        void replacesLf() {
            assertEquals("hello_world", sanitize("hello\nworld"));
        }

        @Test
        @DisplayName("should replace CR with underscore")
        void replacesCr() {
            assertEquals("hello_world", sanitize("hello\rworld"));
        }

        @Test
        @DisplayName("should replace CRLF with two underscores")
        void replacesCrLf() {
            assertEquals("hello__world", sanitize("hello\r\nworld"));
        }

        @Test
        @DisplayName("should replace tab with underscore")
        void replacesTab() {
            assertEquals("hello_world", sanitize("hello\tworld"));
        }

        @Test
        @DisplayName("should replace multiple mixed control chars")
        void replacesMixed() {
            assertEquals("a_b_c_d", sanitize("a\nb\rc\td"));
        }
    }

    @Nested
    @DisplayName("control character stripping")
    class ControlCharStripping {

        @Test
        @DisplayName("should strip NUL (0x00)")
        void stripsNul() {
            assertEquals("ab", sanitize("a\u0000b"));
        }

        @Test
        @DisplayName("should strip BEL (0x07)")
        void stripsBel() {
            assertEquals("ab", sanitize("a\u0007b"));
        }

        @Test
        @DisplayName("should strip ESC (0x1B)")
        void stripsEsc() {
            assertEquals("ab", sanitize("a\u001Bb"));
        }

        @Test
        @DisplayName("should strip DEL (0x7F)")
        void stripsDel() {
            assertEquals("ab", sanitize("a\u007Fb"));
        }

        @Test
        @DisplayName("should strip all non-whitespace control chars (0x01-0x08, 0x0B-0x0C, 0x0E-0x1F)")
        void stripsAllNonWhitespaceControlChars() {
            // Build a string with every control char that is NOT \r \n \t
            StringBuilder sb = new StringBuilder("start");
            for (char c = 0x01; c <= 0x1F; c++) {
                if (c != '\r' && c != '\n' && c != '\t') {
                    sb.append(c);
                }
            }
            sb.append((char) 0x7F);
            sb.append("end");

            // All control chars should be stripped, leaving just "startend"
            assertEquals("startend", sanitize(sb.toString()));
        }
    }

    @Nested
    @DisplayName("pass-through (no modification)")
    class PassThrough {

        @Test
        @DisplayName("should pass through empty string unchanged")
        void emptyString() {
            assertEquals("", sanitize(""));
        }

        @Test
        @DisplayName("should pass through normal ASCII unchanged")
        void normalAscii() {
            assertEquals("hello world 123", sanitize("hello world 123"));
        }

        @Test
        @DisplayName("should pass through printable special characters unchanged")
        void specialChars() {
            assertEquals("foo@bar.com/path?q=1&x=2", sanitize("foo@bar.com/path?q=1&x=2"));
        }

        @Test
        @DisplayName("should pass through Unicode text unchanged")
        void unicodeText() {
            assertEquals("héllo wörld 日本語", sanitize("héllo wörld 日本語"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc-def_123", "UUID-550e8400-e29b-41d4-a716-446655440000", "tenant.default"})
        @DisplayName("should pass through typical IDs unchanged")
        void typicalIds(String id) {
            assertEquals(id, sanitize(id));
        }
    }

    @Nested
    @DisplayName("log injection attack patterns")
    class AttackPatterns {

        @Test
        @DisplayName("should neutralize fake log entry via newline injection")
        void fakeLogEntry() {
            String attack = "legit-id\n2026-04-26 INFO [FAKE] admin logged in";
            String sanitized = sanitize(attack);

            assertFalse(sanitized.contains("\n"), "newline should be replaced");
            assertTrue(sanitized.startsWith("legit-id_"), "original ID should be preserved with _ separator");
        }

        @Test
        @DisplayName("should neutralize CRLF header injection pattern")
        void crlfInjection() {
            String attack = "value\r\nX-Injected-Header: malicious";
            String sanitized = sanitize(attack);

            assertFalse(sanitized.contains("\r"), "CR should be replaced");
            assertFalse(sanitized.contains("\n"), "LF should be replaced");
        }

        @Test
        @DisplayName("should neutralize ANSI escape sequence injection")
        void ansiEscapeInjection() {
            String attack = "value\u001B[31m RED TEXT \u001B[0m";
            String sanitized = sanitize(attack);

            assertFalse(sanitized.contains("\u001B"), "ESC should be stripped");
        }
    }

    @Nested
    @DisplayName("combined behavior (two-pass ordering)")
    class TwoPassOrdering {

        @Test
        @DisplayName("first pass replaces CR/LF/tab with _, second pass strips remaining control chars")
        void twoPassOrder() {
            // \n → _, \u0007 → stripped
            assertEquals("a_bc", sanitize("a\nb\u0007c"));
        }

        @Test
        @DisplayName("tab is replaced not stripped — verifying two-pass distinction")
        void tabReplacedNotStripped() {
            // If it were single-pass [0x00-0x1F], tab would be stripped.
            // Two-pass ensures tab (0x09) → '_' instead.
            String result = sanitize("col1\tcol2");
            assertEquals("col1_col2", result);
            assertTrue(result.contains("_"), "tab should become underscore, not be removed");
        }
    }
}
