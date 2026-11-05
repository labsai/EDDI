/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SlackApprovalContextBlocks")
class SlackApprovalContextBlocksTest {

    private static RenderedApprovalContext context(Item... items) {
        return RenderedApprovalContext.of(List.of(items), false);
    }

    private static Item item(String label, String text) {
        return new Item(label, text, false, "agent", null, null);
    }

    /**
     * A card the way the builder shapes it: facts, a tool line, then actions last.
     */
    private static List<Map<String, Object>> card(boolean withButtons) {
        return new ArrayList<>(SlackHitlSupport.buildApprovalBlocks("⏸️ Conversation awaiting approval", "Conversation", "conv-1", "gtm-writer",
                "Publishing to LinkedIn", "WAIT_INDEFINITELY", "int|conv-1|card", withButtons, "TOOL_CALL", null));
    }

    @SuppressWarnings("unchecked")
    private static String type(Map<String, Object> block) {
        return (String) block.get("type");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> textOf(Map<String, Object> block) {
        return (Map<String, Object>) block.get("text");
    }

    private static String flatten(Object value) {
        StringBuilder sb = new StringBuilder();
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> sb.append(flatten(v)));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> sb.append(flatten(v)));
        } else if (value instanceof String s) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("no context leaves the card byte-identical")
    void noContextNoChange() {
        var blocks = card(true);
        var before = new ArrayList<>(blocks);

        assertFalse(SlackApprovalContextBlocks.insertBeforeActions(blocks, null, true));
        assertFalse(SlackApprovalContextBlocks.insertBeforeActions(blocks, RenderedApprovalContext.of(List.of(), false), true));

        assertEquals(before, blocks);
    }

    @Test
    @DisplayName("values are plain_text with emoji off; the label is a bold mrkdwn context line")
    void valuesArePlainText() {
        var blocks = card(true);

        assertTrue(SlackApprovalContextBlocks.insertBeforeActions(blocks, context(item("Draft", "Acme cuts onboarding to 3 days")), true));

        var section = blocks.stream().filter(b -> "section".equals(type(b)) && textOf(b) != null && "plain_text".equals(textOf(b).get("type")))
                .toList();
        assertEquals(1, section.size());
        assertEquals("Acme cuts onboarding to 3 days", textOf(section.get(0)).get("text"));
        assertEquals(false, textOf(section.get(0)).get("emoji"));
        assertTrue(flatten(blocks).contains("*Draft*"));
        assertTrue(flatten(blocks).contains(SlackApprovalContextBlocks.NOTICE));
    }

    @Test
    @DisplayName("mentions, links and bold spoofing stay literal text")
    void injectionStaysLiteral() {
        var blocks = card(true);
        String hostile = "<!channel> <@U123> <https://evil.example|bank.example> *Approved by the CFO*";

        SlackApprovalContextBlocks.insertBeforeActions(blocks, context(item("Draft", hostile)), true);

        var value = blocks.stream().filter(b -> "section".equals(type(b)) && textOf(b) != null && "plain_text".equals(textOf(b).get("type")))
                .findFirst()
                .orElseThrow();
        assertEquals(hostile, textOf(value).get("text"), "the text is carried verbatim as plain_text, which Slack never interprets");
        // And nowhere is it placed into an mrkdwn element.
        blocks.stream().filter(b -> "context".equals(type(b))).forEach(b -> assertFalse(flatten(b).contains("<!channel>"), flatten(b)));
    }

    @Test
    @DisplayName("an author's label cannot ping or link either")
    void labelIsEscaped() {
        var blocks = card(true);

        SlackApprovalContextBlocks.insertBeforeActions(blocks, context(item("<!here> Draft", "text")), true);

        assertFalse(flatten(blocks).contains("<!here>"));
        assertTrue(flatten(blocks).contains("&lt;!here&gt;"));
    }

    @Test
    @DisplayName("the context goes before the actions block, which stays last")
    void insertedBeforeActions() {
        var blocks = card(true);
        int originalSize = blocks.size();

        SlackApprovalContextBlocks.insertBeforeActions(blocks, context(item("Draft", "one"), item("Sources", "two")), true);

        assertEquals("actions", type(blocks.get(blocks.size() - 1)));
        assertEquals(1, blocks.stream().filter(b -> "actions".equals(type(b))).count());
        int divider = blocks.indexOf(blocks.stream().filter(b -> "divider".equals(type(b))).findFirst().orElseThrow());
        assertEquals(originalSize - 1, divider, "the divider sits where the actions block was");
        assertEquals(originalSize + 2 + 4, blocks.size(), "divider + notice + two items of two blocks");
    }

    @Test
    @DisplayName("a card without buttons gets the context before its final 'no approver' line")
    void withoutActionsAppendsAtEnd() {
        var blocks = card(false);

        SlackApprovalContextBlocks.insertBeforeActions(blocks, context(item("Draft", "one")), true);

        assertTrue(flatten(blocks).contains("one"));
    }

    @Test
    @DisplayName("an integration without the opt-in shows only the one-line hint and no conversation text")
    void notOptedInShowsHintOnly() {
        var blocks = card(true);
        var before = blocks.size();

        assertFalse(SlackApprovalContextBlocks.insertBeforeActions(blocks, context(item("Draft", "secret-ish draft")), false));

        assertEquals(before + 1, blocks.size());
        assertTrue(flatten(blocks).contains(SlackApprovalContextBlocks.HINT_ONLY));
        assertFalse(flatten(blocks).contains("secret-ish draft"));
        assertEquals("actions", type(blocks.get(blocks.size() - 1)));
    }

    @Test
    @DisplayName("the opt-in is the platformConfig key hitlApprovalContext=true")
    void optInKey() {
        assertTrue(SlackApprovalContextBlocks.enabled(Map.of("hitlApprovalContext", "true")));
        assertTrue(SlackApprovalContextBlocks.enabled(Map.of("hitlApprovalContext", " TRUE ")));
        assertFalse(SlackApprovalContextBlocks.enabled(Map.of("hitlApprovalContext", "false")));
        assertFalse(SlackApprovalContextBlocks.enabled(Map.of()));
        assertFalse(SlackApprovalContextBlocks.enabled(null));
    }

    @Test
    @DisplayName("a long value is chunked under 2,900 characters per section")
    void chunking() {
        var blocks = card(true);
        String text = "a".repeat(2_900) + "b".repeat(2_900) + "c".repeat(100);

        SlackApprovalContextBlocks.insertBeforeActions(blocks, context(item("Draft", text)), true);

        var sections = blocks.stream().filter(b -> "section".equals(type(b)) && textOf(b) != null && "plain_text".equals(textOf(b).get("type")))
                .toList();
        assertEquals(3, sections.size());
        sections.forEach(s -> assertTrue(((String) textOf(s).get("text")).length() <= 2_900));
        assertEquals(text, sections.stream().map(s -> (String) textOf(s).get("text")).reduce("", String::concat));
    }

    @Test
    @DisplayName("an item that failed to render says so instead of showing an empty value")
    void errorItem() {
        var blocks = card(true);

        SlackApprovalContextBlocks.insertBeforeActions(blocks,
                context(new Item("Claims", "", false, "agent", null, "render_failed"), item("Draft", "fine")), true);

        assertTrue(flatten(blocks).contains("could not be rendered (render_failed)"));
        assertTrue(flatten(blocks).contains("fine"));
    }

    @Test
    @DisplayName("the 50-block budget: what does not fit becomes '+N more', and the card stays within 50 blocks")
    void blockBudget() {
        for (int existing : new int[]{5, 40, 43, 44, 45, 46, 47, 48, 49}) {
            var blocks = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < existing - 1; i++) {
                blocks.add(Map.of("type", "context", "elements", List.of(Map.of("type", "mrkdwn", "text", "line " + i))));
            }
            blocks.add(Map.of("type", "actions", "elements", List.of()));
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                items.add(item("L" + i, "value " + i));
            }

            SlackApprovalContextBlocks.insertBeforeActions(blocks, RenderedApprovalContext.of(items, false), true);

            assertTrue(blocks.size() <= 50, "existing=" + existing + " -> " + blocks.size());
            assertEquals("actions", type(blocks.get(blocks.size() - 1)), "existing=" + existing);
            if (flatten(blocks).contains("value 11")) {
                assertFalse(flatten(blocks).contains("more context item"), "everything fit, so no overflow line (existing=" + existing + ")");
            } else if (blocks.size() - existing > 0 && existing < 48) {
                assertTrue(flatten(blocks).contains("more context item"), "existing=" + existing);
            }
        }
    }

    @Test
    @DisplayName("budget boundary: exactly filling the 50 blocks is allowed")
    void blockBudgetExactFit() {
        // 46 existing + divider + notice + 1 item (2 blocks) = 50... use 45 existing so
        // one item (2) + header (2) = 49, two items = 51
        var blocks = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 44; i++) {
            blocks.add(Map.of("type", "context", "elements", List.of(Map.of("type", "mrkdwn", "text", "line " + i))));
        }
        blocks.add(Map.of("type", "actions", "elements", List.of()));
        // 45 existing; room for 5 more: divider + notice + one item (label + value) =
        // 4, leaving 1 for the overflow line
        SlackApprovalContextBlocks.insertBeforeActions(blocks, context(item("A", "first"), item("B", "second")), true);

        assertEquals(50, blocks.size());
        assertTrue(flatten(blocks).contains("first"));
        assertFalse(flatten(blocks).contains("second"));
        assertTrue(flatten(blocks).contains("+1 more context item — open in Manager"));
    }

    @Test
    @DisplayName("a truncated context says so when there is room")
    void truncationNote() {
        var blocks = card(true);

        SlackApprovalContextBlocks.insertBeforeActions(blocks, RenderedApprovalContext.of(List.of(item("Draft", "text")), true), true);

        assertTrue(flatten(blocks).contains("cut short"));
    }

    @Test
    @DisplayName("text is passed through the current redaction filter before it reaches the card")
    void redactedAgain() {
        var blocks = card(true);
        String key = "sk-ant-" + "fixture".repeat(5);

        SlackApprovalContextBlocks.insertBeforeActions(blocks, context(item("Draft", "key " + key + " end")), true);

        assertFalse(flatten(blocks).contains(key));
    }
}
