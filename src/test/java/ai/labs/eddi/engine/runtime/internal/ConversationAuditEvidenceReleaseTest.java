/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.audit.IAuditEntryCollector;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException.PauseOrigin;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Once a turn's audit entries are in the ledger, the stored step no longer
 * carries a second copy of the compiled prompt and the model response — on a
 * measured conversation those two were a fifth of the document. Everything
 * short of "the ledger has it" keeps them.
 */
@DisplayName("Conversation: ledger-owned audit evidence is released from the stored step")
class ConversationAuditEvidenceReleaseTest {

    private static final String PROMPT = "SYSTEM: you are helpful\n---\nuser said hello";
    private static final String RESPONSE = "Hello, how can I help?";

    private ConversationMemory memory;
    private IExecutableWorkflow workflow;
    private ILifecycleManager lifecycleManager;
    private final List<AuditEntry> ledger = new ArrayList<>();
    /**
     * What the pipeline saw on the step when it built the entry, i.e. within the
     * turn.
     */
    private final List<String> seenWhileBuildingEntry = new ArrayList<>();

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("conv1", "agent1", 1, "user1");
        memory.setConversationState(ConversationState.READY);
        workflow = mock(IExecutableWorkflow.class);
        lifecycleManager = mock(ILifecycleManager.class);
        lenient().when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        lenient().when(workflow.getWorkflowId()).thenReturn("wf1");
    }

    /** Acknowledges (or refuses) each entry, as the real ledger collector does. */
    private IAuditEntryCollector ledgerAccepting(boolean accepts) {
        return (IAuditEntryCollector.Acknowledging) entry -> {
            if (accepts) {
                ledger.add(entry);
            }
            return accepts;
        };
    }

    /**
     * What LlmTask and LifecycleManager do together: the task writes the evidence
     * to the step, the manager reads it to build the entry and hands that to the
     * collector.
     */
    private void llmTurn(boolean pauseAfterwards) throws Exception {
        doAnswer(invocation -> {
            var step = memory.getCurrentStep();
            step.storeData(new Data<>(MemoryKeys.AUDIT_COMPILED_PROMPT, PROMPT));
            step.storeData(new Data<>(MemoryKeys.AUDIT_MODEL_RESPONSE, RESPONSE));
            step.storeData(new Data<>(MemoryKeys.AUDIT_MODEL_NAME, "model-x"));
            step.storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.25));
            step.storeData(new Data<>(MemoryKeys.AUDIT_TOOL_CALLS, Map.of("calls", List.of(Map.of("tool", "t")))));
            step.storeData(new Data<>(MemoryKeys.AUDIT_TOKEN_USAGE, Map.of("totalTokens", 7)));

            Map<String, Object> llmDetail = new LinkedHashMap<>();
            llmDetail.put("compiledPrompt", step.getLatestData(MemoryKeys.AUDIT_COMPILED_PROMPT).getResult());
            llmDetail.put("modelResponse", step.getLatestData(MemoryKeys.AUDIT_MODEL_RESPONSE).getResult());
            seenWhileBuildingEntry.add(String.valueOf(llmDetail.get("compiledPrompt")));
            memory.getAuditCollector().collect(new AuditEntry("e1", "conv1", "agent1", 1, "user1", null, 0, "ai.labs.llm", "langchain", 0, 1L,
                    null, null, llmDetail, null, List.of(), 0.25, Instant.now(), null, null));
            if (pauseAfterwards) {
                throw new ConversationPauseException("wf1", 0, "gated", PauseOrigin.TOOL_CALL);
            }
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());
    }

    private Conversation conversation() {
        return new Conversation(List.of(workflow), memory, mock(IPropertiesHandler.class), mock(IConversation.IConversationOutputRenderer.class));
    }

    @Test
    @DisplayName("the ledger got the full text; the stored step keeps neither copy, but keeps the small evidence others read")
    void storedStepDropsWhatTheLedgerOwns() throws Exception {
        memory.setAuditCollector(ledgerAccepting(true));
        llmTurn(false);

        conversation().say("hello", Map.of());

        // Within the turn the pipeline saw the full prompt.
        assertEquals(List.of(PROMPT), seenWhileBuildingEntry);
        assertEquals(1, ledger.size());
        assertEquals(PROMPT, ledger.getFirst().llmDetail().get("compiledPrompt"));
        assertEquals(RESPONSE, ledger.getFirst().llmDetail().get("modelResponse"));

        var step = memory.getCurrentStep();
        assertNull(step.getLatestData(MemoryKeys.AUDIT_COMPILED_PROMPT));
        assertNull(step.getLatestData(MemoryKeys.AUDIT_MODEL_RESPONSE));
        // Read back from the stored step by agent tests, cost metering and /v1.
        assertNotNull(step.getLatestData(MemoryKeys.AUDIT_MODEL_NAME));
        assertNotNull(step.getLatestData(MemoryKeys.AUDIT_COST));
        assertNotNull(step.getLatestData(MemoryKeys.AUDIT_TOOL_CALLS));
        assertNotNull(step.getLatestData(MemoryKeys.AUDIT_TOKEN_USAGE));

        String persisted = new ObjectMapper().writeValueAsString(ConversationMemoryUtilities.convertConversationMemory(memory));
        assertFalse(persisted.contains("you are helpful"), "the persisted document must not repeat the prompt");
        assertFalse(persisted.contains("how can I help"), "the persisted document must not repeat the response");
    }

    @Test
    @DisplayName("an entry the ledger refused (full queue) leaves the evidence in the stored step")
    void refusedEntryKeepsTheEvidence() throws Exception {
        memory.setAuditCollector(ledgerAccepting(false));
        llmTurn(false);

        conversation().say("hello", Map.of());

        assertTrue(ledger.isEmpty());
        assertNotNull(memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_COMPILED_PROMPT));
        assertNotNull(memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_MODEL_RESPONSE));
    }

    @Test
    @DisplayName("a ledger submit that throws leaves the evidence in the stored step")
    void failingSubmitKeepsTheEvidence() throws Exception {
        memory.setAuditCollector((IAuditEntryCollector.Acknowledging) entry -> {
            throw new IllegalStateException("ledger down");
        });
        llmTurn(false);

        conversation().say("hello", Map.of());

        assertNotNull(memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_COMPILED_PROMPT));
        assertNotNull(memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_MODEL_RESPONSE));
    }

    @Test
    @DisplayName("a paused turn keeps the evidence: it resumes from this step")
    void pausedTurnKeepsTheEvidence() throws Exception {
        memory.setAuditCollector(ledgerAccepting(true));
        llmTurn(true);

        conversation().say("hello", Map.of());

        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        assertEquals(1, ledger.size(), "the ledger still receives the entry at the pause");
        assertNotNull(memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_COMPILED_PROMPT));
        assertNotNull(memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_MODEL_RESPONSE));
    }

    @Test
    @DisplayName("the resume that completes a paused turn releases the evidence")
    void completedResumeReleasesTheEvidence() throws Exception {
        memory.setAuditCollector(ledgerAccepting(true));
        llmTurn(true);
        conversation().say("hello", Map.of());
        assertNotNull(memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_COMPILED_PROMPT));

        doAnswer(invocation -> {
            memory.getAuditCollector().collect(new AuditEntry("e2", "conv1", "agent1", 1, "user1", null, 0, "ai.labs.llm", "langchain", 1, 1L,
                    null, null, Map.of("compiledPrompt", PROMPT), null, List.of(), 0.0, Instant.now(), null, null));
            return null;
        }).when(lifecycleManager).executeLifecycleFromIndex(any(), anyInt());
        memory.setHitlPausedWorkflowId("wf1");
        memory.setHitlPausedAbsoluteTaskIndex(0);
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);

        conversation().resume(decision);

        assertEquals(2, ledger.size());
        assertNull(memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_COMPILED_PROMPT));
        assertNull(memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_MODEL_RESPONSE));
    }

    @Test
    @DisplayName("with auditing off nothing is touched")
    void auditingOffChangesNothing() throws Exception {
        // No collector: LlmTask would not have written the keys; a step that holds
        // them anyway (written by something else) must come through untouched.
        doAnswer(invocation -> {
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COMPILED_PROMPT, PROMPT));
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_MODEL_RESPONSE, RESPONSE));
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());

        conversation().say("hello", Map.of());

        assertTrue(ledger.isEmpty());
        assertEquals(PROMPT, memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_COMPILED_PROMPT).getResult());
        assertEquals(RESPONSE, memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_MODEL_RESPONSE).getResult());
    }
}
