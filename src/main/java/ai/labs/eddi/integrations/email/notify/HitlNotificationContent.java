/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.notify;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.engine.hitl.HitlToolCallPreview;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * The words of a HITL email notification (email plan §16) — pure, no I/O.
 * <p>
 * <b>What it may say</b> is the Slack approval card's rule: the agent's name,
 * the pause type, the designer's pause reason, the deadline and its policy, the
 * conversation id, and for a {@code TOOL_CALL} pause the tool names with their
 * redacted, truncated arguments ({@link HitlToolCallPreview}). Never anything a
 * user or the model wrote in the conversation — no input, no output, no
 * transcript. The subject carries nothing but the agent's name.
 * <p>
 * <b>What it may not do</b> is decide anything. The only link goes to the
 * Manager's approvals page, which needs a login; there is no token, nothing
 * happens on opening it (link scanners open every link), and replying to the
 * mail does nothing.
 */
public final class HitlNotificationContent {

    /** Agent-name characters kept in the subject and body. */
    static final int MAX_AGENT_NAME = 80;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private HitlNotificationContent() {
    }

    /**
     * The facts of one pause, as read from the stored conversation.
     *
     * @param agentName
     *            the agent's display name, or null
     * @param agentId
     *            the agent id (shown when it has no name)
     * @param pauseType
     *            {@code TOOL_CALL}, {@code RULE} or null (a rule pause)
     * @param reason
     *            the designer's pause reason, or null
     * @param pausedAt
     *            when the pause began
     * @param timeoutPolicy
     *            what happens at the deadline, or null
     * @param approvalTimeout
     *            ISO-8601 duration, or null
     * @param conversationId
     *            the paused conversation
     * @param toolCalls
     *            the pending tool-call batch of a {@code TOOL_CALL} pause, or null
     */
    public record Pause(String agentName, String agentId, String pauseType, String reason, Instant pausedAt, HitlTimeoutPolicy timeoutPolicy,
            String approvalTimeout, String conversationId, PendingToolCallBatch toolCalls) {
    }

    /** A rendered notification. */
    public record Message(String subject, String text) {
    }

    /**
     * Renders the notification.
     *
     * @param approvalsUrl
     *            the Manager approvals page, or null when the deployment has no
     *            public base URL — then the mail names the page instead of linking
     *            it
     */
    public static Message render(Pause pause, String approvalsUrl) {
        String agent = agentLabel(pause);
        boolean toolCall = "TOOL_CALL".equals(pause.pauseType());
        StringBuilder text = new StringBuilder();
        text.append("A conversation is waiting for a human decision.\n\n");
        text.append("Agent:         ").append(agent).append('\n');
        text.append("Pause:         ").append(toolCall ? "tool call approval" : "approval rule").append('\n');
        String reason = oneLine(pause.reason(), 500);
        if (!reason.isEmpty()) {
            text.append("Reason:        ").append(reason).append('\n');
        }
        if (pause.pausedAt() != null) {
            text.append("Waiting since: ").append(WHEN.format(pause.pausedAt())).append('\n');
        }
        text.append("Deadline:      ").append(deadline(pause)).append('\n');
        text.append("Conversation:  ").append(oneLine(pause.conversationId(), 100)).append('\n');
        if (toolCall) {
            List<String> lines = HitlToolCallPreview.lines(pause.toolCalls());
            if (!lines.isEmpty()) {
                text.append("\nTool calls awaiting approval (arguments redacted and shortened):\n");
                for (String line : lines) {
                    text.append("  - ").append(oneLine(line, HitlToolCallPreview.ARGS_DISPLAY_MAX_CHARS + 120)).append('\n');
                }
            }
        }
        text.append('\n');
        if (approvalsUrl != null) {
            text.append("Review and decide on the Approvals page of the EDDI Manager (sign-in required):\n").append(approvalsUrl).append("\n\n");
        } else {
            text.append("Review and decide on the Approvals page of the EDDI Manager (/manage/approvals, sign-in required).\n\n");
        }
        text.append("Opening the link decides nothing, and replying to this email does nothing: approving or rejecting\n");
        text.append("happens only in the Manager, for someone EDDI allows to decide.\n\n");
        text.append("-- \nYou receive this because the agent's HITL settings (hitlConfig.notify.email) list this address.\n");
        return new Message("[EDDI] Approval needed: " + agent, text.toString());
    }

    /** The agent's name, single-line and capped; the id when it has no name. */
    static String agentLabel(Pause pause) {
        String name = oneLine(pause.agentName(), MAX_AGENT_NAME);
        if (!name.isEmpty()) {
            return name;
        }
        String id = oneLine(pause.agentId(), MAX_AGENT_NAME);
        return id.isEmpty() ? "an agent" : "agent " + id;
    }

    private static String deadline(Pause pause) {
        HitlTimeoutPolicy policy = pause.timeoutPolicy();
        if (policy == null || policy == HitlTimeoutPolicy.WAIT_INDEFINITELY || pause.approvalTimeout() == null || pause.pausedAt() == null) {
            return "none — it waits until someone decides";
        }
        Duration timeout;
        try {
            timeout = Duration.parse(pause.approvalTimeout());
        } catch (DateTimeParseException e) {
            return "none — it waits until someone decides";
        }
        String outcome = switch (policy) {
            case AUTO_APPROVE -> "then it is approved automatically";
            case AUTO_REJECT -> "then it is rejected automatically";
            case ABORT -> "then the conversation is cancelled";
            case WAIT_INDEFINITELY -> "";
        };
        try {
            return WHEN.format(pause.pausedAt().plus(timeout)) + ", " + outcome;
        } catch (DateTimeException | ArithmeticException e) {
            // A positive duration passes config validation however large it is; one
            // past the end of the time-line names no date, but the mail still goes.
            return "after " + oneLine(pause.approvalTimeout(), 40) + ", " + outcome;
        }
    }

    /**
     * {@code value} with every control character (line breaks included) turned into
     * a space, collapsed and capped — designer text must not shape the mail.
     */
    static String oneLine(String value, int max) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), max + 1));
        boolean space = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean blank = Character.isWhitespace(c) || Character.isISOControl(c) || c == (char) 0x2028 || c == (char) 0x2029;
            if (blank) {
                space = out.length() > 0;
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.append(c);
            if (out.length() >= max) {
                out.append('…');
                break;
            }
        }
        return out.toString();
    }
}
