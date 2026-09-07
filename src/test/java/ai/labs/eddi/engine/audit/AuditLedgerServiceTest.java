package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditLedgerServiceTest {

    @Mock
    private IAuditStore auditStore;

    private MeterRegistry meterRegistry;
    private AuditLedgerService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        // "The store holds no chain position for this conversation." A bare Mockito
        // mock would answer 0 for a long-returning method, which the seed reads as
        // "position 0 is taken" and turns into a first sequence of 1. Tests that
        // care about the seed override this.
        when(auditStore.maxSequence(anyString())).thenReturn(AuditEntry.UNSEQUENCED);
    }

    /**
     * A store that is genuinely unavailable refuses the batch <em>and</em> the
     * per-entry retry the ledger falls back to. Stubbing only {@code appendBatch}
     * would exercise the recovery path instead of the outage.
     */
    private void storeIsDown() {
        doThrow(new RuntimeException("db error")).when(auditStore).appendBatch(anyList());
        doThrow(new RuntimeException("db error")).when(auditStore).appendEntry(any());
    }

    /** Undo {@link #storeIsDown()} — both write paths accept again. */
    private void storeIsUp() {
        doNothing().when(auditStore).appendBatch(anyList());
        doNothing().when(auditStore).appendEntry(any());
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

    // ==================== isEnabled ====================

    @Test
    @DisplayName("isEnabled — returns true when enabled")
    void isEnabledTrue() {
        service = createService(true, null);
        assertTrue(service.isEnabled());
    }

    @Test
    @DisplayName("isEnabled — returns false when disabled")
    void isEnabledFalse() {
        service = createService(false, null);
        assertFalse(service.isEnabled());
    }

    // ==================== submit ====================

    @Test
    @DisplayName("submit — queues entry when enabled")
    void submitQueuesEntry() {
        service = createService(true, null);
        service.submit(entry("1", "conv1", "agent1"));
        assertEquals(1, service.getQueueSize());
    }

    @Test
    @DisplayName("submit — ignores null entry")
    void submitIgnoresNull() {
        service = createService(true, null);
        service.submit(null);
        assertEquals(0, service.getQueueSize());
    }

    @Test
    @DisplayName("submit — does nothing when disabled")
    void submitWhenDisabled() {
        service = createService(false, null);
        service.submit(entry("1", "conv1", "agent1"));
        assertEquals(0, service.getQueueSize());
    }

    @Test
    @DisplayName("submit — with HMAC key computes hmac")
    void submitWithHmacKey() {
        service = createService(true, "my-secret-key-for-testing");
        assertNotNull(service.getHmacKey());
        service.submit(entry("1", "conv1", "agent1"));
        assertEquals(1, service.getQueueSize());
    }

    // ==================== flush ====================

    @Test
    @DisplayName("flush — persists queued entries")
    void flushPersists() {
        service = createService(true, null);
        service.submit(entry("1", "conv1", "agent1"));
        service.submit(entry("2", "conv2", "agent2"));

        service.flush();

        verify(auditStore).appendBatch(argThat(batch -> batch.size() == 2));
        assertEquals(0, service.getQueueSize());
    }

    /**
     * A null timestamp must be stamped before signing, not by the store. v4 signs
     * the empty string for a null timestamp, but PostgresAuditStore substitutes
     * now() on write — so a null-timestamped entry would read back carrying a
     * timestamp the signature never covered and report INVALID forever, on that
     * backend only. Stamping in the service makes the stored row the signed row.
     */
    @Test
    @DisplayName("flush — a null timestamp is stamped before signing, and verifies")
    @SuppressWarnings("unchecked")
    void nullTimestampIsStampedBeforeSigning() {
        service = createService(true, "master-key");
        service.submit(new AuditEntry("nts-1", "conv1", "agent1", 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("text", "hello"), Map.of("text", "response"),
                null, null, List.of("action1"), 0.0, null, null, null));

        service.flush();

        var persisted = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(persisted.capture());
        AuditEntry stored = ((List<AuditEntry>) persisted.getValue()).getFirst();

        assertNotNull(stored.timestamp(), "the store's now()-fallback must never be what stamps a signed entry");
        assertEquals(0, stored.timestamp().getNano() % 1_000_000, "stamped at the signed (millisecond) precision");
        assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(stored),
                "what was signed is what is stored, so it must verify as-is");
    }

    /**
     * The submit path must floor the timestamp BEFORE signing — deterministically
     * pinned with a nano-precise input, so this does not depend on the test host's
     * clock resolution. Without the flooring, PostgreSQL's microsecond rounding can
     * move a stored timestamp across the millisecond the v4 signature covers, and
     * roughly one row in two thousand reports tampered for no reason.
     */
    @Test
    @DisplayName("flush — a nano-precise timestamp is floored before signing, and verifies")
    @SuppressWarnings("unchecked")
    void nanoPreciseTimestampIsFlooredBeforeSigning() {
        service = createService(true, "master-key");
        Instant nanoPrecise = Instant.parse("2026-08-20T10:15:30Z").plusNanos(123_999_600L);
        service.submit(new AuditEntry("floor-1", "conv1", "agent1", 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("text", "hello"), Map.of("text", "response"),
                null, null, List.of("action1"), 0.0, nanoPrecise, null, null));

        service.flush();

        var persisted = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(persisted.capture());
        AuditEntry stored = ((List<AuditEntry>) persisted.getValue()).getFirst();

        assertEquals(0, stored.timestamp().getNano() % 1_000_000,
                "the stored row must carry the millisecond value the signature covers — nothing left to round");
        assertEquals(nanoPrecise.truncatedTo(ChronoUnit.MILLIS), stored.timestamp());
        assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(stored));
    }

    // ==================== sequence-table eviction ====================

    /**
     * The table used to be {@code clear()}ed on overflow, on the reasoning that
     * re-seeding from {@code countByConversation} was "correct, only slower". It is
     * not: an entry that is still queued has consumed a position the store cannot
     * see yet, so the re-seed hands the same number out twice — and the verifier
     * grades a duplicate as {@code BROKEN}, i.e. the ledger reporting the
     * deployment as tampered because its own bookkeeping wrapped around.
     */
    @Test
    @DisplayName("sequence eviction — a conversation with queued entries is never re-seeded into a duplicate")
    void sequenceEvictionKeepsQueuedConversationsUnique() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, null);

        // Consume position 0 for "live" and leave it sitting in the queue.
        service.submit(entry("live-1", "live", "agent1"));

        // Overflow the table. Nothing is flushed, so "live" is still queued when
        // eviction runs — exactly the window the old clear() got wrong.
        for (int i = 0; i < AuditLedgerService.MAX_TRACKED_CONVERSATIONS + 5; i++) {
            service.submit(entry("fill-" + i, "filler-" + i, "agent1"));
        }

        service.submit(entry("live-2", "live", "agent1"));
        service.flush();

        var persisted = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(persisted.capture());
        @SuppressWarnings("unchecked")
        List<Long> liveSequences = ((List<AuditEntry>) persisted.getValue()).stream()
                .filter(e -> "live".equals(e.conversationId()))
                .map(AuditEntry::sequence)
                .toList();

        assertEquals(List.of(0L, 1L), liveSequences,
                "the second entry must continue the chain, not restart it at a position already taken");
    }

    /**
     * The other half of the contract: once the queue has drained, those counters
     * ARE safe to drop, so the table must actually shrink. Without this the fix
     * could "pass" by simply never evicting, which would strand every later
     * conversation on UNSEQUENCED.
     */
    @Test
    @DisplayName("sequence eviction — fully-persisted conversations are evicted so new ones still chain")
    void sequenceEvictionReclaimsPersistedConversations() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, null);

        for (int i = 0; i < AuditLedgerService.MAX_TRACKED_CONVERSATIONS + 5; i++) {
            service.submit(entry("fill-" + i, "filler-" + i, "agent1"));
        }
        service.flush(); // everything is now durably in the store

        service.submit(entry("fresh-1", "fresh", "agent1"));
        service.flush();

        var persisted = ArgumentCaptor.forClass(List.class);
        verify(auditStore, times(2)).appendBatch(persisted.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> lastBatch = (List<AuditEntry>) persisted.getValue();
        var fresh = lastBatch.stream().filter(e -> "fresh".equals(e.conversationId())).findFirst().orElseThrow();

        assertEquals(0L, fresh.sequence(),
                "with the queue drained the table must have room again, so this chains normally");
        assertNotEquals(AuditEntry.UNSEQUENCED, fresh.sequence());
    }

    /**
     * The third place a consumed chain position can live. Once
     * {@link AuditLedgerService#flush()} has polled a batch off the queue, those
     * positions are in neither the queue nor the store until the append returns —
     * so an eviction landing in that window would read their conversations as fully
     * persisted and re-seed them straight into a duplicate.
     * <p>
     * Driven from inside the mocked {@code appendBatch}, which is precisely when
     * the batch is in flight.
     */
    @Test
    @DisplayName("sequence eviction — a conversation in the in-flight flush batch is not re-seeded")
    void sequenceEvictionRetainsTheInFlightBatch() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, null);

        // Position 0 for "live", then force it out of the queue and into the
        // in-flight batch by flushing with an append that overflows the table
        // while it is still executing.
        service.submit(entry("live-1", "live", "agent1"));
        doAnswer(invocation -> {
            for (int i = 0; i < AuditLedgerService.MAX_TRACKED_CONVERSATIONS + 5; i++) {
                service.submit(entry("fill-" + i, "filler-" + i, "agent1"));
            }
            return null;
        }).when(auditStore).appendBatch(any());

        service.flush();

        // The append has returned, so "live" is genuinely persisted now; what
        // matters is that eviction did not drop its counter mid-flight.
        doAnswer(invocation -> null).when(auditStore).appendBatch(any());
        service.submit(entry("live-2", "live", "agent1"));

        var persisted = ArgumentCaptor.forClass(List.class);
        service.flush();
        verify(auditStore, atLeastOnce()).appendBatch(persisted.capture());
        @SuppressWarnings("unchecked")
        var live2 = ((List<AuditEntry>) persisted.getValue()).stream()
                .filter(e -> "live".equals(e.conversationId()))
                .findFirst().orElseThrow();

        assertEquals(1L, live2.sequence(),
                "the counter must have survived an eviction that ran while its entry was in the flush batch");
    }

    /**
     * When every tracked conversation is genuinely live, a scan evicts nothing —
     * and without a barrier the next submit for an unseen conversation would take
     * the write lock and traverse the whole queue (bounded at 100k) to reach the
     * same conclusion, with every other submitter blocked behind it on the read
     * lock. Only a flush can change the answer, so only a flush lifts the barrier.
     */
    @Test
    @DisplayName("sequence eviction — a futile scan is not repeated until a flush could change the answer")
    void futileEvictionScanIsNotRepeatedUntilFlush() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, null);

        // Fill the table with conversations that are all still queued, so nothing
        // is evictable.
        for (int i = 0; i < AuditLedgerService.MAX_TRACKED_CONVERSATIONS + 5; i++) {
            service.submit(entry("fill-" + i, "filler-" + i, "agent1"));
        }
        int trackedAfterFill = service.getTrackedConversationCount();

        // Further unseen conversations must not each re-scan; they degrade to
        // UNSEQUENCED, which is the honest verdict rather than a duplicate.
        for (int i = 0; i < 50; i++) {
            service.submit(entry("late-" + i, "late-conv-" + i, "agent1"));
        }
        assertEquals(trackedAfterFill, service.getTrackedConversationCount(),
                "a barred scan must not grow the table either");
        assertEquals(1, service.getFutileEvictionScans(),
                "the scan should have run once and then been barred, not once per submit");

        // A flush moves entries out of the queue, so the next scan is worthwhile
        // again — and this time it can actually evict.
        service.flush();
        service.submit(entry("after-1", "after-flush", "agent1"));

        assertTrue(service.getTrackedConversationCount() < trackedAfterFill,
                "once the queue drained, the barrier must lift and eviction reclaim the table");
    }

    /**
     * A conversation with a dead-lettered position can never be re-seeded — the
     * store count is smaller than the next free position once there is a gap, so
     * re-seeding would hand the same numbers out twice. Those counters are
     * therefore pinned for the process lifetime, which is only safe because they
     * cannot fill the table: the undelivered cap counts <em>sequences</em>, so the
     * worst case is one pinned conversation per tracked sequence.
     * <p>
     * The failure this guards is a plausible future edit — raising
     * {@code MAX_TRACKED_UNDELIVERED} to or past {@code MAX_TRACKED_CONVERSATIONS}
     * — after which a long store outage could pin every slot and strand every later
     * conversation on {@code UNSEQUENCED} until restart, with nothing failing to
     * say so.
     */
    @Test
    @DisplayName("sequence eviction — pinned undelivered conversations do not block reclamation")
    void undeliveredPinCannotExhaustTheTable() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, null);

        // A store outage dead-letters this conversation, so position 0 is
        // consumed but never persisted. Its counter is pinned from here on: the
        // store count can no longer tell us where the chain resumes.
        storeIsDown();
        service.submit(entry("pinned-1", "pinned", "agent1"));
        service.flush();
        service.flush();
        service.flush(); // dead-lettered
        assertEquals(Set.of(0L), service.undeliveredSequences("pinned"),
                "precondition: the outage left an unattributed position for this conversation");

        // Store recovers. Fill the table with conversations that DO persist.
        storeIsUp();
        for (int i = 0; i < AuditLedgerService.MAX_TRACKED_CONVERSATIONS + 5; i++) {
            service.submit(entry("fill-" + i, "filler-" + i, "agent1"));
        }
        service.flush();

        // The pinned conversation is retained while the persisted ones are
        // reclaimed, so a new conversation still gets a real chain position
        // rather than being stranded on UNSEQUENCED.
        service.submit(entry("fresh-1", "fresh", "agent1"));
        service.flush();

        var persisted = ArgumentCaptor.forClass(List.class);
        verify(auditStore, atLeastOnce()).appendBatch(persisted.capture());
        @SuppressWarnings("unchecked")
        var fresh = ((List<AuditEntry>) persisted.getValue()).stream()
                .filter(e -> "fresh".equals(e.conversationId()))
                .findFirst().orElseThrow();
        assertNotEquals(AuditEntry.UNSEQUENCED, fresh.sequence(),
                "a pinned conversation must not cost later conversations their chain position");
        assertEquals(0L, fresh.sequence());

        // And the pin did its job: the dead-lettered position is not handed out
        // a second time.
        service.submit(entry("pinned-2", "pinned", "agent1"));
        service.flush();
        verify(auditStore, atLeastOnce()).appendBatch(persisted.capture());
        @SuppressWarnings("unchecked")
        var pinnedSecond = ((List<AuditEntry>) persisted.getValue()).stream()
                .filter(e -> "pinned".equals(e.conversationId()))
                .findFirst().orElseThrow();
        assertEquals(1L, pinnedSecond.sequence(),
                "the counter survived, so the chain resumes past the dead-lettered 0 rather than reusing it");
    }

    @Test
    @DisplayName("flush — does nothing when queue is empty")
    void flushEmptyQueue() {
        service = createService(true, null);
        service.flush();
        verify(auditStore, never()).appendBatch(any());
    }

    @Test
    @DisplayName("flush — retries on failure (less than MAX_FLUSH_RETRIES)")
    void flushRetriesOnFailure() {
        service = createService(true, null);
        storeIsDown();

        service.submit(entry("1", "conv1", "agent1"));
        service.flush(); // First failure — requeues

        assertEquals(1, service.getQueueSize()); // Still in queue
    }

    @Test
    @DisplayName("flush — drops entries after MAX_FLUSH_RETRIES consecutive failures")
    void flushDropsAfterMaxRetries() {
        service = createService(true, null);
        storeIsDown();

        service.submit(entry("1", "conv1", "agent1"));
        service.flush(); // Failure 1 — requeue
        assertEquals(1, service.getQueueSize());
        service.flush(); // Failure 2 — requeue
        assertEquals(1, service.getQueueSize());
        service.flush(); // Failure 3 — drop + dead letter

        assertEquals(0, service.getQueueSize()); // Dropped
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count());
    }

    /**
     * The point of the per-entry fallback (finding 02). A bulk write is atomic in
     * neither backend, so re-offering the whole batch punished every entry for one
     * bad row: the rows that HAD landed were carried back into the store as
     * duplicate keys, nothing new was written, and after three flush windows every
     * record in them — from unrelated conversations — was dead-lettered together.
     */
    @Test
    @DisplayName("one unstorable entry does not take its whole batch down with it")
    void aPoisonEntryOnlyCostsItself() {
        service = createService(true, null);
        // The batch write always fails; the per-entry retry accepts everything
        // except the poison row. That is exactly the shape of a NOT NULL violation
        // on one entry inside a JDBC batch.
        doThrow(new RuntimeException("batch aborted")).when(auditStore).appendBatch(anyList());
        doAnswer(invocation -> {
            AuditEntry e = invocation.getArgument(0);
            if ("poison".equals(e.id())) {
                throw new RuntimeException("null value in column violates not-null constraint");
            }
            return null;
        }).when(auditStore).appendEntry(any());

        service.submit(entry("good-1", "conv-a", "agent-1"));
        service.submit(entry("poison", "conv-b", "agent-1"));
        service.submit(entry("good-2", "conv-c", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditStore, times(3)).appendEntry(captor.capture());
        assertEquals(List.of("good-1", "poison", "good-2"), captor.getAllValues().stream().map(AuditEntry::id).toList(),
                "every entry of the failed batch must be retried on its own");

        assertEquals(1, service.getQueueSize(), "only the entry that genuinely could not be stored comes back");
        assertEquals(0.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "the good entries landed, so nothing is dropped on the first failure");
    }

    /**
     * Finding 06: the final flush has no next attempt, so re-queuing loses the
     * entries silently — no dead-letter record, no dropped-counter increment, and
     * the queue is never drained again.
     */
    @Test
    @DisplayName("shutdown — a failing final flush dead-letters instead of re-queuing")
    void shutdownDeadLettersInsteadOfRequeuing() {
        service = createService(true, null);
        storeIsDown();

        service.submit(entry("1", "conv1", "agent1"));
        service.shutdown();

        assertEquals(0, service.getQueueSize(), "nothing may be left in a queue that will never be drained");
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "an abandoned entry must be counted, not lost between a re-queue and the JVM exiting");
    }

    /**
     * Finding 07. The re-queue loop ran backwards under a comment claiming it put
     * entries "at the front" — which a {@code ConcurrentLinkedQueue} has no way to
     * do, so all it achieved was reversing the batch. The next flush then persisted
     * the retried entries in reverse submission order.
     */
    @Test
    @DisplayName("a re-queued batch keeps its submission order")
    void requeuedEntriesKeepTheirSubmissionOrder() {
        service = createService(true, null);
        storeIsDown();

        service.submit(entry("1", "conv1", "agent1"));
        service.submit(entry("2", "conv1", "agent1"));
        service.submit(entry("3", "conv1", "agent1"));
        service.flush(); // fails on both paths — all three come back

        storeIsUp();
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore, times(2)).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> retried = (List<AuditEntry>) captor.getAllValues().get(1);
        assertEquals(List.of("1", "2", "3"), retried.stream().map(AuditEntry::id).toList(),
                "the retry must not reverse the batch");
    }

    /**
     * The per-entry fallback must not turn an outage into an unbounded blocking
     * loop. It runs on the writer thread inside {@code synchronized flush()} — the
     * monitor the {@code @PreDestroy} final flush also waits on — so an uncapped
     * pass over a queue grown toward the 100k bound, against a PostgreSQL whose
     * connect timeout is measured in tens of seconds, would hold both for hours,
     * where the old whole-batch retry failed once per flush window.
     */
    @Test
    @DisplayName("the per-entry retry stops calling a store that has refused N entries in a row")
    void perEntryRetryGivesUpOnAStoreThatIsSimplyDown() {
        service = createService(true, null);
        storeIsDown();

        int batchSize = AuditLedgerService.MAX_CONSECUTIVE_ENTRY_FAILURES + 5;
        for (int i = 0; i < batchSize; i++) {
            service.submit(entry("id-" + i, "conv-" + i, "agent-1"));
        }
        service.flush();

        verify(auditStore, times(AuditLedgerService.MAX_CONSECUTIVE_ENTRY_FAILURES)).appendEntry(any());
        assertEquals(batchSize, service.getQueueSize(),
                "the entries the pass skipped must still come back for the next flush, not be lost");
    }

    /**
     * Running out of the per-entry time budget is not a store failure.
     * <p>
     * The budget bounds how long one flush may spend on the per-entry fallback, and
     * it has to: an unreachable store costs a connection-acquisition timeout per
     * call, on the monitor the {@code @PreDestroy} final flush waits on. But the
     * abandoned tail came back as "unstorable" regardless of whether anything had
     * actually been refused, so it incremented {@code consecutiveFailures} exactly
     * like a refusal — and a store that was merely SLOW (a large backlog at a few
     * ms per insert) had its still-unoffered backlog dead-lettered on the third
     * flush and counted on {@code eddi_audit_entries_dropped_total}, while it had
     * accepted every single entry it was handed.
     * <p>
     * The two earlier flushes here put the failure counter at 2 — the interesting
     * boundary — so the assertion is about what the third one does when the store
     * has started accepting again, slowly.
     */
    @Test
    @DisplayName("a slow-but-accepting store defers its backlog instead of having it dead-lettered")
    void budgetExhaustionIsNotCountedAsAStoreFailure() {
        service = createService(true, null);
        // The batch path never works, so every flush falls through to the per-entry
        // pass — the shape of a bulk write that one bad row aborts.
        doThrow(new RuntimeException("batch aborted")).when(auditStore).appendBatch(anyList());
        doThrow(new RuntimeException("db error")).when(auditStore).appendEntry(any());

        int submitted = 6;
        for (int i = 0; i < submitted; i++) {
            service.submit(entry("id-" + i, "conv-" + i, "agent-1"));
        }

        service.flush(); // genuine refusals — failure 1
        service.flush(); // genuine refusals — failure 2
        assertEquals(submitted, service.getQueueSize(), "two failures re-queue, they do not drop");
        assertEquals(0.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count());

        // The store recovers, but the first insert is slower than the whole budget,
        // so the pass stores one entry and never gets to offer the rest.
        var firstCall = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstCall.compareAndSet(true, false)) {
                Thread.sleep(AuditLedgerService.ENTRY_RETRY_BUDGET.toMillis() + 300);
            }
            return null;
        }).when(auditStore).appendEntry(any());

        service.flush(); // third flush — with the bug, this dead-letters the tail

        assertEquals(0.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "nothing was refused in this pass, so nothing may be dropped — the store took what it was offered");
        assertEquals(submitted - 1, service.getQueueSize(),
                "the entries the budget never reached must come back for the next flush");
        verify(auditStore, times(AuditLedgerService.MAX_CONSECUTIVE_ENTRY_FAILURES * 2 + 1)).appendEntry(any());
    }

    /**
     * The operator-facing half of the same boundary.
     * <p>
     * Refusing and running out of wall clock are not alternatives: a pass may
     * refuse one or two entries — below {@code MAX_CONSECUTIVE_ENTRY_FAILURES}, so
     * it keeps going — and only then hit the deadline. The message opened with
     * "accepted what it was offered" in that case too, which reads as an all-clear
     * about the store while the same flush is handing those refusals to
     * {@code handlePersistentFailures}. Whoever is watching the ledger during an
     * incident has to be told the refusals happened, and that the two groups leave
     * by different doors.
     */
    @Test
    @DisplayName("the budget-exhaustion log names the refusals that happened in the same pass")
    void budgetExhaustionMessageDoesNotHideRefusals() {
        String withRefusals = AuditLedgerService.budgetExhaustedMessage(2, 7);

        assertFalse(withRefusals.contains("accepted what it was offered"),
                "two entries were refused in this very pass — claiming the store took everything sends the operator "
                        + "past the rows the same flush is about to re-queue or dead-letter");
        assertTrue(withRefusals.contains("refused 2"), "the refusal count must be in the message: " + withRefusals);
        assertTrue(withRefusals.contains("7"), "the deferred tail is still worth naming: " + withRefusals);

        // The clean case keeps its wording — it is accurate there, and it is what the
        // slow-but-healthy store diagnosis depends on.
        String clean = AuditLedgerService.budgetExhaustedMessage(0, 7);
        assertTrue(clean.contains("accepted what it was offered"), clean);
        assertTrue(clean.contains("7"), clean);
    }

    /**
     * The classification the log sentence has to agree with.
     * <p>
     * A deferral promises "the next flush takes these". Three stop conditions
     * cannot keep that promise and must abandon the tail to
     * {@code handlePersistentFailures} instead, each with its own sentence: a
     * refusing store, a SIGTERM landing mid-pass (there is no next flush at all —
     * the message used to say "(shutdown started) — deferring ... to the next
     * flush", twelve lines below a comment stating that during shutdown there is no
     * next flush), and a budget that expired against a store which had accepted
     * nothing. Only the last of the four — the store took something, it is merely
     * slow — defers.
     */
    @Test
    @DisplayName("the abandoned-tail classification defers only a store that has actually stored something")
    void abandonedTailIsClassifiedByWhatThePassObserved() {
        // Refusing store: the failure cap fired.
        String refusing = AuditLedgerService.abandonedTailReason(true, false, 0, 3, 7);
        assertNotNull(refusing, "a refusing store's tail is at the same risk as the refusals — it may not be deferred");
        assertTrue(refusing.contains("7"), refusing);

        // SIGTERM mid-pass: no next flush exists, so this cannot be a deferral.
        String shuttingDown = AuditLedgerService.abandonedTailReason(false, true, 4, 0, 7);
        assertNotNull(shuttingDown, "there is no next flush during shutdown, so the tail must be dead-lettered");
        assertFalse(shuttingDown.contains("to the next flush"),
                "the shutdown message must not promise a flush that will never run: " + shuttingDown);
        assertTrue(shuttingDown.contains("dead-lettered"), shuttingDown);

        // Budget expired and the store had accepted nothing: unavailable, not slow.
        // Against a store whose per-call timeout exceeds the budget this is the ONLY
        // signal there is — the failure cap is unreachable because one call spends
        // the whole budget.
        String tookNothing = AuditLedgerService.abandonedTailReason(false, false, 0, 1, 5);
        assertNotNull(tookNothing,
                "a pass that offered one entry, had it refused and then ran out of clock has met an unreachable store; "
                        + "deferring its tail circulates the backlog instead of escalating it");
        assertTrue(tookNothing.contains("5"), tookNothing);

        // The genuinely slow store — it stored something — still defers.
        assertNull(AuditLedgerService.abandonedTailReason(false, false, 1, 0, 7),
                "a store that accepted an entry in this pass is slow, not down: its unoffered tail has failed at nothing");
        assertNull(AuditLedgerService.abandonedTailReason(false, false, 1, 2, 7),
                "one poison row does not make a store that is still accepting unavailable");
        assertNull(AuditLedgerService.abandonedTailReason(false, false, 0, 0, 7),
                "a pass that offered nothing at all has observed nothing to escalate on");
    }

    /**
     * The store shape {@code ENTRY_RETRY_BUDGET}'s own Javadoc names, driven end to
     * end: unreachable rather than refusing, so every call costs a full
     * connection-acquisition timeout (Agroal's default is 5s) before it throws.
     * <p>
     * {@code MAX_CONSECUTIVE_ENTRY_FAILURES} is unreachable against it — the first
     * call spends the entire 2s budget, so the pass stops at the second entry with
     * a failure count of 1. Classifying that tail as merely "deferred" put it
     * straight back on the queue every flush while only the single refused entry
     * advanced {@code consecutiveFailures}: exactly one entry reached the sink per
     * three flushes, the rest circulated forever, every flush held the
     * {@code synchronized flush()} monitor for a full acquisition timeout, and once
     * the queue reached {@code eddi.audit.max-queue-size} every entry submitted in
     * between was discarded at {@code submit()} with a dropped-counter tick and no
     * dead-letter record — unattributable loss in the evidence store, which the
     * chain verifier grades {@code BROKEN}. The whole-batch retry it replaced
     * dead-lettered the abandoned tail on the third flush, so that classification
     * was a regression against both the previous code and the queue bound.
     */
    @Test
    @DisplayName("a store whose calls outlast the budget escalates its whole backlog, instead of circulating it forever")
    void aStoreSlowerThanTheBudgetStillEscalatesItsBacklog(@TempDir Path tempDir) {
        // A sequenced store, so the abandoned positions are recorded in
        // `undelivered` — the only in-process proof that writeToDeadLetter ran.
        when(auditStore.supportsSequence()).thenReturn(true);
        Path sink = tempDir.resolve("eddi-audit-deadletter.jsonl");
        service = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry,
                AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE, sink.toString());
        service.init();

        doThrow(new RuntimeException("db error")).when(auditStore).appendBatch(anyList());
        doAnswer(invocation -> {
            // One connection-acquisition timeout, scaled down but still longer than
            // the whole per-entry budget — which is the entire point of this shape.
            Thread.sleep(AuditLedgerService.ENTRY_RETRY_BUDGET.toMillis() + 300);
            throw new RuntimeException("db unreachable");
        }).when(auditStore).appendEntry(any());

        int submitted = 6;
        for (int i = 0; i < submitted; i++) {
            service.submit(entry("id-" + i, "conv1", "agent-1"));
        }

        service.flush(); // failure 1
        service.flush(); // failure 2
        service.flush(); // failure 3 — MAX_FLUSH_RETRIES, so the sink

        assertEquals(0, service.getQueueSize(),
                "after MAX_FLUSH_RETRIES against an unreachable store the backlog belongs in the sink, not back on a "
                        + "queue that will drop later submissions unrecorded once it fills");
        assertEquals((double) submitted, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "every abandoned entry must be counted, not just the one the pass got as far as offering");
        assertEquals(submitted, service.undeliveredSequences("conv1").size(),
                "and every abandoned chain position must be attributable — an unrecorded gap is graded BROKEN, "
                        + "which accuses the deployment of deleting rows the ledger itself never wrote");
        // The budget did its other job: one call per flush, not one per entry.
        verify(auditStore, times(3)).appendEntry(any());
    }

    /**
     * The other half of the same boundary: a store that is genuinely refusing must
     * still escalate to the dead letter, or the bound above would turn every outage
     * into an unbounded re-queue loop.
     */
    @Test
    @DisplayName("genuine refusals still reach the dead letter after MAX_FLUSH_RETRIES")
    void refusalsStillEscalate() {
        service = createService(true, null);
        storeIsDown();

        service.submit(entry("1", "conv1", "agent1"));
        service.flush();
        service.flush();
        service.flush();

        assertEquals(0, service.getQueueSize());
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "a store that refuses is still declared unavailable on the third flush");
    }

    /**
     * An entry submitted while the final flush is draining the queue is still
     * perfectly storable, but nothing scheduled will ever pick it up. Recording it
     * as dropped without offering it to a healthy store once more turns a clean
     * shutdown into avoidable audit loss.
     */
    @Test
    @DisplayName("shutdown — an entry submitted during the final flush is stored, not dead-lettered")
    void shutdownStoresEntriesSubmittedDuringTheFinalFlush() {
        service = createService(true, null);
        var lateSubmissionDone = new AtomicBoolean(false);
        doAnswer(invocation -> {
            if (lateSubmissionDone.compareAndSet(false, true)) {
                // Arrives after the final flush has already drained the queue.
                service.submit(entry("late", "conv1", "agent1"));
            }
            return null;
        }).when(auditStore).appendBatch(anyList());

        service.submit(entry("1", "conv1", "agent1"));
        service.shutdown();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore, times(2)).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> lastBatch = (List<AuditEntry>) captor.getAllValues().get(1);
        assertEquals(List.of("late"), lastBatch.stream().map(AuditEntry::id).toList(),
                "the late entry must be offered to the store, not written straight to the dead-letter sink");
        assertEquals(0, service.getQueueSize());
        assertEquals(0.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "a healthy store means nothing was dropped");
    }

    /**
     * The window {@code drainQueueToDeadLetter} actually exists for, and the one no
     * test reached. {@link #shutdownStoresEntriesSubmittedDuringTheFinalFlush}
     * covers an entry that arrives during the FIRST final flush — the second flush
     * stores it and the drain finds nothing, which is why that test asserts
     * {@code dropped == 0}. An entry that arrives during the SECOND flush has no
     * flush left: the executor is already down and the scheduled task will never
     * run again, so the drain is the only thing standing between it and silent
     * loss.
     * <p>
     * Both halves of the drain are pinned, because either could be deleted without
     * any existing test noticing: the {@code droppedCounter} increment (an operator
     * has to be able to see that the ledger abandoned something) and the sink write
     * — asserted through {@code undeliveredSequences}, which only
     * {@code writeToDeadLetter} populates, so a drain that counted the entry and
     * threw it away would still fail here.
     */
    @Test
    @DisplayName("shutdown — an entry submitted during the SECOND final flush is drained to the dead-letter sink")
    void shutdownDrainsEntriesSubmittedDuringTheSecondFinalFlush() {
        // A store that assigns chain positions, so the abandoned entry has a sequence
        // for undeliveredSequences to record — an UNSEQUENCED entry is skipped there
        // by design and the sink assertion below could not tell that apart from a
        // drain that never wrote.
        when(auditStore.supportsSequence()).thenReturn(true);
        service = createService(true, null);
        var flushes = new AtomicInteger();
        doAnswer(invocation -> {
            switch (flushes.incrementAndGet()) {
                // Arrives after the first final flush drained the queue; the second
                // flush is still to come, so this one is storable.
                case 1 -> service.submit(entry("late", "conv1", "agent1"));
                // Arrives after the SECOND flush drained the queue. Nothing will ever
                // flush again.
                case 2 -> service.submit(entry("very-late", "conv1", "agent1"));
                default -> {
                }
            }
            return null;
        }).when(auditStore).appendBatch(anyList());

        service.submit(entry("1", "conv1", "agent1"));
        service.shutdown();

        verify(auditStore, times(2)).appendBatch(anyList());
        assertEquals(0, service.getQueueSize(),
                "nothing may be left in a queue that will never be drained again");
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "the entry the ledger abandoned must be counted, not lost between the last flush and the JVM exiting");
        assertEquals(1, service.undeliveredSequences("conv1").size(),
                "and it must actually reach the dead-letter sink — undelivered is only populated by writeToDeadLetter");
    }

    /**
     * Finding 24: an operator setting 0 in the hope of "flush immediately" used to
     * abort startup with {@code IllegalArgumentException("period <= 0")} thrown
     * from inside the executor API — a stack trace that never names the property.
     */
    @Test
    @DisplayName("a non-positive flush interval falls back to the default instead of failing startup")
    void nonPositiveFlushIntervalFallsBackToTheDefault() {
        var svc = AuditLedgerService.createForTesting(auditStore, true, 0, null, meterRegistry);
        assertDoesNotThrow(svc::init);
        assertEquals(AuditLedgerService.DEFAULT_FLUSH_INTERVAL_SECONDS, svc.getFlushIntervalSeconds());
        svc.shutdown();
    }

    // ==================== scrub secrets ====================

    @Test
    @DisplayName("submit — scrubs secrets from input/output maps")
    void submitScrubsSecrets() {
        service = createService(true, null);
        var entryWithSecret = new AuditEntry("1", "conv1", "agent1", 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("apiKey", "sk-1234567890abcdef"), Map.of("text", "safe"),
                null, null, List.of(), 0.0, Instant.now(), null, null);

        service.submit(entryWithSecret);
        service.flush();

        verify(auditStore).appendBatch(any());
    }

    // ==================== serializeDeadLetterEntry ====================

    @Test
    @DisplayName("serializeDeadLetterEntry — includes type field when provided")
    void serializeWithType() {
        service = createService(true, null);
        var e = entry("1", "conv1", "agent1");
        String json = service.serializeDeadLetterEntry(e, "audit_dead_letter");
        assertTrue(json.contains("\"type\":\"audit_dead_letter\""));
        assertTrue(json.contains("\"conversationId\":\"conv1\""));
    }

    /**
     * Finding 05. The record used to be five fields — timestamp of the drop,
     * conversationId, agentId, taskId, taskType. With no {@code sequence} in it an
     * operator who restarted after a dead-letter event could not prove which
     * positions the ledger itself had abandoned, so every self-inflicted gap read
     * as {@code BROKEN} forever; and the audit content, the HMAC and the agent
     * signature were gone outright, which is not the "durable evidence"
     * {@code undeliveredSequences} rests its verdict on.
     */
    @Test
    @DisplayName("serializeDeadLetterEntry — carries the whole entry, not a five-field summary")
    void deadLetterRecordIsReplayable() {
        service = createService(true, null);
        Instant written = Instant.parse("2026-01-02T03:04:05Z");
        var e = new AuditEntry("dl-1", "conv1", "agent1", 1, "user1", "production",
                2, "taskId", "LlmTask", 3, 100L,
                Map.of("text", "hello"), Map.of("text", "response"),
                Map.of("model", "gpt"), Map.of("tool", "calc"), List.of("action1"), 0.25, written,
                "v4:abcdef", "sig-1", 42L);

        String json = service.serializeDeadLetterEntry(e, "audit_dead_letter");

        assertTrue(json.contains("\"id\":\"dl-1\""), json);
        assertTrue(json.contains("\"sequence\":42"), json);
        assertTrue(json.contains("\"hmac\":\"v4:abcdef\""), json);
        assertTrue(json.contains("\"agentSignature\":\"sig-1\""), json);
        assertTrue(json.contains("\"entryTimestamp\":\"2026-01-02T03:04:05Z\""), json);
        assertTrue(json.contains("\"llmDetail\""), json);
        assertTrue(json.contains("\"toolCalls\""), json);
        // The wrapper fields existing consumers already read are unchanged.
        assertTrue(json.contains("\"conversationId\":\"conv1\""), json);
        assertTrue(json.contains("\"type\":\"audit_dead_letter\""), json);
    }

    /**
     * The price of making the record replayable, pinned so it stays a deliberate
     * decision. Widening the record from five metadata fields to the whole entry
     * put the user id and the verbatim prompts and responses into a plaintext JSONL
     * file on disk — content the old record did not contain — and the GDPR erasure
     * cascade pseudonymises the ledger and the database logs but touches nothing
     * under {@code eddi.audit.dead-letter-path}. So an Art. 17 erasure now leaves
     * user content behind unless the operator handles that file, which is why
     * {@code docs/audit-ledger.md} and {@code docs/gdpr-compliance.md} both say so.
     * <p>
     * Assert it explicitly rather than leaving it implied by "the whole entry":
     * whoever next changes what goes into this record should have to change a test
     * that names the retention obligation.
     */
    @Test
    @DisplayName("serializeDeadLetterEntry — the sink holds personal data, which erasure does not reach")
    void deadLetterRecordCarriesPersonalDataAndIsOutsideTheErasureCascade() {
        service = createService(true, null);
        var e = new AuditEntry("dl-2", "conv1", "agent1", 1, "subject-42", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("text", "my name is Alice"), Map.of("text", "hello Alice"),
                null, null, List.of(), 0.0, Instant.now(), null, null, 7L);

        String json = service.serializeDeadLetterEntry(e, null);

        assertTrue(json.contains("\"userId\":\"subject-42\""),
                "the sink identifies the data subject, so it is in scope for Art. 17: " + json);
        assertTrue(json.contains("my name is Alice"), "and carries their verbatim prompt: " + json);
        assertTrue(json.contains("hello Alice"), "and the verbatim response: " + json);
    }

    @Test
    @DisplayName("serializeDeadLetterEntry — omits type when null")
    void serializeWithoutType() {
        service = createService(true, null);
        var e = entry("1", "conv1", "agent1");
        String json = service.serializeDeadLetterEntry(e, null);
        assertFalse(json.contains("\"type\""));
        assertTrue(json.contains("\"agentId\":\"agent1\""));
    }

    // ==================== shutdown ====================

    @Test
    @DisplayName("shutdown — flushes remaining entries")
    void shutdownFlushes() {
        service = createService(true, null);
        service.submit(entry("1", "conv1", "agent1"));
        service.shutdown();
        verify(auditStore).appendBatch(any());
    }

    @Test
    @DisplayName("shutdown — handles null executor when disabled")
    void shutdownWhenDisabled() {
        service = createService(false, null);
        assertDoesNotThrow(() -> service.shutdown());
    }

    // ==================== G20: bounded queue ====================

    /**
     * The queue was a {@code ConcurrentLinkedQueue} with no bound, and a failed
     * flush re-offered its whole batch back into it — so a store that stopped
     * accepting writes grew the heap without limit and fed its own failure. Entries
     * past the bound are now dropped and counted instead.
     * <p>
     * This covers the submit side only; the retry/dead-letter side is driven by
     * {@link #failedFlushDeadLettersWhatNoLongerFits()}. (It used to stub a
     * throwing {@code appendBatch} here, which was dead weight: nothing in this
     * test ever calls {@code flush()}.)
     */
    @Test
    @DisplayName("a stalled store cannot grow the queue past its bound")
    void stalledStoreDoesNotGrowTheQueueWithoutLimit() {
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 5);
        svc.init();

        for (int i = 0; i < 200; i++) {
            svc.submit(entry("id-" + i, "conv-1", "agent-1"));
        }

        assertEquals(5, svc.getQueueSize(), "the queue must stay at its bound instead of accumulating every entry");
        assertEquals(195.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "dropped entries must be counted, not silently lost");
        verify(auditStore, never()).appendBatch(anyList());
    }

    // ==================== G18 x G20: drops must not forge a tamper verdict ====

    /**
     * The collision the two guarantees used to create: G18 puts a signed, gap-free
     * per-conversation sequence on every entry, G20 drops entries once the queue is
     * full. Consuming the sequence first meant a dropped entry burned a chain
     * position, so {@code /auditstore/verify} reported {@code ChainStatus.BROKEN} —
     * the ledger accusing the deployment of deleting a record the ledger itself had
     * thrown away.
     */
    @Test
    @DisplayName("an entry the ledger drops never consumes a chain position")
    @SuppressWarnings("unchecked")
    void droppedEntryDoesNotConsumeAChainPosition() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-1")).thenReturn(0L);
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, "master-key-1234567890", meterRegistry, 1);
        svc.init();

        svc.submit(entry("id-1", "conv-1", "agent-1")); // queued → sequence 0
        svc.submit(entry("id-2", "conv-1", "agent-1")); // queue full → dropped
        svc.flush(); // store accepts, queue drains
        svc.submit(entry("id-3", "conv-1", "agent-1")); // queued → must be sequence 1
        svc.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore, times(2)).appendBatch(captor.capture());
        List<Long> writtenSequences = ((List<List<AuditEntry>>) (List<?>) captor.getAllValues()).stream().flatMap(List::stream)
                .map(AuditEntry::sequence).toList();

        assertEquals(List.of(0L, 1L), writtenSequences,
                "the dropped entry must not burn sequence 1 — that hole makes /auditstore/verify report BROKEN");
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count());
        assertTrue(svc.undeliveredSequences("conv-1").isEmpty(),
                "nothing was abandoned mid-chain, so there is nothing to attribute");
    }

    /**
     * The retry path's dead-letter branch: the flush drains the queue, the store
     * refuses the batch, and by the time it is re-offered the queue has refilled.
     * Those entries already own chain positions, so the positions must be recorded
     * — otherwise the gap is indistinguishable from a deletion.
     */
    @Test
    @DisplayName("a re-offer that no longer fits is dead-lettered and its chain positions are recorded")
    void failedFlushDeadLettersWhatNoLongerFits() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-1")).thenReturn(0L);
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, "master-key-1234567890", meterRegistry, 2);
        svc.init();

        // The store is down, and while the flush is in flight the queue it just
        // drained fills back up — so the failed batch no longer fits on retry.
        doAnswer(invocation -> {
            svc.submit(entry("late-1", "conv-1", "agent-1"));
            svc.submit(entry("late-2", "conv-1", "agent-1"));
            throw new RuntimeException("store down");
        }).when(auditStore).appendBatch(anyList());
        // The store is down, so the per-entry retry the ledger falls back to
        // refuses as well; only then is the batch re-offered to the queue.
        doThrow(new RuntimeException("store down")).when(auditStore).appendEntry(any());

        svc.submit(entry("id-1", "conv-1", "agent-1")); // sequence 0
        svc.submit(entry("id-2", "conv-1", "agent-1")); // sequence 1
        svc.flush();

        assertEquals(2, svc.getQueueSize(), "the retry re-offer must respect the bound");
        assertEquals(Set.of(0L, 1L), svc.undeliveredSequences("conv-1"),
                "positions the ledger abandoned must be recorded, or verify() reports them as deletions");
        assertEquals(2.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count());
    }

    @Test
    @DisplayName("a batch abandoned after MAX_FLUSH_RETRIES records its chain positions")
    void abandonedBatchRecordsItsChainPositions() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-1")).thenReturn(0L);
        service = createService(true, "master-key-1234567890");
        storeIsDown();

        service.submit(entry("id-1", "conv-1", "agent-1"));
        service.flush(); // failure 1 — requeue
        service.flush(); // failure 2 — requeue
        service.flush(); // failure 3 — dead-letter

        assertEquals(0, service.getQueueSize());
        assertEquals(Set.of(0L), service.undeliveredSequences("conv-1"));
        assertTrue(service.undeliveredSequences("conv-other").isEmpty());
    }

    @Test
    @DisplayName("submits past the bound are dropped, not queued")
    void submitsPastTheBoundAreDropped() {
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 2);
        svc.init();

        svc.submit(entry("id-1", "conv-1", "agent-1"));
        svc.submit(entry("id-2", "conv-1", "agent-1"));
        svc.submit(entry("id-3", "conv-1", "agent-1"));

        assertEquals(2, svc.getQueueSize());
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count());
    }

    @Test
    @DisplayName("queue size returns to zero after a successful flush")
    void queueDrainsOnSuccessfulFlush() {
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 10);
        svc.init();

        svc.submit(entry("id-1", "conv-1", "agent-1"));
        svc.submit(entry("id-2", "conv-1", "agent-1"));
        assertEquals(2, svc.getQueueSize());

        svc.flush();
        assertEquals(0, svc.getQueueSize());
    }

    // ==================== G18: conversation sequence ====================

    @Test
    @DisplayName("entries are numbered per conversation when the store supports it")
    void entriesAreNumberedPerConversation() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.submit(entry("id-2", "conv-a", "agent-1"));
        service.submit(entry("id-3", "conv-b", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();

        assertEquals(0L, written.get(0).sequence());
        assertEquals(1L, written.get(1).sequence(), "the second entry of a conversation continues the chain");
        assertEquals(0L, written.get(2).sequence(), "a different conversation has its own chain");
    }

    /**
     * A SIGTERM landing while a per-entry pass is already running.
     * <p>
     * {@code shuttingDown} is re-read on every iteration precisely because a
     * scheduled flush can be inside this loop when the signal arrives, holding the
     * monitor the {@code @PreDestroy} final flush waits on. Against a store that is
     * unreachable rather than refusing, every further call costs a full
     * connection-acquisition timeout — {@code shutdown()} then runs two flushes
     * around a 5s await before the queue drain, and the shipped Kubernetes
     * manifests give it 30s, so the pod was SIGKILLed inside this loop and the
     * drain never ran. Stopping mid-pass and dead-lettering the rest is what keeps
     * the shutdown inside its grace period; the sink carries the full entry, so
     * nothing the store would have taken is lost.
     * <p>
     * The flag is set from inside the store call rather than by calling
     * {@code shutdown()}, which would deadlock on the very monitor {@code flush()}
     * holds — this reproduces the interleaving deterministically instead of racing
     * for it.
     */
    @Test
    @DisplayName("a SIGTERM mid-pass stops offering entries and dead-letters the batch, instead of timing out per entry")
    void aShutdownArrivingMidPassAbandonsTheRestOfTheBatch(@TempDir Path tempDir) throws Exception {
        when(auditStore.supportsSequence()).thenReturn(true);
        Path sink = tempDir.resolve("eddi-audit-deadletter.jsonl");
        service = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry,
                AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE, sink.toString());
        service.init();

        var shuttingDown = AuditLedgerService.class.getDeclaredField("shuttingDown");
        shuttingDown.setAccessible(true);

        doThrow(new RuntimeException("batch aborted")).when(auditStore).appendBatch(anyList());
        var firstCall = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstCall.compareAndSet(true, false)) {
                shuttingDown.setBoolean(service, true); // the signal lands here
            }
            throw new RuntimeException("db unreachable");
        }).when(auditStore).appendEntry(any());

        for (int i = 0; i < 5; i++) {
            service.submit(entry("id-" + i, "conv1", "agent-1"));
        }

        service.flush();

        verify(auditStore, times(1)).appendEntry(any());
        assertEquals(0, service.getQueueSize(),
                "there is no next flush to defer to, so nothing may be re-queued");
        assertEquals(5.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "every entry of the batch is accounted for, including the four never offered");
        String sinkContents = Files.readString(sink);
        for (int i = 0; i < 5; i++) {
            assertTrue(sinkContents.contains("\"id\":\"id-" + i + "\""),
                    "entry id-" + i + " must be recoverable from the sink: " + sinkContents);
        }
        assertEquals(5, service.undeliveredSequences("conv1").size(),
                "and every abandoned chain position must be attributable, or the gap is graded BROKEN");
    }

    /**
     * A compliance or oversight entry has no conversation, and an entry submitted
     * with a blank one is the same case: there is no chain to join. Neither may
     * cost a store round trip in the pre-warm, and neither may be handed a position
     * — the sequence is part of the signed payload, so a store that drops it would
     * make every such row read as tampered.
     */
    @Test
    @DisplayName("an entry with no conversation is neither pre-warmed nor sequenced")
    void anEntryWithoutAConversationIsNeverSequenced() {
        when(auditStore.supportsSequence()).thenReturn(true);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "   ", "agent-1"));
        service.submit(entry("id-2", null, "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        assertEquals(2, written.size());
        assertTrue(written.stream().allMatch(e -> e.sequence() == AuditEntry.UNSEQUENCED),
                "a chainless entry signed with a position the store cannot round-trip reads as tampered forever");
        verify(auditStore, never()).maxSequence(anyString());
        verify(auditStore, never()).countByConversation(anyString());
    }

    /**
     * The interface default, exercised through a real implementation rather than a
     * Mockito mock — a mock answers 0 for a {@code long} method, which is precisely
     * the value {@code UNSEQUENCED} exists to be distinguishable from. A store that
     * does not implement {@code maxSequence} must fall back to the row count; if
     * the default answered 0 instead, every such chain would be seeded at 1 and its
     * first position would be skipped forever.
     */
    @Test
    @DisplayName("a store that does not implement maxSequence reports UNSEQUENCED, not position 0")
    void theMaxSequenceDefaultReportsUnsequenced() {
        IAuditStore storeWithoutSequenceSupport = new IAuditStore() {
            @Override
            public void appendEntry(AuditEntry entry) {
            }

            @Override
            public void appendBatch(List<AuditEntry> entries) {
            }

            @Override
            public List<AuditEntry> getEntries(String conversationId, int skip, int limit) {
                return List.of();
            }

            @Override
            public List<AuditEntry> getEntriesByAgent(String agentId, Integer agentVersion, int skip, int limit) {
                return List.of();
            }

            @Override
            public long countByConversation(String conversationId) {
                return 0;
            }

            @Override
            public List<AuditEntry> getEntriesByUserId(String userId, int skip, int limit) {
                return List.of();
            }

            @Override
            public long pseudonymizeByUserId(String userId, String pseudonym) {
                return 0;
            }
        };

        assertEquals(AuditEntry.UNSEQUENCED, storeWithoutSequenceSupport.maxSequence("conv-a"),
                "0 would read as 'position 0 is taken' and silently shift the whole chain");
        assertFalse(storeWithoutSequenceSupport.supportsSequence(),
                "and such a store must not be handed sequences at all");
    }

    /**
     * The fallback path: a store that cannot answer {@link IAuditStore#maxSequence}
     * (it returns {@code UNSEQUENCED}) is seeded from the row count, which is what
     * the ledger did for every store before finding 03.
     */
    @Test
    @DisplayName("the sequence continues from the row count when the store cannot report a max")
    void sequenceIsSeededFromTheStore() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-a")).thenReturn(7L);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        assertEquals(7L, written.getFirst().sequence());
    }

    /**
     * Finding 03. {@code countByConversation} counts rows that landed, which stops
     * matching the next free position the moment one was handed out and never
     * stored. Sequences 0-9 issued with 3 and 5 dead-lettered leaves a count of 8
     * while the next free position is 10 — so a restart (or a second cluster node)
     * seeded from the count re-issues 8 and 9, and {@code /auditstore/verify}
     * grades a duplicate exactly like a deletion: {@code BROKEN}. Seeding from
     * {@code max + 1} cannot do that, and unlike the in-memory "undelivered" pin it
     * survives the process.
     */
    @Test
    @DisplayName("the sequence is seeded from max+1, so a dead-lettered gap is never re-issued")
    void sequenceIsSeededFromMaxNotCount() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-a")).thenReturn(8L); // 3 and 5 never landed
        when(auditStore.maxSequence("conv-a")).thenReturn(9L);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        assertEquals(10L, written.getFirst().sequence(),
                "the chain must resume past the highest stored position, not at the row count");
    }

    /**
     * A store that drops the field must not be handed a sequence — the signature
     * covers it, so every one of its rows would read as tampered.
     */
    @Test
    @DisplayName("a store that cannot persist the sequence gets unsequenced entries")
    void storeWithoutSequenceSupportGetsUnsequencedEntries() {
        when(auditStore.supportsSequence()).thenReturn(false);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        assertEquals(AuditEntry.UNSEQUENCED, written.getFirst().sequence());
        verify(auditStore, never()).countByConversation(anyString());
    }

    /**
     * The pre-warm is an optimisation, not the seed's only chance.
     * <p>
     * It exists to move the store round trip off the sequence lock, and it
     * deliberately swallows a failure — but the entry still has to get a real chain
     * position, so the assignment path re-seeds inline when the pre-warm left no
     * counter behind. Losing that fallback would silently downgrade the first entry
     * of every conversation that raced a store blip to {@code UNSEQUENCED}, and an
     * unsequenced entry does not participate in the chain the verifier checks.
     * <p>
     * Both calls are on the caller's thread and the seed is resolved outside the
     * {@code computeIfAbsent} mapping function, so a failing pre-warm costs one
     * extra read and nothing else.
     */
    @Test
    @DisplayName("a pre-warm that fails is retried inline, so the entry still gets its chain position")
    void aFailedPrewarmIsRecoveredByTheInlineSeed() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.maxSequence("conv-a"))
                .thenThrow(new RuntimeException("connection reset"))
                .thenReturn(4L);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        // Asserted before the flush on purpose: the flush does a maxSequence read of
        // its own to detect a second replica allocating for this conversation, and
        // the two reads asserted here are the failed pre-warm and the inline retry.
        verify(auditStore, times(2)).maxSequence("conv-a");

        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        assertEquals(5L, written.getFirst().sequence(),
                "the inline seed must resume from max+1 exactly as the pre-warm would have");
    }

    /**
     * Sequence allocation is not cluster-safe — seeding reads {@code MAX(sequence)}
     * instead of atomically reserving a position, so a multi-replica deployment
     * without conversation affinity produces duplicate positions and
     * {@code /auditstore/verify} grades those conversations {@code BROKEN}. The
     * atomic allocator that would remove the limitation is deliberately deferred
     * (see {@code IAuditStore.maxSequence}), so the condition must at least be
     * <em>operationally detectable</em> rather than silent until verify time.
     * <p>
     * Here this node seeds from an empty store and hands out position 0, so its
     * next free position is 1 — but once the batch has landed the store reports 4.
     * Positions 1-4 were written by another replica serving the same conversation,
     * so every position this node still has to hand out is already taken.
     */
    @Test
    @DisplayName("a chain position allocated by another replica is warned about and counted")
    void aForeignChainPositionIsDetectedAndCounted() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.maxSequence("conv-a"))
                .thenReturn(AuditEntry.UNSEQUENCED)
                .thenReturn(4L);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        assertEquals(1.0, meterRegistry.counter("eddi_audit_sequence_collisions_total").count(),
                "an operator running multi-replica without conversation affinity has to see this in metrics, "
                        + "not discover it as a BROKEN verdict at verify time");

        // And the chain continues past the foreign rows rather than re-issuing
        // positions that are already taken.
        service.submit(entry("id-2", "conv-a", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore, times(2)).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> secondBatch = (List<AuditEntry>) captor.getValue();
        assertEquals(5L, secondBatch.getFirst().sequence(),
                "the next entry must resume above the foreign rows, not duplicate them");
    }

    /**
     * The other half: the detector must stay silent for the deployment everyone
     * actually runs. A single writer's own rows are always at or below the position
     * it last handed out, so the healthy path never touches the counter — a
     * detector that cried wolf every flush would be worse than none.
     */
    @Test
    @DisplayName("a node's own stored rows are not reported as a foreign chain position")
    void theSingleWriterPathReportsNoCollision() {
        when(auditStore.supportsSequence()).thenReturn(true);
        // Empty at seed time; afterwards the store holds exactly the position this
        // node just wrote.
        when(auditStore.maxSequence("conv-a"))
                .thenReturn(AuditEntry.UNSEQUENCED)
                .thenReturn(0L);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        assertEquals(0.0, meterRegistry.counter("eddi_audit_sequence_collisions_total").count(),
                "the rows this node wrote itself are not evidence of a second writer");
    }

    /**
     * Deferral is a promise that the next flush will take these entries, and the
     * bounded queue can break that promise.
     * <p>
     * A pass that runs out of wall clock hands its unoffered tail back to the queue
     * — correct, because the store accepted everything it was actually offered. But
     * the queue has a bound, and turns submitted while the flush was blocked can
     * have taken the space back. An entry that does not fit has already consumed
     * its chain position, so dropping it silently leaves a gap that
     * {@code /auditstore/verify} grades exactly like a deletion. It has to reach
     * the dead-letter sink instead, which is the one place abandoned evidence is
     * recoverable and attributable from.
     * <p>
     * The sleep is what makes the outcome certain rather than uncertain: after
     * blocking for longer than the whole budget the deadline has provably passed,
     * so the pass provably defers. Same shape as
     * {@code aStoreSlowerThanTheBudgetStillEscalatesItsBacklog}, which is the only
     * seam the budget offers.
     */
    @Test
    @DisplayName("a deferred entry that no longer fits on the queue is dead-lettered, not dropped")
    void aDeferredEntryThatDoesNotFitReachesTheSink(@TempDir Path tempDir) throws IOException {
        when(auditStore.supportsSequence()).thenReturn(true);
        Path sink = tempDir.resolve("eddi-audit-deadletter.jsonl");
        // A queue of exactly 2, so refilling it during the flush leaves the deferred
        // entry nowhere to go.
        service = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 2, sink.toString());
        service.init();

        doThrow(new RuntimeException("batch aborted")).when(auditStore).appendBatch(anyList());
        var firstCall = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstCall.compareAndSet(true, false)) {
                // Two turns land while the writer is blocked on this insert and refill
                // the queue the flush had just drained.
                service.submit(entry("id-2", "conv1", "agent-1"));
                service.submit(entry("id-3", "conv1", "agent-1"));
                Thread.sleep(AuditLedgerService.ENTRY_RETRY_BUDGET.toMillis() + 250);
            }
            return null;
        }).when(auditStore).appendEntry(any());

        service.submit(entry("id-0", "conv1", "agent-1"));
        service.submit(entry("id-1", "conv1", "agent-1"));

        service.flush();

        assertEquals(2, service.getQueueSize(), "the two turns that arrived mid-flush keep their slots");
        assertTrue(service.undeliveredSequences("conv1").contains(1L),
                "the deferred entry's chain position must be recorded as undelivered, or its gap is graded BROKEN");
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "exactly the one entry that did not fit is counted — the store accepted what it was offered");
        String sinkContents = Files.readString(sink);
        assertTrue(sinkContents.contains("\"id\":\"id-1\""),
                "the abandoned entry has to be recoverable from the sink, not merely counted: " + sinkContents);
        assertFalse(sinkContents.contains("\"id\":\"id-0\""),
                "the entry the store accepted must not also be dead-lettered");
    }

    @Test
    @DisplayName("a signed entry verifies through the service")
    void signedEntryVerifies() {
        when(auditStore.supportsSequence()).thenReturn(true);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        AuditEntry stored = written.getFirst();

        assertTrue(service.isSigningEnabled());
        assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(stored));
        assertEquals(AuditVerificationStatus.INVALID, service.verifyEntry(stored.withEnvironment("TAMPERED")));
        assertEquals(AuditVerificationStatus.UNSIGNED, service.verifyEntry(stored.withHmac(null)));
    }

    /**
     * G17 end to end: a GDPR erasure rewrites userId on a stored entry, and the
     * entry must still verify — under v1/v2 it did not, so every routine erasure
     * manufactured rows indistinguishable from tampered ones.
     */
    @Test
    @DisplayName("a pseudonymised stored entry still verifies")
    void pseudonymisedStoredEntryStillVerifies() {
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        AuditEntry stored = written.getFirst();

        AuditEntry pseudonymised = stored.withUserId(AuditHmac.pseudonymFor("user1"));
        assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(pseudonymised));
    }

    /**
     * The test factory used to pass the bare relative
     * {@code "eddi-audit-deadletter.jsonl"}, which resolves against the process CWD
     * — the repository root under Maven — so any dropped batch in any unit test
     * wrote a file into the source tree. {@code mvn clean} does not remove it, so
     * {@code .gitignore} carried an entry to hide it instead. The CDI constructor
     * has always defaulted to an absolute path; only the test path was relative.
     * <p>
     * The uniqueness assertion is the other half. A single fixed name under
     * {@code java.io.tmpdir} trades one file per checkout for one file per
     * <em>machine</em>, so two runs on the same host — two git worktrees, or two CI
     * executors sharing {@code /tmp} — would append to the same sink.
     */
    @Test
    @DisplayName("the test dead-letter sink is absolute, outside the source tree and unique per JVM")
    void testDeadLetterPathIsOutsideTheSourceTree() {
        Path path = Path.of(AuditLedgerService.defaultTestDeadLetterPath());

        assertTrue(path.isAbsolute(),
                "a relative dead-letter path resolves against the CWD, which is the project root: " + path);
        assertFalse(path.startsWith(Path.of("").toAbsolutePath()),
                "the test dead-letter sink must not be written inside the project tree: " + path);
        assertTrue(path.getFileName().toString().contains(String.valueOf(ProcessHandle.current().pid())),
                "the test dead-letter sink must be unique per JVM, or two concurrent test runs on one host append"
                        + " to the same file: " + path);
    }

    /**
     * {@code java.io.tmpdir} is an ordinary writable system property, not a
     * guarantee. Set it to a relative value — or an empty one — and
     * {@code Path.of(tmpdir, name)} silently produces a path that resolves against
     * the process working directory, which under Maven is the repository root: the
     * source-tree artifact this branch deleted, recreated by a JVM flag rather than
     * by a code change. It has to fail loudly instead.
     */
    @Test
    @DisplayName("a relative java.io.tmpdir is rejected rather than resolved against the working directory")
    void relativeTempDirectoryIsRejected() {
        String original = System.getProperty("java.io.tmpdir");
        try {
            for (String relative : List.of("tmp", "", "./tmp")) {
                System.setProperty("java.io.tmpdir", relative);
                var thrown = assertThrows(IllegalStateException.class, AuditLedgerService::defaultTestDeadLetterPath,
                        "java.io.tmpdir='" + relative + "' is relative, so the sink would land under the process CWD"
                                + " — the repository root under Maven — instead of the temp directory");
                assertTrue(thrown.getMessage().contains("java.io.tmpdir"),
                        "the failure must name the property an operator has to fix: " + thrown.getMessage());
            }
        } finally {
            if (original == null) {
                System.clearProperty("java.io.tmpdir");
            } else {
                System.setProperty("java.io.tmpdir", original);
            }
        }
    }

    /**
     * The sink {@link #testDeadLetterPathIsOutsideTheSourceTree} grades is the sink
     * the factory actually wires in — asserted end to end, by making the store fail
     * until the batch is abandoned and then looking for the file.
     * <p>
     * That test reads {@code defaultTestDeadLetterPath()} directly, so it says
     * nothing about whether {@code createForTesting} still calls it. Put the old
     * relative {@code "eddi-audit-deadletter.jsonl"} literal back in the factory
     * and it stays green while every unit run drops a file into the repository root
     * again — the precise regression this branch removed the {@code .gitignore}
     * entry for.
     * <p>
     * The source-tree half is a before/after comparison rather than "the file does
     * not exist". {@code eddi-audit-deadletter.jsonl} was generated and gitignored
     * for years, so an existing checkout can easily still have one lying in the
     * repository root — and a bare existence assertion would then fail on a tree
     * that is entirely correct, while saying nothing about what THIS invocation
     * wrote. What has to hold is that this call left it untouched.
     */
    @Test
    @DisplayName("the default test factory dead-letters outside the source tree, not into the repository root")
    void defaultTestFactoryWritesItsDeadLettersOutsideTheSourceTree() throws IOException {
        Path sink = Path.of(AuditLedgerService.defaultTestDeadLetterPath());
        Path repositoryRootSink = Path.of("").toAbsolutePath().resolve("eddi-audit-deadletter.jsonl");
        boolean rootSinkExisted = Files.exists(repositoryRootSink);
        long rootSinkSize = rootSinkExisted ? Files.size(repositoryRootSink) : -1L;
        Files.deleteIfExists(sink);

        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 10);
        svc.init();
        // storeIsDown, not appendBatch alone: main added a per-entry retry pass that
        // runs before the dead-letter drop, so a mock whose appendEntry still
        // succeeds stores the entries individually and nothing ever reaches the sink.
        storeIsDown();

        svc.submit(entry("id-1", "conv-default-sink-probe", "agent-1"));
        svc.flush(); // failure 1 — re-queued
        svc.flush(); // failure 2 — re-queued
        svc.flush(); // failure 3 — abandoned to the dead-letter sink

        try {
            String sourceTreeMessage = "createForTesting dead-lettered into the source tree at " + repositoryRootSink
                    + ". A relative dead-letter path resolves against the process CWD, which under Maven is the"
                    + " repository root; mvn clean does not remove the file, which is why .gitignore used to hide"
                    + " it. It must go through defaultTestDeadLetterPath().";
            assertEquals(rootSinkExisted, Files.exists(repositoryRootSink), sourceTreeMessage);
            if (rootSinkExisted) {
                assertEquals(rootSinkSize, Files.size(repositoryRootSink), sourceTreeMessage
                        + " (a pre-existing file from an earlier checkout grew during this test, so this run"
                        + " appended to it)");
            }
            assertTrue(Files.isRegularFile(sink),
                    "nothing was written to " + sink + ", so the factory is no longer wiring"
                            + " defaultTestDeadLetterPath() into the service");
            assertEquals(List.of("conv-default-sink-probe"), deadLetteredConversations(sink),
                    "the abandoned batch must be the one line in the sink");
        } finally {
            Files.deleteIfExists(sink);
        }
    }

    /**
     * The overload that lets a caller name the sink has to honour it. Nothing else
     * passes a path — every other test takes the default — so a factory that
     * accepted the argument and then handed the constructor
     * {@code defaultTestDeadLetterPath()} anyway would look correct everywhere
     * except in a test that reads its own dead letters back, where it would
     * silently read another test's.
     */
    @Test
    @DisplayName("a dead-lettered batch is written to the sink the factory was given")
    void deadLetteredBatchLandsAtTheCallerSuppliedPath(@TempDir Path tempDir) throws IOException {
        Path sink = tempDir.resolve("nested").resolve("audit-dead-letters.jsonl");
        Files.createDirectories(sink.getParent());
        // Other tests in this class dead-letter through the default sink, and the
        // JVM is shared, so clear it first: "the default was not written" has to
        // mean this test did not write it.
        Path defaultSink = Path.of(AuditLedgerService.defaultTestDeadLetterPath());
        Files.deleteIfExists(defaultSink);

        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 10, sink.toString());
        svc.init();
        // storeIsDown, not appendBatch alone: main added a per-entry retry pass that
        // runs before the dead-letter drop, so a mock whose appendEntry still
        // succeeds stores the entries individually and nothing ever reaches the sink.
        storeIsDown();

        svc.submit(entry("id-1", "conv-configured-sink-probe", "agent-1"));
        svc.flush();
        svc.flush();
        svc.flush();

        assertTrue(Files.isRegularFile(sink),
                "the batch was not dead-lettered to the path the factory was given (" + sink + "), so the"
                        + " deadLetterPath argument is being ignored");
        assertEquals(List.of("conv-configured-sink-probe"), deadLetteredConversations(sink),
                "the sink must hold exactly the abandoned batch");
        assertFalse(Files.exists(defaultSink),
                "a caller-supplied sink must replace the default, not be written alongside it");
    }

    /** The {@code conversationId} of each record in a dead-letter JSONL file. */
    private static List<String> deadLetteredConversations(Path sink) throws IOException {
        var mapper = new ObjectMapper();
        List<String> conversations = new ArrayList<>();
        for (String line : Files.readAllLines(sink)) {
            conversations.add(mapper.readTree(line).get("conversationId").asText());
        }
        return conversations;
    }

    // ==================== shutdown budget (finding r1) ====================

    /**
     * The per-entry retry must not run during shutdown.
     * <p>
     * {@code shutdown()} runs two flushes around a 5s {@code awaitTermination}
     * before {@code drainQueueToDeadLetter}, and the shipped Kubernetes manifests
     * set {@code terminationGracePeriodSeconds: 30}. With the per-entry pass
     * calling an unreachable store once per entry — each call costing a
     * connection-acquisition timeout rather than a prompt refusal — a rolling
     * deploy during a database failover was SIGKILLed inside this loop, so the
     * drain that finding 06 added never ran and every queued entry was lost with
     * neither a dead-letter record nor a dropped-counter increment. That is the
     * exact scenario the drain exists for.
     * <p>
     * There is nothing left to protect at that point: a failure after
     * {@code shutdown()} has begun has no later attempt, so the entries belong in
     * the sink immediately. The store here refuses the batch instantly and blocks
     * on every single-entry call — which is how an unreachable store behaves, and
     * how a mock that fails instantly does not.
     */
    @Test
    @DisplayName("shutdown — the per-entry retry does not run, so a blocking store cannot outlast the grace period")
    void shutdownDoesNotRetryEntryByEntryAgainstABlockingStore() {
        service = createService(true, null);
        doThrow(new RuntimeException("db error")).when(auditStore).appendBatch(anyList());
        doAnswer(invocation -> {
            // One connection-acquisition timeout, scaled down so the test is quick.
            Thread.sleep(400);
            throw new RuntimeException("db unreachable");
        }).when(auditStore).appendEntry(any());

        int submitted = AuditLedgerService.MAX_CONSECUTIVE_ENTRY_FAILURES + 5;
        for (int i = 0; i < submitted; i++) {
            service.submit(entry("id-" + i, "conv-" + i, "agent-1"));
        }

        long startedAt = System.nanoTime();
        service.shutdown();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        verify(auditStore, never()).appendEntry(any());
        assertTrue(elapsedMs < 1_000,
                "shutdown must not spend its grace period retrying a dead store one entry at a time, took " + elapsedMs + "ms");
        assertEquals(0, service.getQueueSize(), "nothing may be left in a queue that will never be drained");
        assertEquals((double) submitted, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "every abandoned entry must be counted and dead-lettered, not left to the SIGKILL");
    }

    // ==================== dead-letter sink probe (finding r8) ====================

    /**
     * Finding 26 asked for the sink's unavailability to be discovered at startup
     * rather than during the incident that needs it. Checking that the directory
     * <em>exists</em> does not do that: a root-owned mount, a
     * {@code readOnlyRootFilesystem} pod with no volume at the path, and a
     * {@code dead-letter-path} that names a directory all pass an existence check
     * and then fail at {@code Files.write} into the same swallowed catch. So the
     * check now performs the operation it is checking for.
     */
    @Test
    @DisplayName("startup check — a sink path that cannot be written is reported, not merely resolved")
    void deadLetterSinkCheckReportsAPathThatCannotBeWritten(@TempDir Path tempDir) throws IOException {
        // The classic misconfiguration: the path names an existing directory. Its
        // parent exists and is writable, so an existence check sees nothing wrong.
        Path sinkThatIsADirectory = Files.createDirectory(tempDir.resolve("eddi-audit-deadletter.jsonl"));

        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry,
                AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE, sinkThatIsADirectory.toString());

        assertTrue(Files.isDirectory(sinkThatIsADirectory.getParent()), "precondition: the parent directory exists");
        assertFalse(svc.checkDeadLetterSinkReachable(),
                "an unwritable sink must be reported at startup, not discovered during the incident");
    }

    @Test
    @DisplayName("startup check — a writable sink passes and leaves no empty probe file behind")
    void deadLetterSinkCheckLeavesNoProbeFile(@TempDir Path tempDir) {
        Path sink = tempDir.resolve("nested").resolve("eddi-audit-deadletter.jsonl");

        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry,
                AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE, sink.toString());

        assertTrue(svc.checkDeadLetterSinkReachable());
        assertTrue(Files.isDirectory(sink.getParent()), "the check creates the directory it needs");
        assertFalse(Files.exists(sink), "an empty dead-letter file reads as an incident to anyone watching for one");
    }

    /**
     * Finding f2-03. "Delete the file only if the probe created it" has to fail in
     * one direction only, and {@code Files.exists} does not: it answers false both
     * for a missing file and for any I/O error while reading attributes. Where the
     * stat fails but the append-open succeeds — a Windows ACL granting
     * {@code FILE_APPEND_DATA} without {@code FILE_READ_ATTRIBUTES}, a transient
     * failure on a network mount — the probe deleted a real dead-letter file, which
     * is the one place abandoned audit evidence is recoverable from.
     */
    @Test
    @DisplayName("startup check — an existing dead-letter file survives a stat the filesystem cannot answer")
    void deadLetterSinkCheckKeepsAFileWhoseExistenceCannotBeDetermined(@TempDir Path tempDir) throws IOException {
        Path sink = tempDir.resolve("eddi-audit-deadletter.jsonl");
        String abandoned = "{\"id\":\"abandoned-1\"}\n";
        Files.writeString(sink, abandoned);

        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry,
                AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE, sink.toString());

        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            // Exactly what an unanswerable stat looks like from the caller's side:
            // BOTH answers are false, because neither can be proven. Stubbing only
            // exists() would pin "the probe does not call Files.exists" rather than
            // the behaviour named above, and would go green again if the guard were
            // rewritten around some other predicate that also collapses on error.
            files.when(() -> Files.exists(any(Path.class))).thenReturn(false);
            files.when(() -> Files.notExists(any(Path.class))).thenReturn(false);

            assertTrue(svc.checkDeadLetterSinkReachable(),
                    "the append-open still succeeds, so the sink really is usable");
        }

        assertTrue(Files.exists(sink),
                "an undeterminable stat must never be read as 'the probe created this file'");
        assertEquals(abandoned, Files.readString(sink), "and the abandoned entries must still be there");
    }

    // ==================== a sink path with no parent directory
    // ====================

    /**
     * Both places that touch the sink guard {@code getParent()} against null, and
     * null is not an impossible state: it is exactly what
     * {@code eddi.audit.dead-letter-path} produces when an operator points it at a
     * filesystem root — {@code "/"} in a container, {@code "C:\"} on a developer
     * machine — which is an ordinary fat-fingered config value, not a state the
     * runtime cannot reach.
     * <p>
     * What the guard buys is <em>which failure gets reported</em>. The verdict is
     * false either way, because an unguarded {@code Files.isDirectory(null)} throws
     * into the same catch that the append-open's own failure lands in, so
     * {@code assertFalse} on its own passes with the guard deleted. The difference
     * is the sentence: with the guard the operator is handed the filesystem's
     * answer about the path they configured, and without it they are handed a null
     * dereference inside EDDI — a bug report instead of a fix.
     */
    @Test
    @DisplayName("startup check — a sink path with no parent reports what the filesystem said, not a null dereference")
    void deadLetterSinkCheckReportsTheFilesystemsAnswerForAPathWithNoParent() {
        String root = Path.of("/").toAbsolutePath().toString();
        assertNull(Path.of(root).toAbsolutePath().getParent(),
                "precondition: a filesystem root has no parent directory to create");

        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry,
                AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE, root);

        var reported = new ArrayList<String>();
        boolean reachable = withLedgerLogCaptured(reported, svc::checkDeadLetterSinkReachable);

        assertFalse(reachable, "a directory cannot be opened for append, so the sink is not usable");
        assertEquals(1, reported.size(), "one verdict, one line: " + reported);
        // The line carries the path twice: once as the sink that was checked, and
        // once as the reason, because the filesystem's exception for a refused open
        // names the file it refused. An unguarded Files.isDirectory(null) throws
        // before the open is ever attempted, so the reason becomes a null-dereference
        // message from inside EDDI and the path appears exactly once.
        assertEquals(2, occurrencesOf(root, reported.getFirst()),
                "the reason has to be the filesystem's own answer about the configured path — a null dereference "
                        + "inside the probe tells the operator to file a bug instead of fixing the path; saw: " + reported);
    }

    /**
     * The same null parent on the write path, reached the way an operator reaches
     * it: the store is down, the batch is abandoned, and the sink it is abandoned
     * to cannot be written either.
     * <p>
     * Two things have to survive that. The ledger's own account of what it dropped
     * — {@code undeliveredSequences} is what keeps a self-inflicted gap graded
     * {@code INCOMPLETE} rather than {@code BROKEN} — is recorded before the write
     * is attempted and must not be lost with it. And the error line has to name the
     * sink the filesystem refused, not a null dereference from
     * {@code Files.createDirectories(null)}: this line is the operator's only
     * notice that audit evidence was lost outright.
     */
    @Test
    @DisplayName("dead-letter write — a sink path with no parent still records the gap and names the sink that refused")
    void deadLetterWriteReportsTheFilesystemsAnswerForAPathWithNoParent() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-1")).thenReturn(0L);
        String root = Path.of("/").toAbsolutePath().toString();

        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry,
                AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE, root);
        svc.init();
        storeIsDown();

        svc.submit(entry("id-1", "conv-1", "agent-1")); // sequence 0

        var reported = new ArrayList<String>();
        withLedgerLogCaptured(reported, () -> {
            svc.flush(); // failure 1 — requeue
            svc.flush(); // failure 2 — requeue
            svc.flush(); // failure 3 — dead-letter
            return null;
        });

        assertEquals(0, svc.getQueueSize(), "the batch was abandoned, not left on the queue");
        assertEquals(Set.of(0L), svc.undeliveredSequences("conv-1"),
                "an unwritable sink must not also cost the ledger its record of which position it dropped");

        String failure = reported.stream().filter(line -> line.contains("Failed to write to dead-letter log"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the one notice that evidence was lost outright: " + reported));
        // The only argument this line carries is the failure's own message, and the
        // filesystem's exception for a refused write names the file. A null
        // dereference from createDirectories(null) names nothing the operator can act
        // on, so the path is absent altogether.
        assertEquals(1, occurrencesOf(root, failure),
                "the operator has to be told which sink the filesystem refused; a null dereference from "
                        + "createDirectories(null) names nothing they can act on; saw: " + failure);
    }

    /** How many times {@code needle} occurs in {@code haystack}. */
    private static int occurrencesOf(String needle, String haystack) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * Runs {@code action} with a handler attached to {@link AuditLedgerService}'s
     * logger, collecting every WARN and ERROR into {@code records}.
     * <p>
     * {@code src/test/resources/logging.properties} silences the whole
     * {@code ai.labs.eddi} namespace, so the level is raised for the duration and
     * the parent handlers are detached — which also keeps the deliberately alarming
     * lines out of the surefire output. Everything is put back afterwards: the
     * logger is process-wide.
     */
    private <T> T withLedgerLogCaptured(List<String> records, Supplier<T> action) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    // Kept whole: whether the backend pre-formats a printf/MessageFormat
                    // call or leaves the arguments beside the pattern is a property of
                    // the JBoss Logging backend on the classpath, not of what was logged.
                    records.add(record.getMessage() + " " + Arrays.toString(record.getParameters()));
                }
            }

            @Override
            public void flush() {
                // nothing is buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        Logger ledgerLogger = Logger.getLogger(AuditLedgerService.class.getName());
        Level previousLevel = ledgerLogger.getLevel();
        boolean previousUseParentHandlers = ledgerLogger.getUseParentHandlers();
        ledgerLogger.setLevel(Level.ALL);
        ledgerLogger.setUseParentHandlers(false);
        ledgerLogger.addHandler(handler);
        try {
            return action.get();
        } finally {
            ledgerLogger.removeHandler(handler);
            ledgerLogger.setLevel(previousLevel);
            ledgerLogger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    // ==================== dead-letter degradation (finding r12)
    // ====================

    /**
     * The entry most likely to reach the serialization catch is the one carrying a
     * payload Jackson cannot render — which is also the one the store most likely
     * rejected, so it is precisely the entry an operator needs in order to
     * attribute the chain gap and replay it. Collapsing the whole record to
     * {@code {"error":"serialization_failed"}} threw away its id, its sequence and
     * its conversation along with the payload that caused the failure, and a gap
     * with no attribution is graded {@code BROKEN} rather than {@code INCOMPLETE}.
     */
    @Test
    @DisplayName("serializeDeadLetterEntry — an unserialisable payload degrades to metadata, it does not erase the record")
    void deadLetterRecordDegradesToMetadataWhenThePayloadCannotBeSerialized() {
        service = createService(true, null);
        // normalizeMap deliberately keeps a value it cannot convert "as-is", so an
        // object Jackson refuses to write really does reach the sink.
        var e = new AuditEntry("dl-9", "conv-9", "agent-1", 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("payload", new Unserialisable()), null, null, null, List.of("a1"), 0.0,
                Instant.parse("2026-01-02T03:04:05Z"), "v4:abcdef", "sig-9", 13L);

        String json = service.serializeDeadLetterEntry(e, "audit_dead_letter");

        assertTrue(json.contains("\"id\":\"dl-9\""), json);
        assertTrue(json.contains("\"sequence\":13"), json);
        assertTrue(json.contains("\"conversationId\":\"conv-9\""), json);
        assertTrue(json.contains("\"hmac\":\"v4:abcdef\""), json);
        assertTrue(json.contains("payloadError"), "the operator has to be told the payload is missing: " + json);
    }

    /** A bean whose only property throws, so Jackson cannot serialize it. */
    public static class Unserialisable {
        public String getBoom() {
            throw new IllegalStateException("this value cannot be rendered");
        }
    }

    // ==================== entry id stamping (finding r13) ====================

    /**
     * {@code ON CONFLICT (id) DO NOTHING} and MongoDB's tolerated {@code E11000}
     * are what make the per-entry retry safe, and both key on the entry id. While
     * the id was minted inside {@code PostgresAuditStore.setEntryParams} instead, a
     * null-id entry received a different primary key on the batch attempt and on
     * the retry, so a row the aborted batch had already committed was stored a
     * second time — and the verifier grades a duplicate sequence exactly like a
     * deletion.
     */
    @Test
    @DisplayName("submit — a null entry id is stamped once, so a retry cannot store the same entry twice")
    void aNullEntryIdIsStampedOnceSoARetryCannotDuplicateTheRow() {
        service = createService(true, null);
        doThrow(new RuntimeException("batch aborted")).when(auditStore).appendBatch(anyList());

        var withoutId = new AuditEntry(null, "conv-1", "agent-1", 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L, Map.of("text", "hi"), null, null, null,
                List.of("a1"), 0.0, Instant.now(), null, null);
        service.submit(withoutId);
        service.flush(); // the batch fails, the per-entry retry succeeds

        var batched = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(batched.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> batch = (List<AuditEntry>) batched.getValue();
        var retried = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditStore).appendEntry(retried.capture());

        assertNotNull(batch.getFirst().id(), "the ledger must not hand the store an entry with no conflict key");
        assertEquals(batch.getFirst().id(), retried.getValue().id(),
                "batch and retry must present the same id, or ON CONFLICT (id) DO NOTHING stores the entry twice");
    }
}
