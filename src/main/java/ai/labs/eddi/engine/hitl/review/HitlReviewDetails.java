/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.review;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The review fields {@code approval-status} adds to a paused conversation's
 * {@code pauseDetails}: the approval category, the reason-code vocabulary, the
 * editable draft of a RULE pause and the refusal of the last edit. Computed at
 * read time, never persisted.
 *
 * @since 6.7.0
 */
public final class HitlReviewDetails {

    /** Step data key of the last edit the engine refused (see ReviewerEditStep). */
    public static final String LAST_EDIT_REFUSED_KEY = "hitl:edit:refused";

    private HitlReviewDetails() {
    }

    /**
     * Adds the review fields of a RULE pause to {@code details}.
     *
     * @param hitlConfig
     *            the agent's pinned HITL config, or null
     */
    public static void forRule(Map<String, Object> details, ConversationMemorySnapshot snapshot, AgentConfiguration.HitlConfig hitlConfig) {
        HitlReviewConfig review = hitlConfig != null ? hitlConfig.getReview() : null;
        var category = ApprovalCategories.forRule(hitlConfig, snapshot.getAgentId());
        details.put("approvalCategory", category.category());
        details.put("reasonCodes", ReasonCodes.asList(review != null ? review.getReasonCodes() : null));
        HitlRevisionCandidate candidate = HitlDecisionValidator.candidateOf(snapshot);
        if (review != null && review.answerEditsAllowed() && candidate != null && candidate.getDraft() != null) {
            var editable = new LinkedHashMap<String, Object>();
            // Served through the current secret filter, like every model text an
            // approver reads off this endpoint.
            editable.put("draft", SecretRedactionFilter.redact(candidate.getDraft()));
            editable.put("format", HitlRevisionCandidate.FORMAT_JSON.equals(candidate.getAnswerFormat()) ? "json" : "text");
            editable.put("maxChars", review.effectiveMaxEditChars());
            details.put("editableAnswer", editable);
        }
        Object refused = lastEditRefused(snapshot);
        if (refused != null) {
            details.put("lastEditRefused", refused);
        }
    }

    /**
     * Adds the review fields of a TOOL_CALL pause: the vocabulary on the details,
     * the category on each call view (matched by {@code callId}).
     */
    public static void forToolCall(Map<String, Object> details, PendingToolCallBatch batch, AgentConfiguration.HitlConfig hitlConfig) {
        HitlReviewConfig review = hitlConfig != null ? hitlConfig.getReview() : null;
        details.put("reasonCodes", ReasonCodes.asList(review != null ? review.getReasonCodes() : null));
        if (batch == null || batch.getCalls() == null || !(details.get("calls") instanceof List<?> views)) {
            return;
        }
        Map<String, String> categories = new LinkedHashMap<>();
        for (PendingToolCallBatch.PendingToolCall call : batch.getCalls()) {
            if (call != null && call.getCallId() != null) {
                categories.put(call.getCallId(),
                        ApprovalCategories.forToolCall(batch.getEffectiveToolApprovals(), call.getGateReason(), call.getMatchedRule()).category());
            }
        }
        for (Object view : views) {
            if (view instanceof Map<?, ?> map && map.get("callId") instanceof String callId && categories.containsKey(callId)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> callView = (Map<String, Object>) map;
                callView.put("approvalCategory", categories.get(callId));
            }
        }
    }

    private static Object lastEditRefused(ConversationMemorySnapshot snapshot) {
        var steps = snapshot.getConversationSteps();
        if (steps == null || steps.isEmpty() || steps.get(steps.size() - 1).getWorkflows() == null) {
            return null;
        }
        Object found = null;
        for (var run : steps.get(steps.size() - 1).getWorkflows()) {
            if (run.getLifecycleTasks() == null) {
                continue;
            }
            for (var result : run.getLifecycleTasks()) {
                if (LAST_EDIT_REFUSED_KEY.equals(result.getKey())) {
                    found = result.getResult();
                }
            }
        }
        return found;
    }
}
