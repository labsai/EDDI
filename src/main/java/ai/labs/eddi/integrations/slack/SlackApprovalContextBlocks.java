/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.hitl.context.ApprovalContextViews;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the designer's {@code approvalContext} to a Slack approval card, between
 * the card's facts and its {@code actions} block, without touching how
 * {@link SlackHitlSupport#buildApprovalBlocks} builds the rest — the card is
 * built first, then this inserts before the {@code actions} block, so whatever
 * the builder puts there stays last.
 * <p>
 * <b>Values are {@code plain_text}, never mrkdwn.</b> The text is conversation
 * data (a model-written draft, an argument, a claim): plain text means no
 * {@code <!channel>} ping, no {@code <@U…>} mention, no {@code <url|label>}
 * link and no {@code *bold*} spoofing of "Approved by …". Labels are
 * author-written but still escaped. A value is chunked under
 * {@value #SECTION_TEXT_MAX} characters (Slack allows 3,000 per section).
 * <p>
 * <b>Audience.</b> Everyone in the approval channel sees a card, not only the
 * approvers, so rendering the context into the channel needs the integration's
 * opt-in {@code platformConfig.hitlApprovalContext=true}
 * ({@link SlackHitlSupport#CFG_HITL_APPROVAL_CONTEXT}). Without it the card
 * gets one line, "More context available in the Manager".
 * <p>
 * <b>Block budget.</b> Slack accepts 50 blocks per message. What does not fit
 * is replaced by "+N more context items — open in Manager"; the card never
 * fails to post because of the context.
 */
public final class SlackApprovalContextBlocks {

    /** Slack's limit for the text of a section block is 3,000; leave headroom. */
    static final int SECTION_TEXT_MAX = 2_900;
    /** Slack's limit for the blocks of one message. */
    static final int MAX_BLOCKS = 50;

    static final String NOTICE = "_Context rendered from conversation data at pause time — not verified by EDDI_";
    static final String HINT_ONLY = "More context available in the Manager";

    private SlackApprovalContextBlocks() {
    }

    /** Whether the integration opted into showing the context in the channel. */
    public static boolean enabled(Map<String, String> platformConfig) {
        return platformConfig != null
                && "true".equalsIgnoreCase(String.valueOf(platformConfig.get(SlackHitlSupport.CFG_HITL_APPROVAL_CONTEXT)).trim());
    }

    /**
     * Inserts the context blocks before the card's {@code actions} block (at the
     * end when the card has none).
     *
     * @param blocks
     *            the card, as built by {@code buildApprovalBlocks}; modified in
     *            place
     * @param context
     *            the persisted context of the pause; {@code null} or empty leaves
     *            the card untouched
     * @param optedIn
     *            {@link #enabled} for the integration
     * @return {@code true} when conversation text was inserted (the caller then
     *         posts with link and media unfurling off); {@code false} when the card
     *         is unchanged or only gained the one-line hint
     */
    public static boolean insertBeforeActions(List<Map<String, Object>> blocks, RenderedApprovalContext context, boolean optedIn) {
        RenderedApprovalContext served = ApprovalContextViews.reRedacted(context);
        if (served == null) {
            return false;
        }
        int at = indexOfActions(blocks);
        if (!optedIn) {
            blocks.add(at, contextLine(HINT_ONLY));
            return false;
        }

        int available = MAX_BLOCKS - blocks.size();
        List<Map<String, Object>> rendered = new ArrayList<>();
        int shown = 0;
        List<RenderedApprovalContext.Item> items = served.getItems();
        for (RenderedApprovalContext.Item item : items) {
            List<Map<String, Object>> itemBlocks = itemBlocks(item);
            // Two header blocks (divider, notice) always come first; leave one block
            // for the "+N more" line unless this is the last item and it fits whole.
            boolean last = shown == items.size() - 1;
            int needed = 2 + rendered.size() + itemBlocks.size() + (last ? 0 : 1);
            if (needed > available) {
                break;
            }
            rendered.addAll(itemBlocks);
            shown++;
        }
        int omitted = items.size() - shown;
        if (shown == 0 && available < 3) {
            // No room for even a header and one item: the Manager has it.
            if (available >= 1) {
                blocks.add(at, contextLine(HINT_ONLY));
            }
            return false;
        }
        List<Map<String, Object>> inserted = new ArrayList<>();
        inserted.add(divider());
        inserted.add(contextLine(NOTICE));
        inserted.addAll(rendered);
        if (omitted > 0) {
            inserted.add(contextLine("+" + omitted + " more context item" + (omitted == 1 ? "" : "s") + " — open in Manager"));
        } else if (served.isTruncated() && blocks.size() + inserted.size() < MAX_BLOCKS) {
            inserted.add(contextLine("Some context was cut short — open in Manager"));
        }
        blocks.addAll(at, inserted);
        return shown > 0;
    }

    private static int indexOfActions(List<Map<String, Object>> blocks) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            if ("actions".equals(blocks.get(i).get("type"))) {
                return i;
            }
        }
        return blocks.size();
    }

    private static List<Map<String, Object>> itemBlocks(RenderedApprovalContext.Item item) {
        List<Map<String, Object>> out = new ArrayList<>();
        String label = item.getLabel() == null || item.getLabel().isBlank() ? "Context" : item.getLabel();
        out.add(contextLine("*" + SlackMrkdwn.escape(label) + "*"));
        if (item.getError() != null) {
            out.add(plainSection("This block could not be rendered (" + item.getError() + ")."));
            return out;
        }
        String text = item.getText() == null ? "" : item.getText();
        if (text.isBlank()) {
            out.add(plainSection("(empty)"));
            return out;
        }
        for (int from = 0; from < text.length();) {
            int end = Math.min(text.length(), from + SECTION_TEXT_MAX);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            out.add(plainSection(text.substring(from, end)));
            from = end;
        }
        return out;
    }

    private static Map<String, Object> plainSection(String text) {
        var textObject = new LinkedHashMap<String, Object>();
        textObject.put("type", "plain_text");
        textObject.put("text", text);
        textObject.put("emoji", false);
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "section");
        block.put("text", textObject);
        return block;
    }

    private static Map<String, Object> contextLine(String mrkdwn) {
        var element = new LinkedHashMap<String, Object>();
        element.put("type", "mrkdwn");
        element.put("text", mrkdwn);
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "context");
        block.put("elements", List.of(element));
        return block;
    }

    private static Map<String, Object> divider() {
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "divider");
        return block;
    }
}
