/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TurnAuditBufferTest {

    private static final String SECRET = "sk-live_abc.123";

    private ConversationMemory memory;
    private List<AuditEntry> ledger;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        ledger = new ArrayList<>();
        memory.setAuditCollector(ledger::add);
    }

    private static AuditEntry entry(String taskId, String userInput, Map<String, Object> llmDetail) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("userInput", userInput);
        return new AuditEntry("id-" + taskId, "conv-1", "agent-1", 1, "user-1", null, 1, taskId, "type", 0, 5L, input,
                Map.of("output", List.of("ok")), llmDetail, null, List.of(), 0.0, Instant.now(), null, null);
    }

    @Test
    @DisplayName("nothing reaches the ledger before flush, then everything does, in order")
    void entriesAreHeldUntilFlush() {
        TurnAuditBuffer buffer = TurnAuditBuffer.install(memory);
        memory.getAuditCollector().collect(entry("parser", "hello", null));
        memory.getAuditCollector().collect(entry("behavior", "hello", null));

        assertTrue(ledger.isEmpty(), "entries must not be submitted while the turn can still reveal a secret");

        buffer.flush(memory);

        assertEquals(List.of("parser", "behavior"), ledger.stream().map(AuditEntry::taskId).toList());
        assertEquals("hello", ledger.getFirst().input().get("userInput"));
        assertFalse(memory.getAuditCollector() instanceof TurnAuditBuffer, "the original collector is restored");
    }

    @Test
    @DisplayName("when a later task vaulted the input, every entry of the turn records the placeholder instead")
    void secretInputIsRedactedFromEarlierEntries() {
        TurnAuditBuffer buffer = TurnAuditBuffer.install(memory);
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.INPUT_INITIAL.key(), SECRET));
        // Parser and behavior rules run — and are audited — before the property setter.
        memory.getAuditCollector().collect(entry("parser", SECRET, null));
        memory.getAuditCollector().collect(entry("llm", SECRET, Map.of("compiledPrompt", "User said: " + SECRET,
                "nested", List.of(Map.of("text", SECRET)))));
        // PropertySetterTask scrubs the step.
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.INPUT_INITIAL.key(), MemoryKeys.SECRET_INPUT_PLACEHOLDER));

        buffer.flush(memory);

        assertEquals(2, ledger.size());
        for (AuditEntry submitted : ledger) {
            assertEquals(MemoryKeys.SECRET_INPUT_PLACEHOLDER, submitted.input().get("userInput"));
            assertFalse(String.valueOf(submitted).contains(SECRET), "no copy of the secret may reach the ledger: " + submitted);
        }
        assertEquals("User said: " + MemoryKeys.SECRET_INPUT_PLACEHOLDER, ledger.get(1).llmDetail().get("compiledPrompt"));
    }

    @Test
    @DisplayName("an ordinary turn is submitted unchanged")
    void ordinaryInputIsNotRedacted() {
        TurnAuditBuffer buffer = TurnAuditBuffer.install(memory);
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.INPUT_INITIAL.key(), "what is the weather"));
        AuditEntry original = entry("parser", "what is the weather", Map.of("compiledPrompt", "what is the weather"));
        memory.getAuditCollector().collect(original);

        buffer.flush(memory);

        assertSame(original, ledger.getFirst());
    }

    @Test
    @DisplayName("a short secret replaces only the recorded input, not every matching substring")
    void shortInputOnlyReplacesTheInputField() {
        AuditEntry redacted = TurnAuditBuffer.redact(entry("llm", "ok", Map.of("modelResponse", "ok, done")), Set.of("ok"));

        assertEquals(MemoryKeys.SECRET_INPUT_PLACEHOLDER, redacted.input().get("userInput"));
        assertEquals("ok, done", redacted.llmDetail().get("modelResponse"));
    }

    @Test
    @DisplayName("entries that record no input — a task failure quoting the token — and entries built after the scrub are redacted too")
    void entriesWithoutOrAfterTheInputAreRedacted() {
        TurnAuditBuffer buffer = TurnAuditBuffer.install(memory);
        memory.getAuditCollector().collect(entry("parser", SECRET, null));
        // A failure entry carries no userInput at all.
        memory.getAuditCollector().collect(new AuditEntry("id-fail", "conv-1", "agent-1", 1, "user-1", null, 1, "normalizer", "type", 1, 5L, null,
                Map.of("status", "TASK_FAILED", "errorMessage", "cannot normalize '" + SECRET + "'"), null, null, List.of(), 0.0, Instant.now(),
                null, null));
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.INPUT_INITIAL.key(), MemoryKeys.SECRET_INPUT_PLACEHOLDER));
        // An entry built after the property setter records the placeholder, but its
        // tool call still quotes the secret.
        memory.getAuditCollector().collect(new AuditEntry("id-llm", "conv-1", "agent-1", 1, "user-1", null, 1, "llm", "type", 3, 5L,
                Map.of("userInput", MemoryKeys.SECRET_INPUT_PLACEHOLDER), null, null, Map.of("args", List.of("key=" + SECRET)), List.of(), 0.0,
                Instant.now(), null, null));

        buffer.flush(memory);

        assertEquals(3, ledger.size());
        for (AuditEntry submitted : ledger) {
            assertFalse(String.valueOf(submitted).contains(SECRET), "no copy of the secret may reach the ledger: " + submitted);
        }
        assertNull(ledger.get(1).input(), "an entry with no input stays without one");
        assertEquals("cannot normalize '" + MemoryKeys.SECRET_INPUT_PLACEHOLDER + "'", ledger.get(1).output().get("errorMessage"));
    }

    @Test
    @DisplayName("a failing submit does not drop the entries after it")
    void failingSubmitDoesNotStopTheRest() {
        List<String> accepted = new ArrayList<>();
        memory.setAuditCollector(e -> {
            if ("parser".equals(e.taskId())) {
                throw new IllegalStateException("queue full");
            }
            accepted.add(e.taskId());
        });
        TurnAuditBuffer buffer = TurnAuditBuffer.install(memory);
        memory.getAuditCollector().collect(entry("parser", "hi there", null));
        memory.getAuditCollector().collect(entry("output", "hi there", null));

        assertDoesNotThrow(() -> buffer.flush(memory));

        assertEquals(List.of("output"), accepted);
    }

    @Test
    @DisplayName("no collector, or one already buffered, installs nothing")
    void installIsANoOpWithoutAuditingOrWhenNested() {
        var unaudited = new ConversationMemory("aabbccddeeff112233445577", "agent-1", 1, "user-1");
        assertNull(TurnAuditBuffer.install(unaudited));

        assertNotNull(TurnAuditBuffer.install(memory));
        assertNull(TurnAuditBuffer.install(memory), "a nested install must not flush the outer turn early");
    }
}
