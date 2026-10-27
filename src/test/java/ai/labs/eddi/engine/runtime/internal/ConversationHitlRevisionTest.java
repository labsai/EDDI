/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.hitl.HitlRejectionAction;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionPolicy;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionRequest;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.undercouch.bson4jackson.BsonFactory;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code hitlConfig.onRejection: REVISE} at the conversation level: what a RULE
 * pause keeps, which rejections revise and which still skip, the per-turn
 * bound, and the round trip of the kept answer through both store
 * serializations.
 */
@DisplayName("Conversation — HITL revise with the reviewer's note (E2)")
class ConversationHitlRevisionTest {

    private static final String REJECTION_PREFIX = "This action was rejected by a human reviewer.";

    private ConversationMemory memory;
    private IPropertiesHandler propertiesHandler;
    private IExecutableWorkflow workflow;
    private ILifecycleManager lifecycleManager;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        // hex id: the BSON mapper stores the conversation id as an ObjectId
        memory = new ConversationMemory("64f0c0ffee00000000000001", "agent1", 1, "user1");
        propertiesHandler = mock(IPropertiesHandler.class);
        workflow = mock(IExecutableWorkflow.class);
        lifecycleManager = mock(ILifecycleManager.class);
        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        when(workflow.getWorkflowId()).thenReturn("wf1");
        when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
        meters = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(meters);
    }

    @AfterEach
    void tearDown() {
        Metrics.globalRegistry.remove(meters);
    }

    private Conversation conversation() {
        return new Conversation(List.of(workflow), memory, propertiesHandler, mock(IConversation.IConversationOutputRenderer.class));
    }

    private static HitlDecision decision(HitlVerdict verdict, String note, String decidedBy) {
        var d = new HitlDecision();
        d.setVerdict(verdict);
        d.setNote(note);
        d.setDecidedBy(decidedBy);
        return d;
    }

    private static HitlRevisionCandidate candidate(int lifecycleIndex, int roundsUsed) {
        var candidate = new HitlRevisionCandidate();
        candidate.setWorkflowId("wf1");
        candidate.setLifecycleIndex(lifecycleIndex);
        candidate.setLlmTaskId("writer");
        candidate.setLlmTaskIndex(0);
        candidate.setDraft("the draft");
        candidate.setTranscriptJson("[{\"type\":\"USER\"}]");
        candidate.setRoundsUsed(roundsUsed);
        return candidate;
    }

    private void revisePolicy(int max) {
        memory.setHitlRevisionPolicy(new HitlRevisionPolicy(HitlRejectionAction.REVISE, max));
    }

    /**
     * A RULE pause at task 3 of wf1 whose step kept the candidate (as a prior turn
     * left it).
     */
    private void pausedWith(HitlRevisionCandidate candidate) {
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedWorkflowId("wf1");
        memory.setHitlPausedAbsoluteTaskIndex(3);
        memory.setHitlPausedAt(Instant.now());
        memory.setHitlPauseType("RULE");
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ACTIONS.key(), List.of("answer", IConversation.PAUSE_CONVERSATION)));
        if (candidate != null) {
            memory.getCurrentStep().storeData(new Data<>(HitlRevisionCandidate.STEP_KEY, candidate.toMap()));
        }
    }

    private String output() {
        Object list = memory.getCurrentStep().getConversationOutput().get(MemoryKeys.OUTPUT_PREFIX);
        return list == null ? "" : list.toString();
    }

    @SuppressWarnings("unchecked")
    private String outcome() {
        var data = memory.getCurrentStep().getData(HitlRevisionCandidate.OUTCOME_STEP_KEY);
        return data == null ? null : (String) ((Map<String, Object>) data.getResult()).get("outcome");
    }

    private double meter(String outcome) {
        var counter = meters.find(Conversation.REVISION_METER).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Nested
    @DisplayName("what a RULE pause keeps")
    class Keep {

        private void pauseAfter(HitlRevisionCandidate candidate, int pausedIndex) throws Exception {
            memory.setConversationState(ConversationState.READY);
            doAnswer(inv -> {
                memory.setHitlRevisionCandidate(candidate);
                throw new ConversationPauseException("wf1", pausedIndex, "review the answer");
            }).when(lifecycleManager).executeLifecycle(any(), any());
            conversation().say("write it", Map.of());
        }

        @Test
        @DisplayName("REVISE: an answer given before the pause is kept in the paused step")
        void keptUnderRevise() throws Exception {
            revisePolicy(3);
            pauseAfter(candidate(1, 0), 3);

            assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
            var kept = HitlRevisionCandidate.fromMap(memory.getCurrentStep().getData(HitlRevisionCandidate.STEP_KEY).getResult());
            assertEquals("the draft", kept.getDraft());
            assertEquals(1, kept.getLifecycleIndex());
            assertNull(memory.getHitlRevisionCandidate(), "the transient copy is consumed");
        }

        @Test
        @DisplayName("default policy: nothing is kept")
        void nothingKeptByDefault() throws Exception {
            pauseAfter(candidate(1, 0), 3);

            assertNull(memory.getCurrentStep().getData(HitlRevisionCandidate.STEP_KEY));
        }

        @Test
        @DisplayName("an answer at or after the pause is not one the reviewer saw")
        void answerAfterPauseNotKept() throws Exception {
            revisePolicy(3);
            pauseAfter(candidate(3, 0), 3);

            assertNull(memory.getCurrentStep().getData(HitlRevisionCandidate.STEP_KEY));
        }

        @Test
        @DisplayName("a request over the size cap (no transcript) is not kept")
        void overCapNotKept() throws Exception {
            revisePolicy(3);
            var candidate = candidate(1, 0);
            candidate.setTranscriptJson(null);
            pauseAfter(candidate, 3);

            assertNull(memory.getCurrentStep().getData(HitlRevisionCandidate.STEP_KEY));
        }

        @Test
        @DisplayName("a TOOL_CALL pause keeps nothing — its rejection is a tool result")
        void toolPauseKeepsNothing() throws Exception {
            revisePolicy(3);
            memory.setConversationState(ConversationState.READY);
            doAnswer(inv -> {
                memory.setHitlRevisionCandidate(candidate(1, 0));
                memory.setHitlPendingToolCalls(new PendingToolCallBatch());
                throw new ConversationPauseException("wf1", 3, "gated", ConversationPauseException.PauseOrigin.TOOL_CALL);
            }).when(lifecycleManager).executeLifecycle(any(), any());

            conversation().say("do it", Map.of());

            assertNull(memory.getCurrentStep().getData(HitlRevisionCandidate.STEP_KEY));
        }
    }

    @Nested
    @DisplayName("a rejection under REVISE")
    class Rejection {

        @Test
        @DisplayName("with a human's note: the model revises and the pipeline continues after its task")
        void revisesWithNote() throws Exception {
            revisePolicy(3);
            pausedWith(candidate(1, 0));
            when(lifecycleManager.reviseAnswerAt(any(), anyInt(), any())).thenReturn(true);

            conversation().resume(decision(HitlVerdict.REJECTED, "  shorter please  ", "alice"));

            var captor = ArgumentCaptor.forClass(HitlRevisionRequest.class);
            verify(lifecycleManager).reviseAnswerAt(eq(memory), eq(1), captor.capture());
            assertEquals("shorter please", captor.getValue().note(), "the note, trimmed, as data");
            assertEquals(1, captor.getValue().round());
            verify(lifecycleManager).executeLifecycleFromIndex(memory, 2);
            assertFalse(output().contains(REJECTION_PREFIX), "a revised turn does not show the rejection");
            assertEquals("revised", outcome());
            assertEquals("1", memory.getCurrentStep().getConversationOutput().get(Conversation.HITL_REVISION_OUTPUT_KEY));
            assertNull(memory.getCurrentStep().getData(HitlRevisionCandidate.STEP_KEY), "the kept answer is consumed");
            assertEquals(ConversationState.READY, memory.getConversationState());
            assertEquals(1.0, meter("revised"));
        }

        @Test
        @DisplayName("without a note: rejected as today")
        void noNoteSkips() throws Exception {
            revisePolicy(3);
            pausedWith(candidate(1, 0));

            conversation().resume(decision(HitlVerdict.REJECTED, "   ", "alice"));

            verify(lifecycleManager, never()).reviseAnswerAt(any(), anyInt(), any());
            verify(lifecycleManager, never()).executeLifecycleFromIndex(any(), anyInt());
            assertTrue(output().contains(REJECTION_PREFIX));
            assertEquals("no_note", outcome());
        }

        @Test
        @DisplayName("a Slack button's attribution note is not a revision request")
        void slackButtonSkips() throws Exception {
            revisePolicy(3);
            pausedWith(candidate(1, 0));

            conversation().resume(decision(HitlVerdict.REJECTED, "Decided via Slack by U123", "slack:U123"));

            verify(lifecycleManager, never()).reviseAnswerAt(any(), anyInt(), any());
            assertEquals("no_note", outcome());
        }

        @Test
        @DisplayName("an automated rejection (timeout) never revises, even with a note")
        void automatedSkips() throws Exception {
            revisePolicy(3);
            pausedWith(candidate(1, 0));

            conversation().resume(decision(HitlVerdict.REJECTED, "timed out", "system:timeout"));

            verify(lifecycleManager, never()).reviseAnswerAt(any(), anyInt(), any());
            assertEquals("automated", outcome());
        }

        @Test
        @DisplayName("nothing to revise (the pause came before any answer): rejected as today")
        void noAnswerSkips() throws Exception {
            revisePolicy(3);
            pausedWith(null);

            conversation().resume(decision(HitlVerdict.REJECTED, "shorter", "alice"));

            verify(lifecycleManager, never()).reviseAnswerAt(any(), anyInt(), any());
            assertTrue(output().contains(REJECTION_PREFIX));
            assertEquals("no_answer", outcome());
        }

        @Test
        @DisplayName("the bound: once maxRevisionsPerTurn revisions were taken, a rejection skips")
        void exhaustedSkips() throws Exception {
            revisePolicy(2);
            pausedWith(candidate(1, 2));

            conversation().resume(decision(HitlVerdict.REJECTED, "again", "alice"));

            verify(lifecycleManager, never()).reviseAnswerAt(any(), anyInt(), any());
            assertTrue(output().contains(REJECTION_PREFIX));
            assertEquals("exhausted", outcome());
            assertEquals(1.0, meter("exhausted"));
        }

        @Test
        @DisplayName("the bound counts the revision in progress: one below the limit still revises")
        void lastRevisionAllowed() throws Exception {
            revisePolicy(2);
            pausedWith(candidate(1, 1));
            when(lifecycleManager.reviseAnswerAt(any(), anyInt(), any())).thenReturn(true);

            conversation().resume(decision(HitlVerdict.REJECTED, "again", "alice"));

            var captor = ArgumentCaptor.forClass(HitlRevisionRequest.class);
            verify(lifecycleManager).reviseAnswerAt(eq(memory), eq(1), captor.capture());
            assertEquals(2, captor.getValue().round());
        }

        @Test
        @DisplayName("the revision could not be produced: rejected as today, the pipeline does not run")
        void failedRevisionSkips() throws Exception {
            revisePolicy(3);
            pausedWith(candidate(1, 0));
            when(lifecycleManager.reviseAnswerAt(any(), anyInt(), any())).thenReturn(false);

            conversation().resume(decision(HitlVerdict.REJECTED, "shorter", "alice"));

            verify(lifecycleManager, never()).executeLifecycleFromIndex(any(), anyInt());
            assertTrue(output().contains(REJECTION_PREFIX));
            assertEquals("failed", outcome());
        }

        @Test
        @DisplayName("a revision that pauses again keeps the revised answer with its round count")
        void rePauseCarriesTheRound() throws Exception {
            revisePolicy(3);
            pausedWith(candidate(1, 0));
            when(lifecycleManager.reviseAnswerAt(any(), anyInt(), any())).thenAnswer(inv -> {
                var revised = candidate(1, 0);
                revised.setDraft("the revision");
                memory.setHitlRevisionCandidate(revised);
                return true;
            });
            doThrow(new ConversationPauseException("wf1", 3, "review again")).when(lifecycleManager).executeLifecycleFromIndex(memory, 2);

            conversation().resume(decision(HitlVerdict.REJECTED, "shorter", "alice"));

            assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
            var kept = HitlRevisionCandidate.fromMap(memory.getCurrentStep().getData(HitlRevisionCandidate.STEP_KEY).getResult());
            assertEquals("the revision", kept.getDraft());
            assertEquals(1, kept.getRoundsUsed());
        }
    }

    @Nested
    @DisplayName("unchanged behaviour")
    class Unchanged {

        @Test
        @DisplayName("default policy: a rejection with a note skips exactly as before, and records nothing new")
        void defaultPolicySkips() throws Exception {
            pausedWith(candidate(1, 0));

            conversation().resume(decision(HitlVerdict.REJECTED, "shorter", "alice"));

            verify(lifecycleManager, never()).reviseAnswerAt(any(), anyInt(), any());
            verify(lifecycleManager, never()).executeLifecycleFromIndex(any(), anyInt());
            assertTrue(output().contains(REJECTION_PREFIX + " Reason: shorter"));
            assertNull(outcome());
            assertNull(memory.getCurrentStep().getData(HitlRevisionCandidate.STEP_KEY));
        }

        @Test
        @DisplayName("APPROVED resumes after the pause as before, and drops the kept answer")
        void approvedUnchanged() throws Exception {
            revisePolicy(3);
            pausedWith(candidate(1, 0));

            conversation().resume(decision(HitlVerdict.APPROVED, "fine", "alice"));

            verify(lifecycleManager, never()).reviseAnswerAt(any(), anyInt(), any());
            verify(lifecycleManager).executeLifecycleFromIndex(memory, 4);
            assertNull(memory.getCurrentStep().getData(HitlRevisionCandidate.STEP_KEY));
        }

        @Test
        @DisplayName("a rejected TOOL_CALL pause re-enters the paused task as before")
        void toolCallRejectionUnaffected() throws Exception {
            revisePolicy(3);
            pausedWith(candidate(1, 0));
            memory.setHitlPauseType("TOOL_CALL");
            memory.setHitlPendingToolCalls(new PendingToolCallBatch());

            conversation().resume(decision(HitlVerdict.REJECTED, "wrong recipient", "alice"));

            verify(lifecycleManager, never()).reviseAnswerAt(any(), anyInt(), any());
            verify(lifecycleManager).executeLifecycleFromIndex(memory, 3);
        }
    }

    @Nested
    @DisplayName("through the store boundary")
    class Persistence {

        private void assertRoundTrip(IConversationMemory restored) throws Exception {
            assertEquals(ConversationState.AWAITING_HUMAN, restored.getConversationState());
            revisePolicyOn(restored);
            var memoryBefore = memory;
            memory = (ConversationMemory) restored;
            try {
                when(lifecycleManager.reviseAnswerAt(any(), anyInt(), any())).thenReturn(true);
                conversation().resume(decision(HitlVerdict.REJECTED, "shorter", "alice"));
                var captor = ArgumentCaptor.forClass(HitlRevisionRequest.class);
                verify(lifecycleManager).reviseAnswerAt(eq(restored), eq(1), captor.capture());
                assertEquals("the draft", captor.getValue().candidate().getDraft());
                assertEquals("[{\"type\":\"USER\"}]", captor.getValue().candidate().getTranscriptJson());
                assertEquals("revised", outcome());
            } finally {
                memory = memoryBefore;
            }
        }

        private void revisePolicyOn(IConversationMemory target) {
            target.setHitlRevisionPolicy(new HitlRevisionPolicy(HitlRejectionAction.REVISE, 3));
        }

        private ConversationMemorySnapshot pausedSnapshot() throws Exception {
            revisePolicy(3);
            memory.setConversationState(ConversationState.READY);
            doAnswer(inv -> {
                memory.setHitlRevisionCandidate(candidate(1, 0));
                throw new ConversationPauseException("wf1", 3, "review the answer");
            }).when(lifecycleManager).executeLifecycle(any(), any());
            conversation().say("write it", Map.of());
            return ConversationMemoryUtilities.convertConversationMemory(memory);
        }

        @Test
        @DisplayName("JSON (PostgreSQL): a resume after a reload still revises")
        void jsonRoundTrip() throws Exception {
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            var json = new JsonSerialization(mapper);
            var snapshot = json.deserialize(json.serialize(pausedSnapshot()), ConversationMemorySnapshot.class);

            assertRoundTrip(ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot));
        }

        @Test
        @DisplayName("BSON (MongoDB codec mapper): a resume after a reload still revises")
        void bsonRoundTrip() throws Exception {
            var mapper = new ObjectMapper(new BsonFactory());
            mapper.registerModule(new JavaTimeModule());
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            new SerializationCustomizer(false).customize(mapper);
            byte[] bson = mapper.writeValueAsBytes(pausedSnapshot());
            var snapshot = mapper.readValue(bson, ConversationMemorySnapshot.class);

            assertRoundTrip(ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot));
        }

        @Test
        @DisplayName("the kept request is withheld from an approver's full snapshot and from the detailed view")
        void withheldFromReaders() throws Exception {
            var snapshot = pausedSnapshot();
            assertNotNull(findStepKey(snapshot), "precondition: the stored snapshot carries it");

            ConversationMemoryUtilities.sanitizePendingToolCallsForApprover(snapshot);
            assertNull(findStepKey(snapshot));

            var detailed = ConversationMemoryUtilities.convertSimpleConversationMemory(ConversationMemoryUtilities.convertConversationMemory(memory),
                    true, false);
            assertFalse(detailed.getConversationSteps().stream().flatMap(step -> step.getConversationStep().stream())
                    .anyMatch(data -> HitlRevisionCandidate.STEP_KEY.equals(data.getKey())));
        }

        private Object findStepKey(ConversationMemorySnapshot snapshot) {
            for (var step : snapshot.getConversationSteps()) {
                for (var workflowRun : step.getWorkflows()) {
                    for (var result : workflowRun.getLifecycleTasks()) {
                        if (HitlRevisionCandidate.STEP_KEY.equals(result.getKey())) {
                            return result;
                        }
                    }
                }
            }
            return null;
        }
    }
}
