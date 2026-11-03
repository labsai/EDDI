/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.HumanRequestDetail;

import java.util.Map;

/**
 * The extra fields a status or inbox summary carries for a pending human input
 * that is not a plain speaking turn: {@code pendingKind} ({@code TURN},
 * {@code TIE_BREAK} or {@code TASK}), {@code pendingOptions} for a tie-break
 * and {@code pendingTaskId} for a human task. One call per surface, so the REST
 * status, the MCP status and any later surface say the same thing.
 */
public final class PendingHumanInputView {

    /**
     * The kind of a pending input without a detail: a member's turn or an
     * escalation.
     */
    public static final String KIND_TURN = "TURN";

    private PendingHumanInputView() {
    }

    /** The pending input's kind, or {@code null} when nothing is pending. */
    public static String kindOf(GroupConversation gc) {
        var pending = gc != null ? gc.getPendingHumanInput() : null;
        if (pending == null) {
            return null;
        }
        HumanRequestDetail detail = pending.detail();
        return detail != null && detail.kind() != null ? detail.kind() : KIND_TURN;
    }

    /**
     * Adds the kind-specific fields to {@code summary}; a no-op when nothing is
     * pending.
     */
    public static void addTo(Map<String, Object> summary, GroupConversation gc) {
        String kind = kindOf(gc);
        if (kind == null) {
            return;
        }
        summary.put("pendingKind", kind);
        HumanRequestDetail detail = gc.getPendingHumanInput().detail();
        if (detail == null) {
            return;
        }
        if (detail.isTieBreak()) {
            summary.put("pendingOptions", detail.options());
        }
        if (detail.taskId() != null) {
            summary.put("pendingTaskId", detail.taskId());
        }
    }
}
