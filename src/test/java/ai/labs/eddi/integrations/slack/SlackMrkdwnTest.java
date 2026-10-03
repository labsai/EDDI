/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlackMrkdwnTest {

    @Test
    void broadcastsMentionsAndLinksAreNeutralised() {
        assertEquals("&lt;!channel&gt; &lt;!here&gt; &lt;@U123&gt; &lt;https://evil|bank&gt;",
                SlackMrkdwn.escape("<!channel> <!here> <@U123> <https://evil|bank>"));
    }

    @Test
    void ampersandIsEscapedFirst() {
        assertEquals("a &amp;lt; b", SlackMrkdwn.escape("a &lt; b"));
    }

    @Test
    void lineLeadingBlockquoteMarkerIsKept() {
        assertEquals("> quoted 1 &gt; 0\n  > again\nx &gt; y", SlackMrkdwn.escape("> quoted 1 > 0\n  > again\nx > y"));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", SlackMrkdwn.escape(null));
    }

    @Test
    void escapedChunksStayWithinTheLimitAndReassembleExactly() {
        String text = ("line with <!here> & a > b\n").repeat(400) + "x".repeat(5000) + "&&&&";

        List<String> chunks = SlackMrkdwn.escapeInChunks(text, 200, 1000);

        assertTrue(chunks.size() > 1);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 200, "chunk of " + chunk.length());
            assertFalse(chunk.contains("<"), chunk);
            assertFalse(chunk.matches("(?s).*&[a-z]{0,3}$") && !chunk.endsWith(";"), "entity cut: " + chunk);
        }
        assertEquals(SlackMrkdwn.escape(text), String.join("", chunks));
    }

    @Test
    void anEntityAtTheCutPointIsNotSplit() {
        // 9 plain characters, then "&" escapes to "&amp;" across the 12-character limit
        List<String> chunks = SlackMrkdwn.escapeInChunks("123456789&abc", 12);

        assertEquals(List.of("123456789", "&amp;abc"), chunks);
    }

    private static final String SMILE = new String(Character.toChars(0x1F600));

    @Test
    void aSurrogatePairAtTheCutPointIsNotSplit() {
        List<String> chunks = SlackMrkdwn.escapeInChunks("1234567" + SMILE + "tail", 8);

        assertEquals("1234567", chunks.getFirst());
        assertTrue(chunks.get(1).startsWith(SMILE));
    }

    @Test
    void emptyTextHasNoChunks() {
        assertTrue(SlackMrkdwn.escapeInChunks(null, 100).isEmpty());
        assertTrue(SlackMrkdwn.escapeInChunks("", 100).isEmpty());
    }

    @Test
    void aTextNeedingMoreThanTheMaximumMessagesIsCutWithAMarker() {
        List<String> chunks = SlackMrkdwn.escapeInChunks("x".repeat(100_000), 100, 3);

        assertEquals(3, chunks.size());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 100, "chunk of " + chunk.length());
        }
        assertTrue(chunks.getLast().endsWith(SlackMrkdwn.TRUNCATION_MARKER), chunks.getLast());
        assertFalse(chunks.get(1).endsWith(SlackMrkdwn.TRUNCATION_MARKER));
    }

    @Test
    void aTextThatFitsIsNotMarked() {
        List<String> chunks = SlackMrkdwn.escapeInChunks("x".repeat(250), 100, 3);

        assertEquals("x".repeat(250), String.join("", chunks));
    }

    @Test
    void aTwoMegabyteNewlineFreeReplyBecomesAtMostTheMaximumNumberOfMessages() {
        List<String> chunks = SlackMrkdwn.escapeInChunks("<&>".repeat(700_000), SlackMrkdwn.MAX_MESSAGE_LENGTH);

        assertEquals(SlackMrkdwn.MAX_MESSAGES, chunks.size());
        assertTrue(chunks.getLast().endsWith(SlackMrkdwn.TRUNCATION_MARKER));
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= SlackMrkdwn.MAX_MESSAGE_LENGTH, "chunk of " + chunk.length());
            assertFalse(chunk.contains("<"), "unescaped text");
        }
    }
}
