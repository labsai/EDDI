/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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

    @Test
    void longLinesThatFitTheAllowedMessagesAreNotTruncated() {
        // 25 lines of 2,501 characters: 62,525 in all, under 20 x 3,900. Cutting at
        // every line break used one message per line and truncated at message 20.
        String line = "y".repeat(2500) + "\n";
        String text = line.repeat(25);

        List<String> chunks = SlackMrkdwn.escapeInChunks(text, SlackMrkdwn.MAX_MESSAGE_LENGTH);

        assertTrue(chunks.size() <= SlackMrkdwn.MAX_MESSAGES, "chunks: " + chunks.size());
        assertEquals(text, String.join("", chunks), "the whole text arrives, without a truncation marker");
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= SlackMrkdwn.MAX_MESSAGE_LENGTH, "chunk of " + chunk.length());
        }
    }

    @Test
    void lineBreaksAreStillPreferredWhenThereIsRoom() {
        String text = ("z".repeat(60) + "\n").repeat(5);

        List<String> chunks = SlackMrkdwn.escapeInChunks(text, 100, 20);

        assertEquals(5, chunks.size());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.endsWith("\n")), chunks.toString());
    }

    @Test
    void aLineBreakIsNotTakenWhenTheRestCouldNoLongerBePartitionedSafely() {
        // 47 escaped characters: "x\n" and nine "&amp;". Cutting at the line break
        // leaves 45 characters that two chunks of 24 can hold only as four entities
        // each (entities cannot be split), so the ninth was dropped. 22 + 20 + 5 fits.
        String text = "x\n" + "&".repeat(9);

        List<String> chunks = SlackMrkdwn.escapeInChunks(text, 24, 3);

        assertEquals(SlackMrkdwn.escape(text), String.join("", chunks));
    }

    /**
     * Property check over random texts mixing entities, surrogate pairs, line
     * breaks, long and short lines: every chunk is within the limit and starts and
     * ends at a safe boundary; the chunks reassemble the escaped text exactly; and
     * a text is truncated only when no safe partition into the allowed number of
     * chunks exists (computed independently by packing indivisible units).
     */
    @Test
    void randomTextsReassembleExactlyAndAreTruncatedOnlyWhenTheyCannotFit() {
        Random random = new Random(944);
        String[] pieces = {"a", "bc", "<", ">", "&", "\n", "\n\n", SMILE, "x".repeat(37), "<&>".repeat(5), "y".repeat(120)};
        for (int run = 0; run < 3000; run++) {
            var text = new StringBuilder();
            int length = random.nextInt(60);
            for (int k = 0; k < length; k++) {
                text.append(pieces[random.nextInt(pieces.length)]);
            }
            int maxLength = 8 + random.nextInt(60);
            int maxChunks = 1 + random.nextInt(8);
            String raw = text.toString();
            String escaped = SlackMrkdwn.escape(raw);

            List<String> chunks = SlackMrkdwn.escapeInChunks(raw, maxLength, maxChunks);

            String context = "run " + run + " maxLength=" + maxLength + " maxChunks=" + maxChunks + " text=" + raw;
            assertTrue(chunks.size() <= maxChunks, context);
            for (String chunk : chunks) {
                assertTrue(chunk.length() <= maxLength, context);
                assertTrue(chunk.isEmpty() || !Character.isLowSurrogate(chunk.charAt(0)), context);
                assertTrue(chunk.isEmpty() || !Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)), context);
                assertTrue(entitiesComplete(chunk), context + " chunk=" + chunk);
            }
            boolean feasible = minimumSafeChunks(escaped, maxLength) <= maxChunks;
            if (feasible) {
                assertEquals(escaped, String.join("", chunks), context);
            } else {
                String posted = String.join("", chunks);
                if (maxLength > SlackMrkdwn.TRUNCATION_MARKER.length()) {
                    assertTrue(posted.endsWith(SlackMrkdwn.TRUNCATION_MARKER), context);
                    posted = posted.substring(0, posted.length() - SlackMrkdwn.TRUNCATION_MARKER.length());
                }
                assertTrue(escaped.startsWith(posted), context);
            }
        }
    }

    /** Every "&" in the chunk starts an entity that ends inside the chunk. */
    private static boolean entitiesComplete(String chunk) {
        for (int i = chunk.indexOf('&'); i >= 0; i = chunk.indexOf('&', i + 1)) {
            if (!(chunk.startsWith("&amp;", i) || chunk.startsWith("&lt;", i) || chunk.startsWith("&gt;", i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fewest chunks of at most maxLength holding the text, packing indivisible
     * units greedily.
     */
    private static int minimumSafeChunks(String escaped, int maxLength) {
        List<Integer> units = new ArrayList<>();
        for (int i = 0; i < escaped.length();) {
            int unit = 1;
            if (escaped.startsWith("&amp;", i)) {
                unit = 5;
            } else if (escaped.startsWith("&lt;", i) || escaped.startsWith("&gt;", i)) {
                unit = 4;
            } else if (Character.isHighSurrogate(escaped.charAt(i)) && i + 1 < escaped.length()) {
                unit = 2;
            }
            units.add(unit);
            i += unit;
        }
        int chunks = 0;
        int used = maxLength;
        for (int unit : units) {
            if (used + unit > maxLength) {
                chunks++;
                used = 0;
            }
            used += unit;
        }
        return chunks;
    }
}
