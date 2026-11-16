/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.hitl.model.GroupHitlReviewConfig;
import ai.labs.eddi.engine.hitl.audit.EditMetrics;
import ai.labs.eddi.engine.hitl.audit.EditSummary;
import ai.labs.eddi.engine.hitl.review.HitlDecisionRecords;
import ai.labs.eddi.engine.hitl.review.HitlDecisionValidator;
import ai.labs.eddi.engine.hitl.review.ReasonCodes;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.internal.TaskDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;

import ai.labs.eddi.utils.LogSanitizer;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The review part of a group approval ({@code review.allowResultEdits}, reason
 * codes): validates {@code taskDecisions}, a SYNTHESIS edit and reason codes
 * BEFORE anything is mutated, then applies the edits.
 * <ul>
 * <li>A TASK's edited result replaces {@code TaskItem.result} — what dependents
 * and the task summary read — and is noted in
 * {@code SharedTaskList.reviewerEdits}. The agent's original stays in its
 * signed {@code TASK_RESULT} transcript entry.</li>
 * <li>A SYNTHESIS phase's edited output is appended as an unsigned
 * {@code SYNTHESIS} transcript entry (speaker {@code hitl:<decidedBy>}, marker
 * {@code errorReason = "reviewer-edit"}): the "last synthesis" every later
 * phase and the final answer read. Signed entries are never rewritten.</li>
 * </ul>
 * One instance per approval request.
 *
 * @since 6.7.0
 */
final class GroupReviewEditor {

    private static final Logger LOGGER = Logger.getLogger(GroupReviewEditor.class);

    /** Speaker id prefix of a reviewer's transcript entry. */
    static final String REVIEWER_SPEAKER_PREFIX = "hitl:";
    /** {@code errorReason} marker of a reviewer's SYNTHESIS entry. */
    static final String REVIEWER_EDIT_MARKER = "reviewer-edit";

    private final Map<String, TaskDecision> taskDecisions;
    private final Map<String, HitlDecisionRecords.TaskOutcome> taskOutcomes = new LinkedHashMap<>();
    private final String synthesisEdit;
    private EditSummary synthesisSummary;

    private GroupReviewEditor(Map<String, TaskDecision> taskDecisions, String synthesisEdit) {
        this.taskDecisions = taskDecisions;
        this.synthesisEdit = synthesisEdit;
    }

    /**
     * Validates the review fields of {@code request} and, when it carries
     * {@code taskDecisions}, sets the equivalent {@code taskApprovals} on it so the
     * existing task validation and application run unchanged.
     *
     * @param phases
     *            the discussion's phases, read only for a SYNTHESIS edit
     * @throws IllegalArgumentException
     *             on any violation — nothing has been mutated
     */
    static GroupReviewEditor prepare(GroupConversation gc, GroupApprovalRequest request, AgentGroupConfiguration.HitlConfig hitlConfig,
                                     Supplier<List<DiscussionPhase>> phases) {
        try {
            return prepareChecked(gc, request, hitlConfig, phases);
        } catch (ReviewRefusalException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // A refusal written in this code base is written for the caller; re-typed so
            // REST can tell
            // it from an unrelated IllegalArgumentException. One thrown by the JDK or a
            // library
            // (its top frame is outside ai.labs.eddi) could carry text that was never meant
            // for a
            // client, so that case answers with a fixed message.
            StackTraceElement[] frames = e.getStackTrace();
            if (frames.length > 0 && frames[0].getClassName().startsWith("ai.labs.eddi.")) {
                throw new ReviewRefusalException(e.getMessage());
            }
            LOGGER.warnf("Unexpected IllegalArgumentException while validating a review: %s", LogSanitizer.sanitize(e.getMessage()));
            throw new ReviewRefusalException("the review fields could not be validated");
        }
    }

