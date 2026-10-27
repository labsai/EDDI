/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * What an approval notification shows of a {@code TOOL_CALL} pause, one line
 * per pending call: {@code "<toolName> — <arguments>"}, at most
 * {@value #MAX_RENDERED_CALLS} calls, then {@code "+N more"}. Shared by the
 * Slack approval card and the email notification, so the two surfaces disclose
 * exactly the same thing.
 * <p>
 * The arguments are {@code argumentsRedacted} — already redaction-filtered and
 * size-capped when the batch was built — re-redacted through the
 * <em>current</em> filter (a message cannot be un-sent if a stored pause
 * predates a filter improvement) and truncated to
 * {@value #ARGS_DISPLAY_MAX_CHARS} characters for display. The raw arguments
 * are never read. This is a deliberate, approval-surface-only relaxation of the
 * names-only rule: a reviewer cannot responsibly approve {@code transfer_funds}
 * without seeing the amount.
 */
public final class HitlToolCallPreview {

    /** Pending calls listed before collapsing into "+N more". */
    public static final int MAX_RENDERED_CALLS = 5;

    /** Display truncation of the redacted arguments. */
    public static final int ARGS_DISPLAY_MAX_CHARS = 300;

    private HitlToolCallPreview() {
    }

    /**
     * The preview lines for {@code batch}; empty when it is null or holds no call.
     */
    public static List<String> lines(PendingToolCallBatch batch) {
        if (batch == null || batch.getCalls() == null || batch.getCalls().isEmpty()) {
            return List.of();
        }
        var calls = batch.getCalls();
        List<String> lines = new ArrayList<>();
        int rendered = Math.min(calls.size(), MAX_RENDERED_CALLS);
        for (int i = 0; i < rendered; i++) {
            var call = calls.get(i);
            lines.add(safe(call.getToolName()) + " — " + truncateForDisplay(SecretRedactionFilter.redact(call.getArgumentsRedacted())));
        }
        int remaining = calls.size() - rendered;
        if (remaining > 0) {
            lines.add("+" + remaining + " more");
        }
        return lines;
    }

    /**
     * An already-redacted argument string cut to {@value #ARGS_DISPLAY_MAX_CHARS}
     * characters, with an ellipsis when cut.
     */
    public static String truncateForDisplay(String argumentsRedacted) {
        String text = safe(argumentsRedacted);
        if (text.length() <= ARGS_DISPLAY_MAX_CHARS) {
            return text;
        }
        return text.substring(0, ARGS_DISPLAY_MAX_CHARS) + "…";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
