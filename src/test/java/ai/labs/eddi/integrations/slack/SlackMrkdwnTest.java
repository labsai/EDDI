/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
