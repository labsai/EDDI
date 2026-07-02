/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.modules.output.model.OutputItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared Slack HITL (human-in-the-loop) helpers: config-key access, Block Kit
 * builders for approval notifications, approver authorization, and the
 * Slack-friendly conversation response-text extraction.
 * <p>
 * Kept as a small static-utility class (no CDI) so it can be reused by
 * {@link SlackEventHandler} (in-thread pause notice + approval notification),
 * the interactivity endpoint handler (authorization), and
 * {@link SlackGroupDiscussionListener} (group pause notification) without
 * duplication.
 *
 * @since 6.1.0
 */
public final class SlackHitlSupport {

    // ─── platformConfig keys (see ChannelIntegrationConfiguration Javadoc) ───
    public static final String CFG_HITL_APPROVAL_CHANNEL = "hitlApprovalChannel";
    public static final String CFG_HITL_APPROVER_USER_IDS = "hitlApproverUserIds";

    // ─── Block Kit action ids ───
    public static final String ACTION_APPROVE = "hitl_approve";
    public static final String ACTION_REJECT = "hitl_reject";

    /**
     * Prefix marking an action value as targeting a group discussion (routed to
     * {@code resumeDiscussion}) rather than a single conversation. A plain value
     * (no prefix) is a conversationId.
     */
    public static final String GROUP_VALUE_PREFIX = "group:";

    /** In-thread notice posted when a conversation enters AWAITING_HUMAN. */
    public static final String PAUSE_NOTICE = "⏸️ This conversation is awaiting human approval.";

    /** Notice when the user keeps messaging a paused conversation. */
    public static final String STILL_AWAITING_NOTICE = "⏸️ Still awaiting approval — a reviewer must decide before I can continue.";

    private SlackHitlSupport() {
        // Utility class
    }

    // ─── Config access ───

    /**
     * Parse the comma-separated {@code hitlApproverUserIds} value into a set.
     * Returns an empty set if unset/blank — the fail-closed default (no buttons,
     * nobody authorized).
     */
    public static Set<String> parseApproverUserIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Whether {@code slackUserId} is authorized to decide, given the configured
     * comma-separated approver list. Fail-closed: an unset list authorizes nobody.
     */
    public static boolean isAuthorizedApprover(String slackUserId, String approverUserIdsCsv) {
        if (slackUserId == null || slackUserId.isBlank()) {
            return false;
        }
        return parseApproverUserIds(approverUserIdsCsv).contains(slackUserId);
    }

    // ─── Block Kit builders ───

    /**
     * Build the interactive approval-notification blocks for a paused
     * conversation/group. If {@code includeButtons} is false (no approver list
     * configured), the actions block is omitted — notification-only, fail-closed.
     *
     * @param title
     *            leading section title (e.g. "⏸️ Conversation awaiting approval")
     * @param subjectLabel
     *            label for the subject id (e.g. "Conversation" or "Discussion")
     * @param subjectId
     *            the conversationId or groupConversationId
     * @param agentLabel
     *            agent/group descriptor (may be null)
     * @param pauseReason
     *            the pause reason from the HITL bookmark (may be null)
     * @param timeoutInfo
     *            human-readable timeout policy/deadline (may be null)
     * @param actionValue
     *            the value carried by both buttons (conversationId, or
     *            {@code group:<id>} for a group discussion)
     * @param includeButtons
     *            whether to render Approve/Reject buttons
     */
    public static List<Map<String, Object>> buildApprovalBlocks(
                                                                String title, String subjectLabel, String subjectId, String agentLabel,
                                                                String pauseReason, String timeoutInfo, String actionValue, boolean includeButtons) {

        var blocks = new ArrayList<Map<String, Object>>();

        blocks.add(section(markdown("*" + title + "*")));

        var fields = new ArrayList<Map<String, Object>>();
        fields.add(markdown("*" + subjectLabel + ":*\n`" + safe(subjectId) + "`"));
        if (agentLabel != null && !agentLabel.isBlank()) {
            fields.add(markdown("*Agent:*\n" + safe(agentLabel)));
        }
        if (pauseReason != null && !pauseReason.isBlank()) {
            fields.add(markdown("*Reason:*\n" + safe(pauseReason)));
        }
        if (timeoutInfo != null && !timeoutInfo.isBlank()) {
            fields.add(markdown("*Timeout:*\n" + safe(timeoutInfo)));
        }
        var fieldsSection = new LinkedHashMap<String, Object>();
        fieldsSection.put("type", "section");
        fieldsSection.put("fields", fields);
        blocks.add(fieldsSection);

        if (includeButtons) {
            var actions = new LinkedHashMap<String, Object>();
            actions.put("type", "actions");
            actions.put("elements", List.of(
                    button("✅ Approve", ACTION_APPROVE, actionValue, "primary"),
                    button("⛔ Reject", ACTION_REJECT, actionValue, "danger")));
            blocks.add(actions);
        } else {
            blocks.add(context(
                    "No approver user ids configured — approve or reject via the API."));
        }

        return blocks;
    }

