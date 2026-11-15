/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.audit.IAuditEntryCollector;
import ai.labs.eddi.engine.hitl.audit.EditMetrics;
import ai.labs.eddi.engine.hitl.audit.HitlApprovalAudit;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit;
import ai.labs.eddi.engine.hitl.review.HitlReviewDetails;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.lifecycle.model.ReviewerEditRequest;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * "Approve with edits" of a RULE pause ({@code hitlConfig.review
 * .allowAnswerEdits}): hands the approver's text to the task that gave the
 * draft and records what happened in the step.
 * <ul>
 * <li>applied — {@code hitl:edit:applied} (digests and sizes, never the
 * original text: it stays in the paused turn's LLM audit entry), conversation
 * output {@code hitlAnswerEdited = "true"} and {@code hitl:edit:outcome}.</li>
 * <li>refused (screened out, configuration drift) — {@code hitl:edit:outcome},
 * {@value HitlReviewDetails#LAST_EDIT_REFUSED_KEY} (shown on the next pause as
 * {@code lastEditRefused}) and a {@code hitl.edit.refused} audit entry; the
 * caller pauses the turn again on the unchanged draft.</li>
 * </ul>
 *
 * @since 6.7.0
 */
final class ReviewerEditStep {

    private static final Logger LOGGER = Logger.getLogger(ReviewerEditStep.class);

    /** Step data key of an applied edit. */
    static final String APPLIED_STEP_KEY = "hitl:edit:applied";
    /** Step data key of the edit's outcome: {@code {outcome}}. */
    static final String OUTCOME_STEP_KEY = "hitl:edit:outcome";
    /** Conversation output key ({@code {memory.current.hitlAnswerEdited}}). */
    static final String EDITED_OUTPUT_KEY = "hitlAnswerEdited";
    /** Counter of edit outcomes. */
    static final String EDIT_METER = "eddi_hitl_answer_edit_total";

    private ReviewerEditStep() {
    }

    /**
     * Whether {@code edited} carries the redaction placeholder although the stored
     * {@code draft} does not, i.e. the placeholder came from the served view.
     */
    static boolean replacesConcealedContent(String draft, String edited) {
        return edited != null && edited.contains(SecretRedactionFilter.REDACTED)
                && (draft == null || !draft.contains(SecretRedactionFilter.REDACTED));
    }

    /**
     * Applies the decision's {@code editedAnswer} to the draft the candidate
     * describes.
     *
     * @param workflow
     *            the workflow of the answer, or null when it no longer exists
     */
    static ReviewerEditRequest.Outcome apply(IConversationMemory memory, IWritableConversationStep step, IExecutableWorkflow workflow,
                                             HitlRevisionCandidate candidate, HitlDecision decision, boolean screen)
            throws LifecycleException {
        ReviewerEditRequest.Outcome outcome;
        if (workflow == null) {
            outcome = ReviewerEditRequest.Outcome.refused(HitlApprovalAudit.REFUSED_CONFIG_DRIFT, null);
        } else if (replacesConcealedContent(candidate.getDraft(), decision.getEditedAnswer())) {
            // The approver edited the REDACTED view (HitlReviewDetails.forRule), whose
            // placeholders stand for text they never saw. Applying the whole string
            // would overwrite that text with the placeholder.
            outcome = ReviewerEditRequest.Outcome.refused(HitlApprovalAudit.REFUSED_REDACTED_CONTENT, null);
        } else {
            step.setCurrentWorkflowId(workflow.getWorkflowId());
            outcome = workflow.getLifecycleManager().replaceAnswerAt(memory, candidate.getLifecycleIndex(),
                    new ReviewerEditRequest(candidate, decision.getEditedAnswer(), decision.getDecidedBy(), screen));
        }
        Metrics.counter(EDIT_METER, "outcome", outcome.applied() ? "applied" : outcome.refusal().toLowerCase(Locale.ROOT)).increment();
        if (outcome.applied()) {
            var applied = new LinkedHashMap<String, Object>();
            applied.put("originalSha256", EditMetrics.sha256Hex(candidate.getDraft()));
            applied.put("editedSha256", EditMetrics.sha256Hex(outcome.text()));
            applied.put("originalChars", candidate.getDraft() != null ? candidate.getDraft().codePointCount(0, candidate.getDraft().length()) : 0);
            applied.put("editedChars", outcome.text().codePointCount(0, outcome.text().length()));
            applied.put("decidedBy", decision.getDecidedBy());
            if (decision.getDecisionId() != null) {
                applied.put("decisionId", decision.getDecisionId());
            }
            step.storeData(new Data<>(APPLIED_STEP_KEY, applied));
            step.removeData(HitlReviewDetails.LAST_EDIT_REFUSED_KEY);
            step.storeData(new Data<>(OUTCOME_STEP_KEY, Map.of("outcome", "applied")));
            step.addConversationOutputString(EDITED_OUTPUT_KEY, "true");
            return outcome;
        }
        LOGGER.infof("HITL answer edit for conversation '%s' not applied (%s) — pausing again on the unchanged draft",
                sanitize(memory.getConversationId()), outcome.refusal());
        step.storeData(new Data<>(OUTCOME_STEP_KEY, Map.of("outcome", outcome.refusal().toLowerCase(Locale.ROOT))));
        var refused = new LinkedHashMap<String, Object>();
        refused.put("reason", outcome.refusal());
        if (outcome.guardrail() != null) {
            refused.put("guardrail", outcome.guardrail());
        }
        refused.put("at", Instant.now().toString());
        step.storeData(new Data<>(HitlReviewDetails.LAST_EDIT_REFUSED_KEY, refused));
        IAuditEntryCollector collector = memory.getAuditCollector();
        if (collector != null && decision.getDecisionId() != null) {
            try {
                collector.collect(HitlApprovalAudit.editRefusedEntry(decision.getDecisionId(), memory.getConversationId(), memory.getAgentId(),
                        memory.getAgentVersion(), memory.getUserId(), null, HitlDecisionUnit.UnitKind.ANSWER, outcome.refusal(),
                        outcome.guardrail()));
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not record the refused edit of %s: %s", sanitize(memory.getConversationId()), e.getMessage());
            }
        }
        return outcome;
    }
}
