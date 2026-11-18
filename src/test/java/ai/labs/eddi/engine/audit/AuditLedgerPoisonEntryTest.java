/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.datastore.postgres.PostgresText;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * One entry the store refuses on its own content must cost nothing but that
 * entry; a store that is down must still take the back-off path.
 */
@DisplayName("AuditLedgerService - a poisoned entry does not take its batch down")
class AuditLedgerPoisonEntryTest {

    /** What PostgresAuditStore answers to {@link IAuditStore#storableText()}. */
    private static final UnaryOperator<String> POSTGRES_TEXT = PostgresText::text;

    @TempDir
    Path tempDir;

    private IAuditStore store;
    private Path deadLetter;
    private final List<AuditEntry> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = mock(IAuditStore.class);
        deadLetter = tempDir.resolve("deadletter.jsonl");
        doReturn(true).when(store).supportsSequence();
    }

    private AuditLedgerService ledger() {
        var service = AuditLedgerService.createForTesting(store, true, 60, null, new SimpleMeterRegistry(), 1000, deadLetter.toString());
        service.init();
        return service;
    }

    private static AuditEntry entry(String id, String conversationId, Map<String, Object> input) {
        return new AuditEntry(id, conversationId, "agent", 1, "user", "production", 0, "task", "LlmTask", 0, 5L, input, Map.of("text", "out"), null,
                null, List.of("a"), 0.0, Instant.now(), null, null);
    }

    private static RuntimeException refusal(String sqlState) {
        return new RuntimeException("Failed to insert audit entry", new SQLException("refused", sqlState));
    }

    /**
     * The batch write fails as a whole; single inserts fail only for ids starting
     * "poison".
     */
    private void storeRejectsPoisonEntries() {
        doThrow(refusal("22P05")).when(store).appendBatch(anyList());
        doAnswer(invocation -> {
            AuditEntry e = invocation.getArgument(0);
            if (e.id().startsWith("poison")) {
                throw refusal("22P05");
            }
            stored.add(e);
            return null;
        }).when(store).appendEntry(any());
    }

    @Test
    @DisplayName("four consecutive poison entries do not count as an outage: every other entry is stored")
    void poisonEntriesAreIsolated() throws Exception {
        storeRejectsPoisonEntries();
        var service = ledger();
        service.submit(entry("ok-1", "conv-a", Map.of("text", "x")));
        service.submit(entry("poison-1", "conv-b", Map.of("text", "x")));
        service.submit(entry("poison-2", "conv-b", Map.of("text", "x")));
        service.submit(entry("poison-3", "conv-b", Map.of("text", "x")));
        service.submit(entry("poison-4", "conv-b", Map.of("text", "x")));
        service.submit(entry("ok-2", "conv-c", Map.of("text", "x")));
        service.submit(entry("ok-3", "conv-a", Map.of("text", "x")));

        service.flush();

        assertEquals(List.of("ok-1", "ok-2", "ok-3"), stored.stream().map(AuditEntry::id).toList());
        assertEquals(0, service.getQueueSize(), "a refused entry is not re-queued for another three rounds");
        List<String> lines = Files.readAllLines(deadLetter);
        assertEquals(4, lines.size());
        assertTrue(lines.stream().allMatch(l -> l.contains("\"reason\":\"store rejected the entry: SQLException SQLState 22P05\"")),
                lines.toString());
        assertTrue(lines.get(0).contains("\"id\":\"poison-1\""));
        assertEquals(4, service.undeliveredSequences("conv-b").size(), "the verifier can tell these gaps were the ledger's own");
        assertTrue(service.undeliveredSequences("conv-a").isEmpty());
    }

    @Test
    @DisplayName("a connection failure still backs off: nothing is dead-lettered, everything is re-queued")
    void anOutageStillTakesTheBackOffPath() {
        doThrow(refusal("08006")).when(store).appendBatch(anyList());
        doThrow(refusal("08006")).when(store).appendEntry(any());
        var service = ledger();
        for (int i = 0; i < 6; i++) {
            service.submit(entry("e-" + i, "conv-" + i, Map.of("text", "x")));
        }

        service.flush();

        assertEquals(6, service.getQueueSize(), "the store was not answering, so nothing is given up on yet");
        assertFalse(Files.exists(deadLetter));
    }

    @Test
    @DisplayName("an unpaired surrogate in one dead letter cannot lose that line or the ones after it")
    void deadLetterWriterSurvivesMalformedText() throws Exception {
        storeRejectsPoisonEntries();
        var service = ledger();
        service.submit(entry("poison-1", "conv-a", Map.of("text", "bad \uD83D here")));
        service.submit(entry("poison-2", "conv-a", Map.of("text", "fine")));

        service.flush();

        List<String> lines = Files.readAllLines(deadLetter, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("bad � here"), lines.get(0));
        assertTrue(lines.get(1).contains("\"id\":\"poison-2\""));
    }

    @Test
    @DisplayName("lenientUtf8 replaces malformed input with U+FFFD and leaves valid text byte-identical")
    void lenientUtf8() throws CharacterCodingException {
        assertArrayEquals("héllo 😀".getBytes(StandardCharsets.UTF_8), AuditLedgerService.lenientUtf8("héllo 😀"));
        assertArrayEquals("a�b".getBytes(StandardCharsets.UTF_8), AuditLedgerService.lenientUtf8("a\uDE00b"));
        // the strict decoder accepts what was produced
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(AuditLedgerService.lenientUtf8("x\uD83D")));
    }

    @Test
    @DisplayName("for a store that replaces NUL, the entry is made storable BEFORE it is signed")
    void nulIsReplacedBeforeSigning() {
        doReturn(POSTGRES_TEXT).when(store).storableText();
        var service = ledger();
        service.submit(new AuditEntry("n-1", "conv-a", "agent", 1, "us\u0000er", "production", 0, "task", "LlmTask", 0, 5L,
                Map.of("text", "a\u0000b", "nested", Map.of("k\u0000", List.of("x\u0000y"))), Map.of(), null, null, List.of("act\u0000"), 0.0,
                Instant.now(), null, null));

        service.flush();

        var captured = ArgumentCaptor.forClass(List.class);
        verify(store, times(1)).appendBatch(captured.capture());
        AuditEntry e = (AuditEntry) captured.getValue().getFirst();
        assertEquals("us�er", e.userId());
        assertEquals("a�b", e.input().get("text"));
        assertEquals(Map.of("k�", List.of("x�y")), e.input().get("nested"));
        assertEquals(List.of("act�"), e.actions());
    }

    /**
     * A payload value that is neither a String, a Map nor a List before the ledger
     * normalises it.
     */
    public static class Payload {
        private final String text;

        public Payload(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    @Test
    @DisplayName("a POJO payload and a conversation id holding NUL sign what the PostgreSQL layer will store, so the row verifies")
    void aSignedEntryStillVerifiesAfterThePostgresReplacement() throws Exception {
        doReturn(POSTGRES_TEXT).when(store).storableText();
        var signing = AuditLedgerService.createForTesting(store, true, 60, "test-master-key-for-the-audit-hmac-0123456789", new SimpleMeterRegistry(),
                1000, deadLetter.toString());
        signing.init();
        signing.submit(new AuditEntry("sign-1", "conv\u0000a", "agent\u0000", 1, "us\u0000er", "production", 0, "task", "LlmTask", 0, 5L,
                Map.of("pojo", new Payload("a\u0000b"), "arr", new String[]{"x\u0000"}, "set", Set.of("s\u0000"), "node",
                        new ObjectMapper().readTree("{\"n\":\"v\\u0000\"}")),
                Map.of("text", "out"), null, null, List.of("act\u0000"), 0.0, Instant.now(), null, null));

        signing.flush();

        var captured = ArgumentCaptor.forClass(List.class);
        verify(store).appendBatch(captured.capture());
        AuditEntry signed = (AuditEntry) captured.getValue().getFirst();
        assertNotNull(signed.hmac(), "the test needs a signing key");
        // what the PostgreSQL layer does to the row on the way in: identifiers are text
        // parameters, the
        // payload maps are serialised and bound as jsonb (both pass through
        // PostgresText)
        ObjectMapper mapper = new ObjectMapper();
        AuditEntry stored = new AuditEntry(signed.id(), PostgresText.text(signed.conversationId()), PostgresText.text(signed.agentId()),
                signed.agentVersion(), PostgresText.text(signed.userId()), PostgresText.text(signed.environment()), signed.stepIndex(),
                PostgresText.text(signed.taskId()), PostgresText.text(signed.taskType()), signed.taskIndex(), signed.durationMs(),
                roundTrip(mapper, signed.input()), roundTrip(mapper, signed.output()), roundTrip(mapper, signed.llmDetail()),
                roundTrip(mapper, signed.toolCalls()), signed.actions().stream().map(PostgresText::text).toList(), signed.cost(), signed.timestamp(),
                signed.hmac(), signed.agentSignature(), signed.sequence());

        assertFalse(signed.input().toString().contains("\u0000"), "nothing is left for the proxy to replace");
        assertEquals(AuditVerificationStatus.VALID, signing.verifyEntry(stored));
    }

    private static Map<String, Object> roundTrip(ObjectMapper mapper, Map<String, Object> map) throws Exception {
        if (map == null) {
            return null;
        }
        String json = PostgresText.json(mapper.writeValueAsString(map));
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    @Test
    @DisplayName("for a store that keeps NUL (MongoDB), the entry is left as it is")
    void nulIsKeptWhenTheStoreKeepsIt() {
        var service = ledger();
        service.submit(new AuditEntry("n-2", "conv-a", "agent", 1, "user", "production", 0, "task", "LlmTask", 0, 5L, Map.of("text", "a\u0000b"),
                Map.of(), null, null, List.of(), 0.0, Instant.now(), null, null));

        service.flush();

        var captured = ArgumentCaptor.forClass(List.class);
        verify(store).appendBatch(captured.capture());
        assertEquals("a\u0000b", ((AuditEntry) captured.getValue().getFirst()).input().get("text"));
    }

    @Test
    @DisplayName("a conversation id holding NUL chains its entries under the id the store holds")
    void sequencesAreKeyedByTheStoredConversationId() {
        doReturn(POSTGRES_TEXT).when(store).storableText();
        var service = ledger();
        service.submit(entry("c-1", "conv\u0000a", Map.of("text", "x")));
        service.submit(entry("c-2", "conv�a", Map.of("text", "x")));

        service.flush();

        var captured = ArgumentCaptor.forClass(List.class);
        verify(store).appendBatch(captured.capture());
        AuditEntry first = (AuditEntry) captured.getValue().get(0);
        AuditEntry second = (AuditEntry) captured.getValue().get(1);
        assertEquals("conv�a", first.conversationId());
        assertEquals("conv�a", second.conversationId());
        assertEquals(first.sequence() + 1, second.sequence(), "one chain: the two ids are one row key in the store");
    }

    @Test
    @DisplayName("a duplicate key on the single retry means the entry is stored: not dead-lettered, not re-queued")
    void aDuplicateKeyCountsAsStored() {
        doThrow(refusal("08006")).when(store).appendBatch(anyList());
        doAnswer(invocation -> {
            AuditEntry e = invocation.getArgument(0);
            if (e.id().startsWith("dup")) {
                throw refusal("23505");
            }
            stored.add(e);
            return null;
        }).when(store).appendEntry(any());
        var service = ledger();
        service.submit(entry("dup-1", "conv-a", Map.of("text", "x")));
        service.submit(entry("ok-1", "conv-a", Map.of("text", "x")));

        service.flush();

        assertEquals(List.of("ok-1"), stored.stream().map(AuditEntry::id).toList());
        assertEquals(0, service.getQueueSize());
        assertFalse(Files.exists(deadLetter));
        assertTrue(service.undeliveredSequences("conv-a").isEmpty());
    }

    @Test
    @DisplayName("an entry with nothing to replace comes back as the same instance (no copy per entry)")
    void cleanEntriesAreNotCopied() {
        AuditEntry clean = entry("x-1", "conv-a", Map.of("text", "plain 😀", "nested", Map.of("k", List.of("v", 1))));
        assertSame(clean, AuditLedgerService.withStorableIdentifiers(clean, POSTGRES_TEXT));
        assertSame(clean, AuditLedgerService.withStorablePayload(clean, POSTGRES_TEXT));
    }

    @Test
    @DisplayName("replacement keeps the key order of a map and the elements before the first change")
    void replacementKeepsOrder() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("a", "1");
        input.put("b\u0000", List.of("x", "y\u0000", "z"));
        input.put("c", "3");
        AuditEntry replaced = AuditLedgerService.withStorablePayload(entry("x-2", "conv-a", input), POSTGRES_TEXT);
        assertEquals(List.of("a", "b�", "c"), List.copyOf(replaced.input().keySet()));
        assertEquals(List.of("x", "y�", "z"), replaced.input().get("b�"));
    }
}
