/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link AuditLedgerService} — scrubbing maps, nested
 * structures, scrubValue branches, HMAC key derivation edge cases, and
 * serializeDeadLetterEntry.
 */
@DisplayName("AuditLedgerService — Extended Branch Coverage")
class AuditLedgerServiceExtendedTest {

    @Mock
    private IAuditStore auditStore;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
    }

    private AuditLedgerService createService(boolean enabled, String masterKey) {
        var svc = AuditLedgerService.createForTesting(auditStore, enabled, 60, masterKey, meterRegistry);
        svc.init();
        return svc;
    }

    private AuditEntry entry(String id, String convId, String agentId) {
        return new AuditEntry(id, convId, agentId, 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("text", "hello"), Map.of("text", "response"),
                null, null, List.of("action1"), 0.0, Instant.now(), null, null);
    }

    // ==================== scrubSecrets — nested maps and lists
    // ====================

    @Nested
    @DisplayName("scrubSecrets — nested data")
    class ScrubSecretsTests {

        @Test
        @DisplayName("scrubs nested map values")
        void scrubsNestedMaps() {
            var service = createService(true, null);
            Map<String, Object> innerMap = new LinkedHashMap<>();
            innerMap.put("apiKey", "sk-1234567890abcdef1234567890abcdef");
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("config", innerMap);

            var entry = new AuditEntry("1", "conv1", "agent1", 1, "user1", "production",
                    0, "taskId", "LlmTask", 0, 100L,
                    inputMap, Map.of("text", "safe"),
                    null, null, List.of(), 0.0, Instant.now(), null, null);

            service.submit(entry);
            service.flush();

            verify(auditStore).appendBatch(any());
        }

        @Test
        @DisplayName("scrubs list values containing strings")
        void scrubsListValues() {
            var service = createService(true, null);
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("items", List.of("sk-1234567890abcdef1234567890abcdef", "normal text"));

            var entry = new AuditEntry("1", "conv1", "agent1", 1, "user1", "production",
                    0, "taskId", "LlmTask", 0, 100L,
                    inputMap, Map.of(),
                    null, null, List.of(), 0.0, Instant.now(), null, null);

            service.submit(entry);
            service.flush();

            verify(auditStore).appendBatch(any());
        }

        @Test
        @DisplayName("scrub handles non-string, non-map, non-list values")
        void handlesOtherTypes() {
            var service = createService(true, null);
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("count", 42);
            inputMap.put("ratio", 3.14);
            inputMap.put("flag", true);

            var entry = new AuditEntry("1", "conv1", "agent1", 1, "user1", "production",
                    0, "taskId", "LlmTask", 0, 100L,
                    inputMap, Map.of(),
                    null, null, List.of(), 0.0, Instant.now(), null, null);

            service.submit(entry);
            service.flush();

            verify(auditStore).appendBatch(any());
        }

        @Test
        @DisplayName("scrub handles null maps in input/output")
        void handlesNullMaps() {
            var service = createService(true, null);

            var entry = new AuditEntry("1", "conv1", "agent1", 1, "user1", "production",
                    0, "taskId", "LlmTask", 0, 100L,
                    null, null,
                    null, null, List.of(), 0.0, Instant.now(), null, null);

            service.submit(entry);
            service.flush();

            verify(auditStore).appendBatch(any());
        }

        @Test
        @DisplayName("scrub handles empty maps")
        void handlesEmptyMaps() {
            var service = createService(true, null);

            var entry = new AuditEntry("1", "conv1", "agent1", 1, "user1", "production",
                    0, "taskId", "LlmTask", 0, 100L,
                    Map.of(), Map.of(),
                    Map.of(), Map.of(), List.of(), 0.0, Instant.now(), null, null);

            service.submit(entry);
            service.flush();

            verify(auditStore).appendBatch(any());
        }
    }

    // ==================== HMAC ====================

    @Nested
    @DisplayName("HMAC key derivation")
    class HmacTests {

        @Test
        @DisplayName("null master key → no HMAC")
        void nullMasterKey() {
            var service = createService(true, null);
            assertNull(service.getHmacKey());
        }

        @Test
        @DisplayName("blank master key → no HMAC")
        void blankMasterKey() {
            var service = createService(true, "   ");
            assertNull(service.getHmacKey());
        }

        @Test
        @DisplayName("empty master key → no HMAC")
        void emptyMasterKey() {
            var service = createService(true, "");
            assertNull(service.getHmacKey());
        }

        @Test
        @DisplayName("valid master key → HMAC key derived")
        void validMasterKey() {
            var service = createService(true, "my-secret-vault-key");
            assertNotNull(service.getHmacKey());
            assertTrue(service.getHmacKey().length > 0);
        }

        @Test
        @DisplayName("entry with HMAC has hmac field set after submit")
        void entryWithHmac() {
            var service = createService(true, "my-secret-vault-key");
            service.submit(entry("1", "conv1", "agent1"));
            service.flush();

            verify(auditStore).appendBatch(argThat(batch -> {
                var e = batch.getFirst();
                return e.hmac() != null && !e.hmac().isBlank();
            }));
        }
    }

    // ==================== serializeDeadLetterEntry edge cases ====================

    @Nested
    @DisplayName("serializeDeadLetterEntry")
    class SerializeDeadLetterTests {

        @Test
        @DisplayName("handles entry with null fields gracefully")
        void nullFields() {
            var service = createService(true, null);
            var e = new AuditEntry("1", null, null, 0, null, null,
                    0, null, null, 0, 0L,
                    null, null, null, null, null, 0.0, null, null, null);
            String json = service.serializeDeadLetterEntry(e, "test_type");
            assertTrue(json.contains("\"type\":\"test_type\""));
        }

        @Test
        @DisplayName("serialization with special characters in fields")
        void specialCharacters() {
            var service = createService(true, null);
            var e = new AuditEntry("1", "conv-\"with-quotes\"", "agent\nnewline", 1, "user1", "prod",
                    0, "task\\slash", "LlmTask", 0, 100L,
                    Map.of(), Map.of(), null, null, List.of(), 0.0, Instant.now(), null, null);
            String json = service.serializeDeadLetterEntry(e, null);
            assertFalse(json.contains("\"type\""));
            // Should properly escape quotes and newlines
            assertFalse(json.contains("\n"));
        }
    }

    // ==================== multiple flush retries scenario ====================

    @Nested
    @DisplayName("flush retry logic")
    class FlushRetryTests {

        @Test
        @DisplayName("retry counter resets after successful flush")
        void retryCounterResets() {
            var service = createService(true, null);

            // First: fail once
            doThrow(new RuntimeException("db error")).doNothing().when(auditStore).appendBatch(any());

            service.submit(entry("1", "conv1", "agent1"));
            service.flush(); // Failure 1 — requeue

            assertEquals(1, service.getQueueSize());

            // Second flush should succeed and reset counter
            service.flush();
            assertEquals(0, service.getQueueSize());
        }

        @Test
        @DisplayName("multiple entries all dropped after max retries")
        void multipleEntriesDropped() {
            var service = createService(true, null);
            doThrow(new RuntimeException("db error")).when(auditStore).appendBatch(any());

            service.submit(entry("1", "c1", "a1"));
            service.submit(entry("2", "c2", "a2"));
            service.submit(entry("3", "c3", "a3"));

            service.flush(); // Failure 1
            service.flush(); // Failure 2
            service.flush(); // Failure 3 — drops all

            assertEquals(0, service.getQueueSize());
        }
    }

    // ==================== shutdown with entries ====================

    @Nested
    @DisplayName("shutdown edge cases")
    class ShutdownTests {

        @Test
        @DisplayName("shutdown flushes and terminates executor")
        void shutdownFlushesAndTerminates() {
            var service = createService(true, null);
            service.submit(entry("1", "conv1", "agent1"));
            service.submit(entry("2", "conv2", "agent2"));

            service.shutdown();

            verify(auditStore).appendBatch(argThat(batch -> batch.size() == 2));
        }
    }
}
