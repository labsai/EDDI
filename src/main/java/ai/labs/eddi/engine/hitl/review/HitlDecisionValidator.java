/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.review;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Validates the review fields of a regular conversation's decision —
 * {@code editedAnswer}, {@code reasonCode} and
 * {@code toolDecisions[].reasonCode} — against the paused snapshot and the
 * agent's pinned {@code hitlConfig.review}, BEFORE the resume consumes the
 * pause. Every violation is an {@link IllegalArgumentException} (REST 400) and
 * leaves the pause untouched. Side-effect free.
 *
 * @since 6.7.0
 */
public final class HitlDecisionValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HitlDecisionValidator() {
    }

    /** Whether the decision carries any field this validator checks. */
    public static boolean hasReviewFields(HitlDecision decision) {
        if (decision == null) {
            return false;
        }
        if (decision.getEditedAnswer() != null || decision.getReasonCode() != null) {
            return true;
        }
        Map<String, ToolCallDecision> perCall = decision.getToolDecisions();
        return perCall != null && perCall.values().stream().anyMatch(d -> d != null && d.getReasonCode() != null);
    }

    /**
     * Validates the review fields of {@code decision}.
     *
     * @param snapshot
     *            the paused conversation
     * @param hitlConfig
     *            the agent's pinned HITL config, or null
     */
    public static void validate(HitlDecision decision, ConversationMemorySnapshot snapshot, AgentConfiguration.HitlConfig hitlConfig) {
        HitlReviewConfig review = hitlConfig != null ? hitlConfig.getReview() : null;
        Map<String, String> vocabulary = review != null ? review.getReasonCodes() : null;
        boolean toolPause = "TOOL_CALL".equals(snapshot.getHitlPauseType());
        HitlVerdict verdict = decision.getVerdict();

        if (decision.getEditedAnswer() != null) {
            validateEditedAnswer(decision, snapshot, review, toolPause);
        }

        boolean anyAmended = decision.getToolDecisions() != null
                && decision.getToolDecisions().values().stream().anyMatch(d -> d != null && d.getAmendedArguments() != null);
        if (decision.getReasonCode() != null) {
            ReasonCodes.require(decision.getReasonCode(), vocabulary, "reasonCode");
            boolean explainsAChange = verdict == HitlVerdict.REJECTED || decision.getEditedAnswer() != null || anyAmended
                    || anyCallRejected(decision);
            if (!explainsAChange) {
                throw new IllegalArgumentException("reasonCode explains a rejection or an edit; a plain approval takes none");
            }
        }
        if (decision.getToolDecisions() != null) {
            for (var entry : decision.getToolDecisions().entrySet()) {
                ToolCallDecision call = entry.getValue();
                if (call == null || call.getReasonCode() == null) {
                    continue;
                }
                String field = "toolDecisions['" + entry.getKey() + "'].reasonCode";
                ReasonCodes.require(call.getReasonCode(), vocabulary, field);
                HitlVerdict callVerdict = call.getVerdict() != null ? call.getVerdict() : verdict;
                if (callVerdict != HitlVerdict.REJECTED && call.getAmendedArguments() == null) {
                    throw new IllegalArgumentException(field + " explains a rejection or an amendment; a plain approval takes none");
                }
            }
        }
    }

    private static boolean anyCallRejected(HitlDecision decision) {
        return decision.getToolDecisions() != null
                && decision.getToolDecisions().values().stream().anyMatch(d -> d != null && d.getVerdict() == HitlVerdict.REJECTED);
    }

    private static void validateEditedAnswer(HitlDecision decision, ConversationMemorySnapshot snapshot, HitlReviewConfig review,
                                             boolean toolPause) {
        if (decision.getVerdict() != HitlVerdict.APPROVED) {
            throw new IllegalArgumentException("editedAnswer is only valid with verdict APPROVED — to reject, send a note instead");
        }
        if (toolPause) {
            throw new IllegalArgumentException("editedAnswer applies to rule pauses; amend a tool call with "
                    + "toolDecisions[callId].amendedArguments instead");
        }
        if (review == null || !review.answerEditsAllowed()) {
            throw new IllegalArgumentException("this agent does not allow approving with an edited answer "
                    + "(hitlConfig.review.allowAnswerEdits is off)");
        }
        HitlRevisionCandidate candidate = candidateOf(snapshot);
        if (candidate == null || candidate.getDraft() == null) {
            throw new IllegalArgumentException("there is no model answer to edit — the pause came before the model answered");
        }
        validateText(decision.getEditedAnswer(), review.effectiveMaxEditChars(), "editedAnswer");
        if (HitlRevisionCandidate.FORMAT_JSON.equals(candidate.getAnswerFormat())) {
            requireJsonObject(decision.getEditedAnswer(), "editedAnswer");
        }
    }

    /**
     * Checks an edited text: not blank, at most {@code maxChars} code points, no
     * control characters other than line breaks and tabs.
     */
    public static void validateText(String text, int maxChars, String field) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        int chars = text.codePointCount(0, text.length());
        if (chars > maxChars) {
            throw new IllegalArgumentException(field + " has " + chars + " characters; at most " + maxChars + " are allowed");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r') {
                throw new IllegalArgumentException(field + " contains a control character (code " + (int) c + ")");
            }
        }
    }

    /** Refuses text that is not a JSON object. */
    public static void requireJsonObject(String text, String field) {
        JsonNode node;
        try {
            node = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be a JSON object — this task answers in JSON (convertToObject)");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(field + " must be a JSON object — this task answers in JSON (convertToObject)");
        }
    }

    /**
     * The revision candidate a RULE pause kept in its (last) step, or null.
     */
    public static HitlRevisionCandidate candidateOf(ConversationMemorySnapshot snapshot) {
        List<ConversationMemorySnapshot.ConversationStepSnapshot> steps = snapshot != null ? snapshot.getConversationSteps() : null;
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        Object stored = null;
        var last = steps.get(steps.size() - 1);
        if (last.getWorkflows() == null) {
            return null;
        }
        for (var run : last.getWorkflows()) {
            if (run.getLifecycleTasks() == null) {
                continue;
            }
            for (var result : run.getLifecycleTasks()) {
                if (HitlRevisionCandidate.STEP_KEY.equals(result.getKey())) {
                    stored = result.getResult();
                }
            }
        }
        return HitlRevisionCandidate.fromMap(stored);
    }
}
