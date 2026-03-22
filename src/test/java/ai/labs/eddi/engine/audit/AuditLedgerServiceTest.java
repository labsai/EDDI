package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuditLedgerService} and {@link AuditHmac}.
 */
class AuditLedgerServiceTest {

    private IAuditStore auditStore;

    private AuditEntry createEntry(String taskId, String conversationId) {
        return new AuditEntry(
                "entry-" + taskId,
                conversationId,
                "agent-1",
                1,
                "user-1",
                "production",
                0, taskId, "test-type", 0,
                42L,
                Map.of("userInput", "hello"),
                Map.of("output", List.of("world")),
                null, null,
                List.of("greet"),
                0.0,
                Instant.now(),
                null);
    }

    @BeforeEach
    void setUp() {
        auditStore = mock(IAuditStore.class);
    }

    // ==================== Queue & Flush ====================

    @Nested
    class QueueAndFlush {

        @Test
        void shouldEnqueueEntries() {
            var service = AuditLedgerService.createForTesting(auditStore, true, 60, "test-master-key");
            service.init();

            service.submit(createEntry("task-1", "conv-1"));
            service.submit(createEntry("task-2", "conv-1"));

            assertEquals(2, service.getQueueSize());
        }

        @Test
        void shouldFlushToStore() {
            var service = AuditLedgerService.createForTesting(auditStore, true, 60, "test-master-key");
            service.init();

            service.submit(createEntry("task-1", "conv-1"));
            service.submit(createEntry("task-2", "conv-1"));

            service.flush();

            verify(auditStore).appendBatch(argThat(list -> list.size() == 2));
            assertEquals(0, service.getQueueSize());
        }

        @Test
        void shouldNotFlushWhenQueueEmpty() {
            var service = AuditLedgerService.createForTesting(auditStore, true, 60, "test-master-key");
            service.init();

            service.flush();

            verify(auditStore, never()).appendBatch(any());
        }

        @Test
        void shouldNotEnqueueWhenDisabled() {
            var service = AuditLedgerService.createForTesting(auditStore, false, 60, "test-master-key");
            service.init();

            service.submit(createEntry("task-1", "conv-1"));

            assertEquals(0, service.getQueueSize());
        }
    }

    // ==================== HMAC ====================

    @Nested
    class HmacSigning {

        @Test
        void shouldComputeHmacWhenKeyConfigured() {
            var service = AuditLedgerService.createForTesting(auditStore, true, 60, "test-master-key");
            service.init();

            service.submit(createEntry("task-1", "conv-1"));
            service.flush();

            verify(auditStore).appendBatch(argThat(list -> {
                AuditEntry entry = list.get(0);
                return entry.hmac() != null && !entry.hmac().isBlank();
            }));
        }

        @Test
        void shouldNotComputeHmacWhenKeyNotConfigured() {
            var service = AuditLedgerService.createForTesting(auditStore, true, 60, "");
            service.init();

            service.submit(createEntry("task-1", "conv-1"));
            service.flush();

            verify(auditStore).appendBatch(argThat(list -> {
                AuditEntry entry = list.get(0);
                return entry.hmac() == null;
            }));
        }

        @Test
        void hmacShouldBeConsistentForSameData() {
            byte[] key = AuditHmac.deriveHmacKey("test-master-key");
            AuditEntry entry = createEntry("task-1", "conv-1");

            String hmac1 = AuditHmac.computeHmac(entry, key);
            String hmac2 = AuditHmac.computeHmac(entry, key);

            assertEquals(hmac1, hmac2);
        }

        @Test
        void hmacShouldDifferForDifferentData() {
            byte[] key = AuditHmac.deriveHmacKey("test-master-key");
            AuditEntry entry1 = createEntry("task-1", "conv-1");
            AuditEntry entry2 = createEntry("task-2", "conv-2");

            String hmac1 = AuditHmac.computeHmac(entry1, key);
            String hmac2 = AuditHmac.computeHmac(entry2, key);

            assertNotEquals(hmac1, hmac2);
        }

        @Test
        void verifyHmac_shouldDetectTampering() {
            byte[] key = AuditHmac.deriveHmacKey("test-master-key");
            AuditEntry entry = createEntry("task-1", "conv-1");
            String hmac = AuditHmac.computeHmac(entry, key);

            // Valid HMAC
            AuditEntry signed = withHmac(entry, hmac);
            assertTrue(AuditHmac.verifyHmac(signed, key));

            // Tampered HMAC
            AuditEntry tampered = withHmac(entry, "deadbeef");
            assertFalse(AuditHmac.verifyHmac(tampered, key));

            // Null HMAC
            assertFalse(AuditHmac.verifyHmac(entry, key));
        }

