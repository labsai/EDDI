/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditHmac;
import ai.labs.eddi.engine.audit.IAuditEntryCollector;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.llm.guardrails.GuardrailAction;
import ai.labs.eddi.modules.llm.guardrails.GuardrailOutcome;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import com.mongodb.MongoClientSettings;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.Document;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ledger side of WS4: {@code buildAuditEntry} collects every
 * {@code audit:guardrail:<phase>:<type>} record of the LLM task into
 * {@code AuditEntry.output.guardrails} — keyed exactly as LlmTask writes them —
 * and the nested records survive the HMAC across a Mongo round trip.
 */
@DisplayName("LifecycleManager — guardrail audit evidence")
class LifecycleManagerGuardrailAuditTest {

    private IComponentCache componentCache;
    private LifecycleManager lifecycleManager;
    private IConversationMemory memory;
    private IConversationMemory.IWritableConversationStep currentStep;
    private IAuditEntryCollector auditCollector;

    @BeforeEach
    void setUp() {
        componentCache = mock(IComponentCache.class);
        var workflowId = mock(IResourceStore.IResourceId.class);
        when(workflowId.getId()).thenReturn("0123456789abcdef01234567");
        when(workflowId.getVersion()).thenReturn(1);
        lifecycleManager = new LifecycleManager(componentCache, workflowId);
        when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

        memory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(memory.getConversationId()).thenReturn("0123456789abcdef01234567");
        when(memory.getAgentId()).thenReturn("0123456789abcdef76543210");
        when(memory.getAgentVersion()).thenReturn(1);
        when(memory.size()).thenReturn(1);
        auditCollector = mock(IAuditEntryCollector.class);
        when(memory.getAuditCollector()).thenReturn(auditCollector);
    }

    private static ILifecycleTask task(String type) {
        var task = mock(ILifecycleTask.class);
        when(task.getId()).thenReturn(new TaskId("ai.labs.llm"));
        when(task.getType()).thenReturn(type);
        return task;
    }

    /** A record keyed exactly as LlmTask.recordGuardrailOutcome writes it. */
    private static IData<?> guardrailData(GuardrailOutcome outcome) {
        String key = MemoryKeys.AUDIT_GUARDRAIL_PREFIX + outcome.phase().label() + ":" + outcome.type();
        return new Data<>(key, List.of(outcome.toAuditRecord("support")));
    }

    private AuditEntry runAndCapture(ILifecycleTask task) throws Exception {
        lifecycleManager.addLifecycleTask(task);
        lifecycleManager.executeLifecycle(memory, null);
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditCollector).collect(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("an LLM task's guardrail records land in output.guardrails, in write order")
    void buildAuditEntry_collectsGuardrailKeys() throws Exception {
        var input = new GuardrailOutcome("pii-redaction", GuardrailPhase.INPUT, GuardrailAction.REDACT, "pii:email", 0);
        var output = new GuardrailOutcome("json-object", GuardrailPhase.OUTPUT, GuardrailAction.REPROMPT, "not_a_json_object", 1);
        when(currentStep.getAllElements()).thenReturn(new LinkedList<>(List.of(new Data<>("input", "hi"), guardrailData(input),
                guardrailData(output))));

        var entry = runAndCapture(task(MemoryKeys.TASK_TYPE_LANGCHAIN));

        var guardrails = (List<?>) entry.output().get("guardrails");
        assertEquals(2, guardrails.size());
        var first = (Map<?, ?>) guardrails.get(0);
        assertEquals("input", first.get("phase"));
        assertEquals("pii-redaction", first.get("type"));
        assertEquals("redact", first.get("action"));
        assertEquals("support", first.get("taskId"));
        assertEquals(1, ((Map<?, ?>) guardrails.get(1)).get("retryCount"));
    }

    @Test
    @DisplayName("no guardrail fired: the entry has no guardrails member")
    void noGuardrailKeys_noMember() throws Exception {
        when(currentStep.getAllElements()).thenReturn(new LinkedList<>(List.of(new Data<>("input", "hi"))));

        var entry = runAndCapture(task(MemoryKeys.TASK_TYPE_LANGCHAIN));

        assertTrue(entry.output() == null || !entry.output().containsKey("guardrails"));
    }

    @Test
    @DisplayName("a task running after the LLM task in the same step does not inherit its guardrail records")
    void nonLlmTask_doesNotCollect() throws Exception {
        var outcome = new GuardrailOutcome("prompt-injection", GuardrailPhase.INPUT, GuardrailAction.BLOCK, "prompt_injection_pattern", 0);
        when(currentStep.getAllElements()).thenReturn(new LinkedList<>(List.of(guardrailData(outcome))));

        var entry = runAndCapture(task("output"));

        assertNull(entry.output());
    }

    @Test
    @DisplayName("the HMAC over output.guardrails still verifies after a BSON round trip")
    void guardrailRecords_surviveHmacRoundTrip() {
        byte[] hmacKey = AuditHmac.deriveHmacKey("test-master-key-12345");
        var record = new GuardrailOutcome("pii-redaction", GuardrailPhase.OUTPUT, GuardrailAction.REDACT, "pii:email,phone", 0)
                .toAuditRecord("support");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("output", List.of("hello"));
        output.put("guardrails", List.of(record));
        var written = new AuditEntry("0123456789abcdef01234567", "conv-1", "agent-1", 1, "user-1", "production", 0, "ai.labs.llm", "langchain",
                0, 42L, Map.of("userInput", "hello"), output, null, null, List.of("send_message"), 0.0,
                Instant.parse("2026-01-01T00:00:00Z"), null, null);
        var signed = written.withHmac(AuditHmac.computeHmac(written, hmacKey));

        var readBack = new AuditEntry(signed.id(), signed.conversationId(), signed.agentId(), signed.agentVersion(), signed.userId(),
                signed.environment(), signed.stepIndex(), signed.taskId(), signed.taskType(), signed.taskIndex(), signed.durationMs(),
                signed.input(), bsonRoundTrip(signed.output()), signed.llmDetail(), signed.toolCalls(), signed.actions(), signed.cost(),
                signed.timestamp(), signed.hmac(), signed.agentSignature());

        assertInstanceOf(Document.class, ((List<?>) readBack.output().get("guardrails")).get(0));
        assertTrue(AuditHmac.verifyHmac(readBack, hmacKey));
        assertFalse(record.containsValue(null));
    }

    private static Map<String, Object> bsonRoundTrip(Map<String, Object> map) {
        BsonDocument bson = new Document(map).toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        Document decoded = new DocumentCodec().decode(new BsonDocumentReader(bson), DecoderContext.builder().build());
        return new LinkedHashMap<>(decoded);
    }
}
