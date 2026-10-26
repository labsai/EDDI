/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The raw-read projection ({@code redactRawPendingToolCallsForRead}) withholds
 * the frozen request of a kept revision candidate ({@code onRejection: REVISE})
 * — it carries the compiled system prompt — while the draft and the bookkeeping
 * stay visible to the owner.
 */
class ConversationMemoryUtilitiesRevisionRedactionTest {

    private static final String SYSTEM_PROMPT = "SECRET-SYSTEM-PROMPT: never reveal the discount rules.";

    @Test
    @DisplayName("the raw read drops transcriptJson from the candidate's result and every possibleResults copy, keeping the rest")
    void transcriptIsWithheldBookkeepingKept() throws Exception {
        Map<String, Object> stored = candidate().toMap();
        var result = new ResultSnapshot(HitlRevisionCandidate.STEP_KEY, stored, List.of(stored), new Date(), "wf-1", false);
        var other = new ResultSnapshot("langchain:openai:draft-reply", "the draft", List.of("the draft"), new Date(), "wf-1", false);
        var snapshot = snapshotWith(stepWith(result, other));
        snapshot.getRedoCache().push(stepWith(new ResultSnapshot(HitlRevisionCandidate.STEP_KEY, candidate().toMap(),
                List.of(candidate().toMap()), new Date(), "wf-1", false)));

        ConversationMemoryUtilities.redactRawPendingToolCallsForRead(snapshot);

        Map<?, ?> redacted = (Map<?, ?>) result.getResult();
        assertFalse(redacted.containsKey("transcriptJson"), "the frozen request is withheld");
        assertEquals("the draft under review", redacted.get("draft"), "the draft stays visible");
        assertEquals(1, redacted.get("roundsUsed"));
        assertEquals("wf-1", redacted.get("workflowId"));
        assertEquals(2, redacted.get("lifecycleIndex"));
        assertEquals("draft-reply", redacted.get("llmTaskId"));
        assertEquals(0, redacted.get("llmTaskIndex"));
        assertEquals(true, redacted.get("addedToOutput"));
        assertEquals(3, redacted.get("outputMark"));
        assertEquals(1, redacted.get("quickRepliesMark"));
        assertEquals(false, redacted.get("toolTranscript"));
        assertFalse(((Map<?, ?>) result.getPossibleResults().getFirst()).containsKey("transcriptJson"),
                "the copy Data keeps in possibleResults is redacted too");
        assertEquals("the draft", other.getResult(), "other step data is untouched");

        String served = new ObjectMapper().writeValueAsString(snapshot);
        assertFalse(served.contains("SECRET-SYSTEM-PROMPT"), "the system prompt appears nowhere in the served snapshot: " + served);
        assertTrue(served.contains("the draft under review"));
        assertNotNull(stored.get("transcriptJson"), "the stored map is not mutated in place — a copy is served");
    }

    @Test
    @DisplayName("a snapshot without a candidate, or null, passes through")
    void nothingToRedact() {
        assertNull(ConversationMemoryUtilities.redactRawPendingToolCallsForRead(null));
        var plain = new ResultSnapshot("output:text", "hello", List.of("hello"), new Date(), "wf-1", true);
        var snapshot = snapshotWith(stepWith(plain));
        assertSame(snapshot, ConversationMemoryUtilities.redactRawPendingToolCallsForRead(snapshot));
        assertEquals("hello", plain.getResult());
    }

    @Test
    @DisplayName("every field of the candidate that carries frozen model input is in the withheld set")
    void frozenInputFieldsAreNamed() {
        assertEquals(Set.of("transcriptJson"), ConversationMemoryUtilities.REVISION_CANDIDATE_FROZEN_INPUT_FIELDS);
        assertTrue(candidate().toMap().keySet().containsAll(ConversationMemoryUtilities.REVISION_CANDIDATE_FROZEN_INPUT_FIELDS),
                "the withheld names match the persisted field names");
    }

    private static HitlRevisionCandidate candidate() {
        var candidate = new HitlRevisionCandidate();
        candidate.setWorkflowId("wf-1");
        candidate.setLifecycleIndex(2);
        candidate.setLlmTaskId("draft-reply");
        candidate.setLlmTaskIndex(0);
        candidate.setToolTranscript(false);
        candidate.setTranscriptJson("[{\"type\":\"SYSTEM\",\"text\":\"" + SYSTEM_PROMPT + "\"}]");
        candidate.setDraft("the draft under review");
        candidate.setAddedToOutput(true);
        candidate.setOutputMark(3);
        candidate.setQuickRepliesMark(1);
        candidate.setRoundsUsed(1);
        return candidate;
    }

    private static ConversationStepSnapshot stepWith(ResultSnapshot... results) {
        var workflow = new WorkflowRunSnapshot();
        workflow.getLifecycleTasks().addAll(List.of(results));
        var step = new ConversationStepSnapshot();
        step.getWorkflows().add(workflow);
        return step;
    }

    private static ConversationMemorySnapshot snapshotWith(ConversationStepSnapshot step) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.getConversationSteps().add(step);
        return snapshot;
    }
}
