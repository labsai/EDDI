/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The self-ungating heuristics test whether a request names an id after the
 * receiver has decoded it. Both guards decode through {@link GuardText}, so
 * they see the same request the same way.
 */
@DisplayName("GuardText — one decoding for the self-ungating heuristics")
class GuardTextTest {

    private static final String ID = "0123456789abcdef01234567";
    private static final String OTHER_ID = "ffffeeeeddddccccbbbbaaaa";
    /**
     * Built from its code point so that no source tool reads it as an escape; still
     * a constant.
     */
    private static final String BACKSLASH = "" + (char) 92;
    /** {@code 4} as a JSON unicode escape; the receiver reads it as {@code 4}. */
    private static final String ESCAPED_FOUR = BACKSLASH + "u0034";

    @Nested
    @DisplayName("decoding")
    class Decoding {

        @Test
        @DisplayName("percent-decodes and lower-cases; a malformed escape keeps the raw text")
        void percentDecoding() {
            assertEquals("agents/abc", GuardText.decodeLower("agents%2F%41BC"));
            assertEquals("a%zz/abc", GuardText.decodeLower("a%zz/ABC"), "a malformed escape must not stop the check");
            assertEquals("a%zz", GuardText.percentDecode("a%zz"));
            assertEquals("", GuardText.decodeLower(null));
        }

        @Test
        @DisplayName("a key written with a JSON unicode escape is found under its decoded name")
        void jsonEscapedKey() {
            String raw = "{\"" + BACKSLASH + "u0074oolApprovals\":{}}";

            assertFalse(GuardText.decodeLower(raw).contains("toolapprovals"), "the raw text hides the key");
            assertTrue(GuardText.haystack(raw).contains("toolapprovals"));
        }

        @Test
        @DisplayName("a document carried as a JSON string is parsed again, a bounded number of levels deep")
        void nestedJsonStrings() {
            String level = "{\"id\":\"0123" + ESCAPED_FOUR + "56789abcdef01234567\"}";
            for (int i = 0; i < 4; i++) {
                level = "{\"config\":" + quoted(level) + "}";
            }
            assertTrue(GuardText.containsId(GuardText.haystack(level), ID), "four levels deep");

            String tooDeep = "{\"config\":" + quoted(level) + "}";
            assertFalse(GuardText.containsId(GuardText.haystack(tooDeep), ID), "the depth stays bounded");
        }

        @Test
        @DisplayName("text that is not JSON is searched as it is, decoded")
        void notJson() {
            assertEquals("not json: abc", GuardText.haystack("not json: %41BC"));
        }

        @Test
        @DisplayName("a blank id names nothing; an id is matched trimmed and case-insensitively")
        void containsId() {
            assertFalse(GuardText.containsId("anything", " "));
            assertFalse(GuardText.containsId("anything", null));
            assertTrue(GuardText.containsId(GuardText.decodeLower("/agents/" + ID.toUpperCase()), " " + ID + " "));
        }

        /** {@code text} as a JSON string literal. */
        private static String quoted(String text) {
            return "\"" + text.replace(BACKSLASH, BACKSLASH + BACKSLASH).replace("\"", BACKSLASH + "\"") + "\"";
        }
    }

    @Nested
    @DisplayName("SelfUngatingGuard and ApprovedCallScope agree")
    class Agreement {

        @ParameterizedTest(name = "{index}: {0}")
        @ValueSource(strings = {
                // the id with one character as a JSON unicode escape
                "{\"id\":\"0123" + ESCAPED_FOUR + "56789abcdef01234567\"}",
                // the id percent-encoded
                "{\"id\":\"%30123456789abcdef01234567\"}",
                // the escape itself percent-encoded: decoded, then parsed
                "{\"id\":\"0123%5Cu003456789abcdef01234567\"}",
                // a document carried as a JSON string, escaped once per level
                "{\"config\":\"{" + BACKSLASH + "\"id" + BACKSLASH + "\":" + BACKSLASH + "\"0123" + BACKSLASH + BACKSLASH
                        + "u003456789abcdef01234567" + BACKSLASH + "\"}\"}",
                // non-standard JSON a lenient receiver still reads
                "{'id':'0123" + ESCAPED_FOUR + "56789abcdef01234567'}"})
        @DisplayName("an escaped or encoded id is seen by both guards")
        void bothSeeTheId(String rawArguments) {
            assertEquals(SelfUngatingGuard.OWN_AGENT_REASON, SelfUngatingGuard.refusalFromArguments(rawArguments, ID), "SelfUngatingGuard");
            assertTrue(ApprovedCallScope.targetsConversation(rawArguments, ID), "ApprovedCallScope");
        }

        @ParameterizedTest(name = "{index}: {0}")
        @ValueSource(strings = {"{\"id\":\"" + OTHER_ID + "\"}", "{\"config\":\"{" + BACKSLASH + "\"id" + BACKSLASH + "\":" + BACKSLASH + "\""
                + OTHER_ID + BACKSLASH + "\"}\"}", "not json, " + OTHER_ID})
        @DisplayName("another id is seen by neither")
        void neitherSeesAnotherId(String rawArguments) {
            assertNull(SelfUngatingGuard.refusalFromArguments(rawArguments, ID), "SelfUngatingGuard");
            assertFalse(ApprovedCallScope.targetsConversation(rawArguments, ID), "ApprovedCallScope");
        }
    }
}
