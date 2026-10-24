/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a model answer needs to be revised after a human rejects it at a later
 * behavior-rule pause ({@code hitlConfig.onRejection: REVISE}).
 * <p>
 * The LLM task records one on the live memory for every answer it gives while
 * the policy is on; {@code LifecycleManager} stamps where in the pipeline the
 * task sits. When a RULE pause follows in the same workflow, the conversation
 * persists the candidate in the paused step under {@link #STEP_KEY} — step
 * data, so it travels with the step through every store and is scrubbed of
 * secret input like the rest of the step — and removes it again when the pause
 * is decided.
 * <p>
 * {@link #getTranscriptJson()} is the request that produced the answer: the
 * system message, the windowed history, the user's message and any tool
 * exchange, frozen like a tool pause's transcript. The revision appends the
 * draft as the assistant's turn and the reviewer's note as a user turn, so the
 * model revises against exactly what it saw. The note is never template source.
 *
 * @since 6.6.0
 */
public class HitlRevisionCandidate {

    /**
     * Step data key of a persisted candidate. Internal: withheld from the detailed
     * snapshot and from an approver's full snapshot.
     */
    public static final String STEP_KEY = "hitl:revision:candidate";

    /**
     * Step data key of the revision outcome of a rejected pause: {@code {outcome,
     * round}}, where {@code outcome} is one of {@code revised}, {@code no_note},
     * {@code automated}, {@code no_answer}, {@code exhausted} or {@code failed}.
     * Written only when the policy is {@code REVISE}.
     */
    public static final String OUTCOME_STEP_KEY = "hitl:revision:outcome";

    private String workflowId;
    private int lifecycleIndex = -1;
    private String llmTaskId;
    private int llmTaskIndex = -1;
    private Integer cascadeStepIndex;
    private boolean toolTranscript;
    private String transcriptJson;
    private String draft;
    private boolean addedToOutput;
    private int outputMark = -1;
    private int quickRepliesMark = -1;
    private int roundsUsed;

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    /** Absolute index of the LLM lifecycle task in its workflow; -1 = unstamped. */
    public int getLifecycleIndex() {
        return lifecycleIndex;
    }

    public void setLifecycleIndex(int lifecycleIndex) {
        this.lifecycleIndex = lifecycleIndex;
    }

    /** Id of the task inside the LLM configuration that gave the answer. */
    public String getLlmTaskId() {
        return llmTaskId;
    }

    public void setLlmTaskId(String llmTaskId) {
        this.llmTaskId = llmTaskId;
    }

    /** Index of that task in the LLM configuration's task list. */
    public int getLlmTaskIndex() {
        return llmTaskIndex;
    }

    public void setLlmTaskIndex(int llmTaskIndex) {
        this.llmTaskIndex = llmTaskIndex;
    }

    /** The cascade step whose answer was accepted; null outside a cascade. */
    public Integer getCascadeStepIndex() {
        return cascadeStepIndex;
    }

    public void setCascadeStepIndex(Integer cascadeStepIndex) {
        this.cascadeStepIndex = cascadeStepIndex;
    }

    /** Whether the transcript holds a tool exchange (re-asked tool-less). */
    public boolean isToolTranscript() {
        return toolTranscript;
    }

    public void setToolTranscript(boolean toolTranscript) {
        this.toolTranscript = toolTranscript;
    }

    public String getTranscriptJson() {
        return transcriptJson;
    }

    public void setTranscriptJson(String transcriptJson) {
        this.transcriptJson = transcriptJson;
    }

    /** The answer under review, as the task stored it. */
    public String getDraft() {
        return draft;
    }

    public void setDraft(String draft) {
        this.draft = draft;
    }

    /** Whether the task added the answer to the conversation output. */
    public boolean isAddedToOutput() {
        return addedToOutput;
    }

    public void setAddedToOutput(boolean addedToOutput) {
        this.addedToOutput = addedToOutput;
    }

    /**
     * Length of the step's {@code output} list before the task wrote its answer:
     * everything from here on was written by the answer or by tasks that run again
     * for the revision, and is cut back before they do. -1 = unknown.
     */
    public int getOutputMark() {
        return outputMark;
    }

    public void setOutputMark(int outputMark) {
        this.outputMark = outputMark;
    }

    /** Same as {@link #getOutputMark()} for the {@code quickReplies} list. */
    public int getQuickRepliesMark() {
        return quickRepliesMark;
    }

    public void setQuickRepliesMark(int quickRepliesMark) {
        this.quickRepliesMark = quickRepliesMark;
    }

    /** Revisions this turn has already taken before this answer. */
    public int getRoundsUsed() {
        return roundsUsed;
    }

    public void setRoundsUsed(int roundsUsed) {
        this.roundsUsed = roundsUsed;
    }

    /** The persisted (step data) form: plain map, values JSON scalars. */
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("workflowId", workflowId);
        map.put("lifecycleIndex", lifecycleIndex);
        map.put("llmTaskId", llmTaskId);
        map.put("llmTaskIndex", llmTaskIndex);
        if (cascadeStepIndex != null) {
            map.put("cascadeStepIndex", cascadeStepIndex);
        }
        map.put("toolTranscript", toolTranscript);
        map.put("transcriptJson", transcriptJson);
        map.put("draft", draft);
        map.put("addedToOutput", addedToOutput);
        map.put("outputMark", outputMark);
        map.put("quickRepliesMark", quickRepliesMark);
        map.put("roundsUsed", roundsUsed);
        return map;
    }

    /**
     * Reads the persisted form back. Numbers may come back as any {@link Number}
     * (BSON, JSON), so every int is read through it. Anything that is not a map
     * yields {@code null}.
     */
    public static HitlRevisionCandidate fromMap(Object stored) {
        if (!(stored instanceof Map<?, ?> map)) {
            return null;
        }
        var candidate = new HitlRevisionCandidate();
        candidate.setWorkflowId(asString(map.get("workflowId")));
        candidate.setLifecycleIndex(asInt(map.get("lifecycleIndex"), -1));
        candidate.setLlmTaskId(asString(map.get("llmTaskId")));
        candidate.setLlmTaskIndex(asInt(map.get("llmTaskIndex"), -1));
        Object cascade = map.get("cascadeStepIndex");
        candidate.setCascadeStepIndex(cascade instanceof Number number ? number.intValue() : null);
        candidate.setToolTranscript(Boolean.TRUE.equals(map.get("toolTranscript")));
        candidate.setTranscriptJson(asString(map.get("transcriptJson")));
        candidate.setDraft(asString(map.get("draft")));
        candidate.setAddedToOutput(Boolean.TRUE.equals(map.get("addedToOutput")));
        candidate.setOutputMark(asInt(map.get("outputMark"), -1));
        candidate.setQuickRepliesMark(asInt(map.get("quickRepliesMark"), -1));
        candidate.setRoundsUsed(asInt(map.get("roundsUsed"), 0));
        return candidate;
    }

    private static String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