        @Test
        void deriveHmacKey_shouldProduceDifferentKeyThanVaultKek() {
            // Verify that the audit HMAC key and vault KEK are different
            // even when derived from the same master key
            byte[] hmacKey = AuditHmac.deriveHmacKey("shared-master-key");
            byte[] vaultKey = ai.labs.eddi.secrets.crypto.EnvelopeCrypto.deriveKeyFromString("shared-master-key");

            assertNotEquals(
                    java.util.HexFormat.of().formatHex(hmacKey),
                    java.util.HexFormat.of().formatHex(vaultKey),
                    "HMAC key and vault KEK should be different (different PBKDF2 salts)");
        }
    }

    // ==================== Secret Scrubbing ====================

    @Nested
    class SecretScrubbing {

        @Test
        void shouldScrubSecretsFromStringValues() {
            var service = AuditLedgerService.createForTesting(auditStore, true, 60, "");
            service.init();

            Map<String, Object> inputWithSecret = new LinkedHashMap<>();
            inputWithSecret.put("authorization", "Bearer sk-abcdefghijklmnopqrstuvwxyz1234567890abcdefghijklmn");
            inputWithSecret.put("normal", "hello world");

            AuditEntry entry = new AuditEntry(
                    "id-1", "conv-1", "agent-1", 1, "user-1", "production",
                    0, "task-1", "test", 0, 10L,
                    inputWithSecret, null, null, null,
                    List.of("action-1"), 0.0, Instant.now(), null);

            service.submit(entry);
            service.flush();

            verify(auditStore).appendBatch(argThat(list -> {
                AuditEntry scrubbed = list.get(0);
                Map<String, Object> input = scrubbed.input();
                // The SecretRedactionFilter should have redacted the API key
                String authValue = (String) input.get("authorization");
                return !authValue.contains("sk-abcdefghij");
            }));
        }
    }

    // ==================== Canonical String ====================

    @Nested
    class CanonicalString {

        @Test
        void shouldIncludeAllFields() {
            AuditEntry entry = createEntry("my-task", "my-conv");
            String canonical = AuditHmac.buildCanonicalString(entry);

            assertTrue(canonical.contains("my-task"));
            assertTrue(canonical.contains("my-conv"));
            assertTrue(canonical.contains("agent-1"));
            assertTrue(canonical.contains("user-1"));
            assertTrue(canonical.contains("hello"));
        }

        @Test
        void shouldHandleNullFields() {
            AuditEntry entry = new AuditEntry(
                    null, null, null, null, null, null,
                    0, null, null, 0, 0L,
                    null, null, null, null, null,
                    0.0, null, null);

            String canonical = AuditHmac.buildCanonicalString(entry);
            assertNotNull(canonical);
            assertFalse(canonical.isEmpty());
        }
    }

    // ==================== Flush Retry ====================

    @Nested
    class FlushRetry {

        @Test
        void shouldReQueueEntriesOnFirstFailure() {
            var service = AuditLedgerService.createForTesting(auditStore, true, 60, "");
            service.init();

            doThrow(new RuntimeException("DB unavailable")).when(auditStore).appendBatch(any());

            service.submit(createEntry("task-1", "conv-1"));
            service.submit(createEntry("task-2", "conv-1"));
            assertEquals(2, service.getQueueSize());

            service.flush(); // First failure — entries should be re-queued

            assertEquals(2, service.getQueueSize());
            verify(auditStore, times(1)).appendBatch(any());
        }

        @Test
        void shouldDropEntriesAfterMaxRetries() {
            var service = AuditLedgerService.createForTesting(auditStore, true, 60, "");
            service.init();

            doThrow(new RuntimeException("DB unavailable")).when(auditStore).appendBatch(any());

            service.submit(createEntry("task-1", "conv-1"));

            // Flush 3 times (MAX_FLUSH_RETRIES) — entries should be dropped
            service.flush(); // Attempt 1 — re-queue
            service.flush(); // Attempt 2 — re-queue
            service.flush(); // Attempt 3 — drop

            assertEquals(0, service.getQueueSize());
        }

        @Test
        void shouldResetRetryCounterOnSuccess() {
            var service = AuditLedgerService.createForTesting(auditStore, true, 60, "");
            service.init();

            // First: fail once
            doThrow(new RuntimeException("DB unavailable"))
                    .doNothing()
                    .when(auditStore).appendBatch(any());

            service.submit(createEntry("task-1", "conv-1"));
            service.flush(); // Failure — re-queue
            assertEquals(1, service.getQueueSize());

            service.flush(); // Success — counter reset
            assertEquals(0, service.getQueueSize());
            verify(auditStore, times(2)).appendBatch(any());
        }
    }

    // ==================== List Scrubbing ====================

    @Nested
    class ListScrubbing {