    private static GroupReviewEditor prepareChecked(GroupConversation gc, GroupApprovalRequest request,
                                                    AgentGroupConfiguration.HitlConfig hitlConfig, Supplier<List<DiscussionPhase>> phases) {
        GroupHitlReviewConfig review = hitlConfig != null ? hitlConfig.getReview() : null;
        Map<String, String> vocabulary = review != null ? review.getReasonCodes() : null;
        HitlDecision decision = request.getDecision();
        Map<String, TaskDecision> decisions = request.getTaskDecisions();
        boolean hasDecisions = decisions != null && !decisions.isEmpty();
        if (hasDecisions && request.getTaskApprovals() != null && !request.getTaskApprovals().isEmpty()) {
            throw new IllegalArgumentException("taskDecisions and taskApprovals are mutually exclusive — send one of them");
        }
        boolean anyChange = false;
        if (hasDecisions) {
            var approvals = new LinkedHashMap<String, String>();
            for (var entry : decisions.entrySet()) {
                String field = "taskDecisions['" + entry.getKey() + "']";
                TaskDecision task = entry.getValue();
                boolean taskEdited = false;
                if (task == null || task.getVerdict() == null) {
                    throw new IllegalArgumentException(field + ".verdict is required (APPROVED or REJECTED)");
                }
                if (task.getNote() != null && task.getNote().length() > HitlDecision.MAX_NOTE_LENGTH) {
                    throw new IllegalArgumentException(field + ".note exceeds the maximum length of " + HitlDecision.MAX_NOTE_LENGTH + " characters");
                }
                if (task.getEditedResult() != null) {
                    if (task.getVerdict() != HitlVerdict.APPROVED) {
                        throw new IllegalArgumentException(field + ".editedResult is only valid for an APPROVED task");
                    }
                    requireEditsAllowed(review);
                    HitlDecisionValidator.validateText(task.getEditedResult(), review.effectiveMaxEditChars(), field + ".editedResult");
                    // An edit that leaves the text as it was is a plain approval (approveEdited
                    // skips it), so it must not unlock an edit reason code either.
                    taskEdited = changesText(EditSummary.Kind.TASK_RESULT, originalResult(gc, entry.getKey()), task.getEditedResult());
                    anyChange |= taskEdited;
                }
                if (task.getVerdict() == HitlVerdict.REJECTED) {
                    anyChange = true;
                }
                if (task.getReasonCode() != null) {
                    ReasonCodes.require(task.getReasonCode(), vocabulary, field + ".reasonCode");
                    if (task.getVerdict() != HitlVerdict.REJECTED && !taskEdited) {
                        throw new IllegalArgumentException(field + ".reasonCode explains a rejection or an edit; a plain approval takes none");
                    }
                }
                approvals.put(entry.getKey(), task.getVerdict().name());
            }
            request.setTaskApprovals(approvals);
        }
        String synthesisEdit = null;
        if (decision != null && decision.getEditedAnswer() != null) {
            if (decision.getVerdict() != HitlVerdict.APPROVED) {
                throw new IllegalArgumentException("editedAnswer is only valid with verdict APPROVED");
            }
            if (gc.getHitlPauseType() != HitlPauseType.PHASE || !isSynthesisPhase(gc, phases)) {
                throw new IllegalArgumentException("phase outputs other than a synthesis cannot be edited — editedAnswer applies to a "
                        + "PHASE pause after a SYNTHESIS phase; edit task results with taskDecisions[id].editedResult");
            }
            requireEditsAllowed(review);
            HitlDecisionValidator.validateText(decision.getEditedAnswer(), review.effectiveMaxEditChars(), "editedAnswer");
            synthesisEdit = decision.getEditedAnswer();
            anyChange |= changesText(EditSummary.Kind.SYNTHESIS, latestSynthesis(gc), synthesisEdit);
        }
        if (request.getTaskApprovals() != null && request.getTaskApprovals().values().stream().anyMatch("REJECTED"::equalsIgnoreCase)) {
            anyChange = true;
        }
        if (decision != null && decision.getReasonCode() != null) {
            ReasonCodes.require(decision.getReasonCode(), vocabulary, "reasonCode");
            if (decision.getVerdict() != HitlVerdict.REJECTED && !anyChange) {
                throw new IllegalArgumentException("reasonCode explains a rejection or an edit; a plain approval takes none");
            }
        }
        var editor = new GroupReviewEditor(hasDecisions ? decisions : Map.of(), synthesisEdit);
        if (request.getTaskApprovals() != null) {
            request.getTaskApprovals().forEach((taskId, value) -> {
                TaskDecision task = editor.taskDecisions.get(taskId);
                editor.taskOutcomes.put(taskId, new HitlDecisionRecords.TaskOutcome(HitlVerdict.fromString(value),
                        task != null ? task.getReasonCode() : null, null));
            });
        }
        return editor;
    }

    private static boolean changesText(EditSummary.Kind kind, String original, String edited) {
        return EditMetrics.ofText(kind, original, edited).changed();
    }

    private static String originalResult(GroupConversation gc, String taskId) {
        var task = gc.getTaskList() != null ? gc.getTaskList().findById(taskId) : null;
        return task != null ? task.result() : null;
    }

    private static void requireEditsAllowed(GroupHitlReviewConfig review) {
        if (review == null || !review.resultEditsAllowed()) {
            throw new IllegalArgumentException("this group does not allow approving with edited results (hitlConfig.review.allowResultEdits is off)");
        }
    }

