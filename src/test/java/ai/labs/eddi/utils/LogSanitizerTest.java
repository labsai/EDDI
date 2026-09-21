package ai.labs.eddi.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static ai.labs.eddi.utils.LogSanitizer.escapeRecordBoundaries;
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

        @Test
        @DisplayName("should strip Unicode Line Separator (U+2028)")
        void stripsLineSeparator() {
            assertEquals("ab", sanitize("a\u2028b"));
        }

        @Test
        @DisplayName("should strip Unicode Paragraph Separator (U+2029)")
        void stripsParagraphSeparator() {
            assertEquals("ab", sanitize("a\u2029b"));
        }

        @Test
        @DisplayName("should strip mixed Unicode and ASCII line separators")
        void stripsMixedSeparators() {
            assertEquals("a_b_cd", sanitize("a\nb\rc\u2028d"));
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

    @Nested
    @DisplayName("escapeRecordBoundaries — the record-level rule")
    class EscapeRecordBoundaries {

        /**
         * Built at runtime rather than written as a unicode escape: the project
         * formatter turns such an escape back into the raw character, and a raw U+2028
         * sitting in a .java file is a thing editors and reviewers mangle.
         */
        private final String lineSeparator = Character.toString(0x2028);

        private final String paragraphSeparator = Character.toString(0x2029);

        @Test
        @DisplayName("null stays null, because a throwable's message is legitimately null")
        void nullStaysNull() {
            // sanitize() renders null as the string "null"; doing that here would turn
            // a printed "java.io.IOException" into "java.io.IOException: null".
            assertNull(escapeRecordBoundaries(null));
        }

        @Test
        @DisplayName("LF and CR become escapes rather than underscores — the text has to survive")
        void escapesRatherThanDestroys() {
            assertEquals("a\\nb", escapeRecordBoundaries("a\nb"));
            assertEquals("a\\rb", escapeRecordBoundaries("a\rb"));
            assertEquals("a\\r\\nb", escapeRecordBoundaries("a\r\nb"));
        }

        @Test
        @DisplayName("a forged record is neutralized but still legible")
        void neutralizesAForgedRecord() {
            String escaped = escapeRecordBoundaries("boom" + LogCaptureSupport.FORGED_RECORD);

            assertFalse(escaped.contains("\n"), "no LF may survive: " + escaped);
            assertFalse(escaped.contains("\r"), "no CR may survive: " + escaped);
            assertTrue(escaped.contains("Forged admin login succeeded"),
                    "and the payload stays readable, so the line keeps the diagnostic it was emitted for: " + escaped);
        }

        @Test
        @DisplayName("TAB survives, because it cannot end a record and it indents stack frames")
        void keepsTab() {
            assertEquals("col1\tcol2", escapeRecordBoundaries("col1\tcol2"));
        }

        @Test
        @DisplayName("other control characters become \\uXXXX escapes")
        void escapesOtherControlCharacters() {
            assertEquals("a\\u001bb", escapeRecordBoundaries("a\u001Bb"));
            assertEquals("a\\u0000b", escapeRecordBoundaries("a\u0000b"));
        }

        @Test
        @DisplayName("the Unicode line and paragraph separators are escaped too")
        void escapesUnicodeSeparators() {
            assertEquals("a\\u2028b", escapeRecordBoundaries("a" + lineSeparator + "b"));
            assertEquals("a\\u2029b", escapeRecordBoundaries("a" + paragraphSeparator + "b"));
        }

        @Test
        @DisplayName("text with nothing to escape comes back as the very same instance")
        void returnsTheSameInstanceWhenNothingToDo() {
            // The overwhelming majority of log records take this path, and the caller
            // decides whether a record needs rewriting by comparing what came back.
            String clean = "started listening on port 7070";
            assertSame(clean, escapeRecordBoundaries(clean));
        }

        @Test
        @DisplayName("a backslash is left alone, so Windows paths in exception messages stay readable")
        void doesNotDoubleBackslashes() {
            assertEquals("C:\\dev\\git", escapeRecordBoundaries("C:\\dev\\git"));
        }
    }
}
