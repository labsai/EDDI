/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escaping here is not cosmetic: both storage backends treat a
 * {@code String} query filter as a <em>regular expression</em>, so an unescaped
 * identity predicate is a cross-user data leak rather than a style problem.
 * These tests exist to fail if that escaping is ever removed.
 */
class SubjectsTest {

    @Nested
    @DisplayName("token encoding")
    class Encoding {

        @Test
        @DisplayName("a principal containing the delimiter cannot forge extra tokens")
        void encodesDelimiter() {
            String forged = Subjects.user("alice|user:admin");
            assertFalse(forged.contains("|"), "an encoded subject must contain no raw delimiter: " + forged);
            assertEquals("user:alice%7Cuser:admin", forged);
        }

        @Test
        @DisplayName("percent is escaped so encoding is reversible")
        void roundTripsPercent() {
            String raw = "a%7Cb";
            String encoded = Subjects.encode(raw);
            assertEquals("a%257Cb", encoded);
            assertEquals(raw, Subjects.decode(encoded), "decoding must not turn a literal %7C into a delimiter");
        }

        @Test
        @DisplayName("ordinary principals are left alone")
        void leavesOrdinaryPrincipalsAlone() {
            assertEquals("user:alice@example.com", Subjects.user("alice@example.com"));
            assertEquals("team:engineering", Subjects.team("/engineering/"));
            assertEquals("team:engineering/backend", Subjects.team("/engineering/backend"));
        }

        @Test
        @DisplayName("blank and slash-only inputs yield no subject at all")
        void rejectsBlank() {
            assertNull(Subjects.user(""));
            assertNull(Subjects.user(null));
            assertNull(Subjects.team("   "));
            assertNull(Subjects.team("///"), "a path of nothing but separators names no group");
        }
    }

    @Nested
    @DisplayName("regex safety")
    class RegexSafety {

        /**
         * MongoDB uses PCRE and PostgreSQL POSIX ERE; Java's Pattern stands in for
         * both.
         */
        private boolean matches(String pattern, String accessIndex) {
            return Pattern.compile(pattern).matcher(accessIndex).find();
        }

        @Test
        @DisplayName("a token pattern does not match a longer principal that contains it")
        void doesNotMatchSubstring() {
            String pattern = Subjects.tokenPattern(Subjects.user("alice"));
            assertTrue(matches(pattern, "|user:alice|"), "must match the exact subject");
            assertFalse(matches(pattern, "|user:malice|"), "must not match a principal that merely contains it");
            assertFalse(matches(pattern, "|user:alice2|"), "must not match a longer principal with the same prefix");
        }

        @Test
        @DisplayName("a dot in an email address is a literal, not a wildcard")
        void escapesDot() {
            String pattern = Subjects.tokenPattern(Subjects.user("a.c@example.com"));
            assertTrue(matches(pattern, "|user:a.c@example.com|"));
            assertFalse(matches(pattern, "|user:abc@example.com|"), "the dot must not match an arbitrary character");
        }

        @Test
        @DisplayName("regex metacharacters in a principal cannot alter the predicate")
        void escapesMetacharacters() {
            String pattern = Subjects.tokenPattern(Subjects.user("a|b"));
            // The pipe is encoded away first, so alternation is impossible even before
            // escaping — belt and braces, and this asserts both halves hold together.
            assertFalse(matches(pattern, "|user:b|"), "an alternation must not be smuggled in through a principal name");
            assertTrue(matches(pattern, "|user:a%7Cb|"));
        }

        @Test
        @DisplayName("an exact pattern is anchored at both ends")
        void exactPatternIsAnchored() {
            String pattern = Subjects.exactPattern("user:alice");
            assertTrue(matches(pattern, "user:alice"));
            assertFalse(matches(pattern, "user:alice2"));
            assertFalse(matches(pattern, "xuser:alice"));
        }

        @Test
        @DisplayName("escaping is confined to metacharacters both engines agree on")
        void escapesOnlySharedMetacharacters() {
            // Escaping an ordinary character is undefined in POSIX ERE, so it must not
            // happen: this is what keeps the predicate portable across the two backends.
            assertEquals("abc_1-2", Subjects.escapeRegex("abc_1-2"));
            assertEquals("\\.\\^\\$\\*\\+\\?\\(\\)\\[\\]\\{\\}\\|\\\\", Subjects.escapeRegex(".^$*+?()[]{}|\\"));
        }
    }
}