        @Test
        void shouldScrubSecretsInsideListOfStrings() {
            var service = AuditLedgerService.createForTesting(auditStore, true, 60, "");
            service.init();

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("tokens", List.of("normal-text", "sk-abcdefghijklmnopqrstuvwxyz1234567890abcdefghijklmn"));

            AuditEntry entry = new AuditEntry(
                    "id-1", "conv-1", "agent-1", 1, "user-1", "production",
                    0, "task-1", "test", 0, 10L,
                    input, null, null, null,
                    List.of(), 0.0, Instant.now(), null);

            service.submit(entry);
            service.flush();

            verify(auditStore).appendBatch(argThat(list -> {
                AuditEntry scrubbed = list.get(0);
                @SuppressWarnings("unchecked")
                List<String> tokens = (List<String>) scrubbed.input().get("tokens");
                return tokens.get(0).equals("normal-text") &&
                        !tokens.get(1).contains("sk-abcdefghij");
            }));
        }

        @Test
        void shouldScrubSecretsInsideNestedMaps() {
            var service = AuditLedgerService.createForTesting(auditStore, true, 60, "");
            service.init();

            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("apiKey", "sk-abcdefghijklmnopqrstuvwxyz1234567890abcdefghijklmn");
            nested.put("safe", "hello");

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("config", nested);

            AuditEntry entry = new AuditEntry(
                    "id-1", "conv-1", "agent-1", 1, "user-1", "production",
                    0, "task-1", "test", 0, 10L,
                    input, null, null, null,
                    List.of(), 0.0, Instant.now(), null);

            service.submit(entry);
            service.flush();

            verify(auditStore).appendBatch(argThat(list -> {
                AuditEntry scrubbed = list.get(0);
                @SuppressWarnings("unchecked")
                Map<String, Object> cfg = (Map<String, Object>) scrubbed.input().get("config");
                return !((String) cfg.get("apiKey")).contains("sk-abcdefghij") &&
                        cfg.get("safe").equals("hello");
            }));
        }
    }

    // ==================== AuditEntry ====================

    @Nested
    class AuditEntryTests {

        @Test
        void withEnvironment_shouldSetEnvironmentField() {
            AuditEntry entry = createEntry("task-1", "conv-1");
            assertNull(new AuditEntry(
                    "id", "conv", "agent", 1, "user", null,
                    0, "task", "type", 0, 0L,
                    null, null, null, null, null, 0.0, null, null).environment());

            AuditEntry enriched = entry.withEnvironment("production");
            assertEquals("production", enriched.environment());
            // Other fields unchanged
            assertEquals(entry.id(), enriched.id());
            assertEquals(entry.conversationId(), enriched.conversationId());
            assertEquals(entry.taskId(), enriched.taskId());
        }

        @Test
        void withHmac_shouldSetHmacField() {
            AuditEntry entry = createEntry("task-1", "conv-1");
            assertNull(entry.hmac());

            AuditEntry signed = entry.withHmac("abc123");
            assertEquals("abc123", signed.hmac());
            // Other fields unchanged
            assertEquals(entry.id(), signed.id());
            assertEquals(entry.conversationId(), signed.conversationId());
            assertEquals(entry.environment(), signed.environment());
            assertEquals(entry.taskId(), signed.taskId());
        }
    }

    // ==================== HMAC Determinism ====================

    @Nested
    class HmacDeterminism {

        @Test
        void shouldProduceSameHmacRegardlessOfMapImplementation() {
            byte[] key = AuditHmac.deriveHmacKey("test-key");

            // Same data in LinkedHashMap (insertion order)
            Map<String, Object> linked = new LinkedHashMap<>();
            linked.put("b", "2");
            linked.put("a", "1");

            // Same data in HashMap (unordered)
            Map<String, Object> hash = new java.util.HashMap<>();
            hash.put("a", "1");
            hash.put("b", "2");

            AuditEntry e1 = new AuditEntry(
                    "id", "conv", "agent", 1, "user", "test",
                    0, "task", "type", 0, 0L,
                    linked, null, null, null, null, 0.0, Instant.EPOCH, null);

            AuditEntry e2 = new AuditEntry(
                    "id", "conv", "agent", 1, "user", "test",
                    0, "task", "type", 0, 0L,
                    hash, null, null, null, null, 0.0, Instant.EPOCH, null);

            String hmac1 = AuditHmac.computeHmac(e1, key);
            String hmac2 = AuditHmac.computeHmac(e2, key);

            assertEquals(hmac1, hmac2,
                    "HMAC should be the same regardless of Map implementation (keys are sorted)");
        }
    }

    // ==================== Helper ====================

    private static AuditEntry withHmac(AuditEntry entry, String hmac) {
        return entry.withHmac(hmac);
    }
}
