/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.hitl.review.HitlDecisionValidator;
import ai.labs.eddi.engine.hitl.review.ReasonCodes;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "✏️ Edit &amp; approve" and "Reject with reason…" on a Slack approval card
 * (regular conversations; group cards keep Approve/Reject).
 * <ul>
 * <li>{@link #forCard} decides which extra buttons a card gets. A TOOL_CALL
 * pause offers Edit when the batch holds exactly one call whose redacted
 * arguments fit Slack's 3000-character input; a RULE pause only when the agent
 * allows answer edits, the integration opted in with
 * {@code platformConfig.hitlShareRuleDrafts=true} (drafts reach Slack only with
 * this flag) and the draft fits and holds no secret.</li>
 * <li>{@link #editView} / {@link #rejectView} build the modals, opened with
 * {@code views.open}; {@code private_metadata} binds a submission to the card,
 * the pause and the call it was opened for.</li>
 * <li>{@link #decisionOf} turns a {@code view_submission} into the decision the
 * resume takes — edited tool arguments are rebuilt server-side by
 * {@link SlackRedactedArgumentsMerge}, so redacted secrets never travel through
 * Slack.</li>
 * </ul>
 *
 * @since 6.7.0
 */
public final class SlackHitlEditModal {

    /**
     * Integration setting: share RULE drafts with Slack so they can be edited
     * there.
     */
    public static final String CFG_HITL_SHARE_RULE_DRAFTS = "hitlShareRuleDrafts";
    /** Button: open the edit modal. */
    public static final String ACTION_EDIT = "hitl_edit";
    /** Button: open the reject-with-reason modal. */
    public static final String ACTION_REJECT_REASON = "hitl_reject_reason";
    /** {@code callback_id} of the edit modal. */
    public static final String CALLBACK_EDIT = "hitl_edit_submit";
    /** {@code callback_id} of the reject modal. */
    public static final String CALLBACK_REJECT = "hitl_reject_submit";
    /** Slack's {@code plain_text_input} limit. */
    public static final int SLACK_INPUT_MAX = 3000;
    /** Slack's {@code private_metadata} limit. */
    static final int PRIVATE_METADATA_MAX = 3000;

    static final String BLOCK_EDIT = "edit";
    static final String BLOCK_REASON = "reason";
    static final String BLOCK_NOTE = "note";
    private static final String ACTION_INPUT = "value";
    private static final int NOTE_MAX = 1000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private SlackHitlEditModal() {
    }

    /**
     * The extra controls of an approval card.
     *
     * @param edit
     *            show "✏️ Edit &amp; approve"
     * @param rejectWithReason
     *            show "Reject with reason…"
     * @param hint
     *            a context line shown above the buttons (e.g. "edit in the
     *            Manager"), or null
     */
    public record CardEdits(boolean edit, boolean rejectWithReason, String hint) {
        /** A card with Approve/Reject only — the pre-6.7 card. */
        public static final CardEdits NONE = new CardEdits(false, false, null);
    }

    /** The controls of a regular conversation's card. */
    public static CardEdits forCard(String pauseType, PendingToolCallBatch batch, ConversationMemorySnapshot snapshot,
                                    Map<String, String> platformConfig) {
        if (SlackHitlSupport.PAUSE_TYPE_TOOL_CALL.equals(pauseType)) {
            var calls = batch != null && batch.getCalls() != null ? batch.getCalls() : List.<PendingToolCallBatch.PendingToolCall>of();
            if (calls.size() == 1 && calls.get(0) != null && !calls.get(0).isArgsTruncated()) {
                // the same pretty-printed text the modal will show, so the button never
                // appears for a modal that would not open
                String shown = pretty(shownArguments(calls.get(0)));
                if (shown != null && shown.length() <= SLACK_INPUT_MAX) {
                    return new CardEdits(true, true, null);
                }
            }
            return new CardEdits(false, true, calls.size() > 1 ? "Several calls — edit them one by one in the Manager." : null);
        }
        HitlRevisionCandidate candidate = HitlDecisionValidator.candidateOf(snapshot);
        if (candidate == null || !candidate.isEditable() || candidate.getDraft() == null) {
            return new CardEdits(false, true, null);
        }
        boolean shareDrafts = platformConfig != null
                && "true".equalsIgnoreCase(String.valueOf(platformConfig.get(CFG_HITL_SHARE_RULE_DRAFTS)).trim());
        String draft = candidate.getDraft();
        if (!shareDrafts) {
            return new CardEdits(false, true, "The answer can be edited before approval in the Manager.");
        }
        if (draft.length() > SLACK_INPUT_MAX || !draft.equals(SecretRedactionFilter.redact(draft))) {
            return new CardEdits(false, true, "The answer is too long or holds a secret — edit it in the Manager.");
        }
        return new CardEdits(true, true, null);
    }

    /**
     * The arguments a modal shows: the redacted arguments, re-redacted through the
     * current filter.
     */
    static String shownArguments(PendingToolCallBatch.PendingToolCall call) {
        return call != null ? SecretRedactionFilter.redact(call.getArgumentsRedacted()) : null;
    }

    // === private_metadata ===

    /**
     * What a modal is bound to.
     *
     * @param integration
     *            the owning integration
     * @param subject
     *            the conversation id
     * @param cardId
     *            the card the button was on
     * @param pauseId
     *            the pause the modal was opened for
     * @param channel
     *            the card's channel
     * @param ts
     *            the card's message ts
     * @param callId
     *            the tool call edited (TOOL_CALL), or null
     */
    public record Metadata(String integration, String subject, String cardId, String pauseId, String channel, String ts, String callId) {

        String encode() {
            var map = new LinkedHashMap<String, String>();
            map.put("i", integration);
            map.put("s", subject);
            map.put("c", cardId);
            map.put("p", pauseId);
            map.put("ch", channel);
            map.put("ts", ts);
            if (callId != null) {
                map.put("call", callId);
            }
            try {
                String encoded = JSON.writeValueAsString(map);
                return encoded.length() <= PRIVATE_METADATA_MAX ? encoded : null;
            } catch (Exception e) {
                return null;
            }
        }

        static Metadata decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            try {
                JsonNode node = JSON.readTree(encoded);
                return new Metadata(text(node, "i"), text(node, "s"), text(node, "c"), text(node, "p"), text(node, "ch"), text(node, "ts"),
                        text(node, "call"));
            } catch (Exception e) {
                return null;
            }
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value != null && value.isTextual() ? value.asText() : null;
        }
    }

    // === Views ===

    /**
     * The edit modal for the pause of {@code snapshot}, or null when there is
     * nothing Slack may edit (the caller then tells the approver to use the
     * Manager).
     */
    public static Map<String, Object> editView(Metadata metadata, ConversationMemorySnapshot snapshot, Map<String, String> reasonCodes,
                                               Map<String, String> platformConfig) {
        // Only what the card offered: a crafted interaction cannot open an edit the
        // card did not show (a RULE draft without hitlShareRuleDrafts, a secret).
        if (snapshot == null || !forCard(snapshot.getHitlPauseType(), snapshot.getHitlPendingToolCalls(), snapshot, platformConfig).edit()) {
            return null;
        }
        String initial;
        String label;
        String callId = null;
        if (SlackHitlSupport.PAUSE_TYPE_TOOL_CALL.equals(snapshot.getHitlPauseType())) {
            var batch = snapshot.getHitlPendingToolCalls();
            if (batch == null || batch.getCalls() == null || batch.getCalls().size() != 1 || batch.getCalls().get(0).isArgsTruncated()) {
                return null;
            }
            var call = batch.getCalls().get(0);
            initial = pretty(shownArguments(call));
            label = "Arguments of " + (call.getToolName() != null ? call.getToolName() : "the call") + " (JSON)";
            callId = call.getCallId();
        } else {
            HitlRevisionCandidate candidate = HitlDecisionValidator.candidateOf(snapshot);
            if (candidate == null || !candidate.isEditable() || candidate.getDraft() == null) {
                return null;
            }
            initial = candidate.getDraft();
            label = "Final answer";
        }
        if (initial == null || initial.length() > SLACK_INPUT_MAX) {
            return null;
        }
        var bound = new Metadata(metadata.integration(), metadata.subject(), metadata.cardId(), metadata.pauseId(), metadata.channel(),
                metadata.ts(), callId).encode();
        if (bound == null) {
            return null;
        }
        var blocks = new ArrayList<Map<String, Object>>();
        blocks.add(input(BLOCK_EDIT, label, textInput(true, initial, SLACK_INPUT_MAX), false));
        blocks.add(input(BLOCK_REASON, "Reason for the change", reasonSelect(reasonCodes), true));
        blocks.add(input(BLOCK_NOTE, "Note", textInput(true, null, NOTE_MAX), true));
        return view(CALLBACK_EDIT, "Edit & approve", "Approve", bound, blocks);
    }

    /** The reject-with-reason modal. */
    public static Map<String, Object> rejectView(Metadata metadata, Map<String, String> reasonCodes) {
        String bound = metadata.encode();
        if (bound == null) {
            return null;
        }
        var blocks = new ArrayList<Map<String, Object>>();
        blocks.add(input(BLOCK_REASON, "Reason", reasonSelect(reasonCodes), false));
        blocks.add(input(BLOCK_NOTE, "Note", textInput(true, null, NOTE_MAX), true));
        return view(CALLBACK_REJECT, "Reject", "Reject", bound, blocks);
    }

    private static String pretty(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    private static Map<String, Object> view(String callbackId, String title, String submit, String privateMetadata,
                                            List<Map<String, Object>> blocks) {
        var view = new LinkedHashMap<String, Object>();
        view.put("type", "modal");
        view.put("callback_id", callbackId);
        view.put("private_metadata", privateMetadata);
        view.put("title", plain(title));
        view.put("submit", plain(submit));
        view.put("close", plain("Cancel"));
        view.put("blocks", blocks);
        return view;
    }

    private static Map<String, Object> input(String blockId, String label, Map<String, Object> element, boolean optional) {
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "input");
        block.put("block_id", blockId);
        block.put("label", plain(label));
        block.put("element", element);
        block.put("optional", optional);
        return block;
    }

    private static Map<String, Object> textInput(boolean multiline, String initial, int maxLength) {
        var element = new LinkedHashMap<String, Object>();
        element.put("type", "plain_text_input");
        element.put("action_id", ACTION_INPUT);
        element.put("multiline", multiline);
        element.put("max_length", maxLength);
        if (initial != null && !initial.isEmpty()) {
            element.put("initial_value", initial);
        }
        return element;
    }

    private static Map<String, Object> reasonSelect(Map<String, String> reasonCodes) {
        var options = new ArrayList<Map<String, Object>>();
        for (var entry : ReasonCodes.effective(reasonCodes).entrySet()) {
            var option = new LinkedHashMap<String, Object>();
            String label = entry.getKey() + " — " + entry.getValue();
            option.put("text", plain(label.length() > 75 ? label.substring(0, 74) + "…" : label));
            option.put("value", entry.getKey());
            options.add(option);
        }
        var element = new LinkedHashMap<String, Object>();
        element.put("type", "static_select");
        element.put("action_id", ACTION_INPUT);
        element.put("options", options);
        return element;
    }

    private static Map<String, Object> plain(String text) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", "plain_text");
        map.put("text", text);
        return map;
    }

    // === Submission ===

    /** A refused submission: the input block and the message to show there. */
    public static final class SubmissionException extends Exception {
        private final String blockId;

        SubmissionException(String blockId, String message) {
            super(message);
            this.blockId = blockId;
        }

        public String blockId() {
            return blockId;
        }
    }

    /**
     * The decision a {@code view_submission} asks for.
     *
     * @param callbackId
     *            the modal ({@link #CALLBACK_EDIT} or {@link #CALLBACK_REJECT})
     * @param state
     *            {@code view.state.values}
     * @param snapshot
     *            the paused conversation (for the raw arguments of an edited call)
     * @param slackUserId
     *            the verified submitter
     */
    public static HitlDecision decisionOf(String callbackId, JsonNode state, Metadata metadata, ConversationMemorySnapshot snapshot,
                                          String slackUserId)
            throws SubmissionException {
        boolean edit = CALLBACK_EDIT.equals(callbackId);
        var decision = new HitlDecision();
        decision.setVerdict(edit ? HitlVerdict.APPROVED : HitlVerdict.REJECTED);
        decision.setPauseId(metadata.pauseId());
        decision.setDecidedBy("slack:" + slackUserId);
        String note = value(state, BLOCK_NOTE);
        decision.setNote(note != null && !note.isBlank() ? note.trim() : "Decided via Slack by " + slackUserId);
        String reason = selected(state, BLOCK_REASON);
        if (!edit) {
            if (reason == null) {
                throw new SubmissionException(BLOCK_REASON, "Choose a reason");
            }
            decision.setReasonCode(reason);
            return decision;
        }
        String text = value(state, BLOCK_EDIT);
        if (text == null || text.isBlank()) {
            throw new SubmissionException(BLOCK_EDIT, "Must not be empty");
        }
        if (metadata.callId() == null) {
            decision.setEditedAnswer(text);
            decision.setReasonCode(reason);
            return decision;
        }
        var batch = snapshot != null ? snapshot.getHitlPendingToolCalls() : null;
        PendingToolCallBatch.PendingToolCall call = batch != null && batch.getCalls() != null
                ? batch.getCalls().stream().filter(c -> c != null && metadata.callId().equals(c.getCallId())).findFirst().orElse(null)
                : null;
        if (call == null) {
            throw new SubmissionException(BLOCK_EDIT, "This request changed — close the dialog and decide on the current card");
        }
        String merged;
        try {
            merged = SlackRedactedArgumentsMerge.merge(call.getArgumentsRaw(), shownArguments(call), text);
        } catch (SlackRedactedArgumentsMerge.MergeException e) {
            throw new SubmissionException(BLOCK_EDIT, e.getMessage());
        }
        var callDecision = new ToolCallDecision();
        callDecision.setVerdict(HitlVerdict.APPROVED);
        callDecision.setAmendedArguments(merged);
        callDecision.setReasonCode(reason);
        decision.setToolDecisions(Map.of(call.getCallId(), callDecision));
        return decision;
    }

    private static String value(JsonNode state, String blockId) {
        JsonNode node = state != null ? state.path(blockId).path(ACTION_INPUT).path("value") : null;
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String selected(JsonNode state, String blockId) {
        JsonNode node = state != null ? state.path(blockId).path(ACTION_INPUT).path("selected_option").path("value") : null;
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    /** The block a validation message belongs to. */
    public static String blockFor(String message) {
        if (message == null) {
            return BLOCK_EDIT;
        }
        return message.startsWith("reasonCode") || message.contains("reasonCode") || message.contains("reason code") ? BLOCK_REASON : BLOCK_EDIT;
    }
}