    /**
     * Build the finalized (buttons-removed) blocks shown after a decision is made
     * or when the message must reflect a resolved state.
     */
    public static List<Map<String, Object>> buildResolvedBlocks(String resolutionMarkdown) {
        return List.of(section(markdown(resolutionMarkdown)));
    }

    // ─── Block Kit primitives ───

    private static Map<String, Object> section(Map<String, Object> text) {
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "section");
        block.put("text", text);
        return block;
    }

    private static Map<String, Object> context(String text) {
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "context");
        block.put("elements", List.of(markdown(text)));
        return block;
    }

    private static Map<String, Object> markdown(String text) {
        var obj = new LinkedHashMap<String, Object>();
        obj.put("type", "mrkdwn");
        obj.put("text", text);
        return obj;
    }

    private static Map<String, Object> button(String text, String actionId, String value, String style) {
        var btn = new LinkedHashMap<String, Object>();
        btn.put("type", "button");
        var txt = new LinkedHashMap<String, Object>();
        txt.put("type", "plain_text");
        txt.put("text", text);
        txt.put("emoji", true);
        btn.put("text", txt);
        btn.put("action_id", actionId);
        btn.put("value", value);
        if (style != null) {
            btn.put("style", style);
        }
        return btn;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ─── Response-text extraction (shared with SlackEventHandler) ───

    /**
     * Extract the text response from a conversation snapshot, returning a
     * Slack-friendly placeholder (never null) when no meaningful output exists.
     * Handles output items stored as {@link OutputItem} POJOs (live memory callback
     * path) or as Maps (deserialized from MongoDB).
     * <p>
     * This is the single source of truth for both the normal say path and the HITL
     * continuation push, so both surfaces format agent output identically.
     */
    public static String extractSlackResponseText(SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == null) {
            return "_No response from agent._";
        }
        var outputs = snapshot.getConversationOutputs();
        if (outputs == null || outputs.isEmpty()) {
            return "_No response from agent._";
        }

        var lastOutput = outputs.get(outputs.size() - 1);
        if (lastOutput == null) {
            return "_No response from agent._";
        }

        var texts = new ArrayList<String>();

        // Format 1: Nested "output" array — may contain TextOutputItem POJOs or Maps
        Object outputArray = lastOutput.get("output");
        if (outputArray instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof String s) {
                    texts.add(s);
                } else if (item instanceof OutputItem oi && oi.toString() != null) {
                    // TextOutputItem.toString() returns the text field
                    texts.add(oi.toString());
                } else if (item instanceof Map<?, ?> map && map.get("text") instanceof String s) {
                    texts.add(s);
                }
            }
            if (!texts.isEmpty()) {
                return String.join("\n", texts);
            }
        }

        // Format 2: Flat keys like "output:text:agent" or "output:text:*"
        for (var entry : lastOutput.entrySet()) {
            if (entry.getKey() instanceof String key && key.startsWith("output:text:")) {
                Object val = entry.getValue();
                if (val instanceof String s) {
                    texts.add(s);
                } else if (val instanceof List<?> list) {
                    for (var item : list) {
                        if (item instanceof String s) {
                            texts.add(s);
                        } else if (item instanceof Map<?, ?> map && map.get("text") instanceof String s) {
                            texts.add(s);
                        }
                    }
                }
            }
        }

        if (!texts.isEmpty()) {
            return String.join("\n", texts);
        }

        return "_Agent completed but produced no text output._";
    }
}