    private static boolean isSynthesisPhase(GroupConversation gc, Supplier<List<DiscussionPhase>> phases) {
        List<DiscussionPhase> list = phases != null ? phases.get() : null;
        int index = gc.getPausedAtPhaseIndex();
        if (list == null || index < 0 || index >= list.size()) {
            return false;
        }
        DiscussionPhase phase = list.get(index);
        return phase.type() == PhaseType.SYNTHESIS && (gc.getPausedPhaseName() == null || gc.getPausedPhaseName().equals(phase.name()));
    }

    /**
     * Approves {@code taskId} with its edited result when the decision carries one.
     *
     * @return whether the task was approved here (otherwise the caller approves it
     *         as it is)
     */
    boolean approveEdited(SharedTaskList taskList, String taskId, String decidedBy) {
        TaskDecision task = taskDecisions.get(taskId);
        if (task == null || task.getEditedResult() == null) {
            return false;
        }
        String original = taskList.findById(taskId) != null ? taskList.findById(taskId).result() : null;
        EditSummary summary = EditMetrics.ofText(EditSummary.Kind.TASK_RESULT, original, task.getEditedResult());
        if (!summary.changed()) {
            return false;
        }
        String originalText = original != null ? original : "";
        taskList.approveTaskWithEditedResult(taskId, task.getEditedResult(), new SharedTaskList.ReviewerEdit(decidedBy, Instant.now().toString(),
                summary.originalSha256(), originalText.codePointCount(0, originalText.length())));
        var outcome = taskOutcomes.get(taskId);
        taskOutcomes.put(taskId, new HitlDecisionRecords.TaskOutcome(HitlVerdict.APPROVED, outcome != null ? outcome.reasonCode() : null, summary));
        return true;
    }

    /** The task's own note when its decision carries one, else {@code fallback}. */
    String noteFor(String taskId, String fallback) {
        TaskDecision task = taskDecisions.get(taskId);
        return task != null && task.getNote() != null && !task.getNote().isBlank() ? task.getNote() : fallback;
    }

    /**
     * Appends the approver's SYNTHESIS edit, when there is one, as the latest
     * synthesis of the paused phase. Call before the resume persists.
     */
    TranscriptEntry appendSynthesisEdit(GroupConversation gc, String decidedBy) {
        if (synthesisEdit == null) {
            return null;
        }
        String original = latestSynthesis(gc);
        synthesisSummary = EditMetrics.ofText(EditSummary.Kind.SYNTHESIS, original, synthesisEdit);
        if (!synthesisSummary.changed()) {
            return null;
        }
        String speaker = REVIEWER_SPEAKER_PREFIX + (decidedBy != null ? decidedBy : "unknown");
        TranscriptEntry entry = new TranscriptEntry(speaker, "Reviewer", synthesisEdit, gc.getPausedAtPhaseIndex(),
                gc.getPausedPhaseName(), TranscriptEntryType.SYNTHESIS, Instant.now(), REVIEWER_EDIT_MARKER, null);
        synchronized (gc.getTranscript()) {
            gc.getTranscript().add(entry);
        }
        return entry;
    }

    /**
     * Takes the entry {@link #appendSynthesisEdit} returned back out of the
     * transcript, by identity: a resume that is rolled back must not leave the
     * approver's text behind for the next approval to build on.
     */
    void removeSynthesisEdit(GroupConversation gc, TranscriptEntry appended) {
        if (appended == null) {
            return;
        }
        synchronized (gc.getTranscript()) {
            gc.getTranscript().removeIf(e -> e == appended);
        }
    }

    private static String latestSynthesis(GroupConversation gc) {
        synchronized (gc.getTranscript()) {
            // the paused phase of the CURRENT round only: an earlier round's synthesis (or
            // another phase's) is not the answer the reviewer is looking at
            int roundStart = Math.max(0, Math.min(gc.getRoundStartTranscriptIndex(), gc.getTranscript().size()));
            for (int i = gc.getTranscript().size() - 1; i >= roundStart; i--) {
                TranscriptEntry entry = gc.getTranscript().get(i);
                if (entry.type() == TranscriptEntryType.SYNTHESIS && entry.phaseIndex() == gc.getPausedAtPhaseIndex()) {
                    return entry.content();
                }
            }
        }
        return null;
    }

    /** Per-task outcomes for the audit entry (edits included once applied). */
    Map<String, HitlDecisionRecords.TaskOutcome> taskOutcomes() {
        return taskOutcomes;
    }

    /** The SYNTHESIS edit's metrics once applied, else null. */
    EditSummary synthesisSummary() {
        return synthesisSummary;
    }
}
