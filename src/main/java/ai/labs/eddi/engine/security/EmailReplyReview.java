/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reply review (email plan v3 §8.2): the reply to a mail waits, at the end of
 * the mail's turn and before the email channel queues it, for a person to
 * approve, reject or edit it.
 * <p>
 * <b>How a turn is armed.</b> The email channel decides per mail
 * ({@code emailControl.replyReview}: {@code always}, {@code firstContact},
 * {@code external}) and puts the reserved context key {@link #CONTEXT_KEY} on
 * the mail's turn — only on that turn: a reply answers one mail. Only the email
 * channel writes the key ({@link ClientContextGuard} removes it from client
 * context), and it counts only on a turn tainted {@code origin=email}. A value
 * that cannot be read still arms the review: the key is reserved, so a value of
 * the wrong shape is a fault, and a fault must not send a reply unreviewed.
 * <p>
 * <b>The pause</b> is a RULE pause after the last task of the turn's last
 * workflow ({@code Conversation}), with {@link #MARKER} on the step holding the
 * draft: the whole text the reply would carry (what the turn and, for the first
 * mail, the conversation's start said). The approver decides it through the
 * usual resume ({@code POST /agents/{id}/resume}, MCP
 * {@code resume_conversation}); {@code editedAnswer} replaces the draft as a
 * whole. Nothing of the pipeline runs again on the decision.
 * <p>
 * <b>The decision</b> is written to the mail's step as the conversation output
 * {@link #OUTPUT_KEY} ({@link #APPROVED}, {@link #EDITED}, {@link #REJECTED})
 * and, for an edit, {@link #TEXT_OUTPUT_KEY} — the text the email channel then
 * sends, signs and records, on every attempt. A rejection, an expiry and a turn
 * that ended undecided send nothing.
 */
public final class EmailReplyReview {

    /** The reserved context key the email channel arms a mail turn with. */
    public static final String CONTEXT_KEY = "emailReplyReview";

    /** Exact step-data key of {@link #CONTEXT_KEY}. */
    public static final String STEP_KEY = "context:" + CONTEXT_KEY;

    /**
     * Step datum of a pending review: {@code {draft}}. Present only while the
     * review waits; the decision removes it.
     */
    public static final String MARKER = "emailGate:replyReview";

    /** Step datum the decision writes: {@code {decision, editedSha256, ...}}. */
    public static final String DECISION_KEY = "emailGate:replyReviewDecision";

    /** Conversation output of the decided review on the mail's step. */
    public static final String OUTPUT_KEY = "emailReplyReview";
    /** Conversation output with the approver's text, when they edited the reply. */
    public static final String TEXT_OUTPUT_KEY = "emailReplyText";

    /** {@link #OUTPUT_KEY}: approved as drafted. */
    public static final String APPROVED = "approved";
    /** {@link #OUTPUT_KEY}: approved with the approver's text. */
    public static final String EDITED = "edited";
    /** {@link #OUTPUT_KEY}: rejected or expired: nothing is sent. */
    public static final String REJECTED = "rejected";
    /** {@link #OUTPUT_KEY} while the review waits. */
    public static final String PENDING = "pending";

    /** Pause reason when the channel gave none. */
    public static final String DEFAULT_REASON = "The reply to a mail waits for review before it is sent.";

    /** Label of the approval-context item that shows the approver the reply. */
    public static final String REPLY_LABEL = "Reply";
    /** Source of that item. */
    public static final String REPLY_SOURCE = "email";

    private static final String FIELD_MODE = "mode";
    private static final String FIELD_REASON = "reason";
    private static final String FIELD_DRAFT = "draft";

    private EmailReplyReview() {
    }

    /**
     * The context value the email channel writes on a mail turn whose reply waits
     * for review.
     *
     * @param mode
     *            the {@code replyReview} mode that armed it ({@code always},
     *            {@code firstContact}, {@code external})
     * @param reason
     *            what the approver is shown (no address, no subject: it reaches the
     *            write-once audit ledger)
     */
    public static Context contextValue(String mode, String reason) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(FIELD_MODE, mode);
        if (reason != null) {
            value.put(FIELD_REASON, reason);
        }
        return new Context(Context.ContextType.object, value);
    }

    /**
     * Whether the turn {@code memory} is running must have its reply reviewed: the
     * current step carries {@link #STEP_KEY} and the turn is tainted
     * {@code origin=email}.
     */
    public static boolean armed(IConversationMemory memory) {
        if (memory == null) {
            return false;
        }
        IConversationMemory.IConversationStep step;
        try {
            step = memory.getCurrentStep();
        } catch (RuntimeException e) {
            return false;
        }
        if (step == null || step.getData(STEP_KEY) == null) {
            return false;
        }
        return TurnOrigin.EMAIL.equals(memory.getOrigin()) || TurnOrigin.currentIsEmail()
                || TurnOrigin.EMAIL.equals(stepTaint(step.getData(EmailApprovalGate.TAINT_STEP_KEY)));
    }

    /** The pause reason the armed turn carries, or {@link #DEFAULT_REASON}. */
    public static String reasonOf(IConversationMemory memory) {
        try {
            IData<Object> datum = memory.getCurrentStep().getData(STEP_KEY);
            Object value = datum == null ? null : datum.getResult();
            if (value instanceof Context context) {
                value = context.getValue();
            } else if (value instanceof Map<?, ?> map && map.containsKey("value") && !map.containsKey(FIELD_MODE)) {
                value = map.get("value");
            }
            if (value instanceof Map<?, ?> map && map.get(FIELD_REASON) instanceof String reason && !reason.isBlank()) {
                return reason;
            }
        } catch (RuntimeException e) {
            // fall through to the default
        }
        return DEFAULT_REASON;
    }

    /** The marker value of a pending review. */
    public static Map<String, Object> marker(String draft) {
        return marker(draft, false);
    }

    /**
     * The marker value of a pending review; {@code endAfter} when the turn stopped
     * its conversation ({@code STOP_CONVERSATION}), which the decision then ends.
     */
    public static Map<String, Object> marker(String draft, boolean endAfter) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(FIELD_DRAFT, draft);
        if (endAfter) {
            value.put(FIELD_END, true);
        }
        return value;
    }

    /**
     * Whether the decision of the review {@code marker} describes ends its
     * conversation.
     */
    public static boolean endsAfter(Object marker) {
        return marker instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get(FIELD_END));
    }

    private static final String FIELD_END = "endConversation";

    /** The draft a pending review's marker holds, or null. */
    public static String draftOf(Object marker) {
        return marker instanceof Map<?, ?> map && map.get(FIELD_DRAFT) instanceof String draft ? draft : null;
    }

    /** Whether the current step of {@code memory} holds a pending review. */
    public static boolean pending(IConversationMemory memory) {
        try {
            return memory != null && memory.getCurrentStep() != null && memory.getCurrentStep().getData(MARKER) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The draft of the pending review on the current step of {@code memory}. */
    public static String draftOf(IConversationMemory memory) {
        try {
            IData<Object> datum = memory.getCurrentStep().getData(MARKER);
            return datum == null ? null : draftOf(datum.getResult());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The pending review's marker in the last step of a paused snapshot, or null
     * when the pause is not a reply review.
     */
    public static Map<String, Object> pendingIn(ConversationMemorySnapshot snapshot) {
        List<ConversationMemorySnapshot.ConversationStepSnapshot> steps = snapshot != null ? snapshot.getConversationSteps() : null;
        if (steps == null || steps.isEmpty() || steps.get(steps.size() - 1).getWorkflows() == null) {
            return null;
        }
        Object found = null;
        for (var run : steps.get(steps.size() - 1).getWorkflows()) {
            if (run.getLifecycleTasks() == null) {
                continue;
            }
            for (var result : run.getLifecycleTasks()) {
                if (MARKER.equals(result.getKey())) {
                    found = result.getResult();
                }
            }
        }
        if (found == null) {
            return null;
        }
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put(FIELD_DRAFT, draftOf(found));
        return marker;
    }

    /** The draft of a paused snapshot's reply review, or null. */
    public static String draftIn(ConversationMemorySnapshot snapshot) {
        Map<String, Object> marker = pendingIn(snapshot);
        return marker == null ? null : (String) marker.get(FIELD_DRAFT);
    }

    /**
     * The most characters an approver's reply may have: the agent's
     * {@code review.maxEditChars} (or its default), and never less than the draft
     * plus room to extend it, up to the ceiling.
     */
    public static int maxEditChars(HitlReviewConfig review, String draft) {
        int configured = review != null ? review.effectiveMaxEditChars() : HitlReviewConfig.DEFAULT_MAX_EDIT_CHARS;
        int draftChars = draft == null ? 0 : draft.codePointCount(0, draft.length());
        return Math.min(HitlReviewConfig.MAX_EDIT_CHARS_CEILING, Math.max(configured, draftChars + 2000));
    }

    /**
     * What the reply review of one conversation output (the mail's step) says, for
     * the email channel.
     */
    public enum State {
        /** The turn was not armed: the reply goes as the turn wrote it. */
        NOT_ARMED,
        /**
         * Armed and not decided: the turn has not reached its review (it paused
         * earlier, or ended with nothing to send or undecided).
         */
        UNDECIDED,
        /** The review itself waits. */
        PENDING,
        /** Approved (as drafted or edited). */
        APPROVED,
        /** Rejected or expired. */
        REJECTED
    }

    /** The review state of the mail's step output {@code output}. */
    public static State stateOf(Map<String, Object> output) {
        if (output == null) {
            return State.NOT_ARMED;
        }
        Object decided = output.get(OUTPUT_KEY);
        if (decided != null) {
            String value = String.valueOf(decided);
            if (PENDING.equals(value)) {
                return State.PENDING;
            }
            // Anything else that is not an approval is read as a rejection: a value
            // that cannot be read must not send the reply.
            return APPROVED.equals(value) || EDITED.equals(value) ? State.APPROVED : State.REJECTED;
        }
        return output.get("context") instanceof Map<?, ?> context && context.containsKey(CONTEXT_KEY) ? State.UNDECIDED : State.NOT_ARMED;
    }

    /** The approver's text on the mail's step output, or null when not edited. */
    public static String editedText(Map<String, Object> output) {
        return output != null && output.get(TEXT_OUTPUT_KEY) instanceof String text && !text.isBlank() ? text : null;
    }

    private static String stepTaint(IData<?> datum) {
        Object value = datum == null ? null : datum.getResult();
        if (value instanceof Context context) {
            value = context.getValue();
        } else if (value instanceof Map<?, ?> map) {
            value = map.get("value");
        }
        return value == null ? null : TurnOrigin.normalise(String.valueOf(value));
    }
}
